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
from .i18n import _, display_engine_note, display_scan_summary
from .channelparamscan import display_state


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
			self["status"].setText(_("Przygotowanie listy kanalow..."))
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
		if profile:
			self.start(profile)

	def start_channel_params(self):
		if self.engine.running:
			self.session.open(MessageBox, _("Inne zadanie EPG Scan juz trwa. Skan parametrow nie moze go przerwac."),
				MessageBox.TYPE_INFO, timeout=5)
			return
		if self.grab_active():
			self.session.open(MessageBox, _("Trwa pobieranie programu uruchomione z dekodera. Zakoncz je przed "
				"uruchomieniem skanu parametrow."), MessageBox.TYPE_INFO, timeout=8)
			return
		from .channelparamscan import start_manual
		try:
			started = start_manual(self.engine)
		except Exception as e:
			log("Nie mozna uruchomic skanu parametrow: %s" % e)
			self.session.open(MessageBox, _("Nie mozna uruchomic skanu parametrow: %s") % e,
				MessageBox.TYPE_ERROR, timeout=8)
			return
		if not started:
			self.session.open(MessageBox, _("Brak kanalow DVB w wybranych bukietach albo skaner jest zajety."),
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
			self.session.open(MessageBox, _("Trwa pobieranie programu na serwerze uruchomione z dekodera.\nPo zakonczeniu: %s.\n\n"
				"Czerwony - przerwanie czekania.") % self.grab.plan(), MessageBox.TYPE_INFO, timeout=8)
			return
		if e.finished:
			e.prepare()       # nowa lista - wykryje zmiany w bukietach
		started = e.start(profile=profile)
		if not started and profile and profile.get("channelparams"):
			from .channelparamscan import start_manual
			try:
				started = start_manual(e, done=e.after_channelparams)
			except Exception as ex:
				log("Nie mozna uruchomic skanu parametrow: %s" % ex)
				self.session.open(MessageBox, _("Nie mozna uruchomic skanu parametrow: %s") % ex,
					MessageBox.TYPE_ERROR, timeout=8)
				return
		if not started:
			self.session.open(MessageBox, _("Nic do zrobienia - sprawdz profil (zielony) i ustawienia."), MessageBox.TYPE_INFO, timeout=5)
		self.redraw()

	def red(self):
		e = self.engine
		if self.grab_active():
			self.session.openWithCallback(self.grab_stop_cb, MessageBox,
				_("Przerwac czekanie na serwer?\n\nPobieranie na serwerze bedzie trwalo dalej (jego plik dostaniesz przy "
				"nastepnym imporcie), ale dekoder nie uruchomi teraz: %s.") % self.grab.plan(), MessageBox.TYPE_YESNO, default=False)
			return
		if not e.running:
			if self.ljob and self.ljob.active and not getattr(self.ljob, "cancelled", False):
				self.session.openWithCallback(lambda yes: yes and self.ljob.cancel(), MessageBox,
					_("Przerwac pobieranie programu na dekoderze?\n\nPlik programu nie zostanie podmieniony - "
					"zostaje poprzedni."), MessageBox.TYPE_YESNO, default=False)
				return
			self.close()
			return
		if e.phase == "xmltv":
			q = _("Zatrzymac import XMLTV?\n\nDane z satelity zostaja. Kanaly, ktore mialy dostac program z XMLTV, "
				"zostana bez niego (lub bez dalszych dni) do nastepnego odswiezania.")
		else:
			q = _("Zatrzymac skanowanie EPG?")
			if self.ljob and self.ljob.active:
				q += _("\n\nPobieranie programu na dekoderze tez zostanie przerwane (plik programu zostaje poprzedni).")
			mode = cfg.clear_mode.value
			if self.engine.topup:
				q += _("\n\nTo doczytywanie brakow - dotychczasowe EPG zostaje.")
			elif clears_now(self.engine.auto):
				q += _("\n\nUWAGA: na poczatku skanu EPG zostalo wyczyszczone. Po przerwaniu EPG bedzie niepelne - "
					"tylko to, co zebrano do tej pory.")
			elif mode == "scanned":
				q += _("\n\nUWAGA: kanaly ze zeskanowanych juz transponderow maja nowe EPG, "
					"ale reszta pozostanie bez odswiezenia.")
			else:
				q += _("\n\nDotychczasowe EPG zostaje - zabraknie tylko odswiezenia pozostalych transponderow.")
		if cfg.last_summary.value:
			q += _("\n\nOstatnie pelne odswiezenie: %s") % cfg.last_summary.value
		files = list_backups()
		if files:
			q += _("\nNajnowsza kopia EPG: %s (zolty przycisk = odtworzenie)") % backup_label(files[0])
		self.session.openWithCallback(self.stop_cb, MessageBox, q, MessageBox.TYPE_YESNO, default=False)

	def grab_stop_cb(self, yes):
		if yes:
			self.grab.cancel()

	def stop_cb(self, yes):
		if yes:
			self.engine.stop()

	def busy(self):
		self.session.open(MessageBox, _("Najpierw zatrzymaj skanowanie (czerwony)."), MessageBox.TYPE_INFO, timeout=4)

	def menu_key(self, sel=0):
		"""MENU: Ustawienia i Kanaly; w trakcie skanu / importu / pobierania tylko Kanaly (ustawien nie zmienia sie w trakcie)."""
		busy = self.engine.running or self.grab_active()
		opts = []
		if not busy:
			opts.append((_("Ustawienia (satelita, program z internetu, harmonogram, kopie, SatWatch)"), "setup"))
		opts.append((_("Kanaly - SatWatch, dopasowanie kanalow, raport mapy"), "channels"))
		title = _("EPG Scan - menu") + (_("  (ustawienia po zakonczeniu pracy)") if busy else "")

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
			self.session.open(MessageBox, _("Brak kopii EPG w katalogu %s") % cfg.backup_dir.value, MessageBox.TYPE_INFO, timeout=5)
			return
		self.session.openWithCallback(self.restore_cb, ChoiceBox, title=_("Odtworz EPG z kopii:"),
			list=[(backup_label(f), f) for f in files])

	def restore_cb(self, choice):
		if choice:
			ok = restore_backup(choice[1])
			self.session.open(MessageBox, _("EPG odtworzone z kopii.") if ok else _("Odtworzenie nieudane, szczegoly w %s") % log_path(),
				MessageBox.TYPE_INFO if ok else MessageBox.TYPE_ERROR, timeout=5)

	def info(self):
		"""Raporty: statystyka EPG, log, pobieranie na dekoderze, porownanie, raport serwera."""
		from .menus import reports_menu
		reports_menu(self.session, self.engine)

	# --- widok ---
	def redraw(self):
		e = self.engine
		params_summary = False
		done, err, el = e.stats()
		tun = _("tunery wolne %d/%d, rezerwa %d") % (e.free_tuners(), e.total_tuners, e.reserve)
		nch = sum(len(j.refs) for j in e.jobs)
		nall = sum(len(j.all_refs) for j in e.jobs)
		nskip = len([j for j in e.jobs if j.skip])
		sk = (_(", pominietych %d") % nskip) if nskip else ""
		if self.ljob and self.ljob.active and not e.running and not self.grab_active():
			head = self.ljob.text
		elif self.grab_active():
			head = _("%s\nPo zakonczeniu: %s") % (self.grab.text, self.grab.plan())
		elif not e.running and self.sw_scanning():
			head = "SatWatch: %s" % self.sw_status()
		elif e.running and e.phase == "xmltv" and e.xmltv:
			if getattr(e, "xmltv_only", False):
				head = _("Import XMLTV: %s   czas %d:%02d\nSatelita: bez skanu (sam import XMLTV)") % (
					e.xmltv.status, el // 60, el % 60)
			else:
				head = _("Uzupelnianie z XMLTV: %s   czas %d:%02d\nSkan z satelity: %s") % (
					e.xmltv.status, el // 60, el % 60, display_scan_summary(e.summary))
		elif e.running and e.phase == "satwatch":
			try:
				from .satwatch.sw import runner
				st = runner.status
			except Exception:
				st = ""
			head = _("SatWatch (przed skanem EPG, czas %d:%02d): %s") % (int(time.time() - e.t_start) // 60,
				int(time.time() - e.t_start) % 60, st)
		elif e.running and e.phase == "channelparams":
			scan = getattr(e, "channel_params_scan", None)
			active = len(scan.active) if scan else 0
			reserved = len(scan.reservations) if scan else 0
			queued = len(scan.queue) if scan else 0
			head = _("Skan PAT/PMT kanalow: aktywne %d, rezerwacje TP %d, w kolejce %d, czas %d:%02d   %s") % (
				active, reserved, queued, el // 60, el % 60, tun)
		elif not e.running and e.finished and getattr(e, "channelparams_report", None):
			report = e.channelparams_report
			params_summary = True
			duration = int(report.get("duration", 0))
			head = _("PAT/PMT: %d kanalow, audio %d/%d sciezek, wybranych %d; bledow %d, pominietych %d, czas %d:%02d") % (
				report.get("scanned", 0), report.get("audio_channels", 0), report.get("audio_tracks", 0),
				report.get("selected_audio", 0), len(report.get("errors", [])), len(report.get("unsupported", [])),
				duration // 60, duration % 60)
		elif not e.jobs:
			head = _("Brak kanalow - sprawdz zakres skanowania i bukiety w ustawieniach")
		elif e.running and e.phase == "xmltv_wait":
			try:
				from .mapping import server_grab
				st = server_grab().text
			except Exception:
				st = ""
			head = _("Czekam na pobranie programu przed importem XMLTV:\n%s") % e.wait_text()
		elif e.running and e.phase == "xmltv_install":
			head = _("Instalowanie pakietu list zrodel XMLTV (opkg)...\nSkan z satelity: %s") % display_scan_summary(e.summary)
		elif e.running and e.topup:
			head = _("Doczytywanie brakow: %d/%d gotowe, aktywne %d, czas %d:%02d   %s") % (
				done, len(e.jobs), len(e.active), el // 60, el % 60, tun)
		elif e.running:
			head = _("Skanowanie%s: %d/%d gotowe, aktywne %d, bledy %d%s, czas %d:%02d   %s") % (
				_(" (harmonogram)") if e.auto else "", done, len(e.jobs) - nskip, len(e.active), err, sk, el // 60, el % 60, tun)
		elif e.finished and getattr(e, "xmltv_only", False):
			head = _("Zakonczono %s, czas %d:%02d") % (display_scan_summary(e.summary), el // 60, el % 60)
		elif e.running and getattr(e, "xmltv_only", False):
			head = _("Import XMLTV: przygotowanie...")
		elif e.finished:
			head = _("Zakonczono: %d/%d gotowe, %d bledow%s, czas %d:%02d") % (done, len(e.jobs) - nskip, err, sk, el // 60, el % 60)
		else:
			head = _("Transponderow: %d%s (kanalow w bukietach %d, wszystkich %d)   limit: %d (ustawienia)   %s") % (
				len(e.jobs), sk, nch, nall, e.max_parallel, tun)
		info = []
		if params_summary:
			conflicts = bool(report.get("sync_conflicts")) or any(
				"Pominiete konflikty:" in message for message in report.get("sync", []))
			if conflicts and report.get("errors"):
				info.append(_("Bledy i konflikty synchronizacji. Szczegoly: Info/EPG -> Raporty"))
			elif conflicts:
				info.append(_("Konflikty synchronizacji. Szczegoly: Info/EPG -> Raporty"))
			elif report.get("errors"):
				info.append(_("Szczegoly bledow: Info/EPG -> Raporty"))
			else:
				info.append(_("Szczegoly skanu: Info/EPG -> Raporty"))
		elif e.note:
			info.append(display_engine_note(e.note))
		if not e.running and cfg.xmltv.value:
			from .xmltv import sources_present
			if not sources_present():
				info.append(_("UWAGA: brak list zrodel XMLTV - zostana doinstalowane przy skanie (lub w ustawieniach)"))

		if not e.running:
			if e.changes:
				info.append(e.changes)
			nb = new_bouquets()
			if nb:
				info.append(_("Nowe bukiety (nie skanowane): %s - dodaj w ustawieniach") % ", ".join(b[1] for b in nb))
			if not cfg.sat_import.value:
				info.append(_("Profil bez satelity - start i harmonogram robia sam import XMLTV%s") % (
					"" if cfg.xmltv.value else _(" (UWAGA: program z internetu wylaczony)")))
			elif eit_on() is False:
				info.append(_("EIT wylaczone w ustawieniach EPG - skan %s") % (
					_("wlaczy je na stale") if cfg.eit_mode.value == "perm" else _("wlaczy je na czas pracy")))
			try:
				from .menus import satwatch_installed
				if satwatch_installed():
					info.append(_("UWAGA: osobna wtyczka SatWatch jest zainstalowana - SatWatch jest juz czescia EPG Scan, odinstaluj ja"))
			except Exception:
				pass
			if epgrefresh_active():
				info.append(_("UWAGA: wlaczony harmonogram EPG Refresh - obie wtyczki beda zajmowac tunery"))

		if not e.running:
			if getattr(getattr(cfg, "auto_in_progress", None), "value", False):
				head = _("Wykryto przerwany skan harmonogramu; po starcie zostanie powtorzony.\n") + head
			elif getattr(getattr(cfg, "cp_auto_in_progress", None), "value", False):
				head = _("Wykryto przerwany skan PAT/PMT; zostanie ponowiony po nastepnym EPG.\n") + head
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
				lines += [_("AKTYWNE: %s - %s") % (j.name, display_state(j.state)) for j in scan.active]
				lines += [_("REZERWACJA TP: [%s] %s") % (j.tuner, j.name) for j in scan.reservations]
				lines += [_("OCZEKUJE: %s - %s") % (j.name, display_state(j.state)) for j in scan.queue]
		else:
			for j in e.active + [x for x in e.jobs if x not in e.active]:
				sh = _(" wspoldz.") if j.shared else ""
				cov = _("EPG %d/%d, wszystkie %d/%d") % (j.covered, len(j.refs), j.all_covered, len(j.all_refs))
				if j in e.active:
					t = int(now - j.t0)
					txt = _("[%s%s] %s %ds  zdarzen %d (bylo %d, %+d)  %s") % (
						j.tuner, sh, display_state(j.state), t, j.events, j.events_start, j.events - j.events_start, cov)
				elif j.state.startswith("gotowe"):
					txt = _("[%s%s] %s %ds  zdarzen %d (bylo %d, %+d)  %s") % (
						j.tuner, sh, display_state(j.state), j.duration, j.events, j.events_start, j.events - j.events_start, cov)
				else:
					txt = _("%s  (kanalow %d, wszystkich %d)") % (display_state(j.state), len(j.refs), len(j.all_refs))
				lines.append("%s  -  %s" % (j.name, txt))
		if getattr(e, "xmltv_only", False):
			lines = [_("Sam import XMLTV (bez skanu satelity) - szczegoly w Raportach (Info/EPG) i w logu.")]
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
			_("Satelita:   %s") % (cfg.last_summary.value or "-"),
			_("Pobranie:   %s") % when(cfg.last_fetch.value),
			"Import:     %s" % when(cfg.last_import.value),
			_("Profil:     %s%s") % (profile_text(profile_default(), localized=True), "" if cfg.p_fetch.value else _(" (bez pobierania)")),
			_("Harmonogram: %s") % ((_("nastepny start %s") % fmt_ts(next_run())) if sched_active() else _("wylaczony"))]
		return out


from .common import scale_skins as _scale_skins  # noqa: E402
_scale_skins(globals())
