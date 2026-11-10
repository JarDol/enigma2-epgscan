# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - harmonogram: start o godzinie / co N godzin, wybudzanie z glebokiego czuwania

import os
import time

from enigma import eTimer
import NavigationInstance

from .common import cfg, log, save_cfg, is_due, sched_active, next_run, fmt_ts, WAKEUP_FILE, topup_candidates, topup_label, TOPUP_INFO
from .engine import get_engine

CHECK_EVERY = 60    # co ile sekund sprawdzac, czy pora na skan
WAKE_TOL = 600      # start w tylu sekundach od zaplanowanego wybudzenia = wybudzenie przez wtyczke

_sched = None


def in_standby():
	try:
		import Screens.Standby
		return bool(Screens.Standby.inStandby)
	except Exception:
		return False


def was_timer_wakeup():
	try:
		from Tools.StbHardware import getFPWasTimerWakeup
		return bool(getFPWasTimerWakeup())
	except Exception:
		return None


def read_wakeup():
	try:
		with open(WAKEUP_FILE) as f:
			return int(f.read().strip() or 0)
	except Exception:
		return 0


def wakeup_time():
	"""Wywolywane przez Enigma2 przy przejsciu w gleboki sen: kiedy wybudzic odbiornik."""
	if not sched_active() or not cfg.sched_wakeup.value:
		_write_wakeup(0)
		return -1
	ts = next_run()
	if not ts:
		_write_wakeup(0)
		return -1
	ts -= 120   # 2 min na start odbiornika
	_write_wakeup(ts)
	return ts


def _write_wakeup(ts):
	try:
		with open(WAKEUP_FILE, "w") as f:
			f.write(str(int(ts)))
	except Exception:
		pass


class Scheduler(object):
	def __init__(self, session):
		self.session = session
		self.woke = False
		self.t_boot = time.time()
		self.resume = bool(cfg.auto_in_progress.value)   # skan z harmonogramu przerwany restartem
		if self.resume:
			log("Poprzedni skan z harmonogramu nie zostal dokonczony (restart odbiornika) - zostanie powtorzony")
		self.waiting = ""            # powod oczekiwania (do logu, bez powtarzania)
		self.timer = eTimer()
		self.timer.callback.append(self.check)
		self.first = True
		self.timer.start(CHECK_EVERY * 1000, False)

	def detect_wake(self):
		ts = read_wakeup()
		if not ts or not cfg.sched_wakeup.value:
			return False
		fp = was_timer_wakeup()
		near = abs(time.time() - ts) < WAKE_TOL
		return near and fp is not False

	def wait(self, why):
		if why != self.waiting:
			log("Harmonogram: skan czeka - %s" % why)
			self.waiting = why

	def check(self):
		if self.first:
			self.first = False
			self.report()
			self.woke = self.detect_wake()
			_write_wakeup(0)
			if self.woke:
				log("Odbiornik wybudzony przez harmonogram EPG Scan")
				if not in_standby():
					try:
						from Tools import Notifications
						import Screens.Standby
						Notifications.AddNotification(Screens.Standby.Standby)
					except Exception:
						pass
		if self.resume and time.time() - self.t_boot < 240:
			return       # po starcie najpierw kontrola/odtworzenie EPG, potem powtorka skanu
		if not sched_active() and self.resume:
			self.resume = False
			cfg.auto_in_progress.value = False
			save_cfg()
		if not sched_active() or not (is_due() or self.resume):
			self.waiting = ""
			self.check_topup()
			return
		eng = get_engine(self.session)
		if eng.running:
			return
		if cfg.sched_when.value == "standby" and not in_standby() and not self.woke:
			return self.wait("odbiornik nie jest w czuwaniu")
		try:
			nav = NavigationInstance.instance
			if cfg.sched_skip_rec.value and nav.RecordTimer.isRecording():
				return self.wait("trwa nagrywanie")
		except Exception:
			pass
		self.waiting = ""
		if self.resume:
			log("Harmonogram: powtorka przerwanego skanu")
			self.resume = False
		else:
			cfg.last_auto_run.value = int(time.time())
		save_cfg()
		if eng.start(auto=True, woke=self.woke):
			log("Harmonogram: skan uruchomiony, nastepny: %s" % fmt_ts(next_run()))
			self.report()
		else:
			log("Harmonogram: brak transponderow do skanowania")
		self.woke = False


	def report(self):
		"""Harmonogram dekodera dla serwera epgpl (opcja pobierania na serwerze)."""
		try:
			from .mapping import report_schedule
			report_schedule()
		except Exception as e:
			log("Zgloszenie harmonogramu do serwera epgpl nieudane: %s" % e)

	def fixed_due(self, now):
		"""Doczytywanie o stalej godzinie: dzisiejsza pora minela (do 2 h temu) i jeszcze go nie bylo."""
		if not cfg.topup_fixed.value:
			return 0
		h, m = cfg.topup_fixed_time.value
		t = time.localtime(now)
		slot = int(time.mktime((t.tm_year, t.tm_mon, t.tm_mday, h, m, 0, 0, 0, -1)))
		if slot <= now < slot + 7200 and cfg.last_topup_fixed.value < slot:
			return slot
		return 0

	def check_topup(self):
		"""Doczytywanie brakow: co N godzin (i opcjonalnie o stalej godzinie) krotki skan transponderow z koncowka EPG."""
		if not cfg.topup.value or not cfg.sat_import.value:
			return
		now = time.time()
		if now - self.t_boot < 300:
			return
		fixed = self.fixed_due(now)
		if not fixed and now - cfg.last_topup.value < cfg.topup_every.value * 3600:
			return
		eng = get_engine(self.session)
		if eng.running:
			return
		if cfg.topup_when.value == "standby" and not in_standby():
			return
		if sched_active():
			nr = next_run()
			if nr and nr - now < 1800:
				return       # pelny skan z harmonogramu za mniej niz 30 min
		try:
			if cfg.sched_skip_rec.value and NavigationInstance.instance.RecordTimer.isRecording():
				return
		except Exception:
			pass
		cfg.last_topup.value = int(now)
		if fixed:
			cfg.last_topup_fixed.value = int(now)
			log("Aktualizacja satelitarna o stalej godzinie %02d:%02d - bez odstepow" %
				tuple(cfg.topup_fixed_time.value))
		save_cfg()
		eng.prepare()
		if cfg.topup_mode.value == "all":
			jobs = eng.jobs
			for job in jobs:
				job.topup_reason = "cykliczna aktualizacja"
			log("Aktualizacja satelitarna: wszystkie %d transponderow, niezaleznie od zasiegu EPG" % len(jobs))
		else:
			jobs = topup_candidates(eng.jobs, force=bool(fixed))
		if not jobs:
			if cfg.topup_mode.value == "all":
				log("Aktualizacja satelitarna: brak transponderow do skanowania")
				return
			parts = []
			if TOPUP_INFO["recent"]:
				parts.append("%d skanowane w ciagu ostatnich %d h" % (TOPUP_INFO["recent"], cfg.topup_every.value))
			if TOPUP_INFO["backoff"]:
				parts.append("%d z wydluzonym odstepem (ostatnio bez zysku)" % TOPUP_INFO["backoff"])
			if parts:
				log("Doczytywanie: transpondery z EPG krotszym niz %s pominiete: %s" % (topup_label(), ", ".join(parts)))
			else:
				log("Doczytywanie: wszystkie kanaly maja EPG na co najmniej %s - nic do zrobienia" % topup_label())
			return
		if cfg.topup_mode.value == "short" and (TOPUP_INFO["recent"] or TOPUP_INFO["backoff"]):
			log("Doczytywanie: pominiete teraz - %d niedawno skanowane, %d z wydluzonym odstepem" % (
				TOPUP_INFO["recent"], TOPUP_INFO["backoff"]))
		eng.start(topup_jobs=jobs)


def start_scheduler(session):
	global _sched
	if _sched is None:
		_sched = Scheduler(session)
	return _sched
