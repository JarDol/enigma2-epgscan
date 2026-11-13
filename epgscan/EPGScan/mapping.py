# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - okno "Dopasowanie kanalow" dla serwera epgpl:
# lista kanalow z bukietow i zrodlo programu dobrane przez serwer; OK - reczne wskazanie stacji z serwisow.
# Wskazanie trafia na serwer (POST /api/alias) i dziala od nastepnego pobrania programu.
# Podglad programu stacji przed zapisem (GET /api/podglad) i pobieranie programu na serwerze z tunera
# (POST /api/pobierz, GET /api/stan) - z opcjonalnym importem XMLTV / skanem satelity po zakonczeniu.

import difflib
import json
import os
import re
import threading
import time
import unicodedata

from enigma import eTimer
from Screens.Screen import Screen
from Screens.MessageBox import MessageBox
from .common import EPGChoiceBox as ChoiceBox
from Components.ActionMap import ActionMap
from Components.Label import Label
from Components.MenuList import MenuList

from .common import keys_skin, init_keys, set_keys, hints_skin, init_hints, log, cfg

DROP = {"hd", "uhd", "4k", "sd", "fhd", "poland", "channel", "tv"}
MAP_HINTS = (("OK", "Wskaz zrodlo"), ("Menu", "Sortowanie, pobieranie"), ("Info/EPG", "Synchronizacja"))
FILTERS = (("all", "wszystkie"), ("nomatch", "bez dopasowania"), ("noprog", "bez programu"), ("manual", "tylko reczne"),
	("tm", "teleman"), ("onet", "onet"), ("naz", "naziemna"), ("wp", "WP Pilot"), ("diff", "rozni sie od drugiej mapy"), ("prop", "propozycje zmian"))


def norm(name):
	s = unicodedata.normalize("NFKD", (name or "").lower().replace("ł", "l")).encode("ascii", "ignore").decode()
	s = s.replace("+", " ").replace("&", " and ")
	s = re.sub(r"\btv\s+(\d+)(?![0-9a-z])", r"tv\1", s)
	return "".join(t for t in re.split(r"[^a-z0-9]+", s) if t and t not in DROP)


STOPW = DROP | {"polska", "pl", "kanal", "the", "hevc", "h265", "extra"}


def tokens(name):
	"""Slowa nazwy (bez HD/Poland/TV itp.) - do podpowiedzi typu Historia Poland -> Discovery Historia."""
	s = unicodedata.normalize("NFKD", (name or "").lower().replace("ł", "l")).encode("ascii", "ignore").decode()
	s = s.replace("+", " plus ").replace("&", " and ")
	return [t for t in re.split(r"[^a-z0-9]+", s) if t and t not in STOPW]


def token_score(kt, nt):
	"""0..0.9: jaka czesc slow kanalu wystepuje w nazwie stacji (takze history ~ historia)."""
	if not kt or not nt:
		return 0.0
	tot = float(sum(len(t) for t in kt))
	got = 0.0
	for t in kt:
		best = 0.0
		for u in nt:
			if t == u:
				best = 1.0
				break
			if t.isdigit() or u.isdigit():
				continue
			cp = len(os.path.commonprefix([t, u]))
			if cp >= 5 and cp >= 0.7 * min(len(t), len(u)):
				best = max(best, 0.8)
		got += best * len(t)
	return 0.45 + 0.45 * got / tot if got else 0.0


def find_ref(name, bq=None):
	"""Referencja kanalu z bukietow (po nazwie, najpierw w podanym bukiecie) - do przelaczenia."""
	try:
		from .localmap import read_bouquets
		rows = read_bouquets(all_bq=True)
	except Exception:
		return None
	hit = [r for r in rows if r[1] == name]
	pref = [r for r in hit if bq and r[0] == bq]
	r = (pref or hit or [None])[0]
	return r[2] if r else None


class Zapper(object):
	"""Przelaczanie na kanal z ekranu dopasowania; po zamknieciu wraca kanal ogladany wczesniej."""

	def __init__(self, session):
		self.session = session
		self.orig = None
		self.zapped = False

	def zap(self, ref):
		if not ref:
			return False
		try:
			from enigma import eServiceReference
			if not self.zapped:
				self.orig = self.session.nav.getCurrentlyPlayingServiceOrGroup()
			self.session.nav.playService(eServiceReference(ref))
			self.zapped = True
			return True
		except Exception as e:
			log("Przelaczenie na %s: %s" % (ref, e))
			return False

	def restore(self):
		if self.zapped and self.orig is not None:
			try:
				import Screens.Standby
				if Screens.Standby.inStandby:
					self.zapped = False
					self.orig = None
					return
				self.session.nav.playService(self.orig)
			except Exception:
				pass
		self.zapped = False


def http_get_json(url, timeout=20):
	import urllib.request
	req = urllib.request.Request(url, headers={"User-Agent": "EPGScan"})
	with urllib.request.urlopen(req, timeout=timeout) as r:
		return json.loads(r.read().decode("utf-8"))


def http_post_json(url, obj=None):
	import urllib.request
	data = json.dumps(obj or {}).encode("utf-8")
	req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json", "User-Agent": "EPGScan"})
	try:
		with urllib.request.urlopen(req, timeout=20) as r:
			return json.loads(r.read().decode("utf-8"))
	except Exception as e:
		try:
			return json.loads(e.read().decode("utf-8"))      # odpowiedz serwera z bledem (400/403)
		except Exception:
			return {"ok": False, "error": str(e)}


DAYS = ("pon", "wt", "sr", "czw", "pt", "sob", "nd")


def notify(text, error=False):
	"""Powiadomienie na ekranie (takze gdy okno wtyczki jest zamkniete); w czuwaniu tylko log."""
	log(text)
	try:
		import Screens.Standby
		if Screens.Standby.inStandby:
			return
	except Exception:
		pass
	try:
		from Tools import Notifications
		Notifications.AddPopup(text, MessageBox.TYPE_ERROR if error else MessageBox.TYPE_INFO, 15, "EPGScanServer")
	except Exception:
		pass


class Background(object):
	"""Wywolanie w watku, wynik w glownym watku Enigmy (przez eTimer)."""

	def __init__(self):
		self.timer = eTimer()
		self.timer.callback.append(self.poll)
		self.th = None

	def run(self, job, done):
		self.done = done
		self.res = {}

		def work():
			try:
				self.res["data"] = job()
			except Exception as e:
				self.res["error"] = str(e)
		self.th = threading.Thread(target=work)
		self.th.daemon = True
		self.th.start()
		self.timer.start(300, False)

	def busy(self):
		return bool(self.th and self.th.is_alive())

	def stop(self):
		self.timer.stop()

	def poll(self):
		if self.th.is_alive():
			return
		self.timer.stop()
		self.done(self.res.get("data"), self.res.get("error"))


# --- pobieranie programu na serwerze z tunera ---
GRAB_MODES = (
	("only", "Tylko pobierz program na serwerze"),
	("x", "Pobierz i importuj XMLTV (bez czyszczenia EPG)"),
	("xc", "Pobierz, wyczysc cale EPG i importuj XMLTV (EPG z satelity zniknie do nastepnego skanu)"),
	("s", "Pobierz, skan satelity, potem XMLTV (bez czyszczenia EPG)"),
	("sc", "Pobierz, wyczysc cale EPG, skan satelity, potem XMLTV"),
	("wait", "Pobierz na serwerze w trakcie skanu z harmonogramu"),
)
# import gotowego pliku z serwera (bez uruchamiania pobierania)
READY_MODES = (
	("rx", "Gotowy plik z serwera: import XMLTV (bez czyszczenia EPG)"),
	("rxc", "Gotowy plik z serwera: wyczysc cale EPG i importuj XMLTV"),
	("rs", "Gotowy plik z serwera: skan satelity, potem XMLTV (bez czyszczenia EPG)"),
	("rsc", "Gotowy plik z serwera: wyczysc cale EPG, skan satelity, potem XMLTV"),
)
SAT_MODES = ("s", "sc", "rs", "rsc")
CLEAR_MODES = ("xc", "sc", "rxc", "rsc")


def local_map_job(rebuild=False):
	"""(mapa, stacje) z dekodera; mapa budowana, gdy jej nie ma albo jest z serwera (albo rebuild)."""
	import json as _j
	from .localrun import out_dir
	from .localmap import build, refresh_stations
	d = out_dir()
	mapa = None
	try:
		mapa = _j.load(open(d + "mapa.json"))
	except Exception:
		pass
	try:
		st = _j.load(open(d + "stacje.json"))
	except Exception:
		st = None
	if not st:
		st = refresh_stations(d, [])
	if rebuild or not mapa or not mapa.get("local"):
		mapa = build(d, st)[0]
	return mapa, st


def bq_desired():
	"""Bukiety do dopasowania jak dla serwera epgpl: "all" albo posortowane nazwy (#NAME) wybranych bukietow."""
	from .common import list_bouquets, xmltv_bouquet_files
	if cfg.xmltv_bouquets.value == "all":
		return "all"
	names = dict(list_bouquets())
	return sorted(names.get(f, f) for f in xmltv_bouquet_files())


def server_bq_job(base):
	"""Wyslanie wyboru bukietow na serwer epgpl i pobranie przeliczonej mapy (serwer liczy ja kilka sekund)."""
	want = bq_desired()
	try:
		from .common import site_order
		res = http_post_json(base + "api/bukiety", {"mode": "all" if want == "all" else "selected",
			"names": [] if want == "all" else want, "sites": site_order(), "one_place": bool(cfg.one_place.value),
			"cand": bool(cfg.cand_night.value)}) or {}
	except Exception:
		res = {}
	if not res.get("ok"):
		return http_get_json(base + "mapa.json"), http_get_json(base + "stacje.json")   # serwer sprzed 0.13.3
	mapa = None
	for _ in range(30):
		time.sleep(2)
		try:
			mapa = http_get_json(base + "mapa.json")
		except Exception:
			continue
		if mapa.get("bouquet_filter") == want and (not res.get("sites") or mapa.get("sites") == res.get("sites")):
			break
	if mapa is None:
		mapa = http_get_json(base + "mapa.json")
	return mapa, http_get_json(base + "stacje.json")


def sync_settings_bg():
	"""Po zmianie kolejnosci serwisow albo bukietow: w tle przeliczenie mapy dekodera i wyslanie ustawien na serwer."""
	import threading

	def work():
		try:
			if cfg.map_mode.value == "dekoder":
				local_map_job(rebuild=True)
		except Exception as e:
			log("Mapa dekodera: %s" % e)
		for base, desc in epgpl_servers():
			try:
				server_bq_job(base)
				log("Serwer epgpl %s: wyslano kolejnosc serwisow i bukiety" % base)
			except Exception as e:
				log("Serwer epgpl %s: %s" % (base, e))
	th = threading.Thread(target=work)
	th.daemon = True
	th.start()


def local_pin(name, target):
	"""Wskazanie dla mapy z dekodera: zapis do /etc/enigma2/epgscan/wskazania.txt i przeliczenie mapy."""
	from .localmap import load_pins, save_pins
	pins = load_pins()
	if target:
		pins[name] = target
	else:
		pins.pop(name, None)
	save_pins(pins)
	local_map_job(rebuild=True)
	return {"ok": True}


def start_import(session, mode):
	"""Import na dekoderze wg trybu (x/xc/s/sc albo rx/rxc/rs/rsc). Zwraca (ok, opis)."""
	from .engine import get_engine
	eng = get_engine(session)
	m = mode[1:] if mode.startswith("r") else mode
	clear = m.endswith("c")
	if eng.running:
		return False, "trwa skan EPG - import pominiety (zrobi go biezacy skan albo nastepny)"
	if m.startswith("x") or not cfg.sat_import.value:
		ok = eng.start_xmltv_only(clear=clear, local=False)
		what = "import XMLTV%s" % (" po wyczyszczeniu EPG" if clear else "")
	else:
		ok = eng.start(clear=clear, force_xmltv=True, local=False)
		what = "skan satelity%s, potem XMLTV" % (" po wyczyszczeniu EPG" if clear else "")
	return ok, ("uruchomiono: %s" % what) if ok else ("nie udalo sie uruchomic: %s (sprawdz zrodla XMLTV)" % what)


def epgpl_servers():
	"""[(adres serwera epgpl, opis zrodla)] ze zrodel XMLTV z serwera epgpl (<epgpl>)."""
	from .xmltv import load_sources
	out, seen = [], set()
	for s in load_sources()[0]:
		if s.epgpl:
			b = s.epgpl if s.epgpl.endswith("/") else s.epgpl + "/"
			if b not in seen:
				seen.add(b)
				out.append((b, s.desc))
	return out


def tuner_grab_on():
	"""Czy harmonogram dekodera uruchamia pobieranie na serwerze (profil domyslny) - dla okna TUNER_WINDOW serwera."""
	from .common import sched_active
	return bool(sched_active() and cfg.xmltv.value and cfg.p_fetch.value and
		cfg.p_srv.value == "zawsze")


def report_schedule():
	"""Zglasza serwerom epgpl harmonogram dekodera - serwer pomija wlasne pobieranie w oknie +-TUNER_WINDOW h."""
	from .common import next_run
	grab = bool(tuner_grab_on())
	nxt = int(next_run() or 0) if grab else 0
	servers = epgpl_servers()

	def work():
		for b, _ in servers:
			http_post_json(b + "api/tuner", {"grab": grab, "next": nxt})
	if servers:
		t = threading.Thread(target=work)
		t.daemon = True
		t.start()


class ServerGrab(object):
	"""Sledzi pobieranie na serwerze w tle (dziala tez po zamknieciu okna); po udanym pobraniu uruchamia import."""

	def __init__(self):
		self.active = False
		self.mode = ""
		self.text = ""
		self.listeners = []
		self.bg = Background()
		self.timer = eTimer()
		self.timer.callback.append(self.check)

	def changed(self):
		for f in self.listeners[:]:
			try:
				f()
			except Exception:
				pass

	def start(self, session, base, mode, desc, only_if_old=False):
		"""only_if_old: pobieranie tylko, gdy na serwerze nie ma pliku programu albo jest starszy niz 30 h."""
		if self.active:
			return
		self.session, self.base, self.mode, self.desc = session, base, mode, desc
		self.active = True
		self.seen_running = False
		self.t0 = time.time()
		self.text = "Uruchamianie pobierania na serwerze..."
		self.changed()

		def job():
			before = http_get_json(base + "api/stan").get("guide", 0)
			if only_if_old and before and time.time() - before < 30 * 3600:
				return before, {"fresh": True}
			return before, http_post_json(base + "api/pobierz")
		self.bg.run(job, self.started)

	def started(self, data, err):
		if err or not data:
			return self.end("Pobieranie na serwerze nie ruszylo: %s" % (err or "brak odpowiedzi"), True)
		self.before, res = data
		if res.get("fresh"):
			self.timer.stop()
			self.active = False
			self.text = ""
			log("Serwer %s: plik programu aktualny (%s) - bez pobierania" % (self.desc,
				time.strftime("%d.%m %H:%M", time.localtime(self.before))))
			self.changed()
			return
		if not res.get("ok") and not res.get("running"):
			return self.end("Pobieranie na serwerze nie ruszylo: %s" % res.get("error", "?"), True)
		log("Serwer %s: pobieranie programu %s (%s)" % (self.desc, "juz trwalo - czekam na koniec" if res.get("running") else "uruchomione",
			dict(GRAB_MODES)[self.mode]))
		self.text = "Pobieranie na serwerze trwa..."
		self.changed()
		self.timer.start(10000, False)

	def check(self):
		if self.bg.busy():
			return

		def job():
			return http_get_json(self.base + "api/stan")
		self.bg.run(job, self.checked)

	def checked(self, st, err):
		el = int(time.time() - self.t0)
		if err or not st:
			self.text = "Pobieranie na serwerze (%d:%02d) - brak odpowiedzi serwera: %s" % (el // 60, el % 60, err)
			self.changed()
			if el > 3 * 3600:
				self.end("Pobieranie na serwerze: brak odpowiedzi serwera od dluzszego czasu - przerywam czekanie", True)
			return
		if st.get("running"):
			self.seen_running = True
			self.text = "Pobieranie na serwerze (%d:%02d): %s" % (el // 60, el % 60, st.get("line", ""))
			self.changed()
			return
		if not self.seen_running and el < 60 and st.get("guide", 0) <= self.before:
			return                                  # usluga dopiero startuje
		self.timer.stop()
		if st.get("guide", 0) > self.before:
			self.finished_ok(el)
		else:
			self.end("Pobieranie na serwerze nieudane - EPG nie zmienione.\n%s" % st.get("result", ""), True)

	def finished_ok(self, el):
		msg = "Program na serwerze pobrany (%d:%02d)." % (el // 60, el % 60)
		if self.mode == "wait":
			return self.end(msg + " Import XMLTV po skanie z satelity.")
		if self.mode == "only":
			return self.end(msg + " Odbiornik wezmie go przy najblizszym imporcie XMLTV.")
		ok, what = start_import(self.session, self.mode)
		self.end("%s %s." % (msg, what[0].upper() + what[1:]), not ok)

	def cancel(self):
		"""Koniec czekania na dekoderze (pobieranie na serwerze trwa dalej, import nie ruszy)."""
		if not self.active:
			return
		self.bg.stop()
		self.timer.stop()
		self.active = False
		self.text = "Czekanie na serwer przerwane - pobieranie na serwerze trwa dalej, import nie zostanie uruchomiony"
		log("Serwer %s: %s" % (self.desc, self.text))
		self.changed()

	def plan(self):
		"""Co dekoder zrobi po pobraniu (do ekranu glownego)."""
		return {"only": "nic (tylko pobranie na serwerze)", "x": "import XMLTV (bez czyszczenia EPG)",
			"xc": "czyszczenie EPG i import XMLTV", "s": "skan satelity, potem XMLTV",
			"sc": "czyszczenie EPG, skan satelity, potem XMLTV",
			"wait": "import XMLTV po skanie z harmonogramu"}.get(self.mode, "")

	def end(self, text, error=False):
		self.timer.stop()
		self.active = False
		self.text = text
		if self.mode == "wait":
			log("Serwer %s: %s" % (self.desc, text.replace("\n", " ")))    # skan z harmonogramu - bez okienek
		else:
			notify(text, error)
		self.changed()


GRAB = None


def server_grab():
	global GRAB
	if GRAB is None:
		GRAB = ServerGrab()
	return GRAB


class EPGScanLocalTest(Screen):
	"""Etap 1 pobierania na dekoderze: teleman.pl pobierany przez dekoder, porownanie z plikiem serwera."""
	skin = """
	<screen position="center,center" size="1600,920" title="EPG Scan - pobieranie na dekoderze (test porownawczy)">
		<widget name="status" position="20,15" size="1560,120" font="Regular;28" />
		<widget name="text" position="20,145" size="1560,690" font="Regular;24" />
		%s
	</screen>""" % keys_skin(20, 850, 1560)

	def __init__(self, session, base, mapa):
		Screen.__init__(self, session)
		from Components.ScrollLabel import ScrollLabel
		self.base, self.mapa = base, mapa
		self["status"] = Label("")
		self["text"] = ScrollLabel("")
		init_keys(self)
		set_keys(self, red="Zamknij (przerywa)")
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions", "DirectionActions"], {
			"cancel": self.close, "red": self.close, "ok": self.close,
			"up": self["text"].pageUp, "down": self["text"].pageDown,
			"left": self["text"].pageUp, "right": self["text"].pageDown,
		}, -1)
		self.grab = None
		self.res = {}
		self.timer = eTimer()
		self.timer.callback.append(self.tick)
		self.onClose.append(self.stop)
		self.onLayoutFinish.append(self.begin)

	def begin(self):
		from .localgrab import LocalGrab, PARSERS
		st, names = [], {}
		for c in self.mapa.get("channels", []):
			if c.get("tag") in PARSERS and c.get("site_id"):
				xid = "%s.%s" % (c["tag"], re.sub(r"[^A-Za-z0-9-]", "_", c["site_id"]))
				if xid not in names:
					names[xid] = c["sname"] or c["name"]
					st.append((c["tag"], c["site_id"], xid, names[xid]))
		self.names = names
		if not st:
			self["status"].setText("W mapie serwera nie ma kanalow z obslugiwanych serwisow.")
			return
		from .common import local_conns
		self.grab = LocalGrab(st, days=int(cfg.local_days.value) + 1, conns=local_conns(), max_total=int(cfg.local_conn_max.value),
			pause=float(cfg.local_pause.value), errlog=(self.out_dir() + "bledy.txt") if cfg.local_errlog.value else None)
		log("Pobieranie na dekoderze (test): stacji %d" % len(st))
		self["text"].setText("Dekoder pobiera sam teleman, onet, naziemna i WP (od wczoraj, %d dni, polaczen %s), potem "
			"porownuje wynik z plikiem programu serwera. Serwisy rownolegle.\nZamkniecie okna przerywa test.\n\nWynik bedzie tez w pliku: %s" % (
			self.grab.days, "tm %s, onet %s, naz %s, wp %s, lacznie maks. %d" % (cfg.local_conn_tm.value, cfg.local_conn_onet.value,
			cfg.local_conn_naz.value, cfg.local_conn_wp.value, self.grab.max_total), self.out_dir() + "porownanie.txt"))

		def work():
			try:
				self.grab.run()
				if self.grab.cancel:
					return
				self.res["phase"] = "serwer"
				from .localgrab import server_progs, compare, write_xmltv
				write_xmltv(self.out_dir() + "test.xml.xz", self.grab.result, names)
				srv = server_progs(self.base + "pl.xml.xz", set(names))
				self.res["report"] = compare(self.grab.result, srv, names)
			except Exception as e:
				self.res["error"] = str(e)
		self.th = threading.Thread(target=work)
		self.th.daemon = True
		self.th.start()
		self.timer.start(1000, False)

	def out_dir(self):
		from .common import backup_dir
		d = backup_dir() + "lokalne/"
		try:
			if not os.path.isdir(d):
				os.makedirs(d)
		except Exception:
			pass
		return d

	def tick(self):
		g = self.grab
		el = int(time.time() - g.t0)
		if self.th.is_alive():
			if self.res.get("phase") == "serwer":
				self["status"].setText("Pobrane (%d:%02d). Pobieranie pliku programu z serwera do porownania..." % (el // 60, el % 60))
			else:
				tot = getattr(g, "total", 0)
				left = (el * (tot - g.done) // g.done) if g.done else 0
				self["status"].setText("Pobieranie na dekoderze: %d / %d stron, bledow %d - %s\nczas %d:%02d, zostalo ok. %d:%02d" % (
					g.done, tot, g.errors, g.progress(), el // 60, el % 60, left // 60, left % 60))
			return
		self.timer.stop()
		if self.res.get("error"):
			self["status"].setText("Blad: %s" % self.res["error"])
			log("Pobieranie na dekoderze (test): blad %s" % self.res["error"])
			return
		took = int(g.t_end - g.t0)
		n = sum(len(v) for v in g.result.values())
		head = "Gotowe: %d stron w %d:%02d (bez danych w serwisie %d), ponowionych %d, bledow po ponowieniu %d (%s), audycji %d dla %d stacji.%s" % (
			g.total, took // 60, took % 60, g.missing, g.retried, g.errors, g.progress(), n, len(g.result),
			("\nSzczegoly bledow: %sbledy.txt" % self.out_dir()) if g.retried and cfg.local_errlog.value else "")
		self["status"].setText(head)
		rep = self.res.get("report", "")
		self["text"].setText(rep)
		log("Pobieranie na dekoderze (test): %s" % head.replace("\n", " "))
		try:
			with open(self.out_dir() + "porownanie.txt", "w") as f:
				f.write(time.strftime("%d.%m.%Y %H:%M\n") + head + "\n\n" + rep + "\n")
		except Exception:
			pass
		set_keys(self, red="Zamknij")

	def stop(self):
		self.timer.stop()
		if self.grab and getattr(self, "th", None) and self.th.is_alive():
			self.grab.cancel = True
			log("Pobieranie na dekoderze (test): przerwane")


PREVIEW_CACHE = {}      # (serwer albo "", stacja) -> (czas, odpowiedz) - podglad pobrany z serwisu, waznosc 3 h


def fill_stops(progs):
	"""Audycje bez czasu konca (np. WP): koniec = poczatek nastepnej."""
	progs.sort(key=lambda p: p["start"])
	for i, p in enumerate(progs):
		if not p.get("stop"):
			p["stop"] = progs[i + 1]["start"] if i + 1 < len(progs) else p["start"] + 1800
	return progs


def cached_preview(base, target):
	c = PREVIEW_CACHE.get((base or "", target))
	return c[1] if c and time.time() - c[0] < 3 * 3600 else None


class EPGScanPreview(Screen):
	skin = """
	<screen position="center,center" size="1600,920" title="EPG Scan - podglad programu">
		<widget name="list" position="20,15" size="740,530" font="Regular;26" itemHeight="44" scrollbarMode="showOnDemand" />
		<eLabel position="778,13" size="804,454" backgroundColor="#00505050" />
		<widget source="session.VideoPicture" render="Pig" position="780,15" size="800,450" zPosition="1" backgroundColor="#ff000000" />
		<widget name="pig_info" position="780,475" size="800,72" font="Regular;24" foregroundColor="#c0c0c0" />
		<widget name="info" position="20,555" size="1560,280" font="Regular;28" foregroundColor="#c0c0c0" />
		%s
	</screen>""" % keys_skin(20, 850, 1560)

	def __init__(self, session, base, target, label, chan, can_save=True, zapper=None, chan_ref=None, window=None):
		"""window = (od, do) w sekundach wzgledem teraz, np. (-7200, 14400): tylko audycje z tego przedzialu."""
		Screen.__init__(self, session)
		self.base, self.target, self.can_save, self.window = base, target, can_save, window
		self.zapper, self.chan_ref, self.chan = zapper, chan_ref, chan
		self["pig_info"] = Label(("Niebieski: przelacz na kanal %s i porownaj z programem stacji." % chan)
			if (zapper and chan_ref) else "")
		self.setTitle("Podglad: %s  (kanal: %s)%s" % (label, chan, "  - od 2 h wstecz do 4 h naprzod" if window else ""))
		self["list"] = MenuList([])
		self["info"] = Label(("Pobieranie programu stacji z serwera...\n(gdy stacji nie ma w pliku programu, serwer pobiera ja teraz z "
			"serwisu - do kilkudziesieciu sekund)") if base else "Dekoder pobiera program stacji z serwisu (2 dni, kilka sekund)...")
		init_keys(self)
		set_keys(self, red="Wroc", green="Zapisz to zrodlo" if can_save else "",
			blue="Przelacz na kanal" if (zapper and chan_ref) else "")
		self.progs = []
		self.bg = Background()
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions"], {
			"ok": self.save,
			"green": self.save,
			"blue": self.zap,
			"cancel": self.back,
			"red": self.back,
		}, -1)
		self.onClose.append(self.bg.stop)
		self.onLayoutFinish.append(self.layout_done)

	def layout_done(self):
		try:
			self["list"].onSelectionChanged.append(self.show_desc)
		except Exception:
			pass
		hit = cached_preview(self.base, self.target)
		if hit:
			self.loaded(hit, None)
			return
		if not self.base:
			self.bg.run(self.local_preview, self.loaded)
			return
		import urllib.parse
		url = self.base + "api/podglad?target=" + urllib.parse.quote(self.target, safe="")
		self.bg.run(lambda: http_get_json(url, timeout=180), self.loaded)

	def zap(self):
		if self.zapper and self.chan_ref and self.zapper.zap(self.chan_ref):
			self["pig_info"].setText("W okienku: %s (na zywo).\nPorownaj z biezaca audycja na liscie obok.\n"
				"Po wyjsciu z dopasowania wroci poprzedni kanal." % self.chan)

	def local_preview(self):
		"""Podglad bez serwera: dekoder pobiera stacje na 2 dni (od dzis)."""
		from .localgrab import LocalGrab
		from .localmap import xid_of
		tag, sid = self.target.split(":", 1)
		xid = xid_of(tag, sid)
		# najpierw pliki dekodera: program (kanaly z bukietow) i kandydaci z nocy
		from .station import file_progs
		from .localrun import out_dir
		for fn, what in (("pl.xml.xz", "plik programu dekodera"), ("kandydaci.xml.xz", "program kandydatow z nocy")):
			pr = file_progs(out_dir() + fn, xid)
			if pr and pr[-1]["start"] > time.time():
				return {"ok": True, "from": what, "programmes": pr}
		if self.window:
			# okno -2h..+4h: strona dzisiejsza i sasiednia (wczoraj w nocy, jutro wieczorem)
			back = 1 if time.localtime().tm_hour < 6 else 0
			g = LocalGrab([(tag, sid, xid, sid)], days=2, conns={tag: 2}, max_total=2, pause=0, back=back)
		else:
			g = LocalGrab([(tag, sid, xid, sid)], days=3, conns={tag: 2}, max_total=2, pause=0)
		g.run()
		now = time.time()
		progs = [{"start": a, "stop": b, "title": t, "sub": "", "desc": d[:500]} for a, b, t, d in g.result.get(xid, [])
			if b > now - 3600 and a < now + 2 * 86400][:120]
		if not progs:
			return {"ok": False, "error": "serwis nie zwrocil programu dla tej stacji"}
		return {"ok": True, "from": "pobrane teraz przez dekoder", "programmes": progs}

	def loaded(self, data, err):
		if err or not data or not data.get("ok"):
			self["info"].setText("Nie udalo sie pobrac podgladu: %s" % ((data or {}).get("error") or err or "brak odpowiedzi"))
			return
		if not cached_preview(self.base, self.target):
			PREVIEW_CACHE[(self.base or "", self.target)] = (time.time(), data)
		self.src = data.get("from", "")
		self.progs = fill_stops([dict(p) for p in data.get("programmes", [])])
		if self.window:
			now = time.time()
			a, b = now + self.window[0], now + self.window[1]
			self.progs = [p for p in self.progs if (p.get("stop") or p["start"] + 1800) > a and p["start"] < b]
			if not self.progs:
				self["info"].setText("Brak audycji od %s do %s (%s)." % (time.strftime("%H:%M", time.localtime(a)),
					time.strftime("%H:%M", time.localtime(b)), data.get("from", "")))
				return
		rows = []
		for p in self.progs:
			lt = time.localtime(p["start"])
			t = "%s %s  %s%s" % (DAYS[lt.tm_wday], time.strftime("%d.%m %H:%M", lt), p["title"],
				("  - " + p["sub"]) if p.get("sub") else "")
			rows.append((t, p))
		self["list"].setList(rows)
		# od biezacej audycji
		now = time.time()
		for i, p in enumerate(self.progs):
			if (p.get("stop") or p["start"]) > now:
				try:
					self["list"].moveToIndex(i)
				except Exception:
					pass
				break
		self.show_desc()

	def show_desc(self):
		cur = self["list"].getCurrent()
		n = len(self.progs)
		with_desc = len([1 for p in self.progs if p.get("desc")])
		head = "Audycji: %d, z opisem: %d.  Zrodlo podgladu: %s" % (n, with_desc, getattr(self, "src", ""))
		if not cur:
			self["info"].setText(head)
			return
		p = cur[1]
		self["info"].setText(head + "\n" + (p.get("desc") or "(brak opisu)"))

	def save(self):
		if self.can_save:
			self.close(True)

	def back(self):
		self.close(False)


class EPGScanDiffs(Screen):
	"""Roznice dekoder / serwer: lista kanalow, pod nia pelne zrodla obu stron; wybor strony dla kanalu albo dla wszystkich."""
	skin = """
	<screen position="center,center" size="1600,920" title="EPG Scan - roznice dekoder / serwer">
		<widget name="list" position="20,15" size="1560,616" font="Regular;28" itemHeight="44" scrollbarMode="showOnDemand" />
		<eLabel position="20,640" size="1560,2" backgroundColor="#00505050" />
		<widget name="info" position="20,650" size="1560,190" font="Regular;28" />
		%s
	</screen>""" % keys_skin(20, 850, 1560)

	def __init__(self, session, items, site_names, local):
		Screen.__init__(self, session)
		self.drows, self.names = items, site_names
		self.setTitle("Roznice: dekoder / serwer (%d)" % len(items))
		rows = []
		for d in items:
			rows.append(("%s%s" % (d["name"], "   (poza wspolna mapa)" if d.get("pins_only") else ""), d))
		self["list"] = MenuList(rows)
		self["info"] = Label("")
		init_keys(self)
		set_keys(self, red="Zamknij", green="Wez z dekodera", yellow="Wez z serwera", blue="Wszystkie...")
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions"], {
			"ok": self.ask_one, "cancel": self.close, "red": self.close,
			"green": lambda: self.one("dekoder"), "yellow": lambda: self.one("serwer"), "blue": self.all_menu,
		}, -1)
		self.onLayoutFinish.append(self.layout_done)

	def layout_done(self):
		try:
			self["list"].onSelectionChanged.append(self.show_info)
		except Exception:
			pass
		self.show_info()

	def current(self):
		c = self["list"].getCurrent()
		return c[1] if c else None

	def show_info(self):
		from .sync import side_text
		d = self.current()
		if not d:
			self["info"].setText("")
			return
		self["info"].setText("%s%s\nDekoder:  %s\nSerwer:   %s\nZielony - zrodlo z dekodera (zapis na serwerze), zolty - z serwera (zapis na dekoderze), "
			"niebieski - wszystkie naraz." % (d["name"], "  [bukiet: %s]" % d["bq"] if d.get("bq") else "",
			side_text(d, "dek", self.names), side_text(d, "srv", self.names)))

	def ask_one(self):
		d = self.current()
		if d:
			self.session.openWithCallback(lambda a: a and a[1] and self.one(a[1]), ChoiceBox, title=d["name"], list=[
				("Wez z dekodera (zapis na serwerze)", "dekoder"), ("Wez z serwera (zapis na dekoderze)", "serwer"), ("Zostaw", None)])

	def one(self, winner):
		d = self.current()
		if d:
			self.close((winner, [d]))

	def all_menu(self):
		self.session.openWithCallback(lambda a: a and a[1] and self.close((a[1], self.drows)), ChoiceBox,
			title="Wszystkie roznice (%d)" % len(self.drows), list=[
				("Wygrywa dekoder (serwer dostaje wskazania dekodera)", "dekoder"),
				("Wygrywa serwer (dekoder dostaje wskazania serwera)", "serwer"), ("Anuluj", None)])


class EPGScanMapping(Screen):
	skin = """
	<screen position="center,center" size="1600,920" title="EPG Scan - dopasowanie kanalow">
		<widget name="list" position="20,15" size="1560,572" font="Regular;28" itemHeight="44" scrollbarMode="showOnDemand" />
		<widget name="info" position="20,595" size="1560,120" font="Regular;28" foregroundColor="#c0c0c0" />
		<widget name="keys" position="20,720" size="1560,62" font="Regular;24" />
		%s
		%s
	</screen>""" % (hints_skin(20, 788, 1560, hints=MAP_HINTS), keys_skin(20, 850, 1560))

	def __init__(self, session, base, desc):
		"""base = adres serwera epgpl albo None - mapa budowana na dekoderze (Kanaly -> dekoder)."""
		Screen.__init__(self, session)
		self.local = not base
		self.base = None if self.local else (base if base.endswith("/") else base + "/")
		self.setTitle("Dopasowanie kanalow - %s" % desc)
		self["list"] = MenuList([])
		self.zapper = Zapper(session)
		self["info"] = Label("Mapa kanalow...")
		self["keys"] = Label(("Zmiana zrodla dziala od nastepnego %s.\n"
			"[auto] automat, [alias] wskazanie nazwa, [reczne] wskazane, [--] wylaczony, [ ?? ] brak dopasowania") % (
			"importu XMLTV (mapa przeliczana od razu)" if self.local else "pobrania na serwerze"))
		init_hints(self, MAP_HINTS)
		init_keys(self)
		self.filter = 0
		self.diff = None             # roznice z druga mapa (dekoder <-> serwer); None = nie policzone / brak serwera
		self.diff_asked = False
		self.sbg = Background()
		self.pbg = Background()
		self.props = {}              # propozycje zmian recznych wskazan: {kanal: (powod, cel, nazwa stacji)}
		self.mapa = None
		self.stacje = {}
		self.res = {}
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions", "MenuActions", "EPGSelectActions"], {
			"ok": self.change,
			"cancel": self.close,
			"red": self.close,
			"green": self.load,
			"yellow": self.next_filter,
			"blue": self.bq_menu,
			"menu": self.sort_menu,
			"info": self.sync_menu,
		}, -1)
		self.timer = eTimer()
		self.timer.callback.append(self.poll)
		self.onClose.append(self.timer.stop)
		self.onClose.append(lambda: self.sbg.stop())
		self.onClose.append(lambda: self.pbg.stop())
		self.onClose.append(self.zapper.restore)
		self.onLayoutFinish.append(self.layout_done)
		self.desc = desc
		self.grab = server_grab()
		self.grab.listeners.append(self.grab_changed)
		self.onClose.append(self.unhook)

	def unhook(self):
		try:
			self.grab.listeners.remove(self.grab_changed)
		except Exception:
			pass

	def grab_changed(self):
		self.show_info()
		if not self.grab.active:
			self.load()                    # nowa mapa po pobraniu

	def layout_done(self):
		try:
			self["list"].onSelectionChanged.append(self.show_info)
		except Exception:
			pass
		if not cfg.xmltv_bq_asked.value:
			self.show_keys()
			self["info"].setText("Najpierw wybierz, ktore bukiety dopasowywac.")
			# okno wyboru dopiero po pokazaniu ekranu (w onLayoutFinish ekran nie jest jeszcze modalny)
			self.ask_timer = eTimer()
			self.ask_timer.callback.append(self.ask_first)
			self.ask_timer.start(200, True)
			return
		self.load()

	def show_keys(self):
		from .common import xmltv_bouquets_text
		set_keys(self, red="Zamknij", green="Odswiez", yellow="Pokaz: %s" % FILTERS[self.filter][1],
			blue="Bukiety: %s" % xmltv_bouquets_text())

	# --- ktore bukiety dopasowywac (wspolne z Ustawienia -> Program z internetu -> Kanaly z bukietow) ---
	def bq_menu(self, first=False):
		from .common import XB_CHOICES
		cur = cfg.xmltv_bouquets.value
		opts = [(("* " if k == cur else "   ") + t, k) for k, t in XB_CHOICES]
		opts.append(("   Wybierz bukiety...", "pick"))
		title = ("Ktore bukiety dopasowywac? Te same kanaly dostana program z internetu (zmiana: niebieski tutaj "
			"albo Ustawienia -> Program z internetu)") if first else "Lista kanalow do dopasowania"
		self.session.openWithCallback(lambda c: self.bq_chosen(c, first), ChoiceBox, title=title, list=opts)

	def server_sites_differ(self):
		"""Serwer 0.14.0+ (ma kolejnosc z dekodera w bukiety.json) liczy z inna kolejnoscia serwisow niz dekoder."""
		from .common import site_order
		sites = [t for t in (self.mapa or {}).get("sites", []) if t in ("tm", "onet", "naz", "wp")]
		return bool(sites) and sites != site_order()

	def ask_first(self):
		self.bq_menu(first=True)

	def bq_chosen(self, c, first):
		if not c:
			if first:
				self.close()          # pierwsze otwarcie: wybor jest wymagany
			return
		if c[1] == "pick":
			from .setup import EPGScanBouquets
			self.session.openWithCallback(lambda *a: self.bq_set("selected"), EPGScanBouquets, "xmltv")
			return
		if c[1] == "selected" and not cfg.xmltv_bq_sel.value:
			from .setup import EPGScanBouquets
			self.session.openWithCallback(lambda *a: self.bq_set("selected"), EPGScanBouquets, "xmltv")
			return
		self.bq_set(c[1])

	def bq_set(self, mode):
		from .common import save_cfg
		cfg.xmltv_bouquets.value = mode
		cfg.xmltv_bouquets.save()
		cfg.xmltv_bq_asked.value = True
		cfg.xmltv_bq_asked.save()
		save_cfg()
		self.show_keys()
		if self.local:
			self["info"].setText("Mapa kanalow z dekodera: przeliczanie dla wybranych bukietow...")
			self.run(lambda: local_map_job(rebuild=True), self.loaded)
		else:
			self.bq_pushed = True
			self["info"].setText("Serwer epgpl: wyslanie wyboru bukietow i przeliczenie mapy (kilka sekund)...")
			base = self.base
			self.run(lambda: server_bq_job(base), self.loaded)

	# --- pobieranie w tle ---
	def run(self, job, done):
		self.done = done
		self.res = {}

		def work():
			try:
				self.res["data"] = job()
			except Exception as e:
				self.res["error"] = str(e)
		self.th = threading.Thread(target=work)
		self.th.daemon = True
		self.th.start()
		self.timer.start(300, False)

	def poll(self):
		if self.th.is_alive():
			return
		self.timer.stop()
		self.done(self.res.get("data"), self.res.get("error"))

	def load(self):
		self.show_keys()
		if self.local:
			self["info"].setText("Mapa kanalow z dekodera (pierwszy raz: pobieranie list stacji serwisow, ok. 10 s)...")
			self.run(local_map_job, self.loaded)
			return
		self["info"].setText("Pobieranie mapy kanalow z serwera...")

		def job():
			return http_get_json(self.base + "mapa.json"), http_get_json(self.base + "stacje.json")
		self.run(job, self.loaded)

	def loaded(self, data, err):
		if err or not data:
			self["info"].setText(("Nie udalo sie zbudowac mapy na dekoderze:\n%s" % err) if self.local else
				"Nie udalo sie pobrac mapy z serwera %s:\n%s\n(wymagany epgpl 0.7.0 lub nowszy)" % (self.base, err))
			return
		self.mapa, self.stacje = data
		if not self.local and self.mapa is not None:
			# serwer liczy mape z innym wyborem bukietow niz dekoder - wyslij wybor (raz na otwarcie)
			if "bouquet_filter" not in self.mapa:
				self.old_server = True
			elif (self.mapa.get("bouquet_filter") != bq_desired() or self.server_sites_differ()) \
					and not getattr(self, "bq_pushed", False):
				self.bq_pushed = True
				self["info"].setText("Serwer epgpl liczy mape dla innych bukietow albo innej kolejnosci serwisow - wysylam ustawienia dekodera...")
				base = self.base
				self.run(lambda: server_bq_job(base), self.loaded)
				return
		self.fill()
		self.diff_start()
		self.props_start()

	# --- lista ---
	def site_name(self, tag):
		return (self.mapa or {}).get("site_names", {}).get(tag, tag)

	def fill(self):
		chans = self.mapa.get("channels", [])
		f = FILTERS[self.filter][0]
		dn = set(d["name"] for d in (self.diff or []))
		rows = []
		for c in chans:
			if not self.passes(c, f, dn, self.props):
				continue
			none = not c["tag"] and not c["off"]
			if c["off"]:
				txt = "[--]  %s  -  bez programu%s" % (c["name"], " (wylaczony recznie)" if c["manual"] else " (wylaczony w aliases.txt)")
			elif none:
				txt = "[ ?? ]  %s  -  brak dopasowania" % c["name"]
			else:
				kind = "reczne" if c["manual"] else ("alias" if "alias" in c["how"] else "auto")
				txt = "[%s]  %s  ->  %s: %s" % (kind, c["name"], self.site_name(c["tag"]), c["sname"])
			if c["name"] in self.props:
				txt = "[!] " + txt
			rows.append((none, chans.index(c), txt, c))
		if cfg.map_sort.value == "abc":
			rows.sort(key=lambda r: (not r[0], r[3]["name"].lower()))     # bez programu na gorze, potem alfabetycznie
		else:
			rows.sort(key=lambda r: (not r[0], r[1]))                     # bez programu na gorze, potem jak w bukietach
		prev = self.current()
		self["list"].setList([(r[2], r[3]) for r in rows])
		if prev:                                   # po odswiezeniu zostan na tym samym kanale
			for i, r in enumerate(rows):
				if r[3]["name"] == prev["name"] and r[3].get("bq") == prev.get("bq"):
					try:
						self["list"].moveToIndex(i)
					except Exception:
						pass
					break
		self.show_info()

	def current(self):
		cur = self["list"].getCurrent()
		return cur[1] if cur else None

	def show_info(self):
		if not self.mapa:
			return
		if self.grab.active or self.grab.text:
			self["keys"].setText(self.grab.text)
		c = self.current()
		n = len(self.mapa.get("channels", []))
		bad = len([1 for x in self.mapa["channels"] if not x["tag"] and not x["off"]])
		man = len([1 for x in self.mapa["channels"] if x["manual"]])
		head = "Kanalow: %d, bez dopasowania: %d, recznych wskazan: %d.  Kolejnosc serwisow: %s" % (
			n, bad, man, ", ".join(self.site_name(t) for t in self.mapa.get("sites", [])))
		extra = []                                  # jedna linia: propozycje i roznice (miejsce na szczegoly kanalu)
		if self.props:
			extra.append("Propozycje zmian [!]: %d" % len(self.props))
		if self.diff is not None:
			extra.append("Rozni sie od %s: %d%s" % ("serwera" if self.local else "dekodera", len(self.diff),
				" (Info/EPG)" if self.diff else ""))
		if extra:
			head += "\n" + "   |   ".join(extra)
		if getattr(self, "old_server", False) and cfg.xmltv_bouquets.value != "all":
			head += "\nSerwer epgpl starszy niz 0.13.3 - dopasowuje wszystkie bukiety (zaktualizuj serwer)"
		if not c:
			self["info"].setText(head)
			return
		if c["tag"]:
			det = "%s [%s] -> %s: %s (%s)%s" % (c["name"], c["bq"], self.site_name(c["tag"]), c["sname"], c["site_id"],
				", kopii na satelicie %d" % c["copies"] if c["copies"] > 1 else "")
		else:
			det = "%s [%s] - %s" % (c["name"], c["bq"], "bez programu (wylaczony)" if c["off"] else "brak dopasowania")
		pr = self.props.get(c["name"])
		if pr:
			det += "\n[!] %s%s" % (pr[0], (" - proponowana: %s: %s" % (self.site_name(pr[1].split(":")[0]), pr[2])) if pr[1] else "")
		self["info"].setText(head + "\n" + det)

	@staticmethod
	def passes(c, f, diff_names, props=None):
		none = not c["tag"] and not c["off"]
		if f == "nomatch":                    # [ ?? ] - automat nic nie znalazl
			return none
		if f == "noprog":                     # [ ?? ] i [--] (wylaczone - recznie albo w aliases.txt)
			return not c["tag"]
		if f == "manual":                     # tylko reczne wskazania stacji; wylaczone = "bez programu"
			return bool(c["manual"]) and not c["off"]
		if f in ("tm", "onet", "naz", "wp"):  # zrodlo w danym serwisie
			return c["tag"] == f and not c["off"]
		if f == "diff":
			return c["name"] in diff_names
		if f == "prop":
			return c["name"] in (props or {})
		return True

	def next_filter(self):
		"""Zolty: lista filtrow z liczba kanalow."""
		if not self.mapa:
			return
		chans = self.mapa.get("channels", [])
		dn = set(d["name"] for d in (self.diff or []))
		opts = []
		for i, (k, t) in enumerate(FILTERS):
			if k == "diff" and self.diff is None:
				continue
			n = len([1 for c in chans if self.passes(c, k, dn, self.props)])
			if k == "diff":
				t = "rozni sie od %s" % ("serwera" if self.local else "dekodera")
			opts.append(("%s%s  (%d)" % ("* " if i == self.filter else "   ", t, n), i))
		self.session.openWithCallback(self.filter_chosen, ChoiceBox, title="Pokaz kanaly", list=opts)

	def filter_chosen(self, c):
		if not c:
			return
		self.filter = c[1]
		self.show_keys()
		self.fill()

	# --- Menu: sortowanie, Info/EPG: synchronizacja ---
	def sort_menu(self):
		"""Menu: sortowanie listy i pobieranie programu (na serwerze / na dekoderze / gotowy plik)."""
		cur = cfg.map_sort.value
		opts = [(("* " if k == cur else "   ") + "Sortowanie: " + t, k) for k, t in (("bq", "zgodnie z bukietami"), ("abc", "alfabetycznie"))]
		opts.append(("   Pobieranie programu...", "grab"))
		self.session.openWithCallback(self.sort_chosen, ChoiceBox, title="Dopasowanie kanalow", list=opts)

	def sort_chosen(self, c):
		if c and c[1] == "grab":
			self.grab_menu()
			return
		if not c or c[1] == cfg.map_sort.value:
			return
		from .common import save_cfg
		cfg.map_sort.value = c[1]
		cfg.map_sort.save()
		save_cfg()
		if self.mapa:
			self.fill()

	# --- propozycje zmian recznych wskazan (przy kazdym wczytaniu mapy) ---
	def props_start(self):
		if self.pbg.busy() or not self.mapa:
			return
		from .sync import proposals
		mapa, st, side = self.mapa, self.stacje, "dekoder" if self.local else "serwer"
		self.pbg.run(lambda: proposals(mapa, st, side), self.props_done)

	def props_done(self, data, err):
		if err:
			log("Propozycje zmian: %s" % err)
		self.props = data or {}
		if self.mapa:
			self.fill()

	# --- roznice z druga mapa i synchronizacja (Info/EPG; ustawienie "Roznice w dopasowaniu") ---
	def other_base(self):
		from .sync import server_base
		return server_base() if self.local else self.base

	def diff_start(self):
		base = self.other_base()
		if not base or self.sbg.busy():
			return
		from .sync import diffs
		local, mine = self.local, self.mapa

		def job():
			from .sync import pins_both
			if local:
				srv = http_get_json(base + "mapa.json")
				return diffs(mine, srv, *pins_both(base))
			return diffs(local_map_job()[0], mine, *pins_both(base))
		self.sbg.run(job, self.diff_done)

	def diff_done(self, data, err):
		if err or data is None:
			log("Dopasowanie kanalow - porownanie z %s: %s" % ("serwerem" if self.local else "dekoderem", err))
			self.diff = None
			return
		self.diff = data
		if FILTERS[self.filter][0] == "diff":
			self.fill()
		self.show_info()
		if getattr(self, "diff_want", False):          # Info/EPG nacisniete przed koncem porownania
			self.diff_want = False
			self.diff_asked = True
			self.sync_menu()
			return
		if self.diff and not self.diff_asked:
			self.diff_asked = True                 # raz na otwarcie okna
			pol = cfg.map_diff.value
			if pol in ("dekoder", "serwer"):
				self.sync_apply(pol, self.diff, auto=True)
			elif pol == "ask":
				self.sync_menu(first=True)

	def sync_menu(self, first=False):
		if not self.other_base():
			self.session.open(MessageBox, "Brak serwera epgpl - nie ma z czym porownac (zrodlo XMLTV z serwera: Ustawienia -> "
				"Program z internetu).", MessageBox.TYPE_INFO, timeout=6)
			return
		if self.diff is None:
			self["info"].setText("Porownywanie z %s..." % ("serwerem" if self.local else "dekoderem"))
			self.diff_want = True
			self.diff_start()
			return
		n = len(self.diff)
		if not n:
			self.session.openWithCallback(self.sync_chosen, ChoiceBox, title="Dopasowanie na dekoderze i na serwerze jest takie samo",
				list=[("Porownaj ponownie", "again")])
			return
		opts = [("Pokaz roznice (%d)" % n, "show"),
			("Synchronizuj: wygrywa dekoder (serwer dostaje wskazania dekodera)", "dekoder"),
			("Synchronizuj: wygrywa serwer (dekoder dostaje wskazania serwera)", "serwer"),
			("Porownaj ponownie", "again")]
		if first:
			opts.append(("Zostaw rozne", None))
		self.session.openWithCallback(self.sync_chosen, ChoiceBox, title="Dopasowanie rozni sie od %s w %d kanalach" % (
			"serwera" if self.local else "dekodera", n), list=opts)

	def sync_chosen(self, c):
		if not c or not c[1]:
			return
		if c[1] == "show":
			self.diff_list()
		elif c[1] == "again":
			self.diff = None
			self.sync_menu()
		else:
			self.sync_apply(c[1], self.diff)

	def diff_list(self):
		names = dict((t, self.site_name(t)) for t in ("tm", "onet", "naz", "wp", "cp"))
		self.session.openWithCallback(self.diff_done_list, EPGScanDiffs, self.diff, names, self.local)

	def diff_done_list(self, res=None):
		"""Z okna roznic: (zwyciezca, [roznice]) albo None."""
		if res:
			self.sync_apply(res[0], res[1])
		else:
			self.sync_menu()                 # exit - powrot do menu synchronizacji

	def sync_apply(self, winner, items, auto=False):
		from .sync import plan, apply_local, apply_server
		for_dek, for_srv = plan(items, winner)
		if not for_dek and not for_srv:
			return
		ch = dict(for_srv if winner == "dekoder" else for_dek)
		base = self.other_base() if self.local else self.base
		self["info"].setText("Synchronizacja (wygrywa %s): %d wskazan..." % (winner, len(for_dek) + len(for_srv)))

		def job():
			res = {"ok": True, "applied": 0, "errors": []}
			if for_srv:
				r = apply_server(base, for_srv)
				res["applied"] += r.get("applied", 0)
				res["errors"] += r.get("errors") or []
			if for_dek:
				r = apply_local(for_dek)
				res["applied"] += r.get("applied", 0)
			return res

		def done(data, err):
			errs = (data or {}).get("errors") or []
			log("Synchronizacja dopasowania (wygrywa %s%s): %s, bledow %d" % (winner, ", automatycznie" if auto else "",
				", ".join("%s=%s" % (k, v or "auto") for k, v in ch.items()), len(errs)))
			msg = "Synchronizacja: wygrywa %s - zmienionych wskazan: %d." % (winner, (data or {}).get("applied", 0))
			if err:
				msg = "Synchronizacja nieudana: %s" % err
			elif errs:
				msg += "\nNie przeniesione (%d):\n%s" % (len(errs), "\n".join("%s - %s" % (e["name"], e["error"]) for e in errs[:8]))
			if auto:
				msg += "\n(ustawienie: Roznice w dopasowaniu)"
			self.session.open(MessageBox, msg, MessageBox.TYPE_ERROR if err else MessageBox.TYPE_INFO, timeout=8)
			self.diff = None
			# mapa serwera przeliczana w tle - odswiezenie po chwili
			self.reload_timer = eTimer()
			self.reload_timer.callback.append(self.load)
			self.reload_timer.start(3000, True)
		self.sbg.run(job, done)

	# --- zmiana ---
	def all_stations(self):
		out = []
		for tag in (self.mapa or {}).get("sites", []):
			for sid, name in self.stacje.get(tag, []):
				out.append((tag, sid, name))
		return out

	def entry(self, tag, sid, name):
		return ("%s: %s" % (self.site_name(tag), name), "%s:%s" % (tag, sid))

	def change(self):
		c = self.current()
		if not c or not self.mapa:
			return
		self.chan = c
		from .station import EPGScanStation
		names = dict((t, self.site_name(t)) for t in ("tm", "onet", "naz", "wp", "cp"))
		ch = dict(c)
		if c["name"] in self.props:
			ch["propose"] = self.props[c["name"]]
		self.session.openWithCallback(self.station_done, EPGScanStation, ch, self.all_stations(), names,
			self.mapa.get("sites", []), self.base, find_ref(c["name"], c.get("bq")), "dekoder" if self.local else "serwer")

	def station_done(self, target):
		if target is None:
			if self.chan.get("name") in self.props:   # mogla zostac pominieta (Menu w oknie wyboru)
				self.props_start()
			return
		c = self.chan
		if target == "%s:%s" % (c.get("tag"), c.get("site_id")) and c.get("manual"):
			return                                   # to samo, juz wskazane recznie
		self.send(target)

	def chosen(self, choice):
		if not choice:
			return
		if choice[1] == "search":
			from Screens.VirtualKeyBoard import VirtualKeyBoard
			self.session.openWithCallback(self.search, VirtualKeyBoard, title="Szukaj stacji (fragment nazwy)", text="")
			return
		if choice[1] == "zap":
			self.zapper.zap(self.chan_ref)
			self.change()
			return
		if choice[1] == "preview_cur":
			c = self.chan
			self.preview("%s:%s" % (c["tag"], c["site_id"]), "%s: %s" % (self.site_name(c["tag"]), c["sname"]), False)
			return
		if choice[1] in ("-", ""):
			self.send(choice[1])
			return
		self.pick = choice
		self.session.openWithCallback(self.pick_action, ChoiceBox, title="%s -> %s" % (self.chan["name"], choice[0]), list=[
			("Zapisz" if self.local else "Zapisz na serwerze", "save"),
			("Najpierw podglad programu", "preview"),
			("Anuluj", None)])

	def pick_action(self, ans):
		if not ans or not ans[1]:
			return
		if ans[1] == "save":
			self.send(self.pick[1])
		else:
			self.preview(self.pick[1], self.pick[0], True)

	def preview(self, target, label, can_save):
		self.prev_target = target
		self.session.openWithCallback(self.preview_done, EPGScanPreview, self.base, target, label, self.chan["name"], can_save,
			self.zapper, getattr(self, "chan_ref", None))
		# self.base = None (mapa z dekodera) -> podglad pobierany przez dekoder

	def preview_done(self, save=False):
		if save:
			self.send(self.prev_target)

	# --- pobieranie na serwerze ---
	def grab_menu(self):
		if self.grab.active:
			self.session.open(MessageBox, self.grab.text + "\n\nPo zakonczeniu pojawi sie powiadomienie (okno mozna zamknac).",
				MessageBox.TYPE_INFO, timeout=8)
			return
		sat = cfg.sat_import.value
		opts = []
		if not self.local:                         # pobieranie na serwerze i gotowy plik - tylko w dopasowaniu serwera
			opts += [(t, m) for m, t in GRAB_MODES if m != "wait" and (sat or m not in SAT_MODES)]
			opts += [(t, m) for m, t in READY_MODES if sat or m not in SAT_MODES]
		opts.append(("Dekoder: pobierz program teraz (bez importu)", "lonly"))
		opts.append(("Dekoder: pobierz program i importuj XMLTV (bez czyszczenia EPG)", "lx"))
		if not self.local:
			opts.append(("Test: pobierz na dekoderze i porownaj z serwerem", "localtest"))
		self.session.openWithCallback(self.grab_chosen, ChoiceBox,
			title="Pobieranie programu na dekoderze" if self.local else "Program z serwera: pobranie na serwerze (ok. 10 min) albo gotowy plik", list=opts)

	def grab_chosen(self, ans):
		if not ans:
			return self.sort_menu()          # exit - powrot do Menu
		self.grab_mode = ans[1]
		if ans[1] in ("lonly", "lx"):
			from .localrun import local_job
			from .engine import get_engine
			j = local_job()
			if j.active:
				self.session.open(MessageBox, j.text, MessageBox.TYPE_INFO, timeout=6)
				return
			j.start("recznie z dopasowania kanalow")
			msg = "Dekoder pobiera program (ok. 5-10 min). Postep na ekranie glownym EPG Scan."
			if ans[1] == "lx":
				ok = get_engine(self.session).start_xmltv_only(clear=False, local=False)
				msg += "\nPotem import XMLTV%s." % ("" if ok else " - NIE ruszyl (trwa skan albo brak zrodel)")
			self.session.open(MessageBox, msg, MessageBox.TYPE_INFO, timeout=6)
			return
		if ans[1] == "localtest":
			if not self.mapa:
				return
			self.session.open(EPGScanLocalTest, self.base, self.mapa)
			return
		if ans[1] in CLEAR_MODES:
			self.session.openWithCallback(self.grab_confirm, MessageBox,
				"%sCale EPG zostanie wyczyszczone przed %s.\nKontynuowac?" % (
					"" if ans[1].startswith("r") else "Po pobraniu na serwerze: ",
					"importem XMLTV - do nastepnego skanu bedzie tylko program z XMLTV" if "x" in ans[1] else "skanem satelity"),
				MessageBox.TYPE_YESNO, default=False)
		else:
			self.grab_confirm(True)

	def grab_confirm(self, yes):
		if not yes:
			return
		if self.grab_mode.startswith("r"):
			ok, what = start_import(self.session, self.grab_mode)
			log("Gotowy plik z serwera %s: %s" % (self.desc, what))
			self.session.open(MessageBox, "Gotowy plik z serwera: %s.\nPostep na ekranie glownym EPG Scan." % what,
				MessageBox.TYPE_INFO if ok else MessageBox.TYPE_ERROR, timeout=6)
			return
		self.grab.start(self.session, self.base, self.grab_mode, self.desc)

	def search(self, text):
		if not text or not text.strip():
			return
		q = norm(text)
		low = text.strip().lower()
		found = [(t, s, n) for t, s, n in self.all_stations() if q in norm(n) or low in n.lower()]
		if not found:
			self.session.open(MessageBox, "Nie znaleziono stacji: %s" % text, MessageBox.TYPE_INFO, timeout=4)
			return
		self.session.openWithCallback(self.chosen, ChoiceBox, title="Wyniki: %s" % text,
			list=[self.entry(t, s, n) for t, s, n in found[:60]])

	def send(self, target):
		name = self.chan["name"]
		self["info"].setText("Zapisywanie%s: %s ..." % ("" if self.local else " na serwerze", name))

		def job():
			if self.local:
				return local_pin(name, target)
			return http_post_json(self.base + "api/alias", {"name": name, "target": target})
		self.target = target
		self.run(job, self.sent)

	def sent(self, data, err):
		if err or not data or not data.get("ok"):
			msg = (data or {}).get("error") or err or "brak odpowiedzi"
			self.session.open(MessageBox, "Serwer nie przyjal zmiany:\n%s" % msg, MessageBox.TYPE_ERROR)
			self.show_info()
			return
		log("Dopasowanie kanalow (%s): %s -> %s" % ("dekoder" if self.local else "serwer", self.chan["name"], self.target or "automatycznie"))
		self.session.open(MessageBox, "Zapisano: %s -> %s\nProgram z nowego zrodla bedzie po nastepnym %s." % (
			self.chan["name"], self.target or "automatycznie", "pobraniu i imporcie" if self.local else "pobraniu na serwerze"),
			MessageBox.TYPE_INFO, timeout=5)
		if self.local:
			self.load()
			return
		self.reload_timer = eTimer()
		self.reload_timer.callback.append(self.load)
		self.reload_timer.start(3000, True)      # serwer przelicza mape w tle


from .common import scale_skins as _scale_skins  # noqa: E402
_scale_skins(globals())
