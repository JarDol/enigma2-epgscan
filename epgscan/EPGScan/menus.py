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
from Components.config import getConfigListEntry, ConfigSelection, ConfigYesNo, ConfigNothing, NoSave

from .common import cfg, log, save_cfg, VERSION, keys_skin, init_keys, set_keys, CLEAR_CHOICES, profile_default, \
	profile_text, log_path, backup_dir, SRV_CHOICES, SW_CHOICES


class EPGScanActions(Screen, ConfigListScreen):
	"""Zielony na ekranie glownym: co zrobic teraz. Wstepnie profil domyslny; zolty zapisuje go jako domyslny
	(OK na ekranie glownym i harmonogram)."""
	skin = """
	<screen position="center,center" size="1500,820" title="EPG Scan - akcje">
		<widget name="config" position="20,15" size="1460,375" font="Regular;30" secondfont="Regular;30" itemHeight="46" scrollbarMode="showOnDemand" />
		<widget name="help" position="20,400" size="1460,330" font="Regular;28" />
		%s
	</screen>""" % keys_skin(20, 745, 1460)

	def __init__(self, session):
		Screen.__init__(self, session)
		self.setTitle("EPG Scan %s - akcje" % VERSION)
		p = profile_default()
		choices = list(CLEAR_CHOICES)
		try:
			choices = [c for c in CLEAR_CHOICES if c[0] in [x[0] for x in cfg.clear_mode.choices.choices]]
		except Exception:
			pass
		self.c_sw = NoSave(ConfigSelection(default=p.get("sw", "nie"), choices=SW_CHOICES))
		self.c_clear = NoSave(ConfigSelection(default=p["clear"], choices=choices))
		self.c_sat = NoSave(ConfigYesNo(default=p["sat"]))
		self.c_dek = NoSave(ConfigYesNo(default=p["dek"]))
		self.c_srv = NoSave(ConfigSelection(default=p["srv"], choices=SRV_CHOICES))
		self.c_other = NoSave(ConfigYesNo(default=p["other"]))
		self.c_fetch = NoSave(ConfigSelection(default="nie" if not p["fetch"] else ("jedno" if p.get("one") else "nowe"), choices=[
			("nie", "nie"), ("jedno", "tak - w jednym miejscu (gotowa dzisiejsza paczka drugiej strony)"),
			("nowe", "tak - pobierz na nowo")]))
		self.c_channelparams = NoSave(ConfigNothing())
		self["help"] = Label("")
		init_keys(self)
		ConfigListScreen.__init__(self, [
			getConfigListEntry("SatWatch: zmiany na satelicie i bukiety", self.c_sw),
			getConfigListEntry("Czysc EPG przed startem", self.c_clear),
			getConfigListEntry("Satelita (skan transponderow)", self.c_sat),
			getConfigListEntry("Dekoder (program pobierany przez dekoder)", self.c_dek),
			getConfigListEntry("Serwer epgpl", self.c_srv),
			getConfigListEntry("Pozostale zrodla XMLTV", self.c_other),
			getConfigListEntry("Pobierz swieze dane", self.c_fetch),
			getConfigListEntry("Skan parametrow kanalow (PAT/PMT) - osobna akcja", self.c_channelparams),
		], session=session, on_change=self.update_help)
		self["actions"] = ActionMap(["SetupActions", "ColorActions"], {
			"ok": self.go, "green": self.go, "save": self.go,
			"yellow": self.save_default,
			"cancel": self.close, "red": self.close,
		}, -2)
		try:
			self["config"].onSelectionChanged.append(self.update_help)
		except Exception:
			pass
		self.onLayoutFinish.append(self.update_help)

	def profile(self):
		return {"sw": self.c_sw.value, "clear": self.c_clear.value, "sat": bool(self.c_sat.value), "dek": bool(self.c_dek.value),
			"srv": self.c_srv.value, "other": bool(self.c_other.value), "fetch": self.c_fetch.value != "nie",
			"one": self.c_fetch.value == "jedno"}

	def update_help(self):
		p = self.profile()
		current = self["config"].getCurrent()
		channelparams_selected = bool(current and current[1] is self.c_channelparams)
		set_keys(self, red="Anuluj", green="Uruchom skan parametrow" if channelparams_selected else "Start EPG",
			yellow="" if channelparams_selected else "Zapisz jako domyslne")
		if channelparams_selected:
			self["help"].setText("Niezalezny skan PAT/PMT kanalow z wybranych bukietow. Nacisnij OK lub zielony, "
				"aby uruchomic skan w tle i wrocic do ekranu postepu EPG Scan.")
			return
		lines = ["Kolejnosc: %s  (zmiana: Ustawienia -> Program z internetu)" % profile_text(p)]
		if p["sw"] != "nie":
			lines.append("SatWatch najpierw (wolne tunery, najwyzej 15 min czekania); po zmianach w kanalach lista "
				"transponderow i mapa kanalow budowane od nowa.")
		if not cfg.xmltv.value:
			lines.append("UWAGA: program z internetu jest wylaczony w ustawieniach - bedzie tylko satelita.")
		if p["fetch"]:
			f = []
			if p["dek"] and p.get("one"):
				f.append("dekoder bierze dzisiejsza pelna paczke serwera, gdy jest - inaczej pobiera sam i wysyla paczke na serwer")
			elif p["dek"]:
				f.append("dekoder pobiera teraz (ok. 5 min)")
			if p["srv"] == "zawsze" and p.get("one") and p["dek"]:
				f.append("serwer nie jest proszony o pobranie (jedno miejsce)")
			elif p["srv"] == "zawsze":
				f.append("serwer pobiera teraz (ok. 3 min)")
			elif p["srv"] == "zapas":
				f.append("serwer tylko, gdy jego plik ma ponad 30 h")
			lines.append("Pobranie: " + (", ".join(f) if f else "nic do pobrania") + "; import czeka na koniec.")
		else:
			lines.append("Pobranie: nie - import z gotowych plikow; gdy pliku nie ma albo ma ponad 30 h, dekoder / serwer pobiera i tak.")
		if not p["sat"]:
			lines.append("Bez satelity: sam import XMLTV. %s" % ("EIT jest wylaczane (Ustawienia -> Satelita)."
				if cfg.eit_force_off.value else ""))
		lines.append("Satelita zawsze ma pierwszenstwo: XMLTV uzupelnia tylko to, czego nie dal satelita "
			"(EIT ma w Enigma2 wyzszy priorytet niz import).")
		lines.append("Profil domyslny (OK na ekranie glownym i harmonogram): %s" % profile_text(profile_default()))
		self["help"].setText("\n".join(lines))

	def go(self):
		current = self["config"].getCurrent()
		if current and current[1] is self.c_channelparams:
			self.close("channelparams")
			return
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
		if p["fetch"]:
			cfg.p_one.value = p["one"]
		for x in (cfg.p_sw, cfg.clear_mode, cfg.sat_import, cfg.p_dek, cfg.p_srv, cfg.p_other, cfg.p_fetch, cfg.p_one):
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
		self.session.open(MessageBox, "Zapisano jako domyslne (OK i harmonogram):\n%s" % profile_text(p),
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
				body = "\n".join(lines) or "(pusty)"
			except Exception as e:
				body = "Brak pliku %s (%s)" % (path, e)
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
	with open(os.path.join(os.path.dirname(__file__), "README.md"), encoding="utf-8") as f:
		return md_text(f.read())


# --- menu ---
SETTINGS = (
	("satwatch", "SatWatch - transpondery: zmiany na satelicie, skan, tunery, bukiety"),
	("sat", "Satelita - EPG: skan transponderow, EIT, bukiety"),
	("params", "Skan parametrow: jezyki, bukiety, tunery, czestotliwosc"),
	("net", "Program z internetu - dekoder, serwer, zrodla XMLTV, kolejnosc"),
	("sched", "Harmonogram"),
	("misc", "Powiadomienia, restart, sprzatanie dysku"),
	("updates", "Aktualizacje, kopie, log, serwer E2Stream"),
)


def _noop(*a):
	pass


def _index(opts, c):
	try:
		return opts.index(c)
	except Exception:
		return 0


def settings_menu(session, done=None, sel=0):
	opts = [(t, k) for k, t in SETTINGS]

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
	session.openWithCallback(chosen, ChoiceBox, title="EPG Scan %s - ustawienia" % VERSION, list=opts, selection=sel)


def reports_menu(session, engine, sel=0):
	"""Raporty; po zamknieciu raportu wraca ta lista (exit cofa o jedno okno)."""
	from .info import report_sources
	opts = [("SatWatch - ostatni raport", "swrep"),
		("SatWatch - wczesniejsze raporty (historia)", "swhist"),
		("SatWatch - log (najnowsze na gorze)", "swlog"),
		("Statystyka EPG", "stats"),
		("Kanaly - zasieg EPG (od najkrotszego)", "chan"),
		("Duble - nakladajace sie audycje", "dups"),
		("Pobieranie na dekoderze - podsumowanie i bledy", "fetch"),
		("Porownanie dekoder / serwer (ostatni test)", "cmp"),
		("Test: pobierz na dekoderze i porownaj z serwerem", "test"),
		("Log EPG Scan (najnowsze na gorze)", "log")]
	for d, u in report_sources():
		opts.append(("Raport serwera - %s" % d, ("srv", d, u)))         # kazdy serwer osobno
	opts.append(("Konfiguracja tunerow - co ustawic (system, EPG Scan, SatWatch)", "tuners"))
	opts.append(("Opis wtyczki (README)", "readme"))
	try:
		from .mapping import epgpl_servers
		for b, d in epgpl_servers():
			opts.append(("Opis serwera epgpl (README) - %s" % d, ("srvreadme", b)))
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
		if k == "stats":
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
			session.openWithCallback(back, EPGScanText, "pobieranie na dekoderze", text=fetch_summary(d))
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
				err = "\n\nBledy pobierania (%s, %d prob):\n%s" % (d + "bledy.txt", len(el), "\n".join(el[:60]))
			except Exception:
				pass
			session.openWithCallback(back, EPGScanText, "pobieranie na dekoderze", text="\n".join(reversed(lines)) + err)
		elif k == "cmp":
			session.openWithCallback(back, EPGScanText, "porownanie dekoder / serwer", d + "porownanie.txt")
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
				body = "Nie udalo sie odczytac tunerow: %s" % e
			session.openWithCallback(back, EPGScanText, "konfiguracja tunerow", text=body)
		elif k == "readme":
			try:
				body = readme_text()
			except Exception as e:
				body = "Brak opisu: %s" % e
			session.openWithCallback(back, EPGScanText, "opis wtyczki %s" % VERSION, text=body)
		elif isinstance(k, tuple) and k[0] == "srvreadme":
			try:
				body = readme_text(k[1])
			except Exception as e:
				body = "Nie udalo sie pobrac opisu z serwera %s (%s).\nOpis jest na serwerze w /opt/epgpl/README.md (epgpl 0.19.4+ udostepnia go dekoderowi)." % (k[1], e)
			session.openWithCallback(back, EPGScanText, "opis serwera epgpl", text=body)
		elif isinstance(k, tuple) and k[0] == "srv":
			from .setup import EPGScanReport
			session.openWithCallback(back, EPGScanReport, k[1], k[2])
	session.openWithCallback(chosen, ChoiceBox, title="Raporty", list=opts, selection=sel)


def satwatch_reports(session, k, back=None):
	"""Raporty SatWatch: ostatni, historia (ostatnie 30), log."""
	try:
		from .satwatch import core as swcore
		from .satwatch import scanner as swscan
	except Exception as e:
		session.openWithCallback(back or _noop, MessageBox, "SatWatch niedostepny: %s" % e, MessageBox.TYPE_ERROR)
		return
	if k == "swrep":
		session.openWithCallback(back or _noop, EPGScanText, "SatWatch - ostatni raport", text=swcore.read_last_report() or "Brak raportu.")
	elif k == "swlog":
		session.openWithCallback(back or _noop, EPGScanText, "SatWatch - log", swscan.log_path(), tail=400)
	else:
		rdir = os.path.join(swcore.out_dir(), "reports")
		try:
			files = sorted((f for f in os.listdir(rdir) if f.startswith("report-")), reverse=True)
		except Exception:
			files = []
		if not files:
			session.openWithCallback(back or _noop, MessageBox, "Brak zapisanych raportow SatWatch (%s)." % rdir, MessageBox.TYPE_INFO, timeout=6)
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
			session.openWithCallback(lambda *a: hist(_index(lst, c)), EPGScanText, "SatWatch - raport %s" % c[1][7:22],
				os.path.join(rdir, c[1]))

		def hist(sel=0):
			session.openWithCallback(pick, ChoiceBox, title="SatWatch - raporty (najnowsze na gorze)", list=lst, selection=sel, keys=[])
		hist()


def fetch_summary(d):
	"""Podsumowanie pobierania na dekoderze: ostatni wynik, plik, stacje wg serwisu, bledy, wpisy z logu."""
	out = []
	v = cfg.last_fetch.value
	try:
		ts, txt = v.split("|", 1)
		out.append("Ostatnie pobranie: %s\n  %s" % (time.strftime("%d.%m.%Y %H:%M", time.localtime(int(ts))), txt))
	except Exception:
		out.append("Ostatnie pobranie: %s" % (v or "jeszcze nie bylo"))
	f = d + "pl.xml.xz"
	if os.path.exists(f):
		age = (time.time() - os.path.getmtime(f)) / 3600.0
		out.append("Plik programu: %s  (%d kB, z %s, %.1f h temu%s)" % (f, os.path.getsize(f) // 1024,
			time.strftime("%d.%m %H:%M", time.localtime(os.path.getmtime(f))), age,
			" - STARSZY NIZ 30 h, uzywany bedzie serwer" if age > 30 else ""))
	else:
		out.append("Plik programu: brak (%s)" % f)
	try:
		import json
		mapa = json.load(open(d + "mapa.json"))
		cnt = {}
		for c in mapa.get("channels", []):
			if c.get("tag"):
				cnt[c["tag"]] = cnt.get(c["tag"], 0) + 1
		names = {"tm": "teleman", "onet": "onet", "naz": "naziemna", "wp": "WP Pilot"}
		out.append("Kanaly wg serwisu (mapa z %s): %s" % (time.strftime("%d.%m %H:%M", time.localtime(os.path.getmtime(d + "mapa.json"))),
			", ".join("%s %d" % (names.get(k, k), n) for k, n in sorted(cnt.items(), key=lambda x: -x[1]))))
	except Exception:
		out.append("Mapa kanalow: brak kopii (%smapa.json)" % d)
	try:
		with open(d + "bledy.txt", encoding="utf-8", errors="ignore") as fh:
			el = [x for x in fh.read().splitlines() if not x.startswith("#")]
		codes = {}
		for x in el:
			p = [y.strip() for y in x.split("|")]
			k = p[2] if len(p) > 2 else "?"
			codes[k] = codes.get(k, 0) + 1
		out.append("Proby z bledem: %d  (%s)  - 404 = dzien poza zasiegiem serwisu, '-' = polaczenie odrzucone/timeout" % (
			len(el), ", ".join("%s: %d" % (k if k != "-" else "bez kodu", n) for k, n in sorted(codes.items()))))
	except Exception:
		pass
	try:
		with open(log_path(), encoding="utf-8", errors="ignore") as fh:
			lines = [x for x in fh.read().splitlines() if " Dekoder:" in x or "Pobieranie na dekoderze" in x][-20:]
		if lines:
			out += ["", "Wpisy w logu (najnowsze na gorze):"] + list(reversed(lines))
	except Exception:
		pass
	try:
		with open(d + "bledy.txt", encoding="utf-8", errors="ignore") as fh:
			el = [x for x in fh.read().splitlines() if not x.startswith("#")]
		if el:
			out += ["", "Bledy (pierwsze 40):"] + el[:40]
	except Exception:
		pass
	return "\n".join(out)


def open_local_test(session, back=None):
	from .mapping import epgpl_servers, EPGScanLocalTest
	srv = epgpl_servers()
	if not srv:
		session.openWithCallback(back or _noop, MessageBox, "Test porownuje z serwerem epgpl - nie ma zrodla XMLTV z serwera.", MessageBox.TYPE_INFO)
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
		session.openWithCallback(back or _noop, MessageBox, "Brak mapy kanalow (serwer nie odpowiada, brak kopii).", MessageBox.TYPE_ERROR)
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
		session.openWithCallback(back or _noop, MessageBox, "SatWatch niedostepny: %s" % e, MessageBox.TYPE_ERROR)
		return
	runner = sw.runner
	opts = []
	if runner.scanner is not None or runner.busy:
		opts.append(("Przerwij skan (%s)" % sw.progress_short(), "abort"))
	opts += [(t, k) for k, t in SW_ITEMS]
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
				msg("Skan przerwany.", after=again)
			else:
				again()
		elif k == "done":
			again()
		elif k == "check":
			if runner.check():
				msg("Sprawdzanie uruchomione (satellites.xml, potem skan zmienionych i wczesniej nieudanych TP)." + SW_STARTED,
					after=again)
			else:
				msg("SatWatch pracuje (%s) - sprobuj pozniej." % runner.status, after=again)
		elif k == "full":
			def go(yes):
				if not yes:
					return again()
				if runner.fullScan():
					msg("Pelny skan uruchomiony (jeden wolny tuner, kilka minut)." + SW_STARTED, after=again)
				else:
					msg("SatWatch pracuje (%s) - sprobuj pozniej." % runner.status, after=again)
			session.openWithCallback(go, MessageBox, "Uruchomic pelny skan monitorowanych pozycji?\n"
				"Potrwa kilka minut i zajmie jeden wolny tuner.", MessageBox.TYPE_YESNO)
		elif k == "fix":
			if runner.fixBouquets():
				msg("Naprawa bukietow uruchomiona." + SW_STARTED, after=again)
			else:
				msg("SatWatch pracuje (%s) - sprobuj pozniej." % runner.status, after=again)
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
					msg(text.splitlines()[0] if text else "Zrzut zapisany.", after=again)
				if not runner.run(sw.core.snapshot_save_only, done):
					msg("SatWatch pracuje (%s) - sprobuj pozniej." % runner.status, after=again)
			session.openWithCallback(snap, MessageBox, "Zapisac aktualny stan lamedb i bukietow jako zrzut referencyjny?\n"
				"Zrob to przed recznym skanem albo po recznych zmianach w bukietach.", MessageBox.TYPE_YESNO)
		elif k == "accept":
			def acc(yes):
				if not yes:
					return again()
				msg("Wyczyszczono %d pozycji DO DECYZJI." % sw.core.clear_unresolved(), after=again)
			session.openWithCallback(acc, MessageBox, "Uznac obecne kanaly w bukietach za poprawne?\n"
				"Pozycje DO DECYZJI przestana wracac w raportach.", MessageBox.TYPE_YESNO)
		elif k == "reload":
			ok = sw.reload_transponders()
			msg("Transpondery przeladowane." if ok else "Przeladowanie nie powiodlo sie - zrestartuj GUI.",
				MessageBox.TYPE_INFO if ok else MessageBox.TYPE_ERROR, after=again)
		elif k in ("restore", "restore_bq"):
			files = sw.core.list_backups() if k == "restore" else sw.core.list_bouquet_backups()
			if not files:
				return msg("Brak kopii %s." % ("satellites.xml" if k == "restore" else "bukietow"), after=again)

			def pick(c2):
				if not c2:
					return again()
				try:
					if k == "restore":
						sw.core.restore_backup(c2[1])
						ok = sw.reload_transponders()
						text = "Przywrocono satellites.xml z kopii %s.\n%s" % (c2[1], "Transpondery przeladowane." if ok else
							"Przeladowanie transponderow nie powiodlo sie - zrestartuj GUI.")
					else:
						n = sw.core.restore_bouquet_backup(c2[1])
						sw.reload_lists()
						text = "Przywrocono %d plikow bukietow z kopii %s. Listy kanalow przeladowane." % (n, c2[1])
					sw.scanner.log(text.replace("\n", " "))
					msg(text, after=again)
				except Exception as e:
					msg("Blad przywracania: %s" % e, MessageBox.TYPE_ERROR, after=again)
			session.openWithCallback(pick, ChoiceBox, title="Kopia %s (najnowsze na gorze)" % (
				"satellites.xml" if k == "restore" else "bukietow (sprzed podmiany)"), list=[(x, x) for x in files], keys=[])
		elif k == "tuners":
			from .satwatch.scanner import tuner_advice
			p = sw.preferred_slot()
			session.openWithCallback(again, EPGScanText, "konfiguracja tunerow", text=tuner_advice(-1 if p is None else p)[1])
		else:
			again()

	def title_text():
		p = sw.progress_short()
		return "SatWatch %s%s   (raporty i log: Info/EPG -> Raporty)" % (sw.core.VERSION, (" - " + p) if p else "")

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
			row = ("Przerwij skan (%s)" % sw.progress_short(), "abort")
		else:
			row = ("Skan zakonczony: %s" % (status or runner.status), "done")
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
	opts = [("SatWatch - sprawdzenie, skan, kanaly do decyzji, zrzut, kopie", "sw")]
	if satwatch_installed():
		opts.append(("UWAGA: osobna wtyczka SatWatch jest zainstalowana - odinstaluj ja", "swno"))
	opts.append(("Skan parametrow kanalow (PAT/PMT), wybor audio i E2Stream", "params"))
	opts.append(("Dopasowanie kanalow: dekoder%s" % ("" if cfg.map_mode.value == "dekoder" else
		"  (mapa dekodera nieuzywana - Ustawienia -> Program z internetu)"), "dek"))
	for base, desc in srv:
		opts.append(("Dopasowanie kanalow: serwer - %s%s" % (desc, "  (uzywana przez dekoder)" if cfg.map_mode.value == "serwer" else ""),
			("srv", base, desc)))
	if srv:
		opts.append(("Przenies wskazania z serwera do dekodera", "pins"))
	opts.append(("Raport mapy kanalow dekodera", "rep"))

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
				"Przeniesc wskazania (reczne z tunera i aliasy) z mapy serwera %s do mapy dekodera?\n"
				"Istniejace wskazania dekodera dla tych samych kanalow zostana nadpisane." % srv[0][1], MessageBox.TYPE_YESNO)
		elif k == "rep":
			session.openWithCallback(back, EPGScanText, "mapa kanalow dekodera", backup_dir() + "lokalne/raport.txt")
		elif k == "sw":
			satwatch_menu(session, back=back)
		elif k == "params":
			from .channelparamsui import ChannelParamsScreen
			session.openWithCallback(back, ChannelParamsScreen)
		else:
			session.openWithCallback(back, MessageBox, "Osobna wtyczka SatWatch dalej dziala z wlasnym harmonogramem. Odinstaluj ja:\n"
				"rm -rf %s; init 4; sleep 2; init 3\nUstawienia, raporty i kopie bukietow zostaja." % SATWATCH_DIR,
				MessageBox.TYPE_WARNING)
	session.openWithCallback(chosen, ChoiceBox, title="Kanaly", list=opts, selection=sel)


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
		session.openWithCallback(back or _noop, MessageBox, "Przeniesiono %d wskazan. Mapa dekodera przeliczona." % len(got), MessageBox.TYPE_INFO, timeout=6)
	except Exception as e:
		session.openWithCallback(back or _noop, MessageBox, "Nie udalo sie: %s" % e, MessageBox.TYPE_ERROR)


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
				session.open(MessageBox, ("Kopia EPG zapisana:\n%s" % dst) if dst else "Kopia nieudana - szczegoly w logu",
					MessageBox.TYPE_INFO if dst else MessageBox.TYPE_ERROR, timeout=6)
			t.callback.append(done)
			t.start(5000, True)
			backups_menu.timer = t
	session.openWithCallback(chosen, ChoiceBox, title="Kopie EPG",
		list=[("Odtworz EPG z kopii", "restore"), ("Zrob kopie EPG teraz (zapis + kopia)", "backup")])


from .common import scale_skins as _scale_skins  # noqa: E402
_scale_skins(globals())
