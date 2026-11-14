import importlib
import os
import sys
import types
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch


ROOT = os.path.dirname(os.path.dirname(__file__))
PACKAGE = "actions_ui_test_plugin"
package = types.ModuleType(PACKAGE)
package.__path__ = [os.path.join(ROOT, "EPGScan")]


class FakeScreen(dict):
	def __init__(self, session):
		dict.__init__(self)
		self.session = session
		self.onLayoutFinish = []
		self.closed = []

	def setTitle(self, title):
		self.title = title

	def close(self, *args):
		self.closed.append(args)


class FakeLabel:
	def __init__(self, text=""):
		self.text = text

	def setText(self, text):
		self.text = text


class FakeConfig:
	def __init__(self, *args, default=None, choices=None, **kwargs):
		self.value = default
		self.choices = types.SimpleNamespace(choices=list(choices or []))

	def save(self):
		pass


class FakeConfigList:
	def __init__(self, entries):
		self.entries = entries
		self.index = 0
		self.onSelectionChanged = []
		self.invalidated = 0

	def getCurrent(self):
		return self.entries[self.index]

	def invalidateCurrent(self):
		self.invalidated += 1


class FakeConfigListScreen:
	def __init__(self, entries, session=None, on_change=None):
		self["config"] = FakeConfigList(entries)


class FakeActionMap:
	def __init__(self, contexts, actions, priority):
		self.contexts = contexts
		self.actions = actions
		self.priority = priority


class FakeSession:
	def __init__(self):
		self.calls = []

	def open(self, screen, *args, **kwargs):
		self.calls.append(("open", screen, args, kwargs))

	def openWithCallback(self, callback, screen, *args, **kwargs):
		self.calls.append(("callback", screen, args, kwargs, callback))


class FakeMessageBox:
	TYPE_INFO = 0
	TYPE_ERROR = 2
	TYPE_YESNO = 1


class FakeChoiceBox:
	pass


screen_module = types.ModuleType("Screens.Screen")
screen_module.Screen = FakeScreen
message_module = types.ModuleType("Screens.MessageBox")
message_module.MessageBox = FakeMessageBox
choice_module = types.ModuleType("Screens.ChoiceBox")
choice_module.ChoiceBox = FakeChoiceBox
action_module = types.ModuleType("Components.ActionMap")
action_module.ActionMap = FakeActionMap
label_module = types.ModuleType("Components.Label")
label_module.Label = FakeLabel
config_list_module = types.ModuleType("Components.ConfigList")
config_list_module.ConfigListScreen = FakeConfigListScreen
scroll_module = types.ModuleType("Components.ScrollLabel")
scroll_module.ScrollLabel = FakeLabel
config_module = types.ModuleType("Components.config")
config_module.getConfigListEntry = lambda label, value: (label, value)
config_module.config = types.SimpleNamespace(plugins=types.SimpleNamespace())
config_module.ConfigSubsection = types.SimpleNamespace
for name in ("ConfigSelection", "ConfigYesNo", "ConfigText", "ConfigDirectory", "ConfigInteger",
		"ConfigSelectionNumber", "ConfigClock"):
	setattr(config_module, name, FakeConfig)
config_module.NoSave = lambda value: value
enigma_module = types.ModuleType("enigma")
for name in ("eServiceReference", "eServiceCenter", "eEPGCache", "iServiceInformation"):
	setattr(enigma_module, name, type(name, (), {}))
bouquet_module = types.ModuleType(PACKAGE + ".bouquetchannels")
bouquet_module.read_bouquet_channels = lambda *_args: []
screens = types.ModuleType("Screens")
screens.__path__ = []
components = types.ModuleType("Components")
components.__path__ = []

modules = {
	PACKAGE: package, PACKAGE + ".bouquetchannels": bouquet_module, "enigma": enigma_module,
	"Screens": screens, "Screens.Screen": screen_module, "Screens.MessageBox": message_module,
	"Screens.ChoiceBox": choice_module, "Components": components, "Components.ActionMap": action_module,
	"Components.Label": label_module, "Components.ConfigList": config_list_module,
	"Components.ScrollLabel": scroll_module, "Components.config": config_module,
}
with patch.dict(sys.modules, modules):
	common = importlib.import_module(PACKAGE + ".common")
	modules[PACKAGE + ".common"] = common
	menus = importlib.import_module(PACKAGE + ".menus")

setup_module = types.ModuleType(PACKAGE + ".setup")
setup_module.EPGScanSetup = type("EPGScanSetup", (), {})
setup_module.EPGScanXmltvSources = type("EPGScanXmltvSources", (), {})
mapping_module = types.ModuleType(PACKAGE + ".mapping")
mapping_module.EPGScanMapping = type("EPGScanMapping", (), {})
mapping_module.epgpl_servers = lambda: []
channel_module = types.ModuleType(PACKAGE + ".channelparamsui")
channel_module.ChannelParamsScreen = type("ChannelParamsScreen", (), {})
destinations = dict(modules, **{
	PACKAGE + ".setup": setup_module,
	PACKAGE + ".mapping": mapping_module,
	PACKAGE + ".channelparamsui": channel_module,
})


class ActionsWindowTests(unittest.TestCase):
	def setUp(self):
		module_patch = patch.dict(sys.modules, destinations)
		module_patch.start()
		self.addCleanup(module_patch.stop)
		self.session = FakeSession()
		self.screen = menus.EPGScanActions(self.session)
		self.screen.update_help()

	def test_settings_menu_separates_storage_from_updates(self):
		menus.settings_menu(self.session)
		options = self.session.calls[-1][3]["list"]
		self.assertEqual([entry[1] for entry in options[-2:]], ["storage", "updates"])
		callback = self.session.calls[-1][4]
		callback(options[-2])
		self.assertEqual(self.session.calls[-1][2], ("storage",))
		callback(options[-1])
		self.assertEqual(self.session.calls[-1][2], ("updates",))

	def press_ok(self, index):
		self.screen["config"].index = index
		self.screen["actions"].actions["ok"]()

	def test_visible_shortcut_bar_and_ok_opens_selected_satwatch(self):
		for index, caption in enumerate(("OK", "Menu", "Info/EPG")):
			self.assertIn('name="hint_cap%d"' % index, self.screen.skin)
			self.assertEqual(self.screen["hint_cap%d" % index].text, caption)
		for scale in (1.0, 2.0 / 3):
			with self.subTest(scale=scale), patch.object(common, "UI_SCALE", scale):
				root = ET.fromstring(common.scale_skin(self.screen.skin))
				widgets = {item.attrib["name"]: item for item in root.findall("widget")}
				screen_width = int(root.attrib["size"].split(",")[0])
				help_y = int(widgets["help"].attrib["position"].split(",")[1])
				help_height = int(widgets["help"].attrib["size"].split(",")[1])
				key_y = int(widgets["key_red"].attrib["position"].split(",")[1])
				for index in range(3):
					for prefix in ("hint_cap", "hint_txt"):
						widget = widgets["%s%d" % (prefix, index)]
						x, y = (int(value) for value in widget.attrib["position"].split(","))
						width, height = (int(value) for value in widget.attrib["size"].split(","))
						self.assertGreater(width, 0)
						self.assertGreaterEqual(x, 0)
						self.assertLessEqual(x + width, screen_width)
						self.assertGreater(y, help_y + help_height)
						self.assertLessEqual(y + height, key_y)
		with patch.object(menus, "satwatch_menu") as satwatch:
			self.press_ok(0)
		satwatch.assert_called_once_with(self.session)
		self.assertEqual(self.screen.closed, [])

	def test_ok_opens_each_existing_module_without_starting_profile(self):
		cases = [
			(2, setup_module.EPGScanSetup, ("sat",)),
			(3, mapping_module.EPGScanMapping, (None, "dekoder")),
			(5, setup_module.EPGScanXmltvSources, ()),
			(7, channel_module.ChannelParamsScreen, ()),
		]
		with patch.dict(sys.modules, destinations):
			for index, screen_class, args in cases:
				with self.subTest(index=index):
					self.session.calls.clear()
					self.press_ok(index)
					self.assertEqual(self.session.calls[-1][:3], ("open", screen_class, args))
					self.assertEqual(self.screen.closed, [])

	def test_server_module_handles_missing_single_and_multiple_servers(self):
		with patch.dict(sys.modules, destinations), patch.object(mapping_module, "epgpl_servers", return_value=[]):
			self.press_ok(4)
			self.assertEqual(self.session.calls[-1][1], FakeMessageBox)
			self.assertEqual(self.screen.closed, [])
		servers = [("http://one/", "Pierwszy"), ("http://two/", "Drugi")]
		with patch.object(mapping_module, "epgpl_servers", return_value=servers[:1]):
			self.press_ok(4)
			self.assertEqual(self.session.calls[-1][:3],
				("open", mapping_module.EPGScanMapping, servers[0]))
		with patch.dict(sys.modules, destinations), patch.object(mapping_module, "epgpl_servers", return_value=servers):
			self.press_ok(4)
			choice_call = self.session.calls[-1]
			self.assertEqual(choice_call[1], common.EPGChoiceBox)
			choice_call[-1](None)
			self.assertIs(self.session.calls[-1], choice_call)
			choice_call[-1](("Drugi", servers[1]))
			self.assertEqual(self.session.calls[-1][:3],
				("open", mapping_module.EPGScanMapping, servers[1]))
		with patch.object(mapping_module, "epgpl_servers", side_effect=OSError("blad")), patch.object(menus, "log") as log:
			self.press_ok(4)
			self.assertEqual(self.session.calls[-1][1], FakeMessageBox)
			self.assertIn("blad", self.session.calls[-1][2][0])
			log.assert_called_once()

	def test_ok_on_profile_only_options_selects_value_without_starting(self):
		for index, element, value in ((1, self.screen.c_clear, "all"), (6, self.screen.c_fetch, "nowe")):
			with self.subTest(index=index):
				previous = element.value
				self.press_ok(index)
				call = self.session.calls[-1]
				self.assertEqual(call[1], common.EPGChoiceBox)
				options = call[3]["list"]
				self.assertEqual(options[call[3]["selection"]][1], previous)
				call[-1](None)
				self.assertEqual(element.value, previous)
				call[-1](next(option for option in options if option[1] == value))
				self.assertEqual(element.value, value)
				self.assertEqual(self.screen.closed, [])
		self.assertEqual(self.screen["config"].invalidated, 2)
		self.assertEqual(common.cfg.clear_mode.value, "none")

	def test_green_starts_profile_and_menu_and_info_open_existing_screens(self):
		with patch.dict(sys.modules, destinations):
			self.screen["actions"].actions["menu"]()
			self.assertEqual(self.session.calls[-1][:3], ("open", setup_module.EPGScanSetup, ("sched",)))
			self.screen["actions"].actions["info"]()
			self.assertEqual(self.session.calls[-1][1], menus.EPGScanText)
			self.assertIn("OK: otworz", self.session.calls[-1][3]["text"])
			self.assertNotIn("OK lub Zielony", self.session.calls[-1][3]["text"])
		self.screen["actions"].actions["green"]()
		self.assertEqual(self.screen.closed, [(self.screen.profile(),)])
		self.screen["actions"].actions["save"]()
		self.assertEqual(self.screen.closed[-1], (self.screen.profile(),))

	def test_yellow_alone_saves_profile_as_default(self):
		with patch.object(common.cfg.clear_mode, "value", "none"), patch.object(common, "apply_eit_policy"), \
				patch.object(menus, "save_cfg") as save, patch.object(menus, "log"):
			self.screen.c_clear.value = "all"
			self.assertEqual(common.cfg.clear_mode.value, "none")
			self.screen["actions"].actions["yellow"]()
			self.assertEqual(common.cfg.clear_mode.value, "all")
			save.assert_called_once()


if __name__ == "__main__":
	unittest.main()
