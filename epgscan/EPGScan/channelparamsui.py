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
from Components.ScrollLabel import ScrollLabel
from Components.SelectionList import SelectionList, SelectionEntryComponent
from enigma import eServiceReference, eTimer

from .channelparamscan import load_results, save_results, start_manual
from .channelparamsstore import add_common_defaults, apply_to_devices
from .common import cfg, keys_skin, init_keys, log, set_keys, selected_bouquets, channel_params_bouquet_channels, save_cfg


def _audio(result, pid):
	return next((stream for stream in result.get("streams", [])
		if stream.get("kind") == "audio" and stream.get("pid") == pid), None)


class ChannelParamsScreen(Screen):
	skin = """
	<screen position="center,center" size="1600,920" title="EPG Scan - parametry kanałów">
		<widget name="head" position="20,15" size="740,145" font="Regular;25" foregroundColor="#00ffcc66" />
		<widget name="list" position="20,170" size="740,455" font="Regular;25" itemHeight="44" scrollbarMode="showOnDemand" />
		<eLabel position="778,13" size="804,454" backgroundColor="#00505050" />
		<widget source="session.VideoPicture" render="Pig" position="780,15" size="800,450" zPosition="1" backgroundColor="#ff000000" />
		<widget name="preview" position="780,475" size="800,145" font="Regular;24" foregroundColor="#c0c0c0" />
		<eLabel position="20,635" size="1560,2" backgroundColor="#00505050" />
		<widget name="info" position="20,645" size="1560,130" font="Regular;25" />
		%s
	</screen>""" % keys_skin(20, 850, 1560)

	def __init__(self, session):
		Screen.__init__(self, session)
		self.setTitle("EPG Scan - parametry kanałów")
		self["head"] = Label("")
		self["list"] = MenuList([])
		self["preview"] = Label("Podglad aktualnego kanalu. Wybierz kanal klawiszem OK.")
		self["info"] = Label("")
		init_keys(self)
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions", "DirectionActions", "MenuActions", "EPGSelectActions"], {
			"ok": self.choose_audio, "green": self.refresh, "yellow": self.filter_menu,
			"blue": self.bouquet_menu, "menu": self.more_actions, "info": self.sync_menu,
			"showEventInfo": self.sync_menu, "showEPGList": self.sync_menu,
			"cancel": self.request_close, "red": self.request_close,
			"up": self["list"].up, "down": self["list"].down,
			"left": self["list"].pageUp, "right": self["list"].pageDown,
		}, -1)
		self.orig = None
		self.preview_ref = None
		self.pending_result = None
		self.pending_ref = None
		self.pending_retry = False
		self.zap_attempts = 0
		self.filter = "all"
		self.sort = cfg.map_sort.value
		self.dirty = False
		self.original_results = {}
		self.view_refs = []
		self.view_errors = {}
		self.preview_timer = eTimer()
		self.preview_timer.callback.append(self.channel_ready)
		self.engine = None
		from .engine import get_engine
		self.engine = get_engine(session)
		self.engine.listeners.append(self.refresh)
		self.refresh()
		self.onLayoutFinish.append(self.layout_done)
		self.onClose.append(self.cleanup)

	def layout_done(self):
		try:
			self["list"].onSelectionChanged.append(self.show_info)
		except Exception:
			pass
		self.show_info()

	def refresh(self):
		selected_ref = None
		selected_error = False
		try:
			selected_ref = self.current() and self.current().get("ref")
			selected_error = self.current_error() is not None
		except Exception:
			pass
		try:
			data = load_results()
		except Exception as e:
			self.results = {}
			self.channel_refs = []
			self.view_refs = []
			self.view_errors = {}
			self["list"].setList(["Nie mozna odczytac wynikow: %s" % e])
			self["head"].setText("Plik wynikow skanu jest uszkodzony lub niedostepny: %s" % e)
			return
		self.results = data.get("results", {})
		self.original_results = copy.deepcopy(self.results)
		self.last_scan = data.get("last_scan", {})
		self.sort = cfg.map_sort.value
		channel_order = []
		try:
			channel_order = [row["ref"] for row in channel_params_bouquet_channels(selected_bouquets()) if row.get("ref")]
		except Exception as e:
			log("Nie mozna odczytac kolejnosci bukietow dla wynikow: %s" % e)
		if self.sort == "abc":
			self.channel_refs = sorted(self.results, key=lambda ref: self.results[ref].get("name", ref).lower())
		else:
			order = {ref: i for i, ref in enumerate(channel_order)}
			self.channel_refs = sorted(self.results, key=lambda ref: (
				order.get(ref, len(order)), self.results[ref].get("name", ref).lower()))
		self.view_refs = []
		self.view_errors = {}
		rows = []
		for ref in self.channel_refs:
			result = self.results[ref]
			if cfg.scope.value == "selected" and ref not in channel_order:
				continue
			if not self._matches_filter(result):
				continue
			pid = result.get("selectedAudioPid")
			stream = _audio(result, pid) if pid is not None else None
			language = "/".join(stream.get("languages", [])) if stream else ""
			mark = "[OK]" if result.get("selectedConfirmed") and stream else ("[?]" if stream else "[--]")
			rows.append("%s %s   PID %s   %s" % (mark, result.get("name", ref), pid or "-", language))
			self.view_refs.append(ref)
		if self.filter in ("all", "errors"):
			for row in self.last_scan.get("errors", []):
				self.view_errors[len(rows)] = row
				rows.append("BLAD   %s: %s" % (row.get("name", row.get("ref", "")), row.get("error", "blad")))
				self.view_refs.append(row.get("ref"))
		if self.filter == "all":
			for row in self.last_scan.get("unsupported", []):
				rows.append("POMINIETY   %s: %s" % (row.get("name", ""), row.get("error", "nieobslugiwany typ")))
				self.view_refs.append(None)
		self["list"].setList(rows or ["Brak wynikow dla wybranego filtra."])
		run = self.last_scan
		if self.engine and self.engine.phase == "channelparams":
			scan = getattr(self.engine, "channel_params_scan", None)
			n = len(scan.active) if scan else 0
			r = len(scan.reservations) if scan else 0
			q = len(scan.queue) if scan else 0
			self["head"].setText("Skanowanie PAT/PMT: aktywne %d, rezerwacje TP %d, w kolejce %d\n"
				"Wyniki czesciowe: %d kanalow" % (n, r, q, len(self.results)))
		else:
			duration = int(run.get("duration", max(0, run.get("finished", 0) - run.get("started", 0))))
			self["head"].setText("Wyniki: %d kanalow | skan: %d odczytanych, %d z audio (%d sciezek), %d wybranych\n"
				"Bledy: %d | nieobslugiwane: %d | czas %d:%02d" %
				(len(self.channel_refs), run.get("scanned", 0), run.get("audio_channels", 0), run.get("audio_tracks", 0),
					run.get("selected_audio", 0), len(run.get("errors", [])), len(run.get("unsupported", [])),
					duration // 60, duration % 60))
		from .common import XB_CHOICES
		scope_name = dict(XB_CHOICES).get(cfg.scope.value, cfg.scope.value)
		filter_names = {"all": "wszystkie", "pending": "do decyzji", "confirmed": "potwierdzone",
			"noaudio": "bez audio", "errors": "bledy"}
		set_keys(self, red="Zamknij", green="Odswiez", yellow="Pokaz: %s" % filter_names.get(self.filter, self.filter),
			blue="Bukiety: %s" % scope_name)
		self.show_info()
		if selected_ref:
			try:
				index = next(i for i, ref in enumerate(self.view_refs)
					if ref == selected_ref and (i in self.view_errors) == selected_error)
				self["list"].moveToIndex(index)
			except Exception:
				pass

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
			ref = self.view_refs[i]
			if ref in self.results:
				return self.results[ref]
			error = self.view_errors.get(i)
			if ref and error:
				return {"ref": ref, "name": error.get("name") or ref, "streams": []}
		return None

	def current_error(self):
		if not getattr(self, "view_errors", None):
			return None
		return self.view_errors.get(self["list"].getSelectedIndex())

	def show_info(self):
		error = self.current_error()
		if error:
			action = "OK: ponow odczyt PAT/PMT i wybierz audio." if error.get("ref") else \
				"Brak referencji kanalu; nie mozna ponowic odczytu."
			self["info"].setText("BLAD %s: %s\n%s" %
				(error.get("name", ""), error.get("error", "blad"), action))
			return
		result = self.current()
		if not result:
			self["info"].setText("OK: przelacz kanal i wybierz sciezke audio. Menu: sortowanie/skan kanalow. "
				"Info/EPG: synchronizacja.")
			return
		pid = result.get("selectedAudioPid")
		stream = _audio(result, pid) if pid is not None else None
		selected = ("%s, PID %s" % ("/".join(stream.get("languages", [])) or "jezyk nieznany", pid)
			if stream else "brak potwierdzonej sciezki")
		streams = result.get("streams", [])
		audio_count = sum(1 for item in streams if item.get("kind") == "audio")
		video = next((item for item in streams if item.get("kind") == "video"), None)
		video_text = ("%s PID %s" % (video.get("codec", "video"), video.get("pid"))
			if video else "brak danych video")
		self["info"].setText("%s | Obecnie: %s | Audio: %d sciezek | Video: %s\n"
			"OK: wybor kanalu/audio   Menu: sortowanie/skan   Info/EPG: synchronizacja" %
			(result.get("name", ""), selected, audio_count, video_text))

	def choose_audio(self):
		result = self.current()
		if not result:
			if self.current_error():
				self.session.open(MessageBox, "Brak referencji kanalu. Nie mozna ponowic odczytu.",
					MessageBox.TYPE_ERROR, timeout=7)
			return
		if self.engine.running and self.engine.phase != "channelparams":
			self.session.open(MessageBox, "Nie mozna przelaczyc kanalu podczas innego skanu EPG.", MessageBox.TYPE_INFO, timeout=5)
			return
		try:
			import NavigationInstance
			nav = NavigationInstance.instance
			if nav.RecordTimer.isRecording():
				self.session.open(MessageBox, "Wybor sciezki anulowany: trwa nagrywanie.", MessageBox.TYPE_INFO, timeout=5)
				return
			if self.orig is None:
				self.orig = nav.getCurrentlyPlayingServiceReference()
			self.pending_result = result
			self.pending_ref = result["ref"]
			self.pending_retry = self.current_error() is not None
			self.zap_attempts = 0
			if not self._currently_on(self.pending_ref):
				nav.playService(eServiceReference(self.pending_ref))
			self.preview_ref = self.pending_ref
			self["preview"].setText("Przelaczanie na %s..." % result.get("name", "kanal"))
			self.preview_timer.start(700, True)
		except Exception as e:
			log("Wybor sciezki: nie mozna przelaczyc na kanal: %s" % e)
			self.session.open(MessageBox, "Nie mozna przelaczyc kanalu: %s" % e, MessageBox.TYPE_ERROR, timeout=8)

	def _currently_on(self, ref):
		try:
			import NavigationInstance
			current = NavigationInstance.instance.getCurrentlyPlayingServiceReference()
			return bool(current and current.toString().startswith(ref.rstrip(":")))
		except Exception:
			return False

	def channel_ready(self):
		result = self.pending_result
		if not result:
			return
		if not self._currently_on(self.pending_ref):
			self.zap_attempts += 1
			if self.zap_attempts < 8:
				self.preview_timer.start(500, True)
				return
			self["preview"].setText("Enigma2 nie przelaczyla kanalu. Sprobuj ponownie lub wybierz inny kanal.")
			return
		if getattr(self, "pending_retry", False):
			self._start_single_scan(result)
			return
		live_streams, current_pid = self._live_audio_tracks()
		scanned = [s for s in result.get("streams", []) if s.get("kind") == "audio"]
		if scanned or live_streams:
			result = copy.deepcopy(result)
			streams = result.setdefault("streams", [])
			known_pids = {stream.get("pid") for stream in streams if stream.get("kind") == "audio"}
			for stream in live_streams:
				if stream.get("pid") not in known_pids:
					live_stream = copy.deepcopy(stream)
					live_stream["source"] = "enigma"
					streams.append(live_stream)
					known_pids.add(stream.get("pid"))
			self.pending_result = result
			self.open_audio_picker(result, [s for s in streams if s.get("kind") == "audio"], current_pid)
			return
		self._start_single_scan(result)

	def _start_single_scan(self, result):
		if self.engine.running:
			self._single_scan_failed(result, "Skan jest zajety")
			return
		self["preview"].setText("Odczyt PAT/PMT dla %s..." % result.get("name", "kanalu"))
		try:
			started = start_manual(self.engine, done=lambda report: self.single_scan_done(report, result), refs=[result["ref"]])
		except Exception as e:
			log("Odczyt audio/PAT/PMT dla %s nieudany: %s" % (result.get("name", ""), e))
			self._single_scan_failed(result, str(e))
			return
		if not started:
			self._single_scan_failed(result, "Nie udalo sie rozpoczac skanu; sprawdz bukiety lub status skanera")

	def _live_audio_tracks(self):
		try:
			import NavigationInstance
			service = NavigationInstance.instance.getCurrentService()
			tracks = service and service.audioTracks()
			if not tracks:
				return [], None
			out = []
			current = tracks.getCurrentTrack()
			for index in range(tracks.getNumberOfTracks()):
				info = tracks.getTrackInfo(index)
				pid = int(info.getPID())
				language = info.getLanguage()
				out.append({"kind": "audio", "pid": pid, "languages": [language] if language else [],
					"codec": info.getDescription() or "", "trackIndex": index})
			current_pid = int(tracks.getTrackInfo(current).getPID()) if 0 <= current < len(out) else None
			return out, current_pid
		except Exception as e:
			log("Nie mozna odczytac sciezek z aktualnej uslugi Enigma2: %s" % e)
			return [], None

	def single_scan_done(self, report, result):
		if self.pending_result is not result:
			return
		self.refresh()
		failures = [row for row in report.get("errors", [])
			if not row.get("ref") or row.get("ref") == result["ref"]]
		failures += [row for row in report.get("unsupported", []) if row.get("ref") == result["ref"]]
		if failures:
			reason = failures[0].get("error", "kanal nie jest obslugiwany")
			self._single_scan_failed(result, reason)
			return
		self._show_audio_after_single_scan(result)

	def _single_scan_failed(self, result, reason):
		self["preview"].setText("Nie udalo sie ponownie odczytac PAT/PMT: %s" % reason)
		self.session.openWithCallback(lambda *_: self._show_audio_after_single_scan(result), MessageBox,
			"Nie udalo sie odczytac PAT/PMT: %s.\nMozesz sprawdzic audio z Enigma2 lub poprzedniego wyniku, "
			"ale PMT nie zostala ponownie odczytana." % reason, MessageBox.TYPE_ERROR)

	def _show_audio_after_single_scan(self, result):
		if self.pending_result is not result:
			return
		result = self.results.get(result["ref"], result)
		live_streams, current_pid = self._live_audio_tracks()
		if live_streams:
			result = copy.deepcopy(result)
			result_streams = result.setdefault("streams", [])
			known_pids = {stream.get("pid") for stream in result_streams if stream.get("kind") == "audio"}
			for stream in live_streams:
				if stream.get("pid") not in known_pids:
					live_stream = copy.deepcopy(stream)
					live_stream["source"] = "enigma"
					result_streams.append(live_stream)
					known_pids.add(stream.get("pid"))
		streams = [s for s in result.get("streams", []) if s.get("kind") == "audio"]
		if streams:
			self.pending_result = result
			self.open_audio_picker(result, streams, current_pid)
		else:
			self["preview"].setText("Odczyt PAT/PMT nie zwrocil sciezek audio dla %s." % result.get("name", "kanalu"))
			self.session.open(MessageBox, "Nie znaleziono sciezek audio w PAT/PMT tego kanalu.",
				MessageBox.TYPE_INFO, timeout=7)

	def open_audio_picker(self, result, streams, current_pid):
		self["preview"].setText("Wybor sciezki audio: %s. Po zamknieciu wroci poprzedni kanal." %
			result.get("name", "kanal"))
		self.session.openWithCallback(self.audio_chosen, ChannelAudioScreen, result, streams, current_pid,
			self._select_preview_track)

	def audio_chosen(self, choice=None):
		if not choice or not self.pending_result:
			self.pending_result = None
			self.pending_ref = None
			return
		pid, source = choice
		self._select_preview_track(pid)
		self.pending_result["selectedAudioPid"] = pid
		self.pending_result["selectedConfirmed"] = True
		self.pending_result["selectionSource"] = source
		self.pending_result["selectedAt"] = int(time.time())
		self.results[self.pending_result["ref"]] = self.pending_result
		self.store_results()
		self.pending_result = None
		self.pending_ref = None
		self.refresh()

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
		if self.dirty:
			self._save_changes()
		else:
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
		self.close()

	def filter_menu(self):
		options = [("Wszystkie", "all"), ("Do decyzji", "pending"), ("Potwierdzone", "confirmed"),
			("Bez sciezki audio", "noaudio"), ("Bledy ostatniego skanu", "errors")]
		self.session.openWithCallback(self.filter_chosen, ChoiceBox, title="Pokaz kanaly", list=options)

	def filter_chosen(self, choice):
		if choice:
			self.filter = choice[1]
			self.refresh()

	def bouquet_menu(self):
		options = [(("* " if key == cfg.scope.value else "   ") + label, key) for key, label in cfg.scope.choices.choices]
		options.append(("Wybierz bukiety...", "pick"))
		self.session.openWithCallback(self.bouquet_chosen, ChoiceBox, title="Zakres bukietow dla skanu kanalow", list=options)

	def bouquet_chosen(self, choice):
		if not choice:
			return
		if choice[1] == "pick":
			from .setup import EPGScanBouquets
			self.session.openWithCallback(lambda *_: self.refresh(), EPGScanBouquets, "scan")
			return
		cfg.scope.value = choice[1]
		cfg.scope.save()
		save_cfg()
		self.refresh()

	def more_actions(self):
		options = [(("* " if key == cfg.map_sort.value else "   ") + "Sortowanie: " + label, ("sort", key))
			for key, label in (("bq", "zgodnie z bukietami"), ("abc", "alfabetycznie"))]
		options.append(("Skan kanalow (PAT/PMT)", ("scan", None)))
		self.session.openWithCallback(self.more_action_chosen, ChoiceBox, title="Parametry kanalow", list=options)

	def more_action_chosen(self, choice):
		if not choice:
			return
		action, value = choice[1]
		if action == "sort":
			cfg.map_sort.value = value
			cfg.map_sort.save()
			save_cfg()
			self.refresh()
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
		self.open_sync_menu()

	def open_sync_menu(self):
		self.session.openWithCallback(self.sync_choice, ChoiceBox, title="E2Stream",
			list=[("1. Zastap domyslne", ("common", True)),
				("2. Dopisz braki", ("common", False)),
				("3. Zastap na klientach", ("devices", True)),
				("4. Dopisz braki na klientach", ("devices", False))])

	def sync_choice(self, choice):
		if not choice:
			return
		target, self.sync_overwrite = choice[1]
		if not self._syncable_results():
			self.session.open(MessageBox, "Brak prawidlowych sciezek audio do synchronizacji. Najpierw wykonaj skan lub wybierz sciezki.",
				MessageBox.TYPE_INFO, timeout=7)
			return
		if target == "common":
			self.session.openWithCallback(self.sync_common_confirm, MessageBox,
				"%s dane domyslne audio i PMT w common.json?\nPrzed zapisem powstanie kopia." %
				("Zastapic" if self.sync_overwrite else "Dopisac braki"),
				MessageBox.TYPE_YESNO, default=False)
		else:
			self.session.openWithCallback(lambda files: self.devices_selected(files, self.sync_overwrite),
				ChannelParamsDevicesScreen, self.results)

	def _syncable_results(self):
		return any(result.get("selectedAudioPid") is not None
			and _audio(result, result.get("selectedAudioPid")) for result in self.results.values())

	def sync_common_confirm(self, yes):
		if not yes:
			return
		try:
			message, collisions = add_common_defaults(self.results, overwrite=self.sync_overwrite)
			if collisions:
				message += "\nPominieto istniejace wartosci z konfliktem: %s" % ", ".join(collisions)
			self.session.open(MessageBox, message, MessageBox.TYPE_INFO, timeout=8)
		except Exception as e:
			self.session.open(MessageBox, "Blad zapisu common.json: %s" % e, MessageBox.TYPE_ERROR, timeout=8)

	def devices_selected(self, files, overwrite=None):
		if not files:
			return
		self.session.openWithCallback(lambda yes: self.apply_devices(yes, files, overwrite), MessageBox,
			"%s dane audio i PMT w %d wybranych odbiornikach?\n"
			"PMT z kolizja SID zostana pominiete. Przed zapisem powstana kopie." %
			("Zastapic" if overwrite else "Dopisac braki", len(files)),
			MessageBox.TYPE_YESNO, default=False)

	def apply_devices(self, yes, files, overwrite=None):
		if not yes:
			return
		try:
			message, collisions = apply_to_devices(self.results, files, overwrite=bool(overwrite))
			if collisions:
				message += "\nPominieto istniejace wartosci z konfliktem: %s" % ", ".join(collisions)
			self.session.open(MessageBox, message, MessageBox.TYPE_INFO, timeout=8)
		except Exception as e:
			self.session.open(MessageBox, "Blad zapisu profili: %s" % e, MessageBox.TYPE_ERROR, timeout=8)

	def cleanup(self):
		self.preview_timer.stop()
		self.pending_result = None
		self.pending_ref = None
		self.restore()
		if self.engine and self.refresh in self.engine.listeners:
			self.engine.listeners.remove(self.refresh)

	def restore(self):
		if self.orig is not None:
			try:
				import Screens.Standby
				if Screens.Standby.inStandby:
					self.orig = None
					return
				import NavigationInstance
				NavigationInstance.instance.playService(self.orig)
			except Exception as e:
				log("Podglad audio: nie udalo sie przywrocic kanalu: %s" % e)
			self.orig = None


class ChannelAudioScreen(Screen):
	skin = """
	<screen position="center,center" size="1600,920" title="EPG Scan - sciezki audio">
		<widget name="head" position="20,15" size="740,130" font="Regular;26" foregroundColor="#00ffcc66" />
		<widget name="list" position="20,155" size="740,470" font="Regular;26" itemHeight="46" scrollbarMode="showOnDemand" />
		<eLabel position="778,13" size="804,454" backgroundColor="#00505050" />
		<widget source="session.VideoPicture" render="Pig" position="780,15" size="800,450" zPosition="1" backgroundColor="#ff000000" />
		<widget name="preview" position="780,475" size="800,145" font="Regular;24" foregroundColor="#c0c0c0" />
		<eLabel position="20,635" size="1560,2" backgroundColor="#00505050" />
		<widget name="details" position="20,645" size="1560,130" font="Regular;25" />
		%s
	</screen>""" % keys_skin(20, 850, 1560)

	def __init__(self, session, result, streams, current_pid=None, preview_track=None):
		Screen.__init__(self, session)
		self.result = result
		self.streams = [stream for stream in streams if stream.get("kind") == "audio"]
		self.current_pid = current_pid
		self.preview_track = preview_track
		self.setTitle("Sciezki audio: %s" % result.get("name", "kanal"))
		self["head"] = Label("")
		self["list"] = MenuList([])
		self["preview"] = Label("Obraz kanalu na zywo.")
		self["details"] = Label("")
		init_keys(self)
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions", "DirectionActions", "EPGSelectActions"], {
			"ok": self.choose, "red": self.cancel, "cancel": self.cancel,
			"green": self.choose_automatic, "yellow": lambda: self.choose_user(0),
			"blue": lambda: self.choose_user(1), "info": self.show_full_info,
			"showEventInfo": self.show_full_info, "showEPGList": self.show_full_info,
			"up": self["list"].up, "down": self["list"].down,
			"left": self["list"].pageUp, "right": self["list"].pageDown,
		}, -1)
		set_keys(self, red="Wroc", green="Automat", yellow="1. jezyk", blue="2. jezyk")
		self.user_languages = [value.strip().lower() for value in cfg.cp_languages.value.split(",") if value.strip()]
		self.decoder_languages = self._decoder_languages()
		self.view_streams = list(self.streams)
		self["head"].setText("Kanal: %s\nAutomat Enigma2: %s\nPreferencje uzytkownika: %s" %
			(result.get("name", ""), ", ".join(self.decoder_languages) or "brak", ", ".join(self.user_languages) or "brak"))
		rows = []
		for stream in self.streams:
			pid = stream.get("pid")
			language = "/".join(stream.get("languages", [])) or "jezyk nieznany"
			marks = []
			if pid == result.get("preferredAudioPid"):
				marks.append("proponowana")
			if pid == result.get("selectedAudioPid"):
				marks.append("zapisana")
			if pid == current_pid:
				marks.append("aktywna")
			if stream.get("source") == "enigma":
				marks.append("Enigma2")
			rows.append("%s   PID %s   %s%s" % (language, pid, stream.get("codec", ""),
				("   [" + ", ".join(marks) + "]") if marks else ""))
		self["list"].setList(rows or ["Brak odczytanych sciezek audio"])
		self["list"].onSelectionChanged.append(self.selection_changed)
		self.show_details()

	def selection_changed(self):
		self.show_details()
		index = self["list"].getSelectedIndex()
		if 0 <= index < len(self.view_streams) and self.preview_track:
			self.preview_track(self.view_streams[index].get("pid"))

	def cancel(self):
		if self.current_pid is not None and self.preview_track:
			self.preview_track(self.current_pid)
		self.close()

	def _decoder_languages(self):
		try:
			from Components.config import config
			return [str(getattr(config.autolanguage, "audio_autoselect%d" % index).value).lower()
				for index in range(1, 5)
				if getattr(config.autolanguage, "audio_autoselect%d" % index).value]
		except Exception as e:
			log("Nie mozna odczytac preferencji audio Enigma2: %s" % e)
			return []

	def show_details(self):
		index = self["list"].getSelectedIndex()
		if not 0 <= index < len(self.view_streams):
			self["details"].setText("OK: wybierz sciezke. Info/EPG: pelne dane PAT/PMT i strumieni.")
			return
		stream = self.view_streams[index]
		self["details"].setText("Wybrana pozycja: %s | PID %s | %s\n"
			"OK: reczny wybor   Zielony: automat   Zolty/Niebieski: preferencje   Info/EPG: szczegoly PAT/PMT" %
			("/".join(stream.get("languages", [])) or "jezyk nieznany", stream.get("pid"), stream.get("codec", "")))

	def choose(self):
		index = self["list"].getSelectedIndex()
		if 0 <= index < len(self.view_streams):
			self.close((self.view_streams[index]["pid"], "manual"))

	def _choose_language(self, language):
		for stream in self.streams:
			stream_languages = [str(value).lower() for value in stream.get("languages", [])]
			if any(code == language or (len(language) >= 3 and code.startswith(language[:3]))
					for code in stream_languages):
				return stream
		return None

	def choose_automatic(self):
		if self.current_pid is not None and any(stream.get("pid") == self.current_pid for stream in self.streams):
			self.close((self.current_pid, "enigma"))
			return
		for language in self.decoder_languages:
			stream = self._choose_language(language)
			if stream:
				self.close((stream["pid"], "enigma"))
				return
		self.session.open(MessageBox, "Brak pasujacej sciezki dla automatycznych preferencji Enigma2.",
			MessageBox.TYPE_INFO, timeout=6)

	def choose_user(self, index):
		if len(self.user_languages) <= index:
			self.session.open(MessageBox, "Nie ustawiono %d. jezyka preferencji uzytkownika." % (index + 1),
				MessageBox.TYPE_INFO, timeout=5)
			return
		language = self.user_languages[index]
		stream = self._choose_language(language)
		if not stream:
			self.session.open(MessageBox, "Brak sciezki audio w jezyku %s." % language, MessageBox.TYPE_INFO, timeout=5)
			return
		self.close((stream["pid"], "user:%s" % language))

	def show_full_info(self):
		self.session.open(ChannelParamsInfoScreen, self.result)


class ChannelParamsInfoScreen(Screen):
	skin = """
	<screen position="center,center" size="1600,920" title="Dane kanalu - PAT/PMT">
		<widget name="text" position="20,20" size="1560,800" font="Regular;24" />
		%s
	</screen>""" % keys_skin(20, 850, 1560)

	def __init__(self, session, result):
		Screen.__init__(self, session)
		self.setTitle("Dane kanalu: %s" % result.get("name", ""))
		self["text"] = ScrollLabel(self._format(result))
		init_keys(self)
		self["actions"] = ActionMap(["OkCancelActions", "DirectionActions", "ColorActions"], {
			"ok": self["text"].pageDown, "down": self["text"].pageDown, "up": self["text"].pageUp,
			"red": self.close, "cancel": self.close,
		}, -1)
		set_keys(self, red="Wroc")

	def _format(self, result):
		lines = ["Kanal: %s" % result.get("name", ""),
			"Service reference: %s" % result.get("ref", ""),
			"Ostatni skan: %s" % result.get("scannedAt", "brak"),
			"PAT PID: 0 | PMT PID: %s" % result.get("pmtPid", "brak")]
		for stream in result.get("streams", []):
			source = " | Enigma2" if stream.get("source") == "enigma" else ""
			lines.append("%s PID %s | %s | jezyki: %s%s" % (
				stream.get("kind", "stream"), stream.get("pid", "?"), stream.get("codec", "?"),
				", ".join(stream.get("languages", [])) or "brak", source))
		lines += ["", "PAT:", result.get("pat", "brak"), "", "PMT:", result.get("section", "brak")]
		return "\n".join(lines)


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


from .common import scale_skins as _scale_skins  # noqa: E402
_scale_skins(globals())
