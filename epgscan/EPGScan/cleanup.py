# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - sprzatanie dysku: stare pliki timeshift
# (nieotwarte przez zaden proces), nadmiar enigma2_crash_*.log i stare zrzuty TS aplikacji E2Stream (stall-*.ts). Co N minut, wyniki w logu tylko gdy cos usunieto.

import glob
import os
import time

from enigma import eTimer

from .common import cfg, log


def open_files():
	"""Sciezki plikow otwartych przez dowolny proces (np. aktywny timeshift)."""
	out = set()
	for fd_dir in glob.glob("/proc/[0-9]*/fd"):
		try:
			for fd in os.listdir(fd_dir):
				try:
					out.add(os.readlink(os.path.join(fd_dir, fd)))
				except Exception:
					pass
		except Exception:
			pass
	return out


def clean_dirs():
	return [d.rstrip("/") or "/" for d in (cfg.cl_dir.value or "/media/hdd").split("|") if d.strip()]


def e2s_dirs():
	"""Katalogi aplikacji E2Stream na dekoderze: z ustawien odbiornikow (ftpDir) i typowe."""
	out = []
	try:
		from .clients import devices
		for dv in devices():
			v = ((dv.get("data") or {}).get("ftpDir") or {}).get("v")
			if v:
				out.append(v.rstrip("/"))
	except Exception:
		pass
	out += [os.path.join(d, "e2stream") for d in clean_dirs()] + ["/media/hdd/e2stream", "/media/usb/e2stream"]
	seen = []
	for d in out:
		if d and d not in seen and os.path.isdir(d):
			seen.append(d)
	return seen


def run_cleanup(dry=False):
	"""(liczba usunietych, MB, linie opisu) - wszystkie zaznaczone katalogi; crash logi: najnowsze lacznie."""
	lines, count, freed = [], 0, 0
	max_age = int(cfg.cl_ts_hours.value) * 3600
	now = time.time()
	busy = None
	crash = []
	for d in clean_dirs():
		if not os.path.isdir(d):
			lines.append("brak katalogu %s" % d)
			continue
		for f in sorted(glob.glob(os.path.join(d, "timeshift.*"))):
			try:
				if not os.path.isfile(f) or now - os.path.getmtime(f) < max_age:
					continue
				if busy is None:
					busy = open_files()
				base = os.path.join(d, ".".join(os.path.basename(f).split(".")[:2]))
				if any(p.startswith(base) for p in busy):
					lines.append("pominieto (w uzyciu) %s" % f)
					continue
				size = os.path.getsize(f)
				if not dry:
					os.remove(f)
				count += 1
				freed += size
				lines.append("usunieto %s (%d MB)" % (f, size // 1048576))
			except Exception as e:
				lines.append("blad %s: %s" % (f, e))
		crash += [f for f in glob.glob(os.path.join(d, "enigma2_crash_*.log")) if f not in crash]
	days = int(cfg.cl_e2s_days.value)
	if days > 0:
		for d in e2s_dirs():
			for f in sorted(glob.glob(os.path.join(d, "stall-*.ts"))):
				try:
					if now - os.path.getmtime(f) < days * 86400:
						continue
					size = os.path.getsize(f)
					if not dry:
						os.remove(f)
					count += 1
					freed += size
					lines.append("usunieto zrzut E2Stream %s (%d MB)" % (f, size // 1048576))
				except Exception as e:
					lines.append("blad %s: %s" % (f, e))
	keep = int(cfg.cl_crash_keep.value)
	crash.sort(key=lambda x: os.path.getmtime(x), reverse=True)
	for f in crash[keep:]:
		try:
			size = os.path.getsize(f)
			if not dry:
				os.remove(f)
			count += 1
			freed += size
			lines.append("usunieto %s" % f)
		except Exception as e:
			lines.append("blad %s: %s" % (f, e))
	return count, freed // 1048576, lines


class Cleaner(object):
	def __init__(self):
		self.timer = eTimer()
		self.timer.callback.append(self.tick)
		self.timer.start(5 * 60000, True)            # pierwszy raz 5 min po starcie

	def tick(self):
		if cfg.cl_on.value:
			try:
				n, mb, lines = run_cleanup()
				if n:
					log("Sprzatanie dysku: usunieto %d plikow (%d MB): %s" % (n, mb, "; ".join(lines)[:400]))
			except Exception as e:
				log("Sprzatanie dysku: %s" % e)
		self.timer.start(int(cfg.cl_every.value) * 60000, True)


_cleaner = None


def start():
	global _cleaner
	if _cleaner is None:
		_cleaner = Cleaner()
