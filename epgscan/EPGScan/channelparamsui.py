# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
"""Review, preview and explicitly apply channel-parameter scan results."""

import copy
import os
import time

from Screens.Screen import Screen
from Screens.MessageBox import MessageBox
from .common import EPGChoiceBox as ChoiceBox
from Components.ActionMap import ActionMap
from Components.Label import Label
from Components.MenuList import MenuList
from Components.SelectionList import SelectionList, SelectionEntryComponent
from enigma import eServiceReference, eTimer

from .channelparamscan import load_results, save_results, start_manual
from .channelparamsstore import add_common_defaults, apply_to_devices
from .common import cfg, keys_skin, init_keys, log, set_keys


def _audio(result, pid):
	return next((stream for stream in result.get("streams", [])
		if stream.get("kind") == "audio" and stream.get("pid") == pid), None)


class ChannelParamsScreen(Screen):
	skin = """
	<screen position="center,center" size="1660,940" title="EPG Scan - parametry kanałów">
		<widget name="summary" position="25,15" size="1610,65" font="Regular;30" />
		<widget name="list" position="25,95" size="1610,730" font="Regular;27" itemHeight="42" scrollbarMode="showOnDemand" />
		<widget name="help" position="25,835" size="1610,42" font="Regular;22" />
		%s
	</screen>""" % keys_skin(25, 880, 1610)

	def __init__(self, session):
		Screen.__init__(self, session)
		self.setTitle("EPG Scan - parametry kanałów")
		self["summary"] = Label("")
		self["list"] = MenuList([])
		self["help"] = Label("")
		init_keys(self)
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions", "DirectionActions", "MenuActions"], {
			"ok": self.choose_audio, "green": self.preview, "yellow": self.filter_menu,
			"blue": self.sync_menu, "menu": self.more_actions, "cancel": self.request_close, "red": self.request_close,
			"up": self["list"].up, "down": self["list"].down,
			"left": self["list"].pageUp, "right": self["list"].pageDown,
		}, -1)
		self.orig = None
		self.preview_ref = None
		self.preview_result = None
		self.preview_active = False
		self.pending_result = None
		self.filter = "all"
		self.dirty = False
		self.original_results = {}
		self.view_refs = []
		self.preview_timer = eTimer()
		self.preview_timer.callback.append(self.preview_ready)
		self.engine = None
		from .engine import get_engine
		self.engine = get_engine(session)
		self.engine.listeners.append(self.refresh)
		self.refresh()
		self.onClose.append(self.cleanup)

	def refresh(self):
		try:
			data = load_results()
		except Exception as e:
			self.results = {}
			self.channel_refs = []
			self["list"].setList(["Nie mozna odczytac wynikow: %s" % e])
			self["summary"].setText("Plik wynikow skanu jest uszkodzony lub niedostepny.")
			return
		if not getattr(self, "dirty", False) or not hasattr(self, "results"):
			self.results = data.get("results", {})
			self.original_results = copy.deepcopy(self.results)
		self.filter = getattr(self, "filter", "all")
		self.last_scan = data.get("last_scan", {})
		self.channel_refs = sorted(self.results, key=lambda ref: self.results[ref].get("name", ref).lower())
		self.view_refs = []
		rows = []
		for ref in self.channel_refs:
			result = self.results[ref]
			if not self._matches_filter(result):
				continue
			pid = result.get("selectedAudioPid")
			stream = _audio(result, pid) if pid is not None else None
			language = "/".join(stream.get("languages", [])) if stream else ""
			mark = "OK" if result.get("selectedConfirmed") and stream else (
				"PROPOZYCJA" if stream else "DO DECYZJI")
			rows.append("%s   %s   PID %s   %s" % (mark, result.get("name", ref), pid or "-", language))
			self.view_refs.append(ref)
		if self.filter in ("all", "errors"):
			rows.extend("BLAD   %s: %s" % (row.get("name", row.get("ref", "")), row.get("error", "blad"))
				for row in self.last_scan.get("errors", []))
		if self.filter == "all":
			rows.extend("POMINIETY   %s: %s" % (row.get("name", ""), row.get("error", "nieobslugiwany typ"))
				for row in self.last_scan.get("unsupported", []))
		self["list"].setList(rows or ["Brak wynikow dla wybranego filtra."])
		run = self.last_scan
		if self.engine and self.engine.phase == "channelparams":
			scan = getattr(self.engine, "channel_params_scan", None)
			n = len(scan.active) if scan else 0
			r = len(scan.reservations) if scan else 0
			q = len(scan.queue) if scan else 0
			self["summary"].setText("Skanowanie PAT/PMT: aktywne %d, rezerwacje TP %d, w kolejce %d" % (n, r, q))
		else:
			duration = int(run.get("duration", max(0, run.get("finished", 0) - run.get("started", 0))))
			self["summary"].setText("Wyniki: %d kanalow | skan: %d poprawnych, %d bledow, %d nieobslugiwanych | czas %d:%02d" %
				(len(self.channel_refs), run.get("scanned", 0), len(run.get("errors", [])),
					len(run.get("unsupported", [])), duration // 60, duration % 60))
		has_results = bool(self.results)
		set_keys(self, red="Zamknij", green="Podglad" if has_results else "",
			yellow="Filtr" if has_results or run.get("errors") else "", blue="E2Stream" if has_results else "")
		if has_results:
			self["help"].setText("OK: sciezka audio   Zielony: podglad kanalu   Zolty: filtr   Menu: potwierdz propozycje / skan")
		else:
			self["help"].setText("Brak wynikow. Uruchom skan z ekranu Akcje albo nacisnij Menu.")

	def _matches_filter(self, result):
		pid = result.get("selectedAudioPid")
		has_audio = _audio(result, pid) is not None if pid is not None else False
		if self.filter == "pending":
			return not result.get("selectedConfirmed") or not has_audio
		if self.filter == "confirmed":
			return bool(result.get("selectedConfirmed") and has_audio)
		if self.filter == "noaudio":
			return not any(stream.get("kind") == "audio" for stream in result.get("streams", []))
		return self.filter != "errors"

	def current(self):
		i = self["list"].getSelectedIndex()
		if 0 <= i < len(self.view_refs):
			return self.results[self.view_refs[i]]
		return None

	def choose_audio(self):
		result = self.current()
		if not result:
			return
		streams = [s for s in result.get("streams", []) if s.get("kind") == "audio"]
		if not streams:
			self.session.open(MessageBox, "Brak sciezek audio w PMT tego kanalu.", MessageBox.TYPE_INFO, timeout=5)
			return
		options = []
		for stream in streams:
			options.append(("%s  PID %d  %s  %s" % ("/".join(stream.get("languages", [])) or "jezyk nieznany",
				stream["pid"], stream.get("codec", ""), " (preferowana)" if stream["pid"] == result.get("preferredAudioPid") else ""),
				stream["pid"]))
		self.pending_result = result
		self.session.openWithCallback(self.audio_chosen, ChoiceBox, title="Wybor stacji: %s" % result.get("name", ""),
			list=options)

	def audio_chosen(self, choice):
		if not choice:
			return
		pid = choice[1]
		self.pending_result["selectedAudioPid"] = pid
		self.pending_result["selectedConfirmed"] = True
		self.pending_result["selectedAt"] = int(time.time())
		if self.preview_active and self.preview_result is self.pending_result:
			self._select_preview_track(pid)
		self.store_results()

	def confirm_all(self):
		if not self.results:
			return
		count = sum(1 for result in self.results.values()
			if result.get("selectedAudioPid") is not None and _audio(result, result.get("selectedAudioPid")))
		self.session.openWithCallback(self.confirm_all_done, MessageBox,
			"Potwierdzic %d zapisanych propozycji sciezki audio? Niepewne kanaly pozostana bez wyboru." % count,
			MessageBox.TYPE_YESNO, default=False)

	def confirm_all_done(self, yes):
		if not yes:
			return
		for result in self.results.values():
			pid = result.get("selectedAudioPid")
			if pid is not None and _audio(result, pid):
				result["selectedConfirmed"] = True
				result["selectedAt"] = int(time.time())
		self.store_results()

	def store_results(self):
		self.dirty = self.results != self.original_results
		self.refresh()

	def _save_changes(self):
		if not self.dirty:
			return True
		try:
			data = load_results()
			data["results"] = self.results
			data["updated"] = int(time.time())
			save_results(data)
			self.original_results = copy.deepcopy(self.results)
			self.dirty = False
			self.refresh()
			return True
		except Exception as e:
			log("Nie udalo sie zapisac wyborow sciezek audio: %s" % e)
			self.session.open(MessageBox, "Nie udalo sie zapisac wynikow: %s" % e, MessageBox.TYPE_ERROR, timeout=8)
			return False

	def request_close(self):
		if not self.dirty:
			self.close()
			return
		self.session.openWithCallback(self.close_save_answer, MessageBox,
			"Zapisac zmiany wyboru sciezek audio przed zamknieciem?", MessageBox.TYPE_YESNO, default=True)

	def close_save_answer(self, yes):
		if yes:
			if self._save_changes():
				self.close()
		else:
			self.close()

	def filter_menu(self):
		options = [("Wszystkie", "all"), ("Do decyzji", "pending"), ("Potwierdzone", "confirmed"),
			("Bez sciezki audio", "noaudio"), ("Bledy ostatniego skanu", "errors")]
		self.session.openWithCallback(self.filter_chosen, ChoiceBox, title="Pokaz kanaly", list=options)

	def filter_chosen(self, choice):
		if choice:
			self.filter = choice[1]
			self.refresh()

	def more_actions(self):
		options = [("Potwierdz jednoznaczne propozycje audio", "confirm"),
			("Zapisz zmiany", "save"), ("Uruchom ponownie skan PAT/PMT", "scan")]
		self.session.openWithCallback(self.more_action_chosen, ChoiceBox, title="Akcje - parametry kanalow", list=options)

	def more_action_chosen(self, choice):
		if not choice:
			return
		if choice[1] == "confirm":
			self.confirm_all()
		elif choice[1] == "save":
			self._save_changes()
		else:
			self.scan_action()

	def scan_action(self):
		if self.engine.running and self.engine.phase != "channelparams":
			self.session.open(MessageBox, "Inne zadanie EPG Scan juz trwa. Skan parametrow nie moze go przerwac.",
				MessageBox.TYPE_INFO, timeout=5)
			return
		if self.engine.phase == "channelparams":
			self.session.openWithCallback(lambda yes: self.engine.stop() if yes else None, MessageBox,
				"Przerwac skan parametrow?", MessageBox.TYPE_YESNO, default=False)
			return
		self.session.openWithCallback(self.start_scan_answer, MessageBox,
			"Uruchomic osobny skan PAT/PMT dla wszystkich kanalow z wybranych bukietow?\n"
			"EPG nie bedzie czyszczone ani modyfikowane. IPTV zostanie jawnie wykazane jako nieobslugiwane.",
			MessageBox.TYPE_YESNO, default=False)

	def start_scan_answer(self, yes):
		if not yes:
			return
		if self.dirty and not self._save_changes():
			return
		try:
			started = start_manual(self.engine)
		except Exception as e:
			self.session.open(MessageBox, "Nie mozna odczytac/uruchomic skanu: %s" % e,
				MessageBox.TYPE_ERROR, timeout=8)
			return
		if not started:
			self.session.open(MessageBox, "Nie mozna uruchomic skanu. Sprawdz bukiety lub status EPG Scan.",
				MessageBox.TYPE_ERROR, timeout=6)
			self.refresh()
			return
		self.close()

	def preview(self):
		result = self.current()
		if not result:
			return
		self.preview_result = result
		self.session.openWithCallback(self.preview_answer, MessageBox,
			"Podglad przelaczy kanal na %s. Po zamknieciu okna wroci poprzedni kanal.\n"
			"Sciezke audio wybierzesz klawiszem OK. Nie kontynuuj podczas nagrywania." %
			result.get("name", ""), MessageBox.TYPE_YESNO, default=False)

	def preview_answer(self, yes):
		if not yes:
			return
		try:
			import NavigationInstance
			nav = NavigationInstance.instance
			if nav.RecordTimer.isRecording():
				self.session.open(MessageBox, "Podglad anulowany: trwa nagrywanie.", MessageBox.TYPE_INFO, timeout=5)
				return
			if self.orig is None:
				self.orig = nav.getCurrentlyPlayingServiceReference()
			self.preview_ref = self.preview_result["ref"]
			if not nav.playService(eServiceReference(self.preview_ref)):
				raise RuntimeError("Enigma2 nie przelaczyla kanalu")
			self.preview_active = False
			self.preview_timer.start(2500, True)
		except Exception as e:
			self.session.open(MessageBox, "Nie mozna uruchomic podgladu: %s" % e, MessageBox.TYPE_ERROR, timeout=8)

	def preview_ready(self):
		self.preview_active = True
		self["help"].setText("Podglad: %s. OK: wybor i zmiana sciezki audio; zamknij okno, aby wrocic do poprzedniego kanalu." %
			self.preview_result.get("name", ""))

	def _select_preview_track(self, pid):
		try:
			import NavigationInstance
			service = NavigationInstance.instance.getCurrentService()
			tracks = service and service.audioTracks()
			if not tracks:
				raise RuntimeError("Brak aktywnych sciezek audio")
			for index in range(tracks.getNumberOfTracks()):
				if tracks.getTrackInfo(index).getPID() == pid:
					tracks.selectTrack(index)
					return
			raise RuntimeError("Aktywna usluga nie udostepnia PID %d" % pid)
		except Exception as e:
			log("Podglad audio: nie mozna ustawic PID %s: %s" % (pid, e))
			self.session.open(MessageBox, "Nie mozna przelaczyc sciezki audio podgladu: %s" % e,
				MessageBox.TYPE_ERROR, timeout=8)

	def sync_menu(self):
		if self.dirty:
			self.session.openWithCallback(self.sync_save_answer, MessageBox,
				"Zapisac zmiany wyboru sciezek audio przed synchronizacja z E2Stream?",
				MessageBox.TYPE_YESNO, default=True)
			return
		self.open_sync_menu()

	def sync_save_answer(self, yes):
		if yes and self._save_changes():
			self.open_sync_menu()

	def open_sync_menu(self):
		confirmed = [result for result in self.results.values()
			if result.get("selectedConfirmed") and _audio(result, result.get("selectedAudioPid"))]
		if not confirmed:
			proposals = [result for result in self.results.values()
				if result.get("selectedAudioPid") is not None and _audio(result, result.get("selectedAudioPid"))]
			if not proposals:
				self.session.open(MessageBox, "Brak propozycji sciezek audio. Uruchom skan lub wybierz sciezke klawiszem OK.",
					MessageBox.TYPE_INFO, timeout=7)
				return
			self.session.openWithCallback(self.confirm_for_sync, MessageBox,
				"Nie ma potwierdzonych sciezek. Potwierdzic %d jednoznacznych propozycji audio przed synchronizacja?" %
				len(proposals), MessageBox.TYPE_YESNO, default=False)
			return
		self.session.openWithCallback(self.sync_choice, ChoiceBox, title="E2Stream",
			list=[("Dodaj brakujace wartosci do common.json (nowe urzadzenia)", "common"),
				("Zapisz w wybranych konfiguracjach klientow", "devices")])

	def confirm_for_sync(self, yes):
		if not yes:
			return
		self.confirm_all_done(True)
		if self._save_changes():
			self.open_sync_menu()

	def sync_choice(self, choice):
		if not choice:
			return
		if choice[1] == "common":
			self.session.openWithCallback(self.sync_common_confirm, MessageBox,
				"%s wpisy audio i PMT w common.json?\nPrzed zapisem powstanie kopia." %
				("Nadpisac" if cfg.cp_common_overwrite.value else "Uzupelnic brakujace lub nieprawidlowe"),
				MessageBox.TYPE_YESNO, default=False)
		else:
			self.session.openWithCallback(self.devices_selected, ChannelParamsDevicesScreen, self.results)

	def sync_common_confirm(self, yes):
		if not yes:
			return
		try:
			message, collisions = add_common_defaults(self.results, overwrite=cfg.cp_common_overwrite.value)
			if collisions:
				message += "\nPominieto istniejace wartosci z konfliktem: %s" % ", ".join(collisions)
			self.session.open(MessageBox, message, MessageBox.TYPE_INFO, timeout=8)
		except Exception as e:
			self.session.open(MessageBox, "Blad zapisu common.json: %s" % e, MessageBox.TYPE_ERROR, timeout=8)

	def devices_selected(self, files):
		if not files:
			return
		self.session.openWithCallback(lambda yes: self.apply_devices(yes, files), MessageBox,
			"%s dane audio i PMT w %d wybranych odbiornikach?\n"
			"PMT z kolizja SID zostana pominiete. Przed zapisem powstana kopie." %
			("Nadpisac" if cfg.cp_devices_overwrite.value else "Uzupelnic brakujace lub nieprawidlowe", len(files)),
			MessageBox.TYPE_YESNO, default=False)

	def apply_devices(self, yes, files):
		if not yes:
			return
		try:
			message, collisions = apply_to_devices(self.results, files, overwrite=cfg.cp_devices_overwrite.value)
			if collisions:
				message += "\nPominieto istniejace wartosci z konfliktem: %s" % ", ".join(collisions)
			self.session.open(MessageBox, message, MessageBox.TYPE_INFO, timeout=8)
		except Exception as e:
			self.session.open(MessageBox, "Blad zapisu profili: %s" % e, MessageBox.TYPE_ERROR, timeout=8)

	def cleanup(self):
		self.preview_timer.stop()
		self.restore()
		if self.engine and self.refresh in self.engine.listeners:
			self.engine.listeners.remove(self.refresh)

	def restore(self):
		if self.orig is not None:
			try:
				import NavigationInstance
				NavigationInstance.instance.playService(self.orig)
			except Exception as e:
				log("Podglad audio: nie udalo sie przywrocic kanalu: %s" % e)
			self.orig = None


class ChannelParamsDevicesScreen(Screen):
	skin = """
	<screen position="center,center" size="1200,820" title="E2Stream - wybór odbiorników">
		<widget name="list" position="20,20" size="1160,680" scrollbarMode="showOnDemand" />
		<widget name="keys" position="20,720" size="1160,60" font="Regular;26" />
		%s
	</screen>""" % keys_skin(20, 780, 1160)

	def __init__(self, session, results):
		Screen.__init__(self, session)
		self.results = results
		from . import clients
		self.devices = [name for name in sorted(os.listdir(clients.DIR))
			if name.startswith("device-") and name.endswith(".json")] if os.path.isdir(clients.DIR) else []
		entries = []
		for i, name in enumerate(self.devices):
			info = clients._load(os.path.join(clients.DIR, name), {}) or {}
			device = info.get("device", {})
			label = "%s  %s" % (device.get("name") or device.get("model") or "Odbiornik %d" % (i + 1),
				device.get("model", "") if device.get("name") else "")
			entries.append(SelectionEntryComponent(label, name, i, False))
		self["list"] = SelectionList(entries)
		self["keys"] = Label("OK: zaznacz.  Zielony: dalej.  Czerwony: anuluj.")
		init_keys(self)
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions"], {
			"ok": self["list"].toggleSelection, "green": self.confirm, "red": self.close, "cancel": self.close,
		}, -1)
		set_keys(self, red="Anuluj", green="Zapisz wybrane")

	def confirm(self):
		self.close([row[1] for row in self["list"].getSelectionsList()])
