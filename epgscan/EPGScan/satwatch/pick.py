# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# SatWatch - listy wyboru w ustawieniach: monitorowane pozycje, bukiety, adres satellites.xml.
from Screens.Screen import Screen
from Screens.MessageBox import MessageBox
from Components.ActionMap import ActionMap
from Components.Label import Label
from Components.MenuList import MenuList
from Components.SelectionList import SelectionList, SelectionEntryComponent

from ..common import keys_skin, init_keys, set_keys
from ..i18n import _
from . import core

# ten sam uklad co lista bukietow EPG Scan (setup.EPGScanBouquets)
SK = """<widget name="list" position="20,15" size="1560,720" scrollbarMode="showOnDemand" />
\t\t<eLabel position="20,745" size="1560,2" backgroundColor="#00606060" />
\t\t<widget name="%s" position="20,755" size="1560,80" font="Regular;28" foregroundColor="#00b0b0b0" />"""

BUILTIN_URLS = (("OpenPLi (GitHub, tuxbox-xml)", core.DEFAULT_URL),)


# ----------------------------------------------------------------- pozycje

def tuner_positions():
    """Pozycje skonfigurowane w tunerach DVB-S dekodera (orbpos jak w satellites.xml, W ujemne)."""
    out = set()
    try:
        from Components.NimManager import nimmanager
        for n in nimmanager.nim_slots:
            try:
                if n.isCompatible("DVB-S") and n.isEnabled():
                    for x in nimmanager.getSatListForNim(n.slot):
                        out.add(core.norm_pos(x[0]))
            except Exception:
                pass
    except Exception:
        pass
    return out


def position_items(current):
    """[(opis, pos, zaznaczona)] - najpierw pozycje z tunerow, potem reszta z satellites.xml."""
    names = {}
    try:
        root, sats = core.parse_sat_xml(core.local_xml_path())
        for p, el in sats.items():
            names[p] = core.clean_name(el.get("name") or "")
    except Exception:
        pass
    tun = tuner_positions()
    cur = set(current)
    allp = set(names) | cur | tun

    def label(p):
        n = names.get(p, "")
        if n and n.startswith(core.pos_str(p)):
            n = n[len(core.pos_str(p)):].strip()
        tag = []
        if p in tun:
            tag.append(_("w tunerach"))
        if p not in names:
            tag.append(_("brak w satellites.xml"))
        return "%s  %s%s" % (core.pos_str(p), n, ("   [%s]" % ", ".join(tag)) if tag else "")

    def west_east(p):
        return (p not in tun, p if p >= 0 else 10000 - p)
    return [(label(p), p, p in cur) for p in sorted(allp, key=west_east)]


def positions_text(value):
    ps = core.parse_positions(value) or [130]
    return ", ".join(core.pos_str(p) for p in ps)


# ----------------------------------------------------------------- bukiety

def bouquet_names():
    """Nazwy bukietow TV i radio (z podbukietami, bez grup alternatyw), bez powtorzen, kolejnosc z bouquets.tv/.radio."""
    return [n for n, radio in bouquet_list()]


def bouquet_list():
    """[(nazwa, radio)] - jak bouquet_names, z informacja, czy to bukiet radiowy."""
    out, seen = [], set()
    try:
        for fname, bname in core._bouquet_files(core.E2DIR):
            if fname.startswith("bouquets.") or fname.startswith("alternatives."):
                continue
            k = bname.lower()
            if k not in seen:
                seen.add(k)
                out.append((bname, fname.endswith(".radio")))
    except Exception:
        pass
    return out


def bouquets_text(ignore_value):
    names = bouquet_names()
    ign = core.parse_ignore(ignore_value)
    on = [n for n in names if n.lower() not in ign]
    if not names:
        return _("wszystkie")
    return _("wszystkie (%d)") % len(names) if len(on) == len(names) else _("%d z %d") % (len(on), len(names))


def ignore_from_selection(ignore_value, chosen):
    """Nowa lista pomijanych: odznaczone bukiety + pomijane, ktorych dzis nie ma (np. Last Scanned po czyszczeniu)."""
    names = bouquet_names()
    low = set(n.lower() for n in names)
    keep = [x.strip() for x in (ignore_value or "").replace(";", ",").split(",") if x.strip() and x.strip().lower() not in low]
    off = [n for n in names if n not in chosen]
    return ", ".join(off + keep)


# ----------------------------------------------------------------- adresy

def url_list(custom_value):
    out = list(BUILTIN_URLS)
    for u in (custom_value or "").split("|"):
        u = u.strip()
        if u and u not in [x[1] for x in out]:
            out.append(("", u))
    return out


def url_text(url):
    for name, u in BUILTIN_URLS:
        if u == url:
            return name
    try:
        return url.split("/")[2]
    except Exception:
        return url


# ----------------------------------------------------------------- ekrany

class SatWatchPick(Screen):
    """Lista z polami wyboru: OK - zaznacz / odznacz, zielony - zapisz. Zwraca liste wartosci albo None."""
    skin = """
    <screen position="center,center" size="1600,920" title="SatWatch">
        %s
        %s
    </screen>""" % ((SK % "info").replace("\t\t", "        "), keys_skin(20, 855, 1560))

    def __init__(self, session, title, items, info=""):
        Screen.__init__(self, session)
        self.setTitle(title)
        self["list"] = SelectionList([SelectionEntryComponent(t, v, i, s) for i, (t, v, s) in enumerate(items)])
        self["info"] = Label(info or _("OK: zaznacz / odznacz.  Zielony: zapisz.  Czerwony: anuluj."))
        init_keys(self)
        self["actions"] = ActionMap(["OkCancelActions", "ColorActions"], {
            "ok": self["list"].toggleSelection,
            "green": self.save,
            "red": self.cancel,
            "cancel": self.cancel,
        }, -1)
        self.onLayoutFinish.append(self.layout)

    def layout(self):
        try:
            from ..setup import big_list
            big_list(self["list"])
        except Exception:
            pass
        set_keys(self, red=_("Anuluj"), green=_("Zapisz"))

    def save(self):
        self.close([x[1] for x in self["list"].getSelectionsList()])

    def cancel(self):
        self.close(None)


class SatWatchUrls(Screen):
    """Adresy satellites.xml: OK - ustaw jako domyslny, zolty - dodaj, zielony - popraw, niebieski - usun (dopisane).
    Zwraca (domyslny, dopisane 'a|b') albo None."""
    skin = """
    <screen position="center,center" size="1600,920" title="SatWatch - adres satellites.xml">
        <widget name="list" position="20,15" size="1560,500" font="Regular;28" itemHeight="46" scrollbarMode="showOnDemand" />
        <eLabel position="20,535" size="1560,2" backgroundColor="#00606060" />
        <widget name="info" position="20,545" size="1560,200" font="Regular;26" foregroundColor="#00b0b0b0" />
        %s
    </screen>""" % keys_skin(20, 855, 1560)

    def __init__(self, session, current, custom):
        Screen.__init__(self, session)
        self.setTitle(_("SatWatch - adres satellites.xml"))
        self.current = current
        self.edit_of = None
        self.custom = [u for u in (custom or "").split("|") if u.strip()]
        if current and current not in [x[1] for x in BUILTIN_URLS] and current not in self.custom:
            self.custom.append(current)
        self["list"] = MenuList([])
        self["info"] = Label("")
        init_keys(self)
        self["actions"] = ActionMap(["OkCancelActions", "ColorActions"], {
            "ok": self.set_default,
            "yellow": self.add,
            "green": self.edit,
            "blue": self.remove,
            "red": self.done,
            "cancel": self.done,
        }, -1)
        self["list"].onSelectionChanged.append(self.show_info)
        self.onLayoutFinish.append(self.fill)

    def entries(self):
        return url_list("|".join(self.custom))

    def fill(self, keep=None):
        lst = []
        for name, u in self.entries():
            mark = "* " if u == self.current else "   "
            lst.append(("%s%s%s" % (mark, ("%s - " % name) if name else "", u), u))
        self["list"].setList(lst)
        if keep:
            for i, x in enumerate(lst):
                if x[1] == keep:
                    self["list"].moveToIndex(i)
                    break
        set_keys(self, red=_("Gotowe"), green=_("Popraw"), yellow=_("Dodaj adres"), blue=_("Usun"))
        self.show_info()

    def cur(self):
        c = self["list"].getCurrent()
        return c[1] if c else None

    def show_info(self):
        u = self.cur()
        if not u:
            return
        builtin = u in [x[1] for x in BUILTIN_URLS]
        self["info"].setText(_("%s\nOK - ustaw jako domyslny (* = uzywany). Zolty - dodaj adres (sprawdzany przed zapisem). "
                             "Zielony / niebieski - popraw / usun dopisany%s.") % (
                                 u, _(" (wbudowanego nie mozna zmienic)") if builtin else ""))

    def set_default(self):
        u = self.cur()
        if u:
            self.current = u
            self.fill(u)

    def add(self, text="https://"):
        from Screens.VirtualKeyBoard import VirtualKeyBoard
        self.edit_of = None
        self.session.openWithCallback(self.typed, VirtualKeyBoard, title=_("Adres pliku satellites.xml"), text=text)

    def edit(self):
        u = self.cur()
        if not u or u in [x[1] for x in BUILTIN_URLS]:
            return
        from Screens.VirtualKeyBoard import VirtualKeyBoard
        self.edit_of = u
        self.session.openWithCallback(self.typed, VirtualKeyBoard, title=_("Adres pliku satellites.xml"), text=u)

    def remove(self):
        u = self.cur()
        if not u or u not in self.custom:
            return
        self.custom.remove(u)
        if self.current == u:
            self.current = core.DEFAULT_URL
        self.fill(self.current)

    def typed(self, url):
        url = (url or "").strip()
        if not url or url in ("http://", "https://"):
            return
        self["info"].setText(_("Sprawdzam %s ...") % url)
        from twisted.internet import threads
        d = threads.deferToThread(check_url, url)
        d.addCallback(lambda res: self.checked(url, res))
        d.addErrback(lambda f: self.checked(url, str(f.value)))

    def checked(self, url, res):
        if res is not True:
            self.session.open(MessageBox, _("Pod adresem nie ma poprawnego satellites.xml:\n%s\n\n%s") % (url, res),
                              MessageBox.TYPE_ERROR, timeout=10)
            self.show_info()
            return
        if self.edit_of and self.edit_of in self.custom:
            i = self.custom.index(self.edit_of)
            self.custom[i] = url
            if self.current == self.edit_of:
                self.current = url
        elif url not in [x[1] for x in self.entries()]:
            self.custom.append(url)
        self.fill(url)

    def done(self):
        self.close((self.current, "|".join(self.custom)))


def check_url(url):
    """True albo opis bledu - plik pobierany i sprawdzany (root <satellites>, elementy <sat>)."""
    import ssl
    from urllib.request import Request, urlopen
    try:
        req = Request(url, headers={"User-Agent": "SatWatch/%s (Enigma2)" % core.VERSION})
        data = urlopen(req, timeout=30, context=ssl.create_default_context()).read()
        root, sats = core.parse_sat_xml(data)
        return True
    except Exception as e:
        return str(e)


from ..common import scale_skins as _scale_skins  # noqa: E402
_scale_skins(globals())
