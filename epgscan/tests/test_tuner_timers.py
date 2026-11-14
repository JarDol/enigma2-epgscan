import importlib
import os
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

import test_actions_ui


class TunerTimerTests(unittest.TestCase):
	def setUp(self):
		enigma = types.ModuleType("enigma")
		enigma.eTimer = type("Timer", (), {})
		enigma.eServiceReference = object
		enigma.eEPGCache = object
		enigma.iRecordableService = object
		self.navigation = types.ModuleType("NavigationInstance")
		self.navigation.instance = types.SimpleNamespace(
			RecordTimer=types.SimpleNamespace(timer_list=[]))
		modules = dict(test_actions_ui.modules, enigma=enigma, NavigationInstance=self.navigation)
		module_patch = patch.dict(sys.modules, modules)
		module_patch.start()
		self.addCleanup(module_patch.stop)
		self.module = importlib.import_module(test_actions_ui.PACKAGE + ".engine")
		self.report = importlib.import_module(test_actions_ui.PACKAGE + ".tunerreport")
		directory = tempfile.TemporaryDirectory()
		self.addCleanup(directory.cleanup)
		path_patch = patch.object(self.report, "CHANNEL_PARAMS_FILE", os.path.join(directory.name, "channelparams.json"))
		path_patch.start()
		self.addCleanup(path_patch.stop)
		self.engine = self.module.ScanEngine.__new__(self.module.ScanEngine)
		self.engine.total_tuners = 8

	def test_upcoming_recordings_and_zaps_are_reserved_only_while_pending(self):
		def timer(begin, end, justplay=False, disabled=False, state=0):
			return types.SimpleNamespace(begin=begin, end=end, justplay=justplay,
				disabled=disabled, state=state)

		self.navigation.instance.RecordTimer.timer_list = [
			timer(1100, 1300), timer(1120, 1180, justplay=True, state=1),
			timer(1121, 1200, justplay=True), timer(1010, 1100, disabled=True),
			timer(990, 1100, justplay=True, state=2), timer(900, 999),
		]
		with patch.object(self.module.time, "time", return_value=1000):
			self.assertEqual(self.engine.upcoming_recordings(), 2)

	def test_timer_read_failure_fails_safe_and_is_logged_once(self):
		self.navigation.instance.RecordTimer = None
		with patch.object(self.module, "log") as log:
			self.assertEqual(self.engine.upcoming_recordings(), 8)
			self.assertEqual(self.engine.upcoming_recordings(), 8)
			self.assertEqual(log.call_count, 1)
			self.navigation.instance.RecordTimer = types.SimpleNamespace(timer_list=[])
			self.assertEqual(self.engine.upcoming_recordings(), 0)
			self.assertEqual(log.call_count, 2)

	def test_trace_counts_overlapping_conditions_and_shared_tuners(self):
		state = types.SimpleNamespace(free=6, timer_hold=0, timer_check_error=None,
			used={0, 1}, other_used={0}, active=[types.SimpleNamespace(tuner_no=0, shared=True, name="TV")])
		state.free_tuners = lambda: state.free
		state.eff_reserve = lambda: 2 + state.timer_hold
		trace = self.report.TunerTrace(8, 2, 6, 2, 100)
		trace.note_start(False, True)
		trace.note_start(True, False)
		trace.sample(state, 100, 4, 4, 1, True, True, monotonic=0)
		state.free = 2
		state.timer_hold = 1
		trace.sample(state, 102, 3, 3, 2, False, False, monotonic=2)
		result = trace.finish(105, monotonic=5)
		self.assertEqual((result["min_free"], result["max_free"], result["max_own"]), (2, 6, 2))
		self.assertEqual(result["tuners"], [0])
		self.assertEqual(result["attempts"], {"independent": 1, "shared": 1, "failed": 1})
		self.assertEqual(result["seconds"], {"tuning": 2, "gap": 2, "timer": 3, "reserve": 3})
		with patch.object(self.report, "_", lambda value: value):
			text = self.report.format_trace(result)
		self.assertIn("A*: TV", text)
		self.assertIn("inne A", text)
		self.assertIn("czasy moga sie nakladac", text)

	def test_trace_limits_history_but_keeps_whole_scan_summary(self):
		state = types.SimpleNamespace(free=0, timer_hold=0, timer_check_error=None,
			used=set(), other_used=set(), active=[])
		state.free_tuners = lambda: state.free
		state.eff_reserve = lambda: 0
		trace = self.report.TunerTrace(200, 0, 2, 2, 100)
		for number in range(150):
			state.free = number
			trace.sample(state, 100 + number, 0, 0, 0, False, False, monotonic=number)
		result = trace.finish(250, monotonic=150)
		self.assertEqual(result["samples"], 150)
		self.assertEqual(result["max_free"], 149)
		self.assertEqual(len(result["events"]), self.report.TRACE_LIMIT)
		self.assertEqual(result["omitted"], 30)
		self.assertEqual(result["events"][59]["at"], 159)
		self.assertEqual(result["events"][60]["at"], 190)

	def test_trace_save_is_atomic_and_failure_is_visible(self):
		previous = {"events": [], "started": 10}
		self.report.save_trace("epg", previous)
		with patch.object(self.report.os, "replace", side_effect=OSError("disk full")), \
				patch.object(self.report, "log") as log:
			result = self.report.close_trace(self.report.TunerTrace(8, 2, 6, 2, 20), "epg", 30)
		self.assertEqual(self.report.load_trace("epg"), previous)
		self.assertIn("disk full", result["save_error"])
		log.assert_called_once()
		self.assertFalse(os.path.exists(self.report._path("epg") + ".tmp"))
		with patch.object(self.report, "_", lambda value: value):
			self.assertIn("disk full", self.report.format_trace(result))

	def test_epg_scheduler_records_and_persists_tuner_activity(self):
		e = self.engine
		e.reserve, e.max_parallel, e.timer_hold = 2, 6, 0
		e.used, e.other_used = {0, 1}, {0}
		e.active = []
		e.queue = [types.SimpleNamespace(name=name, state="czeka", not_before=0, shared=False, tuner_no=-1)
			for name in ("first", "second")]
		e.last_usage, e.last_tune, e.running = 100, 0, True
		e.on_busy_tp = lambda _job: False
		e.notify = Mock()
		e.epg_tuner_trace = self.report.TunerTrace(8, 2, 6, 2, 100)
		def start(job, shared):
			job.state, job.shared, job.tuner_no = "strojenie", shared, 2
			e.active.append(job)
			e.last_tune = 100
			return True
		e.start_job = start
		with patch.object(self.module.time, "time", return_value=100), \
				patch.object(self.module.time, "monotonic", return_value=10):
			e.tick()
		e.timer, e.end_eit, e.detach = Mock(), Mock(), Mock()
		e.stats = lambda: (1, 0, 3)
		e.topup, e.finish_topup = True, Mock()
		with patch.object(self.module.time, "time", return_value=103), \
				patch.object(self.module.time, "monotonic", return_value=13):
			e.finish()
		report = self.report.load_trace("epg")
		self.assertEqual(report["tuners"], [2])
		self.assertEqual(report["max_own"], 1)
		self.assertEqual(report["attempts"]["independent"], 1)
		self.assertEqual(report["seconds"]["tuning"], 3)
		self.assertEqual(report["seconds"]["gap"], 3)
		self.assertFalse(report["cancelled"])

	def test_stopping_epg_persists_cancelled_trace(self):
		e = self.engine
		e.running, e.phase, e.active = True, "scan", []
		e.epg_params_scan = None
		e.epg_tuner_trace = self.report.TunerTrace(8, 2, 6, 2, 100)
		e.clear_auto_flag, e.end_eit, e.detach, e.notify = Mock(), Mock(), Mock(), Mock()
		e.timer = Mock()
		with patch.object(self.module.time, "time", return_value=110):
			e.stop()
		self.assertTrue(self.report.load_trace("epg")["cancelled"])
		self.assertIsNone(e.epg_tuner_trace)

	def test_epg_statistics_load_saved_tuner_trace_after_restart(self):
		info = importlib.import_module(test_actions_ui.PACKAGE + ".info")
		report = self.report.TunerTrace(8, 2, 6, 2, 100).finish(110)
		self.report.save_trace("epg", report)
		screen = info.EPGScanInfo.__new__(info.EPGScanInfo)
		screen.engine = types.SimpleNamespace(running=False, topup=False, epg_tuner_report=None)
		screen["text"] = test_actions_ui.FakeLabel()
		with patch.object(info, "build_jobs", return_value=[]), patch.object(info, "all_refs", return_value=[]), \
				patch.object(info, "epg_stats", return_value={}), patch.object(info, "stats_text", return_value="stats"), \
				patch.object(info, "whole_cache_stats", return_value=(0, 0, 0, "-")), patch.object(info, "log"), \
				patch.object(info, "_", lambda value: value), patch.object(self.report, "_", lambda value: value):
			screen.compute()
		self.assertIn("Wykorzystanie tunerow", screen["text"].text)


if __name__ == "__main__":
	unittest.main()
