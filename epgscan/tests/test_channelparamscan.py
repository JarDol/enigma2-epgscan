import importlib
import ast
import base64
import json
import os
import sys
import tempfile
import time
import types
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import Mock, patch


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
common.MAX_RETRIES = 3
common.NO_TUNER_WAIT = 15
common.TUNE_GAP = 2
common.TUNE_TIMEOUT = 30
common.channel_params_bouquet_channels = lambda _files: []
common.cfg = types.SimpleNamespace(cp_language_source=setting("manual"), cp_languages=setting("pol"),
	cp_group_tp=setting(True), cp_retry_failed=setting(False), cp_interval=setting("7"), cp_last_run=setting(0), cp_auto_in_progress=setting(False),
	cp_sync_common=setting("off"), cp_sync_devices=setting("off"),
	cp_common_overwrite=setting(False), cp_devices_overwrite=setting(False),
	map_sort=setting("bq"), scope=types.SimpleNamespace(value="all",
		choices=types.SimpleNamespace(choices=[("all", "wszystkie"), ("selected", "wybrane"), ("tps", "transpondery")])),
	sched_when=setting("standby"), sched_skip_rec=setting(False),
	max_parallel=setting(2), reserve=setting(1))
common.configured_tuners = lambda: 4
common.frontend_of = lambda _service: (-1, None)
common.log = lambda _message: None
common.same_tp = lambda a, b: bool(a and b and a == b)
common.selected_bouquets = lambda: []
common.XB_CHOICES = [("all", "wszystkie bukiety"), ("selected", "wybrane bukiety")]
common.service_name = lambda ref: ref
common.tp_key = lambda _ref: None
common.tp_params = lambda _ref: None
common.save_cfg = lambda: None
common.keys_skin = lambda *_args: ""
common.hints_skin = lambda *_args, **_kwargs: ""
common.init_keys = lambda _screen: None
common.init_hints = lambda _screen, *_args: None
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
common.is_due = lambda now=None: False
common.sched_active = lambda: False
common.next_run = lambda: 0
common.last_slot = lambda now: 0
common.fmt_ts = lambda _timestamp: ""
common.WAKEUP_FILE = os.path.join(tempfile.gettempdir(), "epgscan-wakeup-test")
common.topup_candidates = lambda: []
common.topup_label = lambda _candidate: ""
common.TOPUP_INFO = {}
sys.modules[common.__name__] = common

channelparams = importlib.import_module(PACKAGE + ".channelparams")
scanner_module = importlib.import_module(PACKAGE + ".channelparamscan")
tunerreport_module = importlib.import_module(PACKAGE + ".tunerreport")
bouquetchannels = importlib.import_module(PACKAGE + ".bouquetchannels")
clients = types.ModuleType(PACKAGE + ".clients")
sys.modules[clients.__name__] = clients
store_module = importlib.import_module(PACKAGE + ".channelparamsstore")
engine_module = types.ModuleType(PACKAGE + ".engine")
engine_module.get_engine = lambda _session: None
sys.modules[engine_module.__name__] = engine_module
schedule_module = importlib.import_module(PACKAGE + ".schedule")
updater_module = importlib.import_module(PACKAGE + ".updater")
windowguard_module = importlib.import_module(PACKAGE + ".windowguard")


screens = types.ModuleType("Screens")
screens.__path__ = []
screen_module = types.ModuleType("Screens.Screen")
screen_module.Screen = type("FakeScreen", (dict,), {})
messagebox_module = types.ModuleType("Screens.MessageBox")
messagebox_module.MessageBox = type("FakeMessageBox", (), {"TYPE_INFO": 0, "TYPE_ERROR": 2, "TYPE_YESNO": 1})
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


def setUpModule():
	global trace_directory, trace_path_patch
	trace_directory = tempfile.TemporaryDirectory()
	trace_path_patch = patch.object(tunerreport_module, "CHANNEL_PARAMS_FILE",
		os.path.join(trace_directory.name, "channelparams.json"))
	trace_path_patch.start()


def tearDownModule():
	trace_path_patch.stop()
	trace_directory.cleanup()


class ChannelParamScanTests(unittest.TestCase):
	def test_standby_closes_plugin_and_overlays_but_not_older_dialogs(self):
		closed = []
		other = type("OtherDialog", (), {"close": lambda self: closed.append("other")})()
		plugin_type = type("ChannelParamsScreen", (), {
			"close": lambda self: closed.append("plugin")})
		plugin_type.__module__ = "Plugins.Extensions.EPGScan.channelparamsui"
		plugin = plugin_type()
		overlay_type = type("MessageBox", (), {"close": lambda self: closed.append("overlay")})
		overlay_type.__module__ = "Screens.MessageBox"
		overlay = overlay_type()
		session = types.SimpleNamespace(dialog_stack=[other, plugin, overlay])

		count = windowguard_module.close_plugin_dialogs(session)

		self.assertEqual(count, 2)
		self.assertEqual(closed, ["overlay", "plugin"])

	def test_standby_cancels_setup_instead_of_saving_it(self):
		calls = []
		setup_type = type("EPGScanSetup", (), {
			"cancel": lambda self: calls.append("cancel"),
			"close": lambda self: calls.append("close")})
		setup_type.__module__ = "Plugins.Extensions.EPGScan.setup"
		session = types.SimpleNamespace(dialog_stack=[setup_type()])

		self.assertEqual(windowguard_module.close_plugin_dialogs(session), 1)
		self.assertEqual(calls, ["cancel"])

	def test_single_channel_scan_limits_pat_pmt_read_to_selected_reference(self):
		rows = [
			{"ref": "service-one", "name": "One", "supported": True},
			{"ref": "service-two", "name": "Two", "supported": True},
		]
		engine = types.SimpleNamespace(running=False, phase="", notify=lambda: None)
		with patch.object(scanner_module, "channel_params_bouquet_channels", return_value=rows), \
				patch.object(scanner_module.ChannelParamsScan, "tick", lambda _scan: None):
			self.assertTrue(scanner_module.start_manual(engine, refs=["service-two"]))
		self.assertEqual([job.ref for job in engine.channel_params_scan.queue], ["service-two"])

	def test_channel_tune_does_not_treat_false_playservice_return_as_failure(self):
		class ServiceReference(object):
			def __init__(self, value):
				self.value = value

			def toString(self):
				return self.value

		class FakeNavigation(object):
			RecordTimer = types.SimpleNamespace(isRecording=lambda: False)

			def __init__(self):
				self.current = ServiceReference("original")

			def getCurrentlyPlayingServiceReference(self):
				return self.current

			def playService(self, reference):
				self.current = ServiceReference(reference)
				return False

		navigation_module = sys.modules["NavigationInstance"]
		navigation = FakeNavigation()
		result = {"ref": "service:chosen", "name": "Chosen",
			"streams": [{"kind": "audio", "pid": 123, "languages": ["pol"]}]}
		screen = channelparamsui.ChannelParamsScreen.__new__(channelparamsui.ChannelParamsScreen)
		screen.engine = types.SimpleNamespace(running=False, phase="")
		screen.orig = None
		screen.session = types.SimpleNamespace(open=lambda *_args, **_kwargs: None)
		screen.current = lambda: result
		screen["preview"] = types.SimpleNamespace(setText=lambda _text: None)
		screen.preview_timer = FakeTimer()
		with patch.object(navigation_module, "instance", navigation), \
				patch.object(screen, "_live_audio_tracks", return_value=([], None)), \
				patch.object(screen, "open_audio_picker") as open_picker:
			screen.choose_audio()
			screen.channel_ready()
		self.assertEqual(screen.pending_ref, "service:chosen")
		self.assertEqual(open_picker.call_count, 1)
		self.assertEqual(open_picker.call_args[0][1:3], (result["streams"], None))
		self.assertIsNot(open_picker.call_args[0][0], result)

	def test_live_enigma_tracks_open_picker_when_pat_pmt_has_no_audio(self):
		result = {"ref": "service:chosen", "name": "Chosen", "streams": []}
		live_streams = [{"kind": "audio", "pid": 562, "languages": ["pol"], "codec": "audio/mpeg-L2"}]
		screen = channelparamsui.ChannelParamsScreen.__new__(channelparamsui.ChannelParamsScreen)
		screen.pending_result = result
		screen.pending_ref = result["ref"]
		screen.engine = types.SimpleNamespace(running=False)
		screen.zap_attempts = 0
		screen.current = lambda: result
		screen._currently_on = lambda _ref: True
		screen._live_audio_tracks = lambda: (live_streams, 562)
		screen.open_audio_picker = lambda result_arg, streams, pid: opened.append((result_arg, streams, pid))
		opened = []

		screen.channel_ready()

		self.assertEqual(len(opened), 1)
		self.assertEqual(opened[0][1][0]["pid"], 562)
		self.assertEqual(opened[0][1][0]["source"], "enigma")
		self.assertEqual(opened[0][2], 562)
		self.assertIs(screen.pending_result, opened[0][0])

	def test_single_scan_fallback_rechecks_live_enigma_tracks(self):
		result = {"ref": "service:chosen", "name": "Chosen", "streams": []}
		live_streams = [{"kind": "audio", "pid": 563, "languages": ["mis"], "codec": "audio/mpeg-L2"}]
		opened = []
		screen = channelparamsui.ChannelParamsScreen.__new__(channelparamsui.ChannelParamsScreen)
		screen.pending_result = result
		screen.results = {result["ref"]: result}
		screen.refresh = lambda: None
		screen._live_audio_tracks = lambda: (live_streams, 563)
		screen.open_audio_picker = lambda result_arg, streams, pid: opened.append((result_arg, streams, pid))

		screen.single_scan_done({}, result)

		self.assertEqual(len(opened), 1)
		self.assertEqual(opened[0][1][0]["pid"], 563)
		self.assertEqual(opened[0][1][0]["source"], "enigma")
		self.assertEqual(opened[0][2], 563)

	def test_error_row_retries_selected_channel_even_when_live_audio_is_available(self):
		class FakeList(object):
			def __init__(self):
				self.index = 0

			def setList(self, rows):
				self.rows = rows

			def getSelectedIndex(self):
				return self.index

			def moveToIndex(self, index):
				self.index = index

		ref = "1:0:1:3ABD:514:13E:820000:0:0:0:"
		error = {"ref": ref, "name": "TVP 1 HD", "error": "brak PID PMT"}
		data = {"results": {}, "last_scan": {"errors": [error]}}
		screen = channelparamsui.ChannelParamsScreen.__new__(channelparamsui.ChannelParamsScreen)
		screen["list"] = FakeList()
		for name in ("head", "info", "preview"):
			screen[name] = types.SimpleNamespace(setText=Mock())
		screen.engine = types.SimpleNamespace(running=False, phase="")
		screen.session = Mock()
		screen.filter = "errors"
		screen.view_refs = []
		screen.orig = None
		screen.preview_timer = FakeTimer()
		navigation = types.SimpleNamespace(
			RecordTimer=types.SimpleNamespace(isRecording=lambda: False),
			getCurrentlyPlayingServiceReference=lambda: "original",
			playService=Mock())
		with patch.object(channelparamsui, "load_results", return_value=data), \
				patch.object(channelparamsui, "set_keys"), \
				patch.object(sys.modules["NavigationInstance"], "instance", navigation), \
				patch.object(channelparamsui, "start_manual", return_value=True) as start, \
				patch.object(screen, "_currently_on", side_effect=[False, True]), \
				patch.object(screen, "_live_audio_tracks", return_value=(
					[{"kind": "audio", "pid": 424, "languages": ["pol"]}], 424)), \
				patch.object(screen, "open_audio_picker") as picker:
			screen.refresh()
			self.assertEqual(screen.current()["ref"], ref)
			screen.choose_audio()
			screen.channel_ready()

		self.assertEqual(start.call_args.kwargs["refs"], [ref])
		picker.assert_not_called()
		self.assertEqual(navigation.playService.call_args.args[0], ref)

	def test_error_selection_stays_on_error_and_nonchannel_errors_are_reported(self):
		class FakeList(object):
			def __init__(self):
				self.index = 0

			def setList(self, _rows):
				self.index = 0

			def getSelectedIndex(self):
				return self.index

			def moveToIndex(self, index):
				self.index = index

		ref = "1:0:1:3ABD:514:13E:820000:0:0:0:"
		result = {"ref": ref, "name": "TVP 1 HD", "streams": []}
		data = {"results": {ref: result}, "last_scan": {"errors": [
			{"ref": ref, "name": "TVP 1 HD", "error": "blad odczytu"},
			{"name": "wyniki", "error": "blad zapisu"}],
			"unsupported": [{"ref": "iptv", "name": "IPTV"}]}}
		screen = channelparamsui.ChannelParamsScreen.__new__(channelparamsui.ChannelParamsScreen)
		screen["list"] = FakeList()
		for name in ("head", "info", "preview"):
			screen[name] = types.SimpleNamespace(setText=Mock())
		screen.engine = None
		screen.session = Mock()
		screen.filter = "all"
		screen.view_refs = []
		with patch.object(channelparamsui, "load_results", return_value=data), \
				patch.object(channelparamsui, "set_keys"):
			screen.refresh()
			screen["list"].index = 1
			self.assertEqual(screen.current()["ref"], ref)
			self.assertEqual(screen.current_error()["error"], "blad odczytu")
			screen.refresh()
			self.assertEqual(screen["list"].index, 1)
			screen["list"].index = 2
			screen.choose_audio()
			self.assertIn("Brak referencji", screen.session.open.call_args.args[1])
			screen["list"].index = 3
			self.assertIsNone(screen.current())
			self.assertIsNone(screen.current_error())

	def test_single_channel_scan_failure_is_reported_before_live_audio_picker(self):
		ref = "1:0:1:3ABD:514:13E:820000:0:0:0:"
		result = {"ref": ref, "name": "TVP 1 HD", "streams": []}
		screen = channelparamsui.ChannelParamsScreen.__new__(channelparamsui.ChannelParamsScreen)
		screen.pending_result = result
		screen.results = {}
		screen.refresh = Mock()
		screen.session = Mock()
		screen["preview"] = types.SimpleNamespace(setText=Mock())
		screen._live_audio_tracks = lambda: ([{"kind": "audio", "pid": 424, "languages": ["pol"]}], 424)
		screen.open_audio_picker = Mock()

		screen.single_scan_done({"errors": [{"ref": ref, "error": "brak danych demuksera"}]}, result)

		screen.session.openWithCallback.assert_called_once()
		message_args = screen.session.openWithCallback.call_args.args
		self.assertIn("brak danych demuksera", message_args[2])
		self.assertIn("PMT", message_args[2])
		screen.open_audio_picker.assert_not_called()
		message_args[0](None)
		self.assertEqual(screen.open_audio_picker.call_args.args[1][0]["pid"], 424)
		self.assertEqual(screen.open_audio_picker.call_args.args[1][0]["source"], "enigma")

	def test_error_channel_can_show_live_audio_when_retry_cannot_start(self):
		result = {"ref": "service:chosen", "name": "Chosen", "streams": []}
		screen = channelparamsui.ChannelParamsScreen.__new__(channelparamsui.ChannelParamsScreen)
		screen.pending_result = result
		screen.pending_ref = result["ref"]
		screen.pending_retry = True
		screen.results = {}
		screen.engine = types.SimpleNamespace(running=True)
		screen.session = Mock()
		screen["preview"] = types.SimpleNamespace(setText=Mock())
		screen._currently_on = lambda _ref: True
		screen._live_audio_tracks = lambda: ([{"kind": "audio", "pid": 424, "languages": ["pol"]}], 424)
		screen.open_audio_picker = Mock()

		screen.channel_ready()

		message_args = screen.session.openWithCallback.call_args.args
		self.assertIn("PMT", message_args[2])
		message_args[0](None)
		self.assertEqual(screen.open_audio_picker.call_args.args[1][0]["pid"], 424)

	def test_closed_screen_ignores_single_channel_scan_callback(self):
		result = {"ref": "service:chosen", "name": "Chosen"}
		screen = channelparamsui.ChannelParamsScreen.__new__(channelparamsui.ChannelParamsScreen)
		screen.pending_result = result
		screen.pending_ref = result["ref"]
		screen.preview_timer = FakeTimer()
		screen.restore = Mock()
		screen.engine = None
		screen.session = Mock()

		screen.cleanup()
		screen.single_scan_done({"errors": [{"ref": result["ref"], "error": "failed"}]}, result)

		screen.session.openWithCallback.assert_not_called()
		self.assertIsNone(screen.pending_result)

	def test_audio_picker_auditions_highlight_and_restores_track_on_cancel(self):
		class FakeList(object):
			def getSelectedIndex(self):
				return 1

		class FakeLabel(object):
			def setText(self, _text):
				pass

		previewed = []
		screen = channelparamsui.ChannelAudioScreen.__new__(channelparamsui.ChannelAudioScreen)
		screen["list"] = FakeList()
		screen["details"] = FakeLabel()
		screen.view_streams = [
			{"pid": 562, "languages": ["pol"], "codec": "audio/mpeg-L2"},
			{"pid": 563, "languages": ["mis"], "codec": "audio/mpeg-L2"},
		]
		screen.current_pid = 562
		screen.preview_track = previewed.append
		screen.close = lambda: previewed.append("closed")

		screen.selection_changed()
		screen.cancel()

		self.assertEqual(previewed, [563, 562, "closed"])

	def test_audio_cancel_callback_without_choice_is_safe(self):
		screen = channelparamsui.ChannelParamsScreen.__new__(channelparamsui.ChannelParamsScreen)
		screen.pending_result = {"ref": "service:chosen"}
		screen.pending_ref = "service:chosen"

		screen.audio_chosen()

		self.assertIsNone(screen.pending_result)
		self.assertIsNone(screen.pending_ref)

	def test_channel_parameters_info_shows_ok_menu_and_info_keys(self):
		result = {"name": "TV6 HD", "selectedAudioPid": 562,
			"selectedConfirmed": True, "streams": [
				{"kind": "audio", "pid": 562, "languages": ["pol"]},
				{"kind": "video", "pid": 1542, "codec": "H.264"},
			]}
		screen = channelparamsui.ChannelParamsScreen.__new__(channelparamsui.ChannelParamsScreen)
		screen.current = lambda: result
		screen["info"] = types.SimpleNamespace(setText=lambda text: setattr(screen, "info_text", text))

		screen.show_info()

		self.assertIn("OK:", screen.info_text)
		self.assertIn("Menu:", screen.info_text)
		self.assertIn("Info/EPG:", screen.info_text)

	def test_channel_params_screen_does_not_shadow_enigma_screen_keys(self):
		class FakeList(object):
			onSelectionChanged = []

			def setList(self, _rows):
				pass

			def getSelectedIndex(self):
				return -1

			def moveToIndex(self, _index):
				pass

		class FakeLabel(object):
			def setText(self, _text):
				pass

		screen = channelparamsui.ChannelParamsScreen.__new__(channelparamsui.ChannelParamsScreen)
		screen["list"] = FakeList()
		screen["head"] = FakeLabel()
		screen["preview"] = FakeLabel()
		screen["info"] = FakeLabel()
		screen.engine = None
		screen.filter = "all"
		screen.view_refs = []
		with patch.object(channelparamsui, "load_results", return_value={"results": {}, "last_scan": {}}), \
				patch.object(channelparamsui, "set_keys") as set_keys:
			screen.refresh()
		self.assertEqual(set(screen.keys()), {"list", "head", "preview", "info"})
		self.assertEqual(set_keys.call_args.kwargs["green"], "Odswiez")
		self.assertEqual(set_keys.call_args.kwargs["yellow"], "Pokaz: wszystkie")

	def test_actions_returns_run_profile_with_parameter_scan_toggle(self):
		screen = scan_module.EPGScanScreen.__new__(scan_module.EPGScanScreen)
		profile = {"sat": True, "channelparams": True}
		started = []
		screen.start = lambda value: started.append(value)
		screen.actions_cb(profile)
		self.assertEqual(started, [profile])

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

	def test_channel_jobs_can_disable_transponder_grouping(self):
		rows = [{"ref": "service-one", "name": "One"}, {"ref": "service-two", "name": "Two"}]
		with patch.object(scanner_module, "tp_params", return_value=(11000, 0, 192)):
			jobs = [scanner_module.ChannelJob(row) for row in rows]

		groups = scanner_module.group_channel_jobs(jobs, group_by_transponder=False)

		self.assertEqual(len(groups), 2)

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
		self.assertEqual(started, [("tp-one-a", False), ("tp-two-a", False)])

		scan._complete_channel(jobs[0])
		scan.tick()
		self.assertEqual(started[-1], ("tp-one-b", True))

		scan._complete_channel(jobs[1])
		scan.tick()
		self.assertEqual(started[-1], ("tp-two-b", True))

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

	def test_upcoming_timer_releases_reservation_and_blocks_new_tuning(self):
		engine = types.SimpleNamespace(active=[], timer_hold=0, max_parallel=4,
			scan_usage=lambda: None, upcoming_recordings=lambda: 1,
			free_tuners=lambda: 2, on_busy_tp=lambda _job: False, notify=lambda: None)
		engine.eff_reserve = lambda: 2 + engine.timer_hold
		engine.release = lambda job: engine.active.remove(job) if job in engine.active else None
		scan = scanner_module.ChannelParamsScan(engine)
		group = scanner_module.TransponderGroup((11000, 0, 192))
		anchor = scanner_module.ChannelJob({"ref": "tp-one-a", "name": "One"})
		anchor.group = group
		anchor.rec = object()
		group.reservation = anchor
		new_group = scanner_module.TransponderGroup((12000, 0, 192))
		next_job = scanner_module.ChannelJob({"ref": "tp-two-a", "name": "Two"})
		next_job.group = new_group
		new_group.pending.append(next_job)
		scan.groups = [group, new_group]
		scan.queue = [next_job]
		scan.reservations = [anchor]
		engine.active.append(anchor)
		scan._start = Mock()

		scan.tick()

		self.assertEqual(engine.timer_hold, 1)
		self.assertIsNone(group.reservation)
		scan._start.assert_not_called()
		self.assertEqual(scan.queue, [next_job])

	def test_timer_blocks_independent_starts_but_allows_shared_transponder(self):
		engine = types.SimpleNamespace(active=[], used={0}, timer_hold=0, max_parallel=6,
			scan_usage=lambda: None, upcoming_recordings=lambda: 1, eff_reserve=lambda: 3,
			free_tuners=lambda: 7, on_busy_tp=lambda job: job.ref == "shared", notify=lambda: None)
		scan = scanner_module.ChannelParamsScan(engine)
		jobs = [scanner_module.ChannelJob({"ref": ref, "name": ref}) for ref in ("shared", "new")]
		jobs[0].tp, jobs[1].tp = (11000, 0, 192), (12000, 0, 192)
		scan.groups = scanner_module.group_channel_jobs(jobs)
		scan.queue = list(jobs)
		started = []

		def start(job, shared):
			started.append((job.ref, shared))
			job.shared = shared
			job.group.current = job
			scan.active.append(job)
			return True

		scan._start = start
		scan.tick()
		self.assertEqual(started, [("shared", True)])
		self.assertEqual(scan.queue, [jobs[1]])

	def test_upcoming_timer_reclaims_an_unreported_frontend(self):
		upcoming = [0]
		engine = types.SimpleNamespace(active=[], used={0, 1}, timer_hold=0, max_parallel=6,
			scan_usage=lambda: None, upcoming_recordings=lambda: upcoming[0],
			free_tuners=lambda: 6, on_busy_tp=lambda _job: False, notify=lambda: None)
		engine.eff_reserve = lambda: 2 + engine.timer_hold
		released = []

		def release(job):
			released.append(job)
			engine.active.remove(job)
			job.rec = None

		engine.release = release
		scan = scanner_module.ChannelParamsScan(engine)
		jobs = [scanner_module.ChannelJob({"ref": str(i), "name": str(i)}) for i in range(5)]
		for i, job in enumerate(jobs):
			job.tp = (11000 + i * 1000, 0, 192)
		scan.groups = scanner_module.group_channel_jobs(jobs)
		scan.queue = list(jobs)

		def start(job, shared):
			job.rec = object()
			job.shared = shared
			job.state = "strojenie"
			job.t0 = time.time()
			job.group.current = job
			scan.active.append(job)
			engine.active.append(job)
			return True

		scan._start = start
		scan.tick()
		self.assertEqual(len(scan.active), 4)
		upcoming[0] = 1
		scan.tick()
		self.assertEqual(len(released), 1)
		self.assertEqual(len(scan.active), 3)
		self.assertEqual(len(scan.queue), 2)

	def test_timer_does_not_repeatedly_yield_while_frontend_mask_is_stale(self):
		upcoming = [0]
		engine = types.SimpleNamespace(active=[], used={0, 1}, timer_hold=0, max_parallel=6,
			scan_usage=lambda: None, upcoming_recordings=lambda: upcoming[0],
			on_busy_tp=lambda _job: False, notify=lambda: None)
		engine.free_tuners = lambda: 8 - len(engine.used)
		engine.eff_reserve = lambda: 2 + engine.timer_hold
		released = []

		def release(job):
			released.append(job.rec.number)
			engine.active.remove(job)
			job.rec = None

		engine.release = release
		scan = scanner_module.ChannelParamsScan(engine)
		jobs = [scanner_module.ChannelJob({"ref": str(i), "name": str(i)}) for i in range(5)]
		for i, job in enumerate(jobs):
			job.tp = (11000 + i * 1000, 0, 192)
		scan.groups = scanner_module.group_channel_jobs(jobs)
		scan.queue = list(jobs)

		def start(job, shared):
			job.rec = types.SimpleNamespace(number=2 + int(job.ref))
			job.shared = shared
			job.state = "strojenie"
			job.t0 = time.time()
			job.group.current = job
			scan.active.append(job)
			engine.active.append(job)
			return True

		scan._start = start
		scan.tick()
		self.assertEqual(len(scan.active), 4)
		engine.used.update({2, 3, 4, 5})
		scan.tick()
		self.assertEqual(released, [])
		upcoming[0] = 1
		scan.tick()
		self.assertEqual(len(released), 1)
		scan.tick()
		self.assertEqual(len(released), 1)
		upcoming[0] = 2
		scan.tick()
		self.assertEqual(len(released), 2)
		scan.tick()
		self.assertEqual(len(released), 2)
		engine.used.difference_update(released)
		scan.tick()
		self.assertEqual(len(scan.active), 2)

	def test_parallel_start_uses_available_tuners_without_consuming_reserve(self):
		for used, expected in ((set(), 6), ({0, 1}, 4)):
			with self.subTest(initially_used=used):
				engine = types.SimpleNamespace(active=[], used=set(used), timer_hold=0, max_parallel=6,
					scan_usage=lambda: None, upcoming_recordings=lambda: 0, eff_reserve=lambda: 2,
					on_busy_tp=lambda _job: False, notify=lambda: None)
				engine.free_tuners = lambda: 8 - len(engine.used)
				scan = scanner_module.ChannelParamsScan(engine)
				jobs = [scanner_module.ChannelJob({"ref": "tp-%d" % i, "name": "TP %d" % i})
					for i in range(8)]
				for i, job in enumerate(jobs):
					job.tp = (11000 + i * 1000, 0, 192)
				scan.groups = scanner_module.group_channel_jobs(jobs)
				scan.queue = list(jobs)
				started = []

				def start(job, shared):
					started.append((job, shared))
					job.state = "strojenie"
					job.t0 = time.time()
					job.group.current = job
					scan.active.append(job)
					engine.active.append(job)
					return True

				scan._start = start
				scan.tick()
				self.assertEqual(len(started), expected)
				self.assertTrue(all(not shared for _job, shared in started))
				# Enigma2 has not reported the newly reserved frontends yet.
				scan.tick()
				self.assertEqual(len(started), expected)

	def test_released_tuner_is_not_immediately_reused(self):
		now = [100.0]
		engine = types.SimpleNamespace(active=[], used={0}, timer_hold=0, max_parallel=6,
			scan_usage=lambda: None, upcoming_recordings=lambda: 0, eff_reserve=lambda: 2,
			on_busy_tp=lambda _job: False, notify=lambda: None)
		engine.free_tuners = lambda: 8 - len(engine.used)
		scan = scanner_module.ChannelParamsScan(engine)
		old = scanner_module.ChannelJob({"ref": "old", "name": "old"})
		new = scanner_module.ChannelJob({"ref": "new", "name": "new"})
		old.tp, new.tp = (11000, 0, 192), (12000, 0, 192)
		scan.groups = scanner_module.group_channel_jobs([old, new])
		old.group.pending.remove(old)
		old.group.current = old
		old.rec = object()
		old.tuner_no = 0
		scan.active.append(old)
		engine.active.append(old)
		scan.queue = [new]

		def release(job):
			engine.used.discard(job.tuner_no)
			engine.active.remove(job)
			job.rec = None

		engine.release = release
		def start(job, _shared):
			job.state = "strojenie"
			job.t0 = now[0]
			job.group.current = job
			scan.active.append(job)
			engine.active.append(job)
			return True

		scan._start = Mock(side_effect=start)
		with patch.object(scanner_module.time, "time", side_effect=lambda: now[0]), \
				patch.object(scanner_module.time, "monotonic", side_effect=lambda: now[0]):
			scan._release(old)
			scan.tick()
			scan._start.assert_not_called()
			now[0] += common.TUNE_GAP
			scan.tick()
		scan._start.assert_called_once_with(new, False)

	def test_serial_fallback_keeps_the_interval_between_tune_starts(self):
		now = [100.0]
		engine = types.SimpleNamespace(active=[], used=set(), timer_hold=0, max_parallel=6,
			scan_usage=lambda: None, upcoming_recordings=lambda: 0, eff_reserve=lambda: 2,
			free_tuners=lambda: 8, on_busy_tp=lambda _job: False, notify=lambda: None)
		scan = scanner_module.ChannelParamsScan(engine)
		scan.serial_tuning = True
		jobs = [scanner_module.ChannelJob({"ref": str(i), "name": str(i)}) for i in range(3)]
		for i, job in enumerate(jobs):
			job.tp = (11000 + i * 1000, 0, 192)
		scan.groups = scanner_module.group_channel_jobs(jobs)
		scan.queue = list(jobs)
		started = []

		def start(job, _shared):
			started.append(job)
			job.state = "strojenie"
			job.t0 = now[0]
			job.group.current = job
			scan.active.append(job)
			return True

		scan._start = start
		with patch.object(scanner_module.time, "time", side_effect=lambda: now[0]), \
				patch.object(scanner_module.time, "monotonic", side_effect=lambda: now[0]):
			scan.tick()
			self.assertEqual(len(started), 1)
			now[0] += 1
			scan.tick()
			self.assertEqual(len(started), 1)
			now[0] += 1
			scan.tick()
			self.assertEqual(len(started), 2)

	def test_scanner_can_start_a_second_tune_while_first_is_still_tuning(self):
		engine = types.SimpleNamespace(active=[], timer_hold=0, max_parallel=2,
			scan_usage=lambda: None, upcoming_recordings=lambda: 0, eff_reserve=lambda: 1,
			free_tuners=lambda: 4, on_busy_tp=lambda _job: False, notify=lambda: None)
		engine.release = lambda job: engine.active.remove(job) if job in engine.active else None
		scan = scanner_module.ChannelParamsScan(engine)
		jobs = [scanner_module.ChannelJob({"ref": "tp-%d" % i, "name": "TP %d" % i}) for i in range(2)]
		for i, job in enumerate(jobs):
			job.tp = (11000 + i * 1000, 0, 192)
		scan.groups = scanner_module.group_channel_jobs(jobs)
		scan.queue = list(jobs)
		started = []

		def start(job, shared):
			started.append(job)
			job.state = "strojenie"
			job.t0 = time.time()
			job.group.current = job
			scan.active.append(job)
			engine.active.append(job)
			return True

		scan._start = start
		scan.last_tune = 0
		scan.tick()
		scan.last_tune = 0
		scan.tick()

		self.assertEqual(len(started), 2)
		self.assertTrue(all(job.state == "strojenie" for job in started))

	def test_successful_channel_defaults_to_first_audio_and_keeps_raw_pmt(self):
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
		self.assertTrue(result["selectedConfirmed"])
		self.assertEqual(result["selectionSource"], "default")
		self.assertEqual(__import__("base64").b64decode(result["section"]), section)
		self.assertEqual(job.state, "gotowe")

	def test_scanner_uses_pat_pmt_pid_when_enigma_pid_list_is_missing_or_ambiguous(self):
		body = bytes.fromhex("00b0110001c100000000e010428fe133")
		pat = body + channelparams._crc32(body).to_bytes(4, "big")
		pmt = base64.b64decode(PMT_BASE64)
		for pids in ([], [(307, "pmt"), (308, "pmt")], [(999, "pmt")]):
			with self.subTest(pids=pids):
				with patch.object(scanner_module, "load_results", return_value={"results": {}}):
					scan = scanner_module.ChannelParamsScan(types.SimpleNamespace(active=[]))
				job = scanner_module.ChannelJob({
					"ref": "1:0:1:428F:1:1:13E:0:0:0:", "name": "test"})
				job.rec = types.SimpleNamespace(stream=lambda: types.SimpleNamespace(
					getStreamingData=lambda: {"adapter": 0, "demux": 2, "pids": pids}))
				job.tuned_at = 100
				job.state = "odczyt strumienia"
				scan._handle_failure = Mock()
				scan._complete_channel = Mock()
				pat_reader = Mock(read=Mock(return_value=pat))
				pmt_reader = Mock(read=Mock(return_value=pmt))
				with patch.object(scanner_module.time, "time", return_value=101), \
						patch.object(channelparams, "SectionReader",
							side_effect=[pat_reader, pmt_reader]) as readers:
					scan._begin_capture(job)
					scan._poll_capture(job)

				self.assertEqual([call.args for call in readers.call_args_list],
					[(0, 2, 0, 0), (0, 2, 307, 2)])
				self.assertEqual(scan.results[job.ref]["pmtPid"], 307)
				self.assertEqual(scan.results[job.ref]["section"], PMT_BASE64)
				scan._handle_failure.assert_not_called()
				scan._complete_channel.assert_called_once_with(job)

	def test_scanner_waits_for_stream_metadata_before_retrying_tune(self):
		with patch.object(scanner_module, "load_results", return_value={"results": {}}):
			scan = scanner_module.ChannelParamsScan(types.SimpleNamespace(active=[]))
		job = scanner_module.ChannelJob({"ref": "1:0:1:428F:1:1:13E:0:0:0:", "name": "test"})
		stream = types.SimpleNamespace(getStreamingData=Mock(side_effect=[None, {"adapter": 0, "demux": 1}]))
		job.rec = types.SimpleNamespace(stream=lambda: stream)
		job.tuned_at = 100
		job.state = "odczyt strumienia"
		scan._handle_failure = Mock()
		with patch.object(scanner_module.time, "time", return_value=101), \
				patch.object(channelparams, "SectionReader") as reader:
			scan._begin_capture(job)
			self.assertEqual(job.state, "odczyt strumienia")
			scan._begin_capture(job)
		scan._handle_failure.assert_not_called()
		reader.assert_called_once_with(0, 1, 0, 0)
		self.assertEqual(job.state, "zbieranie PAT/PMT")

	def test_pat_sections_without_the_service_are_skipped_until_its_pid_is_found(self):
		def pat(service):
			body = bytes.fromhex("00b0110001c100000000e010") + service.to_bytes(2, "big") + bytes.fromhex("e133")
			return body + channelparams._crc32(body).to_bytes(4, "big")

		with patch.object(scanner_module, "load_results", return_value={"results": {}}):
			scan = scanner_module.ChannelParamsScan(types.SimpleNamespace(active=[]))
		job = scanner_module.ChannelJob({"ref": "1:0:1:428F:1:1:13E:0:0:0:", "name": "test"})
		job.rec = types.SimpleNamespace(stream=lambda: types.SimpleNamespace(
			getStreamingData=lambda: {"adapter": 0, "demux": 2}))
		job.tuned_at = 100
		scan._handle_failure = Mock()
		scan._complete_channel = Mock()
		pat_reader = Mock(read=Mock(side_effect=[pat(17007), pat(17039)]))
		pmt_reader = Mock(read=Mock(return_value=base64.b64decode(PMT_BASE64)))
		with patch.object(scanner_module.time, "time", return_value=101), \
				patch.object(channelparams, "SectionReader", side_effect=[pat_reader, pmt_reader]) as readers:
			scan._begin_capture(job)
			scan._poll_capture(job)
			self.assertEqual(readers.call_count, 1)
			self.assertEqual(scan.results, {})
			scan._poll_capture(job)
		self.assertEqual(scan.results[job.ref]["pmtPid"], 307)
		scan._handle_failure.assert_not_called()

	def test_missing_stream_metadata_eventually_reports_an_error(self):
		with patch.object(scanner_module, "load_results", return_value={"results": {}}):
			scan = scanner_module.ChannelParamsScan(types.SimpleNamespace(active=[]))
		job = scanner_module.ChannelJob({"ref": "1:0:1:428F:1:1:13E:0:0:0:", "name": "test"})
		job.rec = types.SimpleNamespace(stream=lambda: None)
		job.tuned_at = 100
		scan._handle_failure = Mock()
		with patch.object(scanner_module.time, "time", return_value=110):
			scan._begin_capture(job)
		self.assertIn("Enigma2 nie udostepnila danych strumienia", scan._handle_failure.call_args.args[1])

	def test_retry_discards_the_previous_pat_before_reading_again(self):
		with patch.object(scanner_module, "load_results", return_value={"results": {}}):
			scan = scanner_module.ChannelParamsScan(types.SimpleNamespace(active=[]))
		job = scanner_module.ChannelJob({"ref": "1:0:1:428F:1:1:13E:0:0:0:", "name": "test"})
		job.rec = types.SimpleNamespace(stream=lambda: types.SimpleNamespace(
			getStreamingData=lambda: {"adapter": 0, "demux": 2}))
		job.tuned_at = 100
		job.pat_section = b"old pat"
		job.pmt_pid = 500
		with patch.object(scanner_module.time, "time", return_value=101), \
				patch.object(channelparams, "SectionReader"):
			scan._begin_capture(job)
		self.assertIsNone(job.pat_section)
		self.assertEqual(job.pmt_pid, -1)

	def test_previous_manual_audio_is_preserved_when_pid_is_still_available(self):
		section = base64.b64decode(PMT_BASE64)
		pmt = channelparams.parse_pmt(section, 17039)
		ref = "1:0:1:428f:1:1:13e:0:0:0:"
		engine = types.SimpleNamespace(active=[], phase="", channel_params_scan=None, timer_hold=0, max_parallel=1,
			scan_usage=lambda: None, upcoming_recordings=lambda: 0, eff_reserve=lambda: 0, free_tuners=lambda: 1)
		scan = scanner_module.ChannelParamsScan(engine)
		scan.results[ref] = {"selectedAudioPid": 792, "selectedConfirmed": True,
			"selectionSource": "manual", "selectedAt": 123}
		job = types.SimpleNamespace(ref=ref, name="test", pmt_pid=307, pat_section=b"pat", state="")

		scan._save_channel(job, pmt)

		self.assertEqual(scan.results[ref]["selectedAudioPid"], 792)
		self.assertTrue(scan.results[ref]["selectedConfirmed"])
		self.assertEqual((scan.results[ref]["selectionSource"], scan.results[ref]["selectedAt"]), ("manual", 123))

	def test_removed_manual_pid_falls_back_to_first_audio(self):
		pmt = channelparams.parse_pmt(base64.b64decode(PMT_BASE64), 17039)
		ref = "1:0:1:428f:1:1:13e:0:0:0:"
		with patch.object(scanner_module, "load_results", return_value={"results": {}}):
			scan = scanner_module.ChannelParamsScan(types.SimpleNamespace(active=[]))
		scan.results[ref] = {"selectedAudioPid": 999, "selectedConfirmed": True}
		job = types.SimpleNamespace(ref=ref, name="test", pmt_pid=307, pat_section=b"pat", state="")

		scan._save_channel(job, pmt)

		self.assertEqual(scan.results[ref]["selectedAudioPid"], 536)
		self.assertTrue(scan.results[ref]["selectedConfirmed"])
		self.assertEqual(scan.results[ref]["selectionSource"], "default")

	def test_preferred_audio_is_default_when_a_later_track_matches_language(self):
		pmt = channelparams.parse_pmt(base64.b64decode(PMT_BASE64), 17039)
		pmt["streams"][1]["languages"] = ["eng"]
		pmt["streams"][2]["languages"] = ["pol"]
		with patch.object(scanner_module, "load_results", return_value={"results": {}}):
			scan = scanner_module.ChannelParamsScan(types.SimpleNamespace(active=[]))
		job = types.SimpleNamespace(ref="1:0:1:428F:1:1:13E:0:0:0:", name="test", pmt_pid=307,
			pat_section=b"pat", state="")
		scan._save_channel(job, pmt)
		self.assertEqual(scan.results[job.ref]["selectedAudioPid"], 792)
		self.assertEqual(scan.results[job.ref]["preferredAudioPid"], 792)
		self.assertTrue(scan.results[job.ref]["selectedConfirmed"])
		screen = channelparamsui.ChannelParamsScreen.__new__(channelparamsui.ChannelParamsScreen)
		screen.filter = "pending"
		self.assertFalse(screen._matches_filter(scan.results[job.ref]))
		audio, _sources, pmts, conflicts = store_module._sync_payload(scan.results)
		self.assertEqual(len(audio), 1)
		self.assertIn("pmts_17039", pmts)
		self.assertEqual(conflicts, set())

	def test_automatic_default_follows_preferred_audio_when_order_changes(self):
		pmt = channelparams.parse_pmt(base64.b64decode(PMT_BASE64), 17039)
		pmt["streams"][1], pmt["streams"][2] = pmt["streams"][2], pmt["streams"][1]
		with patch.object(scanner_module, "load_results", return_value={"results": {}}):
			scan = scanner_module.ChannelParamsScan(types.SimpleNamespace(active=[]))
		job = types.SimpleNamespace(ref="1:0:1:428F:1:1:13E:0:0:0:", name="test", pmt_pid=307,
			pat_section=b"pat", state="")
		scan.results[job.ref] = {"selectedAudioPid": 536, "selectedConfirmed": True, "selectionSource": "default"}
		scan._save_channel(job, pmt)
		self.assertEqual(scan.results[job.ref]["selectedAudioPid"], 536)
		self.assertEqual(scan.results[job.ref]["selectionSource"], "default")

	def test_beta8_defaults_migrate_to_preferred_language_but_confirmed_legacy_is_preserved(self):
		pmt = channelparams.parse_pmt(base64.b64decode(PMT_BASE64), 17039)
		ref = "1:0:1:428F:1:1:13E:0:0:0:"
		job = types.SimpleNamespace(ref=ref, name="test", pmt_pid=307, pat_section=b"pat", state="")
		for tracks, old_pid, preferred_pid in [
				([(208, "und"), (308, "pol")], 208, 308),
				([(1052, "eng"), (1053, "deu"), (1054, "pol"), (1055, "eng")], 1052, 1054)]:
			pmt["streams"] = [{"kind": "audio", "pid": pid, "languages": [lang]} for pid, lang in tracks]
			with patch.object(scanner_module, "load_results", return_value={"results": {}}), \
					patch.object(scanner_module, "preferred_languages", return_value=["pol", "eng"]):
				scan = scanner_module.ChannelParamsScan(types.SimpleNamespace(active=[]))
				for previous, expected in [
						({"selectedAudioPid": old_pid, "selectionSource": "default", "selectedConfirmed": True}, preferred_pid),
						({"selectedAudioPid": old_pid, "selectionSource": "manual", "selectedConfirmed": True}, old_pid),
						({"selectedAudioPid": 9999, "selectionSource": "manual", "selectedConfirmed": True}, preferred_pid),
						({"selectedAudioPid": old_pid, "selectedConfirmed": True}, old_pid),
						({"selectedAudioPid": old_pid, "selectedConfirmed": False}, preferred_pid)]:
					scan.results[ref] = previous
					scan._save_channel(job, pmt)
					self.assertEqual(scan.results[ref]["selectedAudioPid"], expected)

	def test_no_preferred_language_selects_first_track(self):
		pmt = channelparams.parse_pmt(base64.b64decode(PMT_BASE64), 17039)
		pmt["streams"][1]["languages"] = ["und"]
		pmt["streams"][2]["languages"] = ["deu"]
		with patch.object(scanner_module, "load_results", return_value={"results": {}}), \
				patch.object(scanner_module, "preferred_languages", return_value=["pol", "eng"]):
			scan = scanner_module.ChannelParamsScan(types.SimpleNamespace(active=[]))
			job = types.SimpleNamespace(ref="ref", name="test", pmt_pid=307, pat_section=b"pat", state="")
			scan._save_channel(job, pmt)
		self.assertEqual(scan.results["ref"]["selectedAudioPid"], 536)

	def test_no_audio_is_not_reported_as_a_confirmed_selection(self):
		pmt = channelparams.parse_pmt(base64.b64decode(PMT_BASE64), 17039)
		pmt["streams"] = [stream for stream in pmt["streams"] if stream["kind"] != "audio"]
		with patch.object(scanner_module, "load_results", return_value={"results": {}}):
			scan = scanner_module.ChannelParamsScan(types.SimpleNamespace(active=[]))
		job = types.SimpleNamespace(ref="1:0:1:428F:1:1:13E:0:0:0:", name="test", pmt_pid=307,
			pat_section=b"pat", state="")
		scan._save_channel(job, pmt)
		self.assertIsNone(scan.results[job.ref]["selectedAudioPid"])
		self.assertFalse(scan.results[job.ref]["selectedConfirmed"])

	def test_language_preference_is_read_from_enigma_when_configured(self):
		common.cfg.cp_language_source.value = "enigma"
		config_module = types.ModuleType("Components.config")
		config_module.config = types.SimpleNamespace(autolanguage=types.SimpleNamespace(
			audio_autoselect1=setting("pol"), audio_autoselect2=setting("eng"),
			audio_autoselect3=setting(""), audio_autoselect4=setting("")))
		with patch.dict(sys.modules, {"Components.config": config_module}):
			self.assertEqual(scanner_module.preferred_languages(), ["pol", "eng", "", ""])
		common.cfg.cp_language_source.value = "manual"

	def test_scan_due_uses_interval_and_interrupted_scan_flag(self):
		common.cfg.cp_interval.value = "2"
		common.cfg.cp_last_run.value = 0
		self.assertTrue(scanner_module.scan_due(0))
		common.cfg.cp_last_run.value = 100
		self.assertFalse(scanner_module.scan_due(100 + 2 * 86400 - 1))
		self.assertTrue(scanner_module.scan_due(100 + 2 * 86400))
		common.cfg.cp_auto_in_progress.value = True
		self.assertTrue(scanner_module.scan_due(101))
		common.cfg.cp_auto_in_progress.value = False
		common.cfg.cp_interval.value = "7"
		common.cfg.cp_last_run.value = 0

	def test_scan_report_counts_only_channels_scanned_in_this_run(self):
		engine = types.SimpleNamespace(channel_params_scan=object())
		scan = scanner_module.ChannelParamsScan(engine)
		scan.started = 100
		scan.results = {
			"previous": {"scannedAt": 100, "readDuration": 10},
			"current": {"scannedAt": 100, "readDuration": 2},
		}
		scan.scanned_refs = {"current"}
		with patch.object(scanner_module, "save_results") as save:
			scan._finish()
		report = save.call_args[0][0]["last_scan"]
		self.assertEqual(report["scanned"], 1)
		self.assertEqual(report["read_average"], 2)

	def test_parameter_report_retains_full_conflicts_and_errors(self):
		conflicts = ["pmts_%d" % index for index in range(500)]
		report = {"started": 100, "finished": 160, "duration": 60, "scanned": 4,
			"audio_channels": 3, "audio_tracks": 5, "selected_audio": 3,
			"errors": [{"ref": "service-error", "name": "Channel", "error": "read failed"}],
			"unsupported": [{"ref": "service-skip", "name": "IPTV", "reason": "unsupported"}],
			"sync": ["Pominiete konflikty: " + ", ".join(conflicts)]}
		text = scanner_module.format_report(report)
		self.assertIn(conflicts[0], text)
		self.assertIn(conflicts[-1], text)
		self.assertIn("service-error", text)
		self.assertIn("read failed", text)
		self.assertIn("service-skip", text)
		self.assertIn("unsupported", text)
		self.assertIn("Brak raportu", scanner_module.format_report(None))

	def test_main_screen_keeps_long_parameter_report_out_of_status(self):
		conflicts = ["pmts_%d" % index for index in range(500)]
		report = {"scanned": 4, "audio_channels": 3, "audio_tracks": 5, "selected_audio": 3,
			"duration": 60, "errors": [{"name": "Channel", "error": "read failed"}],
			"unsupported": [], "sync": ["Pominiete konflikty: " + ", ".join(conflicts)],
			"sync_conflicts": {"common": conflicts}}
		engine = types.SimpleNamespace(stats=lambda: (0, 0, 60), free_tuners=lambda: 1,
			total_tuners=2, reserve=1, jobs=[], active=[], running=False, finished=True,
			channelparams_report=report, note=report["sync"][0], changes="")
		screen = scan_module.EPGScanScreen.__new__(scan_module.EPGScanScreen)
		dict.__init__(screen)
		screen.engine, screen.ljob, screen.grab = engine, None, None
		screen.sw_scanning = lambda: False
		screen.stage_lines = lambda: []
		screen["status"] = types.SimpleNamespace(setText=Mock())
		screen["list"] = types.SimpleNamespace(setText=Mock())
		with patch.object(common.cfg, "xmltv", setting(False), create=True), \
				patch.object(common.cfg, "sat_import", setting(True), create=True):
			screen.redraw()
		status = screen["status"].setText.call_args.args[0]
		self.assertIn("PAT/PMT: 4", status)
		self.assertIn("bledow 1", status)
		self.assertIn("konflikt", status.lower())
		self.assertIn("Raporty", status)
		self.assertNotIn(conflicts[-1], status)
		self.assertLess(len(status), 300)

	def test_main_screen_displays_effective_tuner_reserve_and_timer_error(self):
		scan = types.SimpleNamespace(active=[], reservations=[], queue=[])
		engine = types.SimpleNamespace(stats=lambda: (0, 0, 60), free_tuners=lambda: 3,
			total_tuners=8, reserve=2, timer_hold=1, timer_check_error=None,
			jobs=[], active=[], running=True, finished=False, phase="channelparams",
			channel_params_scan=scan, note="")
		screen = scan_module.EPGScanScreen.__new__(scan_module.EPGScanScreen)
		dict.__init__(screen)
		screen.engine, screen.ljob, screen.grab = engine, None, None
		screen["status"] = types.SimpleNamespace(setText=Mock())
		screen["list"] = types.SimpleNamespace(setText=Mock())

		screen.redraw()
		status = screen["status"].setText.call_args.args[0]
		self.assertIn("rezerwa 3 (w tym timery: 1)", status)

		engine.timer_check_error = "unavailable"
		screen.redraw()
		status = screen["status"].setText.call_args.args[0]
		self.assertIn("brak danych timerow", status)
		self.assertNotIn("w tym timery", status)

	def test_single_channel_retry_preserves_other_last_scan_errors(self):
		ref = "1:0:1:428F:1:1:13E:0:0:0:"
		other_ref = "1:0:1:9999:1:1:13E:0:0:0:"
		previous = {"scanned": 141, "duration": 644, "errors": [
			{"ref": ref, "name": "TVP 1 HD", "error": "old error"},
			{"ref": other_ref, "name": "Other", "error": "other error"}]}
		for succeeds in (True, False):
			with self.subTest(succeeds=succeeds):
				initial = {"results": {}, "last_scan": previous}
				with patch.object(scanner_module, "load_results", return_value=initial):
					scan = scanner_module.ChannelParamsScan(
						types.SimpleNamespace(channel_params_scan=object()), refs=[ref])
				scan.started = 100
				scan.scanned_refs = {ref} if succeeds else set()
				if succeeds:
					scan.results[ref] = {"streams": []}
				else:
					scan.errors = [{"ref": ref, "name": "TVP 1 HD", "error": "new error"}]
				with patch.object(scanner_module, "save_results") as save:
					scan._finish()
				saved_report = save.call_args.args[0]["last_scan"]
				self.assertEqual((saved_report["scanned"], saved_report["duration"]), (141, 644))
				self.assertEqual(saved_report["last_retry"]["scanned"], 1 if succeeds else 0)
				self.assertIn(previous["errors"][1], saved_report["errors"])
				self.assertEqual([error["error"] for error in saved_report["errors"] if error.get("ref") == ref],
					[] if succeeds else ["new error"])

	def test_channel_scan_does_not_run_without_main_schedule(self):
		scheduler = object.__new__(schedule_module.Scheduler)
		scheduler.first = False
		scheduler.resume = False
		scheduler.woke = False
		scheduler.waiting = ""
		scheduler.session = object()
		with patch.object(common, "sched_active", return_value=False), \
				patch.object(scheduler, "check_topup") as check_topup, \
				patch.object(schedule_module, "get_engine") as get_engine:
			scheduler.check()
		check_topup.assert_called_once()
		get_engine.assert_not_called()

	def test_main_scheduler_remains_authoritative_for_scheduled_scan(self):
		engine = types.SimpleNamespace(running=False, auto=False, woke=False)
		scheduler = object.__new__(schedule_module.Scheduler)
		scheduler.first = False
		scheduler.resume = False
		scheduler.woke = False
		scheduler.waiting = ""
		scheduler.session = object()
		with patch.object(schedule_module, "sched_active", return_value=True), \
				patch.object(schedule_module, "is_due", return_value=True), \
				patch.object(schedule_module, "get_engine", return_value=engine), \
		patch.object(schedule_module, "in_standby", return_value=False), \
		patch.object(scheduler, "check_topup"), \
		patch.object(schedule_module.Scheduler, "blocked_reason", return_value="odbiornik nie jest w czuwaniu") as blocked:
			scheduler.check()
		blocked.assert_called_once_with("standby", False)
		self.assertEqual(scheduler.waiting, "odbiornik nie jest w czuwaniu")

	def test_scheduled_start_uses_daily_slot_interval_and_resume_time(self):
		now = 1800000000
		slot = now - 120
		for mode, resume, due, previous, expected in (
				("daily", False, True, 0, slot),
				("interval", False, True, 0, now),
				("daily", True, False, now - 3600, now - 3600),
				("daily", True, False, 0, now)):
			with self.subTest(mode=mode, resume=resume, previous=previous):
				scheduler = object.__new__(schedule_module.Scheduler)
				scheduler.first = False
				scheduler.resume = resume
				scheduler.woke = False
				scheduler.waiting = ""
				scheduler.session = object()
				scheduler.t_boot = now - 300
				engine = Mock(running=False)
				engine.start.return_value = True
				with patch.object(common.cfg, "sched_mode", setting(mode), create=True), \
						patch.object(common.cfg, "last_auto_run", setting(previous), create=True), \
						patch.object(schedule_module.time, "time", return_value=now + 0.75), \
						patch.object(schedule_module, "sched_active", return_value=True), \
						patch.object(schedule_module, "is_due", return_value=due) as is_due, \
						patch.object(schedule_module, "last_slot", side_effect=lambda timestamp: slot) as last_slot, \
						patch.object(schedule_module, "get_engine", return_value=engine), \
						patch.object(schedule_module, "save_cfg") as save, \
						patch.object(scheduler, "blocked_reason", return_value=""), \
						patch.object(scheduler, "report"):
					scheduler.check()
					is_due.assert_called_once_with(now)
					if mode == "daily" and not resume:
						last_slot.assert_called_once_with(now)
					else:
						last_slot.assert_not_called()
					self.assertEqual(common.cfg.last_auto_run.value, previous if resume else expected)
					self.assertEqual(engine.auto_start_time, expected)
					engine.start.assert_called_once_with(auto=True, woke=False)
					save.assert_called_once()

	def test_scheduled_scan_finishes_and_clears_resume_flag(self):
		with tempfile.TemporaryDirectory() as directory:
			engine = types.SimpleNamespace(active=[], phase="", channel_params_scan=None, running=False,
				timer_hold=0, max_parallel=1, scan_usage=lambda: None, upcoming_recordings=lambda: 0,
				eff_reserve=lambda: 0, free_tuners=lambda: 1, notify=lambda: None, maybe_deep_standby=lambda: None)
			row = {"ref": "4097:0:1:0:0:0:0:0:0:0:http%3a//example/stream",
				"name": "IPTV channel", "supported": False, "error": "Nieobslugiwany typ kanalu"}
			with patch.object(scanner_module, "CHANNEL_PARAMS_FILE", os.path.join(directory, "results.json")), \
					patch.object(scanner_module, "channel_params_bouquet_channels", return_value=[row]):
				self.assertTrue(scanner_module.start_scheduled(engine))
				self.assertTrue(common.cfg.cp_auto_in_progress.value)
				engine.channel_params_scan.tick()
			self.assertFalse(common.cfg.cp_auto_in_progress.value)
			self.assertGreater(common.cfg.cp_last_run.value, 0)

	def test_common_defaults_only_fill_missing_entries_and_backup_file(self):
		with tempfile.TemporaryDirectory() as directory:
			clients.DIR = directory
			clients.COMMON = os.path.join(directory, "common.json")
			key = "audio_1_0_1_428F_1_1_13E_0_0_0"
			with open(clients.COMMON, "w") as f:
				import json
				json.dump({"data": {key: {"v": "536|eng|audio/mpeg"}}}, f)
			results = {"r": {"ref": "1:0:1:428f:1:1:13e:0:0:0:", "selectedConfirmed": True,
				"selectedAudioPid": 536, "sid": 17039, "section": PMT_BASE64,
				"streams": [{"kind": "audio", "pid": 536, "languages": ["pol"], "codec": "audio/mpeg"}]}}

			message, conflicts = store_module.add_common_defaults(results)

			self.assertIn("Dodano 1 wartosci", message)
			self.assertIn(key, conflicts)
			with open(clients.COMMON) as f:
				data = __import__("json").load(f)
			self.assertEqual(data["data"][key]["v"], "536|eng|audio/mpeg")
			self.assertEqual(data["data"]["pmts_17039"]["v"], PMT_BASE64)
			self.assertTrue(any(name.startswith("common.json.bak-") for name in os.listdir(directory)))

	def test_overwrite_mode_replaces_existing_common_audio_and_pmt(self):
		with tempfile.TemporaryDirectory() as directory:
			clients.DIR = directory
			clients.COMMON = os.path.join(directory, "common.json")
			key = "audio_1_0_1_428F_1_1_13E_0_0_0"
			with open(clients.COMMON, "w") as f:
				json.dump({"data": {key: {"v": "536|eng|audio/mpeg"}}}, f)
			results = {"r": {"ref": "1:0:1:428f:1:1:13e:0:0:0:", "selectedConfirmed": True,
				"selectedAudioPid": 536, "sid": 17039, "section": PMT_BASE64,
				"streams": [{"kind": "audio", "pid": 536, "languages": ["pol"], "codec": "audio/mpeg"}]}}

			store_module.add_common_defaults(results, overwrite=True)

			with open(clients.COMMON) as f:
				data = json.load(f)
			self.assertEqual(data["data"][key]["v"], "536|pol|audio/mpeg")
			self.assertEqual(data["data"]["pmts_17039"]["v"], PMT_BASE64)

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

	def test_failed_job_is_reported_and_cancel_releases_active_service(self):
		engine = types.SimpleNamespace(active=[], phase="", channel_params_scan=None)
		engine.release = lambda job: engine.active.remove(job) if job in engine.active else None
		scan = scanner_module.ChannelParamsScan(engine)
		job = types.SimpleNamespace(
			pat_reader=None, pmt_reader=None, rec=None, state="", not_before=0, name="test", ref="ref", group=None)
		scan.active.append(job)
		engine.active.append(job)

		scan._handle_failure(job, "timeout")

		self.assertEqual(scan.active, [])
		self.assertEqual(engine.active, [])
		self.assertEqual(job.state, "BLAD: timeout")
		self.assertEqual(scan.queue, [])
		self.assertEqual(scan.errors[0]["ref"], "ref")

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

		audio, _sources, pmts, conflicts = store_module._sync_payload({
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

	def test_manual_release_list_filters_invalid_assets_and_sorts_versions(self):
		releases = [
			{"tag_name": "epgscan-2.2.0", "assets": [
				{"name": "epgscan-2.2.0.zip", "browser_download_url": "https://example/2.2.0.zip"}]},
			{"tag_name": "epgscan-2.3.0-beta.1", "prerelease": True, "assets": [
				{"name": "epgscan-2.3.0-beta.1.zip", "browser_download_url": "https://example/beta.zip"}]},
			{"tag_name": "epgscan-2.4.0", "draft": True, "assets": [
				{"name": "epgscan-2.4.0.zip", "browser_download_url": "https://example/draft.zip"}]},
			{"tag_name": "epgscan-2.5.0", "assets": [{"name": "source.zip", "browser_download_url": "https://example/source.zip"}]},
		]
		response = types.SimpleNamespace(read=lambda: json.dumps(releases).encode("utf-8"))
		with patch("urllib.request.urlopen", return_value=response):
			available = updater_module.available_releases()

		self.assertEqual([release[0] for release in available], ["2.3.0-beta.1", "2.2.0"])
		self.assertTrue(available[0][2])
		self.assertFalse(available[1][2])


class SyncModeTests(unittest.TestCase):
	def setUp(self):
		self.directory = tempfile.TemporaryDirectory()
		self.addCleanup(self.directory.cleanup)
		self.dir_patch = patch.object(clients, "DIR", self.directory.name, create=True)
		self.common_patch = patch.object(clients, "COMMON", os.path.join(self.directory.name, "common.json"), create=True)
		self.dir_patch.start()
		self.common_patch.start()
		self.addCleanup(self.dir_patch.stop)
		self.addCleanup(self.common_patch.stop)
		self.ref = "1:0:1:428F:1:1:13E:0:0:0:"
		self.key = "audio_1_0_1_428F_1_1_13E_0_0_0"
		parsed = channelparams.parse_pmt(base64.b64decode(PMT_BASE64), 17039)
		self.result = {"ref": self.ref, "name": "test", "selectedConfirmed": True,
			"selectedAudioPid": 536, "sid": 17039, "section": PMT_BASE64, "streams": parsed["streams"]}
		self.results = {self.ref: self.result}

	def write(self, name, data):
		with open(os.path.join(self.directory.name, name), "w", encoding="utf-8") as file:
			json.dump({"data": data}, file)

	def read(self, name):
		with open(os.path.join(self.directory.name, name), encoding="utf-8") as file:
			return json.load(file)["data"]

	def test_pmt_without_audio_syncs_to_common_but_not_devices(self):
		body = bytes.fromhex("02b012428fc10000e118f0001be118f000")
		video_pmt = base64.b64encode(body + channelparams._crc32(body).to_bytes(4, "big")).decode("ascii")
		result = dict(self.result, section=video_pmt, selectedAudioPid=None, selectedConfirmed=False, streams=[])
		results = {self.ref: result}
		screen = channelparamsui.ChannelParamsScreen.__new__(channelparamsui.ChannelParamsScreen)
		screen.results = results
		self.assertTrue(screen._syncable_results())
		for mode in ("missing", "overwrite"):
			with self.subTest(target="common", mode=mode):
				self.write("common.json", {})
				store_module.add_common_defaults(results, mode=mode)
				self.assertEqual(self.read("common.json")["pmts_17039"]["v"], video_pmt)
				self.assertNotIn(self.key, self.read("common.json"))
		for mode in ("missing", "overwrite", "repair"):
			with self.subTest(target="device", mode=mode):
				self.write("device-test.json", {})
				store_module.apply_to_devices(results, ["device-test.json"], mode=mode)
				self.assertNotIn("pmts_17039", self.read("device-test.json"))
				self.assertNotIn(self.key, self.read("device-test.json"))
		result.update(selectedAudioPid=536, selectedConfirmed=False, streams=self.result["streams"])
		audio, _, pmts, _ = store_module._sync_payload(results)
		self.assertEqual(audio, {})
		self.assertEqual(pmts["pmts_17039"], video_pmt)

	def test_pmt_only_modes_leave_audio_untouched_and_clean_device_pmt(self):
		result = dict(self.result, selectedAudioPid=None, selectedConfirmed=False, streams=[])
		results = {self.ref: result}
		for mode in ("missing", "overwrite"):
			with self.subTest(target="common", mode=mode):
				self.write("common.json", {self.key: {"v": "792|eng|old"}, "pmts_17039": {"v": "broken"}})
				store_module.add_common_defaults(results, mode=mode)
				data = self.read("common.json")
				self.assertEqual(data[self.key]["v"], "792|eng|old")
				self.assertEqual(data["pmts_17039"]["v"], "broken" if mode == "missing" else PMT_BASE64)
		for mode in ("missing", "overwrite", "repair"):
			with self.subTest(target="device", mode=mode):
				self.write("device-test.json", {self.key: {"v": "792|eng|old"}, "pmts_17039": {"v": "broken"}})
				store_module.apply_to_devices(results, ["device-test.json"], mode=mode)
				data = self.read("device-test.json")
				self.assertEqual(data[self.key]["v"], "792|eng|old")
				self.assertNotIn("pmts_17039", data)

	def test_audio_free_pmt_participates_in_sid_collision_detection(self):
		changed = bytearray(base64.b64decode(PMT_BASE64))
		changed[9] ^= 1
		changed[-4:] = channelparams._crc32(changed[:-4]).to_bytes(4, "big")
		without_audio = dict(self.result, ref="1:0:1:428F:2:1:13E:0:0:0:", selectedAudioPid=None,
			selectedConfirmed=False, section=base64.b64encode(changed).decode("ascii"))
		audio, _, pmts, conflicts = store_module._sync_payload({self.ref: self.result, "other": without_audio})
		self.assertEqual(list(audio), [self.key])
		self.assertEqual(pmts, {})
		self.assertEqual(conflicts, {"pmts_17039"})
		without_audio["section"] = PMT_BASE64
		_, _, pmts, conflicts = store_module._sync_payload({self.ref: self.result, "other": without_audio})
		self.assertEqual(pmts, {"pmts_17039": PMT_BASE64})
		self.assertEqual(conflicts, set())

	def test_collision_only_is_reported_without_writing(self):
		first = dict(self.result, selectedConfirmed=False, selectedAudioPid=None)
		second = dict(first, ref="1:0:1:428F:2:1:13E:0:0:0:")
		raw = bytearray(base64.b64decode(PMT_BASE64))
		raw[9] ^= 1
		raw[-4:] = channelparams._crc32(raw[:-4]).to_bytes(4, "big")
		second["section"] = base64.b64encode(raw).decode("ascii")
		results = {"first": first, "second": second}
		message, conflicts = store_module.add_common_defaults(results)
		self.assertIn("koliz", message.lower())
		self.assertEqual(conflicts, ["pmts_17039"])
		self.assertFalse(os.path.exists(clients.COMMON))
		self.write("device-test.json", {})
		message, conflicts = store_module.apply_to_devices(results, ["device-test.json"])
		self.assertEqual(message, "Zapisano ustawienia w 0 odbiornikach.")
		self.assertEqual(conflicts, [])
		self.assertEqual(self.read("device-test.json"), {})

	def test_invalid_pmt_without_audio_is_reported(self):
		result = dict(self.result, selectedConfirmed=False, selectedAudioPid=None, section="not-base64")
		screen = channelparamsui.ChannelParamsScreen.__new__(channelparamsui.ChannelParamsScreen)
		screen.results = {self.ref: result}
		self.assertFalse(screen._syncable_results())
		with self.assertRaisesRegex(ValueError, "Nieprawidlowa PMT"):
			store_module.add_common_defaults(screen.results)
		for sid in (None, 1):
			result.update(section=PMT_BASE64, sid=sid)
			with self.subTest(sid=sid), self.assertRaisesRegex(ValueError, "Nieprawidlowa PMT"):
				store_module.add_common_defaults(screen.results)

	def test_automatic_sync_exports_only_current_run_pmt_without_audio(self):
		self.write("common.json", {})
		self.write("device-test.json", {})
		engine = types.SimpleNamespace(active=[], channel_params_scan=None)
		result = dict(self.result, selectedAudioPid=None, selectedConfirmed=False, streams=[])
		raw = bytearray(base64.b64decode(PMT_BASE64))
		raw[3:5] = (17040).to_bytes(2, "big")
		raw[-4:] = channelparams._crc32(raw[:-4]).to_bytes(4, "big")
		stale = dict(result, ref="1:0:1:4290:2:1:13E:0:0:0:", sid=17040,
			section=base64.b64encode(raw).decode("ascii"))
		reports = []
		with patch.object(scanner_module, "load_results", return_value={"results": {}}), \
				patch.object(scanner_module, "save_results"), \
				patch.object(common.cfg.cp_sync_common, "value", "missing"), \
				patch.object(common.cfg.cp_sync_devices, "value", "repair"):
			scan = scanner_module.ChannelParamsScan(engine, done=reports.append)
			scan.results = {self.ref: result, "stale": stale}
			scan.scanned_refs = {self.ref}
			scan._finish()
		self.assertEqual(reports[0]["errors"], [])
		self.assertEqual(len(reports[0]["sync"]), 2)
		self.assertEqual(set(self.read("common.json")), {"pmts_17039"})
		self.assertEqual(set(self.read("device-test.json")), set())

	def test_automatic_repair_also_fills_audio_from_existing_common(self):
		self.write("common.json", {"audio_shared": {"v": "123|pol|aac", "t": 1}})
		self.write("device-test.json", {})
		engine = types.SimpleNamespace(active=[], channel_params_scan=None)
		reports = []
		with patch.object(scanner_module, "load_results", return_value={"results": {}}), \
				patch.object(scanner_module, "save_results"), \
				patch.object(common.cfg.cp_sync_common, "value", "off"), \
				patch.object(common.cfg.cp_sync_devices, "value", "repair"):
			scan = scanner_module.ChannelParamsScan(engine, done=reports.append)
			scan.results = self.results
			scan.scanned_refs = {self.ref}
			scan._finish()
		self.assertEqual(reports[0]["errors"], [])
		self.assertEqual(self.read("device-test.json")["audio_shared"]["v"], "123|pol|aac")

	def test_automatic_sync_reports_collision_when_only_pmts_are_available(self):
		engine = types.SimpleNamespace(active=[], channel_params_scan=None)
		first = dict(self.result, selectedAudioPid=None, selectedConfirmed=False, streams=[])
		raw = bytearray(base64.b64decode(PMT_BASE64))
		raw[9] ^= 1
		raw[-4:] = channelparams._crc32(raw[:-4]).to_bytes(4, "big")
		other_ref = "1:0:1:428F:2:1:13E:0:0:0:"
		second = dict(first, ref=other_ref, section=base64.b64encode(raw).decode("ascii"))
		reports = []
		with patch.object(scanner_module, "load_results", return_value={"results": {}}), \
				patch.object(scanner_module, "save_results"), \
				patch.object(common.cfg.cp_sync_common, "value", "missing"):
			scan = scanner_module.ChannelParamsScan(engine, done=reports.append)
			scan.results = {self.ref: first, other_ref: second}
			scan.scanned_refs = {self.ref, other_ref}
			scan._finish()
		self.assertIn("pmts_17039", reports[0]["sync"][0])
		self.assertIn("koliz", reports[0]["sync"][0].lower())
		self.assertIn("pmts_17039", reports[0]["sync_conflicts"]["common"])
		self.assertEqual(reports[0]["errors"], [])
		self.assertFalse(os.path.exists(clients.COMMON))

	def test_common_missing_never_replaces_invalid_but_overwrite_does(self):
		self.write("common.json", {self.key: {"v": "999|und|bad"}, "pmts_17039": {"v": "broken"}})
		store_module.add_common_defaults(self.results, mode="missing")
		self.assertEqual(self.read("common.json")[self.key]["v"], "999|und|bad")
		self.assertEqual(self.read("common.json")["pmts_17039"]["v"], "broken")
		store_module.add_common_defaults(self.results, mode="overwrite")
		self.assertTrue(self.read("common.json")[self.key]["v"].startswith("536|"))
		self.assertEqual(self.read("common.json")["pmts_17039"]["v"], PMT_BASE64)
		self.assertTrue(any(name.startswith("common.json.bak-") for name in os.listdir(self.directory.name)))

	def test_malformed_common_section_is_not_overwritten(self):
		self.write("common.json", [])
		with self.assertRaisesRegex(ValueError, "Nieprawidlowa sekcja"):
			store_module.add_common_defaults(self.results, mode="overwrite")
		self.assertEqual(self.read("common.json"), [])

	def test_device_modes_preserve_valid_choices_and_only_repair_missing_pids(self):
		for name, pid in (("device-valid.json", 792), ("device-invalid.json", 999),
				("device-overwrite.json", 792)):
			self.write(name, {self.key: {"v": "%s|eng|old" % pid}, "audio_unrelated": {"v": "999|und|bad"}})
		store_module.apply_to_devices(self.results, ["device-invalid.json"], mode="missing")
		self.assertEqual(self.read("device-invalid.json")[self.key]["v"], "999|eng|old")
		store_module.apply_to_devices(self.results, ["device-valid.json", "device-invalid.json"], mode="repair")
		self.assertEqual(self.read("device-valid.json")[self.key]["v"], "792|eng|old")
		self.assertTrue(self.read("device-invalid.json")[self.key]["v"].startswith("536|"))
		self.assertEqual(self.read("device-invalid.json")["audio_unrelated"]["v"], "999|und|bad")
		store_module.apply_to_devices(self.results, ["device-overwrite.json"], mode="overwrite")
		self.assertTrue(self.read("device-overwrite.json")[self.key]["v"].startswith("536|"))
		self.assertTrue(any(name.startswith("device-invalid.json.bak-") for name in os.listdir(self.directory.name)))

	def test_repair_cleans_legacy_pmt_and_adds_missing_audio(self):
		old = bytearray(base64.b64decode(PMT_BASE64))
		offset = old.find(bytes.fromhex("e218"))
		self.assertGreater(offset, 0)
		old[offset:offset + 2] = bytes.fromhex("e3e7")
		old[-4:] = channelparams._crc32(old[:-4]).to_bytes(4, "big")
		self.write("device-test.json", {"pmts_17039": {"v": base64.b64encode(old).decode("ascii")}})
		message, conflicts = store_module.apply_to_devices(self.results, ["device-test.json"], mode="repair")
		data = self.read("device-test.json")
		self.assertNotIn("pmts_17039", data)
		self.assertTrue(data[self.key]["v"].startswith("536|"))
		self.assertIn("Usunieto 1 nieuzywanych PMT", message)
		self.assertEqual(conflicts, [])
		backup = next(name for name in os.listdir(self.directory.name) if name.startswith("device-test.json.bak-"))
		self.assertEqual(self.read(backup)["pmts_17039"]["v"], base64.b64encode(old).decode("ascii"))

	def test_repair_fills_missing_audio_from_common_without_overwriting_client(self):
		self.write("common.json", {
			"audio_shared": {"v": "123|pol|aac", "t": 1},
			"audio_existing": {"v": "456|eng|aac", "t": 1},
			"pmts_123": {"v": "not-copied"},
			"other": {"v": "not-copied"},
		})
		self.write("device-test.json", {"audio_existing": {"v": "789|pol|aac", "t": 2}})
		store_module.apply_to_devices(self.results, ["device-test.json"], mode="repair")
		data = self.read("device-test.json")
		self.assertEqual(data["audio_shared"]["v"], "123|pol|aac")
		self.assertGreater(data["audio_shared"]["t"], 1)
		self.assertEqual(data["audio_existing"]["v"], "789|pol|aac")
		self.assertNotIn("pmts_123", data)
		self.assertNotIn("other", data)

	def test_repair_checks_common_choice_against_current_pmt(self):
		self.write("common.json", {self.key: {"v": "999|und|old", "t": 1}})
		self.write("device-test.json", {})
		store_module.apply_to_devices(self.results, ["device-test.json"], mode="repair")
		self.assertTrue(self.read("device-test.json")[self.key]["v"].startswith("536|"))

	def test_missing_mode_does_not_copy_other_common_audio(self):
		self.write("common.json", {"audio_shared": {"v": "123|pol|aac", "t": 1}})
		self.write("device-test.json", {})
		store_module.apply_to_devices(self.results, ["device-test.json"], mode="missing")
		self.assertNotIn("audio_shared", self.read("device-test.json"))

	def test_repair_reports_invalid_common_data_without_modifying_profile(self):
		self.write("common.json", {"audio_shared": {"v": ""}})
		self.write("device-test.json", {"audio_existing": {"v": "123|pol|aac"}})
		with self.assertRaisesRegex(ValueError, "audio_shared"):
			store_module.apply_to_devices(self.results, ["device-test.json"], mode="repair")
		self.assertEqual(self.read("device-test.json"), {"audio_existing": {"v": "123|pol|aac"}})

	def test_repair_does_not_guess_when_current_pmt_is_missing(self):
		self.write("device-test.json", {self.key: {"v": "999|und|bad"}})
		result = dict(self.result)
		result.pop("section")
		_, conflicts = store_module.apply_to_devices({self.ref: result}, ["device-test.json"], mode="repair")
		self.assertEqual(self.read("device-test.json")[self.key]["v"], "999|und|bad")
		self.assertIn(self.key, conflicts)


class BatchScanTests(unittest.TestCase):
	def setUp(self):
		self.now = 100
		self.sections = {}
		self.readers = []
		self.records = []
		self.released = []
		self.rows = [{"ref": "1:0:1:%X:1:1:13E:0:0:0:" % sid, "name": str(sid), "supported": True}
			for sid in (1, 2)]
		self.engine = types.SimpleNamespace(active=[], phase="", channel_params_scan=None, total_tuners=4, reserve=1,
			timer_hold=0, max_parallel=2, scan_usage=lambda: None, upcoming_recordings=lambda: 0,
			eff_reserve=lambda: 1, free_tuners=lambda: 4, on_busy_tp=lambda _job: False, notify=lambda: None)

		def release(job):
			if job.rec is not None:
				self.released.append(job.rec)
				job.rec = None
			if job in self.engine.active:
				self.engine.active.remove(job)
		self.engine.release = release

		def record(ref):
			rec = types.SimpleNamespace(ref=ref, prepareStreaming=lambda: None, start=lambda: None,
				stream=lambda: types.SimpleNamespace(getStreamingData=lambda: {"adapter": 0, "demux": 2}))
			self.records.append(rec)
			return rec
		self.navigation = types.SimpleNamespace(record_event=[], recordService=record)

		def reader(_adapter, _demux, pid, _table):
			item = Mock()
			item.read.side_effect = lambda: self.sections.get(pid, []).pop(0) if self.sections.get(pid) else None
			item.pid = pid
			self.readers.append(item)
			return item
		patches = [
			patch.object(scanner_module, "load_results", return_value={"results": {}}),
			patch.object(scanner_module, "channel_params_bouquet_channels", side_effect=lambda _files: self.rows),
			patch.object(scanner_module, "tp_params", return_value=(11000, 0, 192)),
			patch.object(scanner_module, "tp_key", side_effect=lambda ref: tuple(int(x, 16) for x in ref.split(":")[4:7])),
			patch.object(scanner_module.time, "time", side_effect=lambda: self.now),
			patch.object(scanner_module.time, "monotonic", side_effect=lambda: self.now),
			patch.object(channelparams, "SectionReader", side_effect=reader),
			patch.object(sys.modules["NavigationInstance"], "instance", self.navigation),
			patch.object(common.cfg.cp_retry_failed, "value", False),
			patch.object(scanner_module, "save_results"),
		]
		for item in patches:
			item.start()
			self.addCleanup(item.stop)
		self.scan = scanner_module.ChannelParamsScan(self.engine)
		self.addCleanup(self.scan.cancel)

	def pat(self, entries, tsid=1, number=0, last=0):
		payload = b"".join(sid.to_bytes(2, "big") + (0xe000 | pid).to_bytes(2, "big")
			for sid, pid in entries)
		length = 9 + len(payload)
		body = bytes([0, 0xb0 | (length >> 8), length & 255]) + tsid.to_bytes(2, "big") + \
			bytes([0xc1, number, last]) + payload
		return body + channelparams._crc32(body).to_bytes(4, "big")

	def pmt(self, sid):
		body = bytearray(base64.b64decode(PMT_BASE64)[:-4])
		body[3:5] = sid.to_bytes(2, "big")
		return bytes(body) + channelparams._crc32(body).to_bytes(4, "big")

	def tune(self):
		self.scan.rec_event(self.records[-1], enigma.iRecordableService.evTunedIn)
		self.scan.tick()

	def borrowed_job(self, ref=None):
		return types.SimpleNamespace(ref=ref or self.rows[0]["ref"],
			rec=types.SimpleNamespace(stream=lambda: types.SimpleNamespace(
				getStreamingData=lambda: {"adapter": 0, "demux": 2})))

	def real_engine_module(self):
		path = os.path.join(ROOT, "EPGScan", "engine.py")
		with open(path, encoding="utf-8") as source:
			tree = ast.parse(source.read())
		names = next(node.names for node in tree.body
			if isinstance(node, ast.ImportFrom) and node.module == "common")
		missing = {name.name: Mock() for name in names if not hasattr(common, name.name)}
		spec = importlib.util.spec_from_file_location(PACKAGE + ".real_engine", path)
		module = importlib.util.module_from_spec(spec)
		with patch.multiple(common, create=True, **missing):
			spec.loader.exec_module(module)
		return module

	def test_engine_scheduling_and_manual_policy_prepare_only_when_requested(self):
		module = self.real_engine_module()
		engine = module.ScanEngine.__new__(module.ScanEngine)
		engine.topup = False
		engine.profile = {"channelparams": False}
		engine.epg_params_scan = None
		engine.auto = True
		with patch.object(scanner_module, "scan_due", return_value=False), \
				patch.object(scanner_module, "ChannelParamsScan") as constructor:
			engine.prepare_epg_params()
			constructor.assert_not_called()
		with patch.object(scanner_module, "scan_due", return_value=True), \
				patch.object(scanner_module, "save_cfg"), patch.object(module, "save_cfg"):
			engine.prepare_epg_params()
			self.assertIsNotNone(engine.epg_params_scan)
			self.assertTrue(common.cfg.cp_auto_in_progress.value)
		common.cfg.cp_auto_in_progress.value = False
		engine.epg_params_scan = None
		engine.auto = False
		with patch.object(scanner_module, "ChannelParamsScan") as constructor:
			engine.prepare_epg_params()
			constructor.assert_not_called()
			engine.profile["channelparams"] = True
			engine.prepare_epg_params()
			constructor.assert_called_once_with(engine)
		engine.topup = True
		with patch.object(scanner_module, "ChannelParamsScan") as constructor:
			engine.prepare_epg_params()
			constructor.assert_not_called()

	def test_scheduled_sync_uses_only_this_run_and_configured_modes(self):
		self.engine.running = False
		self.engine.maybe_deep_standby = lambda: None
		self.scan.prepare()
		for job in self.scan.queue:
			job.pmt_pid = 307
			job.pat_section = self.pat([(int(job.ref.split(":")[3], 16), 307)])
			self.scan._save_channel(job, channelparams.parse_pmt(self.pmt(int(job.ref.split(":")[3], 16)),
				int(job.ref.split(":")[3], 16)))
		self.scan.results["stale-ref"] = {"selectedAudioPid": 999}
		reports = []
		with patch.object(common.cfg.cp_sync_common, "value", "missing"), \
				patch.object(common.cfg.cp_sync_devices, "value", "repair"), \
				patch.object(common.cfg.cp_auto_in_progress, "value", False), \
				patch.object(common.cfg.cp_last_run, "value", 0), \
				patch.object(scanner_module, "save_cfg"), \
				patch.object(store_module, "add_common_defaults", return_value=("common ok", [])) as common_sync, \
				patch.object(store_module, "device_profiles", return_value=["device-one.json"]), \
				patch.object(store_module, "apply_to_devices", return_value=("devices ok", [])) as device_sync:
			self.assertTrue(scanner_module.start_scheduled(self.engine, scan=self.scan, done=reports.append))
			self.scan.tick()
		self.assertEqual(set(common_sync.call_args.args[0]), {row["ref"] for row in self.rows})
		self.assertNotIn("stale-ref", device_sync.call_args.args[0])
		self.assertEqual(common_sync.call_args.kwargs["mode"], "missing")
		self.assertEqual(device_sync.call_args.kwargs["mode"], "repair")
		self.assertEqual(reports[0]["sync"], ["common ok", "devices ok"])
		self.assertEqual(reports[0]["errors"], [])

	def test_sync_is_opt_in_and_failures_are_reported(self):
		ref = self.rows[0]["ref"]
		self.scan.scanned_refs.add(ref)
		self.scan.results[ref] = {"ref": ref, "streams": []}
		reports = []
		self.scan.done = reports.append
		with patch.object(store_module, "add_common_defaults") as sync:
			self.scan._finish()
			sync.assert_not_called()
		with patch.object(common.cfg.cp_sync_devices, "value", "repair"), \
				patch.object(store_module, "device_profiles", side_effect=OSError("profile error")):
			self.scan._finish()
		self.assertIn("profile error", reports[-1]["errors"][-1]["error"])
		saved = scanner_module.save_results.call_args.args[0]
		self.assertIn("profile error", saved["last_scan"]["errors"][-1]["error"])

	def test_no_sync_when_results_cannot_be_saved(self):
		ref = self.rows[0]["ref"]
		self.scan.scanned_refs.add(ref)
		self.scan.results[ref] = {"ref": ref, "streams": []}
		reports = []
		self.scan.done = reports.append
		with patch.object(common.cfg.cp_sync_common, "value", "overwrite"), \
				patch.object(scanner_module, "save_results", side_effect=OSError("disk full")), \
				patch.object(store_module, "add_common_defaults") as sync:
			self.scan._finish()
		sync.assert_not_called()
		self.assertIn("disk full", reports[0]["errors"][-1]["error"])

	def test_finish_preserves_new_manual_selection_made_after_capture(self):
		ref = self.rows[0]["ref"]
		self.scan.prepare()
		job = self.scan.queue[0]
		job.pmt_pid = 307
		job.pat_section = self.pat([(1, 307)])
		self.scan._save_channel(job, channelparams.parse_pmt(self.pmt(1), 1))
		latest = {"results": {ref: {"selectedAudioPid": 792, "selectedConfirmed": True,
			"selectionSource": "manual", "selectedAt": 123}, "other": {"name": "new"}}}
		with patch.object(scanner_module, "load_results", return_value=latest):
			self.scan._finish()
		saved = scanner_module.save_results.call_args.args[0]["results"]
		self.assertEqual(saved[ref]["selectedAudioPid"], 792)
		self.assertEqual(saved[ref]["selectionSource"], "manual")
		self.assertEqual(saved["other"], {"name": "new"})

	def test_after_epg_passes_this_run_to_scheduled_or_manual_fallback(self):
		module = self.real_engine_module()
		engine = module.ScanEngine.__new__(module.ScanEngine)
		engine.topup = False
		engine.woke = False
		engine.auto_start_time = 50
		engine.profile = {"channelparams": True}
		engine.night_notes = Mock()
		engine.maybe_deep_standby = Mock()
		engine.epg_params_scan = self.scan
		engine.auto = True
		with patch.object(scanner_module, "scan_due", return_value=True), \
				patch.object(scanner_module, "start_scheduled", return_value=True) as scheduled:
			engine.after_backup()
			self.assertIs(scheduled.call_args.kwargs["scan"], self.scan)
			self.assertIsNone(engine.epg_params_scan)
		engine.auto = False
		engine.epg_params_scan = self.scan
		with patch.object(scanner_module, "start_manual", return_value=True) as manual:
			engine.after_backup()
			self.assertIs(manual.call_args.kwargs["scan"], self.scan)
			self.assertIsNone(engine.epg_params_scan)

	def test_pending_epg_results_cannot_be_replaced_before_backup_handoff(self):
		module = self.real_engine_module()
		engine = module.ScanEngine.__new__(module.ScanEngine)
		engine.running = False
		engine.phase = ""
		engine.epg_params_scan = self.scan
		self.assertFalse(engine.start(profile={"sat": True}))
		self.assertFalse(engine.start_xmltv_only())
		engine.prepare()
		self.assertFalse(scanner_module.start_manual(engine))
		with patch.object(common.cfg.cp_auto_in_progress, "value", True):
			self.assertFalse(scanner_module.start_scheduled(engine))
			self.assertTrue(common.cfg.cp_auto_in_progress.value)
		self.assertIs(engine.epg_params_scan, self.scan)
		engine.running = True
		engine.after_backup()
		self.assertIs(engine.epg_params_scan, self.scan)

	def test_after_backup_start_failure_preserves_captured_results_and_reports_error(self):
		module = self.real_engine_module()
		engine = module.ScanEngine.__new__(module.ScanEngine)
		engine.running = False
		engine.topup = False
		engine.auto = False
		engine.profile = {"channelparams": True}
		engine.epg_params_scan = self.scan
		engine.notify = Mock()
		engine.maybe_deep_standby = Mock()
		ref = self.rows[0]["ref"]
		self.scan.scanned_refs.add(ref)
		self.scan.results[ref] = {"sid": 1}
		self.scan.last_scan = {"errors": [{"name": "old error"}]}
		with patch.object(scanner_module, "start_manual", side_effect=RuntimeError("start failed")):
			engine.after_backup()
		saved = scanner_module.save_results.call_args.args[0]
		self.assertEqual(saved["results"][ref], {"sid": 1})
		self.assertEqual(saved["last_scan"], self.scan.last_scan)
		self.assertIn("start failed", engine.note)
		self.assertIn("start failed", engine.channelparams_report["errors"][0]["error"])
		self.assertIsNone(engine.epg_params_scan)

	def test_stop_persists_partial_results_and_surfaces_save_failures(self):
		module = self.real_engine_module()
		for phase, save_fails in (("scan", False), ("channelparams", False), ("scan", True)):
			with self.subTest(phase=phase, save_fails=save_fails):
				engine = module.ScanEngine.__new__(module.ScanEngine)
				engine.running = True
				engine.phase = phase
				engine.auto = False
				engine.channelparams_auto = False
				engine.active = []
				engine.timer = FakeTimer()
				engine.end_eit = Mock()
				engine.detach = Mock()
				engine.notify = Mock()
				engine.clear_auto_flag = Mock()
				self.scan.engine = engine
				self.scan.scanned_refs = {self.rows[0]["ref"]}
				self.scan.results[self.rows[0]["ref"]] = {"sid": 1}
				self.scan.last_scan = {"errors": [{"name": "previous"}]}
				engine.epg_params_scan = self.scan if phase == "scan" else None
				engine.channel_params_scan = self.scan if phase == "channelparams" else None
				scanner_module.save_results.reset_mock()
				scanner_module.save_results.side_effect = OSError("disk full") if save_fails else None
				engine.stop()
				self.assertFalse(engine.running)
				scanner_module.save_results.assert_called_once()
				if save_fails:
					self.assertIn("disk full", engine.note)
				else:
					saved = scanner_module.save_results.call_args.args[0]
					self.assertEqual(saved["results"][self.rows[0]["ref"]], {"sid": 1})
					self.assertEqual(saved["last_scan"], {"errors": [{"name": "previous"}]})
					self.assertEqual(common.cfg.cp_last_run.value, 0)
				scanner_module.save_results.side_effect = None

	def test_previous_run_results_do_not_count_as_current_capture(self):
		self.scan.results[self.rows[0]["ref"]] = {"selectedAudioPid": 208, "selectionSource": "default"}
		self.scan.prepare()
		self.assertEqual(self.scan.scanned_refs, set())
		self.assertTrue(self.scan.start())
		self.assertEqual(self.records[0].ref, self.rows[0]["ref"])

	def test_fallback_respects_last_epg_tune_gap(self):
		self.scan.prepare()
		self.engine.last_tune = self.now
		self.assertTrue(self.scan.start())
		self.assertEqual(self.records, [])
		self.now += common.TUNE_GAP
		self.scan.tick()
		self.assertEqual(len(self.records), 1)

	def test_engine_tuned_event_poll_and_release_use_existing_service(self):
		module = self.real_engine_module()
		engine = module.ScanEngine.__new__(module.ScanEngine)
		engine.epg_params_scan = self.scan
		engine.other_used = set()
		engine.fail_streak = {}
		engine.active = []
		engine.queue = [types.SimpleNamespace(not_before=9999, state="waiting")]
		engine.running = True
		engine.last_usage = self.now
		engine.last_tune = self.now
		engine.timer_hold = 0
		engine.max_parallel = 1
		engine.free_tuners = lambda: 4
		engine.eff_reserve = lambda: 1
		engine.notify = lambda: None
		job = self.borrowed_job()
		job.name = "EPG"
		job.tuner_no = -1
		job.last_count = self.now
		job.all_events = 0
		job.t0 = self.now
		engine.active.append(job)
		self.navigation.stopRecordService = Mock()
		self.sections = {0: [self.pat([(1, 307), (2, 308)])],
			307: [self.pmt(1)], 308: [self.pmt(2)]}
		self.scan.prepare()
		engine.rec_event(job.rec, enigma.iRecordableService.evTunedIn)
		with patch.multiple(module.cfg, create=True, min_time=setting(100), max_time=setting(200),
				idle_time=setting(100)), patch.multiple(module, USAGE_EVERY=100, COUNT_EVERY=100):
			engine.tick()
			engine.tick()
		self.assertEqual(self.scan.scanned_refs, {row["ref"] for row in self.rows})
		engine.release(job)
		self.navigation.stopRecordService.assert_called_once()
		self.assertEqual(self.records, [])

	def test_epg_capture_completes_without_any_additional_tune(self):
		self.sections = {0: [self.pat([(1, 307), (2, 308)])],
			307: [self.pmt(1)], 308: [self.pmt(2)]}
		self.scan.prepare()
		job = self.borrowed_job()
		self.scan.attach_epg(job)
		self.scan.poll_epg(job)
		self.scan.poll_epg(job)
		self.assertEqual(self.scan.scanned_refs, {row["ref"] for row in self.rows})
		self.assertIn(id(job), self.scan.borrowed)
		self.scan.release_epg(job)
		self.assertEqual(self.released, [])
		reports = []
		self.scan.done = reports.append
		self.assertTrue(self.scan.start())
		self.scan.tick()
		self.assertEqual(self.records, [])
		self.assertEqual(reports[0]["scanned"], 2)
		self.assertEqual(reports[0]["errors"], [])
		self.assertTrue(all(reader.close.call_count == 1 for reader in self.readers))

	def test_epg_partial_capture_and_skipped_or_failed_transponders_fall_back(self):
		self.rows.extend([{"ref": "1:0:1:%X:%X:1:13E:0:0:0:" % (sid, sid),
			"name": str(sid), "supported": True} for sid in (3, 4)])
		self.scan.prepare()
		self.scan.results[self.rows[2]["ref"]] = {"old": True}
		self.sections = {0: [self.pat([(1, 307), (2, 308)])], 307: [self.pmt(1)]}
		job = self.borrowed_job()
		self.scan.attach_epg(job)
		self.scan.poll_epg(job)
		self.scan.poll_epg(job)
		self.scan.release_epg(job)
		self.assertEqual(self.scan.scanned_refs, {self.rows[0]["ref"]})
		self.assertTrue(all(reader.close.call_count == 1 for reader in self.readers))
		self.assertEqual(self.released, [])
		reports = []
		self.scan.done = reports.append

		def record(ref):
			tsid = int(ref.split(":")[4], 16)
			rec = types.SimpleNamespace(ref=ref, prepareStreaming=lambda: None, start=lambda: None,
				stream=lambda: types.SimpleNamespace(getStreamingData=lambda: {"adapter": 0, "demux": tsid}))
			self.records.append(rec)
			return rec

		def reader(_adapter, demux, pid, _table):
			sid = 2 if demux == 1 else demux
			section = self.pat([(sid, 307)], tsid=demux) if pid == 0 else self.pmt(sid)
			item = Mock()
			item.read.return_value = section
			self.readers.append(item)
			return item

		self.navigation.recordService = record
		with patch.object(channelparams, "SectionReader", side_effect=reader):
			self.assertTrue(self.scan.start())
			self.assertEqual(self.records[0].ref, self.rows[1]["ref"])
			self.assertEqual({job.ref for job in self.scan.queue} | {job.ref for job in self.scan.active},
				{row["ref"] for row in self.rows[1:]})
			self.assertEqual(self.scan.results[self.rows[2]["ref"]], {"old": True})
			for _ in range(50):
				for current in list(self.scan.active):
					if current.state == "strojenie":
						self.scan.rec_event(current.rec, enigma.iRecordableService.evTunedIn)
				self.now += 1
				self.scan.tick()
				if reports:
					break
		self.assertEqual(len(self.records), 3)
		self.assertEqual(reports[0]["scanned"], 4)
		self.assertEqual(reports[0]["errors"], [])
		self.assertEqual(self.scan.results[self.rows[2]["ref"]]["sid"], 3)

	def test_epg_capture_rejects_other_transport_identity_and_cleans_up_on_cancel(self):
		self.scan.prepare()
		job = self.borrowed_job("1:0:1:1:1:2:13E:0:0:0:")
		self.scan.attach_epg(job)
		self.assertEqual(self.scan.borrowed, {})
		job = self.borrowed_job()
		self.scan.attach_epg(job)
		self.scan.poll_epg(job)
		self.scan.cancel()
		self.assertEqual(self.scan.borrowed, {})
		self.assertEqual(self.released, [])
		self.assertTrue(all(reader.close.call_count == 1 for reader in self.readers))

	def test_epg_capture_accepts_pmt_after_batch_timeout_without_retune(self):
		self.scan.prepare()
		job = self.borrowed_job()
		self.sections = {0: [self.pat([(1, 307), (2, 308)])]}
		self.scan.attach_epg(job)
		self.scan.poll_epg(job)
		self.scan.poll_epg(job)
		self.now += scanner_module.BATCH_DATA_WAIT + 1
		self.sections[307] = [self.pmt(1)]
		self.sections[308] = [self.pmt(2)]
		self.scan.poll_epg(job)
		self.assertEqual(self.scan.scanned_refs, {row["ref"] for row in self.rows})
		self.assertIn(id(job), self.scan.borrowed)
		self.scan.release_epg(job)
		self.assertEqual(self.records, [])
		self.assertEqual(self.released, [])
		self.assertTrue(all(reader.close.call_count == 1 for reader in self.readers))
		reports = []
		self.scan.done = reports.append
		self.assertTrue(self.scan.start())
		self.scan.tick()
		self.assertEqual(self.records, [])
		self.assertEqual(reports[0]["scanned"], 2)

	def test_epg_observes_changed_pmt_until_release_without_losing_manual_pid(self):
		self.scan.prepare()
		job = self.borrowed_job()
		self.sections = {0: [self.pat([(1, 307)])], 307: [self.pmt(1)]}
		self.scan.results[self.rows[0]["ref"]] = {"selectedAudioPid": 792,
			"selectedConfirmed": True, "selectionSource": "manual"}
		self.scan.attach_epg(job)
		self.scan.poll_epg(job)
		self.scan.poll_epg(job)
		changed = bytearray(self.pmt(1))
		changed[5] ^= 0x02
		offset = changed.find(bytes.fromhex("e218"))
		self.assertGreater(offset, 0)
		changed[offset:offset + 2] = bytes.fromhex("e219")
		changed[-4:] = channelparams._crc32(changed[:-4]).to_bytes(4, "big")
		self.sections[307] = [bytes(changed)]
		self.scan.poll_epg(job)
		result = self.scan.results[self.rows[0]["ref"]]
		self.assertEqual(base64.b64decode(result["section"]), bytes(changed))
		self.assertIn(537, [stream["pid"] for stream in result["streams"]])
		self.assertNotIn(536, [stream["pid"] for stream in result["streams"]])
		self.assertEqual(result["selectedAudioPid"], 792)
		self.assertEqual(result["selectionSource"], "manual")
		self.assertIn(id(job), self.scan.borrowed)
		self.scan.release_epg(job)
		self.assertEqual(self.records, [])
		self.assertEqual(self.released, [])
		self.assertTrue(all(reader.close.call_count == 1 for reader in self.readers))

	def test_epg_monitor_rotates_filters_after_initial_reads(self):
		self.rows = [{"ref": "1:0:1:%X:1:1:13E:0:0:0:" % sid, "name": str(sid), "supported": True}
			for sid in range(1, 7)]
		self.scan.prepare()
		job = self.borrowed_job()
		self.sections = {0: [self.pat([(sid, 300 + sid) for sid in range(1, 7)])]}
		for sid in range(1, 7):
			self.sections[300 + sid] = [self.pmt(sid)]
		self.scan.attach_epg(job)
		self.scan.poll_epg(job)
		for _ in range(15):
			self.scan.poll_epg(job)
			anchor = self.scan.borrowed[id(job)]
			self.assertLessEqual(len(anchor.batch_readers), scanner_module.BATCH_MAX_PMT_FILTERS)
			self.now += 1
		self.assertEqual(self.scan.scanned_refs, {row["ref"] for row in self.rows})
		self.scan.release_epg(job)
		self.assertTrue(all(reader.close.call_count == 1 for reader in self.readers))
		self.assertEqual(self.records, [])

	def test_epg_changed_pat_requires_new_pmt_before_counting_success(self):
		self.scan.prepare()
		job = self.borrowed_job()
		self.sections = {0: [self.pat([(1, 307)])], 307: [self.pmt(1)]}
		self.scan.attach_epg(job)
		self.scan.poll_epg(job)
		self.scan.poll_epg(job)
		self.assertIn(self.rows[0]["ref"], self.scan.scanned_refs)
		self.sections[0] = [self.pat([(1, 308)])]
		self.scan.poll_epg(job)
		self.assertNotIn(self.rows[0]["ref"], self.scan.scanned_refs)
		self.scan.release_epg(job)
		self.assertIn(self.rows[0]["ref"], [item.ref for item in self.scan.queue])
		self.assertTrue(self.scan.start())
		self.assertEqual(self.records[0].ref, self.rows[0]["ref"])

	def test_epg_waits_for_late_stream_metadata_until_release(self):
		self.scan.prepare()
		job = self.borrowed_job()
		stream = types.SimpleNamespace(getStreamingData=Mock(side_effect=[None, {"adapter": 0, "demux": 2}]))
		job.rec.stream = lambda: stream
		self.scan.attach_epg(job)
		self.scan.poll_epg(job)
		self.now += scanner_module.STREAM_DATA_WAIT + 1
		self.scan.poll_epg(job)
		self.assertIn(id(job), self.scan.borrowed)
		self.scan.release_epg(job)
		self.assertTrue(all(reader.close.call_count == 1 for reader in self.readers))
		self.assertEqual(self.records, [])

	def test_one_reservation_collects_multiple_pat_sections_and_shared_pmt_pid(self):
		self.sections = {0: [self.pat([(1, 307)], number=0, last=1),
			self.pat([(2, 307)], number=1, last=1)], 307: [self.pmt(2), self.pmt(1), self.pmt(2)]}
		self.assertTrue(self.scan.start())
		self.tune()
		for _ in range(3):
			self.scan.tick()
		self.assertEqual(len(self.records), 1)
		self.assertEqual(self.scan.scanned_refs, {row["ref"] for row in self.rows})
		self.assertEqual([self.scan.results[row["ref"]]["sid"] for row in self.rows], [1, 2])
		self.assertEqual([base64.b64decode(self.scan.results[row["ref"]]["pat"]) for row in self.rows],
			[self.pat([(1, 307)], number=0, last=1), self.pat([(2, 307)], number=1, last=1)])
		self.assertEqual([reader.pid for reader in self.readers], [0, 307])
		self.assertTrue(all(reader.close.call_count == 1 for reader in self.readers))
		self.assertEqual(len(self.released), 1)

	def test_parallel_scan_accounts_for_pending_frontends_and_reuses_after_release(self):
		for initially_used, expected in ((set(), 6), ({0, 1}, 4)):
			with self.subTest(initially_used=initially_used):
				self.now = 100
				self.records.clear()
				self.released.clear()
				self.engine.active.clear()
				self.engine.phase = ""
				self.engine.last_tune = 0
				self.engine.total_tuners = 8
				self.engine.reserve = 2
				self.engine.max_parallel = 6
				self.engine.used = set(initially_used)
				self.engine.eff_reserve = lambda: 2
				self.engine.free_tuners = lambda: 8 - len(self.engine.used)
				self.rows = [{"ref": "1:0:1:%X:%X:1:13E:0:0:0:" % (sid, sid),
					"name": str(sid), "supported": True} for sid in range(1, 9)]
				allocated = set(initially_used)

				def record(ref):
					number = next(no for no in range(8) if no not in allocated)
					allocated.add(number)
					rec = types.SimpleNamespace(ref=ref, number=number, prepareStreaming=lambda: None,
						start=lambda: None, stream=lambda: types.SimpleNamespace(
							getStreamingData=lambda: {"adapter": 0, "demux": number}))
					self.records.append(rec)
					return rec

				def release(job):
					if job.rec is not None:
						allocated.remove(job.rec.number)
						self.engine.used.discard(job.rec.number)
						self.released.append(job.rec)
						job.rec = None
					if job in self.engine.active:
						self.engine.active.remove(job)

				self.navigation.recordService = record
				self.engine.release = release
				self.scan = scanner_module.ChannelParamsScan(self.engine)
				try:
					with patch.object(scanner_module, "frontend_of", side_effect=lambda rec: (rec.number, None)):
						self.assertTrue(self.scan.start())
						self.assertEqual(len(self.records), expected)
						self.assertEqual(len(self.scan.active), expected)
						self.assertEqual(self.scan.tuner_trace.max_own, expected)
						self.assertEqual(self.scan.tuner_trace.attempts["independent"], expected)
						self.now += 0.25
						self.scan.tick()
						self.assertEqual(len(self.records), expected)
						self.engine.used.update(rec.number for rec in self.records)
						for job in list(self.scan.active):
							self.scan.rec_event(job.rec, enigma.iRecordableService.evTunedIn)
						self.now += 0.25
						self.scan.tick()
						victim = self.scan.active[0]
						self.scan._release(victim)
						victim.group.done = True
						self.now += 1
						self.scan.tick()
						self.assertEqual(len(self.records), expected)
						self.now += 1
						self.scan.tick()
						self.assertEqual(len(self.records), expected + 1)
						self.assertEqual(len(allocated), 6)
				finally:
					self.scan.cancel()

	def test_parallel_tune_failure_retries_once_with_safe_spacing(self):
		self.rows = [{"ref": "1:0:1:%X:%X:1:13E:0:0:0:" % (sid, sid),
			"name": str(sid), "supported": True} for sid in range(1, 4)]
		with patch.object(scanner_module, "log") as log:
			self.assertTrue(self.scan.start())
			self.assertEqual(len(self.scan.active), 2)
			failed = self.scan.active[0]
			self.scan.rec_event(failed.rec, enigma.iRecordableService.evTuneFailed)
			self.scan.tick()
			self.assertIn(failed, self.scan.queue)
			self.assertEqual(self.scan.errors, [])
			self.assertEqual(len(self.records), 2)
			self.now += common.TUNE_GAP
			self.scan.tick()
			self.assertEqual(len(self.records), 3)
			self.assertIn(failed, self.scan.active)
			self.scan.rec_event(failed.rec, enigma.iRecordableService.evTuneFailed)
			self.scan.tick()
			self.assertNotIn(failed, self.scan.queue)
			self.assertTrue(any(row["ref"] == failed.ref for row in self.scan.errors))
			self.assertTrue(any("rownoleg" in str(call.args[0]).lower() for call in log.call_args_list))
			self.scan.cancel()
			report = tunerreport_module.load_trace("params")
			self.assertTrue(report["parallel_fallback"])
			with patch.object(tunerreport_module, "_", lambda value: value):
				self.assertIn("Po bledzie rownoleglego strojenia", tunerreport_module.format_trace(report))

	def test_parallel_batch_tune_failure_requeues_all_uncaptured_channels(self):
		self.rows.append({"ref": "1:0:1:3:3:1:13E:0:0:0:", "name": "3", "supported": True})
		self.assertTrue(self.scan.start())
		self.assertEqual(len(self.scan.active), 2)
		failed = self.scan.active[0]
		self.assertEqual({job.ref for job in failed.batch_members}, {row["ref"] for row in self.rows[:2]})
		self.scan.rec_event(failed.rec, enigma.iRecordableService.evTuneFailed)
		self.scan.tick()
		self.assertEqual({job.ref for job in self.scan.queue}, {row["ref"] for row in self.rows[:2]})
		self.assertTrue(all(job.individual for job in self.scan.queue))
		self.assertTrue(self.scan.serial_tuning)
		self.assertEqual(self.scan.errors, [])
		self.now += common.TUNE_GAP
		self.scan.tick()
		self.assertEqual(len(self.records), 3)
		self.assertEqual(len(self.scan.queue), 1)

	def test_parallel_reservation_failure_is_retried_with_spacing(self):
		self.rows = [{"ref": "1:0:1:%X:%X:1:13E:0:0:0:" % (sid, sid),
			"name": str(sid), "supported": True} for sid in range(1, 4)]
		record = self.navigation.recordService
		attempts = []

		def fail_second(ref):
			attempts.append(ref)
			return None if len(attempts) == 2 else record(ref)

		self.navigation.recordService = fail_second
		self.assertTrue(self.scan.start())
		self.assertEqual(len(attempts), 2)
		self.assertEqual(len(self.scan.queue), 2)
		self.assertTrue(self.scan.serial_tuning)
		self.assertEqual(self.scan.errors, [])
		self.now += common.TUNE_GAP
		self.scan.tick()
		self.assertEqual(len(attempts), 3)
		self.assertEqual(attempts[1], attempts[2])

	def test_synchronous_parallel_tune_failure_is_retried_with_spacing(self):
		self.rows = [{"ref": "1:0:1:%X:%X:1:13E:0:0:0:" % (sid, sid),
			"name": str(sid), "supported": True} for sid in range(1, 4)]
		record = self.navigation.recordService

		def fail_second(ref):
			rec = record(ref)
			if len(self.records) == 2:
				rec.start = lambda: self.scan.rec_event(rec, enigma.iRecordableService.evTuneFailed)
			return rec

		self.navigation.recordService = fail_second
		self.assertTrue(self.scan.start())
		self.assertEqual(len(self.records), 2)
		self.assertEqual(len(self.scan.active), 1)
		self.assertTrue(self.scan.serial_tuning)
		self.assertEqual(self.scan.errors, [])
		self.now += common.TUNE_GAP
		self.scan.tick()
		self.assertEqual(len(self.records), 3)
		self.assertEqual(self.records[1].ref, self.records[2].ref)

	def test_parallel_tune_timeouts_are_retried_with_spacing(self):
		self.rows = [{"ref": "1:0:1:%X:%X:1:13E:0:0:0:" % (sid, sid),
			"name": str(sid), "supported": True} for sid in range(1, 4)]
		self.assertTrue(self.scan.start())
		self.assertEqual(len(self.scan.active), 2)
		self.now += common.TUNE_TIMEOUT + 1
		self.scan.tick()
		self.assertTrue(self.scan.serial_tuning)
		self.assertEqual(len(self.scan.queue), 3)
		self.assertEqual(self.scan.errors, [])
		self.assertEqual(len(self.records), 2)
		self.now += common.TUNE_GAP
		self.scan.tick()
		self.assertEqual(len(self.records), 3)

	def test_twenty_three_transport_streams_need_only_twenty_three_tunes(self):
		self.rows = [{"ref": "1:0:1:%X:%X:1:13E:0:0:0:" % (sid, tsid),
			"name": "service %d" % sid, "supported": True}
			for tsid in range(1, 24) for sid in (2 * tsid - 1, 2 * tsid)]
		started = {}
		tune_times = []

		def record(ref):
			tsid = int(ref.split(":")[4], 16)
			started[tsid] = self.now
			tune_times.append(self.now)
			rec = types.SimpleNamespace(ref=ref, prepareStreaming=lambda: None, start=lambda: None,
				stream=lambda: types.SimpleNamespace(getStreamingData=lambda: {"adapter": 0, "demux": tsid}))
			self.records.append(rec)
			return rec

		def reader(_adapter, demux, pid, _table):
			sids = (2 * demux - 1, 2 * demux)
			section = self.pat([(sids[0], 307), (sids[1], 308)], tsid=demux) if pid == 0 \
				else self.pmt(sids[pid - 307])
			item = Mock()
			item.read.side_effect = lambda: section if pid == 0 or self.now - started[demux] >= 3 else None
			self.readers.append(item)
			return item

		self.navigation.recordService = record
		reports = []
		self.scan.done = reports.append
		max_active = 0
		with patch.object(channelparams, "SectionReader", side_effect=reader):
			self.scan.start()
			for _ in range(400):
				for job in list(self.scan.active):
					if job.state == "strojenie":
						self.scan.rec_event(job.rec, enigma.iRecordableService.evTunedIn)
				self.now += 0.25
				self.scan.tick()
				max_active = max(max_active, len(self.scan.active))
				if self.engine.channel_params_scan is None:
					break
		self.assertEqual(len(self.records), 23)
		self.assertEqual(len(self.released), 23)
		self.assertEqual(max_active, 2)
		self.assertEqual(tune_times[0], tune_times[1])
		self.assertEqual(reports[0]["scanned"], 46)
		self.assertEqual(reports[0]["errors"], [])
		trace = reports[0]["tuners"]
		self.assertFalse(trace["cancelled"])
		self.assertEqual(trace["attempts"]["independent"], 23)
		self.assertEqual(trace["max_own"], 2)
		self.assertGreater(trace["samples"], 0)
		self.assertEqual(tunerreport_module.load_trace("params"), trace)
		self.assertEqual(self.engine.active, [])
		self.assertTrue(all(reader.close.call_count == 1 for reader in self.readers))

	def test_missing_pat_or_pmt_falls_back_only_for_missing_service(self):
		for missing in ("pat", "pmt"):
			with self.subTest(missing=missing):
				self.sections = {0: [self.pat([(1, 307)] if missing == "pat" else [(1, 307), (2, 308)])],
					307: [self.pmt(1)]}
				self.scan.start()
				self.tune()
				self.scan.tick()
				self.assertEqual(self.scan.scanned_refs, {self.rows[0]["ref"]})
				self.now += scanner_module.BATCH_DATA_WAIT
				self.scan.tick()
				self.assertEqual(len(self.records), 1)
				self.now += common.TUNE_GAP
				self.scan.tick()
				self.assertEqual(len(self.records), 2)
				self.assertEqual(self.records[-1].ref, self.rows[1]["ref"])
				self.assertEqual(len(self.released), 1)
				self.sections = {0: [self.pat([(2, 308)])], 308: [self.pmt(2)]}
				self.tune()
				self.scan.tick()
				self.assertEqual(self.scan.scanned_refs, {row["ref"] for row in self.rows})
				self.assertEqual(len(self.records), 2)
				self.scan.cancel()
				self.records.clear()
				self.released.clear()
				self.readers.clear()
				self.engine.phase = ""
				self.now += common.TUNE_GAP
				self.scan = scanner_module.ChannelParamsScan(self.engine)

	def test_different_tsid_same_frequency_is_not_grouped(self):
		self.rows[1]["ref"] = "1:0:1:2:2:1:13E:0:0:0:"
		self.scan.start()
		self.assertEqual(len(self.scan.groups), 2)
		self.assertEqual(len(self.scan.groups[0].pending), 0)

	def test_wrong_pat_tsid_and_wrong_pmt_sid_never_mark_success(self):
		self.sections = {0: [self.pat([(1, 307), (2, 308)], tsid=2)],
			307: [self.pmt(2)], 308: [self.pmt(1)]}
		self.scan.start()
		self.tune()
		self.scan.tick()
		self.assertEqual(len(self.readers), 1)
		self.sections[0] = [self.pat([(1, 307), (2, 308)])]
		self.scan.tick()
		self.assertEqual(self.scan.scanned_refs, set())
		self.now += scanner_module.BATCH_DATA_WAIT + 1
		self.scan.tick()
		self.assertEqual(len(self.records), 1)
		self.now += common.TUNE_GAP
		self.scan.tick()
		self.assertEqual(len(self.records), 2)
		self.assertTrue(all(reader.close.call_count == 1 for reader in self.readers))

	def test_filter_failure_and_cancel_release_resources(self):
		self.sections = {0: [self.pat([(1, 307), (2, 308)])]}
		self.scan.start()
		self.tune()
		self.scan.tick()
		self.assertEqual(len(self.readers), 3)
		self.scan.cancel()
		self.assertEqual(len(self.released), 1)
		self.assertEqual(self.engine.active, [])
		self.assertTrue(all(reader.close.call_count == 1 for reader in self.readers))
		self.assertEqual(self.navigation.record_event, [])

	def test_scheduler_persists_cancelled_tuner_report_without_replacing_last_scan(self):
		self.rows[1]["ref"] = "1:0:1:2:2:1:13E:0:0:0:"
		self.engine.used, self.engine.other_used = {0, 1}, {0}
		previous = {"started": 50, "scanned": 42}
		self.scan.last_scan = previous
		with patch.object(scanner_module, "frontend_of", return_value=(2, None)), \
				patch.object(scanner_module.time, "monotonic", side_effect=lambda: self.now):
			self.assertTrue(self.scan.start())
			self.scan.rec_event(self.scan.active[0].rec, enigma.iRecordableService.evTunedIn)
			self.now = 101
			self.scan.tick()
			self.now = 102
			self.scan.tick()
			self.scan.rec_event(self.scan.active[-1].rec, enigma.iRecordableService.evTunedIn)
			ref = self.rows[0]["ref"]
			self.scan.scanned_refs.add(ref)
			self.scan.results[ref] = {"ref": ref}
			self.now = 103
			self.scan.cancel()
			self.scan.save_partial()
		report = tunerreport_module.load_trace("params")
		self.assertTrue(report["cancelled"])
		self.assertTrue(report["independent"])
		self.assertEqual(report["tuners"], [2])
		self.assertEqual(report["max_own"], 2)
		self.assertEqual(report["attempts"]["independent"], 2)
		self.assertNotIn("gap", report["seconds"])
		self.assertEqual(scanner_module.save_results.call_args[0][0]["last_scan"], previous)
		self.scan.cancel()
		self.assertEqual(tunerreport_module.load_trace("params"), report)

	def test_cancel_during_epg_reports_borrowed_tuning_without_independent_phase(self):
		self.scan.prepare()
		self.scan.attach_epg(types.SimpleNamespace(ref=self.rows[0]["ref"], rec=object()))
		self.scan.cancel()
		report = tunerreport_module.load_trace("params")
		self.assertTrue(report["cancelled"])
		self.assertFalse(report["independent"])
		self.assertEqual(report["epg_borrowed"], 1)
		self.assertEqual(report["samples"], 0)
		with patch.object(tunerreport_module, "_", lambda value: value):
			self.assertIn("Niezalezna faza PAT/PMT nie rozpoczela sie", tunerreport_module.format_trace(report))

	def test_cancel_before_first_epg_tune_replaces_previous_tuner_report(self):
		tunerreport_module.save_trace("params", {"events": [], "cancelled": False})
		self.scan.prepare()
		self.scan.cancel()
		report = tunerreport_module.load_trace("params")
		self.assertTrue(report["cancelled"])
		self.assertFalse(report["independent"])
		self.assertEqual(report["epg_borrowed"], 0)
		self.assertEqual(report["samples"], 0)

	def test_failed_pmt_filter_preserves_other_results_then_falls_back(self):
		self.rows.append({"ref": "1:0:1:3:1:1:13E:0:0:0:", "name": "3", "supported": True})
		self.sections = {0: [self.pat([(1, 307), (2, 308), (3, 309)])],
			307: [self.pmt(1)], 309: [self.pmt(3)]}
		self.scan.start()
		self.tune()
		factory = channelparams.SectionReader.side_effect
		def fail_second(adapter, demux, pid, table):
			if pid == 308:
				raise OSError("no demux filter")
			return factory(adapter, demux, pid, table)
		channelparams.SectionReader.side_effect = fail_second
		self.scan.tick()
		self.assertEqual(self.scan.scanned_refs, {self.rows[0]["ref"]})
		self.assertEqual(len(self.released), 0)
		self.now += 0.25
		self.scan.tick()
		self.assertEqual(self.scan.scanned_refs, {self.rows[0]["ref"], self.rows[2]["ref"]})
		self.now += scanner_module.BATCH_DATA_WAIT
		self.scan.tick()
		self.assertEqual(len(self.records), 1)
		self.now += common.TUNE_GAP
		self.scan.tick()
		self.assertEqual(self.records[-1].ref, self.rows[1]["ref"])
		self.assertEqual(len(self.released), 1)
		self.assertTrue(all(reader.close.call_count == 1 for reader in self.readers))
		self.assertTrue(self.scan.active[0].individual)

	def test_limited_filters_rotate_past_missing_pmts(self):
		self.rows = [{"ref": "1:0:1:%X:1:1:13E:0:0:0:" % sid, "name": str(sid), "supported": True}
			for sid in range(1, 7)]
		self.sections = {0: [self.pat([(sid, 300 + sid) for sid in range(1, 7)])],
			305: [self.pmt(5)], 306: [self.pmt(6)]}
		self.scan.start()
		self.tune()
		for _ in range(12):
			self.scan.tick()
			if self.scan.active:
				self.assertLessEqual(len(self.scan.active[0].batch_readers), scanner_module.BATCH_MAX_PMT_FILTERS)
			self.now += 0.5
		self.assertEqual(self.scan.scanned_refs, {self.rows[4]["ref"], self.rows[5]["ref"]})
		self.assertEqual(len(self.records), 1)
		self.assertLess(self.now - 100, scanner_module.BATCH_DATA_WAIT)

	def test_tuner_reserve_yields_partial_batch_without_rescanning_success(self):
		free = [4]
		self.engine.free_tuners = lambda: free[0]
		self.engine.eff_reserve = lambda: 2
		self.sections = {0: [self.pat([(1, 307)])], 307: [self.pmt(1)]}
		self.scan.start()
		self.tune()
		self.scan.tick()
		self.assertEqual(self.scan.scanned_refs, {self.rows[0]["ref"]})
		free[0] = 1
		self.scan.tick()
		self.assertEqual([job.ref for job in self.scan.queue], [self.rows[1]["ref"]])
		self.assertEqual(self.engine.active, [])
		self.assertTrue(all(reader.close.call_count == 1 for reader in self.readers))
		free[0] = 4
		self.now += common.NO_TUNER_WAIT + 1
		self.scan.tick()
		self.assertEqual(self.records[-1].ref, self.rows[1]["ref"])

	def test_initial_tune_respects_existing_engine_tune_gap(self):
		self.engine.last_tune = self.now
		self.scan = scanner_module.ChannelParamsScan(self.engine)
		self.scan.start()
		self.assertEqual(self.records, [])
		self.now += common.TUNE_GAP
		self.scan.tick()
		self.assertEqual(len(self.records), 1)

	def test_initial_tune_waits_after_epg_releases_a_service(self):
		module = self.real_engine_module()
		engine = module.ScanEngine.__new__(module.ScanEngine)
		engine.active = []
		job = types.SimpleNamespace(rec=object())
		engine.active.append(job)
		self.navigation.stopRecordService = Mock()
		engine.release(job)
		self.assertEqual(engine.last_release_mono, self.now)

		self.engine.last_release_mono = engine.last_release_mono
		self.scan = scanner_module.ChannelParamsScan(self.engine)
		self.assertTrue(self.scan.start())
		self.assertEqual(self.records, [])
		self.now += common.TUNE_GAP
		self.scan.tick()
		self.assertEqual(len(self.records), 1)

	def test_scheduled_final_retry_removes_only_successful_channel_error(self):
		common.cfg.cp_retry_failed.value = True
		self.engine.running = False
		self.engine.maybe_deep_standby = lambda: None
		reports = []
		with patch.object(scanner_module, "save_cfg"), \
				patch.object(common.cfg.cp_auto_in_progress, "value", False), \
				patch.object(common.cfg.cp_last_run, "value", 0):
			self.assertTrue(scanner_module.start_scheduled(self.engine, done=reports.append))
			scan = self.engine.channel_params_scan
			for _ in range(400):
				for job in list(scan.active):
					if scan.final_retry_started and job.ref == self.rows[0]["ref"]:
						if job.state == "strojenie":
							self.sections = {0: [self.pat([(1, 307)])], 307: [self.pmt(1)]}
							scan.rec_event(job.rec, enigma.iRecordableService.evTunedIn)
					elif job.state == "strojenie":
						scan.rec_event(job.rec, enigma.iRecordableService.evTuneFailed)
				self.now += 1
				scan.tick()
				if self.engine.channel_params_scan is None:
					break
			self.assertFalse(common.cfg.cp_auto_in_progress.value)
			self.assertEqual(len(reports), 1)
			self.assertEqual(reports[0]["scanned"], 1)
			self.assertEqual([row["ref"] for row in reports[0]["errors"]], [self.rows[1]["ref"]])

	def test_final_retry_runs_once_only_after_all_initial_jobs(self):
		common.cfg.cp_retry_failed.value = True
		reports = []
		self.scan.done = reports.append
		self.scan.start()
		initial_counts = None
		for _ in range(40):
			for job in list(self.scan.active):
				self.scan.rec_event(job.rec, enigma.iRecordableService.evTuneFailed)
			self.now += 31
			self.scan.tick()
			if self.scan.final_retry_started and initial_counts is None:
				initial_counts = [sum(rec.ref == row["ref"] for rec in self.records) for row in self.rows]
			if self.engine.channel_params_scan is None:
				break
		self.assertEqual(sorted(initial_counts), [1, 2])
		self.assertEqual(sorted(sum(rec.ref == row["ref"] for rec in self.records) for row in self.rows), [2, 3])
		self.assertEqual(len(reports), 1)
		self.assertEqual(len(reports[0]["errors"]), 2)

	def test_failed_channels_are_not_retried_without_the_optional_final_pass(self):
		reports = []
		self.scan.done = reports.append
		self.scan.start()
		for _ in range(20):
			for job in list(self.scan.active):
				self.scan.rec_event(job.rec, enigma.iRecordableService.evTuneFailed)
			self.now += common.TUNE_GAP
			self.scan.tick()
			if self.engine.channel_params_scan is None:
				break
		self.assertEqual(sorted(sum(rec.ref == row["ref"] for rec in self.records) for row in self.rows), [1, 2])
		self.assertEqual(len(reports), 1)
		self.assertEqual(len(reports[0]["errors"]), 2)

	def test_failed_scan_preserves_previous_result_and_reports_error(self):
		self.rows = self.rows[:1]
		old = {"name": "old result", "selectedAudioPid": 123}
		self.scan.results[self.rows[0]["ref"]] = old
		reports = []
		self.scan.done = reports.append
		self.scan.start()
		self.scan.rec_event(self.records[0], enigma.iRecordableService.evTuneFailed)
		self.now += common.TUNE_GAP
		self.scan.tick()
		self.assertIs(self.scan.results[self.rows[0]["ref"]], old)
		self.assertEqual(reports[0]["scanned"], 0)
		self.assertEqual([row["ref"] for row in reports[0]["errors"]], [self.rows[0]["ref"]])


class ChannelParamsLayoutTests(unittest.TestCase):
	def test_audio_hints_and_device_buttons_fit_at_fhd_and_hd(self):
		import test_actions_ui
		real = test_actions_ui.common
		path = os.path.join(ROOT, "EPGScan", "channelparamsui.py")
		spec = importlib.util.spec_from_file_location(PACKAGE + ".layout_probe", path)
		module = importlib.util.module_from_spec(spec)
		with patch.dict(sys.modules, ui_modules), \
				patch.object(common, "keys_skin", real.keys_skin), \
				patch.object(common, "hints_skin", real.hints_skin):
			spec.loader.exec_module(module)
		for scale in (1.0, 2.0 / 3.0):
			with self.subTest(scale=scale), patch.object(real, "UI_SCALE", scale):
				audio = ET.fromstring(real.scale_skin(module.ChannelAudioScreen.skin))
				names = {w.get("name") for w in audio.findall("widget")}
				for index in range(3):
					self.assertIn("hint_cap%d" % index, names)
					self.assertIn("hint_txt%d" % index, names)
				screen = ET.fromstring(real.scale_skin(module.ChannelParamsDevicesScreen.skin))
				width, height = (int(value) for value in screen.get("size").split(","))
				for widget in screen.findall("widget"):
					x, y = (int(value) for value in widget.get("position").split(","))
					w, h = (int(value) for value in widget.get("size").split(","))
					self.assertLessEqual(x + w, width, widget.get("name"))
					self.assertLessEqual(y + h, height, widget.get("name"))

	def test_audio_menu_dispatches_real_actions(self):
		screen = channelparamsui.ChannelAudioScreen.__new__(channelparamsui.ChannelAudioScreen)
		screen.choose_automatic = Mock()
		screen.choose_user = Mock()
		screen.options_selected(("Automat", "automatic"))
		screen.options_selected(("1. jezyk", 0))
		screen.options_selected(("2. jezyk", 1))
		screen.options_selected(None)
		screen.choose_automatic.assert_called_once_with()
		self.assertEqual(screen.choose_user.call_args_list, [unittest.mock.call(0), unittest.mock.call(1)])


if __name__ == "__main__":
	unittest.main()
