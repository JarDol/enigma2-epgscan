# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - silnik skanowania dzialajacy w tle (niezalezny od ekranu)

import os
import time

from enigma import eTimer, eServiceReference, eEPGCache, iRecordableService
import NavigationInstance

from .common import ensure_eit, restore_eit, pause_eit_for_xmltv, resume_eit_after_xmltv, cfg, VERSION, rotate_logs, log, save_cfg, build_jobs, count_detail, configured_tuners, \
	frontend_of, same_tp, flush_all, flush_refs, clears_now, copy_backup, changes_since_last, remember_scan, \
	update_tp_stats, update_topup_stats, horizons, apply_skip, epg_stats, stats_text, all_refs, xmltv_targets, log_dups, \
	profile_default, profile_text, xmltv_in_profile, TUNE_TIMEOUT, MAX_RETRIES, COUNT_EVERY, TUNE_GAP, NO_TUNER_WAIT, USAGE_EVERY, TIMER_AHEAD

try:
	from enigma import eDVBResourceManager
except Exception:
	eDVBResourceManager = None

_engine = None


def get_engine(session=None):
	global _engine
	if _engine is None:
		_engine = ScanEngine()
	if session is not None:
		_engine.session = session
	return _engine


HANG_FAILS = 3      # tyle nieudanych strojen z rzedu na jednym tunerze = tuner zawieszony


class ScanEngine(object):
	def __init__(self):
		self.session = None
		self.jobs = []
		self.queue = []
		self.active = []
		self.max_parallel = cfg.max_parallel.value
		self.reserve = cfg.reserve.value
		self.total_tuners = configured_tuners()
		self.mask = None
		self.used = set()
		self.other_used = set()
		self.used_tps = []
		self.running = False
		self.finished = False
		self.t_start = 0
		self.t_end = 0
		self.last_tune = 0
		self.last_usage = 0
		self.note = ""
		self.changes = ""
		self.phase = ""              # "" / "scan" / "xmltv"
		self.xmltv = None
		self.xmltv_resume_eit = False
		self.summary = ""
		self.timer_hold = 0          # tunery trzymane wolne dla nagran, ktore zaraz rusza
		self.auto = False            # start z harmonogramu
		self.topup = False           # doczytywanie brakow (wybrane transpondery, bez czyszczenia)
		self.woke = False            # odbiornik wybudzony przez wtyczke z glebokiego czuwania
		self.listeners = []          # funkcje odswiezajace otwarte ekrany
		self.timer = eTimer()
		self.timer.callback.append(self.tick)
		self.bk_timer = eTimer()
		self.bk_timer.callback.append(self.do_backup)
		try:
			eDVBResourceManager.getInstance().frontendUseMaskChanged.get().append(self.mask_changed)
		except Exception:
			pass

	# --- pomocnicze ---
	def notify(self):
		for f in list(self.listeners):
			try:
				f()
			except Exception:
				pass

	def mask_changed(self, mask):
		self.mask = mask
		self.last_usage = 0

	def prepare(self):
		"""Odswieza liste transponderow (gdy skan nie trwa)."""
		if self.running:
			return
		self.jobs = build_jobs()
		self.jobs_partial = False
		self.max_parallel = cfg.max_parallel.value
		self.reserve = cfg.reserve.value
		self.total_tuners = configured_tuners()
		self.changes = changes_since_last(self.jobs)
		self.finished = False
		self.t_start = 0
		self.note = ""
		self.scan_usage()
		self.last_usage = time.time()

	def stats(self):
		done = len([j for j in self.jobs if j.state.startswith("gotowe")])
		err = len([j for j in self.jobs if j.state.startswith("BLAD")])
		end = self.t_end if self.finished else time.time()
		el = int(end - self.t_start) if self.t_start else 0
		return done, err, el

	# --- start/stop ---
	def start(self, auto=False, woke=False, topup_jobs=None, clear=None, force_xmltv=False, local=True, profile=None):
		"""profile: {clear, sat, dek, srv, other, fetch} - z Akcji; None = profil domyslny (OK, harmonogram).
		clear: None = wg profilu, True/False = wymuszenie."""
		if self.running:
			return False
		self.channelparams_report = None
		if topup_jobs is None:
			self.profile = dict(profile or profile_default())
			if not local:
				self.profile["fetch"] = False
			if clear is None:
				clear = clears_now(auto, self.profile["clear"])
			if self.profile.get("sw", "nie") != "nie" and not getattr(self, "_sw_done", False):
				return self.satwatch_stage(dict(auto=auto, woke=woke, clear=clear, force_xmltv=force_xmltv, local=local,
					profile=self.profile))
			if not self.profile["sat"]:
				# bez satelity - sam XMLTV (pobranie wg profilu)
				return self.start_xmltv_only(clear=clear, auto=auto, local=local, profile=self.profile)
		self.force_xmltv = force_xmltv
		if auto:
			self.auto_start_time = cfg.last_auto_run.value or int(time.time())
		if topup_jobs is not None:
			self.jobs = topup_jobs
			self.jobs_partial = True         # lista tylko z transponderami do doczytania
		elif auto or not self.jobs or getattr(self, "jobs_partial", False) or getattr(self, "sw_changed", False):
			self.prepare()                   # pelny skan nigdy na liscie z doczytywania
		if not self.jobs:
			return False
		self.topup = topup_jobs is not None
		self.xmltv_only = False
		if self.topup:
			auto = False
		self.auto = auto
		self.woke = woke
		if auto:
			cfg.auto_in_progress.value = True
			save_cfg()
		if not self.topup and not getattr(self, "_sw_done", False):
			rotate_logs()   # poprzednie logi: epgscan.log.1, .2 ... (tyle, ile kopii EPG, min. 2)
		self.note = ""
		if not self.topup:
			apply_skip(self.jobs)
		for j in self.jobs:
			j.reset()
			if j.skip:
				j.state = "pominiety (brak EPG)"
		self.queue = [j for j in self.jobs if not j.skip]
		self.phase = "scan"
		self.active = []
		self.running = True
		self.finished = False
		self.t_start = time.time()
		if self.topup:
			log("Doczytywanie brakow: %d transponderow: %s" % (len(self.jobs),
				", ".join("%s (%s)" % (j.name, getattr(j, "topup_reason", "")) for j in self.jobs)))
		else:
			log("EPG Scan %s start%s: %d transponderow, %d kanalow z bukietow, %d wszystkich, tunerow %d, rezerwa %d, limit %d, czyszczenie: %s" % (
			VERSION, " (harmonogram)" if auto else "", len(self.jobs), sum(len(j.refs) for j in self.jobs), sum(len(j.all_refs) for j in self.jobs),
			self.total_tuners, self.reserve, self.max_parallel, cfg.clear_mode.value))
		if self.changes and not self.topup:
			log(self.changes)
		self.fail_streak = {}
		self.hung = set()
		self.log_tuners("Tunery na starcie")
		sk = [j.name for j in self.jobs if j.skip]
		if sk:
			log("Pominiete (brak EPG w 2 ostatnich skanach): %s" % ", ".join(sk))
		if not self.topup:
			self.fetch_start()
		self.eit_restore = ensure_eit()
		self.cleared = bool(not self.topup and (clears_now(auto) if clear is None else clear))
		if self.cleared:
			flush_all()
		NavigationInstance.instance.record_event.append(self.rec_event)
		self.timer.start(1000, False)
		self.tick()
		return True

	def stop(self):
		# czerwony "Zatrzymaj": razem ze skanem przerywane jest pobieranie programu na dekoderze
		try:
			from .localrun import local_job
			local_job().cancel()
		except Exception:
			pass
		if not self.running:
			return
		if self.phase == "channelparams":
			scan = getattr(self, "channel_params_scan", None)
			if scan:
				scan.cancel()
			if getattr(self, "channelparams_auto", False):
				cfg.cp_auto_in_progress.value = False
				save_cfg()
			self.channelparams_auto = False
			self.channelparams_only = False
			self.phase = ""
			self.running = False
			self.finished = True
			self.t_end = time.time()
			self.note = "Skan parametrow przerwany"
			self.detach()
			self.notify()
			return
		if self.phase == "satwatch":
			self._sw_cancel = True
			if getattr(self, "_sw_next", None):
				self.sw_gap_timer.stop()
				self._sw_next = None
			log("SatWatch przerwany przez uzytkownika - bez skanu EPG")
			try:
				from .satwatch.sw import runner
				if not runner.abortScan():
					self.sw_finish_cancel()
			except Exception:
				self.sw_finish_cancel()
			return
		if self.phase == "xmltv_wait":
			self.wait_timer.stop()
			log("Czekanie na serwer epgpl przerwane przez uzytkownika - bez importu XMLTV")
			self.complete()
			return
		if self.phase == "xmltv" and self.xmltv:
			log("Import XMLTV przerwany przez uzytkownika")
			self.xmltv.cancel()          # konczy sie przez complete()
			return
		log("Przerwane przez uzytkownika")
		self.clear_auto_flag()
		self.topup = False
		self.phase = ""
		self.timer.stop()
		for j in list(self.active):
			self.stop_job(j, "przerwany")
		self.end_eit()
		self.running = False
		self.finished = True
		self.t_end = time.time()
		self.note = "Skan przerwany"
		self.detach()
		self.notify()

	def clear_auto_flag(self):
		if cfg.auto_in_progress.value:
			cfg.auto_in_progress.value = False
			save_cfg()

	def detach(self):
		try:
			NavigationInstance.instance.record_event.remove(self.rec_event)
		except Exception:
			pass

	# --- tunery ---
	def eff_reserve(self):
		return self.reserve + self.timer_hold

	def upcoming_recordings(self):
		"""Ile nagran z timera ruszy w ciagu TIMER_AHEAD sekund (jeszcze nie nagrywaja)."""
		try:
			now = time.time()
			n = 0
			for t in NavigationInstance.instance.RecordTimer.timer_list:
				if getattr(t, "disabled", False) or getattr(t, "justplay", False):
					continue
				if getattr(t, "state", 0) >= 2:          # juz nagrywa - liczy sie w zajetych tunerach
					continue
				if t.begin - now <= TIMER_AHEAD and t.end > now:
					n += 1
			return n
		except Exception:
			return 0

	def scan_usage(self):
		used = set()
		other = set()
		tps = []
		nav = NavigationInstance.instance
		services = []
		own = []
		for j in self.active:
			if j.rec is not None:
				try:
					own.append(j.rec.__deref__())
				except Exception:
					own.append(j.rec)
		try:
			cur = nav.getCurrentService()
			if cur:
				services.append(cur)
		except Exception:
			pass
		try:
			for r in nav.getRecordings():
				try:
					d = r.__deref__()
				except Exception:
					d = r
				if d in own:
					no, _ = frontend_of(r)
					if no >= 0:
						used.add(no)
					continue
				services.append(r)
		except Exception:
			pass
		try:
			pip = getattr(self.session, "pip", None)
			if pip is not None and getattr(pip, "pipservice", None):
				services.append(pip.pipservice)
		except Exception:
			pass
		for s in services:
			no, params = frontend_of(s)
			if no >= 0:
				used.add(no)
				other.add(no)
			if params:
				tps.append(params)
		if self.mask is not None:
			i = 0
			m = self.mask
			while m:
				if m & 1:
					used.add(i)
				m >>= 1
				i += 1
		self.used = used
		self.other_used = other
		self.used_tps = tps
		for j in self.active:
			if j.tuner_no >= 0:
				j.shared = j.tuner_no in other

	def free_tuners(self):
		return self.total_tuners - len([t for t in self.used if t < self.total_tuners])

	def on_busy_tp(self, job):
		for p in self.used_tps:
			if same_tp(job.tp, p):
				return True
		return False

	# --- transpondery ---
	def count(self, job):
		d = count_detail(job.all_refs)
		bq = [d.get(r, 0) for r in job.refs]
		job.covered = len([n for n in bq if n])
		job.all_covered = len([n for n in d.values() if n])
		job.all_events = sum(d.values())
		return sum(bq)

	def start_job(self, job, shared_expected):
		if cfg.clear_mode.value == "scanned" and not self.topup:
			if not flush_refs(job.refs) and not self.note:
				self.note = "Czyszczenie pojedynczych kanalow nieobslugiwane - skan scala dane"
		rec = None
		try:
			rec = NavigationInstance.instance.recordService(eServiceReference(job.ref))
		except Exception as e:
			log("recordService wyjatek %s: %s" % (job.name, e))
		if rec is None:
			return False
		job.rec = rec
		job.t0 = time.time()
		job.state = "strojenie"
		job.shared = shared_expected
		job.events_start = job.events = self.count(job)
		job.hz_start = horizons(job.refs) if self.topup else {}
		job.all_start = job.all_events
		job.last_count = time.time()
		self.last_tune = time.time()
		self.last_usage = 0
		self.active.append(job)
		try:
			rec.prepareStreaming()
			rec.start()
		except Exception as e:
			log("start wyjatek %s: %s" % (job.name, e))
			self.release(job)
			return False
		log("Strojenie: %s%s" % (job.name, " (tuner juz pracuje na tym transponderze)" if shared_expected else ""))
		return True

	def release(self, job):
		if job.rec is not None:
			try:
				NavigationInstance.instance.stopRecordService(job.rec)
			except Exception:
				pass
			job.rec = None
		if job in self.active:
			self.active.remove(job)

	def stop_job(self, job, state):
		job.duration = int(time.time() - job.t0) if job.t0 else 0
		job.done_at = int(time.time())
		job.state = state
		self.release(job)
		extra = ""
		if self.topup and getattr(job, "hz_start", None):
			after = horizons(job.refs)
			ext = [after[r] - job.hz_start.get(r, 0) for r in job.refs if after.get(r) and job.hz_start.get(r)]
			gained = [x for x in ext if x > 1800]
			job.gained = len(gained)
			job.gain_h = max(gained) / 3600.0 if gained else 0
			extra = ", zasieg: %s" % (("+%.1f h (kanalow %d)" % (job.gain_h, len(gained))) if gained else "bez zmian")
		before = job.events_start
		if before > 0:
			src = "z innych transponderow" if getattr(self, "cleared", False) else "przed strojeniem"
			ev = "zdarzen %d: bylo %d %s, %+d z tego strojenia" % (job.events, before, src, job.events - before)
		elif job.events > 0:
			ev = "zdarzen %d, wszystkie z tego strojenia" % job.events
		else:
			ev = "zdarzen 0"
		log("%s: %s (%ds, %s, EPG bukiety %d/%d, wszystkie %d/%d%s)" % (
			state, job.name, job.duration, ev,
			job.covered, len(job.refs), job.all_covered, len(job.all_refs), extra))

	def yield_tuner(self, job):
		log("Zwalniam tuner %s dla uzytkownika: %s (wroci pozniej)" % (job.tuner, job.name))
		self.release(job)
		job.state = "czeka na tuner"
		job.t0 = 0
		job.tuner = "-"
		job.tuner_no = -1
		self.queue.insert(0, job)

	def rec_event(self, rec, event):
		for j in self.active:
			if j.rec is None:
				continue
			try:
				same = j.rec.__deref__() == rec.__deref__()
			except Exception:
				same = j.rec is rec
			if not same:
				continue
			if event == iRecordableService.evTunedIn:
				j.state = "zbiera"
				j.tuned_at = j.last_change = time.time()
				j.tuner_no, _ = frontend_of(j.rec)
				j.tuner = chr(ord("A") + j.tuner_no) if j.tuner_no >= 0 else "?"
				j.shared = j.tuner_no in self.other_used
				log("Dostrojony: %s (tuner %s%s)" % (j.name, j.tuner, ", wspoldzielony" if j.shared else ""))
				getattr(self, "fail_streak", {}).pop(j.tuner_no, None)
			elif event in (iRecordableService.evTuneFailed, iRecordableService.evRecordFailed):
				j.state = "blad_strojenia"
			break

	def tuner_state(self):
		"""Stan tunerow do logu: kto trzyma ktory tuner (diagnostyka zawieszonych tunerow)."""
		own = dict((j.tuner_no, j.name) for j in getattr(self, "active", []) if getattr(j, "tuner_no", -1) >= 0)
		out = []
		for i in range(self.total_tuners):
			t = chr(ord("A") + i)
			if i in own:
				out.append("%s: EPG Scan (%s)" % (t, own[i]))
			elif i in getattr(self, "other_used", ()):
				out.append("%s: ogladanie/nagranie" % t)
			elif i in getattr(self, "used", ()):
				out.append("%s: zajety (nie przez EPG Scan ani ogladanie)" % t)
			else:
				out.append("%s: wolny" % t)
		return ", ".join(out)

	def log_tuners(self, title):
		try:
			self.scan_usage()
			log("%s: %s" % (title, self.tuner_state()))
		except Exception as e:
			log("%s: ? (%s)" % (title, e))

	def note_tune_fail(self, no, job):
		"""3 nieudane strojenia z rzedu na tym samym tunerze (rozne transpondery) = tuner zawieszony."""
		st = self.fail_streak.setdefault(no, set()) if hasattr(self, "fail_streak") else set()
		st.add(job.name)
		if len(st) < HANG_FAILS or no in getattr(self, "hung", set()):
			return
		self.hung.add(no)
		t = chr(ord("A") + no)
		log("Tuner %s nie stroi (%d transponderow z rzedu) - prawdopodobnie zawieszony" % (t, len(st)))
		self.log_tuners("Tunery teraz")
		try:
			from .health import tuner_hung
			tuner_hung(self, t)
		except Exception as e:
			log("Tuner %s: %s" % (t, e))

	def requeue_or_fail(self, job, reason):
		no, params = frontend_of(job.rec) if job.rec is not None else (-1, None)
		if no >= 0:
			self.note_tune_fail(no, job)
			reason += " (tuner %s)" % chr(ord("A") + no)
		if params:
			reason += " [%s kHz, pol %s]" % (params[0], params[1])
		self.release(job)
		self.scan_usage()
		if self.free_tuners() <= self.eff_reserve():
			job.state = "czeka na tuner"
			job.t0 = 0
			job.not_before = time.time() + NO_TUNER_WAIT
			self.queue.append(job)
			log("%s: %s - brak wolnego tunera, bez liczenia bledu" % (reason, job.name))
			return
		job.not_before = time.time() + 10 * (job.retries + 1)
		job.retries += 1
		if job.retries < MAX_RETRIES:
			job.state = "czeka (ponownie)"
			job.t0 = 0
			self.queue.append(job)
			log("%s: %s - ponowna proba pozniej" % (reason, job.name))
		else:
			job.state = "BLAD: " + reason
			log("BLAD: %s: %s" % (reason, job.name))

	def tick(self):
		now = time.time()
		if now - self.last_usage >= USAGE_EVERY:
			self.scan_usage()
			self.last_usage = now
			hold = self.upcoming_recordings()
			if hold != self.timer_hold:
				if hold:
					log("Za mniej niz %d min rusza nagranie (%d) - trzymam wolne tunery, nowe skany wstrzymane" % (
						TIMER_AHEAD // 60, hold))
				else:
					log("Brak nagran w najblizszych %d min - skan wraca do zwyklej rezerwy" % (TIMER_AHEAD // 60))
				self.timer_hold = hold

		min_t = cfg.min_time.value
		idle_t = cfg.idle_time.value
		max_t = cfg.max_time.value

		# 1. postep i konczenie aktywnych (koniec, gdy nie przybywa zdarzen na calym transponderze)
		for j in list(self.active):
			if j.state == "blad_strojenia":
				self.requeue_or_fail(j, "brak dostrojenia")
				continue
			if j.state == "strojenie":
				if now - j.t0 > TUNE_TIMEOUT:
					self.requeue_or_fail(j, "timeout strojenia")
				continue
			if now - j.last_count >= COUNT_EVERY:
				j.last_count = now
				before = j.all_events
				j.events = self.count(j)
				if j.all_events != before:
					j.last_change = now
			on = now - j.tuned_at
			if on >= max_t:
				self.stop_job(j, "gotowe (limit czasu)")
			elif on >= min_t and now - j.last_change >= idle_t:
				self.stop_job(j, "gotowe")

		# 2. pilnuj rezerwy: jesli brakuje wolnych tunerow, oddaj tyle wlasnych, ile brakuje
		#    (liczone raz - mapa zajetosci tunerow aktualizuje sie z opoznieniem);
		#    skany wspoldzielone nie zajmuja tunera, wiec ich nie ruszamy
		deficit = self.eff_reserve() - self.free_tuners()
		if deficit > 0:
			own = sorted([j for j in self.active if not j.shared], key=lambda x: x.t0, reverse=True)
			for j in own[:deficit]:
				self.yield_tuner(j)
			if own:
				self.last_usage = 0

		# 3. jedno nowe strojenie na raz, z odstepem (Unicable);
		#    transpondery wspoldzielone nie licza sie do limitu
		tuning = [j for j in self.active if j.state == "strojenie"]
		ready = [j for j in self.queue if j.not_before <= now]
		own_active = len([j for j in self.active if not j.shared])
		if not tuning and now - self.last_tune >= TUNE_GAP and ready:
			job = None
			shared = False
			for q in ready:
				if self.on_busy_tp(q):
					job, shared = q, True
					break
			if job is None and not self.timer_hold and own_active < self.max_parallel \
					and self.free_tuners() - self.eff_reserve() > 0:
				job = ready[0]
			if job is not None:
				self.queue.remove(job)
				if not self.start_job(job, shared):
					job.state = "czeka na tuner"
					job.not_before = now + NO_TUNER_WAIT
					self.queue.append(job)

		for j in self.queue:
			if j.state == "czeka" and self.active and self.free_tuners() - self.eff_reserve() <= 0:
				j.state = "czeka na tuner"

		if self.running and not self.queue and not self.active:
			self.finish()
		self.notify()

	def end_eit(self):
		if getattr(self, "eit_restore", False):
			self.eit_restore = False
			restore_eit()

	def finish(self):
		"""Koniec skanu z satelity; potem opcjonalny import XMLTV, na koncu complete()."""
		self.timer.stop()
		self.end_eit()
		self.t_end = time.time()
		self.detach()
		done, err, el = self.stats()
		if self.topup:
			self.finish_topup(done, err, el)
			return
		total = sum(j.events for j in self.jobs)
		total_all = sum(j.all_events for j in self.jobs)
		cfg.last_events.value = total
		cfg.last_summary.value = "%s: %d/%d transponderow, %d bledow, %d zdarzen, %d:%02d" % (
			time.strftime("%Y-%m-%d %H:%M"), done, len(self.jobs), err, total, el // 60, el % 60)
		remember_scan(self.jobs)
		update_tp_stats(self.jobs)
		save_cfg()
		self.changes = ""
		log("Koniec skanu, czas %ds, zdarzen w bukietach %d, wszystkich %d" % (el, total, total_all))
		try:
			log("Statystyka EPG po skanie (bukiety): " + stats_text(epg_stats(all_refs())))
		except Exception as e:
			log("Statystyka EPG nieudana: %s" % e)
		skipped = len([j for j in self.jobs if j.skip])
		self.summary = "%d/%d transponderow, %d bledow%s, czas %d:%02d" % (
			done, len(self.jobs) - skipped, err, (", %d pominietych" % skipped) if skipped else "", el // 60, el % 60)
		log_dups("po skanie")
		if xmltv_in_profile(getattr(self, "profile", None) or profile_default()):
			self.wait_server_then_xmltv()
		else:
			self.complete()

	def finish_topup(self, done, err, el):
		"""Koniec doczytywania: zapis EPG i statystyka; bez XMLTV, kopii i zmian punktu odniesienia."""
		update_tp_stats(self.jobs)
		for n in update_topup_stats(self.jobs):
			log("Doczytywanie: " + n)
		won = [j for j in self.jobs if getattr(j, "gained", 0)]
		best = max([j.gain_h for j in won] or [0])
		self.summary = "doczytywanie: %d/%d transponderow, zasieg wydluzony na %d (maks. +%.1f h), czas %d:%02d" % (
			done, len(self.jobs), len(won), best, el // 60, el % 60)
		log("Doczytywanie zakonczone: %s" % self.summary)
		self.note = self.summary
		try:
			eEPGCache.getInstance().save()
		except Exception as e:
			log("Doczytywanie: zapis EPG nieudany: %s" % e)
		self.phase = ""
		self.running = False
		self.finished = True
		self.topup = False
		self.notify()
		if cfg.cli_api.value and cfg.cli_pack.value:
			from .clientpack import request_rebuild
			request_rebuild()

	def start_xmltv(self, tried=False):
		from .xmltv import XmltvImport, sources_present, cache_present, install_sources
		if getattr(self, "pending_clear", False):
			self.pending_clear = False
			log("Czyszczenie calego EPG przed importem XMLTV")
			flush_all()
		if not sources_present() and not tried:
			log("XMLTV: brak list zrodel w /etc/epgimport - instaluje pakiet zrodel")
			self.phase = "xmltv_install"
			self.notify()

			def done(ok, msg):
				if not self.running:
					return
				if ok:
					self.start_xmltv(True)
				elif cache_present():
					log("XMLTV: pakiet zrodel niedostepny - uzywam kopii list zrodel z wtyczki")
					self.start_xmltv(True)
				else:
					log("XMLTV: import pominiety - brak list zrodel")
					self.complete()
			install_sources(done)
			return
		try:
			self.xmltv_resume_eit = pause_eit_for_xmltv()
		except Exception as e:
			log("XMLTV: nie udalo sie wylaczyc EIT - import przerwany: %s" % e)
			self.complete()
			return
		try:
			names = {}
			coverage = {}
			event_ids = {}
			targets = xmltv_targets(names, coverage, event_ids)
			miss = len([k for k, hz in targets.items() if not hz])
			log("XMLTV: kanalow do uzupelnienia %d (z bukietow %d, kopie spoza bukietow %d; bez EPG %d, z EPG %d)" % (
				len(targets), len(names), len(targets) - len(names), miss, len(targets) - miss))
			if not targets:
				self.complete()
				return
			self.phase = "xmltv"
			from . import xmltv as xm
			xm.PROFILE = getattr(self, "profile", None) or profile_default()
			srcs = xm.selected_sources()[0]
			log("XMLTV: zrodla wg profilu (%s): %s" % (profile_text(xm.PROFILE), ", ".join(s.desc for s in srcs) or "brak"))
			self.xmltv = XmltvImport(targets, self.complete, names, coverage, event_ids)
			self.xmltv.force = getattr(self, "force_xmltv", False)
			if not self.xmltv.start():
				self.xmltv = None
				self.complete()
		except Exception as e:
			log("XMLTV: nie udalo sie rozpoczac importu: %s" % e)
			self.complete()
		self.notify()

	# --- pobranie programu przed importem XMLTV (wg profilu) ---
	# --- SatWatch: krok przed skanem EPG ---
	def satwatch_stage(self, kw):
		try:
			from .satwatch.sw import runner, full_due
		except Exception as e:
			log("SatWatch niedostepny (%s) - pomijam krok" % e)
			return self.continue_after_sw(kw, False)
		self.hook_satwatch()
		full = kw["profile"]["sw"] == "pelny" or (kw["auto"] and full_due())
		runner.wait_override = 15 * 60                 # w przebiegu najwyzej 15 min czekania na wolne tunery
		runner.pipeline = not full                     # bez zmian -> od razu EPG, ponowienia TP w tle
		self._sw_pending = kw
		self._sw_cancel = False
		rotate_logs()
		log("SatWatch: %s (przed skanem EPG)" % ("pelny skan transponderow" if full else "sprawdzenie zmian"))
		if not runner.check(scheduled=kw["auto"], full=full):
			log("SatWatch zajety (inne zadanie w toku) - pomijam krok")
			self._sw_pending = None
			runner.wait_override = 0
			runner.pipeline = False
			return self.continue_after_sw(kw, False)
		self.running = True
		self.finished = False
		self.phase = "satwatch"
		self.auto = kw["auto"]
		self.note = ""
		self.t_start = time.time()
		self.notify()
		return True

	def hook_satwatch(self):
		try:
			from .satwatch.sw import runner
			if self.sw_done not in runner.done_cbs:
				runner.done_cbs.append(self.sw_done)
		except Exception:
			pass

	def continue_after_sw(self, kw, changed):
		self.sw_changed = changed
		self._sw_done = True
		try:
			ok = self.start(**kw)
		finally:
			self._sw_done = False
		if not ok:
			self.running = False
			self.finished = True
			self.notify()
		return ok

	def sw_finish_cancel(self):
		self._sw_pending = None
		self.running = False
		self.finished = True
		self.phase = ""
		self.summary = "SatWatch przerwany"
		self.notify()

	def sw_done(self, summary, important, changed, info):
		"""Koniec pracy SatWatch: krok przebiegu albo zadanie w tle (wznowienie, okno SatWatch)."""
		try:
			from .satwatch.sw import runner
			runner.wait_override = 0
			runner.pipeline = False
		except Exception:
			pass
		try:
			cfg.last_sw.value = "%d|%s" % (int(time.time()), summary[:200])
			cfg.last_sw.save()
		except Exception:
			pass
		self.sw_info = info
		log("%s%s" % (summary if summary.startswith("SatWatch") else "SatWatch: " + summary, " - zmiany w kanalach" if changed else ""))
		try:
			from .health import add_night_note
			if info.get("unresolved"):
				add_night_note("SatWatch: %d kanalow do decyzji (Menu -> Kanaly -> SatWatch -> Kanaly do decyzji)" % info["unresolved"])
			if info.get("error") or info.get("restored"):
				add_night_note(summary)
		except Exception:
			pass
		if info.get("updated") and not info.get("reloaded"):
			self.need_restart_gui = "SatWatch zaktualizowal satellites.xml - nowe transpondery po restarcie Enigma2"
		kw = getattr(self, "_sw_pending", None)
		if self.running and self.phase == "satwatch" and kw:
			self._sw_pending = None
			if getattr(self, "_sw_cancel", False):
				self.sw_finish_cancel()
				return
			self.running = False
			self.phase = ""
			if info.get("tps"):
				# SatWatch stroil tunery - chwila na ich zwolnienie (Unicable/FBC), potem skan EPG
				log("SatWatch uzywal tunerow - 10 s przerwy przed skanem EPG")
				self._sw_next = (kw, changed)
				if not hasattr(self, "sw_gap_timer"):
					self.sw_gap_timer = eTimer()
					self.sw_gap_timer.callback.append(self.sw_gap_done)
				self.running = True
				self.phase = "satwatch"
				self.sw_gap_timer.start(10000, True)
				return
			self.continue_after_sw(kw, changed)
			return
		if changed:
			self.request_rerun("SatWatch zmienil kanaly w tle (%s)" % summary)

	def sw_gap_done(self):
		kw, changed = getattr(self, "_sw_next", (None, False))
		self._sw_next = None
		self.running = False
		self.phase = ""
		if kw is None or getattr(self, "_sw_cancel", False):
			self.sw_finish_cancel()
			return
		self.continue_after_sw(kw, changed)

	def request_rerun(self, why):
		"""Dogrywka po zmianach SatWatch poza przebiegiem: skan EPG + mapa + import (bez pobierania, gdy plik jest)."""
		log("Dogrywka EPG zaplanowana: %s" % why)
		self.rerun_pending = True
		if not hasattr(self, "rerun_timer"):
			self.rerun_timer = eTimer()
			self.rerun_timer.callback.append(self.do_rerun)
		self.rerun_timer.start(60000, True)

	def do_rerun(self):
		if not getattr(self, "rerun_pending", False):
			return
		if self.running:
			self.rerun_timer.start(60000, True)         # po biezacej pracy
			return
		self.rerun_pending = False
		p = profile_default()
		p.update({"sw": "nie", "clear": "none", "fetch": False})
		self.sw_changed = True
		log("Dogrywka EPG po zmianach SatWatch")
		if not self.start(profile=p):
			log("Dogrywka EPG nie ruszyla")

	def fetch_start(self):
		"""Pobranie przed importem wg profilu. Bez "Pobierz swieze dane" (i dla serwera-zapasu) pobieranie rusza
		tylko wtedy, gdy nie ma uzywalnego pliku (brak albo starszy niz 30 h)."""
		from .localrun import local_file_ok
		p = getattr(self, "profile", None) or profile_default()
		if not cfg.xmltv.value:
			return
		if p["dek"]:
			if p["fetch"]:
				self.local_grab_start()
			elif not local_file_ok():
				log("Dekoder: brak aktualnego pliku programu - pobieram mimo \"Pobierz swieze dane: Nie\"")
				self.local_grab_start()
		if getattr(self, "sw_changed", False) and p["srv"] != "nie" and cfg.map_mode.value == "serwer":
			log("Serwer epgpl: zmiany w kanalach (SatWatch) - pobieranie z nowa mapa na serwerze")
			self.server_grab_start()
		elif p["srv"] == "zawsze" and p["fetch"]:
			if p.get("one") and cfg.one_place.value and p["dek"]:
				# pobieranie w jednym miejscu: dekoder sam sprawdzi, czy serwer ma dzisiejszy program (paczka)
				log("Serwer epgpl: pobieranie w jednym miejscu - serwer nie jest proszony o pobranie, dekoder sprawdzi jego paczke")
			else:
				self.server_grab_start()
		elif p["srv"] != "nie":
			self.server_grab_start(only_if_old=True)     # gotowy plik serwera; pobieranie tylko, gdy go brak / stary

	def server_grab_start(self, only_if_old=False):
		try:
			from .mapping import epgpl_servers, server_grab
			srv = epgpl_servers()
			if not srv:
				log("Serwer epgpl: opcja pobierania wlaczona, ale zadne wybrane zrodlo XMLTV nie jest z serwera epgpl")
				return
			if len(srv) > 1:
				log("Serwer epgpl: kilka serwerow - pobieranie uruchamiane tylko na %s" % srv[0][0])
			log("Serwer epgpl: %s (%s) - import XMLTV po jego zakonczeniu" % (
				"sprawdzam plik programu (pobranie tylko, gdy brak albo starszy niz 30 h)" if only_if_old else
				"uruchamiam pobieranie programu", srv[0][1]))
			server_grab().start(self.session, srv[0][0], "wait", srv[0][1], only_if_old=only_if_old)
		except Exception as e:
			log("Serwer epgpl: nie udalo sie uruchomic pobierania: %s" % e)

	def local_grab_start(self):
		try:
			from .localrun import local_job
			p = getattr(self, "profile", None) or profile_default()
			# "w jednym miejscu" (Akcje -> Pobierz swieze dane; harmonogram - profil domyslny): paczka drugiej strony
			local_job().start("przed importem XMLTV", scheduled=bool(p.get("one") and p.get("fetch") and cfg.one_place.value))
		except Exception as e:
			log("Dekoder: nie udalo sie uruchomic pobierania: %s" % e)

	def waits(self):
		"""Czy XMLTV ma czekac: pobieranie na serwerze (tryb harmonogramu) albo na dekoderze."""
		try:
			from .mapping import server_grab
			g = server_grab()
			if g.active and g.mode == "wait":
				return True
		except Exception:
			pass
		try:
			from .localrun import local_job
			return local_job().active
		except Exception:
			return False

	def wait_text(self):
		out = []
		try:
			from .mapping import server_grab
			g = server_grab()
			if g.active:
				out.append(g.text)
		except Exception:
			pass
		try:
			from .localrun import local_job
			j = local_job()
			if j.active:
				out.append(j.text)
		except Exception:
			pass
		return "\n".join(out)

	def wait_server_then_xmltv(self):
		try:
			from .localrun import local_job
			j = local_job()
			log("XMLTV: przed importem - dekoder %s, serwer %s" % ("pobiera" if j.active else
				("ostatnio %s" % {None: "-", True: "udane", False: "nieudane"}[j.last_ok]),
				"pobiera" if self.waits() and not j.active else "-"))
		except Exception as e:
			log("XMLTV: stan pobierania nieznany: %s" % e)
		if self.waits():
			self.phase = "xmltv_wait"
			self.wait_t0 = time.time()
			log("XMLTV: czekam na koniec pobierania programu (serwer epgpl / dekoder, najwyzej 45 min)")
			self.wait_timer = eTimer()
			self.wait_timer.callback.append(self.check_server_wait)
			self.wait_timer.start(5000, False)
			self.notify()
			return
		self.start_xmltv()

	def check_server_wait(self):
		w = self.waits()
		if w and time.time() - self.wait_t0 < 45 * 60:
			self.notify()
			return
		self.wait_timer.stop()
		if w:
			log("XMLTV: pobieranie nie skonczylo sie w 45 min - import z poprzedniego pliku programu")
		self.start_xmltv()

	def start_xmltv_only(self, clear=False, auto=False, local=True, profile=None):
		"""Sam import XMLTV (po pobraniu programu na serwerze, z gotowego pliku albo gdy import z satelity
		jest wylaczony), bez skanu satelity."""
		if self.running:
			return False
		self.profile = dict(profile or getattr(self, "profile", None) or profile_default())
		if not local:
			self.profile["fetch"] = False
		if not xmltv_in_profile(self.profile):
			log("Import XMLTV: %s" % ("program z internetu wylaczony w ustawieniach" if not cfg.xmltv.value else
				"profil nie ma zadnego zrodla XMLTV"))
			return False
		self.topup = False
		self.auto = auto
		self.force_xmltv = True
		self.xmltv_only = True
		self.note = ""
		self.running = True
		self.finished = False
		self.t_start = time.time()
		self.summary = "import XMLTV%s" % (" po wyczyszczeniu EPG" if clear else "")
		if auto and not getattr(self, "_sw_done", False):
			rotate_logs()
		log("Import samego XMLTV%s%s" % (" (harmonogram, import z satelity wylaczony)" if auto else "",
			" - najpierw czyszczenie calego EPG" if clear else " (bez czyszczenia EPG)"))
		self.fetch_start()
		self.pending_clear = bool(clear)      # czyszczenie tuz przed importem, nie na czas pobierania
		self.wait_server_then_xmltv()
		self.notify()
		return True

	def complete(self):
		"""Koniec calego odswiezania: zapis EPG, powiadomienie, kopia i ew. gleboki sen."""
		if self.xmltv_resume_eit:
			self.xmltv_resume_eit = False
			try:
				resume_eit_after_xmltv()
			except Exception as e:
				log("XMLTV: nie udalo sie przywrocic EIT: %s" % e)
		if self.phase == "xmltv":
			try:
				log("Statystyka EPG po imporcie XMLTV (bukiety): " + stats_text(epg_stats(all_refs())))
			except Exception:
				pass
			if self.xmltv:
				res = "zapisano %d audycji dla %d kanalow" % (self.xmltv.imported, len(self.xmltv.results))
				if getattr(self, "xmltv_only", False):
					self.summary = "%s: %s" % (self.summary, res)
					self.note = "Import XMLTV: " + res
		if self.phase == "xmltv":
			try:
				cfg.last_import.value = "%s|%s" % (int(time.time()), res if self.xmltv else "brak danych")
				cfg.last_import.save()
			except Exception:
				pass
		try:
			from . import xmltv as xm
			xm.PROFILE = None
		except Exception:
			pass
		self.phase = ""
		self.xmltv = None
		self.running = False
		self.clear_auto_flag()
		self.finished = True
		self.t_end = time.time()
		try:
			eEPGCache.getInstance().save()
			log("Baza EPG zapisana")
		except Exception as e:
			log("Zapis EPG nieudany: %s" % e)
		el = int(self.t_end - self.t_start) if self.t_start else 0
		log("Koniec, czas calkowity %d:%02d" % (el // 60, el % 60))
		self.popup("EPG Scan: " + self.summary)
		self.notify()
		self.bk_timer.start(5000, True)   # chwila na zapis pliku EPG, potem kopia i ew. gleboki sen

	def popup(self, text):
		"""Powiadomienie tylko wtedy, gdy nikt nie oglada ekranu wtyczki i opcja jest wlaczona."""
		if self.listeners or not cfg.notify.value:
			return
		try:
			import Screens.Standby
			if Screens.Standby.inStandby:
				return
		except Exception:
			pass
		try:
			from Tools import Notifications
			from Screens.MessageBox import MessageBox
			Notifications.AddPopup(text, MessageBox.TYPE_INFO, 10, "EPGScanDone")
		except Exception:
			pass

	def do_backup(self):
		if cfg.backup.value:
			dst = copy_backup()
			self.note = ("Kopia EPG: %s" % dst) if dst else "Kopia EPG nieudana - szczegoly w logu"
			self.notify()
		self.sw_changed = False
		# ostatni krok pracy z harmonogramu: dane dla aplikacji E2Stream (aktualny skan i gotowe EPG)
		if self.auto and not getattr(self, "topup", False) and cfg.cli_api.value and cfg.cli_pack.value:
			try:
				from .clientpack import build
				if build(lambda res: self.after_backup()):
					return
			except Exception as e:
				log("Dane dla E2Stream: %s" % e)
		self.after_backup()

	def after_backup(self):
		if self.auto:
			self.night_notes()
		if not getattr(self, "topup", False):
			try:
				from .channelparamscan import scan_due, start_manual, start_scheduled
				if self.auto:
					if scan_due() and start_scheduled(self, self.auto_start_time, woke=self.woke,
							done=self.after_channelparams):
						return
				elif getattr(self, "profile", {}).get("channelparams") and \
						start_manual(self, done=self.after_channelparams):
					return
			except Exception as e:
				log("Skan parametrow po EPG nieudany: %s" % e)
		if self.auto:
			from .health import maybe_restart
			if maybe_restart(self):
				return
			self.need_restart_gui = ""
		self.maybe_deep_standby()

	def after_channelparams(self, _report):
		from .health import maybe_restart
		if maybe_restart(self):
			return
		self.need_restart_gui = ""
		self.maybe_deep_standby()

	def night_notes(self):
		"""Wazne rzeczy z nocnego przebiegu do okienka po wlaczeniu odbiornika."""
		try:
			from .health import add_night_note
			bad = [j.name for j in self.jobs if str(j.state).startswith("BLAD")]
			if bad and not self.xmltv_only:
				add_night_note("Skan EPG: blad na %d transponderach (%s)" % (len(bad), ", ".join(bad[:4])))
			from .localrun import local_job
			if local_job().last_ok is False:
				add_night_note("Pobieranie na dekoderze nieudane - uzyty serwer (Info -> Pobieranie na dekoderze)")
		except Exception as e:
			log("Podsumowanie nocy: %s" % e)

	def maybe_deep_standby(self):
		"""Po skanie z harmonogramu, ktory wybudzil odbiornik, wroc do glebokiego czuwania."""
		if not (self.auto and self.woke):
			return
		deep_after = cfg.sched_deep_after.value
		if not deep_after or self.session is None:
			return
		try:
			import Screens.Standby
			if not Screens.Standby.inStandby:
				log("Brak powrotu do glebokiego czuwania: odbiornik jest w uzyciu")
				return
			nav = NavigationInstance.instance
			if nav.getRecordings():
				log("Brak powrotu do glebokiego czuwania: trwa nagrywanie lub stream")
				return
			nxt = nav.RecordTimer.getNextRecordingTime()
			if nxt > 0 and nxt - time.time() < 900:
				log("Brak powrotu do glebokiego czuwania: nagranie za mniej niz 15 min")
				return
			log("Powrot do glebokiego czuwania")
			self.session.open(Screens.Standby.TryQuitMainloop, 1)
		except Exception as e:
			log("Powrot do glebokiego czuwania nieudany: %s" % e)
