# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
"""Per-service PAT/PMT scanner, using the existing Enigma2 recording-service tuner path."""

import base64
import json
import os
import time

from enigma import eServiceReference, eTimer, iRecordableService
import NavigationInstance

from . import channelparams
from .common import CHANNEL_PARAMS_FILE, MAX_RETRIES, NO_TUNER_WAIT, TUNE_GAP, TUNE_TIMEOUT, \
	channel_params_bouquet_channels, cfg, configured_tuners, frontend_of, log, same_tp, selected_bouquets, service_name, tp_key, tp_params, save_cfg


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
		self.retries = 0
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
	if job.tp is not None and group.tp is not None:
		return same_tp(job.tp, group.tp)
	return job.tp_key is not None and job.tp_key == group.tp_key


def group_owns_tuner(group):
	return any(job is not None and not job.shared for job in (group.current, group.reservation))


class ChannelParamsScan(object):
	"""Uses ScanEngine's tuner accounting; it never clears or reads/writes EPG data."""

	def __init__(self, engine, done=None):
		self.engine = engine
		self.done = done
		self.queue = []
		self.active = []
		self.groups = []
		self.reservations = []
		self.results = load_results().get("results", {})
		self.scanned_refs = set()
		self.errors = []
		self.unsupported = []
		self.started = 0
		self.last_tune = 0
		self.last_notify = 0
		self.cancelled = False
		self.timer = eTimer()
		self.timer.callback.append(self.tick)

	def start(self):
		if self.engine.phase == "channelparams":
			return False
		rows = channel_params_bouquet_channels(selected_bouquets())
		self.unsupported = [row for row in rows if not row["supported"]]
		self.queue = [ChannelJob(row) for row in rows if row["supported"]]
		self.groups = group_channel_jobs(self.queue, bool(cfg.cp_group_tp.value))
		if not self.queue and not self.unsupported:
			return False
		self.started = int(time.time())
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

	def cancel(self):
		self.cancelled = True
		self.timer.stop()
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
		for attr in ("pat_reader", "pmt_reader"):
			reader = getattr(job, attr)
			setattr(job, attr, None)
			if reader is not None:
				try:
					reader.close()
				except OSError as e:
					log("Skan parametrow: zamkniecie filtra %s (%s): %s" % (job.name, attr, e))

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

	def _retry_or_fail(self, job, reason):
		group = job.group
		self._release(job)
		job.retries += 1
		if job.retries < MAX_RETRIES:
			job.state = "czeka (ponownie)"
			job.not_before = time.time() + 10 * job.retries
			if group:
				group.pending.append(job)
			self.queue.append(job)
			log("Skan parametrow: %s - %s; ponowna proba" % (job.name, reason))
		else:
			job.state = "BLAD: " + reason
			self.errors.append({"ref": job.ref, "name": job.name, "error": reason})
			log("Skan parametrow: BLAD %s: %s" % (job.name, reason))
			self._finish_group_if_idle(group)

	def _start(self, job, shared):
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
				raise ValueError("Enigma2 nie udostepnila danych strumienia")
			job.adapter = int(data["adapter"])
			job.demux = int(data["demux"])
			pmt_pids = [int(pid) for pid, kind in data.get("pids", []) if kind == "pmt"]
			if len(pmt_pids) != 1:
				raise ValueError("Brak jednoznacznego PID PMT w danych Enigma2")
			job.pmt_pid = pmt_pids[0]
			job.pat_reader = channelparams.SectionReader(job.adapter, job.demux, 0, 0x00)
			job.pmt_reader = channelparams.SectionReader(job.adapter, job.demux, job.pmt_pid, 0x02)
			job.state = "zbieranie PAT/PMT"
			job.tuned_at = time.time()
			job.capture_started_at = job.tuned_at
		except Exception as e:
			self._retry_or_fail(job, "odczyt PID/demux: %s" % e)

	def _poll_capture(self, job):
		if time.time() - job.tuned_at > 20:
			self._retry_or_fail(job, "timeout odczytu PAT/PMT")
			return
		try:
			if job.pat_section is None:
				section = job.pat_reader.read()
				if section:
					sid = int(job.ref.split(":")[3], 16)
					pmt_pid = channelparams.parse_pat(section, sid)
					if pmt_pid is not None:
						if pmt_pid != job.pmt_pid:
							raise ValueError("PID PMT z PAT rozni sie od danych Enigma2")
						job.pat_section = section
			if job.pat_section is not None and job.pmt_section is None:
				section = job.pmt_reader.read()
				if section:
					sid = int(job.ref.split(":")[3], 16)
					pmt = channelparams.parse_pmt(section, sid)
					self._save_channel(job, pmt)
					self._complete_channel(job)
		except (ValueError, KeyError, TypeError, OSError) as e:
			self._retry_or_fail(job, "odczyt PAT/PMT: %s" % e)

	def _save_channel(self, job, pmt):
		streams = pmt["streams"]
		selected = channelparams.choose_audio(streams, preferred_languages())
		old = self.results.get(job.ref, {})
		selected_pid = old.get("selectedAudioPid")
		if selected_pid not in [s["pid"] for s in streams if s["kind"] == "audio"]:
			selected_pid = selected["pid"] if selected else None
		self.results[job.ref] = {
			"ref": job.ref, "name": job.name, "sid": pmt["sid"], "pmtPid": job.pmt_pid,
			"pcrPid": pmt["pcr_pid"], "streams": streams, "section": base64.b64encode(pmt["section"]).decode("ascii"),
			"pat": base64.b64encode(job.pat_section).decode("ascii"),
			"preferredAudioPid": selected["pid"] if selected else None, "selectedAudioPid": selected_pid,
			"selectedConfirmed": bool(old.get("selectedConfirmed", False)) and selected_pid is not None,
			"scannedAt": int(time.time()),
			"readDuration": round(max(0, time.time() - getattr(job, "capture_started_at", 0)), 2)
				if getattr(job, "capture_started_at", 0) else 0,
		}
		self.scanned_refs.add(job.ref)
		job.state = "gotowe"

	def _finish(self):
		self.timer.stop()
		try:
			NavigationInstance.instance.record_event.remove(self.rec_event)
		except Exception:
			pass
		finished = int(time.time())
		scanned_results = [self.results[ref] for ref in self.scanned_refs if ref in self.results]
		read_times = [result.get("readDuration", 0) for result in scanned_results if result.get("readDuration", 0) > 0]
		report = {"started": self.started, "finished": finished, "duration": max(0, finished - self.started),
			"scanned": len(scanned_results),
			"read_average": round(sum(read_times) / len(read_times), 2) if read_times else 0,
			"read_max": round(max(read_times), 2) if read_times else 0,
			"errors": self.errors, "unsupported": self.unsupported}
		data = {
			"updated": int(time.time()), "results": self.results,
			"last_scan": report,
		}
		try:
			save_results(data)
		except Exception as e:
			log("Skan parametrow: zapis wynikow nieudany: %s" % e)
			report["errors"] = list(self.errors) + [{"name": "wyniki", "error": "Zapis wynikow nieudany: %s" % e}]
		log("Skan parametrow zakonczony: %d kanalow, %d bledow, %d nieobslugiwanych" %
			(report["scanned"], len(report["errors"]), len(self.unsupported)))
		engine = self.engine
		engine.channel_params_scan = None
		if self.done:
			self.done(report)

	def _yield_group(self, group, now):
		current = group.current
		if current:
			self._release(current)
			current.state = "czeka na tuner"
			current.tuner_no = -1
			current.tuner = "-"
			current.not_before = now + NO_TUNER_WAIT
			group.pending.insert(0, current)
			self.queue.insert(0, current)
		if group.reservation:
			self._release(group.reservation)
		group.not_before = now + NO_TUNER_WAIT

	def tick(self):
		if self.cancelled:
			return
		now = time.time()
		for job in list(self.active):
			if job.state == "BLAD: brak dostrojenia":
				self._retry_or_fail(job, "brak dostrojenia")
			elif job.state == "strojenie" and now - job.t0 > TUNE_TIMEOUT:
				self._retry_or_fail(job, "timeout strojenia")
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
					self._retry_or_fail(job, "nie mozna zarezerwowac serwisu")
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


def _start_scan(engine, done, auto=False, woke=False):
	if engine.running or engine.phase == "channelparams":
		return False

	def finished(report):
		engine.phase = ""
		engine.running = False
		engine.finished = True
		engine.t_end = time.time()
		engine.channelparams_report = report
		engine.summary = "Skan parametrow: %d kanalow, %d bledow" % (report["scanned"], len(report["errors"]))
		engine.note = engine.summary
		engine.notify()
		try:
			if done:
				done(report)
		finally:
			engine.channelparams_only = False

	scan = ChannelParamsScan(engine, finished)
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


def start_manual(engine, done=None):
	return _start_scan(engine, done)


def start_scheduled(engine, slot=None, woke=False, done=None):
	if engine.running or engine.phase == "channelparams":
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
		started = _start_scan(engine, finished, auto=True, woke=woke)
	except Exception:
		cfg.cp_auto_in_progress.value = False
		save_cfg()
		raise
	if not started:
		cfg.cp_auto_in_progress.value = False
		save_cfg()
	return started
