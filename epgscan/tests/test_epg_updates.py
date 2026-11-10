import importlib
import hashlib
import json
import os
import sys
import tempfile
import types
import unittest
from unittest.mock import patch


ROOT = os.path.dirname(os.path.dirname(__file__))
package = types.ModuleType("epgscan_test_plugin")
package.__path__ = [os.path.join(ROOT, "EPGScan")]
sys.modules[package.__name__] = package
enigma = types.ModuleType("enigma")
enigma.eTimer = object
enigma.eEPGCache = type("FakeEPGCache", (), {})
sys.modules.setdefault("enigma", enigma)
common = types.ModuleType(package.__name__ + ".common")
common.cfg = types.SimpleNamespace()
common.log = lambda message: None
common.E2 = "/etc/enigma2"
common.save_cfg = lambda: None
sys.modules[common.__name__] = common

intervals = importlib.import_module(package.__name__ + ".epgintervals")
xmltv = importlib.import_module(package.__name__ + ".xmltv")
clientpack = importlib.import_module(package.__name__ + ".clientpack")
for name in ("is_due", "sched_active", "next_run", "fmt_ts", "topup_candidates", "topup_label"):
	setattr(common, name, lambda *args, **kwargs: None)
common.WAKEUP_FILE = "/tmp/epgscan-test-wakeup"
common.TOPUP_INFO = {"recent": 0, "backoff": 0}
navigation = types.ModuleType("NavigationInstance")
navigation.instance = types.SimpleNamespace(RecordTimer=types.SimpleNamespace(isRecording=lambda: False))
sys.modules.setdefault("NavigationInstance", navigation)
engine_module = types.ModuleType(package.__name__ + ".engine")
engine_module.get_engine = lambda session: None
sys.modules[engine_module.__name__] = engine_module
schedule = importlib.import_module(package.__name__ + ".schedule")


class CoverageTests(unittest.TestCase):
	def test_merge_and_split_preserve_uncovered_intervals(self):
		coverage = intervals.merge_intervals([(50, 60), (20, 30), (25, 40), (60, 65), (0, 0)])
		self.assertEqual(coverage, [(20, 40), (50, 65)])
		event = (10, 70, "title", "short", "long", 0)
		self.assertEqual(intervals.uncovered_event(event, coverage), [
			(10, 10, "title", "short", "long", 0),
			(40, 10, "title", "short", "long", 0),
			(65, 15, "title", "short", "long", 0),
		])

	def test_boundaries_and_full_coverage(self):
		event = (10, 10, "x", "", "", 0)
		self.assertEqual(intervals.uncovered_event(event, [(0, 10), (20, 30)]), [event])
		self.assertEqual(intervals.uncovered_event(event, [(0, 30)]), [])
		self.assertEqual(intervals.uncovered_event(event, []), [event])

	def test_xmltv_fills_hole_before_last_satellite_event(self):
		data = b"""<tv>
		<programme channel="a" start="20261111000000 +0000" stop="20261111010000 +0000"><title>Missing</title></programme>
		<programme channel="a" start="20261111010000 +0000" stop="20261111020000 +0000"><title>Satellite</title></programme>
		</tv>"""
		start = xmltv.xmltv_time("20261111000000 +0000")
		key = (1, 2, 3, 4)
		job = xmltv.XmltvImport.__new__(xmltv.XmltvImport)
		job.cancelled = False
		job.targets = {key: start + 7200}
		job.coverage = {key: [(start + 3600, start + 7200)]}
		with tempfile.NamedTemporaryFile() as f:
			f.write(data)
			f.flush()
			got, _, _ = job.parse(f.name, "guide.xml", {"a": [key]}, start - 1)
		self.assertEqual([(e[0], e[1], e[2]) for e in got[key]], [(start, 3600, "Missing")])

	def test_import_skips_id_collision_without_replacing_existing_event(self):
		key = (1, 2, 3, 4)
		job = xmltv.XmltvImport.__new__(xmltv.XmltvImport)
		job.cancelled = False
		job.queue = [(key, [(100, 10, "collision", "", "", 0), (120, 10, "safe", "", "", 0)])]
		job.targets = {key: 1}
		job.coverage = {key: []}
		job.event_ids = {key: {100}}
		job.names = {key: "channel"}
		job.imp_timer = types.SimpleNamespace(stop=lambda: None)
		job.imported = 0
		job.finish = lambda: None
		written = []
		cache = types.SimpleNamespace(importEvents=lambda refs, events: written.extend(events))
		with patch.object(xmltv.eEPGCache, "getInstance", return_value=cache, create=True), \
				patch.object(xmltv.cfg, "xmltv_gaps", types.SimpleNamespace(value="off"), create=True):
			job.import_step()
		self.assertEqual([event[2] for event in written], ["safe"])


class PackTests(unittest.TestCase):
	def test_pack_metadata_contains_checksum_of_exported_file(self):
		job = clientpack.PackJob.__new__(clientpack.PackJob)
		job.chans = [("ref", "ref:", "channel")]
		job.epg = {"ref": {"n": "channel", "e": [[1, 100, 60, "event", "", ""]]}}
		job.bq = [{"name": "bouquet", "ch": [["channel", "ref"]]}]
		job.t0 = 0
		with tempfile.TemporaryDirectory() as directory:
			with patch.object(clientpack, "pack_dir", return_value=directory), \
					patch.object(clientpack, "_picon_index", return_value={}):
				job.write()
			with open(os.path.join(directory, "epg.json.gz"), "rb") as f:
				checksum = hashlib.sha256(f.read()).hexdigest()
			with open(os.path.join(directory, "pack.json")) as f:
				meta = json.load(f)
			self.assertEqual(meta["epg_sha256"], checksum)

	def test_failed_cache_read_does_not_write_partial_pack(self):
		job = clientpack.PackJob.__new__(clientpack.PackJob)
		job.chans = [("ref", "ref:", "channel")]
		job.i = 0
		job.epg = {}
		results = []
		job.finished = results.append
		cache = types.SimpleNamespace(lookupEvent=lambda _query: (_ for _ in ()).throw(RuntimeError("failure")))
		with patch.object(clientpack.eEPGCache, "getInstance", return_value=cache, create=True):
			job.step()
		self.assertIn("failure", results[0])
		self.assertIn("poprzednia paczka", results[0])
		self.assertEqual(job.epg, {})

	def test_rebuild_queued_when_pack_is_running(self):
		clientpack._job = object()
		clientpack._rebuild_requested = False
		try:
			clientpack.request_rebuild()
			self.assertTrue(clientpack._rebuild_requested)
		finally:
			clientpack._job = None
			clientpack._rebuild_requested = False

	def test_queued_rebuild_starts_after_current_pack_finishes(self):
		job = clientpack.PackJob.__new__(clientpack.PackJob)
		job.done = None
		last = types.SimpleNamespace(value="", save=lambda: None)
		clientpack._job = job
		clientpack._rebuild_requested = True
		try:
			with patch.object(clientpack.cfg, "cli_pack_last", last, create=True), \
					patch.object(clientpack, "build", return_value=True) as build:
				job.finished("ok")
			build.assert_called_once_with()
			self.assertFalse(clientpack._rebuild_requested)
		finally:
			clientpack._job = None
			clientpack._rebuild_requested = False


class DaytimeScheduleTests(unittest.TestCase):
	def test_all_mode_scans_every_transponder_even_with_long_epg(self):
		def setting(value):
			return types.SimpleNamespace(value=value)
		cfg = types.SimpleNamespace(
			topup=setting(True), sat_import=setting(True), topup_every=setting(3),
			last_topup=setting(0), last_topup_fixed=setting(0), topup_when=setting("always"),
			sched_skip_rec=setting(False), topup_mode=setting("all"))
		jobs = [types.SimpleNamespace(), types.SimpleNamespace()]
		started = []
		engine = types.SimpleNamespace(running=False, jobs=jobs, prepare=lambda: None,
			start=lambda **kwargs: started.append(kwargs))
		scheduler = schedule.Scheduler.__new__(schedule.Scheduler)
		scheduler.t_boot = 0
		scheduler.session = None
		scheduler.fixed_due = lambda now: 0
		with patch.object(schedule, "cfg", cfg), patch.object(schedule, "get_engine", return_value=engine), \
				patch.object(schedule, "sched_active", return_value=False), \
				patch.object(schedule, "topup_candidates", side_effect=AssertionError("short mode called")), \
				patch.object(schedule.time, "time", return_value=20000):
			scheduler.check_topup()
		self.assertEqual(started, [{"topup_jobs": jobs}])
		self.assertEqual(cfg.last_topup.value, 20000)


if __name__ == "__main__":
	unittest.main()
