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
from .common import CHANNEL_PARAMS_FILE, CP_SCHED_DAYS, MAX_RETRIES, NO_TUNER_WAIT, TUNE_GAP, TUNE_TIMEOUT, \
	bouquet_channels, cfg, configured_tuners, frontend_of, log, same_tp, selected_bouquets, service_name, tp_params


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
		self.adapter = -1
		self.demux = -1
		self.pmt_pid = -1
		self.pat_reader = None
		self.pmt_reader = None
		self.pat_section = None
		self.pmt_section = None


class ChannelParamsScan(object):
	"""Uses ScanEngine's tuner accounting; it never clears or reads/writes EPG data."""

	def __init__(self, engine, done=None):
		self.engine = engine
		self.done = done
		self.queue = []
		self.active = []
		self.results = load_results().get("results", {})
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
		rows = bouquet_channels(selected_bouquets())
		self.unsupported = [row for row in rows if not row["supported"]]
		self.queue = [ChannelJob(row) for row in rows if row["supported"]]
		if not self.queue and not self.unsupported:
			return False
		self.started = int(time.time())
		self.engine.phase = "channelparams"
		self.engine.channel_params_scan = self
		log("Skan parametrow: %d kanalow DVB, %d nieobslugiwanych z bukietow; bez zmian w EPG" %
			(len(self.queue), len(self.unsupported)))
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
		for job in list(self.active):
			self._close_readers(job)
			self.engine.release(job)
			job.state = "przerwany"
		self.active = []
		self.queue = []
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

	def _retry_or_fail(self, job, reason):
		self._release(job)
		job.retries += 1
		if job.retries < MAX_RETRIES:
			job.state = "czeka (ponownie)"
			job.not_before = time.time() + 10 * job.retries
			self.queue.append(job)
			log("Skan parametrow: %s - %s; ponowna proba" % (job.name, reason))
		else:
			job.state = "BLAD: " + reason
			self.errors.append({"ref": job.ref, "name": job.name, "error": reason})
			log("Skan parametrow: BLAD %s: %s" % (job.name, reason))

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
					self._release(job)
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
		}
		job.state = "gotowe"

	def _finish(self):
		self.timer.stop()
		try:
			NavigationInstance.instance.record_event.remove(self.rec_event)
		except Exception:
			pass
		report = {"started": self.started, "finished": int(time.time()),
			"scanned": len([x for x in self.results.values() if x.get("scannedAt", 0) >= self.started]),
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
			own = sorted((j for j in self.active if not j.shared), key=lambda x: x.t0, reverse=True)
			for job in own[:deficit]:
				log("Skan parametrow: zwalniam tuner dla nagrania: %s" % job.name)
				self._release(job)
				job.state = "czeka na tuner"
				job.tuner_no = -1
				job.tuner = "-"
				job.not_before = now + NO_TUNER_WAIT
				self.queue.insert(0, job)

		ready = [job for job in self.queue if job.not_before <= now]
		own_active = len([job for job in self.active if not job.shared])
		tuning = any(job.state == "strojenie" for job in self.active)
		if ready and not tuning and now - self.last_tune >= TUNE_GAP:
			job = None
			shared = False
			for candidate in ready:
				if any(same_tp(candidate.tp, active.tp) for active in self.active):
					job, shared = candidate, True
					break
				if engine.on_busy_tp(candidate):
					job, shared = candidate, True
					break
			if job is None and not engine.timer_hold and own_active < engine.max_parallel \
					and engine.free_tuners() - engine.eff_reserve() > 0:
				job = ready[0]
			if job is not None:
				self.queue.remove(job)
				if not self._start(job, shared):
					self._retry_or_fail(job, "nie mozna zarezerwowac serwisu")
		if not self.queue and not self.active:
			return self._finish()
		if now - self.last_notify >= 1:
			self.last_notify = now
			engine.notify()
		self.timer.start(250, False)


def scheduled_day_enabled(timestamp):
	try:
		import time as _time
		return bool(cfg.cp_sched.value and CP_SCHED_DAYS[_time.localtime(timestamp).tm_wday].value)
	except Exception:
		return False


def start_manual(engine, done=None):
	if engine.running or engine.phase == "channelparams":
		return False

	def finished(report):
		engine.phase = ""
		engine.running = False
		engine.finished = True
		engine.t_end = time.time()
		engine.summary = "Skan parametrow: %d kanalow, %d bledow" % (report["scanned"], len(report["errors"]))
		engine.note = engine.summary
		engine.notify()
		if done:
			done(report)

	scan = ChannelParamsScan(engine, finished)
	engine.running = True
	engine.finished = False
	engine.auto = False
	engine.topup = False
	engine.max_parallel = int(cfg.max_parallel.value)
	engine.reserve = int(cfg.reserve.value)
	engine.total_tuners = configured_tuners()
	engine.t_start = time.time()
	engine.note = ""
	try:
		started = scan.start()
	except Exception:
		scan.cancel()
		engine.phase = ""
		engine.running = False
		engine.finished = True
		engine.t_end = time.time()
		engine.notify()
		raise
	if not started:
		engine.running = False
		engine.finished = True
		return False
	engine.notify()
	return True
