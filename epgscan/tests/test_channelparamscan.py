import importlib
import base64
import os
import sys
import tempfile
import time
import types
import unittest
from unittest.mock import patch


ROOT = os.path.dirname(os.path.dirname(__file__))
PACKAGE = "channelparamscan_test_plugin"
package = types.ModuleType(PACKAGE)
package.__path__ = [os.path.join(ROOT, "EPGScan")]
sys.modules[PACKAGE] = package


class FakeTimer:
	def __init__(self):
		self.callback = []

	def start(self, *_args):
		pass

	def stop(self):
		pass


enigma = types.ModuleType("enigma")
enigma.eTimer = FakeTimer
enigma.eServiceReference = lambda ref: ref
enigma.iRecordableService = types.SimpleNamespace(evTunedIn=1, evTuneFailed=2, evRecordFailed=3)
enigma.eEPGCache = type("FakeEPGCache", (), {})
sys.modules.setdefault("enigma", enigma)

navigation = types.ModuleType("NavigationInstance")
navigation.instance = types.SimpleNamespace(record_event=[], RecordTimer=types.SimpleNamespace(isRecording=lambda: False))
sys.modules.setdefault("NavigationInstance", navigation)


def setting(value):
	return types.SimpleNamespace(value=value)


common = types.ModuleType(PACKAGE + ".common")
common.CHANNEL_PARAMS_FILE = os.path.join(tempfile.gettempdir(), "channel-params-test.json")
common.CP_SCHED_DAYS = [setting(True) for _ in range(7)]
common.MAX_RETRIES = 3
common.NO_TUNER_WAIT = 15
common.TUNE_GAP = 2
common.TUNE_TIMEOUT = 30
common.channel_params_bouquet_channels = lambda _files: []
common.cfg = types.SimpleNamespace(cp_language_source=setting("manual"), cp_languages=setting("pol"), cp_sched=setting(False),
	cp_sched_time=setting((3, 30)), cp_sched_when=setting("standby"), cp_sched_skip_rec=setting(False),
	cp_sched_wakeup=setting(False), cp_last_run=setting(0), cp_auto_in_progress=setting(False),
	sched_when=setting("standby"), sched_skip_rec=setting(False),
	max_parallel=setting(2), reserve=setting(1))
common.configured_tuners = lambda: 4
common.frontend_of = lambda _service: (-1, None)
common.log = lambda _message: None
common.same_tp = lambda a, b: bool(a and b and a == b)
common.selected_bouquets = lambda: []
common.service_name = lambda ref: ref
common.tp_key = lambda _ref: None
common.tp_params = lambda _ref: None
common.save_cfg = lambda: None
common.keys_skin = lambda *_args: ""
common.hints_skin = lambda *_args: ""
common.init_keys = lambda _screen: None
common.init_hints = lambda _screen: None
common.set_keys = lambda *_args, **_kwargs: None
common.set_key_color = lambda *_args: None
common.KEY_COLORS = [(0, 0)] * 4
common.KEY_EXIT = 0
common.EPGChoiceBox = type("FakeChoiceBox", (), {})
common.eit_on = lambda: True
common.VERSION = "test"
common.log_path = lambda: ""
common.epgrefresh_active = lambda: False
common.list_backups = lambda: []
common.restore_backup = lambda *_args: None
common.backup_label = lambda _path: ""
common.new_bouquets = lambda: []
common.clears_now = lambda *_args: False
common.epg_stats = lambda *_args: {}
common.stats_text = lambda *_args: ""
common.all_refs = lambda: []
common.log_dups = lambda *_args: None
common.export_dups = lambda *_args: None
common.scale_skins = lambda *_args: None
common.is_due = lambda: False
common.sched_active = lambda: False
common.next_run = lambda: 0
common.last_slot = lambda: 0
common.fmt_ts = lambda _timestamp: ""
common.WAKEUP_FILE = os.path.join(tempfile.gettempdir(), "epgscan-wakeup-test")
common.topup_candidates = lambda: []
common.topup_label = lambda _candidate: ""
common.TOPUP_INFO = {}
sys.modules[common.__name__] = common

channelparams = importlib.import_module(PACKAGE + ".channelparams")
scanner_module = importlib.import_module(PACKAGE + ".channelparamscan")
bouquetchannels = importlib.import_module(PACKAGE + ".bouquetchannels")
clients = types.ModuleType(PACKAGE + ".clients")
sys.modules[clients.__name__] = clients
store_module = importlib.import_module(PACKAGE + ".channelparamsstore")
engine_module = types.ModuleType(PACKAGE + ".engine")
engine_module.get_engine = lambda _session: None
sys.modules[engine_module.__name__] = engine_module
schedule_module = importlib.import_module(PACKAGE + ".schedule")


screens = types.ModuleType("Screens")
screens.__path__ = []
screen_module = types.ModuleType("Screens.Screen")
screen_module.Screen = type("FakeScreen", (dict,), {})
messagebox_module = types.ModuleType("Screens.MessageBox")
messagebox_module.MessageBox = type("FakeMessageBox", (), {"TYPE_INFO": 0, "TYPE_YESNO": 1})
audio_module = types.ModuleType("Screens.AudioSelection")
audio_module.AudioSelection = type("FakeAudioSelection", (), {})
components = types.ModuleType("Components")
components.__path__ = []
action_module = types.ModuleType("Components.ActionMap")
action_module.ActionMap = type("FakeActionMap", (), {})
label_module = types.ModuleType("Components.Label")
label_module.Label = type("FakeLabel", (), {})
menulist_module = types.ModuleType("Components.MenuList")
menulist_module.MenuList = type("FakeMenuList", (), {})
selection_module = types.ModuleType("Components.SelectionList")
selection_module.SelectionList = type("FakeSelectionList", (), {})
selection_module.SelectionEntryComponent = lambda *args: args
scroll_module = types.ModuleType("Components.ScrollLabel")
scroll_module.ScrollLabel = type("FakeScrollLabel", (), {})
ui_modules = {
	"Screens": screens, "Screens.Screen": screen_module, "Screens.MessageBox": messagebox_module,
	"Screens.AudioSelection": audio_module, "Components": components,
	"Components.ActionMap": action_module, "Components.Label": label_module,
	"Components.MenuList": menulist_module, "Components.SelectionList": selection_module,
	"Components.ScrollLabel": scroll_module,
}
with patch.dict(sys.modules, ui_modules):
	channelparamsui = importlib.import_module(PACKAGE + ".channelparamsui")
	scan_module = importlib.import_module(PACKAGE + ".scan")

PMT_BASE64 = "ArAsQo/DAADhGPAAG+EY8AAE4hjwBgoEcG9sAAbjGPAKegKAAgoEcWFhAEkPdSI="


class ChannelParamScanTests(unittest.TestCase):
	def test_channel_params_screen_does_not_shadow_enigma_screen_keys(self):
		class FakeList(object):
			def setList(self, _rows):
				pass

		class FakeLabel(object):
			def setText(self, _text):
				pass

		screen = channelparamsui.ChannelParamsScreen.__new__(channelparamsui.ChannelParamsScreen)
		screen["list"] = FakeList()
		screen["summary"] = FakeLabel()
		screen["help"] = FakeLabel()
		screen.engine = None
		with patch.object(channelparamsui, "load_results", return_value={"results": {}, "last_scan": {}}), \
				patch.object(channelparamsui, "set_keys") as set_keys:
			screen.refresh()
		self.assertEqual(set(screen.keys()), {"list", "summary", "help"})
		self.assertEqual(set_keys.call_args.kwargs["green"], "")
		self.assertEqual(set_keys.call_args.kwargs["yellow"], "")

	def test_actions_channel_params_choice_starts_background_scan(self):
		engine = types.SimpleNamespace(running=False)
		screen = scan_module.EPGScanScreen.__new__(scan_module.EPGScanScreen)
		screen.engine = engine
		screen.grab = None
		opened = []
		redrawn = []
		screen.session = types.SimpleNamespace(open=lambda *args, **kwargs: opened.append((args, kwargs)))
		screen.redraw = lambda: redrawn.append(True)
		with patch.object(scanner_module, "start_manual", return_value=True) as start_scan:
			screen.actions_cb("channelparams")
		start_scan.assert_called_once_with(engine)
		self.assertEqual(redrawn, [True])
		self.assertEqual(opened, [])

	def test_channel_scan_keeps_two_tuners_free_on_six_tuner_receiver(self):
		engine = types.SimpleNamespace(running=False, phase="")
		with patch.object(scanner_module, "configured_tuners", return_value=6), \
				patch.object(scanner_module, "channel_params_bouquet_channels", return_value=[]), \
				patch.object(common.cfg.reserve, "value", 1):
			self.assertFalse(scanner_module.start_manual(engine))
		self.assertEqual(engine.reserve, 2)

	def test_bouquet_scan_flattens_subbouquets_and_reports_iptv(self):
		with tempfile.TemporaryDirectory() as directory:
			with open(os.path.join(directory, "bouquets.tv"), "w") as f:
				f.write('#SERVICE 1:64:1:0:0:0:0:0:0:0:FROM BOUQUET "main.tv" ORDER BY bouquet\n')
			with open(os.path.join(directory, "main.tv"), "w") as f:
				f.write('#SERVICE 1:0:1:428f:1:1:13e:0:0:0:\n#DESCRIPTION DVB channel\n')
				f.write('#SERVICE 4097:0:1:0:0:0:0:0:0:0:http%3a//example/stream\n#DESCRIPTION IPTV channel\n')
				f.write('#SERVICE 1:64:1:0:0:0:0:0:0:0:FROM BOUQUET "main.tv" ORDER BY bouquet\n')
			rows = bouquetchannels.read_bouquet_channels(["bouquets.tv"], directory, lambda ref: ref)
		self.assertEqual(len(rows), 2)
		self.assertEqual(rows[0]["name"], "DVB channel")
		self.assertTrue(rows[0]["supported"])
		self.assertEqual(rows[1]["name"], "IPTV channel")
		self.assertFalse(rows[1]["supported"])

	def test_channel_jobs_group_interleaved_bouquet_channels_by_transponder(self):
		rows = [
			{"ref": "service-one", "name": "One"},
			{"ref": "service-two", "name": "Two"},
			{"ref": "service-three", "name": "Three"},
		]
		with patch.object(scanner_module, "tp_params",
				side_effect=[(11000, 0, 192), (12000, 1, 192), (11000, 0, 192)]):
			jobs = [scanner_module.ChannelJob(row) for row in rows]

		groups = scanner_module.group_channel_jobs(jobs)

		self.assertEqual([[job.ref for job in group.pending] for group in groups],
			[["service-one", "service-three"], ["service-two"]])

	def test_channel_jobs_use_service_reference_when_transponder_data_is_missing(self):
		rows = [{"ref": "service-one", "name": "One"}, {"ref": "service-two", "name": "Two"}]
		with patch.object(scanner_module, "tp_params", return_value=None), \
				patch.object(scanner_module, "tp_key", side_effect=[(1, 2, 3), (1, 2, 3)]):
			jobs = [scanner_module.ChannelJob(row) for row in rows]

		groups = scanner_module.group_channel_jobs(jobs)

		self.assertEqual(len(groups), 1)

	def test_completed_channel_holds_tuner_for_next_channel_on_transponder(self):
		engine = types.SimpleNamespace(active=[])
		engine.release = lambda job: engine.active.remove(job) if job in engine.active else None
		scan = scanner_module.ChannelParamsScan(engine)
		group = scanner_module.TransponderGroup((11000, 0, 192))
		first = scanner_module.ChannelJob({"ref": "service-one", "name": "One"})
		second = scanner_module.ChannelJob({"ref": "service-two", "name": "Two"})
		first.group = second.group = group
		first.rec = object()
		group.current = first
		group.pending.append(second)
		scan.active.append(first)
		engine.active.append(first)

		scan._complete_channel(first)

		self.assertIs(group.reservation, first)
		self.assertIn(first, engine.active)
		self.assertNotIn(first, scan.active)

		group.pending.clear()
		second.rec = object()
		group.current = second
		scan.active.append(second)
		engine.active.append(second)
		scan._complete_channel(second)

		self.assertTrue(group.done)
		self.assertEqual(engine.active, [])
		self.assertEqual(scan.reservations, [])

	def test_scheduler_starts_other_transponders_before_revisiting_reserved_group(self):
		engine = types.SimpleNamespace(active=[], timer_hold=0, max_parallel=2,
			scan_usage=lambda: None, upcoming_recordings=lambda: 0, eff_reserve=lambda: 1,
			free_tuners=lambda: 4, on_busy_tp=lambda _job: False, notify=lambda: None)
		engine.release = lambda job: engine.active.remove(job) if job in engine.active else None
		scan = scanner_module.ChannelParamsScan(engine)
		jobs = [scanner_module.ChannelJob({"ref": ref, "name": ref}) for ref in
			("tp-one-a", "tp-two-a", "tp-one-b", "tp-two-b", "tp-three-a", "tp-one-c")]
		jobs[0].tp = jobs[2].tp = jobs[5].tp = (11000, 0, 192)
		jobs[1].tp = jobs[3].tp = (12000, 1, 192)
		jobs[4].tp = (13000, 0, 192)
		scan.groups = scanner_module.group_channel_jobs(jobs)
		scan.queue = list(jobs)
		started = []

		def start(job, shared):
			started.append((job.ref, shared))
			job.state = "scanning"
			job.group.current = job
			scan.active.append(job)
			engine.active.append(job)
			return True

		scan._start = start
		scan.last_tune = 0
		scan.tick()
		self.assertEqual(started, [("tp-one-a", False)])

		scan.last_tune = 0
		scan._complete_channel(jobs[0])
		scan.tick()
		self.assertEqual(started[-1], ("tp-two-a", False))

		scan.last_tune = 0
		scan._complete_channel(jobs[1])
		scan.tick()
		self.assertEqual(started[-1], ("tp-one-b", True))

		scan._complete_channel(jobs[2])
		scan.last_tune = 0
		scan.tick()
		self.assertNotEqual(started[-1][0], "tp-three-a")

	def test_tuner_reserve_shortage_releases_transponder_and_delays_resume(self):
		engine = types.SimpleNamespace(active=[], timer_hold=0, max_parallel=4,
			scan_usage=lambda: None, upcoming_recordings=lambda: 0, eff_reserve=lambda: 2,
			free_tuners=lambda: 1, on_busy_tp=lambda _job: False, notify=lambda: None)
		engine.release = lambda job: engine.active.remove(job) if job in engine.active else None
		scan = scanner_module.ChannelParamsScan(engine)
		group = scanner_module.TransponderGroup((11000, 0, 192))
		anchor = scanner_module.ChannelJob({"ref": "tp-one-a", "name": "One"})
		next_job = scanner_module.ChannelJob({"ref": "tp-one-b", "name": "Two"})
		anchor.group = next_job.group = group
		anchor.rec = object()
		group.reservation = anchor
		group.pending.append(next_job)
		scan.groups = [group]
		scan.queue = [next_job]
		scan.reservations = [anchor]
		engine.active.append(anchor)

		scan.tick()

		self.assertIsNone(group.reservation)
		self.assertNotIn(anchor, engine.active)
		self.assertEqual(group.pending, [next_job])
		self.assertGreater(group.not_before, time.time())

	def test_six_tuners_and_two_reserved_allow_four_transponder_groups(self):
		engine = types.SimpleNamespace(active=[], timer_hold=0, max_parallel=4,
			scan_usage=lambda: None, upcoming_recordings=lambda: 0, eff_reserve=lambda: 2,
			free_tuners=lambda: 6, on_busy_tp=lambda _job: False, notify=lambda: None)
		scan = scanner_module.ChannelParamsScan(engine)
		jobs = [scanner_module.ChannelJob({"ref": "tp-%d" % i, "name": "TP %d" % i}) for i in range(6)]
		for i, job in enumerate(jobs):
			job.tp = (11000 + i * 1000, 0, 192)
		scan.groups = scanner_module.group_channel_jobs(jobs)
		scan.queue = list(jobs)
		started = []

		def start(job, shared):
			started.append(job)
			job.state = "scanning"
			job.group.current = job
			scan.active.append(job)
			return True

		scan._start = start
		for _ in jobs:
			scan.last_tune = 0
			scan.tick()

		self.assertEqual(len(started), 4)
		self.assertEqual(len(scan.active), 4)

	def test_successful_channel_recommends_only_unique_preferred_audio_and_keeps_raw_pmt(self):
		section = base64.b64decode(PMT_BASE64)
		pmt = channelparams.parse_pmt(section, 17039)
		engine = types.SimpleNamespace(active=[], phase="", channel_params_scan=None)
		scan = scanner_module.ChannelParamsScan(engine)
		job = types.SimpleNamespace(ref="1:0:1:428f:1:1:13e:0:0:0:", name="test", pmt_pid=307,
			pat_section=b"pat", state="")

		scan._save_channel(job, pmt)

		result = scan.results[job.ref]
		self.assertEqual(result["preferredAudioPid"], 536)
		self.assertEqual(result["selectedAudioPid"], 536)
		self.assertFalse(result["selectedConfirmed"])
		self.assertEqual(__import__("base64").b64decode(result["section"]), section)
		self.assertEqual(job.state, "gotowe")

	def test_previous_manual_audio_is_preserved_when_pid_is_still_available(self):
		section = base64.b64decode(PMT_BASE64)
		pmt = channelparams.parse_pmt(section, 17039)
		ref = "1:0:1:428f:1:1:13e:0:0:0:"
		engine = types.SimpleNamespace(active=[], phase="", channel_params_scan=None, timer_hold=0, max_parallel=1,
			scan_usage=lambda: None, upcoming_recordings=lambda: 0, eff_reserve=lambda: 0, free_tuners=lambda: 1)
		scan = scanner_module.ChannelParamsScan(engine)
		scan.results[ref] = {"selectedAudioPid": 792, "selectedConfirmed": True}
		job = types.SimpleNamespace(ref=ref, name="test", pmt_pid=307, pat_section=b"pat", state="")

		scan._save_channel(job, pmt)

		self.assertEqual(scan.results[ref]["selectedAudioPid"], 792)
		self.assertTrue(scan.results[ref]["selectedConfirmed"])

	def test_language_preference_is_read_from_enigma_when_configured(self):
		common.cfg.cp_language_source.value = "enigma"
		config_module = types.ModuleType("Components.config")
		config_module.config = types.SimpleNamespace(autolanguage=types.SimpleNamespace(
			audio_autoselect1=setting("pol"), audio_autoselect2=setting("eng"),
			audio_autoselect3=setting(""), audio_autoselect4=setting("")))
		with patch.dict(sys.modules, {"Components.config": config_module}):
			self.assertEqual(scanner_module.preferred_languages(), ["pol", "eng", "", ""])
		common.cfg.cp_language_source.value = "manual"

	def test_schedule_day_uses_the_planned_start_weekday(self):
		common.cfg.cp_sched.value = True
		common.CP_SCHED_DAYS[3].value = False
		with patch.object(scanner_module.time, "localtime", return_value=types.SimpleNamespace(tm_wday=3)):
			self.assertFalse(scanner_module.scheduled_day_enabled(1))
		common.CP_SCHED_DAYS[3].value = True
		with patch.object(scanner_module.time, "localtime", return_value=types.SimpleNamespace(tm_wday=3)):
			self.assertTrue(scanner_module.scheduled_day_enabled(1))

	def test_standalone_schedule_is_due_without_an_epg_schedule(self):
		common.cfg.cp_sched.value = True
		common.cfg.cp_sched_time.value = (3, 30)
		common.cfg.cp_last_run.value = 0
		local = types.SimpleNamespace(tm_year=2026, tm_mon=1, tm_mday=7, tm_wday=2)
		slot = int(scanner_module.time.mktime((2026, 1, 7, 3, 30, 0, 0, 0, -1)))
		with patch.object(scanner_module.time, "localtime", return_value=local):
			self.assertEqual(scanner_module.scheduled_slot(slot + 3600), slot)
			common.cfg.cp_last_run.value = slot
			self.assertEqual(scanner_module.scheduled_slot(slot + 3600), 0)

	def test_scheduler_starts_channel_scan_when_epg_schedule_is_off(self):
		common.cfg.cp_sched.value = True
		common.cfg.cp_sched_when.value = "always"
		engine = types.SimpleNamespace(running=False, auto=False, woke=False)
		scheduler = object.__new__(schedule_module.Scheduler)
		scheduler.first = False
		scheduler.resume = False
		scheduler.cp_resume = False
		scheduler.woke = False
		scheduler.waiting = ""
		scheduler.session = object()
		with patch.object(scanner_module, "scheduled_slot", return_value=123), \
				patch.object(scanner_module, "start_scheduled", return_value=True) as start_scan, \
				patch.object(schedule_module, "get_engine", return_value=engine):
			scheduler.check()
		start_scan.assert_called_once_with(engine, 123, woke=False)

	def test_channel_scan_can_run_while_due_epg_scan_waits_for_standby(self):
		common.cfg.cp_sched.value = True
		common.cfg.sched_when.value = "standby"
		common.cfg.cp_sched_when.value = "always"
		engine = types.SimpleNamespace(running=False, auto=False, woke=False)
		scheduler = object.__new__(schedule_module.Scheduler)
		scheduler.first = False
		scheduler.resume = False
		scheduler.cp_resume = False
		scheduler.woke = False
		scheduler.waiting = ""
		scheduler.session = object()
		with patch.object(common, "sched_active", return_value=True), \
				patch.object(common, "is_due", return_value=True), \
				patch.object(scanner_module, "scheduled_slot", return_value=123), \
				patch.object(scanner_module, "start_scheduled", return_value=True) as start_scan, \
				patch.object(schedule_module, "get_engine", return_value=engine), \
				patch.object(schedule_module, "in_standby", return_value=False):
			scheduler.check()
		start_scan.assert_called_once_with(engine, 123, woke=False)

	def test_standalone_scheduled_scan_finishes_and_clears_resume_flag(self):
		with tempfile.TemporaryDirectory() as directory:
			engine = types.SimpleNamespace(active=[], phase="", channel_params_scan=None, running=False,
				timer_hold=0, max_parallel=1, scan_usage=lambda: None, upcoming_recordings=lambda: 0,
				eff_reserve=lambda: 0, free_tuners=lambda: 1, notify=lambda: None, maybe_deep_standby=lambda: None)
			row = {"ref": "4097:0:1:0:0:0:0:0:0:0:http%3a//example/stream",
				"name": "IPTV channel", "supported": False, "error": "Nieobslugiwany typ kanalu"}
			with patch.object(scanner_module, "CHANNEL_PARAMS_FILE", os.path.join(directory, "results.json")), \
					patch.object(scanner_module, "channel_params_bouquet_channels", return_value=[row]):
				self.assertTrue(scanner_module.start_scheduled(engine, 123))
				self.assertTrue(common.cfg.cp_auto_in_progress.value)
				engine.channel_params_scan.tick()
			self.assertFalse(common.cfg.cp_auto_in_progress.value)
			self.assertEqual(common.cfg.cp_last_run.value, 123)

	def test_common_defaults_only_fill_missing_entries_and_backup_file(self):
		with tempfile.TemporaryDirectory() as directory:
			clients.DIR = directory
			clients.COMMON = os.path.join(directory, "common.json")
			key = "audio_1_0_1_428F_1_1_13E_0_0_0"
			with open(clients.COMMON, "w") as f:
				import json
				json.dump({"data": {key: {"v": "old"}}}, f)
			results = {"r": {"ref": "1:0:1:428f:1:1:13e:0:0:0:", "selectedConfirmed": True,
				"selectedAudioPid": 536, "sid": 17039, "section": PMT_BASE64,
				"streams": [{"kind": "audio", "pid": 536, "languages": ["pol"], "codec": "audio/mpeg"}]}}

			message, conflicts = store_module.add_common_defaults(results)

			self.assertIn("Dodano 1 wartosci", message)
			self.assertIn(key, conflicts)
			with open(clients.COMMON) as f:
				data = __import__("json").load(f)
			self.assertEqual(data["data"][key]["v"], "old")
			self.assertEqual(data["data"]["pmts_17039"]["v"], PMT_BASE64)
			self.assertTrue(any(name.startswith("common.json.bak-") for name in os.listdir(directory)))

	def test_device_profiles_are_backed_up_and_only_selected_device_is_updated(self):
		with tempfile.TemporaryDirectory() as directory:
			clients.DIR = directory
			for name in ("device-one.json", "device-two.json"):
				with open(os.path.join(directory, name), "w") as f:
					import json
					json.dump({"data": {}, "device": {"name": name}}, f)
			results = {"r": {"ref": "1:0:1:428f:1:1:13e:0:0:0:", "selectedConfirmed": True,
				"selectedAudioPid": 536, "sid": 17039, "section": PMT_BASE64,
				"streams": [{"kind": "audio", "pid": 536, "languages": ["pol"], "codec": "audio/mpeg"}]}}

			message, conflicts = store_module.apply_to_devices(results, ["device-one.json"])

			self.assertEqual(message, "Zapisano ustawienia w 1 odbiornikach.")
			self.assertEqual(conflicts, [])
			with open(os.path.join(directory, "device-one.json")) as f:
				updated = __import__("json").load(f)
			with open(os.path.join(directory, "device-two.json")) as f:
				untouched = __import__("json").load(f)
			self.assertEqual(updated["data"]["audio_1_0_1_428F_1_1_13E_0_0_0"]["v"], "536|pol|audio/mpeg")
			self.assertNotIn("audio_1_0_1_428F_1_1_13E_0_0_0", untouched["data"])
			self.assertTrue(any(name.startswith("device-one.json.bak-") for name in os.listdir(directory)))

	def test_device_write_failure_rolls_back_already_written_profiles(self):
		with tempfile.TemporaryDirectory() as directory:
			clients.DIR = directory
			originals = {}
			for name in ("device-one.json", "device-two.json"):
				path = os.path.join(directory, name)
				originals[name] = b'{"data": {}, "device": {}}'
				with open(path, "wb") as f:
					f.write(originals[name])
			results = {"r": {"ref": "1:0:1:428f:1:1:13e:0:0:0:", "selectedConfirmed": True,
				"selectedAudioPid": 536, "sid": 17039, "section": PMT_BASE64,
				"streams": [{"kind": "audio", "pid": 536, "languages": ["pol"], "codec": "audio/mpeg"}]}}
			real_write = store_module._atomic_json
			calls = []

			def fail_second(path, data):
				calls.append(path)
				if len(calls) == 2:
					raise OSError("disk full")
				real_write(path, data)

			with patch.object(store_module, "_atomic_json", side_effect=fail_second):
				with self.assertRaisesRegex(OSError, "disk full"):
					store_module.apply_to_devices(results, ["device-one.json", "device-two.json"])
			for name, content in originals.items():
				with open(os.path.join(directory, name), "rb") as f:
					self.assertEqual(f.read(), content)

	def test_unsupported_only_bouquets_still_write_an_explicit_report(self):
		with tempfile.TemporaryDirectory() as directory:
			result_path = os.path.join(directory, "channelparams.json")
			engine = types.SimpleNamespace(active=[], phase="", channel_params_scan=None, timer_hold=0, max_parallel=1,
				scan_usage=lambda: None, upcoming_recordings=lambda: 0, eff_reserve=lambda: 0, free_tuners=lambda: 1)
			engine.total_tuners = 1
			engine.reserve = 0
			row = {"ref": "4097:0:1:0:0:0:0:0:0:0:http%3a//example/stream",
				"name": "IPTV channel", "supported": False, "error": "Nieobslugiwany typ kanalu"}
			with patch.object(scanner_module, "CHANNEL_PARAMS_FILE", result_path), \
					patch.object(scanner_module, "channel_params_bouquet_channels", return_value=[row]) as bouquet_reader:
				scan = scanner_module.ChannelParamsScan(engine)
				self.assertTrue(scan.start())
				bouquet_reader.assert_called_once_with([])
				scan.tick()
			with open(result_path, encoding="utf-8") as f:
				data = __import__("json").load(f)
			self.assertEqual(data["last_scan"]["scanned"], 0)
			self.assertEqual(data["last_scan"]["unsupported"], [row])

	def test_retry_requeues_failed_job_and_cancel_releases_active_service(self):
		engine = types.SimpleNamespace(active=[], phase="", channel_params_scan=None)
		engine.release = lambda job: engine.active.remove(job) if job in engine.active else None
		scan = scanner_module.ChannelParamsScan(engine)
		job = types.SimpleNamespace(
			pat_reader=None, pmt_reader=None, rec=None, state="", retries=0, not_before=0, name="test", group=None)
		scan.active.append(job)
		engine.active.append(job)

		scan._retry_or_fail(job, "timeout")

		self.assertEqual(scan.active, [])
		self.assertEqual(engine.active, [])
		self.assertEqual(job.retries, 1)
		self.assertEqual(job.state, "czeka (ponownie)")
		self.assertEqual(scan.queue, [job])

		scan.active.append(job)
		engine.active.append(job)
		scan.queue = [types.SimpleNamespace()]
		navigation.instance.record_event.append(scan.rec_event)
		scan.cancel()

		self.assertEqual(scan.active, [])
		self.assertEqual(engine.active, [])
		self.assertEqual(scan.queue, [])
		self.assertEqual(job.state, "przerwany")
		self.assertNotIn(scan.rec_event, navigation.instance.record_event)

	def test_conflicting_pmts_with_same_sid_are_not_exported(self):
		first = bytearray(base64.b64decode(PMT_BASE64))
		second = bytearray(first)
		second[9] ^= 1
		second[-4:] = channelparams._crc32(second[:-4]).to_bytes(4, "big")

		def result(ref, section):
			return {"ref": ref, "selectedConfirmed": True, "selectedAudioPid": 536, "sid": 17039,
				"section": base64.b64encode(section).decode("ascii"),
				"streams": [{"kind": "audio", "pid": 536, "languages": ["pol"], "codec": "audio/mpeg"}]}

		audio, pmts, conflicts = store_module._sync_payload({
			"first": result("1:0:1:428f:1:1:13e:0:0:0:", first),
			"second": result("1:0:1:4290:1:1:13e:0:0:0:", second),
		})

		self.assertEqual(len(audio), 2)
		self.assertEqual(pmts, {})
		self.assertEqual(conflicts, {"pmts_17039"})

	def test_corrupt_pmt_is_rejected_before_profile_write(self):
		results = {"r": {"ref": "1:0:1:428f:1:1:13e:0:0:0:", "selectedConfirmed": True,
			"selectedAudioPid": 536, "sid": 17039, "section": "not-base64",
			"streams": [{"kind": "audio", "pid": 536, "languages": ["pol"], "codec": "audio/mpeg"}]}}
		with self.assertRaisesRegex(ValueError, "Nieprawidlowa PMT"):
			store_module._sync_payload(results)


if __name__ == "__main__":
	unittest.main()
