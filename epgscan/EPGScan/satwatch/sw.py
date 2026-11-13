# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# SatWatch (czesc EPG Scan od 2.2.0) - kontrola satellites.xml, skan zmienionych TP, pelny skan, naprawa bukietow.
# Harmonogram prowadzi EPG Scan (krok "Satelita: zmiany i bukiety" przed skanem EPG); tu tylko wznawianie
# przerwanych skanow TP, gdy tunery wolne. Ustawienia: ta sama przestrzen config.plugins.satwatch co osobna wtyczka.
import time

from enigma import eTimer, getDesktop, eDVBDB, eServiceReference
from twisted.internet import threads

from Components.ActionMap import ActionMap
from Components.ConfigList import ConfigListScreen
from Components.Label import Label
from Components.MenuList import MenuList
from Components.ScrollLabel import ScrollLabel
from Components.Sources.StaticText import StaticText
from Components.config import (config, configfile, ConfigSubsection, ConfigYesNo,
                               ConfigClock, ConfigText, ConfigSelection,
                               ConfigSelectionNumber, getConfigListEntry, NoSave)
try:
    from ..common import EPGChoiceBox as ChoiceBox      # lista bez okienka z obrazem (jak w EPG Scan)
except Exception:
    from Screens.ChoiceBox import ChoiceBox
from Screens.MessageBox import MessageBox
from Screens.Screen import Screen
from Tools import Notifications

from . import core
from . import scanner


def _clock(h, m):
    lt = time.localtime()
    return int(time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday, h, m, 0, 0, 0, -1)))


DAYS = [("0", "poniedziałek"), ("1", "wtorek"), ("2", "środa"), ("3", "czwartek"),
        ("4", "piątek"), ("5", "sobota"), ("6", "niedziela")]

config.plugins.satwatch = ConfigSubsection()
cfg = config.plugins.satwatch
cfg.url = ConfigText(default=core.DEFAULT_URL, fixed_size=False)
cfg.positions = ConfigText(default="130", fixed_size=False)
cfg.mode = ConfigSelection(default="positions", choices=[
    ("positions", "wybrane pozycje"), ("full", "cały plik")])
cfg.action = ConfigSelection(default="update", choices=[
    ("report", "tylko raport"), ("update", "raport + aktualizacja satellites.xml")])
cfg.tolerance = ConfigSelectionNumber(1, 10, 1, default=3)
cfg.backups = ConfigSelectionNumber(1, 20, 1, default=5)
cfg.verify_ssl = ConfigYesNo(default=True)
cfg.reload = ConfigYesNo(default=False)
cfg.snapshot = ConfigYesNo(default=True)
cfg.ignore_bq = ConfigText(default=core.DEFAULT_IGNORE, fixed_size=False)
cfg.popup = ConfigSelection(default="changes", choices=[
    ("never", "nigdy"), ("changes", "tylko przy zmianach"), ("always", "zawsze")])
cfg.schedule = ConfigYesNo(default=True)
cfg.time = ConfigClock(default=_clock(4, 30))
# 0.2.0
cfg.autoscan = ConfigYesNo(default=True)
cfg.full_enable = ConfigYesNo(default=True)
cfg.full_day = ConfigSelection(default="6", choices=DAYS)
cfg.full_time = ConfigClock(default=_clock(3, 30))
cfg.remove_stale = ConfigYesNo(default=True)
cfg.autoreplace = ConfigYesNo(default=True)
cfg.reserve = ConfigSelectionNumber(0, 3, 1, default=1)
# 0.2.3
cfg.wait_max = ConfigSelection(default="120", choices=[
    ("15", "15 min"), ("30", "30 min"), ("60", "1 h"), ("120", "2 h"), ("240", "4 h"), ("360", "6 h")])
cfg.resume = ConfigYesNo(default=True)


def tuner_choices():
    """Preferowany tuner do skanu - lista z konfiguracji dekodera (tunery DVB-S wlaczone)."""
    out = [("auto", "automatycznie (najwyższy wolny)")]
    try:
        from Components.NimManager import nimmanager
        for n in nimmanager.nim_slots:
            try:
                if not (n.isCompatible("DVB-S") and n.isEnabled()):
                    continue
                role = "FBC główny" if n.isFBCRoot() else ("FBC połączony" if n.isFBCLink() else "")
                try:
                    mode = n.config_mode
                except Exception:
                    mode = ""
                extra = ", ".join(x for x in (role, mode) if x and x != "nothing")
                out.append((str(n.slot), "%s%s" % (scanner.tuner_name(n.slot), (" (%s)" % extra) if extra else "")))
            except Exception:
                pass
    except Exception:
        pass
    if len(out) == 1:
        out += [(str(i), scanner.tuner_name(i)) for i in range(8)]
    return out


cfg.scan_tuner = ConfigSelection(default="auto", choices=tuner_choices())
cfg.urls = ConfigText(default="", fixed_size=False)     # dopisane adresy satellites.xml, rozdzielone "|"


def preferred_slot():
    v = cfg.scan_tuner.value
    return int(v) if v and v != "auto" and v.isdigit() else None


def _logdir_choices():
    """Pamiec wewnetrzna + typowe nosniki (zawsze, by zapisany wybor nie przepadl, gdy
    dysk montuje sie pozniej) + inne zamontowane w /media."""
    import os
    ch = [(core.DATA_DIR, "pamięć wewnętrzna (%s)" % core.DATA_DIR)]
    seen = set()
    roots = ["/media/hdd", "/media/usb", "/media/mmc"]
    try:
        roots += sorted(os.path.join("/media", d) for d in os.listdir("/media"))
    except Exception:
        pass
    for r in roots:
        if r in seen or r == "/media/net" or r == "/media/autofs":
            continue
        seen.add(r)
        if r in ("/media/hdd", "/media/usb", "/media/mmc") or os.path.ismount(r):
            ch.append((r + "/satwatch", r + "/satwatch"))
    return ch


cfg.logdir = ConfigSelection(default=core.DATA_DIR, choices=_logdir_choices())


def apply_logdir():
    core.LOG_PREF = "" if cfg.logdir.value == core.DATA_DIR else cfg.logdir.value


def get_opts():
    return {
        "url": cfg.url.value.strip(),
        "positions": core.parse_positions(cfg.positions.value) or [130],
        "mode": cfg.mode.value,
        "action": cfg.action.value,
        "tol_mhz": int(cfg.tolerance.value),
        "backups": int(cfg.backups.value),
        "verify_ssl": cfg.verify_ssl.value,
        "snapshot": cfg.snapshot.value,
        "ignore_bouquets": cfg.ignore_bq.value,
        "autoreplace": cfg.autoreplace.value,
    }


def reload_transponders():
    """Przeladowanie listy transponderow bez restartu GUI.
    W 9.2 readTransponders() dopisuje do satList zamiast ja czyscic - czyscimy recznie."""
    try:
        from Components.NimManager import nimmanager
        del nimmanager.satList[:]
        nimmanager.readTransponders()
        return True
    except Exception as e:
        scanner.log("readTransponders: %s" % e)
        return False


def reload_lists():
    """Po zmianach w bukietach: wczytaj je ponownie i odswiez liste kanalow."""
    try:
        eDVBDB.getInstance().reloadBouquets()
    except Exception as e:
        scanner.log("reloadBouquets: %s" % e)
    try:
        from Components.ServiceList import refreshServiceList
        refreshServiceList()
    except Exception as e:
        scanner.log("refreshServiceList: %s" % e)


def remove_services(stale):
    """Usuwa serwisy z bazy enigmy (w pamieci) i zapisuje lamedb. Zwraca usuniete."""
    db = eDVBDB.getInstance()
    removed = []
    for s in stale:
        ref = eServiceReference(core._ref(s["key"], s["type"], s["srcid"]))
        try:
            if db.removeService(ref) == 0:
                removed.append(s)
        except Exception as e:
            scanner.log("removeService %s: %s" % (ref.toString(), e))
    if removed:
        db.saveServicelist()
    return removed


class Runner(object):
    """Zadania w tle, harmonogram i caly przebieg: sprawdzenie -> skan -> bukiety."""

    def __init__(self):
        self.busy = False
        self.session = None
        self.scanner = None
        self.listeners = []
        self.timer = eTimer()
        self.timer.callback.append(self.onTimer)
        self.next_check = 0
        self.next_full = 0
        self.status = ""
        self.last_tick = 0
        self.idle_since = 0
        self.done_cbs = []          # EPG Scan: f(summary, important, changed, info)
        self.wait_override = 0      # EPG Scan: krotsze czekanie na wolne tunery (s)
        self.pipeline = False       # EPG Scan: krok przebiegu - skan tylko realnych zmian, ponowienia w tle
        self.last_info = {}

    # --- harmonogram ---
    @staticmethod
    def _next_daily(h, m, now):
        lt = time.localtime(now)
        t = time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday, h, m, 0, 0, 0, -1))
        if t <= now + 30:
            t = time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday + 1, h, m, 0, 0, 0, -1))
        return int(t)

    @staticmethod
    def _next_weekly(day, h, m, now):
        lt = time.localtime(now)
        for add in range(0, 8):
            t = time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday + add, h, m, 0, 0, 0, -1))
            if time.localtime(t).tm_wday == day and t > now + 30:
                return int(t)
        return int(now + 7 * 86400)

    def schedule(self):
        """Wylicza nastepne terminy i uruchamia zegar sprawdzajacy co minute.
        Co minute, a nie jeden dlugi timer: po restarcie zegar dekodera bywa
        przez chwile zly (synchronizacja czasu) - skok zegara = przeliczenie."""
        now = time.time()
        self.last_tick = now
        self.next_check = 0           # terminy prowadzi harmonogram EPG Scan
        self.next_full = 0
        if not self.timer.isActive():
            self.timer.start(60000, False)

    def onTimer(self):
        now = time.time()
        if now < self.last_tick - 120 or now > self.last_tick + 900:
            scanner.log("Harmonogram: skok zegara, przeliczam")
            self.schedule()
            return
        self.last_tick = now
        self.checkResume(now)
        due_full = self.next_full and now >= self.next_full
        due_check = self.next_check and now >= self.next_check
        if not (due_full or due_check) or self.busy:
            return          # zajety: zadanie zostaje zalegle, sprobujemy za minute
        if due_full:
            started = self.fullScan(scheduled=True)
        else:
            started = self.check(scheduled=True)
        if started:
            self.schedule()

    def checkResume(self, now):
        """Wznowienie przerwanego skanu, gdy wszystkie tunery wolne od 2 minut."""
        if self.busy or self.session is None or not cfg.resume.value:
            self.idle_since = 0
            return
        if scanner.use_mask(self.session) != 0:
            self.idle_since = 0
            return
        if not self.idle_since:
            self.idle_since = now
            return
        if now - self.idle_since < 120 or not core.resume_pending():
            return
        r = core.resume_take()
        self.idle_since = 0
        if r:
            kind, tps = r
            scanner.log("Wznowienie: tunery wolne, skanuje pozostale %d TP" % len(tps))
            self.startScan(tps, kind + " (wznowienie)", {})

    # --- zadania w watku ---
    def run(self, func, done, *args):
        if self.busy:
            return False
        self.busy = True
        self.notify("Pracuję…")

        def ok(res):
            self.busy = False
            done(res)

        def err(f):
            self.busy = False
            msg = "Błąd: %s" % f.getErrorMessage()
            scanner.log(msg)
            done({"summary": msg, "report": msg, "changes": 0, "updated": False, "error": msg,
                  "scan_tps": [], "bq_changed": False, "lines": [msg], "changed": False,
                  "replaced": 0, "unresolved": 0})

        threads.deferToThread(func, *args).addCallbacks(ok, err)
        return True

    # --- przebieg ---
    def check(self, scheduled=False, full=False):
        return self.run(core.run_check, lambda r: self._checkDone(r, full), get_opts())

    def fullScan(self, scheduled=False):
        return self.check(scheduled, full=True)

    def _checkDone(self, res, full):
        if res.get("updated") and cfg.reload.value:
            reload_transponders()
        if res.get("bq_changed"):
            reload_lists()
        tps = []
        kind = ""
        if full:
            try:
                tps = core.merge_tps(core.full_scan_tps(get_opts()["positions"]), res.get("scan_tps"))
                kind = "pelny"
            except Exception as e:
                res["report"] = core.append_report(["Pelny skan: nie moge odczytac satellites.xml: %s" % e])
        elif cfg.autoscan.value and self.pipeline and "scan_new" in res:
            # krok przebiegu EPG Scan: tunery tylko dla realnych zmian; ponowienia nieudanych TP
            # ida do wznowienia w tle (gdy wszystkie tunery beda wolne od 2 min)
            tps = res.get("scan_new") or []
            kind = "zmienione TP"
            retry = [t for t in (res.get("retry") or []) if core.tp_id(t) not in set(core.tp_id(x) for x in tps)]
            if retry and cfg.resume.value:
                try:
                    core.resume_add("ponowienie", retry)
                    scanner.log("Ponowienie %d TP z poprzedniego skanu - w tle, gdy tunery beda wolne" % len(retry))
                except Exception as e:
                    scanner.log("Ponowienie w tle: %s" % e)
            elif retry:
                tps = core.merge_tps(tps, retry) if tps else []
        elif cfg.autoscan.value and res.get("scan_tps"):
            tps = res["scan_tps"]
            kind = "zmienione TP"
        self.last_info = {"updated": bool(res.get("updated")), "reloaded": bool(res.get("updated") and cfg.reload.value),
                          "bq_changed": bool(res.get("bq_changed")), "restored": bool(res.get("restored"))}
        if tps and self.session is not None:
            self.startScan(tps, kind, res)
        else:
            self.finish(res["summary"], res.get("report"), bool(res.get("changes") or res.get("error")),
                        changed=bool(res.get("bq_changed")), unresolved=res.get("unresolved", 0))

    def startScan(self, tps, kind, pre):
        self.busy = True
        self.scanner = scanner.TPScanner(self.session, tps, int(cfg.reserve.value),
                                         wait_max=self.wait_override or int(cfg.wait_max.value) * 60,
                                         on_progress=self.notify, prefer=preferred_slot(),
                                         on_done=lambda r, f: self._scanDone(kind, pre, r, f))
        self.notify("Skan (%s): %d TP" % (kind, len(tps)))
        self.scanner.start()

    def abortScan(self):
        if self.scanner is not None and not self.scanner.finished:
            self.scanner.abort()
            return True
        return False

    def _scanDone(self, kind, pre, results, found):
        self.scanner = None
        tol = int(cfg.tolerance.value) * 1000
        stale, suspicious, removed = [], [], []
        try:
            stale, suspicious = core.find_stale(results, found, tol)
            if cfg.remove_stale.value:
                removed = remove_services(stale)
            core.stale_kept_update(stale, removed, found)
            core.scan_failed_update(results, keep_aborted=not kind.startswith("pelny"))
        except Exception as e:
            scanner.log("Po skanie: %s" % e)
        lines = core.format_scan_report(kind, results, stale, removed, suspicious, kept=not cfg.remove_stale.value)
        try:
            n = core.resume_save(kind, results) if cfg.resume.value else 0
            if n:
                lines.append("  Wznowienie: %d TP zostanie zeskanowanych, gdy tunery beda wolne (do 24 h)." % n)
        except Exception as e:
            scanner.log("Wznowienie: %s" % e)
        self.busy = False
        if not self.run(core.process_bouquets,
                        lambda b: self._bouquetsDone(kind, lines, results, removed, b, pre),
                        cfg.ignore_bq.value, cfg.autoreplace.value):
            self._bouquetsDone(kind, lines, results, removed,
                               {"lines": ["Bukiety: pominiete (zajete)"], "changed": False,
                                "replaced": 0, "unresolved": 0}, pre)

    def _bouquetsDone(self, kind, lines, results, removed, b, pre=None):
        if b.get("changed") or removed:
            reload_lists()
        text = core.append_report(lines + [""] + b.get("lines", []))
        okn = len([r for r in results if r.get("ok")])
        repl = b.get("replaced", 0) + (pre or {}).get("bq_replaced", 0)   # + podmiany przy sprawdzeniu (przed skanem)
        b["replaced"] = repl
        summary = "SatWatch: skan %s %d/%d TP OK, usunieto %d, podmieniono %d, do decyzji %d" % (
            kind, okn, len(results), len(removed), repl, b.get("unresolved", 0))
        scanner.log(summary)
        important = okn < len(results) or removed or b.get("replaced") or b.get("unresolved_new")
        nosig = len([r for r in results if not r.get("ok")])
        self.last_info.update({"scanned": len(results), "failed": nosig})
        self.finish(summary, text, important, error=okn < len(results),
                    changed=bool(removed or b.get("changed") or b.get("replaced")), unresolved=b.get("unresolved", 0),
                    scanned_tps=[r.get("tp") for r in results if r.get("ok")])

    def fixBouquets(self):
        return self.run(core.process_bouquets, self._fixDone, cfg.ignore_bq.value, cfg.autoreplace.value)

    def _fixDone(self, b):
        if b.get("changed"):
            reload_lists()
        text = core.append_report(["Naprawa bukietow (recznie):"] + b.get("lines", []))
        self.finish("Bukiety: podmieniono %d, do decyzji %d" % (b.get("replaced", 0), b.get("unresolved", 0)),
                    text, False)

    def finish(self, summary, report, important, error=False, changed=False, unresolved=0, scanned_tps=None):
        info = dict(self.last_info, changed=changed, unresolved=unresolved, error=error, tps=scanned_tps or [])
        self.last_info = {}
        for cb in self.done_cbs[:]:
            try:
                cb(summary, important, changed, info)
            except Exception as e:
                scanner.log("EPG Scan: %s" % e)
        p = cfg.popup.value
        if p == "always" or (p == "changes" and important):
            Notifications.AddPopup(summary, MessageBox.TYPE_WARNING if error else MessageBox.TYPE_INFO,
                                   20, "SatWatchPopup")
        self.notify(summary, report)

    def notify(self, status, report=None):
        self.status = status
        for cb in self.listeners[:]:
            try:
                cb(status, report)
            except Exception:
                pass


runner = Runner()


def progress_short():
    """Krotki postep do listy SatWatch: 'Skan 21/73 - 27%, zostalo ok. 4 min' / '... - czeka na wolny tuner'."""
    sc = runner.scanner
    if sc is None:
        return runner.status if runner.busy else ""
    n = len(sc.tps) or 1
    i = min(sc.idx + 1, n)
    if sc.scan is None and sc.waited > 0:
        return "Skan %d/%d - czeka na wolny tuner" % (i, n)
    pct = int(sc.idx * 100 / n)
    if sc.idx >= 3:
        import time as _t
        left = (_t.time() - sc.t0) / sc.idx * (n - sc.idx)
        return "Skan %d/%d - %d%%, zostało ok. %s" % (i, n, pct, ("%d min" % round(left / 60.0)) if left >= 90 else ("%d s" % left))
    return "Skan %d/%d - %d%%" % (i, n, pct)


def _f(v):
    return int(v * _f.scale)


try:
    _f.scale = getDesktop(0).size().width() / 1440.0
except Exception:
    _f.scale = 1.0


def _buttons(y, labels):
    colors = ("#9f1313", "#1f771f", "#a08000", "#18188b")
    s = ""
    for i, key in enumerate(("key_red", "key_green", "key_yellow", "key_blue")):
        if key not in labels:
            continue
        x = 20 + i * 295
        s += '<eLabel position="%d,%d" size="%d,%d" backgroundColor="%s" />' % (
            _f(x), _f(y), _f(8), _f(34), colors[i])
        s += ('<widget source="%s" render="Label" position="%d,%d" size="%d,%d" '
              'font="Regular;%d" valign="center" transparent="1" />') % (
            key, _f(x + 14), _f(y), _f(270), _f(34), _f(20))
    return s


class SatWatchMain(Screen):
    skin = """
    <screen name="SatWatchMain" position="center,center" size="%d,%d" title="SatWatch">
        <widget name="status" position="%d,%d" size="%d,%d" font="Regular;%d" foregroundColor="#00b0b0b0" />
        <widget name="report" position="%d,%d" size="%d,%d" font="Console;%d" />
        <eLabel position="%d,%d" size="%d,%d" backgroundColor="#00606060" />
        <widget name="menu_hint" position="%d,%d" size="%d,%d" font="Regular;%d" foregroundColor="#00b0b0b0" />
    </screen>""" % (_f(1200), _f(690),
                    _f(20), _f(10), _f(1160), _f(30), _f(20),
                    _f(20), _f(50), _f(1160), _f(560), _f(17),
                    _f(20), _f(616), _f(1160), _f(1),
                    _f(20), _f(622), _f(1160), _f(30), _f(18))

    def __init__(self, session, action=None):
        Screen.__init__(self, session)
        self.setTitle("SatWatch %s" % core.VERSION)
        self.start_action = action          # z menu Kanaly -> SatWatch: akcja do wykonania po otwarciu okna
        rep = core.read_last_report() or "Brak raportu. Kanały -> SatWatch -> Sprawdź teraz."
        self["report"] = ScrollLabel(rep)
        self["status"] = Label(runner.status if runner.busy else self.statusText())
        self["menu_hint"] = Label("Exit / OK - powrót do listy SatWatch (praca trwa w tle).  Strzałki - przewijanie raportu.")
        self["actions"] = ActionMap(
            ["OkCancelActions", "DirectionActions"], {
                "cancel": self.close,
                "ok": self.close,
                "up": self["report"].pageUp,
                "down": self["report"].pageDown,
                "left": self["report"].pageUp,
                "right": self["report"].pageDown,
            }, -1)
        runner.listeners.append(self.onRunner)
        self.onClose.append(self.cleanup)
        # akcja z listy SatWatch - dopiero po pokazaniu okna (okna z pytaniem nie wolno otwierac w trakcie budowania ekranu)
        self.start_timer = eTimer()
        self.start_timer.callback.append(self.runStartAction)
        self.onShown.append(self.queueStartAction)

    def queueStartAction(self):
        if self.start_action:
            self.start_timer.start(50, True)

    def runStartAction(self):
        a, self.start_action = self.start_action, None
        if not a:
            return
        {"check": self.keyCheck, "snap": self.keySnap, "compare": self.keyCompare}.get(
            a, lambda: self.menuDone((a, a)))()

    def statusText(self):
        st = core.load_json(core.DATA_DIR + "/state.json", {})
        s = "Sprawdzenie: %s" % st.get("last_check", "-")
        if runner.next_check:
            s += " | następne %s" % time.strftime("%d.%m %H:%M", time.localtime(runner.next_check))
        if runner.next_full:
            s += " | pełny skan %s" % time.strftime("%d.%m %H:%M", time.localtime(runner.next_full))
        return s

    def cleanup(self):
        if self.onRunner in runner.listeners:
            runner.listeners.remove(self.onRunner)

    def onRunner(self, status, report):
        self["status"].setText(status)
        if report:
            self["report"].setText(report)

    def busy(self):
        self.session.open(MessageBox, "Trwa zadanie (%s). Poczekaj albo przerwij skan: Kanały -> SatWatch -> Przerwij skan."
                          % runner.status,
                          MessageBox.TYPE_INFO, timeout=6)

    def keyCheck(self):
        if not runner.check():
            self.busy()

    def keySnap(self):
        self.session.openWithCallback(
            lambda ans: (runner.run(core.snapshot_save_only, self.simpleDone) or self.busy()) if ans else self.close(),
            MessageBox, "Zapisać aktualny stan lamedb i bukietów jako zrzut referencyjny?\n"
                        "Zrób to przed ręcznym skanem.", MessageBox.TYPE_YESNO)

    def keyCompare(self):
        if not runner.run(core.snapshot_compare_only, self.simpleDone, cfg.ignore_bq.value):
            self.busy()

    def simpleDone(self, res):
        text = res.get("report", "") if isinstance(res, dict) else res
        self["status"].setText(text.splitlines()[0] if text else "")
        self["report"].setText(text)

    def keySetup(self):
        self.session.openWithCallback(lambda *a: self["status"].setText(self.statusText()), SatWatchSetup)

    def keyMenu(self):
        items = []
        if runner.scanner is not None:
            items.append(("Przerwij skan", "abort"))
        items += [("Do decyzji - wybierz kandydata", "decide"),
                  ("Skan zmienionych TP teraz (sprawdzenie + skan)", "check"),
                  ("Pełny skan teraz", "full"),
                  ("Napraw bukiety teraz (podmiany wg reguł)", "fix"),
                  ("Akceptuj obecny stan (wyczyść listę DO DECYZJI)", "accept"),
                  ("Przywróć kopię bukietów", "restore_bq"),
                  ("Przywróć kopię satellites.xml", "restore"),
                  ("Przeładuj transpondery (bez restartu)", "reload"),
                  ("Pokaż log (ostatnie 200 linii)", "log"),
                  ("Konfiguracja tunerów - co ustawić (system, EPG Scan, SatWatch)", "tuners"),
                  ("Ustawienia SatWatch (też: EPG Scan -> Ustawienia)", "setup")]
        self.session.openWithCallback(self.menuDone, ChoiceBox, title="SatWatch", list=items)

    def menuDone(self, choice):
        if not choice:
            return
        c = choice[1]
        if c == "decide":
            open_decisions(self.session)
        elif c == "abort":
            runner.abortScan()
        elif c == "check":
            runner.check() or self.busy()
        elif c == "full":
            self.session.openWithCallback(lambda a: (runner.fullScan() or self.busy()) if a else self.close(), MessageBox,
                                          "Uruchomić pełny skan monitorowanych pozycji?\n"
                                          "Potrwa kilkanaście minut, używa jednego wolnego tunera.",
                                          MessageBox.TYPE_YESNO)
        elif c == "fix":
            runner.fixBouquets() or self.busy()
        elif c == "accept":
            self.session.openWithCallback(
                lambda a: self["status"].setText("Wyczyszczono %d pozycji DO DECYZJI." % core.clear_unresolved()) if a else self.close(),
                MessageBox, "Uznać obecne kanały w bukietach za poprawne?\n"
                            "Pozycje DO DECYZJI przestaną wracać w raportach.", MessageBox.TYPE_YESNO)
        elif c == "log":
            od = core.out_dir()
            note = "" if (core.LOG_PREF in ("", od)) else "  (wybrany %s niedostępny!)" % core.LOG_PREF
            self["status"].setText("Log: %s%s" % (scanner.log_path(), note))
            self["report"].setText(scanner.read_log(200))
        elif c == "setup":
            self.keySetup()
        elif c == "tuners":
            p = preferred_slot()
            self["status"].setText(scanner.tuner_advice(-1 if p is None else p)[0])
            self["report"].setText(scanner.tuner_advice(-1 if p is None else p)[1])
        elif c == "reload":
            ok = reload_transponders()
            self["status"].setText("Transpondery przeładowane." if ok else
                                   "Przeładowanie nie powiodło się - zrestartuj GUI.")
        elif c == "restore":
            b = core.list_backups()
            if not b:
                self.session.open(MessageBox, "Brak kopii.", MessageBox.TYPE_INFO, timeout=5)
                return
            self.session.openWithCallback(self.restoreDone, ChoiceBox, title="Kopia satellites.xml",
                                          list=[(x, x) for x in b])
        elif c == "restore_bq":
            b = core.list_bouquet_backups()
            if not b:
                self.session.open(MessageBox, "Brak kopii bukietów.", MessageBox.TYPE_INFO, timeout=5)
                return
            self.session.openWithCallback(self.restoreBqDone, ChoiceBox, title="Kopia bukietów (sprzed podmiany)",
                                          list=[(x, x) for x in b])

    def restoreDone(self, choice):
        if not choice:
            return self.close()
        try:
            core.restore_backup(choice[1])
            self["status"].setText("Przywrócono %s. Zrestartuj GUI lub przeładuj transpondery." % choice[1])
        except Exception as e:
            self["status"].setText("Błąd przywracania: %s" % e)

    def restoreBqDone(self, choice):
        if not choice:
            return self.close()
        try:
            n = core.restore_bouquet_backup(choice[1])
            reload_lists()
            self["status"].setText("Przywrócono %d plików bukietów z %s." % (n, choice[1]))
        except Exception as e:
            self["status"].setText("Błąd przywracania: %s" % e)


class SatWatchSetup(ConfigListScreen, Screen):
    skin = """
    <screen name="SatWatchSetup" position="center,center" size="%d,%d" title="SatWatch - ustawienia">
        <widget name="config" position="%d,%d" size="%d,%d" font="Regular;%d" itemHeight="%d" scrollbarMode="showOnDemand" />
        <eLabel position="%d,%d" size="%d,%d" backgroundColor="#00606060" />
        <widget name="help" position="%d,%d" size="%d,%d" font="Regular;%d" foregroundColor="#00b0b0b0" />
        %s
    </screen>""" % (_f(1200), _f(690),
                    _f(20), _f(15), _f(1020), _f(416), _f(20), _f(32),
                    _f(20), _f(438), _f(1020), _f(1),
                    _f(20), _f(446), _f(1020), _f(140), _f(17),
                    _buttons(595, ("key_red", "key_green")))

    def __init__(self, session):
        Screen.__init__(self, session)
        self.setTitle("SatWatch - transpondery - ustawienia")
        self["help"] = Label("")
        from . import pick
        self.pick = pick
        # pozycje, bukiety i adres - wybierane z listy (OK); w wierszu skrot, zapis przy "Zapisz"
        self.v_positions, self.v_ignore, self.v_url, self.v_urls = cfg.positions.value, cfg.ignore_bq.value, cfg.url.value, cfg.urls.value
        self.p_positions = NoSave(ConfigSelection(choices=[("v", pick.positions_text(self.v_positions))]))
        self.p_bouquets = NoSave(ConfigSelection(choices=[("v", pick.bouquets_text(self.v_ignore))]))
        self.p_url = NoSave(ConfigSelection(choices=[("v", pick.url_text(self.v_url))]))
        self.proxy_key = {id(self.p_positions): "positions", id(self.p_bouquets): "ignore_bq", id(self.p_url): "url"}
        self["key_red"] = StaticText("Anuluj")
        self["key_green"] = StaticText("Zapisz")
        lst = [
            getConfigListEntry("Akcja przy zmianach satellites.xml", cfg.action),
            getConfigListEntry("Skanuj zmienione TP automatycznie", cfg.autoscan),
            getConfigListEntry("Pełny skan raz w tygodniu (w harmonogramie EPG Scan)", cfg.full_enable),
            getConfigListEntry("  dzień", cfg.full_day),
            getConfigListEntry("Usuwaj nieaktualne serwisy po skanie", cfg.remove_stale),
            getConfigListEntry("Podmieniaj kanały w bukietach automatycznie", cfg.autoreplace),
            getConfigListEntry("Czas na skan mimo zajętych tunerów", cfg.wait_max),
            getConfigListEntry("Wznów przerwany skan, gdy tunery wolne", cfg.resume),
            getConfigListEntry("Rezerwa wolnych tunerów", cfg.reserve),
            getConfigListEntry("Preferowany tuner do skanu (zajęty - inny wolny)", cfg.scan_tuner),
            getConfigListEntry("Monitorowane pozycje (OK - lista)", self.p_positions),
            getConfigListEntry("Zakres aktualizacji pliku", cfg.mode),
            getConfigListEntry("Tolerancja częstotliwości [MHz]", cfg.tolerance),
            getConfigListEntry("Kanały z bukietów (OK - lista)", self.p_bouquets),
            getConfigListEntry("Zrzut i raport bukietów przy sprawdzaniu", cfg.snapshot),
            getConfigListEntry("Przeładuj transpondery po aktualizacji", cfg.reload),
            getConfigListEntry("Katalog logu i historii raportów", cfg.logdir),
            getConfigListEntry("Powiadomienie na ekranie", cfg.popup),
            getConfigListEntry("Liczba kopii satellites.xml", cfg.backups),
            getConfigListEntry("Weryfikacja certyfikatu SSL", cfg.verify_ssl),
            getConfigListEntry("Adres satellites.xml (OK - lista)", self.p_url),
        ]
        ConfigListScreen.__init__(self, lst, session=session)
        self["config"].onSelectionChanged.append(self.showHelp)
        self.onLayoutFinish.append(self.showHelp)
        self["setupActions"] = ActionMap(["SetupActions", "ColorActions"], {
            "ok": self.keyOk,
            "cancel": self.keyCancel,
            "red": self.keyCancel,
            "green": self.keySave,
            "save": self.keySave,
        }, -2)

    HELP = {
        "action": "Co zrobić, gdy satellites.xml w repozytorium OpenPLi różni się od lokalnego dla monitorowanych pozycji. "
                  "Tylko raport: zapis w raporcie, plik bez zmian. Raport + aktualizacja: zapis /etc/enigma2/satellites.xml "
                  "(z kopią; /etc/tuxbox/satellites.xml nie jest zmieniany) i skan zmienionych TP.",
        "autoscan": "Po aktualizacji satellites.xml skanuje tylko nowe i zmienione transpondery jednym wolnym tunerem. "
                    "Sprawdzenie: w nocnym przebiegu EPG Scan albo Kanały -> SatWatch -> Sprawdź teraz.",
        "full": "Raz w tygodniu, w wybrany dzień, nocny przebieg EPG Scan robi pełny skan wszystkich TP monitorowanych pozycji "
                "(13.0E: ok. 70 TP, kilka minut); w pozostałe dni - tylko sprawdzenie zmian. Harmonogram EPG Scan określa "
                "wyłącznie kiedy ten przebieg może się uruchomić.",
        "remove_stale": "Serwisy, których skan nie znalazł na zeskanowanym TP, są usuwane z lamedb (TP z mniej niż połową "
                        "znanych kanałów - nic nie usuwa). Wyłączone: zostają, ale raport je wypisuje, a kanały z bukietów "
                        "na nich trafiają do podmiany / decyzji.",
        "autoreplace": "Zgubiony kanał w bukiecie jest podmieniany sam tylko przy pewnym kandydacie (ta sama nazwa, ta sama "
                       "po pominięciu HD/spacji, jeden podobny). Reszta - do decyzji. Wyłączone: wszystko do decyzji. "
                       "Przed zmianą kopia bukietów.",
        "wait_max": "Jak długo skan czeka na wolny tuner (albo na zgodę Enigmy przy FBC), zanim zostanie przerwany. "
                    "Nieskanowane TP mogą być dokończone później (opcja niżej).",
        "resume": "Przerwany skan (brak tunera, użycie odbiornika) jest dokończony później, gdy tunery będą wolne - do 24 h.",
        "reserve": "Tyle tunerów skan zawsze zostawia wolnych - na oglądanie, nagrania i streamy (1 tuner w dekoderze: ustaw 0).",
        "scan_tuner": "Pierwszy do próby, gdy wolny; zajęty - skan od razu bierze inny wolny. W systemie ustaw temu tunerowi "
                      "niższy priorytet (1), żeby Enigma brała go do oglądania na końcu.",
        "positions": "OK - lista pozycji z satellites.xml (na górze te, które masz w tunerach) do zaznaczenia. Tylko zaznaczone "
                     "pozycje są porównywane, aktualizowane i skanowane.",
        "mode": "Wybrane pozycje: w lokalnym satellites.xml zmieniane są tylko sekcje monitorowanych pozycji, reszta zostaje. "
                "Cały plik: lokalny plik zastępowany w całości plikiem zdalnym.",
        "tolerance": "Transponder przesunięty o najwyżej tyle MHz jest uznawany za ten sam (bez skanu i bez zmiany TP kanałów); "
                     "ta sama tolerancja przy przypisaniu serwisów do zeskanowanych TP.",
        "ignore_bq": "OK - lista bukietów TV i radio do zaznaczenia. SatWatch pilnuje zaznaczonych (raport, podmiany, decyzje); "
                     "bukiet dodany później jest pilnowany od razu. Domyślnie odznaczony tylko Last Scanned.",
        "snapshot": "Przy każdym sprawdzeniu: raport bukietów względem zrzutu referencyjnego (zgubione kanały, zmiany nazw "
                    "i TP) i zapis nowego zrzutu. Ręcznie: Kanały -> SatWatch -> Zapisz zrzut / Podgląd bukietów.",
        "reload": "Po aktualizacji satellites.xml przeładowuje transpondery w Enigma2 bez restartu. Wyłączone: zmiana działa "
                  "po restarcie GUI (może go zrobić 'Restart po nocnym przebiegu' w EPG Scan).",
        "logdir": "Gdzie trzymać satwatch.log i historię raportów (30 ostatnich). Dysk/USB oszczędza pamięć flash; gdy nośnik "
                  "jest niedostępny, log trafia do pamięci wewnętrznej. Raporty i log: Info/EPG -> Raporty.",
        "popup": "Komunikat na ekranie po sprawdzeniu lub skanie: nigdy / tylko gdy coś się zmieniło / zawsze.",
        "backups": "Ile kopii satellites.xml sprzed aktualizacji trzymać. Przywracanie: Kanały -> SatWatch -> "
                   "Przywróć kopię satellites.xml.",
        "verify_ssl": "Sprawdzanie certyfikatu HTTPS przy pobieraniu pliku zdalnego. Wyłącz tylko, gdy pobieranie kończy się "
                      "błędem SSL (nieaktualne certyfikaty w obrazie).",
        "url": "OK - lista adresów pliku satellites.xml do porównania: wbudowany (OpenPLi tuxbox-xml) i dopisane przez Ciebie "
               "(sprawdzane przed zapisem). Jeden ustawiasz jako domyślny.",
    }

    def showHelp(self):
        try:
            item = self["config"].getCurrent()[1]
        except Exception:
            return
        key = self.proxy_key.get(id(item))
        for k in ([] if key else self.HELP):
            if k == "full":
                if item in (cfg.full_enable, cfg.full_day):
                    key = k
            elif getattr(cfg, k, None) is item:
                key = k
            if key:
                break
        txt = self.HELP.get(key, "")
        if item in (cfg.scan_tuner, cfg.reserve, cfg.wait_max):
            p = preferred_slot()
            txt += "\n" + scanner.tuner_advice(-1 if p is None else p)[0] + "  Pełna porada: Kanały -> SatWatch -> " \
                "Konfiguracja tunerów."
        self["help"].setText(txt)

    def keyOk(self):
        try:
            item = self["config"].getCurrent()[1]
        except Exception:
            return
        k = self.proxy_key.get(id(item))
        if k == "positions":
            cur = core.parse_positions(self.v_positions) or [130]
            self.session.openWithCallback(self.gotPositions, self.pick.SatWatchPick, "SatWatch - monitorowane pozycje",
                                          self.pick.position_items(cur),
                                          "OK: zaznacz / odznacz.  Zielony: zapisz.  Czerwony: anuluj.")
        elif k == "ignore_bq":
            ign = core.parse_ignore(self.v_ignore)
            items = [(n + ("   (radio)" if radio else ""), n, n.lower() not in ign) for n, radio in self.pick.bouquet_list()]
            self.session.openWithCallback(self.gotBouquets, self.pick.SatWatchPick, "SatWatch - kanały z bukietów", items)
        elif k == "url":
            self.session.openWithCallback(self.gotUrl, self.pick.SatWatchUrls, self.v_url, self.v_urls)
        else:
            try:
                ConfigListScreen.keyOK(self)
            except Exception:
                pass

    def gotPositions(self, res):
        if res is None:
            return
        if not res:
            self.session.open(MessageBox, "Zaznacz co najmniej jedną pozycję.", MessageBox.TYPE_INFO, timeout=5)
            return
        self.v_positions = ", ".join(core.pos_str(p) for p in res)
        self.setProxy(self.p_positions, self.pick.positions_text(self.v_positions))

    def gotBouquets(self, res):
        if res is None:
            return
        self.v_ignore = self.pick.ignore_from_selection(self.v_ignore, res)
        self.setProxy(self.p_bouquets, self.pick.bouquets_text(self.v_ignore))

    def gotUrl(self, res):
        if res is None:
            return
        self.v_url, self.v_urls = res
        self.setProxy(self.p_url, self.pick.url_text(self.v_url))

    def setProxy(self, el, text):
        try:
            el.setChoices([("v", text)], "v")
        except Exception:
            pass
        try:
            self["config"].invalidateCurrent()
        except Exception:
            pass
        self.showHelp()

    def keySave(self):
        for c, v in ((cfg.positions, self.v_positions), (cfg.ignore_bq, self.v_ignore), (cfg.url, self.v_url),
                     (cfg.urls, self.v_urls)):
            if c.value != v:
                c.value = v
                c.save()
        old = core.out_dir()
        for x in self["config"].list:
            x[1].save()
        configfile.save()
        apply_logdir()
        new = core.out_dir()
        if core.LOG_PREF and new != core.LOG_PREF:
            self.session.open(MessageBox, "Katalog %s jest niedostępny (nośnik niezamontowany?).\n"
                              "Do czasu jego pojawienia się log zostaje w %s." % (core.LOG_PREF, new),
                              MessageBox.TYPE_WARNING, timeout=10)
        elif new != old:
            try:
                n = core.migrate_out_dir(old, new)
                scanner.log("Katalog logu: %s -> %s (skopiowano %d plikow)" % (old, new, n))
            except Exception as e:
                scanner.log("Katalog logu: blad kopiowania %s" % e)
        self.close(True)

    def keyCancel(self):
        for x in self["config"].list:
            x[1].cancel()
        self.close(False)




def init(session):
    """Start w EPG Scan (sessionstart): katalog logu, sesja dla skanera, zegar wznawiania przerwanych skanow."""
    apply_logdir()
    runner.session = session
    runner.schedule()


def full_due(now=None):
    """Czy dzis dzien pelnego skanu (ustawienia SatWatch: pelny skan raz w tygodniu)."""
    if not cfg.full_enable.value:
        return False
    return time.localtime(now or time.time()).tm_wday == int(cfg.full_day.value)


# --------------------------------------------------------------------------
# DO DECYZJI: wybor kandydata recznie (EPG Scan 2.2.5)
# --------------------------------------------------------------------------

def _places(e):
    pl = e.get("places") or []
    s = ", ".join('"%s" poz. %d' % (p[0], p[1]) for p in pl[:3])
    return s + (" (+%d)" % (len(pl) - 3) if len(pl) > 3 else "")


def _cand_label(c):
    sid = core.str_key(c["key"])[0]
    extra = []
    if c.get("same_ts"):
        extra.append("ten sam TS")
    if c.get("in_bq"):
        extra.append("juz w bukietach")
    if c.get("new"):
        extra.append("NOWY")
    if c.get("fallback"):
        extra.append("z tego TP" if c.get("same_ts") else "ten sam nadawca")
    return "-> %s | %s | SID %X | nazwa %d%%%s" % (c["name"], c.get("tp") or "?", sid, int(c["ratio"] * 100),
                                                  (" | " + ", ".join(extra)) if extra else "")


def open_decisions(session, back=None):
    """Lista kanalow DO DECYZJI -> kanal -> kandydat (podmiana / podglad) albo usun / zostaw."""
    def got(res):
        if not isinstance(res, list):
            session.openWithCallback(lambda *a: back and back(), MessageBox,
                                     "SatWatch: %s" % (res.get("summary") if isinstance(res, dict) else res),
                                     MessageBox.TYPE_ERROR, timeout=8)
            return
        if not res:
            session.openWithCallback(lambda *a: back and back(), MessageBox, "Brak kanalow do decyzji.",
                                     MessageBox.TYPE_INFO, timeout=5)
            return
        items = []
        for e in res:
            pl = e.get("places") or []
            items.append(("%s  -  %s%s  (kandydatow: %d)" % (
                e["name"], ('"%s" poz. %d' % (pl[0][0], pl[0][1])) if pl else "?",
                (" +%d" % (len(pl) - 1)) if len(pl) > 1 else "", len(e.get("candidates") or [])), e))
        session.openWithCallback(lambda c: _entry(session, c[1], back) if c else (back and back()), ChoiceBox,
                                 title="SatWatch - do decyzji: %d" % len(res), list=items, keys=[])
    if not runner.run(core.pending_decisions, got, cfg.ignore_bq.value):
        session.openWithCallback(lambda *a: back and back(), MessageBox,
                                 "SatWatch pracuje (%s) - sprobuj za chwile." % runner.status, MessageBox.TYPE_INFO, timeout=6)


_STYPE = {1: "SD (MPEG-2)", 0x11: "HD (MPEG-2)", 0x16: "SD (H.264)", 0x19: "HD (H.264)", 0x1F: "UHD/HD (HEVC)",
          2: "radio", 0x0A: "radio"}


def _stype(t):
    return _STYPE.get(t or 1, "typ %X" % (t or 0))


def _sid(ks):
    return core.str_key(ks)[0]


def _cand_info(e, c, i, n):
    """Opis kandydata pod lista - wszystko, co pomaga zdecydowac."""
    osid, csid = _sid(e["key"]), _sid(c["key"])
    oprov, cprov = (e.get("provider") or "").strip(), (c.get("provider") or "").strip()
    L = ["Kandydat %d/%d:  %s" % (i + 1, n, c["name"])]
    if c.get("fallback"):
        L[0] += "   (nazwa i SID nie pasuja - kanal z tego samego TP / nadawcy)"
    if c.get("same_sid"):
        L.append("SID:          %04X - ZGODNY z dawnym (najmocniejsza wskazowka)" % csid)
    else:
        L.append("SID:          %04X - inny niz dawny (%04X)" % (csid, osid))
    if c.get("same_ts"):
        L.append("Transponder:  %s - ten sam strumien (TS) co dawniej" % (c.get("tp") or "?"))
    else:
        L.append("Transponder:  %s - inny niz dawny (%s)" % (c.get("tp") or "?", e.get("tp") or "?"))
    if cprov and oprov and cprov.lower().rstrip(".") == oprov.lower().rstrip("."):
        L.append("Nadawca:      %s - ten sam" % cprov)
    else:
        L.append("Nadawca:      %s - %s" % (cprov or "?", ("inny niz dawny (%s)" % oprov) if oprov else "dawny nieznany"))
        if not c.get("known_prov", True):
            L[-1] += ", spoza pakietow z Twoich bukietow"
    nm = "Nazwa:        zgodnosc %d%%" % int(c["ratio"] * 100)
    if c["name"].strip().lower() == (e.get("name") or "").strip().lower():
        nm += " - identyczna"
    elif c.get("compact_eq"):
        nm += " - ta sama po pominieciu HD/spacji"
    elif not c.get("first_eq"):
        nm += " - inne pierwsze slowo"
    L.append(nm)
    L.append("Typ:          %s%s" % (_stype(c.get("type")), "" if c.get("type") == e.get("type") else
                                     "  (dawniej %s)" % _stype(e.get("type"))))
    flags = []
    flags.append("JUZ JEST w Twoich bukietach (podmiana zrobi dubel)" if c.get("in_bq") else "nie ma go w Twoich bukietach")
    if c.get("new"):
        flags.append("NOWY w lamedb (pojawil sie od ostatniego sprawdzenia)")
    L.append("Uwagi:        " + "; ".join(flags))
    return "\n".join(L)


class SatWatchDecision(Screen):
    """Jeden kanal DO DECYZJI: krotka lista kandydatow, szczegoly zaznaczonego pod lista."""
    skin = """
    <screen name="SatWatchDecision" position="center,center" size="%d,%d" title="SatWatch - decyzja">
        <widget name="head" position="%d,%d" size="%d,%d" font="Regular;%d" foregroundColor="#00ffcc66" />
        <widget name="list" position="%d,%d" size="%d,%d" font="Regular;%d" itemHeight="%d" scrollbarMode="showOnDemand" />
        <eLabel position="%d,%d" size="%d,%d" backgroundColor="#00505050" />
        <widget source="session.VideoPicture" render="Pig" position="%d,%d" size="%d,%d" zPosition="1" backgroundColor="#ff000000" />
        <eLabel position="%d,%d" size="%d,%d" backgroundColor="#00505050" />
        <widget name="info" position="%d,%d" size="%d,%d" font="Regular;%d" />
        %s
    </screen>""" % (_f(1200), _f(690),
                    _f(20), _f(8), _f(578), _f(150), _f(18),
                    _f(20), _f(166), _f(578), _f(160), _f(19), _f(32),
                    _f(618), _f(8), _f(564), _f(319),
                    _f(620), _f(10), _f(560), _f(315),
                    _f(20), _f(338), _f(1160), _f(2),
                    _f(20), _f(346), _f(1160), _f(258), _f(18),
                    _buttons(615, ("key_red", "key_green", "key_yellow", "key_blue")))

    def __init__(self, session, e):
        Screen.__init__(self, session)
        self.e = e
        self.cands = (e.get("candidates") or [])[:20]
        try:
            e["reason"] = core.decide(e)[1]
        except Exception:
            pass
        self.setTitle("SatWatch - decyzja: %s" % e["name"])
        self["head"] = Label("Bylo:   %s  |  %s\n            %s\n            SID %04X  |  %s\nGdzie: %s\nPowod: %s" % (
            e["name"], e.get("provider") or "?", e.get("tp") or "?", _sid(e["key"]), _stype(e.get("type")),
            _places(e), e.get("reason") or "-"))
        items = []
        for c in self.cands:
            tag = []
            if c.get("same_sid"):
                tag.append("SID zgodny")
            if c.get("in_bq"):
                tag.append("w bukietach")
            items.append("%s  |  SID %04X%s" % (c["name"], _sid(c["key"]), ("  |  " + ", ".join(tag)) if tag else ""))
        if not items:
            items = ["(brak kandydatow - usun kanal albo zostaw)"]
        self["list"] = MenuList(items)
        self["info"] = Label("")
        self["key_red"] = StaticText("Wroc")
        self["key_green"] = StaticText("Podglad" if self.cands else "")
        self["key_yellow"] = StaticText("Usun z bukietow")
        self["key_blue"] = StaticText("Zostaw jak jest")
        self["actions"] = ActionMap(["OkCancelActions", "ColorActions"], {
            "ok": self.keyOk,
            "cancel": self.close,
            "red": self.close,
            "green": self.keyPlay,
            "yellow": self.keyRemove,
            "blue": self.keyKeep,
        }, -1)
        self["list"].onSelectionChanged.append(self.update)
        self.onLayoutFinish.append(self.update)
        try:
            self.orig = session.nav.getCurrentlyPlayingServiceOrGroup()
        except Exception:
            self.orig = None
        self.played = False
        self.onClose.append(self.restore)

    def restore(self):
        """Po wyjsciu z decyzji wraca kanal ogladany wczesniej (gdy byl podglad)."""
        try:
            import Screens.Standby
            if Screens.Standby.inStandby:
                self.played = False
                self.orig = None
                return
        except Exception:
            pass
        if self.played and self.orig is not None:
            try:
                self.session.nav.playService(self.orig)
            except Exception:
                pass

    def cur(self):
        i = self["list"].getSelectedIndex()
        return (i, self.cands[i]) if 0 <= i < len(self.cands) else (i, None)

    def update(self):
        i, c = self.cur()
        if c is None:
            self["info"].setText("Brak kandydatow w lamedb. Zolty: usun kanal z bukietow, niebieski: zostaw.")
            return
        self["info"].setText(_cand_info(self.e, c, i, len(self.cands)) +
                             "\n\nOK: podmien na ten kanal   |   zielony: podglad w okienku obok (po wyjsciu wraca poprzedni kanal)")

    def keyOk(self):
        i, c = self.cur()
        if c is None:
            return
        self.session.openWithCallback(lambda yes: yes and self.close(("replace", c)), MessageBox,
                                      "Podmienic %s na %s (SID %04X, %s) we wszystkich bukietach?" % (
                                          self.e["name"], c["name"], _sid(c["key"]), c.get("tp") or "?"),
                                      MessageBox.TYPE_YESNO)

    def keyPlay(self):
        i, c = self.cur()
        if c is None:
            return
        try:
            self.session.nav.playService(eServiceReference(c["ref"]))
            self.played = True
        except Exception as ex:
            scanner.log("Podglad %s: %s" % (c["ref"], ex))

    def keyRemove(self):
        self.session.openWithCallback(lambda yes: yes and self.close(("remove", None)), MessageBox,
                                      "Usunac %s z bukietow (%s)?" % (self.e["name"], _places(self.e)),
                                      MessageBox.TYPE_YESNO)

    def keyKeep(self):
        self.close(("keep", None))


def _entry(session, e, back=None):
    def done(res=None):
        if res:
            _resolve(session, e, res[0], res[1], back)
        else:
            open_decisions(session, back)
    session.openWithCallback(done, SatWatchDecision, e)


def _resolve(session, e, action, c, back=None):
    def done(res):
        if isinstance(res, tuple):
            msg, changed = res
            if changed:
                reload_lists()
            scanner.log("Decyzja: %s - %s" % (e["name"], msg))
        else:
            msg = res.get("summary") if isinstance(res, dict) else str(res)
        session.openWithCallback(lambda *a: open_decisions(session, back), MessageBox, "%s: %s" % (e["name"], msg),
                                 MessageBox.TYPE_INFO, timeout=4)
    if not runner.run(core.resolve_decision, done, e["key"], action, c, cfg.ignore_bq.value):
        session.open(MessageBox, "SatWatch pracuje - sprobuj za chwile.", MessageBox.TYPE_INFO, timeout=5)
