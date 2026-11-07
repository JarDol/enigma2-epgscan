# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - restart tylko wtedy, gdy potrzebny (po nocnym przebiegu) i podsumowanie nocy po wlaczeniu odbiornika.

import time

from enigma import eTimer

from .common import cfg, log, save_cfg

_rss_base = [0]


def mem_available_mb():
	try:
		for ln in open("/proc/meminfo"):
			if ln.startswith("MemAvailable:"):
				return int(ln.split()[1]) // 1024
	except Exception:
		pass
	return 0


def own_rss_mb():
	"""Pamiec procesu Enigma2 (wtyczka dziala w jego wnetrzu)."""
	try:
		for ln in open("/proc/self/status"):
			if ln.startswith("VmRSS:"):
				return int(ln.split()[1]) // 1024
	except Exception:
		pass
	return 0


def uptime_days():
	try:
		return float(open("/proc/uptime").read().split()[0]) / 86400.0
	except Exception:
		return 0.0


def remember_rss():
	_rss_base[0] = own_rss_mb()
	log("Pamiec Enigma2 po starcie: %d MB, wolna w systemie: %d MB" % (_rss_base[0], mem_available_mb()))


def add_night_note(text):
	"""Wpis do podsumowania nocy (okienko po wlaczeniu odbiornika)."""
	old = cfg.night_note.value
	new = (old + " | " if old else "") + text
	cfg.night_note.value = new[-600:]
	cfg.night_note.save()
	save_cfg()


def restart_reason(engine):
	"""(rodzaj, powod) albo None. rodzaj: "gui" = restart Enigma2, "reboot" = pelny restart odbiornika."""
	mode = cfg.rs_mode.value
	if mode == "never":
		return None
	if time.time() - cfg.last_restart.value < 20 * 3600:
		return None
	if mode == "always":
		return ("reboot", "restart po kazdym nocnym przebiegu (ustawienie)")
	bad = [j for j in getattr(engine, "jobs", []) if str(getattr(j, "state", "")).startswith("BLAD")]
	if len(bad) >= 3:
		return ("reboot", "tuner nie stroi: %d transponderow z bledem (%s)" % (len(bad), ", ".join(j.name for j in bad[:4])))
	info = getattr(engine, "sw_info", None) or {}
	if info.get("scanned") and info.get("failed", 0) >= 3 and info["failed"] * 2 >= info["scanned"]:
		return ("reboot", "SatWatch: %d z %d transponderow bez sygnalu" % (info["failed"], info["scanned"]))
	mem = mem_available_mb()
	if mem and mem < int(cfg.rs_mem.value):
		return ("gui", "malo wolnej pamieci: %d MB (prog %s MB)" % (mem, cfg.rs_mem.value))
	rss = own_rss_mb()
	if _rss_base[0] and rss > _rss_base[0] + 250:
		return ("gui", "Enigma2 zajmuje %d MB (po starcie %d MB)" % (rss, _rss_base[0]))
	if getattr(engine, "need_restart_gui", ""):
		return ("gui", engine.need_restart_gui)
	days = int(cfg.rs_days.value)
	if days and uptime_days() >= days:
		return ("reboot", "profilaktycznie - odbiornik dziala %.1f dnia (prog %d)" % (uptime_days(), days))
	return None


def maybe_restart(engine):
	"""Po nocnym przebiegu: restart, gdy potrzebny i bezpieczny. True = restart uruchomiony."""
	try:
		r = restart_reason(engine)
		if not r:
			return False
		kind, why = r
		import Screens.Standby
		import NavigationInstance
		if not Screens.Standby.inStandby:
			log("Restart potrzebny (%s), ale odbiornik jest w uzyciu - pominiety" % why)
			return False
		nav = NavigationInstance.instance
		if nav.getRecordings():
			log("Restart potrzebny (%s), ale trwa nagrywanie - pominiety" % why)
			return False
		nxt = nav.RecordTimer.getNextRecordingTime()
		if nxt > 0 and nxt - time.time() < 900:
			log("Restart potrzebny (%s), ale nagranie za mniej niz 15 min - pominiety" % why)
			return False
		cfg.last_restart.value = int(time.time())
		cfg.last_restart.save()
		save_cfg()
		what = "restart Enigma2" if kind == "gui" else "pelny restart odbiornika"
		log("%s: %s" % (what[0].upper() + what[1:], why))
		add_night_note("%s - %s" % (what, why))
		engine.session.open(Screens.Standby.TryQuitMainloop, 3 if kind == "gui" else 2)
		return True
	except Exception as e:
		log("Restart: %s" % e)
		return False


class NightNotice(object):
	"""Okienko z podsumowaniem nocy, gdy odbiornik jest uzywany od ok. minuty (krotkie wybudzenie nie wystarczy)."""

	def __init__(self):
		self.active = 0
		self.timer = eTimer()
		self.timer.callback.append(self.tick)
		self.timer.start(30000, False)
		self.rss_timer = eTimer()
		self.rss_timer.callback.append(remember_rss)
		self.rss_timer.start(300000, True)

	def tick(self):
		try:
			import Screens.Standby
			if Screens.Standby.inStandby:
				self.active = 0
				return
		except Exception:
			return
		self.active += 30
		if self.active < 60 or not cfg.night_note.value:
			return
		text = cfg.night_note.value
		cfg.night_note.value = ""
		cfg.night_note.save()
		save_cfg()
		try:
			from Tools import Notifications
			from Screens.MessageBox import MessageBox
			Notifications.AddPopup("EPG Scan - podsumowanie nocy:\n" + text.replace(" | ", "\n"), MessageBox.TYPE_INFO, 30,
				"EPGScanNight")
		except Exception:
			pass


_notice = None


def start(session):
	global _notice
	if _notice is None:
		_notice = NightNotice()


def tuner_hung(engine, t):
	"""Tuner nie stroi kilku transponderow z rzedu. W nocy (czuwanie): restart Enigma2, skan z harmonogramu
	powtorzy sie po starcie (najwyzej raz na noc). Recznie: pytanie o restart."""
	add_night_note("tuner %s nie stroil" % t)
	if getattr(engine, "auto", False):
		if cfg.rs_mode.value == "never":
			log("Tuner %s: restart wylaczony w ustawieniach - skan idzie dalej" % t)
			return
		if time.time() - cfg.hang_restart.value < 12 * 3600:
			log("Tuner %s: restart z tego powodu juz byl tej nocy - skan idzie dalej" % t)
			return
		try:
			import Screens.Standby
			import NavigationInstance
			if not Screens.Standby.inStandby:
				log("Tuner %s: odbiornik w uzyciu - bez restartu" % t)
				return
			nav = NavigationInstance.instance
			nxt = nav.RecordTimer.getNextRecordingTime()
			if nav.getRecordings() or (nxt > 0 and nxt - time.time() < 900):
				log("Tuner %s: nagrywanie teraz albo za chwile - bez restartu" % t)
				return
			now = int(time.time())
			cfg.hang_restart.value = now
			cfg.hang_restart.save()
			cfg.last_restart.value = now
			cfg.last_restart.save()
			save_cfg()            # auto_in_progress zostaje -> harmonogram powtorzy skan po starcie
			log("Restart Enigma2: tuner %s zawieszony - skan zostanie powtorzony po starcie" % t)
			add_night_note("restart Enigma2 (tuner %s) i powtorka skanu" % t)
			engine.session.open(Screens.Standby.TryQuitMainloop, 3)
		except Exception as e:
			log("Tuner %s: restart nieudany (%s)" % (t, e))
		return
	try:
		from Tools import Notifications
		from Screens.MessageBox import MessageBox

		def answer(yes):
			if yes:
				import Screens.Standby
				log("Restart Enigma2 na zyczenie uzytkownika (tuner %s zawieszony)" % t)
				engine.session.open(Screens.Standby.TryQuitMainloop, 3)
		Notifications.AddNotificationWithCallback(answer, MessageBox,
			"EPG Scan: tuner %s nie stroi kilku transponderow z rzedu - prawdopodobnie zawieszony.\n"
			"Zwykle pomaga restart Enigma2. Zrestartowac teraz?\n(skan trzeba bedzie potem uruchomic ponownie)" % t,
			MessageBox.TYPE_YESNO, timeout=60, default=False)
	except Exception as e:
		log("Tuner %s: %s" % (t, e))
