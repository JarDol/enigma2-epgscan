# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# SatWatch - skanowanie transponderow w tle (eComponentScan), po jednym TP.
#
# Zasady:
#  * tuner: najwyzszy wolny tuner DVB-S z dana pozycja, przy zachowaniu rezerwy
#    (po zajeciu zostaje co najmniej `reserve` wolnych),
#  * gdy w trakcie skanu wolnych tunerow zrobi sie mniej niz rezerwa (ogladanie,
#    nagranie, stream) - skan TP jest przerywany i ponawiany pozniej,
#  * skan bez flagi scanRemoveServices: nic nie jest usuwane przez enigme;
#    nieaktualne serwisy usuwa wtyczka (tylko z TP zeskanowanych poprawnie).
import time

from enigma import eComponentScan, eDVBFrontendParametersSatellite, eTimer

from ..i18n import _
from . import core

TP_TIMEOUT = 240        # maks. czas na jeden TP (strojenie Unicable + SDT) [s]
WAIT_STEP = 30          # co ile sprawdzac, czy zwolnil sie tuner [s]
WAIT_MAX = 1800         # tyle lacznie czekamy na wolny tuner; potem skan jest przerywany [s]
GAP = 3                 # przerwa miedzy TP [s] (Unicable nie lubi strojenia seriami)
POLL = 2                # kontrola rezerwy w trakcie skanu [s]
MAX_PREEMPT = 5         # ile razy TP moze byc oddany uzytkownikowi, zanim go pominiemy

LOG = None     # None = core.out_dir()/satwatch.log (wybrany katalog lub /etc/enigma2/satwatch)
LOG_MAX = 200 * 1024    # po przekroczeniu: satwatch.log -> .1 -> .2 ... (rotacja)
LOG_KEEP = 4            # ile starszych plikow zachowac (lacznie max ok. 1 MB)


def log_path():
    import os
    return LOG or os.path.join(core.out_dir(), "satwatch.log")


def rotate_log(path):
    import os
    for i in range(LOG_KEEP, 0, -1):
        src = path if i == 1 else "%s.%d" % (path, i - 1)
        dst = "%s.%d" % (path, i)
        if os.path.exists(src):
            os.rename(src, dst)


def log(msg):
    import os
    line = "%s %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg)
    print("[SatWatch] " + msg)
    try:
        path = log_path()
        d = os.path.dirname(path)
        if not os.path.isdir(d):
            os.makedirs(d)
        if os.path.exists(path) and os.path.getsize(path) > LOG_MAX:
            rotate_log(path)
        with open(path, "a") as f:
            f.write(line + "\n")
    except Exception:
        pass


def read_log(lines=200):
    """Ostatnie linie logu (z poprzednim plikiem, jesli biezacy krotki)."""
    import os
    out = []
    path = log_path()
    for p in ("%s.1" % path, path):
        try:
            with open(p, "rb") as f:
                out += f.read().decode("utf-8", "replace").splitlines()
        except Exception:
            pass
    return "\n".join(out[-lines:]) if out else _("Log jest pusty.")


def root2gold(root):
    if root < 0 or root > 0x3ffff:
        return 0
    x = 1
    for g in range(0x3ffff):
        if root == x:
            return g
        x = (((x ^ (x >> 7)) & 1) << 17) | (x >> 1)
    return 0


def orbpos(pos):
    pos = int(pos)
    return pos + 3600 if pos < 0 else pos


def sat_param(tp):
    """dict z atrybutami satellites.xml (+ 'position') -> eDVBFrontendParametersSatellite.
    Wartosci domyslne jak w eDVBDB::readSatellites."""
    def g(k, d):
        try:
            return int(tp.get(k, d))
        except (TypeError, ValueError):
            return d
    p = eDVBFrontendParametersSatellite()
    p.frequency = g("frequency", 0)
    p.symbol_rate = g("symbol_rate", 0)
    p.polarisation = g("polarization", 0)
    p.fec = g("fec_inner", 0)
    p.inversion = g("inversion", 2)
    p.orbital_position = orbpos(tp["position"])
    p.system = g("system", 0)
    p.modulation = g("modulation", 1)
    p.rolloff = g("rolloff", 0)
    p.pilot = g("pilot", 2)
    p.is_id = g("is_id", -1)
    mode, code = g("pls_mode", 1), g("pls_code", 0)
    if mode == 0:  # root -> gold
        mode, code = 1, root2gold(code)
    p.pls_mode = mode
    p.pls_code = code
    p.t2mi_plp_id = g("t2mi_plp_id", -1)
    p.t2mi_pid = g("t2mi_pid", 4096)
    return p


def tuner_name(slot):
    return chr(ord("A") + slot) if slot is not None and slot >= 0 else "?"


def _nim_info():
    from Components.NimManager import nimmanager
    return nimmanager


def eligible_slots(pos):
    """Tunery DVB-S, ktore maja dana pozycje w konfiguracji - kandydaci do skanu.
    Kolejnosc proby: najpierw tunery polaczone FBC (C..H, od konca), potem glowne
    (A, B) - glowne zostaja dla uzytkownika. Czy enigma da tuner, okazuje sie przy
    starcie skanu (allocateRawChannel); odmowa = proba nastepnego."""
    nm = _nim_info()
    op = orbpos(pos)
    roots, links = [], []
    for n in nm.nim_slots:
        try:
            if not n.isCompatible("DVB-S"):
                continue
            if op not in [x[0] for x in nm.getSatListForNim(n.slot)]:
                continue
            (links if n.isFBCLink() else roots).append(n.slot)
        except Exception:
            pass
    if not roots and not links:
        try:
            roots = list(nm.getNimListForSat(op))
        except Exception:
            pass
    return sorted(links, reverse=True) + sorted(roots, reverse=True)


def describe_tuners(session, pos):
    """Opis ukladu tunerow do logu (diagnostyka)."""
    nm = _nim_info()
    mask = use_mask(session)
    op = orbpos(pos)
    out = []
    for n in nm.nim_slots:
        try:
            role = "glowny FBC" if n.isFBCRoot() else ("polaczony FBC" if n.isFBCLink() else "zwykly")
            try:
                mode = n.config_mode
            except Exception:
                mode = "?"
            try:
                has = op in [x[0] for x in nm.getSatListForNim(n.slot)]
            except Exception:
                has = False
            out.append("%s: %s, %s, %s, %s" % (tuner_name(n.slot), role, mode,
                                               "ma %s" % core.pos_str(pos) if has else "bez %s" % core.pos_str(pos),
                                               "ZAJETY" if mask & (1 << n.slot) else "wolny"))
        except Exception as e:
            out.append("%s: ? (%s)" % (getattr(n, "slot", "?"), e))
    return out


def all_slots():
    """Wszystkie wlaczone tunery DVB-S (z polaczonymi FBC) - do liczenia rezerwy."""
    from Components.NimManager import nimmanager
    out = []
    for n in nimmanager.nim_slots:
        try:
            if n.isCompatible("DVB-S") and n.isEnabled():
                out.append(n.slot)
        except Exception:
            pass
    return out


def use_mask(session):
    try:
        return session.screen["TunerInfo"].tuner_use_mask
    except Exception:
        return 0


class TPScanner(object):
    """
    Skanuje kolejno liste TP. on_progress(tekst), on_done(results, found_keys).
    results: lista dict TP + ok, status, found, tuner.
    found_keys: zbior (sid, ns, tsid, onid) znalezionych serwisow (wszystkie TP).
    """

    def __init__(self, session, tps, reserve=1, on_progress=None, on_done=None, wait_max=WAIT_MAX, prefer=None):
        self.session = session
        self.prefer = prefer          # preferowany tuner (slot) - pierwszy do proby, gdy wolny
        self.t0 = time.time()
        self.wait_max = int(wait_max)
        self.tps = list(tps)
        self.reserve = int(reserve)
        self.on_progress = on_progress
        self.on_done = on_done
        self.results = []
        self.found = set()
        self.idx = 0
        self.scan = None
        self.slot = None
        self.cur_found = set()
        self.waited = 0
        self.preempted = {}
        self.aborted = False
        self.finished = False
        self.t_start = 0
        self.stepTimer = eTimer()
        self.stepTimer.callback.append(self.step)
        self.doneTimer = eTimer()
        self.doneTimer.callback.append(self.scanFinished)
        self.wdTimer = eTimer()
        self.wdTimer.callback.append(self.scanTimeout)
        self.pollTimer = eTimer()
        self.pollTimer.callback.append(self.poll)

    # ---------------------------------------------------------------
    def progress(self, text, quiet=False, display=None):
        if not quiet:
            log(text)
        if self.on_progress:
            try:
                self.on_progress(text if display is None else display)
            except Exception:
                pass

    def start(self):
        self.t0 = time.time()
        log("Skan: start, %d TP, rezerwa %d, preferowany tuner %s" % (len(self.tps), self.reserve,
            tuner_name(self.prefer) if self.prefer is not None else "automatycznie"))
        if self.tps:
            try:
                for line in describe_tuners(self.session, self.tps[0]["position"]):
                    log("  tuner " + line)
                log("  kolejnosc prob: %s" % ", ".join(tuner_name(x) for x in self.order(eligible_slots(self.tps[0]["position"]))))
            except Exception as e:
                log("  opis tunerow: %s" % e)
        self.stepTimer.start(100, True)

    def giveUp(self, status):
        log("Skan: %s" % status)
        for tp in self.tps[self.idx:]:
            self.results.append(dict(tp, ok=False, status=status, found=0, tuner="-"))
        self.idx = len(self.tps)
        self.finish()

    def abort(self):
        if self.finished:
            return
        self.aborted = True
        self.stopScan()
        for tp in self.tps[self.idx:]:
            self.results.append(dict(tp, ok=False, status="przerwano", found=0, tuner="-"))
        self.idx = len(self.tps)
        self.finish()

    # ---------------------------------------------------------------
    def eta(self, display=False):
        """' (26%, zostalo ok. 4 min)' - postep i szacowany czas do konca."""
        n = len(self.tps)
        if not n:
            return ""
        pct = int(self.idx * 100 / n)
        if self.idx < 3:
            return " (%d%%)" % pct
        left = (time.time() - self.t0) / self.idx * (n - self.idx)
        template = _(" (%d%%, zostalo ok. %s)") if display else " (%d%%, zostalo ok. %s)"
        return template % (pct, ("%d min" % round(left / 60.0)) if left >= 90 else ("%d s" % left))

    def order(self, free):
        """Kolejnosc proby: preferowany (gdy wolny), potem pozostale od najwyzszego. Preferowany zajety -
        od razu inne, bez czekania."""
        out = sorted(free, reverse=True)
        if self.prefer in out:
            out.remove(self.prefer)
            out.insert(0, self.prefer)
        return out

    def free_slots(self, pos, exclude=None):
        """Wolne tunery do skanu tej pozycji."""
        mask = use_mask(self.session)
        return [s for s in eligible_slots(pos) if not (mask & (1 << s)) and s != exclude]

    def free_total(self, exclude=None):
        """Wszystkie wolne tunery (do rezerwy dla uzytkownika/streamingu)."""
        mask = use_mask(self.session)
        return [s for s in all_slots() if not (mask & (1 << s)) and s != exclude]

    def step(self):
        if self.aborted or self.finished:
            return
        if self.idx >= len(self.tps):
            self.finish()
            return
        tp = self.tps[self.idx]
        desc = "%d/%d%s %s" % (self.idx + 1, len(self.tps), self.eta(), core.tp_str(tp))
        display_desc = "%d/%d%s %s" % (self.idx + 1, len(self.tps), self.eta(display=True), core.tp_str(tp))
        if not eligible_slots(tp["position"]):
            # zaden tuner nie ma tej pozycji: od razu wszystkie TP tej pozycji, bez prob co 3 s
            pos = tp["position"]
            st = "brak tunera skonfigurowanego na %s" % core.pos_str(pos)
            rest = [t for t in self.tps[self.idx:] if t["position"] == pos]
            log("Skan: %s - pomijam %d TP tej pozycji (sprawdz satellites.xml i ustawienia tunerow)" % (st, len(rest)))
            for t in rest:
                self.results.append(dict(t, ok=False, status=st, found=0, tuner="-"))
            others = [t for t in self.tps[self.idx:] if t["position"] != pos]
            self.tps = self.tps[:self.idx] + rest + others
            self.idx += len(rest) - 1
            self.next()
            return
        free = self.free_slots(tp["position"])
        total = self.free_total()
        if not free or len(total) < 1 + self.reserve:
            if self.waited >= self.wait_max:
                self.giveUp("przerwano - brak wolnego tunera przez %d min" % (self.wait_max // 60))
                return
            self.waited += WAIT_STEP
            self.progress("Skan %s: czekam na wolny tuner (wolne %d, do skanu %d, rezerwa %d)"
                          % (desc, len(total), len(free), self.reserve), quiet=(self.waited - WAIT_STEP) % 600 != 0,
                          display=_("Skan %s: czekam na wolny tuner (wolne %d, do skanu %d, rezerwa %d)")
                          % (display_desc, len(total), len(free), self.reserve))
            self.stepTimer.start(WAIT_STEP * 1000, True)
            return
        for slot in self.order(free):
            sc = eComponentScan()
            sc.addInitial(sat_param(tp))
            sc.statusChanged.get().append(self.statusChanged)
            sc.newService.get().append(self.newService)
            self.scan = sc
            err = sc.start(slot, 0)
            if err == 0:
                self.slot = slot
                break
            self.disconnect()
        if self.scan is None:
            if self.waited >= self.wait_max:
                self.giveUp("przerwano - tunery zajete (FBC) przez %d min" % (self.wait_max // 60))
                return
            self.waited += WAIT_STEP
            self.progress("Skan %s: czekam - enigma nie daje tunera do skanu (%s odmowily; FBC zajety?)"
                          % (desc, ", ".join(tuner_name(x) for x in self.order(free))), quiet=(self.waited - WAIT_STEP) % 600 != 0,
                          display=_("Skan %s: czekam - enigma nie daje tunera do skanu (%s odmowily; FBC zajety?)")
                          % (display_desc, ", ".join(tuner_name(x) for x in self.order(free))))
            self.stepTimer.start(WAIT_STEP * 1000, True)
            return
        self.cur_found = set()
        self.t_start = time.time()
        self.progress("Skan %s (tuner %s)" % (desc, tuner_name(self.slot)),
                      display=_("Skan %s (tuner %s)") % (display_desc, tuner_name(self.slot)))
        self.wdTimer.start(TP_TIMEOUT * 1000, True)
        self.pollTimer.start(POLL * 1000, False)

    def disconnect(self):
        if self.scan is not None:
            try:
                self.scan.statusChanged.get().remove(self.statusChanged)
            except Exception:
                pass
            try:
                self.scan.newService.get().remove(self.newService)
            except Exception:
                pass
        self.scan = None

    def stopScan(self):
        self.wdTimer.stop()
        self.pollTimer.stop()
        self.doneTimer.stop()
        self.disconnect()     # zwolnienie obiektu = zwolnienie tunera
        self.slot = None

    # --- zdarzenia eComponentScan (wolane z C++: tylko notujemy, dalej w timerze) ---
    def newService(self):
        if self.scan is None:
            return
        try:
            k = core._ref_key(self.scan.getLastServiceRef())
            if k:
                self.cur_found.add(k)
        except Exception:
            pass

    def statusChanged(self):
        if self.scan is not None and self.scan.isDone() == 1:
            self.doneTimer.start(100, True)

    # ---------------------------------------------------------------
    def scanFinished(self):
        if self.scan is None:
            return
        tp = self.tps[self.idx]
        err = self.scan.getError()
        n = self.scan.getNumServices()
        slot = self.slot
        self.stopScan()
        dt = int(time.time() - self.t_start)
        self.waited = 0
        if err == 0 and n > 0:
            self.found |= self.cur_found
            self.record(tp, True, "OK, %d serwisow, %d s" % (n, dt), n, slot)
        elif err == 0:
            self.record(tp, False, "brak serwisow (brak sygnalu?)", 0, slot)
        else:
            self.record(tp, False, "blad skanu %s" % err, 0, slot)
        self.next()

    def scanTimeout(self):
        if self.scan is None:
            return
        tp = self.tps[self.idx]
        slot = self.slot
        self.stopScan()
        self.record(tp, False, "limit czasu %d s" % TP_TIMEOUT, 0, slot)
        self.next()

    def poll(self):
        """Uzytkownik/stream potrzebuje tunera -> oddajemy, TP ponowimy pozniej."""
        if self.scan is None:
            return
        tp = self.tps[self.idx]
        if len(self.free_total(exclude=self.slot)) >= self.reserve:
            return
        i = core.tp_id(tp)
        self.preempted[i] = self.preempted.get(i, 0) + 1
        slot = self.slot
        self.stopScan()
        if self.preempted[i] > MAX_PREEMPT:
            self.record(tp, False, "tuner potrzebny uzytkownikowi (%dx)" % MAX_PREEMPT, 0, slot)
            self.next()
            return
        self.progress("Skan: zwalniam tuner %s dla uzytkownika, TP %s ponowie" % (tuner_name(slot), core.tp_str(tp)),
                      display=_("Skan: zwalniam tuner %s dla uzytkownika, TP %s ponowie") % (tuner_name(slot), core.tp_str(tp)))
        self.stepTimer.start(WAIT_STEP * 1000, True)

    # ---------------------------------------------------------------
    def record(self, tp, ok, status, found=0, slot=None):
        r = dict(tp, ok=ok, status=status, found=found, tuner=tuner_name(slot))
        self.results.append(r)
        log("Skan: %s %s -> %s" % (core.pos_str(tp["position"]), core.tp_str(tp), status))

    def next(self):
        self.idx += 1
        self.stepTimer.start(GAP * 1000, True)

    def finish(self):
        if self.finished:
            return
        self.finished = True
        self.stopScan()
        self.stepTimer.stop()
        try:
            import gc
            gc.collect()      # obiekt skanu (eComponentScan) znika od razu = tuner zwolniony
        except Exception:
            pass
        okn = len([r for r in self.results if r["ok"]])
        log("Skan: koniec, poprawnie %d z %d" % (okn, len(self.results)))
        if self.on_done:
            self.on_done(self.results, self.found)


# --------------------------------------------------------------------------
# porada: konfiguracja tunerow (system + EPG Scan + SatWatch) wg wykrytego ukladu
# --------------------------------------------------------------------------
def tuner_layout():
    """[(slot, rola, tryb)] wlaczonych tunerow DVB-S; rola: 'fbc_root' / 'fbc_link' / 'zwykly'."""
    nm = _nim_info()
    out = []
    for n in nm.nim_slots:
        try:
            if not (n.isCompatible("DVB-S") and n.isEnabled()):
                continue
            role = "fbc_root" if n.isFBCRoot() else ("fbc_link" if n.isFBCLink() else "zwykly")
            try:
                mode = n.config_mode
            except Exception:
                mode = "?"
            out.append((n.slot, role, mode))
        except Exception:
            pass
    return out


def tuner_advice(pref=None):
    """(krotko, dlugo) - co ustawic w systemie, w EPG Scan i w SatWatch."""
    try:
        lay = tuner_layout()
    except Exception as e:
        message = _("Nie udalo sie odczytac tunerow (%s).") % e
        return message, message
    names = {"fbc_root": _("FBC glowny"), "fbc_link": _("FBC polaczony"), "zwykly": _("zwykly")}
    desc = ", ".join("%s (%s, %s)" % (tuner_name(s), names[r], m) for s, r, m in lay) or _("brak")
    n = len(lay)
    roots = [s for s, r, m in lay if r == "fbc_root"]
    fed = [s for s, r, m in lay if r == "fbc_root" and m not in ("nothing", "loopthrough")]
    L = [_("Wykryte tunery DVB-S: %d - %s") % (n, desc), ""]
    if n <= 1:
        short = _("1 tuner: rezerwa 0 (EPG Scan i SatWatch), skan w nocy w czuwaniu.")
        L += [_("JEDEN TUNER"),
              _("Skan EPG i skan satelity zajmuja jedyny tuner - w tym czasie nie obejrzysz ani nie nagrasz"),
              _("kanalu z innego transpondera. Dlatego pracuja w nocy, w czuwaniu."),
              _("  System:    nic do zmiany."),
              _("  EPG Scan:  Rezerwa wolnych tunerow = 0, harmonogram w nocy, 'Czekaj, gdy trwa nagrywanie' wlaczone."),
              _("  SatWatch:  Rezerwa wolnych tunerow = 0, preferowany tuner: automatycznie.")]
    elif roots and len(fed) < 2:
        short = _("FBC z jednym wejsciem: skan satelity zwykle tylko przy wolnych tunerach - zalecany 2. kabel na wejscie B.")
        L += [_("FBC, JEDNO WEJSCIE ANTENOWE"),
              _("Wszystkie tunery sa w jednej grupie. Enigma moze odmawiac skanu satelity, gdy w grupie cos gra"),
              _("(ogladanie, szybkie przelaczanie FCC, stream do klienta) - skan ruszy dopiero, gdy tunery beda wolne"),
              _("(np. w nocy w czuwaniu). Skan EPG dziala normalnie."),
              _("Zalecane: drugi kabel na wejscie B i dwie grupy:"),
              _("  System:    tuner A + polaczone C..H -> priorytet AUTO; tuner B bez polaczonych -> priorytet 1"),
              _("             (mniejszy niz AUTO - Enigma bierze B na koncu). Przy Unicable kazdy tuner ma wlasny SCR."),
              _("  EPG Scan:  Rezerwa wolnych tunerow = 1."),
              _("  SatWatch:  preferowany tuner = B, rezerwa 1.")]
    elif roots:
        short = _("FBC, dwa wejscia: B osobno z priorytetem 1, w SatWatch preferowany B.")
        L += [_("FBC, DWA WEJSCIA"),
              _("Skan satelity dziala podczas ogladania, gdy jedna grupa (wejscie) jest wolna."),
              _("  System:    tuner A + polaczone C..H -> priorytet AUTO; tuner B bez polaczonych -> priorytet 1."),
              _("  EPG Scan:  Rezerwa wolnych tunerow = 1."),
              _("  SatWatch:  preferowany tuner = B, rezerwa 1.")]
    else:
        last = tuner_name(max(s for s, r, m in lay))
        short = _("%d tunery: tuner %s z priorytetem 1 w systemie, w SatWatch preferowany %s.") % (n, last, last)
        L += [_("KILKA ZWYKLYCH TUNEROW"),
              _("  System:    tuner %s -> priorytet 1 (mniejszy niz AUTO), pozostale AUTO - Enigma bierze %s na koncu.") % (last, last),
              _("  EPG Scan:  Rezerwa wolnych tunerow = 1 (przy 2 tunerach skan EPG zajmie najwyzej 1)."),
              _("  SatWatch:  preferowany tuner = %s, rezerwa 1.") % last]
    L += ["",
          _("Priorytet tunera: menu Enigmy -> Ustawienia -> Tunery i skanowanie -> Konfiguracja tunerow -> wybrany tuner"),
          _("(nazwy menu moga sie roznic w zaleznosci od obrazu)."),
          _("Preferowany tuner zajety -> SatWatch bierze od razu inny wolny; gdy Enigma odmowi, probuje kolejnego."),
          _("Przyklad i wyniki testow (VU+ Uno 4K SE, FBC 8 tunerow, Unicable): README, rozdzial 'Konfiguracja tunerow'.")]
    if pref is not None:
        L.append(_("Obecnie w SatWatch preferowany: %s.") % (tuner_name(pref) if pref >= 0 else _("automatycznie")))
    return short, "\n".join(L)
