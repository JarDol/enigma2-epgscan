# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan 2.0 - Akcje (profil startu), menu Ustawien / Raportow / Kanalow, podglad plikow tekstowych.

import os
import time

from Screens.Screen import Screen
from Screens.MessageBox import MessageBox
from .common import EPGChoiceBox as ChoiceBox
from Components.ActionMap import ActionMap
from Components.Label import Label
from Components.ConfigList import ConfigListScreen
from Components.ScrollLabel import ScrollLabel
from Components.config import getConfigListEntry, ConfigSelection, ConfigYesNo, NoSave

from .common import cfg, log, save_cfg, VERSION, keys_skin, init_keys, set_keys, hints_skin, init_hints, CLEAR_CHOICES, \
	profile_default, profile_text, log_path, backup_dir, SRV_CHOICES, SW_CHOICES
from .i18n import _, language


ACTION_HINTS = (("OK", "Otworz"), ("Menu", "Harmonogram"), ("Info/EPG", "Opis"))


class EPGScanActions(Screen, ConfigListScreen):
	"""Zielony na ekranie glownym: co zrobic teraz. Wstepnie profil domyslny; zolty zapisuje go jako domyslny
	(OK na ekranie glownym i harmonogram)."""
	skin = """
	<screen position="center,center" size="1600,920" title="EPG Scan - akcje">
		<widget name="config" position="20,15" size="1560,375" font="Regular;30" secondfont="Regular;30" itemHeight="46" scrollbarMode="showOnDemand" />
		<widget name="help" position="20,400" size="1560,330" font="Regular;28" />
		%s
		%s
	</screen>""" % (hints_skin(20, 780, 1560, hints=ACTION_HINTS), keys_skin(20, 850, 1560))

	def __init__(self, session):
		Screen.__init__(self, session)
		self.setTitle(_("EPG Scan %s - akcje") % VERSION)
		p = profile_default()
		choices = list(CLEAR_CHOICES)
		try:
			choices = [c for c in CLEAR_CHOICES if c[0] in [x[0] for x in cfg.clear_mode.choices.choices]]
		except Exception:
			pass
		self.c_sw = NoSave(ConfigSelection(default=p.get("sw", "nie"), choices=[(v, _(t)) for v, t in SW_CHOICES]))
		self.c_clear = NoSave(ConfigSelection(default=p["clear"], choices=[(v, _(t)) for v, t in choices]))
		self.c_sat = NoSave(ConfigYesNo(default=p["sat"]))
		self.c_dek = NoSave(ConfigYesNo(default=p["dek"]))
		self.c_srv = NoSave(ConfigSelection(default=p["srv"], choices=[(v, _(t)) for v, t in SRV_CHOICES]))
		self.c_other = NoSave(ConfigYesNo(default=p["other"]))
		self.c_fetch = NoSave(ConfigSelection(default="nie" if not p["fetch"] else ("jedno" if p.get("one") else "nowe"), choices=[
			("nie", _("nie")), ("jedno", _("tak - w jednym miejscu (gotowa dzisiejsza paczka drugiej strony)")),
			("nowe", _("tak - pobierz na nowo"))]))
		self.c_channelparams = NoSave(ConfigYesNo(default=p.get("channelparams", False)))
		self["help"] = Label("")
		init_keys(self)
		init_hints(self, ACTION_HINTS)
		ConfigListScreen.__init__(self, [
			getConfigListEntry(_("SatWatch: zmiany na satelicie i bukiety"), self.c_sw),
			getConfigListEntry(_("Czysc EPG przed startem"), self.c_clear),
			getConfigListEntry(_("Satelita (skan transponderow)"), self.c_sat),
			getConfigListEntry(_("Dekoder (program pobierany przez dekoder)"), self.c_dek),
			getConfigListEntry(_("Serwer epgpl"), self.c_srv),
			getConfigListEntry(_("Pozostale zrodla XMLTV"), self.c_other),
			getConfigListEntry(_("Pobierz swieze dane"), self.c_fetch),
			getConfigListEntry(_("Skan parametrow kanalow (PAT/PMT)"), self.c_channelparams),
		], session=session, on_change=self.update_help)
		self["actions"] = ActionMap(["SetupActions", "ColorActions", "MenuActions", "EPGSelectActions"], {
			"ok": self.open_selected, "green": self.go, "save": self.go,
			"yellow": self.save_default,
			"cancel": self.close, "red": self.close,
			"menu": self.open_schedule, "info": self.show_help,
			"showEventInfo": self.show_help, "showEPGList": self.show_help,
		}, -2)
		try:
			self["config"].onSelectionChanged.append(self.update_help)
		except Exception:
			pass
		self.onLayoutFinish.append(self.update_help)

	def profile(self):
		return {"sw": self.c_sw.value, "clear": self.c_clear.value, "sat": bool(self.c_sat.value), "dek": bool(self.c_dek.value),
			"srv": self.c_srv.value, "other": bool(self.c_other.value), "fetch": self.c_fetch.value != "nie",
			"one": self.c_fetch.value == "jedno", "channelparams": bool(self.c_channelparams.value)}

	def update_help(self):
		p = self.profile()
		set_keys(self, red="Anuluj", green="Uruchom", yellow="Zapisz jako domyslne")
		cur = self["config"].getCurrent()
		action = cur[0] if cur else _("Profil")
		self["help"].setText(_("%s: %s\nOK: otworz modul lub wybierz tryb.  Zielony: uruchom profil.\n"
			"Info/EPG: pelny opis.  Menu: harmonogram.  Zolty: zapisz jako domyslne.") % (action, profile_text(p, localized=True)))

	def full_help(self):
		p = self.profile()
		lines = [_("Kolejnosc: %s  (zmiana: Ustawienia -> Program z internetu)") % profile_text(p, localized=True)]
		if p.get("channelparams"):
			lines.append(_("Po zakonczonym EPG uruchom skan parametrow PAT/PMT kanalow z wybranych bukietow."))
		if p["sw"] != "nie":
			lines.append(_("SatWatch najpierw (wolne tunery, najwyzej 15 min czekania); po zmianach w kanalach lista "
				"transponderow i mapa kanalow budowane od nowa."))
		if not cfg.xmltv.value:
			lines.append(_("UWAGA: program z internetu jest wylaczony w ustawieniach - bedzie tylko satelita."))
		if p["fetch"]:
			f = []
			if p["dek"] and p.get("one"):
				f.append(_("dekoder bierze dzisiejsza pelna paczke serwera, gdy jest - inaczej pobiera sam i wysyla paczke na serwer"))
			elif p["dek"]:
				f.append(_("dekoder pobiera teraz (ok. 5 min)"))
			if p["srv"] == "zawsze" and p.get("one") and p["dek"]:
				f.append(_("serwer nie jest proszony o pobranie (jedno miejsce)"))
			elif p["srv"] == "zawsze":
				f.append(_("serwer pobiera teraz (ok. 3 min)"))
			elif p["srv"] == "zapas":
				f.append(_("serwer tylko, gdy jego plik ma ponad 30 h"))
			lines.append(_("Pobranie: ") + (", ".join(f) if f else _("nic do pobrania")) + _("; import czeka na koniec."))
		else:
			lines.append(_("Pobranie: nie - import z gotowych plikow; gdy pliku nie ma albo ma ponad 30 h, dekoder / serwer pobiera i tak."))
		if not p["sat"]:
			lines.append(_("Bez satelity: sam import XMLTV. %s") % (_("EIT jest wylaczane (Ustawienia -> Satelita).")
				if cfg.eit_force_off.value else ""))
		lines.append(_("Satelita zawsze ma pierwszenstwo: XMLTV uzupelnia tylko to, czego nie dal satelita "
			"(EIT ma w Enigma2 wyzszy priorytet niz import)."))
		lines.append(_("Profil domyslny (OK na ekranie glownym i harmonogram): %s") % profile_text(profile_default(), localized=True))
		lines += ["", _("OK: otworz modul zaznaczonej pozycji lub wybierz tryb. Zielony: uruchom profil."),
		_("Zolty: zapisz ten profil jako domyslny dla OK i harmonogramu."),
		_("Menu: otworz istniejace ustawienia harmonogramu. Czerwony: wroc.")]
		return "\n".join(lines)

	def show_help(self):
		self.session.open(EPGScanText, _("pelny opis akcji"), text=self.full_help())

	def open_schedule(self):
		from .setup import EPGScanSetup
		self.session.open(EPGScanSetup, "sched")

	def open_selected(self):
		current = self["config"].getCurrent()
		if not current:
			self.session.open(MessageBox, _("Wybierz pozycje z listy."), MessageBox.TYPE_INFO, timeout=4)
			return
		element = current[1]
		if element is self.c_sw:
			satwatch_menu(self.session)
		elif element is self.c_clear or element is self.c_fetch:
			self.choose_profile_option(current[0], element)
		elif element is self.c_sat:
			from .setup import EPGScanSetup
			self.session.open(EPGScanSetup, "sat")
		elif element is self.c_dek:
			from .mapping import EPGScanMapping
			self.session.open(EPGScanMapping, None, "dekoder")
		elif element is self.c_srv:
			self.open_server_mapping()
		elif element is self.c_other:
			from .setup import EPGScanXmltvSources
			self.session.open(EPGScanXmltvSources)
		elif element is self.c_channelparams:
			from .channelparamsui import ChannelParamsScreen
			self.session.open(ChannelParamsScreen)
		else:
			self.session.open(MessageBox, _("Brak modulu dla wybranej pozycji."), MessageBox.TYPE_ERROR, timeout=4)

	def choose_profile_option(self, title, element):
		options = [(label, value) for value, label in element.choices.choices]
		if not options:
			self.session.open(MessageBox, _("Brak dostepnych ustawien: %s.") % title, MessageBox.TYPE_ERROR, timeout=4)
			return
		selection = next((i for i, option in enumerate(options) if option[1] == element.value), 0)

		def chosen(choice):
			if choice:
				element.value = choice[1]
				self["config"].invalidateCurrent()
				self.update_help()

		self.session.openWithCallback(chosen, ChoiceBox, title=title, list=options, selection=selection, keys=[])

	def open_server_mapping(self):
		try:
			from .mapping import epgpl_servers, EPGScanMapping
			servers = epgpl_servers()
		except Exception as e:
			log("Nie mozna odczytac serwerow epgpl: %s" % e)
			self.session.open(MessageBox, _("Nie mozna odczytac serwerow epgpl:\n%s") % e, MessageBox.TYPE_ERROR)
			return
		if not servers:
			self.session.open(MessageBox, _("Brak skonfigurowanego serwera epgpl w zrodlach XMLTV."),
				MessageBox.TYPE_INFO, timeout=6)
		elif len(servers) == 1:
			self.session.open(EPGScanMapping, *servers[0])
		else:
			options = [(desc or base, (base, desc)) for base, desc in servers]

			def chosen(choice):
				if choice:
					self.session.open(EPGScanMapping, *choice[1])

			self.session.openWithCallback(chosen, ChoiceBox, title=_("Wybierz serwer epgpl"), list=options, keys=[])

	def go(self):
		self.close(self.profile())

	def save_default(self):
		p = self.profile()
		cfg.p_sw.value = p["sw"]
		cfg.clear_mode.value = p["clear"]
		cfg.sat_import.value = p["sat"]
		cfg.p_dek.value = p["dek"]
		cfg.p_srv.value = p["srv"]
		cfg.p_other.value = p["other"]
		cfg.p_fetch.value = p["fetch"]
		cfg.p_channelparams.value = p["channelparams"]
		if p["fetch"]:
			cfg.p_one.value = p["one"]
		for x in (cfg.p_sw, cfg.clear_mode, cfg.sat_import, cfg.p_dek, cfg.p_srv, cfg.p_other, cfg.p_fetch, cfg.p_one,
				cfg.p_channelparams):
			x.save()
		save_cfg()
		try:
			from .common import apply_eit_policy
			apply_eit_policy()
			from .mapping import report_schedule
			report_schedule()
		except Exception:
			pass
		log("Profil domyslny: %s, czyszczenie %s, pobranie %s" % (profile_text(p), p["clear"], p["fetch"]))
		self.session.open(MessageBox, _("Zapisano jako domyslne (OK i harmonogram):\n%s") % profile_text(p, localized=True),
			MessageBox.TYPE_INFO, timeout=4)
		self.update_help()


class EPGScanText(Screen):
	"""Podglad pliku tekstowego (log, raporty) - od konca albo od poczatku."""
	skin = """
	<screen position="center,center" size="1600,920" title="EPG Scan">
		<widget name="text" position="20,15" size="1560,820" font="Regular;24" />
		%s
	</screen>""" % keys_skin(20, 850, 1560)

	def __init__(self, session, title, path=None, text=None, tail=0):
		Screen.__init__(self, session)
		self.setTitle("EPG Scan - %s" % title)
		body = text
		if body is None:
			try:
				with open(path, encoding="utf-8", errors="ignore") as f:
					lines = f.read().splitlines()
				if tail:
					lines = lines[-tail:]
					lines.reverse()                          # najnowsze na gorze
				body = "\n".join(lines) or _("(pusty)")
			except Exception as e:
				body = _("Brak pliku %s (%s)") % (path, e)
		self["text"] = ScrollLabel(body)
		init_keys(self)
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions", "DirectionActions"], {
			"cancel": self.close, "ok": self.close, "red": self.close,
			"up": self["text"].pageUp, "down": self["text"].pageDown,
			"left": self["text"].pageUp, "right": self["text"].pageDown,
		}, -1)
		self.onLayoutFinish.append(self.show_keys)      # metoda, nie lambda: Enigma2 wykonuje exec() na innych obiektach

	def show_keys(self):
		set_keys(self, red="Zamknij")


def md_text(md):
	"""Markdown (README) do czytania na ekranie: bez znacznikow, tabele jako kolumny rozdzielone " | "."""
	import re
	out = []
	for ln in md.splitlines():
		s = ln.rstrip()
		if re.match(r"^\s*\|?\s*:?-{3,}", s) or s.strip() == "---" or s.strip().startswith("```"):
			continue                                   # separator tabeli, linia pozioma, ramka kodu
		if re.match(r"^\s*(!\[|<p><img|<img)", s):
			continue                                   # zrzuty ekranu (tylko na GitHubie)
		m = re.match(r"^(#{1,6})\s+(.*)$", s)
		if m:
			t = m.group(2).strip()
			out += ["", t.upper() if len(m.group(1)) <= 2 else t, "=" * min(len(t), 60) if len(m.group(1)) <= 2 else "-" * min(len(t), 60)]
			continue
		if s.startswith("|") and s.endswith("|"):
			s = "  " + " | ".join(c.strip() for c in s.strip("|").split("|"))
		s = re.sub(r"\*\*(.+?)\*\*", r"\1", s)
		s = re.sub(r"(?<![\w*])\*([^*]+)\*(?![\w*])", r"\1", s)
		s = s.replace("`", "")
		s = re.sub(r"\[([^\]]+)\]\(#[^)]*\)", r"\1", s)            # odnosnik w tym samym opisie
		s = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r"\1 (\2)", s)
		if not s.strip() and out and not out[-1].strip():
			continue                                   # najwyzej jedna pusta linia
		out.append(s)
	return "\n".join(out).strip()


def readme_text(base=None):
	"""Opis wtyczki (README.md z katalogu wtyczki) albo serwera epgpl (README.md z serwera, epgpl 0.19.4+)."""
	if base:
		import urllib.request
		req = urllib.request.Request(base + "README.md", headers={"User-Agent": "EPGScan"})
		return md_text(urllib.request.urlopen(req, timeout=15).read().decode("utf-8", "ignore"))
	filename = "README.en.md" if language() == "en" else "README.md"
	with open(os.path.join(os.path.dirname(__file__), filename), encoding="utf-8") as f:
		return md_text(f.read())


# --- menu ---
SETTINGS = (
	("satwatch", "SatWatch - transpondery: zmiany na satelicie, skan, tunery, bukiety"),
	("sat", "Satelita - EPG: skan transponderow, EIT, bukiety"),
	("params", "Skan parametrow: jezyki, bukiety, tunery, czestotliwosc"),
	("net", "Program z internetu - dekoder, serwer, zrodla XMLTV, kolejnosc"),
	("sched", "Harmonogram"),
	("misc", "Powiadomienia, restart, sprzatanie dysku"),
	("storage", "Kopie, log, serwer E2Stream"),
	("updates", "Aktualizacje i informacje o wtyczce"),
)


def _noop(*a):
	pass


def _index(opts, c):
	try:
		return opts.index(c)
	except Exception:
		return 0


def settings_menu(session, done=None, sel=0):
	opts = [(_(t), k) for k, t in SETTINGS]

	def chosen(c):
		if not c:
			if done:
				done()
			return
		idx = _index(opts, c)

		def back(*a):
			settings_menu(session, done, idx)
		if c[1] == "satwatch":
			try:
				from .satwatch.sw import SatWatchSetup
				session.openWithCallback(back, SatWatchSetup)
			except Exception as e:
				session.openWithCallback(back, MessageBox, "SatWatch: %s" % e, MessageBox.TYPE_ERROR)
			return
		from .setup import EPGScanSetup
		session.openWithCallback(back, EPGScanSetup, c[1])
	session.openWithCallback(chosen, ChoiceBox, title=_("EPG Scan %s - ustawienia") % VERSION, list=opts, selection=sel)


def reports_menu(session, engine, sel=0):
	"""Raporty; po zamknieciu raportu wraca ta lista (exit cofa o jedno okno)."""
	from .info import report_sources
	opts = [(_("SatWatch - ostatni raport"), "swrep"),
		(_("SatWatch - wczesniejsze raporty (historia)"), "swhist"),
		(_("SatWatch - log (najnowsze na gorze)"), "swlog"),
		(_("Skan parametrow kanalow - ostatni raport"), "params"),
		(_("Statystyka EPG"), "stats"),
		(_("Kanaly - zasieg EPG (od najkrotszego)"), "chan"),
		(_("Duble - nakladajace sie audycje"), "dups"),
		(_("Pobieranie na dekoderze - podsumowanie i bledy"), "fetch"),
		(_("Porownanie dekoder / serwer (ostatni test)"), "cmp"),
		(_("Test: pobierz na dekoderze i porownaj z serwerem"), "test"),
		(_("Log EPG Scan (najnowsze na gorze)"), "log")]
	for d, u in report_sources():
		opts.append((_("Raport serwera - %s") % d, ("srv", d, u)))         # kazdy serwer osobno
	opts.append((_("Konfiguracja tunerow - co ustawic (system, EPG Scan, SatWatch)"), "tuners"))
	opts.append((_("Opis wtyczki (README)"), "readme"))
	try:
		from .mapping import epgpl_servers
		for b, d in epgpl_servers():
			opts.append((_("Opis serwera epgpl (README) - %s") % d, ("srvreadme", b)))
	except Exception:
		pass

	def chosen(c):
		if not c:
			return
		k = c[1]
		d = backup_dir() + "lokalne/"
		idx = _index(opts, c)

		def back(*a):
			reports_menu(session, engine, idx)
		if k == "params":
			from .channelparamscan import format_report, load_results
			try:
				report = getattr(engine, "channelparams_report", None)
				if not report:
					report = load_results().get("last_scan", {})
					report = report.get("last_retry", report)
				body = format_report(report)
			except Exception as e:
				log("Skan parametrow: odczyt raportu nieudany: %s" % e)
				body = _("Nie mozna odczytac raportu skanu parametrow: %s") % e
			session.openWithCallback(back, EPGScanText, _("skan parametrow kanalow"), text=body)
		elif k == "stats":
			from .info import EPGScanInfo
			session.openWithCallback(back, EPGScanInfo, engine)
		elif k == "chan":
			from .info import EPGScanChannels
			session.openWithCallback(back, EPGScanChannels)
		elif k == "dups":
			from .info import EPGScanDups
			session.openWithCallback(back, EPGScanDups)
		elif k == "log":
			session.openWithCallback(back, EPGScanText, "log", log_path(), tail=400)
		elif k == "fetch":
			session.openWithCallback(back, EPGScanText, _("pobieranie na dekoderze"), text=fetch_summary(d))
			return
		elif k == "fetch_old":
			lines = []
			try:
				with open(log_path(), encoding="utf-8", errors="ignore") as f:
					lines = [x for x in f.read().splitlines() if " Dekoder:" in x or "Pobieranie na dekoderze" in x][-30:]
			except Exception:
				pass
			err = ""
			try:
				with open(d + "bledy.txt", encoding="utf-8", errors="ignore") as f:
					el = [x for x in f.read().splitlines() if not x.startswith("#")]
				err = _("\n\nBledy pobierania (%s, %d prob):\n%s") % (d + "bledy.txt", len(el), "\n".join(el[:60]))
			except Exception:
				pass
			session.openWithCallback(back, EPGScanText, _("pobieranie na dekoderze"), text="\n".join(reversed(lines)) + err)
		elif k == "cmp":
			session.openWithCallback(back, EPGScanText, _("porownanie dekoder / serwer"), d + "porownanie.txt")
		elif k in ("swrep", "swhist", "swlog"):
			satwatch_reports(session, k, back)
		elif k == "test":
			open_local_test(session, back)
		elif k == "tuners":
			try:
				from .satwatch.scanner import tuner_advice
				from .satwatch.sw import preferred_slot
				p = preferred_slot()
				body = tuner_advice(-1 if p is None else p)[1]
			except Exception as e:
				body = _("Nie udalo sie odczytac tunerow: %s") % e
			session.openWithCallback(back, EPGScanText, _("konfiguracja tunerow"), text=body)
		elif k == "readme":
			try:
				body = readme_text()
			except Exception as e:
				body = _("Brak opisu: %s") % e
			session.openWithCallback(back, EPGScanText, _("opis wtyczki %s") % VERSION, text=body)
		elif isinstance(k, tuple) and k[0] == "srvreadme":
			try:
				body = readme_text(k[1])
			except Exception as e:
				body = _("Nie udalo sie pobrac opisu z serwera %s (%s).\nOpis jest na serwerze w /opt/epgpl/README.md (epgpl 0.19.4+ udostepnia go dekoderowi).") % (k[1], e)
			session.openWithCallback(back, EPGScanText, _("opis serwera epgpl"), text=body)
		elif isinstance(k, tuple) and k[0] == "srv":
			from .setup import EPGScanReport
			session.openWithCallback(back, EPGScanReport, k[1], k[2])
	session.openWithCallback(chosen, ChoiceBox, title=_("Raporty"), list=opts, selection=sel)


def satwatch_reports(session, k, back=None):
	"""Raporty SatWatch: ostatni, historia (ostatnie 30), log."""
	try:
		from .satwatch import core as swcore
		from .satwatch import scanner as swscan
	except Exception as e:
		session.openWithCallback(back or _noop, MessageBox, _("SatWatch niedostepny: %s") % e, MessageBox.TYPE_ERROR)
		return
	if k == "swrep":
		session.openWithCallback(back or _noop, EPGScanText, _("SatWatch - ostatni raport"), text=swcore.read_last_report() or _("Brak raportu."))
	elif k == "swlog":
		session.openWithCallback(back or _noop, EPGScanText, "SatWatch - log", swscan.log_path(), tail=400)
	else:
		rdir = os.path.join(swcore.out_dir(), "reports")
		try:
			files = sorted((f for f in os.listdir(rdir) if f.startswith("report-")), reverse=True)
		except Exception:
			files = []
		if not files:
			session.openWithCallback(back or _noop, MessageBox, _("Brak zapisanych raportow SatWatch (%s).") % rdir, MessageBox.TYPE_INFO, timeout=6)
			return

		def label(f):
			try:
				t = time.strptime(f[7:22], "%Y%m%d-%H%M%S")
				first = ""
				with open(os.path.join(rdir, f), encoding="utf-8", errors="ignore") as fh:
					for ln in fh:
						if ln.startswith("SatWatch:") or "zmian" in ln or "Skan" in ln or "Naprawa" in ln or "Decyzja" in ln:
							first = ln.strip()
							break
				return "%s  %s" % (time.strftime("%d.%m %H:%M", t), first[:80])
			except Exception:
				return f
		lst = [(label(f), f) for f in files]

		def pick(c, sel=0):
			if not c:
				return (back or _noop)()
			session.openWithCallback(lambda *a: hist(_index(lst, c)), EPGScanText, _("SatWatch - raport %s") % c[1][7:22],
				os.path.join(rdir, c[1]))

		def hist(sel=0):
			session.openWithCallback(pick, ChoiceBox, title=_("SatWatch - raporty (najnowsze na gorze)"), list=lst, selection=sel, keys=[])
		hist()


def fetch_summary(d):
	"""Podsumowanie pobierania na dekoderze: ostatni wynik, plik, stacje wg serwisu, bledy, wpisy z logu."""
	out = []
	v = cfg.last_fetch.value
	try:
		ts, txt = v.split("|", 1)
		out.append(_("Ostatnie pobranie: %s\n  %s") % (time.strftime("%d.%m.%Y %H:%M", time.localtime(int(ts))), txt))
	except Exception:
		out.append(_("Ostatnie pobranie: %s") % (v or _("jeszcze nie bylo")))
	f = d + "pl.xml.xz"
	if os.path.exists(f):
		age = (time.time() - os.path.getmtime(f)) / 3600.0
		out.append(_("Plik programu: %s  (%d kB, z %s, %.1f h temu%s)") % (f, os.path.getsize(f) // 1024,
			time.strftime("%d.%m %H:%M", time.localtime(os.path.getmtime(f))), age,
			_(" - STARSZY NIZ 30 h, uzywany bedzie serwer") if age > 30 else ""))
	else:
		out.append(_("Plik programu: brak (%s)") % f)
	try:
		import json
		mapa = json.load(open(d + "mapa.json"))
		cnt = {}
		for c in mapa.get("channels", []):
			if c.get("tag"):
				cnt[c["tag"]] = cnt.get(c["tag"], 0) + 1
		names = {"tm": "teleman", "onet": "onet", "naz": "naziemna", "wp": "WP Pilot"}
		out.append(_("Kanaly wg serwisu (mapa z %s): %s") % (time.strftime("%d.%m %H:%M", time.localtime(os.path.getmtime(d + "mapa.json"))),
			", ".join("%s %d" % (names.get(k, k), n) for k, n in sorted(cnt.items(), key=lambda x: -x[1]))))
	except Exception:
		out.append(_("Mapa kanalow: brak kopii (%smapa.json)") % d)
	try:
		with open(d + "bledy.txt", encoding="utf-8", errors="ignore") as fh:
			el = [x for x in fh.read().splitlines() if not x.startswith("#")]
		codes = {}
		for x in el:
			p = [y.strip() for y in x.split("|")]
			k = p[2] if len(p) > 2 else "?"
			codes[k] = codes.get(k, 0) + 1
		out.append(_("Proby z bledem: %d  (%s)  - 404 = dzien poza zasiegiem serwisu, '-' = polaczenie odrzucone/timeout") % (
			len(el), ", ".join("%s: %d" % (k if k != "-" else _("bez kodu"), n) for k, n in sorted(codes.items()))))
	except Exception:
		pass
	try:
		with open(log_path(), encoding="utf-8", errors="ignore") as fh:
			lines = [x for x in fh.read().splitlines() if " Dekoder:" in x or "Pobieranie na dekoderze" in x][-20:]
		if lines:
			out += ["", _("Wpisy w logu (najnowsze na gorze):")] + list(reversed(lines))
	except Exception:
		pass
	try:
		with open(d + "bledy.txt", encoding="utf-8", errors="ignore") as fh:
			el = [x for x in fh.read().splitlines() if not x.startswith("#")]
		if el:
			out += ["", _("Bledy (pierwsze 40):")] + el[:40]
	except Exception:
		pass
	return "\n".join(out)


def open_local_test(session, back=None):
	from .mapping import epgpl_servers, EPGScanLocalTest
	srv = epgpl_servers()
	if not srv:
		session.openWithCallback(back or _noop, MessageBox, _("Test porownuje z serwerem epgpl - nie ma zrodla XMLTV z serwera."), MessageBox.TYPE_INFO)
		return
	mapa = None
	try:
		import json
		from .mapping import http_get_json
		mapa = http_get_json(srv[0][0] + "mapa.json")
	except Exception:
		try:
			import json
			mapa = json.load(open(backup_dir() + "lokalne/mapa.json"))
		except Exception:
			pass
	if not mapa:
		session.openWithCallback(back or _noop, MessageBox, _("Brak mapy kanalow (serwer nie odpowiada, brak kopii)."), MessageBox.TYPE_ERROR)
		return
	session.openWithCallback(back or _noop, EPGScanLocalTest, srv[0][0], mapa)


SW_ITEMS = (
	("check", "Sprawdz teraz (satellites.xml + skan zmienionych TP)"),
	("full", "Pelny skan teraz"),
	("decide", "Kanaly do decyzji - wybor kandydata"),
	("fix", "Napraw bukiety teraz (podmiany wg regul)"),
	("compare", "Podglad bukietow - porownanie ze zrzutem"),
	("snap", "Zapisz zrzut referencyjny (lamedb + bukiety)"),
	("accept", "Akceptuj obecny stan (wyczysc liste DO DECYZJI)"),
	("restore_bq", "Przywroc kopie bukietow"),
	("restore", "Przywroc kopie satellites.xml"),
	("reload", "Przeladuj transpondery (bez restartu)"),
	("tuners", "Konfiguracja tunerow - co ustawic (system, EPG Scan, SatWatch)"),
)
SW_STARTED = "\n\nPostep: na gorze listy SatWatch i na ekranie glownym EPG Scan. Wynik: Info/EPG -> Raporty -> SatWatch."


def satwatch_menu(session, sel=0, back=None):
	"""Menu -> Kanaly -> SatWatch: wszystkie akcje SatWatch na jednej liscie. Dlugie akcje w tle (postep na gorze
	listy, odswiezany na zywo), krotkie - wynik w komunikacie; okno raportu tylko przy podgladzie bukietow."""
	try:
		from .satwatch import sw
	except Exception as e:
		session.openWithCallback(back or _noop, MessageBox, _("SatWatch niedostepny: %s") % e, MessageBox.TYPE_ERROR)
		return
	runner = sw.runner
	opts = []
	if runner.scanner is not None or runner.busy:
		opts.append((_("Przerwij skan (%s)") % sw.progress_short(), "abort"))
	opts += [(_(t), k) for k, t in SW_ITEMS]
	state = {"dlg": None}

	def msg(text, kind=MessageBox.TYPE_INFO, after=None):
		session.openWithCallback(after or again_here, MessageBox, text, kind, timeout=8)

	def again_here(*a):
		satwatch_menu(session, state.get("idx", 0), back)

	def chosen(c):
		_unhook()
		if not c:
			return (back or _noop)()
		k = c[1]
		state["idx"] = idx = _index(opts, c)

		def again(*a):
			satwatch_menu(session, idx, back)
		if k == "abort":
			if runner.scanner is not None:
				runner.abortScan()
				msg(_("Skan przerwany."), after=again)
			else:
				again()
		elif k == "done":
			again()
		elif k == "check":
			if runner.check():
				msg(_("Sprawdzanie uruchomione (satellites.xml, potem skan zmienionych i wczesniej nieudanych TP).") + _(SW_STARTED),
					after=again)
			else:
				msg(_("SatWatch pracuje (%s) - sprobuj pozniej.") % runner.status, after=again)
		elif k == "full":
			def go(yes):
				if not yes:
					return again()
				if runner.fullScan():
					msg(_("Pelny skan uruchomiony (jeden wolny tuner, kilka minut).") + _(SW_STARTED), after=again)
				else:
					msg(_("SatWatch pracuje (%s) - sprobuj pozniej.") % runner.status, after=again)
			session.openWithCallback(go, MessageBox, _("Uruchomic pelny skan monitorowanych pozycji?\n"
				"Potrwa kilka minut i zajmie jeden wolny tuner."), MessageBox.TYPE_YESNO)
		elif k == "fix":
			if runner.fixBouquets():
				msg(_("Naprawa bukietow uruchomiona.") + _(SW_STARTED), after=again)
			else:
				msg(_("SatWatch pracuje (%s) - sprobuj pozniej.") % runner.status, after=again)
		elif k == "decide":
			sw.open_decisions(session, again)
		elif k == "compare":
			session.openWithCallback(again, sw.SatWatchMain, k)
		elif k == "snap":
			def snap(yes):
				if not yes:
					return again()

				def done(res):
					text = res.get("report", "") if isinstance(res, dict) else str(res)
					msg(text.splitlines()[0] if text else _("Zrzut zapisany."), after=again)
				if not runner.run(sw.core.snapshot_save_only, done):
					msg(_("SatWatch pracuje (%s) - sprobuj pozniej.") % runner.status, after=again)
			session.openWithCallback(snap, MessageBox, _("Zapisac aktualny stan lamedb i bukietow jako zrzut referencyjny?\n"
				"Zrob to przed recznym skanem albo po recznych zmianach w bukietach."), MessageBox.TYPE_YESNO)
		elif k == "accept":
			def acc(yes):
				if not yes:
					return again()
				msg(_("Wyczyszczono %d pozycji DO DECYZJI.") % sw.core.clear_unresolved(), after=again)
			session.openWithCallback(acc, MessageBox, _("Uznac obecne kanaly w bukietach za poprawne?\n"
				"Pozycje DO DECYZJI przestana wracac w raportach."), MessageBox.TYPE_YESNO)
		elif k == "reload":
			ok = sw.reload_transponders()
			msg(_("Transpondery przeladowane.") if ok else _("Przeladowanie nie powiodlo sie - zrestartuj GUI."),
				MessageBox.TYPE_INFO if ok else MessageBox.TYPE_ERROR, after=again)
		elif k in ("restore", "restore_bq"):
			files = sw.core.list_backups() if k == "restore" else sw.core.list_bouquet_backups()
			if not files:
				return msg(_("Brak kopii %s.") % ("satellites.xml" if k == "restore" else _("bukietow")), after=again)

			def pick(c2):
				if not c2:
					return again()
				try:
					if k == "restore":
						sw.core.restore_backup(c2[1])
						ok = sw.reload_transponders()
						text = _("Przywrocono satellites.xml z kopii %s.\n%s") % (c2[1], _("Transpondery przeladowane.") if ok else
							_("Przeladowanie transponderow nie powiodlo sie - zrestartuj GUI."))
					else:
						n = sw.core.restore_bouquet_backup(c2[1])
						sw.reload_lists()
						text = _("Przywrocono %d plikow bukietow z kopii %s. Listy kanalow przeladowane.") % (n, c2[1])
					sw.scanner.log(text.replace("\n", " "))
					msg(text, after=again)
				except Exception as e:
					msg(_("Blad przywracania: %s") % e, MessageBox.TYPE_ERROR, after=again)
			session.openWithCallback(pick, ChoiceBox, title=_("Kopia %s (najnowsze na gorze)") % (
				"satellites.xml" if k == "restore" else _("bukietow (sprzed podmiany)")), list=[(x, x) for x in files], keys=[])
		elif k == "tuners":
			from .satwatch.scanner import tuner_advice
			p = sw.preferred_slot()
			session.openWithCallback(again, EPGScanText, _("konfiguracja tunerow"), text=tuner_advice(-1 if p is None else p)[1])
		else:
			again()

	def title_text():
		p = sw.progress_short()
		return _("SatWatch %s%s   (raporty i log: Info/EPG -> Raporty)") % (sw.core.VERSION, (" - " + p) if p else "")

	def live(status=None, report=None):
		dlg = state["dlg"]
		if dlg is None:
			return
		try:
			dlg["text"].setText(title_text())
		except Exception:
			pass
		if not opts or opts[0][1] not in ("abort", "done"):
			return
		if runner.scanner is not None or runner.busy:
			row = (_("Przerwij skan (%s)") % sw.progress_short(), "abort")
		else:
			row = (_("Skan zakonczony: %s") % (status or runner.status), "done")
		try:
			from Components.ChoiceList import ChoiceEntryComponent
			lst = dlg["list"]
			i = lst.getSelectedIndex()
			lst.list[0] = ChoiceEntryComponent(key="dummy", text=row)
			lst.l.setList(lst.list)
			lst.moveToIndex(i)
			opts[0] = row
		except Exception:
			pass

	def _unhook():
		if live in runner.listeners:
			runner.listeners.remove(live)

	dlg = session.openWithCallback(chosen, ChoiceBox, title=title_text(), list=opts, selection=sel, keys=[])
	state["dlg"] = dlg
	runner.listeners.append(live)
	try:
		dlg.onClose.append(lambda: (_unhook(), state.update(dlg=None)))
	except Exception:
		pass


def channels_menu(session, sel=0):
	"""Kanaly; po zamknieciu wybranego okna wraca ta lista."""
	from .mapping import epgpl_servers
	srv = epgpl_servers()
	opts = [(_("SatWatch - sprawdzenie, skan, kanaly do decyzji, zrzut, kopie"), "sw")]
	if satwatch_installed():
		opts.append((_("UWAGA: osobna wtyczka SatWatch jest zainstalowana - odinstaluj ja"), "swno"))
	opts.append((_("Skan parametrow kanalow (PAT/PMT), wybor audio i E2Stream"), "params"))
	opts.append((_("Dopasowanie kanalow: dekoder%s") % ("" if cfg.map_mode.value == "dekoder" else
		_("  (mapa dekodera nieuzywana - Ustawienia -> Program z internetu)")), "dek"))
	for base, desc in srv:
		opts.append((_("Dopasowanie kanalow: serwer - %s%s") % (desc, _("  (uzywana przez dekoder)") if cfg.map_mode.value == "serwer" else ""),
			("srv", base, desc)))
	if srv:
		opts.append((_("Przenies wskazania z serwera do dekodera"), "pins"))
	opts.append((_("Raport mapy kanalow dekodera"), "rep"))

	def chosen(c):
		if not c:
			return
		k = c[1]
		idx = _index(opts, c)

		def back(*a):
			channels_menu(session, idx)
		if isinstance(k, tuple):
			from .mapping import EPGScanMapping
			session.openWithCallback(back, EPGScanMapping, k[1], k[2])
		elif k == "dek":
			from .mapping import EPGScanMapping
			session.openWithCallback(back, EPGScanMapping, None, "dekoder")
		elif k == "pins":
			session.openWithCallback(lambda yes: import_pins(session, srv[0][0], back) if yes else back(), MessageBox,
				_("Przeniesc wskazania (reczne z tunera i aliasy) z mapy serwera %s do mapy dekodera?\n"
				"Istniejace wskazania dekodera dla tych samych kanalow zostana nadpisane.") % srv[0][1], MessageBox.TYPE_YESNO)
		elif k == "rep":
			session.openWithCallback(back, EPGScanText, _("mapa kanalow dekodera"), backup_dir() + "lokalne/raport.txt")
		elif k == "sw":
			satwatch_menu(session, back=back)
		elif k == "params":
			from .channelparamsui import ChannelParamsScreen
			session.openWithCallback(back, ChannelParamsScreen)
		else:
			session.openWithCallback(back, MessageBox, _("Osobna wtyczka SatWatch dalej dziala z wlasnym harmonogramem. Odinstaluj ja:\n"
				"rm -rf %s; init 4; sleep 2; init 3\nUstawienia, raporty i kopie bukietow zostaja.") % SATWATCH_DIR,
				MessageBox.TYPE_WARNING)
	session.openWithCallback(chosen, ChoiceBox, title=_("Kanaly"), list=opts, selection=sel)


SATWATCH_DIR = "/usr/lib/enigma2/python/Plugins/Extensions/SatWatch"
SATWATCH_REPORT = "/etc/enigma2/satwatch/last_report.txt"


def satwatch_installed():
	return os.path.exists(os.path.join(SATWATCH_DIR, "plugin.py")) or os.path.exists(os.path.join(SATWATCH_DIR, "plugin.pyc"))


def import_pins(session, base, back=None):
	try:
		from .mapping import http_get_json, local_map_job
		from .localmap import pins_from_server_map, load_pins, save_pins
		got = pins_from_server_map(http_get_json(base + "mapa.json"))
		pins = load_pins()
		pins.update(got)
		save_pins(pins)
		local_map_job(rebuild=True)
		log("Wskazania z serwera przeniesione do dekodera: %d" % len(got))
		session.openWithCallback(back or _noop, MessageBox, _("Przeniesiono %d wskazan. Mapa dekodera przeliczona.") % len(got), MessageBox.TYPE_INFO, timeout=6)
	except Exception as e:
		session.openWithCallback(back or _noop, MessageBox, _("Nie udalo sie: %s") % e, MessageBox.TYPE_ERROR)


def backups_menu(session, engine, restore_cb):
	"""Zolty na ekranie glownym: odtworzenie kopii albo kopia teraz."""
	def chosen(c):
		if not c:
			return
		if c[1] == "restore":
			restore_cb()
		else:
			from enigma import eEPGCache, eTimer
			from .common import copy_backup
			try:
				eEPGCache.getInstance().save()
			except Exception:
				pass
			t = eTimer()

			def done():
				dst = copy_backup()
				session.open(MessageBox, (_("Kopia EPG zapisana:\n%s") % dst) if dst else _("Kopia nieudana - szczegoly w logu"),
					MessageBox.TYPE_INFO if dst else MessageBox.TYPE_ERROR, timeout=6)
			t.callback.append(done)
			t.start(5000, True)
			backups_menu.timer = t
	session.openWithCallback(chosen, ChoiceBox, title=_("Kopie EPG"),
		list=[(_("Odtworz EPG z kopii"), "restore"), (_("Zrob kopie EPG teraz (zapis + kopia)"), "backup")])


from .common import scale_skins as _scale_skins  # noqa: E402
_scale_skins(globals())
