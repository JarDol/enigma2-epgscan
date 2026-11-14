# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
"""PAT/PMT scanner using the existing Enigma2 recording-service tuner path."""

import base64
import json
import os
import time

from enigma import eServiceReference, eTimer, iRecordableService
import NavigationInstance

from . import channelparams
from .common import CHANNEL_PARAMS_FILE, NO_TUNER_WAIT, TUNE_GAP, TUNE_TIMEOUT, \
	channel_params_bouquet_channels, cfg, configured_tuners, frontend_of, log, same_tp, selected_bouquets, service_name, tp_key, tp_params, save_cfg

STREAM_DATA_WAIT = 5
BATCH_DATA_WAIT = 20
BATCH_MAX_PMT_FILTERS = 4
BATCH_PMT_SLICE = 2
BATCH_SECTIONS_PER_TICK = 4


def load_results():
	try:
		with open(CHANNEL_PARAMS_FILE, encoding="utf-8") as f:
			data = json.load(f)
		if not isinstance(data, dict) or not isinstance(data.get("results"), dict):
			raise ValueError("Nieprawidlowy format pliku wynikow")
		return data
	except FileNotFoundError:
		return {"results": {}}
	except Exception as e:
		log("Skan parametrow: odczyt wynikow nieudany: %s" % e)
		raise


def save_results(data):
	directory = os.path.dirname(CHANNEL_PARAMS_FILE)
	if not os.path.isdir(directory):
		os.makedirs(directory)
	tmp = CHANNEL_PARAMS_FILE + ".tmp"
	try:
		with open(tmp, "w", encoding="utf-8") as f:
			json.dump(data, f, ensure_ascii=False, indent=1, sort_keys=True)
			f.flush()
			os.fsync(f.fileno())
		os.replace(tmp, CHANNEL_PARAMS_FILE)
	except Exception:
		try:
			os.unlink(tmp)
		except OSError:
			pass
		raise


def preferred_languages():
	if cfg.cp_language_source.value == "enigma":
		try:
			from Components.config import config
			return [getattr(config.autolanguage, "audio_autoselect%d" % i).value for i in range(1, 5)]
		except Exception as e:
			log("Skan parametrow: nie mozna odczytac preferencji jezykow Enigma2: %s" % e)
			return []
	return [x.strip() for x in cfg.cp_languages.value.split(",") if x.strip()]


class ChannelJob(object):
	def __init__(self, row):
		self.ref = row["ref"]
		self.name = row["name"] or service_name(self.ref)
		self.refs = [self.ref]
		self.all_refs = [self.ref]
		self.tp = tp_params(self.ref)
		self.tp_key = tp_key(self.ref)
		self.group = None
		self.rec = None
		self.tuner_no = -1
		self.tuner = "-"
		self.shared = False
		self.not_before = 0
		self.state = "czeka"
		self.t0 = 0
		self.tuned_at = 0
		self.events = 0
		self.events_start = 0
		self.covered = 0
		self.all_covered = 0
		self.duration = 0
		self.capture_started_at = 0
		self.adapter = -1
		self.demux = -1
		self.pmt_pid = -1
		self.pat_reader = None
		self.pmt_reader = None
		self.pat_section = None
		self.pmt_section = None
		self.individual = False
		self.batch_members = []
		self.batch_readers = {}
		self.batch_waiting = []
		self.batch_started = {}
		self.batch_filter_errors = {}


class TransponderGroup(object):
	def __init__(self, tp):
		self.tp = tp
		self.tp_key = None
		self.pending = []
		self.current = None
		self.reservation = None
		self.not_before = 0
		self.started_at = 0
		self.done = False


def group_channel_jobs(jobs, group_by_transponder=True):
	groups = []
	for job in jobs:
		group = next((item for item in groups if group_by_transponder and same_transponder(job, item)), None)
		if group is None:
			group = TransponderGroup(job.tp)
			group.tp_key = job.tp_key
			groups.append(group)
		elif group.tp is None and job.tp is not None:
			group.tp = job.tp
		group.pending.append(job)
		job.group = group
	return groups


def same_transponder(job, group):
	if job.tp_key is not None and group.tp_key is not None and job.tp_key != group.tp_key:
		return False
	if job.tp is not None and group.tp is not None:
		return same_tp(job.tp, group.tp)
	return job.tp_key is not None and job.tp_key == group.tp_key


def group_owns_tuner(group):
	return any(job is not None and not job.shared for job in (group.current, group.reservation))


class ChannelParamsScan(object):
	"""Uses ScanEngine's tuner accounting; it never clears or reads/writes EPG data."""

	def __init__(self, engine, done=None, refs=None):
		self.engine = engine
		self.done = done
		self.refs = set(refs) if refs is not None else None
		self.queue = []
		self.active = []
		self.groups = []
		self.reservations = []
		data = load_results()
		self.results = data.get("results", {})
		self.last_scan = data.get("last_scan", {})
		self.scanned_refs = set()
		self.errors = []
		self.failed_jobs = []
		self.final_retry_started = False
		self.unsupported = []
		self.started = 0
		self.last_tune = getattr(engine, "last_tune", 0)
		self.last_notify = 0
		self.cancelled = False
		self.borrowed = {}
		self.prepared = False
		self.timer = eTimer()
		self.timer.callback.append(self.tick)

	def prepare(self):
		rows = channel_params_bouquet_channels(selected_bouquets())
		if self.refs is not None:
			rows = [row for row in rows if row["ref"] in self.refs]
		self.unsupported = [row for row in rows if not row["supported"]]
		self.queue = [ChannelJob(row) for row in rows if row["supported"]]
		self.groups = group_channel_jobs(self.queue, bool(cfg.cp_group_tp.value))
		self.prepared = True
		self.started = int(time.time())

	def start(self):
		if self.engine.phase == "channelparams":
			return False
		if not self.prepared:
			self.prepare()
		self.last_tune = max(self.last_tune, getattr(self.engine, "last_tune", 0))
		self.queue = [job for job in self.queue if job.ref not in self.scanned_refs]
		for group in self.groups:
			group.pending = [job for job in group.pending if job.ref not in self.scanned_refs]
		if not self.queue and not self.unsupported:
			if not self.scanned_refs:
				return False
		self.engine.phase = "channelparams"
		self.engine.channel_params_scan = self
		log("Skan parametrow: %d kanalow DVB w %d transponderach, %d nieobslugiwanych; tunery %d, rezerwa %d" %
			(len(self.queue), len(self.groups), len(self.unsupported), self.engine.total_tuners, self.engine.reserve))
		if self.unsupported:
			log("Skan parametrow: IPTV/inne pominięte jawnie: %s" %
				", ".join(row["name"] for row in self.unsupported[:20]))
		if not self.queue:
			self.timer.start(1, True)
			return True
		NavigationInstance.instance.record_event.append(self.rec_event)
		self.timer.start(250, False)
		self.tick()
		return True

	def attach_epg(self, job):
		"""Borrow the already tuned EPG service, without owning or releasing it."""
		key = tp_key(job.ref)
		if key is None or id(job) in self.borrowed:
			return
		in_use = {member.ref for anchor in self.borrowed.values() for member in anchor.batch_members}
		members = [member for member in self.queue if member.tp_key == key
			and member.ref not in self.scanned_refs and member.ref not in in_use]
		if not members:
			return
		anchor = ChannelJob({"ref": members[0].ref, "name": members[0].name})
		anchor.rec = job.rec
		anchor.group = TransponderGroup(members[0].tp)
		anchor.group.pending = list(members)
		anchor.batch_members = members
		anchor.tuned_at = time.time()
		anchor.state = "odczyt strumienia"
		anchor.borrowed = True
		self.borrowed[id(job)] = anchor

	def poll_epg(self, job):
		anchor = self.borrowed.get(id(job))
		if anchor is None:
			return
		try:
			if anchor.state == "odczyt strumienia":
				self._begin_capture(anchor)
			elif anchor.state == "zbieranie PAT/PMT":
				self._poll_batch(anchor)
		except Exception as e:
			log("Skan parametrow podczas EPG: %s" % e)
			self._end_borrowed(anchor)

	def _end_borrowed(self, anchor):
		self._close_readers(anchor)
		anchor.rec = None
		for key, current in list(self.borrowed.items()):
			if current is anchor:
				del self.borrowed[key]
				break

	def release_epg(self, job):
		anchor = self.borrowed.get(id(job))
		if anchor is not None:
			self._end_borrowed(anchor)

	def save_partial(self):
		if self.scanned_refs:
			self._merge_latest_results()
			save_results({"updated": int(time.time()), "results": self.results, "last_scan": self.last_scan})

	def _merge_latest_results(self):
		latest = load_results().get("results", {})
		for ref in self.scanned_refs:
			current = self.results.get(ref)
			old = latest.get(ref, {})
			if not current or old.get("selectionSource") == "default":
				continue
			pid = old.get("selectedAudioPid")
			if (old.get("selectedConfirmed") or old.get("selectionSource") == "manual") and \
					any(stream.get("kind") == "audio" and stream.get("pid") == pid
						for stream in current.get("streams", [])):
				current["selectedAudioPid"] = pid
				current["selectedConfirmed"] = True
				for field in ("selectionSource", "selectedAt"):
					if field in old:
						current[field] = old[field]
					else:
						current.pop(field, None)
		latest.update({ref: self.results[ref] for ref in self.scanned_refs if ref in self.results})
		self.results.update(latest)

	def cancel(self):
		self.cancelled = True
		self.timer.stop()
		for anchor in list(self.borrowed.values()):
			self._end_borrowed(anchor)
		try:
			NavigationInstance.instance.record_event.remove(self.rec_event)
		except Exception:
			pass
		for job in list(self.active) + list(self.reservations):
			self._close_readers(job)
			self.engine.release(job)
			job.state = "przerwany"
		self.active = []
		self.reservations = []
		self.queue = []
		self.groups = []
		self.engine.channel_params_scan = None

	def rec_event(self, rec, event):
		for job in self.active:
			if job.rec is None:
				continue
			try:
				matches = job.rec.__deref__() == rec.__deref__()
			except Exception:
				matches = job.rec is rec
			if not matches:
				continue
			if event == iRecordableService.evTunedIn:
				job.tuned_at = time.time()
				job.tuner_no, _params = frontend_of(job.rec)
				job.tuner = chr(ord("A") + job.tuner_no) if job.tuner_no >= 0 else "?"
				job.state = "odczyt strumienia"
				group = job.group
				anchor = group.reservation if group else None
				if anchor and anchor.tuner_no >= 0 and anchor.tuner_no == job.tuner_no:
					self._release(anchor)
			elif event in (iRecordableService.evTuneFailed, iRecordableService.evRecordFailed):
				job.state = "BLAD: brak dostrojenia"
			break

	def _close_readers(self, job):
		for pid in list(getattr(job, "batch_readers", {})):
			self._close_batch_reader(job, pid)
		if hasattr(job, "batch_waiting"):
			job.batch_waiting = []
			job.batch_filter_errors = {}
		for attr in ("pat_reader", "pmt_reader"):
			reader = getattr(job, attr)
			setattr(job, attr, None)
			if reader is not None:
				try:
					reader.close()
				except Exception as e:
					log("Skan parametrow: zamkniecie filtra %s (%s): %s" % (job.name, attr, e))

	def _close_batch_reader(self, job, pid):
		reader = job.batch_readers.pop(pid)
		job.batch_started.pop(pid, None)
		try:
			reader.close()
		except Exception as e:
			log("Skan parametrow: zamkniecie filtra PMT %d (%s): %s" % (pid, job.name, e))

	def _release(self, job):
		self._close_readers(job)
		self.engine.release(job)
		if job in self.active:
			self.active.remove(job)
		if job in self.reservations:
			self.reservations.remove(job)
		group = job.group
		if group:
			if group.current is job:
				group.current = None
			if group.reservation is job:
				group.reservation = None

	def _reserve_transponder(self, job):
		self._close_readers(job)
		if job in self.active:
			self.active.remove(job)
		group = job.group
		group.current = None
		group.reservation = job
		if job not in self.reservations:
			self.reservations.append(job)
		job.state = "rezerwa transpondera"

	def _finish_group_if_idle(self, group):
		if group and not group.pending and not group.current:
			if group.reservation:
				self._release(group.reservation)
			group.done = True

	def _complete_channel(self, job):
		group = job.group
		self._close_readers(job)
		if job in self.active:
			self.active.remove(job)
		if group.current is job:
			group.current = None
		if group.reservation and group.reservation is not job:
			self._release(group.reservation)
		if group.pending:
			self._reserve_transponder(job)
		else:
			self._release(job)
			group.done = True

	def _handle_failure(self, job, reason):
		if getattr(job, "borrowed", False):
			log("Skan parametrow podczas EPG: %s: %s" % (job.name, reason))
			self._end_borrowed(job)
			return
		if getattr(job, "batch_members", None):
			self._fallback_batch(job, reason)
			return
		group = job.group
		self._release(job)
		job.state = "BLAD: " + reason
		self.errors.append({"ref": job.ref, "name": job.name, "error": reason})
		self.failed_jobs.append(job)
		log("Skan parametrow: BLAD %s: %s" % (job.name, reason))
		self._finish_group_if_idle(group)

	def _start(self, job, shared):
		job.batch_members = []
		if not job.individual and job.group:
			members = [member for member in job.group.pending if not member.individual]
			if members:
				job.batch_members = [job] + members
		try:
			rec = NavigationInstance.instance.recordService(eServiceReference(job.ref))
		except Exception as e:
			log("Skan parametrow: recordService %s: %s" % (job.name, e))
			return False
		if rec is None:
			return False
		job.rec = rec
		job.t0 = time.time()
		job.state = "strojenie"
		job.shared = shared
		job.tuner_no = -1
		self.active.append(job)
		self.engine.active.append(job)
		if job.group:
			if job.group.current is None and job.group.reservation is None:
				job.group.started_at = job.t0
			job.group.current = job
		self.last_tune = time.time()
		self.engine.last_tune = self.last_tune
		try:
			rec.prepareStreaming()
			rec.start()
		except Exception as e:
			log("Skan parametrow: start %s: %s" % (job.name, e))
			self._release(job)
			return False
		return True

	def _begin_capture(self, job):
		try:
			stream = job.rec.stream()
			data = stream and stream.getStreamingData()
			if not isinstance(data, dict) or not data:
				if getattr(job, "borrowed", False) or time.time() - job.tuned_at < STREAM_DATA_WAIT:
					return
				raise ValueError("Enigma2 nie udostepnila danych strumienia")
			if data.get("adapter") is None or data.get("demux") is None:
				if getattr(job, "borrowed", False) or time.time() - job.tuned_at < STREAM_DATA_WAIT:
					return
				raise ValueError("Enigma2 nie udostepnila adaptera/demuksera")
			job.adapter = int(data["adapter"])
			job.demux = int(data["demux"])
			job.pat_section = None
			job.pmt_section = None
			job.pmt_pid = -1
			for member in job.batch_members:
				member.pat_section = None
				member.pmt_section = None
				member.pmt_pid = -1
			job.pat_reader = channelparams.SectionReader(job.adapter, job.demux, 0, 0x00)
			job.state = "zbieranie PAT/PMT"
			job.tuned_at = time.time()
			job.capture_started_at = job.tuned_at
		except Exception as e:
			self._handle_failure(job, "odczyt PID/demux: %s" % e)

	def _poll_capture(self, job):
		if job.batch_members:
			self._poll_batch(job)
			return
		if time.time() - job.tuned_at > 20:
			self._handle_failure(job, "timeout odczytu PAT/PMT")
			return
		try:
			if job.pat_section is None:
				section = job.pat_reader.read()
				if section:
					sid = int(job.ref.split(":")[3], 16)
					if len(section) < 5 or (section[3] << 8 | section[4]) != int(job.ref.split(":")[4], 16):
						return
					pmt_pid = channelparams.parse_pat(section, sid)
					if pmt_pid is not None:
						job.pmt_pid = pmt_pid
						job.pmt_reader = channelparams.SectionReader(job.adapter, job.demux, pmt_pid, 0x02)
						job.pat_section = section
			if job.pat_section is not None and job.pmt_section is None:
				section = job.pmt_reader.read()
				if section:
					sid = int(job.ref.split(":")[3], 16)
					if len(section) >= 5 and (section[3] << 8 | section[4]) != sid:
						return
					pmt = channelparams.parse_pmt(section, sid)
					self._save_channel(job, pmt)
					self._complete_channel(job)
		except (ValueError, KeyError, TypeError, OSError, IndexError) as e:
			self._handle_failure(job, "odczyt PAT/PMT: %s" % e)

	def _fallback_batch(self, anchor, reason):
		if getattr(anchor, "borrowed", False):
			log("Skan parametrow podczas EPG: %s - %s; brakujace kanaly pozniej" % (anchor.name, reason))
			self._end_borrowed(anchor)
			return
		members = list(anchor.batch_members)
		anchor.batch_members = []
		group = anchor.group
		self._release(anchor)
		for member in members:
			if member.ref in self.scanned_refs:
				member.state = "gotowe"
				continue
			member.individual = True
			member.state = "czeka (osobno)"
			if member not in group.pending:
				group.pending.insert(0, member)
			if member not in self.queue:
				self.queue.insert(0, member)
		log("Skan parametrow: partia %s - %s; brakujace kanaly osobno" % (anchor.name, reason))
		self._finish_group_if_idle(group)

	def _poll_batch(self, anchor):
		borrowed = getattr(anchor, "borrowed", False)
		if not borrowed and time.time() - anchor.capture_started_at >= BATCH_DATA_WAIT:
			self._fallback_batch(anchor, "timeout PAT/PMT")
			return
		try:
			for _ in range(BATCH_SECTIONS_PER_TICK):
				section = anchor.pat_reader.read()
				if not section:
					break
				entries = channelparams.parse_pat_entries(section)
				tsid = (section[3] << 8) | section[4]
				for member in anchor.batch_members:
					if not borrowed and member.ref in self.scanned_refs:
						continue
					fields = member.ref.split(":")
					sid = int(fields[3], 16)
					if int(fields[4], 16) != tsid or sid not in entries:
						continue
					pid = entries[sid]
					if member.pat_section is not None and member.pmt_pid != pid:
						if not borrowed:
							raise ValueError("zmieniony PID PMT")
						self.scanned_refs.discard(member.ref)
						if member not in self.queue:
							self.queue.append(member)
					member.pat_section = section
					member.pmt_pid = pid
					member.capture_started_at = anchor.capture_started_at
					if pid not in anchor.batch_readers and pid not in anchor.batch_waiting:
						anchor.batch_waiting.append(pid)
			while anchor.batch_waiting and len(anchor.batch_readers) < BATCH_MAX_PMT_FILTERS:
				pid = anchor.batch_waiting.pop(0)
				try:
					anchor.batch_readers[pid] = channelparams.SectionReader(anchor.adapter, anchor.demux, pid, 0x02)
					anchor.batch_started[pid] = time.time()
				except OSError as e:
					anchor.batch_waiting.append(pid)
					message = str(e)
					if anchor.batch_filter_errors.get(pid) != message:
						log("Skan parametrow: filtr PMT %d (%s): %s" % (pid, anchor.name, message))
						anchor.batch_filter_errors[pid] = message
					break
			for pid, reader in list(anchor.batch_readers.items()):
				try:
					for _ in range(BATCH_SECTIONS_PER_TICK):
						section = reader.read()
						if not section:
							break
						if len(section) < 5:
							raise ValueError("Niepelna sekcja PMT")
						sid = (section[3] << 8) | section[4]
						matches = [member for member in anchor.batch_members
							if (borrowed or member.ref not in self.scanned_refs) and member.pmt_pid == pid
							and int(member.ref.split(":")[3], 16) == sid]
						if not matches:
							continue
						pmt = channelparams.parse_pmt(section, sid)
						for member in matches:
							old = self.results.get(member.ref, {})
							if borrowed and member.ref in self.scanned_refs and \
									old.get("section") == base64.b64encode(pmt["section"]).decode("ascii") and \
									old.get("pat") == base64.b64encode(member.pat_section).decode("ascii") and \
									old.get("pmtPid") == member.pmt_pid:
								continue
							self._save_channel(member, pmt)
							if member is anchor:
								anchor.state = "zbieranie PAT/PMT"
							if member in anchor.group.pending:
								anchor.group.pending.remove(member)
							if member in self.queue:
								self.queue.remove(member)
				except (ValueError, OSError) as e:
					message = str(e)
					if anchor.batch_filter_errors.get(pid) != message:
						log("Skan parametrow: odczyt PMT %d (%s): %s" % (pid, anchor.name, message))
						anchor.batch_filter_errors[pid] = message
					self._close_batch_reader(anchor, pid)
					if pid not in anchor.batch_waiting:
						anchor.batch_waiting.append(pid)
					continue
				unresolved = any((borrowed or member.ref not in self.scanned_refs) and member.pmt_pid == pid
					for member in anchor.batch_members)
				if not unresolved or (anchor.batch_waiting and time.time() - anchor.batch_started[pid] >= BATCH_PMT_SLICE):
					self._close_batch_reader(anchor, pid)
					if unresolved:
						anchor.batch_waiting.append(pid)
			if not borrowed and all(member.ref in self.scanned_refs for member in anchor.batch_members):
				anchor.state = "gotowe"
				anchor.batch_members = []
				group = anchor.group
				self._release(anchor)
				self._finish_group_if_idle(group)
		except (ValueError, KeyError, TypeError, OSError, IndexError) as e:
			self._fallback_batch(anchor, "odczyt PAT/PMT: %s" % e)

	def _save_channel(self, job, pmt):
		streams = pmt["streams"]
		selected = channelparams.choose_audio(streams, preferred_languages())
		old = self.results.get(job.ref, {})
		old_pid = old.get("selectedAudioPid")
		old_source = old.get("selectionSource")
		old_available = old_source != "default" and (old.get("selectedConfirmed", False) or old_source == "manual") \
			and old_pid is not None and any(s["pid"] == old_pid for s in streams if s["kind"] == "audio")
		first_audio = next((stream for stream in streams if stream["kind"] == "audio"), None)
		default = selected or first_audio
		selected_pid = old_pid if old_available else (default["pid"] if default else None)
		self.results[job.ref] = {
			"ref": job.ref, "name": job.name, "sid": pmt["sid"], "pmtPid": job.pmt_pid,
			"pcrPid": pmt["pcr_pid"], "streams": streams, "section": base64.b64encode(pmt["section"]).decode("ascii"),
			"pat": base64.b64encode(job.pat_section).decode("ascii"),
			"preferredAudioPid": selected["pid"] if selected else None, "selectedAudioPid": selected_pid,
			"selectedConfirmed": selected_pid is not None,
			"scannedAt": int(time.time()),
			"readDuration": round(max(0, time.time() - getattr(job, "capture_started_at", 0)), 2)
				if getattr(job, "capture_started_at", 0) else 0,
		}
		if old_available:
			for field in ("selectionSource", "selectedAt"):
				if field in old:
					self.results[job.ref][field] = old[field]
		elif selected_pid is not None:
			self.results[job.ref]["selectionSource"] = "default"
		self.scanned_refs.add(job.ref)
		job.state = "gotowe"

	def _sync_after_scan(self, report):
		common_mode = getattr(getattr(cfg, "cp_sync_common", None), "value", "off")
		device_mode = getattr(getattr(cfg, "cp_sync_devices", None), "value", "off")
		if not self.scanned_refs or (common_mode == "off" and device_mode == "off"):
			return False
		from . import channelparamsstore as store
		results = {ref: self.results[ref] for ref in self.scanned_refs if ref in self.results}
		report["sync"] = []
		for target, mode in (("common", common_mode), ("clients", device_mode)):
			if mode == "off":
				continue
			try:
				if target == "common":
					message, conflicts = store.add_common_defaults(results, mode=mode)
				else:
					message, conflicts = store.apply_to_devices(results, store.device_profiles(), mode=mode)
				if conflicts:
					message += " Pominiete konflikty: " + ", ".join(conflicts)
				report["sync"].append(message)
				log("Synchronizacja PAT/PMT %s: %s" % (target, message))
			except Exception as e:
				message = "Synchronizacja %s nieudana: %s" % (target, e)
				report["errors"].append({"name": "E2Stream " + target, "error": message})
				log(message)
		return True

	def _finish(self):
		self.timer.stop()
		try:
			NavigationInstance.instance.record_event.remove(self.rec_event)
		except Exception:
			pass
		finished = int(time.time())
		scanned_results = [self.results[ref] for ref in self.scanned_refs if ref in self.results]
		read_times = [result.get("readDuration", 0) for result in scanned_results if result.get("readDuration", 0) > 0]
		audio_channels = [result for result in scanned_results if any(
			stream.get("kind") == "audio" for stream in result.get("streams", []))]
		audio_tracks = sum(1 for result in scanned_results for stream in result.get("streams", [])
			if stream.get("kind") == "audio")
		selected_audio = sum(1 for result in scanned_results if any(
			stream.get("kind") == "audio" and stream.get("pid") == result.get("selectedAudioPid")
			for stream in result.get("streams", [])))
		report = {"started": self.started, "finished": finished, "duration": max(0, finished - self.started),
			"scanned": len(scanned_results),
			"audio_channels": len(audio_channels), "audio_tracks": audio_tracks, "selected_audio": selected_audio,
			"read_average": round(sum(read_times) / len(read_times), 2) if read_times else 0,
			"read_max": round(max(read_times), 2) if read_times else 0,
			"errors": self.errors, "unsupported": self.unsupported}
		last_scan = report
		if self.refs is not None and self.last_scan:
			last_scan = dict(self.last_scan)
			retry_errors = {row["ref"]: row for row in self.errors if row.get("ref")}
			errors = []
			for row in self.last_scan.get("errors", []):
				ref = row.get("ref")
				if ref in self.scanned_refs:
					continue
				errors.append(retry_errors.pop(ref, row))
			errors.extend(retry_errors.values())
			last_scan["errors"] = errors
		saved = False
		try:
			self._merge_latest_results()
			data = {"updated": int(time.time()), "results": self.results, "last_scan": last_scan}
			save_results(data)
			saved = True
		except Exception as e:
			log("Skan parametrow: zapis wynikow nieudany: %s" % e)
			report["errors"] = list(self.errors) + [{"name": "wyniki", "error": "Zapis wynikow nieudany: %s" % e}]
		if saved:
			try:
				synced = self._sync_after_scan(report)
			except Exception as e:
				report["errors"].append({"name": "E2Stream", "error": "Synchronizacja nieudana: %s" % e})
				log(report["errors"][-1]["error"])
				synced = True
			if synced:
				if last_scan is not report:
					last_scan["errors"].extend(row for row in report["errors"] if not row.get("ref"))
					last_scan["sync"] = report.get("sync", [])
				try:
					save_results(data)
				except Exception as e:
					log("Skan parametrow: zapis raportu synchronizacji nieudany: %s" % e)
					report["errors"].append({"name": "wyniki", "error": "Zapis raportu synchronizacji nieudany: %s" % e})
		log("Skan parametrow zakonczony: %d kanalow, %d z audio, %d sciezek audio, %d wyborow, %d bledow, %d nieobslugiwanych" %
			(report["scanned"], report["audio_channels"], report["audio_tracks"], report["selected_audio"],
				len(report["errors"]), len(self.unsupported)))
		engine = self.engine
		engine.channel_params_scan = None
		if self.done:
			self.done(report)

	def _yield_group(self, group, now):
		current = group.current
		if current:
			members = list(current.batch_members)
			current.batch_members = []
			self._release(current)
			if not members or current.ref not in self.scanned_refs:
				current.state = "czeka na tuner"
				current.tuner_no = -1
				current.tuner = "-"
				current.not_before = now + NO_TUNER_WAIT
				group.pending.insert(0, current)
				self.queue.insert(0, current)
			else:
				current.state = "gotowe"
		if group.reservation:
			self._release(group.reservation)
		group.not_before = now + NO_TUNER_WAIT

	def tick(self):
		if self.cancelled:
			return
		now = time.time()
		for job in list(self.active):
			if job.state == "BLAD: brak dostrojenia":
				self._handle_failure(job, "brak dostrojenia")
			elif job.state == "strojenie" and now - job.t0 > TUNE_TIMEOUT:
				self._handle_failure(job, "timeout strojenia")
			elif job.state == "odczyt strumienia":
				self._begin_capture(job)
			elif job.state == "zbieranie PAT/PMT":
				self._poll_capture(job)

		engine = self.engine
		engine.scan_usage()
		engine.timer_hold = engine.upcoming_recordings()
		deficit = engine.eff_reserve() - engine.free_tuners()
		if deficit > 0:
			own_groups = []
			for group in self.groups:
				job = group.current or group.reservation
				if job and group_owns_tuner(group):
					own_groups.append(group)
			own_groups.sort(key=lambda group: group.started_at, reverse=True)
			for group in own_groups[:deficit]:
				job = group.current or group.reservation
				log("Skan parametrow: zwalniam tuner dla nagrania: %s" % job.name)
				self._yield_group(group, now)

		own_groups = len([group for group in self.groups if group_owns_tuner(group)])
		if now - self.last_tune >= TUNE_GAP:
			job = None
			group = None
			shared = False
			ready_groups = [item for item in self.groups if not item.done and not item.current and item.pending
				and item.not_before <= now and item.pending[0].not_before <= now]
			new_groups = [item for item in ready_groups if item.reservation is None]
			continuing_groups = [item for item in ready_groups if item.reservation is not None]
			for candidate_group in new_groups + continuing_groups:
				candidate = candidate_group.pending[0]
				if candidate_group.reservation:
					group, job, shared = candidate_group, candidate, True
					break
				if engine.on_busy_tp(candidate):
					group, job, shared = candidate_group, candidate, True
					break
				if not engine.timer_hold and own_groups < engine.max_parallel \
						and engine.free_tuners() - engine.eff_reserve() > 0:
					group, job = candidate_group, candidate
					break
			if job is not None:
				group.pending.remove(job)
				self.queue.remove(job)
				if not self._start(job, shared):
					self._handle_failure(job, "nie mozna zarezerwowac serwisu")
		if not self.queue and not self.active and not self.final_retry_started:
			self.final_retry_started = True
			if cfg.cp_retry_failed.value and self.failed_jobs:
				for job in self.failed_jobs:
					job.individual = True
					job.not_before = 0
					job.state = "czeka (ostatnia proba)"
					job.group.done = False
					job.group.pending.append(job)
					self.queue.append(job)
				self.errors = []
				self.failed_jobs = []
		if not self.queue and not self.active:
			for group in self.groups:
				self._finish_group_if_idle(group)
		if not self.queue and not self.active and not self.reservations:
			return self._finish()
		if now - self.last_notify >= 1:
			self.last_notify = now
			engine.notify()
		self.timer.start(250, False)


def scan_due(now=None):
	if cfg.cp_auto_in_progress.value:
		return True
	last = int(cfg.cp_last_run.value or 0)
	now = int(time.time() if now is None else now)
	return last <= 0 or now - last >= int(cfg.cp_interval.value) * 86400


def _start_scan(engine, done, auto=False, woke=False, refs=None, scan=None):
	pending = getattr(engine, "epg_params_scan", None)
	if engine.running or engine.phase == "channelparams" or (pending is not None and pending is not scan):
		return False

	def finished(report):
		engine.phase = ""
		engine.running = False
		engine.finished = True
		engine.t_end = time.time()
		engine.channelparams_report = report
		engine.summary = "Skan parametrow: %d kanalow, %d bledow" % (report["scanned"], len(report["errors"]))
		engine.note = engine.summary
		if report["errors"]:
			engine.note += "; " + report["errors"][-1].get("error", "")
		elif report.get("sync"):
			engine.note += "; " + "; ".join(report["sync"])
		if not auto and refs is None:
			cfg.cp_last_run.value = int(report["finished"])
			save_cfg()
		engine.notify()
		try:
			if done:
				done(report)
		finally:
			engine.channelparams_only = False

	if scan is None:
		scan = ChannelParamsScan(engine, finished, refs=refs)
	else:
		scan.done = finished
	engine.running = True
	engine.finished = False
	engine.channelparams_report = None
	engine.auto = auto
	engine.topup = False
	engine.woke = woke
	engine.channelparams_only = True
	engine.channelparams_auto = auto
	engine.max_parallel = int(cfg.max_parallel.value)
	engine.total_tuners = configured_tuners()
	engine.reserve = max(int(cfg.reserve.value), min(2, max(0, engine.total_tuners - 1)))
	engine.t_start = time.time()
	engine.note = ""
	try:
		started = scan.start()
	except Exception:
		scan.cancel()
		engine.phase = ""
		engine.running = False
		engine.finished = True
		engine.channelparams_only = False
		engine.channelparams_auto = False
		engine.t_end = time.time()
		engine.notify()
		raise
	if not started:
		engine.running = False
		engine.finished = True
		engine.channelparams_only = False
		engine.channelparams_auto = False
		return False
	engine.notify()
	return True


def start_manual(engine, done=None, refs=None, scan=None):
	return _start_scan(engine, done, refs=refs, scan=scan)


def start_scheduled(engine, slot=None, woke=False, done=None, scan=None):
	pending = getattr(engine, "epg_params_scan", None)
	if engine.running or engine.phase == "channelparams" or (pending is not None and pending is not scan):
		return False
	cfg.cp_auto_in_progress.value = True
	save_cfg()

	def finished(report):
		cfg.cp_auto_in_progress.value = False
		cfg.cp_last_run.value = int(report["finished"])
		save_cfg()
		engine.auto = True
		engine.channelparams_auto = False
		if done:
			try:
				done(report)
			finally:
				engine.auto = False
				engine.woke = False
				engine.channelparams_only = False
			return
		engine.maybe_deep_standby()
		engine.auto = False
		engine.woke = False
		engine.channelparams_only = False

	try:
		started = _start_scan(engine, finished, auto=True, woke=woke, scan=scan)
	except Exception:
		cfg.cp_auto_in_progress.value = False
		save_cfg()
		raise
	if not started:
		cfg.cp_auto_in_progress.value = False
		save_cfg()
	return started
