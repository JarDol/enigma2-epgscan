import importlib
import os
import sys
import types
import unittest
from unittest.mock import patch


ROOT = os.path.dirname(os.path.dirname(__file__))
PACKAGE = "mapping_i18n_test_plugin"
package = types.ModuleType(PACKAGE)
package.__path__ = [os.path.join(ROOT, "EPGScan")]


class Screen(dict):
	def __init__(self, session):
		self.session = session
		self.onClose = []
		self.onLayoutFinish = []

	def setTitle(self, title):
		self.title = title


class Label:
	def __init__(self, text=""):
		self.text = text

	def setText(self, text):
		self.text = text


class MenuList:
	def __init__(self, rows):
		self.rows = rows
		self.onSelectionChanged = []

	def setList(self, rows):
		self.rows = rows

	def getCurrent(self):
		return self.rows[0] if self.rows else None

	def moveToIndex(self, index):
		pass


class Timer:
	def __init__(self):
		self.callback = []

	def start(self, *args):
		pass

	def stop(self):
		pass


class Session:
	def openWithCallback(self, callback, screen, *args, **kwargs):
		self.options = kwargs


def module(name, **values):
	m = types.ModuleType(name)
	m.__dict__.update(values)
	return m


class MappingTranslationTests(unittest.TestCase):
	@classmethod
	def setUpClass(cls):
		cls.language = "pl"
		cls.translations = {
			"wszystkie": "all", "bez dopasowania": "unmatched",
			"bez programu": "no guide", "Pokaz kanaly": "Show channels",
			"serwer": "server", "dekoder": "receiver",
			"reczne": "manual", "  [reczne]": "  [manual]",
			"brak dopasowania": "unmatched", "brak wskazania": "no selection",
			"   [obecne]": "   [current]",
			"Tylko pobierz program na serwerze": "Only download guide on server",
			"wybrane bukiety": "selected bouquets",
		}
		def translate(text):
			return cls.translations.get(text, text) if cls.language == "en" else text
		cfg = types.SimpleNamespace(
			map_sort=types.SimpleNamespace(value="bq"),
			xmltv_bouquets=types.SimpleNamespace(value="selected"),
			sat_import=types.SimpleNamespace(value=True))
		common = module(PACKAGE + ".common", cfg=cfg, EPGChoiceBox=object,
			keys_skin=lambda *a, **k: "", hints_skin=lambda *a, **k: "",
			init_keys=lambda *a: None, set_keys=lambda *a, **k: None,
			init_hints=lambda *a: None, log=lambda *a: None, scale_skins=lambda *a: None,
			XB_CHOICES=[("all", "wszystkie"), ("selected", "wybrane bukiety")],
			xmltv_bouquet_files=lambda: ["one"])
		modules = {
			PACKAGE: package, PACKAGE + ".common": common, PACKAGE + ".i18n": module(PACKAGE + ".i18n", _=translate),
			"enigma": module("enigma", eTimer=Timer),
			"Screens.Screen": module("Screens.Screen", Screen=Screen),
			"Screens.MessageBox": module("Screens.MessageBox", MessageBox=type("MessageBox", (), {})),
			"Components.ActionMap": module("Components.ActionMap", ActionMap=lambda *a: None),
			"Components.Label": module("Components.Label", Label=Label),
			"Components.MenuList": module("Components.MenuList", MenuList=MenuList),
		}
		cls.module_patch = patch.dict(sys.modules, modules)
		cls.module_patch.start()
		cls.mapping = importlib.import_module(PACKAGE + ".mapping")
		cls.station = importlib.import_module(PACKAGE + ".station")

	@classmethod
	def tearDownClass(cls):
		cls.module_patch.stop()

	def setUp(self):
		type(self).language = "en"

	def test_filter_choices_translate_at_display_time_preserving_ids(self):
		screen = self.mapping.EPGScanMapping.__new__(self.mapping.EPGScanMapping)
		screen.session = Session()
		screen.local = True
		screen.filter = 0
		screen.diff = None
		screen.props = {}
		screen.mapa = {"channels": []}
		screen.next_filter()
		options = screen.session.options
		self.assertEqual(options["title"], "Show channels")
		self.assertEqual(options["list"][0], ("* all  (0)", 0))
		self.assertEqual(self.mapping.FILTERS[0], ("all", "wszystkie"))
		type(self).language = "pl"
		screen.next_filter()
		self.assertEqual(screen.session.options["list"][0], ("* wszystkie  (0)", 0))

	def test_server_grab_menu_keeps_mode_values(self):
		screen = self.mapping.EPGScanMapping.__new__(self.mapping.EPGScanMapping)
		screen.session = Session()
		screen.grab = types.SimpleNamespace(active=False)
		screen.local = False
		screen.grab_menu()
		self.assertEqual(screen.session.options["list"][0], ("Only download guide on server", "only"))
		self.assertEqual(self.mapping.GRAB_MODES[0][0], "only")

	def test_bouquet_labels_keep_selection_values(self):
		screen = self.mapping.EPGScanMapping.__new__(self.mapping.EPGScanMapping)
		screen.session = Session()
		screen.bq_menu()
		self.assertEqual(screen.session.options["list"][1], ("* selected bouquets", "selected"))

	def test_side_text_and_station_names_preserved(self):
		screen = self.mapping.EPGScanDiffs.__new__(self.mapping.EPGScanDiffs)
		screen.names = {"tm": "Teleman"}
		row = {"dek": {"tag": "tm", "site_id": "id-1", "sname": "bez programu", "manual": True}}
		self.assertEqual(screen.side_text(row, "dek"), "Teleman: bez programu  [manual]")
		row = {"pins_only": True, "dek": {"pin": "-"}}
		self.assertEqual(screen.side_text(row, "dek"), "no guide")
		station = self.station.EPGScanStation.__new__(self.station.EPGScanStation)
		station.chan = {"tag": "tm", "site_id": "id-1"}
		station.names = {"tm": "Teleman"}
		station.stats = {}
		self.assertEqual(station.row("tm", "id-1", "bez programu"),
			("Teleman: bez programu   [current]", ("tm", "id-1", "bez programu")))

	def test_external_preview_source_is_not_translated(self):
		screen = self.mapping.EPGScanPreview.__new__(self.mapping.EPGScanPreview)
		dict.__init__(screen)
		screen.target = "test"
		screen.window = (-1, 1)
		screen["info"] = Label()
		data = {"ok": True, "from": "dekoder", "programmes": []}
		with patch.object(self.mapping, "cached_preview", return_value=None), \
				patch.dict(self.mapping.PREVIEW_CACHE, {}, clear=True):
			screen.base = "https://example.test/"
			screen.loaded(data, None)
			self.assertEqual(screen.src, "dekoder")
			screen.base = None
			screen.loaded(data, None)
			self.assertEqual(screen.src, "receiver")


if __name__ == "__main__":
	unittest.main()
