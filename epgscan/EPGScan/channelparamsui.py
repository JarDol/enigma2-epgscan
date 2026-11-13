# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
"""Review, preview and explicitly apply channel-parameter scan results."""

import os
import time

from Screens.Screen import Screen
from Screens.MessageBox import MessageBox
from Screens.AudioSelection import AudioSelection
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
		<widget name="list" position="25,95" size="1610,700" font="Regular;27" itemHeight="42" scrollbarMode="showOnDemand" />
		<widget name="help" position="25,810" size="1610,70" font="Regular;23" />
		%s
	</screen>""" % keys_skin(25, 880, 1610)

	def __init__(self, session):
		Screen.__init__(self, session)
		self.setTitle("EPG Scan - parametry kanałów")
		self["summary"] = Label("")
		self["list"] = MenuList([])
		self["help"] = Label("OK: wybor sciezki   Zielony: potwierdz i zapisz   Zolty: podglad/odsluch   Niebieski: E2Stream")
		init_keys(self)
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions", "DirectionActions", "MenuActions"], {
			"ok": self.choose_audio, "green": self.confirm_all, "yellow": self.preview,
			"blue": self.sync_menu, "menu": self.scan_action, "cancel": self.close, "red": self.close,
			"up": self["list"].up, "down": self["list"].down,
			"left": self["list"].pageUp, "right": self["list"].pageDown,
		}, -1)
		self.orig = None
		self.preview_ref = None
		self.preview_timer = eTimer()
		self.preview_timer.callback.append(self.open_audio_selection)
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
			self.keys = []
			self["list"].setList(["Nie mozna odczytac wynikow: %s" % e])
			self["summary"].setText("Plik wynikow skanu jest uszkodzony lub niedostepny.")
			return
		self.results = data.get("results", {})
		self.keys = sorted(self.results, key=lambda ref: self.results[ref].get("name", ref).lower())
		rows = []
		for ref in self.keys:
			result = self.results[ref]
			pid = result.get("selectedAudioPid")
			stream = _audio(result, pid) if pid is not None else None
			language = "/".join(stream.get("languages", [])) if stream else "do decyzji"
			mark = "OK" if result.get("selectedConfirmed") and stream else "WYBOR"
			rows.append("%s   %s   PID %s   %s" % (mark, result.get("name", ref), pid or "-", language))
		rows.extend("BLAD   %s: %s" % (row.get("name", row.get("ref", "")), row.get("error", "blad"))
			for row in data.get("last_scan", {}).get("errors", []))
		rows.extend("POMINIETY   %s: %s" % (row.get("name", ""), row.get("error", "nieobslugiwany typ"))
			for row in data.get("last_scan", {}).get("unsupported", []))
		self["list"].setList(rows or ["Brak wynikow. Uruchom skan przyciskiem Menu."])
		run = data.get("last_scan", {})
		if self.engine and self.engine.phase == "channelparams":
			scan = getattr(self.engine, "channel_params_scan", None)
			n = len(scan.active) if scan else 0
			q = len(scan.queue) if scan else 0
			self["summary"].setText("Skanowanie PAT/PMT: aktywne %d, w kolejce %d" % (n, q))
		else:
			self["summary"].setText("Wyniki: %d kanalow | ostatni skan: %d poprawnych, %d bledow, %d nieobslugiwanych" %
				(len(self.keys), run.get("scanned", 0), len(run.get("errors", [])), len(run.get("unsupported", []))))
		set_keys(self, red="Zamknij", green="Potwierdz/zapisz", yellow="Podglad", blue="E2Stream")

	def current(self):
		i = self["list"].getSelectedIndex()
		if 0 <= i < len(self.keys):
			return self.results[self.keys[i]]
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
		self.session.openWithCallback(self.audio_chosen, __import__("Screens.ChoiceBox", fromlist=["ChoiceBox"]).ChoiceBox,
			title=result.get("name", "Sciezka audio"), list=options)

	def audio_chosen(self, choice):
		if not choice:
			return
		pid = choice[1]
		self.pending_result["selectedAudioPid"] = pid
		self.pending_result["selectedConfirmed"] = True
		self.pending_result["selectedAt"] = int(time.time())
		self.store_results()

	def confirm_all(self):
		if not self.results:
			return
		self.session.openWithCallback(self.confirm_all_done, MessageBox,
			"Zapisac wszystkie jednoznaczne propozycje oraz reczne wybory?\n"
			"Niepewne i niejednoznaczne kanaly pozostana bez wyboru.", MessageBox.TYPE_YESNO, default=False)

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
		data = load_results()
		data["results"] = self.results
		data["updated"] = int(time.time())
		try:
			save_results(data)
			self.refresh()
		except Exception as e:
			self.session.open(MessageBox, "Nie udalo sie zapisac wynikow: %s" % e, MessageBox.TYPE_ERROR, timeout=8)

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
		try:
			started = start_manual(self.engine, lambda _report: self.refresh())
		except Exception as e:
			self.session.open(MessageBox, "Nie mozna odczytac/uruchomic skanu: %s" % e,
				MessageBox.TYPE_ERROR, timeout=8)
			return
		if not started:
			self.session.open(MessageBox, "Nie mozna uruchomic skanu. Sprawdz bukiety lub status EPG Scan.",
				MessageBox.TYPE_ERROR, timeout=6)
		self.refresh()

	def preview(self):
		result = self.current()
		if not result:
			return
		self.preview_result = result
		self.session.openWithCallback(self.preview_answer, MessageBox,
			"Podglad przelaczy aktualnie odtwarzany kanal. Po zamknieciu okna zostanie przywrocony.\n"
			"Po dostrojeniu otworzy sie standardowy wybor audio Enigma2, gdzie mozna odsluchac sciezki.\n"
			"Nie kontynuuj podczas nagrywania.", MessageBox.TYPE_YESNO, default=False)

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
			nav.playService(eServiceReference(self.preview_ref))
			self.preview_timer.start(2500, True)
		except Exception as e:
			self.session.open(MessageBox, "Nie mozna uruchomic podgladu: %s" % e, MessageBox.TYPE_ERROR, timeout=8)

	def open_audio_selection(self):
		try:
			self.session.openWithCallback(self.preview_done, AudioSelection, getattr(self.session, "infobar", None))
		except Exception as e:
			self.session.open(MessageBox, "Wybor audio Enigma2 niedostepny: %s" % e, MessageBox.TYPE_ERROR, timeout=8)

	def preview_done(self, _result=None):
		try:
			import NavigationInstance
			service = NavigationInstance.instance.getCurrentService()
			tracks = service and service.audioTracks()
			index = tracks and tracks.getCurrentTrack()
			if index is None or index < 0:
				return
			info = tracks.getTrackInfo(index)
			pid = info.getPID()
			if _audio(self.preview_result, pid):
				self.session.openWithCallback(lambda yes: self._use_preview_pid(pid) if yes else None,
					MessageBox, "Uzywac odsluchanej sciezki PID %d dla %s?" % (pid, self.preview_result.get("name", "")),
					MessageBox.TYPE_YESNO, default=True)
		except Exception as e:
			log("Podglad audio: nie mozna odczytac wybranej sciezki: %s" % e)

	def _use_preview_pid(self, pid):
		self.preview_result["selectedAudioPid"] = pid
		self.preview_result["selectedConfirmed"] = True
		self.preview_result["selectedAt"] = int(time.time())
		self.store_results()

	def sync_menu(self):
		from .common import EPGChoiceBox as ChoiceBox
		self.session.openWithCallback(self.sync_choice, ChoiceBox, title="E2Stream",
			list=[("Dodaj brakujace wartosci do common.json (nowe urzadzenia)", "common"),
				("Wybierz odbiorniki i nadpisz ich profile", "devices")])

	def sync_choice(self, choice):
		if not choice:
			return
		if choice[1] == "common":
			self.session.openWithCallback(self.sync_common_confirm, MessageBox,
				"Dodac tylko brakujace wpisy do wspolnych wartosci E2Stream?\n"
				"Plik common.json otrzyma kopie. Istniejace wpisy nie zostana nadpisane.",
				MessageBox.TYPE_YESNO, default=False)
		else:
			self.session.openWithCallback(self.devices_selected, ChannelParamsDevicesScreen, self.results)

	def sync_common_confirm(self, yes):
		if not yes:
			return
		try:
			message, collisions = add_common_defaults(self.results)
			if collisions:
				message += "\nPominieto kolidujace PMT: %s" % ", ".join(collisions)
			self.session.open(MessageBox, message, MessageBox.TYPE_INFO, timeout=8)
		except Exception as e:
			self.session.open(MessageBox, "Blad zapisu common.json: %s" % e, MessageBox.TYPE_ERROR, timeout=8)

	def devices_selected(self, files):
		if not files:
			return
		self.session.openWithCallback(lambda yes: self.apply_devices(yes, files), MessageBox,
			"Nadpisac potwierdzone sciezki audio w %d wybranych odbiornikach?\n"
			"PMT beda zapisane tylko bez kolizji SID. Przed zapisem powstana kopie profili." % len(files),
			MessageBox.TYPE_YESNO, default=False)

	def apply_devices(self, yes, files):
		if not yes:
			return
		try:
			message, collisions = apply_to_devices(self.results, files)
			if collisions:
				message += "\nPominieto kolidujace PMT: %s" % ", ".join(collisions)
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
			label = "%s  %s %s" % (name, device.get("name", ""), device.get("model", ""))
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
