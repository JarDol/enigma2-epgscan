# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - pobieranie programu na dekoderze w tle (LocalJob) i zrodlo XMLTV "EPG PL (dekoder)".
# Mapa kanalow (ktory kanal bierze ktora stacje) i mapa referencji (pl.channels.xml.xz) - z serwera epgpl,
# z kopia na dysku: gdy serwer nie odpowiada, dekoder pobiera wg ostatniej kopii.

import json
import os
import threading
import time
import urllib.request

from enigma import eTimer

from .common import cfg, log, backup_dir
from .localgrab import LocalGrab, PARSERS, write_xmltv
from .i18n import _

SRC_FILE = "/etc/enigma2/epgscan_sources/wlasne/dekoder.sources.xml"
DESC = "EPG PL (dekoder)"


def out_dir():
	d = backup_dir() + "lokalne/"
	if not os.path.isdir(d):
		os.makedirs(d)
	return d


def local_file_ok():
	"""Plik programu z dekodera istnieje i jest mlodszy niz 30 h."""
	try:
		f = out_dir() + "pl.xml.xz"
		return os.path.exists(f) and time.time() - os.path.getmtime(f) < 30 * 3600 and os.path.exists(SRC_FILE)
	except Exception:
		return False


def write_sources(d):
	if not os.path.isdir(os.path.dirname(SRC_FILE)):
		os.makedirs(os.path.dirname(SRC_FILE))
	tmp = SRC_FILE + ".tmp"
	with open(tmp, "w") as f:
		f.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- EPG Scan: program pobierany przez dekoder (Ustawienia -> '
			'XMLTV -> Pobieranie programu na dekoderze) -->\n<sources>\n\t<mappings>\n'
			'\t\t<channel name="dekoder.channels.xml.xz">\n\t\t\t<url>%spl.channels.xml.xz</url>\n\t\t</channel>\n'
			'\t</mappings>\n\t<sourcecat sourcecatname="EPG PL">\n'
			'\t\t<source type="gen_xmltv" nocheck="1" channels="dekoder.channels.xml.xz">\n'
			'\t\t\t<description>%s</description>\n\t\t\t<url>%spl.xml.xz</url>\n\t\t</source>\n'
			'\t</sourcecat>\n</sources>\n' % (d, DESC, d))
	os.rename(tmp, SRC_FILE)


def get(url, dest):
	req = urllib.request.Request(url, headers={"User-Agent": "EPGScan"})
	data = urllib.request.urlopen(req, timeout=60).read()
	with open(dest + ".tmp", "wb") as f:
		f.write(data)
	os.rename(dest + ".tmp", dest)


class LocalJob(object):
	"""Jedno pobieranie naraz; postep w self.text, koniec -> log i self.listeners."""

	def __init__(self):
		self.active = False
		self.last_ok = None          # wynik ostatniej proby (None = nie bylo w tej sesji)
		self.text = ""
		self.grab = None
		self.listeners = []
		self.msgs = []
		self.timer = eTimer()
		self.timer.callback.append(self.poll)

	def changed(self):
		for f in self.listeners[:]:
			try:
				f()
			except Exception:
				pass

	def cancel(self):
		"""Przerwanie pobierania (czerwony na ekranie glownym). Plik programu nie jest podmieniany."""
		if not self.active:
			return False
		self.cancelled = True
		if self.grab is not None:
			self.grab.cancel = True
		self.text = _("Pobieranie programu na dekoderze: przerywanie...")
		log("Dekoder: pobieranie przerwane przez uzytkownika")
		self.changed()
		return True

	def start(self, reason="", scheduled=False):
		"""scheduled=True: "pobieranie w jednym miejscu" - najpierw dzisiejsza pelna paczka serwera (harmonogram albo
		Akcje -> Pobierz swieze dane: w jednym miejscu); False - zawsze wlasne pobieranie."""
		if self.active:
			if getattr(self, "cancelled", False):
				# poprzednie (przerwane) jeszcze sie konczy - nowe ruszy zaraz po nim
				self.restart = reason or "ponownie"
				return True
			return False
		self.cancelled = False
		self.restart = None
		self.from_server = False
		self.scheduled = scheduled
		self.stat = None
		self.beat = 0
		try:
			from .mapping import epgpl_servers
			srv = epgpl_servers()
		except Exception:
			srv = []
		base = srv[0][0] if srv else ""
		self.active = True
		self.ok = False
		self.grab = None
		self.t0 = time.time()
		self.text = _("Pobieranie programu na dekoderze: przygotowanie...")
		self.msgs = []
		log("Dekoder: pobieranie programu%s" % ((" (%s)" % reason) if reason else ""))
		days = int(cfg.local_days.value) + 1

		def work():
			try:
				d = out_dir()
				if scheduled and base:
					got = self.take_server_package(base, d)
					if got:
						self.msgs.append(got)
						self.from_server = True
						self.ok = True
						return
					announce(base, time.time() + 20 * 60)      # serwer: dekoder pobiera - harmonogram serwera czeka
				local_map = cfg.map_mode.value == "dekoder"
				if local_map:
					from .localmap import refresh_stations, build
					st_msgs = []
					stations = refresh_stations(d, st_msgs)
					self.msgs += st_msgs
					_, nst, head = build(d, stations)
					self.msgs.append("mapa z dekodera: %s" % head[2] if len(head) > 2 else "mapa z dekodera zbudowana")
				elif base:
					try:
						get(base + "mapa.json", d + "mapa.json")
						get(base + "pl.channels.xml.xz", d + "pl.channels.xml.xz")
					except Exception as e:
						self.msgs.append("serwer %s nie odpowiada (%s) - mapa kanalow z kopii" % (base, e))
				if not os.path.exists(d + "mapa.json") or not os.path.exists(d + "pl.channels.xml.xz"):
					raise Exception("brak mapy kanalow - potrzebny choc raz serwer epgpl (zrodlo XMLTV z <epgpl>)")
				mapa = json.load(open(d + "mapa.json"))
				names, st = {}, []
				for c in mapa.get("channels", []):
					tag, sid = c.get("tag"), c.get("site_id")
					if tag in PARSERS and sid:
						import re
						xid = "%s.%s" % (tag, re.sub(r"[^A-Za-z0-9-]", "_", sid))
						if xid not in names:
							names[xid] = c.get("sname") or c.get("name") or sid
							st.append((tag, sid, xid, names[xid]))
				from .common import local_conns
				self.grab = LocalGrab(st, days=days, conns=local_conns(), max_total=int(cfg.local_conn_max.value),
					pause=float(cfg.local_pause.value), errlog=(d + "bledy.txt") if cfg.local_errlog.value else None)
				if self.cancelled:
					raise Exception("przerwane przez uzytkownika - plik programu nie podmieniony")
				self.grab.run()
				if self.cancelled:
					raise Exception("przerwane przez uzytkownika - plik programu nie podmieniony")
				n = sum(len(v) for v in self.grab.result.values())
				if n < 100:
					raise Exception("za malo audycji (%d) - plik nie podmieniony" % n)
				if local_map:
					from .localmap import update_empty
					miss = update_empty(d, [s[2] for s in st], self.grab.result)
					if miss:
						self.msgs.append("bez danych %d stacji (przy nastepnej mapie kolejny serwis): %s" % (
							len(miss), ", ".join(names.get(x, x) for x in miss[:10])))
				write_xmltv(d + "pl.xml.xz", self.grab.result, names)
				write_sources(d)
				took = int(time.time() - self.t0)
				if self.grab.errors and cfg.local_errlog.value:
					self.msgs.append("szczegoly bledow: %sbledy.txt" % d)
				self.msgs.append("gotowe w %d:%02d: audycji %d dla %d stacji, stron %d (bez danych w serwisie %d), ponowionych %d, "
					"bledow %d (%s)" % (took // 60, took % 60, n, len(self.grab.result), self.grab.total, self.grab.missing,
					self.grab.retried, self.grab.errors, self.grab.progress()))
				if getattr(self.grab, "slowed", None):
					log("Pobieranie na dekoderze: serwis ograniczal zapytania (429/503) - przerwa wydluzona: %s" % ", ".join(
						"%s %.0f s" % (t, v) for t, v in sorted(self.grab.slowed.items())))
				self.ok = True
				self.stat = {"days": int(cfg.local_days.value), "channels": len(self.grab.result), "total": len(st)}
			except Exception as e:
				self.msgs.append("blad: %s" % e)
			if base and scheduled and not self.from_server:
				announce(base, 0)
		self.th = threading.Thread(target=work)
		self.th.daemon = True
		self.th.start()
		self.timer.start(2000, False)
		self.changed()
		return True

	def take_server_package(self, base, d):
		"""Pobieranie w jednym miejscu: gdy serwer pobral dzis pelny program - jego paczka zamiast wlasnego pobierania.
		Gdy serwer wlasnie pobiera - czekanie (najwyzej 90 min). Zwraca opis albo None (pobieranie na dekoderze)."""
		from .mapping import http_get_json
		t0 = time.time()
		st = {}
		while not self.cancelled and time.time() - t0 < 90 * 60:
			try:
				st = http_get_json(base + "api/stan", timeout=15)
			except Exception as e:
				self.msgs.append("pobieranie w jednym miejscu: serwer %s nie odpowiada (%s) - pobiera dekoder" % (base, e))
				return None
			if not st.get("running"):
				break
			self.text = _("Pobieranie programu na dekoderze: serwer wlasnie pobiera - czekam na jego paczke (%d min)") % (
				(time.time() - t0) // 60)
			time.sleep(30)
		full = st.get("full") or {}
		why = full_ok(full)
		if why:
			self.msgs.append("pobieranie w jednym miejscu: paczka serwera nie pasuje (%s) - pobiera dekoder" % why)
			return None
		try:
			get(base + "pl.xml.xz", d + "pl.xml.xz.srv")
			get(base + "pl.channels.xml.xz", d + "pl.channels.xml.xz.srv")
		except Exception as e:
			self.msgs.append("pobieranie w jednym miejscu: paczki serwera nie udalo sie pobrac (%s) - pobiera dekoder" % e)
			return None
		os.rename(d + "pl.channels.xml.xz.srv", d + "pl.channels.xml.xz")
		os.rename(d + "pl.xml.xz.srv", d + "pl.xml.xz")
		cand = ""
		try:
			get(base + "kandydaci.xml.xz", d + "kandydaci.xml.xz")
			cand = ", z programem kandydatow"
		except Exception:
			pass
		write_sources(d)
		return "program z serwera %s (pobrany %s, %s dni, kanalow %s/%s%s) - wlasne pobieranie pominiete (pobieranie w jednym miejscu)" % (
			base, time.strftime("%H:%M", time.localtime(full["time"])), full.get("days"), full.get("channels"), full.get("total"), cand)

	def poll(self):
		el = int(time.time() - self.t0)
		if self.th.is_alive():
			g = self.grab
			if getattr(self, "cancelled", False):
				self.text = _("Pobieranie programu na dekoderze: przerywanie (%d:%02d)...") % (el // 60, el % 60)
			elif g and g.total:
				self.text = _("Pobieranie programu na dekoderze (%d:%02d): %s") % (el // 60, el % 60, g.progress(localized=True))
				if getattr(self, "scheduled", False) and cfg.one_place.value and el - getattr(self, "beat", 0) > 600:
					self.beat = el                     # co 10 min: serwer nadal ma czekac
					announce_bg(time.time() + 20 * 60)
			else:
				self.text = _("Pobieranie programu na dekoderze (%d:%02d): mapa kanalow...") % (el // 60, el % 60)
			self.changed()
			return
		self.timer.stop()
		for m in self.msgs:
			log("Dekoder: %s" % m)
		self.text = _("Program na dekoderze: %s") % (self.msgs[-1] if self.msgs else "?")
		self.last_ok = bool(self.ok)
		try:
			cfg.last_fetch.value = "%d|%s" % (int(time.time()), (self.msgs[-1] if self.msgs else "?")[:200])
			cfg.last_fetch.save()
		except Exception:
			pass
		self.active = False
		if self.ok and not self.from_server and cfg.map_mode.value == "dekoder" and cfg.cand_night.value:
			cand_night()
		if self.ok and not self.from_server and cfg.one_place.value and getattr(self, "stat", None):
			upload_package(self.stat)
		if getattr(self, "restart", None):
			r, self.restart = self.restart, None
			self.start(r)            # start po przerwanym - wywoluje changed()
			return
		self.changed()


CAND_TH = None


def midnight():
	t = time.localtime()
	return time.mktime((t.tm_year, t.tm_mon, t.tm_mday, 0, 0, 0, 0, 0, -1))


def full_ok(full):
	"""Czy paczka (znacznik pelnego pobrania) nadaje sie do wziecia: "" = tak, inaczej powod."""
	if not full.get("time"):
		return "brak znacznika pelnego pobrania (serwer starszy niz 0.18.0 albo jeszcze nie pobieral)"
	if full["time"] < midnight():
		return "ostatnie pelne pobranie %s - nie z dzisiaj" % time.strftime("%d.%m %H:%M", time.localtime(full["time"]))
	if full.get("days", 0) < int(cfg.local_days.value):
		return "program na %s dni, dekoder chce %s" % (full.get("days"), cfg.local_days.value)
	if full.get("total") and float(full.get("channels", 0)) / full["total"] < 0.9:
		return "dane tylko dla %s z %s stacji" % (full.get("channels"), full["total"])
	return ""


def announce(base, until):
	"""Serwer: dekoder pobiera do 'until' (0 = koniec) - harmonogram serwera czeka zamiast pobierac rownolegle."""
	try:
		from .mapping import http_post_json
		http_post_json(base + "api/zajety", {"until": int(until)})
	except Exception:
		pass


def announce_bg(until):
	def work():
		try:
			from .mapping import epgpl_servers
			srv = epgpl_servers()
			if srv:
				announce(srv[0][0], until)
		except Exception:
			pass
	th = threading.Thread(target=work)
	th.daemon = True
	th.start()


def post_file(url, path):
	data = open(path, "rb").read()
	req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/octet-stream", "User-Agent": "EPGScan"})
	r = json.loads(urllib.request.urlopen(req, timeout=120).read().decode("utf-8"))
	if not r.get("ok"):
		raise Exception(r.get("error") or "serwer nie przyjal pliku")


def upload_package(stat):
	"""Pobieranie w jednym miejscu: dekoder byl pierwszy - paczka na serwer (po programie kandydatow, gdy liczony),
	chyba ze serwer ma juz dzisiejszy pelny program."""
	def work():
		try:
			from .mapping import epgpl_servers, http_get_json, http_post_json
			srv = epgpl_servers()
			if not srv:
				return
			base = srv[0][0]
			full = (http_get_json(base + "api/stan", timeout=15) or {}).get("full")
			if full is None:
				log("Dekoder: paczka nie wyslana - serwer %s starszy niz 0.18.0" % base)
				return
			d = out_dir()
			mine = os.path.getmtime(d + "pl.xml.xz")
			if full.get("time", 0) >= midnight() and (full.get("source") != "dekoder" or full["time"] >= mine):
				# serwer pobral dzis sam albo ma juz te (albo nowsza) paczke dekodera
				log("Dekoder: paczka nie wyslana - serwer ma juz dzisiejszy program (%s, %s)" % (
					full.get("source"), time.strftime("%H:%M", time.localtime(full["time"]))))
				return
			t0 = time.time()
			while CAND_TH is not None and CAND_TH.is_alive() and time.time() - t0 < 3 * 3600:
				time.sleep(10)                        # najpierw program kandydatow (jedzie w paczce)
			files = ["pl.xml.xz", "pl.channels.xml.xz"]
			if os.path.exists(d + "kandydaci.xml.xz") and os.path.getmtime(d + "kandydaci.xml.xz") >= os.path.getmtime(d + "pl.xml.xz"):
				files.append("kandydaci.xml.xz")
			for f in files:
				post_file(base + "api/paczka?plik=" + f, d + f)
			r = http_post_json(base + "api/paczka?gotowe=1", stat)
			if not r.get("ok"):
				raise Exception(r.get("error") or "serwer nie przyjal paczki")
			log("Dekoder: paczka wyslana na serwer %s (%s) - serwer nie pobiera dzis sam (pobieranie w jednym miejscu)" % (
				base, ", ".join(files)))
		except Exception as e:
			log("Dekoder: wyslanie paczki na serwer nieudane: %s" % e)
	th = threading.Thread(target=work)
	th.daemon = True
	th.start()


def cand_night():
	"""Po udanym pobraniu (mapa z dekodera): program na dobe dla kandydatow z okna wyboru stacji
	-> lokalne/kandydaci.xml.xz (tylko podglad, nie trafia do EPG). W tle, nic na niego nie czeka."""
	global CAND_TH
	if CAND_TH is not None and CAND_TH.is_alive():
		return
	d = out_dir()

	def work():
		try:
			if os.path.exists(d + "kandydaci.xml.xz") and os.path.getmtime(d + "kandydaci.xml.xz") > os.path.getmtime(d + "pl.xml.xz"):
				return
			t0 = time.time()
			from .station import candidate_stations, local_grab, xid_of
			mapa = json.load(open(d + "mapa.json"))
			stacje = json.load(open(d + "stacje.json"))
			st = candidate_stations(mapa, stacje)
			if not st:
				return
			log("Dekoder: kandydaci do okna wyboru stacji: %d stacji - pobieranie programu na dobe" % len(st))
			res, g = local_grab(st, 2, 1)
			names = dict((xid_of(t, s), n) for t, s, n in st)
			result = {}
			for k, pr in res.items():
				t, s = k.split(":", 1)
				result[xid_of(t, s)] = [(p["start"], p["stop"], p["title"], p["desc"]) for p in pr]
			write_xmltv(d + "kandydaci.xml.xz", result, names)
			log("Dekoder: kandydaci gotowe w %d min: program dla %d z %d stacji, bledow %d" % (
				(time.time() - t0) // 60, len(result), len(st), g.errors))
		except Exception as e:
			log("Dekoder: kandydaci - blad: %s" % e)
	CAND_TH = threading.Thread(target=work)
	CAND_TH.daemon = True
	CAND_TH.start()


JOB = None


def local_job():
	global JOB
	if JOB is None:
		JOB = LocalJob()
	return JOB
