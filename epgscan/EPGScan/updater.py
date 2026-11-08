# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - aktualizacja wtyczki z wydan GitHub (JarDol/enigma2-epgscan, tagi epgscan-X.Y.Z)
#
# Recznie (Ustawienia -> "Sprawdz aktualizacje teraz") - dziala zawsze, niezaleznie od autoaktualizacji.
# Automatycznie (opcja) - raz na dobe sprawdza wydania; nowa wersja jest instalowana, a Enigma2
# restartowana dopiero w czuwaniu, gdy nic nie nagrywa i nie trwa praca wtyczki.

import json
import os
import shutil
import time
import zipfile

from enigma import eTimer

from .common import cfg, log, save_cfg, VERSION

API = "https://api.github.com/repos/JarDol/enigma2-epgscan/releases?per_page=50"
PLUGIN_DIR = os.path.dirname(os.path.abspath(__file__))
TMP_ZIP = "/tmp/epgscan-update.zip"
TMP_DIR = "/tmp/epgscan-update"
BACKUP_DIR = "/tmp/EPGScan.poprzednia"

_auto = None


def _vt(v):
	return tuple(int(x) if x.isdigit() else 0 for x in v.split("."))


def newer(a, b):
	return _vt(a) > _vt(b)


def latest():
	"""(wersja, adres ZIP) najnowszego wydania wtyczki albo None."""
	try:
		from urllib.request import Request, urlopen
		req = Request(API, headers={"User-Agent": "EPGScan/%s" % VERSION, "Accept": "application/vnd.github+json"})
		data = json.loads(urlopen(req, timeout=20).read().decode("utf-8"))
	except Exception as e:
		log("Aktualizacja: brak odpowiedzi GitHub (%s)" % e)
		return None
	best = None
	for r in data:
		tag = r.get("tag_name", "")
		if r.get("draft") or r.get("prerelease") or not tag.startswith("epgscan-"):
			continue
		ver = tag[len("epgscan-"):]
		for a in r.get("assets", []):
			if a.get("name") == "epgscan-%s.zip" % ver:
				if best is None or newer(ver, best[0]):
					best = (ver, a.get("browser_download_url"))
	return best


def install(url, ver):
	"""Pobiera ZIP i podmienia katalog wtyczki (kopia poprzedniej w /tmp). Zwraca komunikat bledu albo None."""
	try:
		from urllib.request import Request, urlopen
		req = Request(url, headers={"User-Agent": "EPGScan/%s" % VERSION})
		with open(TMP_ZIP, "wb") as f:
			f.write(urlopen(req, timeout=120).read())
		with zipfile.ZipFile(TMP_ZIP) as z:
			names = z.namelist()
			if "EPGScan/plugin.py" not in names or "EPGScan/common.py" not in names:
				return "nieprawidlowa paczka"
			shutil.rmtree(TMP_DIR, ignore_errors=True)
			z.extractall(TMP_DIR)
		src = os.path.join(TMP_DIR, "EPGScan")
		shutil.rmtree(BACKUP_DIR, ignore_errors=True)
		shutil.copytree(PLUGIN_DIR, BACKUP_DIR, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
		# podmiana plikow (katalog wtyczki zostaje - Enigma2 ma go otwartego)
		for root, dirs, files in os.walk(src):
			rel = os.path.relpath(root, src)
			dst = os.path.join(PLUGIN_DIR, rel) if rel != "." else PLUGIN_DIR
			if not os.path.isdir(dst):
				os.makedirs(dst)
			for fn in files:
				shutil.copy2(os.path.join(root, fn), os.path.join(dst, fn))
		shutil.rmtree(os.path.join(PLUGIN_DIR, "__pycache__"), ignore_errors=True)
		shutil.rmtree(TMP_DIR, ignore_errors=True)
		try:
			os.remove(TMP_ZIP)
		except Exception:
			pass
		log("Aktualizacja: zainstalowano EPG Scan %s (poprzednia wersja %s w %s) - zadziala po restarcie Enigma2"
			% (ver, VERSION, BACKUP_DIR))
		return None
	except Exception as e:
		log("Aktualizacja: instalacja nieudana: %s" % e)
		return str(e)


def check_async(callback):
	"""callback(info) na watku glownym; info = (wersja, url) albo None."""
	from twisted.internet import threads
	d = threads.deferToThread(latest)
	d.addCallback(callback)
	d.addErrback(lambda f: callback(None))


def install_async(url, ver, callback):
	from twisted.internet import threads
	d = threads.deferToThread(install, url, ver)

	def done(err):
		if err is None:
			cfg.upd_pending.value = ver
			cfg.upd_pending.save()
			save_cfg()
		callback(err)
	d.addCallback(done)
	d.addErrback(lambda f: callback(str(f.value)))


def restart_gui(session):
	import Screens.Standby
	session.open(Screens.Standby.TryQuitMainloop, 3)


# ---------------------------------------------------------------- automatycznie

def _quiet(session):
	"""Czuwanie, brak nagrania (teraz i za 15 min), wtyczka nie pracuje."""
	try:
		import Screens.Standby
		if not Screens.Standby.inStandby:
			return False
		import NavigationInstance
		nav = NavigationInstance.instance
		nxt = nav.RecordTimer.getNextRecordingTime()
		if nav.getRecordings() or (nxt > 0 and nxt - time.time() < 900):
			return False
		from .engine import get_engine
		if get_engine().running:
			return False
	except Exception:
		return False
	return True


class AutoUpdate(object):
	CHECK_EVERY = 3600   # s - co godzine sprawdza, czy pora (raz na dobe) i czy mozna zrestartowac

	def __init__(self, session):
		self.session = session
		self.busy = False
		self.timer = eTimer()
		self.timer.callback.append(self.tick)
		self.timer.start(10 * 60 * 1000, True)   # pierwsze sprawdzenie 10 min po starcie

	def tick(self):
		try:
			self.run()
		finally:
			self.timer.start(self.CHECK_EVERY * 1000, True)

	def run(self):
		# zainstalowana nowa wersja czeka na restart
		if cfg.upd_pending.value and cfg.upd_pending.value != VERSION:
			if _quiet(self.session):
				log("Aktualizacja: restart Enigma2 (nowa wersja %s)" % cfg.upd_pending.value)
				restart_gui(self.session)
			return
		if cfg.upd_pending.value == VERSION:
			cfg.upd_pending.value = ""
			cfg.upd_pending.save()
		if not cfg.upd_auto.value or self.busy:
			return
		if time.time() - cfg.upd_last.value < 20 * 3600:
			return
		self.busy = True
		check_async(self.checked)

	def checked(self, info):
		cfg.upd_last.value = int(time.time())
		cfg.upd_last.save()
		save_cfg()
		if not info or not newer(info[0], VERSION):
			self.busy = False
			if info:
				log("Aktualizacja: wtyczka aktualna (%s)" % VERSION)
			return
		log("Aktualizacja: dostepna wersja %s - instaluje" % info[0])
		install_async(info[1], info[0], self.installed)

	def installed(self, err):
		self.busy = False
		if err is None:
			self.run()   # restart, jesli odbiornik juz jest w czuwaniu


def start(session):
	global _auto
	if _auto is None:
		_auto = AutoUpdate(session)
