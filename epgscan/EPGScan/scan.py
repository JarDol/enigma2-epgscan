# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - ekran podgladu (skan dziala w tle, Exit nie przerywa)

import time

from Screens.Screen import Screen
from .common import EPGChoiceBox as ChoiceBox
from Screens.MessageBox import MessageBox
from Components.ActionMap import ActionMap
from Components.Label import Label
from Components.ScrollLabel import ScrollLabel

from .common import eit_on, cfg, VERSION, log_path, log, epgrefresh_active, list_backups, restore_backup, backup_label, new_bouquets, \
	next_run, fmt_ts, clears_now, keys_skin, hints_skin, init_hints, init_keys, set_keys, set_key_color, KEY_COLORS, KEY_EXIT, epg_stats, stats_text, all_refs, log_dups, export_dups
from .engine import get_engine


class EPGScanScreen(Screen):
	skin = """
	<screen position="center,center" size="1600,920" title="EPG Scan">
		<widget name="status" position="20,10" size="1560,150" font="Regular;30" />
		<widget name="list" position="20,170" size="1560,600" font="Regular;30" />
		%s
		%s
	</screen>""" % (hints_skin(20, 780, 1560), keys_skin(20, 845, 1560))

	def __init__(self, session):
		Screen.__init__(self, session)
		self.setTitle("EPG Scan %s" % VERSION)
		self.engine = get_engine(session)
		try:
			from .common import migrate_v2
			migrate_v2()
		except Exception:
			pass
		self["status"] = Label("")
		self["list"] = ScrollLabel("")
		init_hints(self)
		init_keys(self)
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions", "DirectionActions", "MenuActions", "EPGSelectActions"], {
			"ok": self.start,
			"green": self.actions,
			"red": self.red,
			"cancel": self.close,
			"yellow": self.backups,
			"blue": self.setup,
			"menu": self.channels,
			"info": self.info,
			"up": self["list"].pageUp,
			"down": self["list"].pageDown,
		}, -1)
		self.engine.listeners.append(self.redraw)
		try:
			from .mapping import server_grab
			self.grab = server_grab()
			self.grab.listeners.append(self.redraw)
		except Exception:
			self.grab = None
		try:
			from .localrun import local_job
			self.ljob = local_job()
			self.ljob.listeners.append(self.redraw)
		except Exception:
			self.ljob = None
		self.sw_cb = lambda *a: self.redraw()
		try:
			from .satwatch.sw import runner as _swr
			_swr.listeners.append(self.sw_cb)
		except Exception:
			pass
		self.onClose.append(self.cleanup)
		self.onLayoutFinish.append(self.first)

	def first(self):
		if not self.engine.running and not self.engine.finished:
			self["status"].setText("Przygotowanie listy kanalow...")
			self.engine.prepare()
		self.redraw()

	def cleanup(self):
		try:
			from .satwatch.sw import runner as _swr
			_swr.listeners.remove(self.sw_cb)
		except Exception:
			pass
		try:
			self.engine.listeners.remove(self.redraw)
		except Exception:
			pass
		try:
			self.grab.listeners.remove(self.redraw)
		except Exception:
			pass
		try:
			self.ljob.listeners.remove(self.redraw)
		except Exception:
			pass

	def sw_scanning(self):
		try:
			from .satwatch.sw import runner as _swr
			return _swr.scanner is not None or bool(_swr.busy)
		except Exception:
			return False

	def sw_status(self):
		try:
			from .satwatch.sw import runner as _swr
			return _swr.status
		except Exception:
			return ""

	def grab_active(self):
		return bool(self.grab and self.grab.active and not self.engine.running)

	# --- klawisze ---
	def actions(self):
		if self.engine.running:
			return
		from .menus import EPGScanActions
		self.session.openWithCallback(self.actions_cb, EPGScanActions)

	def actions_cb(self, profile=None):
		if profile == "channelparams":
			self.start_channel_params()
			return
		if profile:
			self.start(profile)

	def start_channel_params(self):
		if self.engine.running:
			self.session.open(MessageBox, "Inne zadanie EPG Scan juz trwa. Skan parametrow nie moze go przerwac.",
				MessageBox.TYPE_INFO, timeout=5)
			return
		if self.grab_active():
			self.session.open(MessageBox, "Trwa pobieranie programu uruchomione z dekodera. Zakoncz je przed "
				"uruchomieniem skanu parametrow.", MessageBox.TYPE_INFO, timeout=8)
			return
		from .channelparamscan import start_manual
		try:
			started = start_manual(self.engine)
		except Exception as e:
			log("Nie mozna uruchomic skanu parametrow: %s" % e)
			self.session.open(MessageBox, "Nie mozna uruchomic skanu parametrow: %s" % e,
				MessageBox.TYPE_ERROR, timeout=8)
			return
		if not started:
			self.session.open(MessageBox, "Brak kanalow DVB w wybranych bukietach albo skaner jest zajety.",
				MessageBox.TYPE_INFO, timeout=6)
			return
		self.redraw()

	def channels(self):
		from .menus import channels_menu
		channels_menu(self.session)

	def backups(self):
		if self.engine.running:
			return self.busy()
		from .menus import backups_menu
		backups_menu(self.session, self.engine, self.restore)

	def start(self, profile=None):
		e = self.engine
		if e.running:
			return
		if self.grab_active():
			self.session.open(MessageBox, "Trwa pobieranie programu na serwerze uruchomione z dekodera.\nPo zakonczeniu: %s.\n\n"
				"Czerwony - przerwanie czekania." % self.grab.plan(), MessageBox.TYPE_INFO, timeout=8)
			return
		if e.finished:
			e.prepare()       # nowa lista - wykryje zmiany w bukietach
		if not e.start(profile=profile):
			self.session.open(MessageBox, "Nic do zrobienia - sprawdz profil (zielony) i ustawienia.", MessageBox.TYPE_INFO, timeout=5)
		self.redraw()

	def red(self):
		e = self.engine
		if self.grab_active():
			self.session.openWithCallback(self.grab_stop_cb, MessageBox,
				"Przerwac czekanie na serwer?\n\nPobieranie na serwerze bedzie trwalo dalej (jego plik dostaniesz przy "
				"nastepnym imporcie), ale dekoder nie uruchomi teraz: %s." % self.grab.plan(), MessageBox.TYPE_YESNO, default=False)
			return
		if not e.running:
			if self.ljob and self.ljob.active and not getattr(self.ljob, "cancelled", False):
				self.session.openWithCallback(lambda yes: yes and self.ljob.cancel(), MessageBox,
					"Przerwac pobieranie programu na dekoderze?\n\nPlik programu nie zostanie podmieniony - "
					"zostaje poprzedni.", MessageBox.TYPE_YESNO, default=False)
				return
			self.close()
			return
		if e.phase == "xmltv":
			q = "Zatrzymac import XMLTV?\n\nDane z satelity zostaja. Kanaly, ktore mialy dostac program z XMLTV, " \
				"zostana bez niego (lub bez dalszych dni) do nastepnego odswiezania."
		else:
			q = "Zatrzymac skanowanie EPG?"
			if self.ljob and self.ljob.active:
				q += "\n\nPobieranie programu na dekoderze tez zostanie przerwane (plik programu zostaje poprzedni)."
			mode = cfg.clear_mode.value
			if self.engine.topup:
				q += "\n\nTo doczytywanie brakow - dotychczasowe EPG zostaje."
			elif clears_now(self.engine.auto):
				q += "\n\nUWAGA: na poczatku skanu EPG zostalo wyczyszczone. Po przerwaniu EPG bedzie niepelne - " \
					"tylko to, co zebrano do tej pory."
			elif mode == "scanned":
				q += "\n\nUWAGA: kanaly ze zeskanowanych juz transponderow maja nowe EPG, " \
					"ale reszta pozostanie bez odswiezenia."
			else:
				q += "\n\nDotychczasowe EPG zostaje - zabraknie tylko odswiezenia pozostalych transponderow."
		if cfg.last_summary.value:
			q += "\n\nOstatnie pelne odswiezenie: %s" % cfg.last_summary.value
		files = list_backups()
		if files:
			q += "\nNajnowsza kopia EPG: %s (zolty przycisk = odtworzenie)" % backup_label(files[0])
		self.session.openWithCallback(self.stop_cb, MessageBox, q, MessageBox.TYPE_YESNO, default=False)

	def grab_stop_cb(self, yes):
		if yes:
			self.grab.cancel()

	def stop_cb(self, yes):
		if yes:
			self.engine.stop()

	def busy(self):
		self.session.open(MessageBox, "Najpierw zatrzymaj skanowanie (czerwony).", MessageBox.TYPE_INFO, timeout=4)

	def menu_key(self, sel=0):
		"""MENU: Ustawienia i Kanaly; w trakcie skanu / importu / pobierania tylko Kanaly (ustawien nie zmienia sie w trakcie)."""
		busy = self.engine.running or self.grab_active()
		opts = []
		if not busy:
			opts.append(("Ustawienia (satelita, program z internetu, harmonogram, kopie, SatWatch)", "setup"))
		opts.append(("Kanaly - SatWatch, dopasowanie kanalow, raport mapy", "channels"))
		title = "EPG Scan - menu" + ("  (ustawienia po zakonczeniu pracy)" if busy else "")

		def chosen(c):
			if not c:
				return
			if c[1] == "setup":
				self.setup()
			else:
				from .menus import channels_menu
				channels_menu(self.session)
		from .common import EPGChoiceBox as _CB
		self.session.openWithCallback(chosen, _CB, title=title, list=opts)

	def setup(self):
		if self.engine.running or self.grab_active():
			self.close()
			return
		from .menus import settings_menu
		settings_menu(self.session, self.after_setup)

	def after_setup(self, *args):
		self.engine.prepare()
		self.redraw()

	def restore(self):
		if self.engine.running:
			return self.busy()
		if self.grab_active():
			return self.start()          # komunikat o pobieraniu na serwerze
		files = list_backups()
		if not files:
			self.session.open(MessageBox, "Brak kopii EPG w katalogu %s" % cfg.backup_dir.value, MessageBox.TYPE_INFO, timeout=5)
			return
		self.session.openWithCallback(self.restore_cb, ChoiceBox, title="Odtworz EPG z kopii:",
			list=[(backup_label(f), f) for f in files])

	def restore_cb(self, choice):
		if choice:
			ok = restore_backup(choice[1])
			self.session.open(MessageBox, "EPG odtworzone z kopii." if ok else "Odtworzenie nieudane, szczegoly w %s" % log_path(),
				MessageBox.TYPE_INFO if ok else MessageBox.TYPE_ERROR, timeout=5)

	def info(self):
		"""Raporty: statystyka EPG, log, pobieranie na dekoderze, porownanie, raport serwera."""
		from .menus import reports_menu
		reports_menu(self.session, self.engine)

	# --- widok ---
	def redraw(self):
		e = self.engine
		done, err, el = e.stats()
		tun = "tunery wolne %d/%d, rezerwa %d" % (e.free_tuners(), e.total_tuners, e.reserve)
		nch = sum(len(j.refs) for j in e.jobs)
		nall = sum(len(j.all_refs) for j in e.jobs)
		nskip = len([j for j in e.jobs if j.skip])
		sk = (", pominietych %d" % nskip) if nskip else ""
		if self.ljob and self.ljob.active and not e.running and not self.grab_active():
			head = self.ljob.text
		elif self.grab_active():
			head = "%s\nPo zakonczeniu: %s" % (self.grab.text, self.grab.plan())
		elif not e.running and self.sw_scanning():
			head = "SatWatch: %s" % self.sw_status()
		elif e.running and e.phase == "xmltv" and e.xmltv:
			if getattr(e, "xmltv_only", False):
				head = "Import XMLTV: %s   czas %d:%02d\nSatelita: bez skanu (sam import XMLTV)" % (
					e.xmltv.status, el // 60, el % 60)
			else:
				head = "Uzupelnianie z XMLTV: %s   czas %d:%02d\nSkan z satelity: %s" % (
					e.xmltv.status, el // 60, el % 60, e.summary)
		elif e.running and e.phase == "satwatch":
			try:
				from .satwatch.sw import runner
				st = runner.status
			except Exception:
				st = ""
			head = "SatWatch (przed skanem EPG, czas %d:%02d): %s" % (int(time.time() - e.t_start) // 60,
				int(time.time() - e.t_start) % 60, st)
		elif e.running and e.phase == "channelparams":
			scan = getattr(e, "channel_params_scan", None)
			active = len(scan.active) if scan else 0
			reserved = len(scan.reservations) if scan else 0
			queued = len(scan.queue) if scan else 0
			head = "Skan PAT/PMT kanalow: aktywne %d, rezerwacje TP %d, w kolejce %d, czas %d:%02d   %s" % (
				active, reserved, queued, el // 60, el % 60, tun)
		elif not e.jobs:
			head = "Brak kanalow - sprawdz zakres skanowania i bukiety w ustawieniach"
		elif e.running and e.phase == "xmltv_wait":
			try:
				from .mapping import server_grab
				st = server_grab().text
			except Exception:
				st = ""
			head = "Czekam na pobranie programu przed importem XMLTV:\n%s" % e.wait_text()
		elif e.running and e.phase == "xmltv_install":
			head = "Instalowanie pakietu list zrodel XMLTV (opkg)...\nSkan z satelity: %s" % e.summary
		elif e.running and e.topup:
			head = "Doczytywanie brakow: %d/%d gotowe, aktywne %d, czas %d:%02d   %s" % (
				done, len(e.jobs), len(e.active), el // 60, el % 60, tun)
		elif e.running:
			head = "Skanowanie%s: %d/%d gotowe, aktywne %d, bledy %d%s, czas %d:%02d   %s" % (
				" (harmonogram)" if e.auto else "", done, len(e.jobs) - nskip, len(e.active), err, sk, el // 60, el % 60, tun)
		elif e.finished and getattr(e, "xmltv_only", False):
			head = "Zakonczono %s, czas %d:%02d" % (e.summary, el // 60, el % 60)
		elif e.running and getattr(e, "xmltv_only", False):
			head = "Import XMLTV: przygotowanie..."
		elif e.finished:
			head = "Zakonczono: %d/%d gotowe, %d bledow%s, czas %d:%02d" % (done, len(e.jobs) - nskip, err, sk, el // 60, el % 60)
		else:
			head = "Transponderow: %d%s (kanalow w bukietach %d, wszystkich %d)   limit: %d (ustawienia)   %s" % (
				len(e.jobs), sk, nch, nall, e.max_parallel, tun)
		info = []
		if e.note:
			info.append(e.note)
		if not e.running and cfg.xmltv.value:
			from .xmltv import sources_present
			if not sources_present():
				info.append("UWAGA: brak list zrodel XMLTV - zostana doinstalowane przy skanie (lub w ustawieniach)")

		if not e.running:
			if e.changes:
				info.append(e.changes)
			nb = new_bouquets()
			if nb:
				info.append("Nowe bukiety (nie skanowane): %s - dodaj w ustawieniach" % ", ".join(b[1] for b in nb))
			if not cfg.sat_import.value:
				info.append("Profil bez satelity - start i harmonogram robia sam import XMLTV%s" % (
					"" if cfg.xmltv.value else " (UWAGA: program z internetu wylaczony)"))
			elif eit_on() is False:
				info.append("EIT wylaczone w ustawieniach EPG - skan %s" % (
					"wlaczy je na stale" if cfg.eit_mode.value == "perm" else "wlaczy je na czas pracy"))
			try:
				from .menus import satwatch_installed
				if satwatch_installed():
					info.append("UWAGA: osobna wtyczka SatWatch jest zainstalowana - SatWatch jest juz czescia EPG Scan, odinstaluj ja")
			except Exception:
				pass
			if epgrefresh_active():
				info.append("UWAGA: wlaczony harmonogram EPG Refresh - obie wtyczki beda zajmowac tunery")

		self["status"].setText(head + "\n" + "\n".join(info[:3]))
		if self.grab_active():
			set_key_color(self, "blue", KEY_COLORS[3][1])
			set_keys(self, red="Przerwij czekanie", green="", yellow="", blue="Exit: dziala w tle")
		elif e.running:
			# niebieskie pole w trakcie skanu sluzy jako "Exit" (niebieski tez zamyka okno) - kolor zostaje niebieski
			set_key_color(self, "blue", KEY_COLORS[3][1])
			set_keys(self, red="Zatrzymaj import" if e.phase == "xmltv" else "Zatrzymaj skan",
				blue="Exit: dziala w tle")
		elif self.ljob and self.ljob.active and not getattr(self.ljob, "cancelled", False):
			set_key_color(self, "blue", KEY_COLORS[3][1])
			set_keys(self, red="Przerwij pobieranie", green="Akcje", yellow="Kopie EPG", blue="Ustawienia")
		else:
			set_key_color(self, "blue", KEY_COLORS[3][1])
			set_keys(self, red="Wyjscie", green="Akcje", yellow="Kopie EPG", blue="Ustawienia")
		now = time.time()
		lines = []
		if e.running and e.phase == "channelparams":
			scan = getattr(e, "channel_params_scan", None)
			if scan:
				lines += ["AKTYWNE: %s - %s" % (j.name, j.state) for j in scan.active]
				lines += ["REZERWACJA TP: [%s] %s" % (j.tuner, j.name) for j in scan.reservations]
				lines += ["OCZEKUJE: %s - %s" % (j.name, j.state) for j in scan.queue]
		else:
			for j in e.active + [x for x in e.jobs if x not in e.active]:
				sh = " wspoldz." if j.shared else ""
				cov = "EPG %d/%d, wszystkie %d/%d" % (j.covered, len(j.refs), j.all_covered, len(j.all_refs))
				if j in e.active:
					t = int(now - j.t0)
					txt = "[%s%s] %s %ds  zdarzen %d (bylo %d, %+d)  %s" % (
						j.tuner, sh, j.state, t, j.events, j.events_start, j.events - j.events_start, cov)
				elif j.state.startswith("gotowe"):
					txt = "[%s%s] %s %ds  zdarzen %d (bylo %d, %+d)  %s" % (
						j.tuner, sh, j.state, j.duration, j.events, j.events_start, j.events - j.events_start, cov)
				else:
					txt = "%s  (kanalow %d, wszystkich %d)" % (j.state, len(j.refs), len(j.all_refs))
				lines.append("%s  -  %s" % (j.name, txt))
		if getattr(e, "xmltv_only", False):
			lines = ["Sam import XMLTV (bez skanu satelity) - szczegoly w Raportach (Info/EPG) i w logu."]
		if not e.running:
			lines = self.stage_lines() + [""] + lines
		self["list"].setText("\n".join(lines))

	def stage_lines(self):
		"""Stan etapow (ekran glowny bez pracy w tle)."""
		from .common import profile_default, profile_text, sched_active

		def when(v):
			try:
				ts, txt = v.split("|", 1)
				return "%s  %s" % (time.strftime("%d.%m %H:%M", time.localtime(int(ts))), txt)
			except Exception:
				return v or "-"
		out = ["SatWatch:   %s" % when(cfg.last_sw.value),
			"Satelita:   %s" % (cfg.last_summary.value or "-"),
			"Pobranie:   %s" % when(cfg.last_fetch.value),
			"Import:     %s" % when(cfg.last_import.value),
			"Profil:     %s%s" % (profile_text(profile_default()), "" if cfg.p_fetch.value else " (bez pobierania)"),
			"Harmonogram: %s" % (("nastepny start %s" % fmt_ts(next_run())) if sched_active() else "wylaczony")]
		return out


from .common import scale_skins as _scale_skins  # noqa: E402
_scale_skins(globals())
