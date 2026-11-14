import ast
import gettext
import importlib
from pathlib import Path
import re
import sys
import types
import unittest
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "satwatch_localization_test"


class Timer:
    def __init__(self):
        self.callback = []

    def start(self, *args):
        pass

    def stop(self):
        pass


class Config:
    def __init__(self, *args, default=None, choices=None, **kwargs):
        self.value = default
        self.choices = types.SimpleNamespace(choices=list(choices or []))

    def setChoices(self, choices, default=None):
        self.choices.choices = list(choices)
        self.value = default


class Screen(dict):
    def __init__(self, session):
        self.session = session
        self.onLayoutFinish = []

    def setTitle(self, title):
        self.title = title


class ConfigListScreen:
    def __init__(self, entries, session=None):
        self["config"] = types.SimpleNamespace(onSelectionChanged=[])


def module(name, **attrs):
    m = types.ModuleType(name)
    m.__dict__.update(attrs)
    return m


class SatWatchLocalizationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = ROOT / "EPGScan" / "locale" / "en" / "LC_MESSAGES" / "EPGScan.mo"
        with path.open("rb") as stream:
            cls.translation = gettext.GNUTranslations(stream)
        cls.catalog = cls.translation._catalog
        package = module(PACKAGE, __path__=[str(ROOT / "EPGScan")])
        config = module("Components.config", config=types.SimpleNamespace(plugins=types.SimpleNamespace()),
                        configfile=Mock(), ConfigSubsection=types.SimpleNamespace,
                        getConfigListEntry=lambda label, value: (label, value), NoSave=lambda value: value)
        for name in ("ConfigYesNo", "ConfigClock", "ConfigText", "ConfigSelection", "ConfigSelectionNumber"):
            setattr(config, name, Config)
        enigma = module("enigma", eTimer=Timer, eComponentScan=Mock, eDVBFrontendParametersSatellite=Mock,
                        getDesktop=Mock(), eDVBDB=Mock(), eServiceReference=Mock)
        common = module(PACKAGE + ".common", EPGChoiceBox=object, keys_skin=lambda *a: "",
                        init_keys=lambda *a: None, set_keys=lambda *a, **k: None, scale_skins=lambda *a: None)
        cls.modules = {
            PACKAGE: package,
            PACKAGE + ".common": common,
            "enigma": enigma,
            "twisted": module("twisted", __path__=[]),
            "twisted.internet": module("twisted.internet", threads=Mock()),
            "Components": module("Components", __path__=[]),
            "Components.config": config,
            "Components.ActionMap": module("Components.ActionMap", ActionMap=Mock),
            "Components.ConfigList": module("Components.ConfigList", ConfigListScreen=ConfigListScreen),
            "Components.Label": module("Components.Label", Label=Mock),
            "Components.MenuList": module("Components.MenuList", MenuList=Mock),
            "Components.ScrollLabel": module("Components.ScrollLabel", ScrollLabel=Mock),
            "Components.SelectionList": module("Components.SelectionList", SelectionList=Mock, SelectionEntryComponent=Mock),
            "Components.Sources": module("Components.Sources", __path__=[]),
            "Components.Sources.StaticText": module("Components.Sources.StaticText", StaticText=Mock),
            "Screens": module("Screens", __path__=[]),
            "Screens.Screen": module("Screens.Screen", Screen=Screen),
            "Screens.MessageBox": module("Screens.MessageBox", MessageBox=Mock),
            "Tools": module("Tools", Notifications=Mock()),
        }
        cls.patcher = patch.dict(sys.modules, cls.modules)
        cls.patcher.start()
        cls.i18n = importlib.import_module(PACKAGE + ".i18n")
        cls.i18n._catalog = cls.translation
        cls.language = "en"
        cls.i18n.configure(lambda: cls.language)
        cls.core = importlib.import_module(PACKAGE + ".satwatch.core")
        cls.scanner = importlib.import_module(PACKAGE + ".satwatch.scanner")
        cls.pick = importlib.import_module(PACKAGE + ".satwatch.pick")
        cls.sw = importlib.import_module(PACKAGE + ".satwatch.sw")

    @classmethod
    def tearDownClass(cls):
        cls.patcher.stop()
        for name in list(sys.modules):
            if name == PACKAGE or name.startswith(PACKAGE + "."):
                del sys.modules[name]

    def setUp(self):
        type(self).language = "en"

    def test_decision_reasons_are_display_only_and_follow_language(self):
        entry = {"name": "A", "candidates": [{"name": "A", "known_prov": True, "same_sid": True},
                                                {"name": "A", "known_prov": True, "same_sid": False}]}
        candidate, reason = self.core.decide(entry)
        self.assertIs(candidate, entry["candidates"][0])
        self.assertEqual(reason, "ta sama nazwa, rozstrzyga: ten sam SID")
        self.assertEqual(self.core.display_reason(reason), "same name, tie-break: same SID")
        type(self).language = "pl"
        self.assertEqual(self.core.display_reason(reason), reason)

    def test_scan_report_localizes_but_retry_status_remains_polish(self):
        result = {"position": 130, "status": "przerwano - brak wolnego tunera przez 15 min", "ok": False}
        lines = self.core.format_scan_report("pelny (wznowienie)", [result], [], [], [])
        self.assertEqual(lines[0], "SatWatch: Scan (full scan (resumed)): 1 transponders, successful 0, failed 1")
        self.assertIn("stopped - no free tuner for 15 min", lines[1])
        self.assertEqual(result["status"], "przerwano - brak wolnego tunera przez 15 min")
        state = {}
        with patch.object(self.core, "load_json", return_value=state), patch.object(self.core, "save_json") as saved, \
             patch.object(self.core, "tp_id", return_value=(1,)):
            self.core.scan_failed_update([result], keep_aborted=False)
        self.assertEqual(saved.call_args[0][1]["pending_failed"], [])

    def test_missing_tuner_is_not_retried(self):
        result = {"position": 130, "status": "brak tunera skonfigurowanego na 13.0E", "ok": False}
        self.assertEqual(self.core.display_scan_status(result["status"]), "no tuner configured for 13.0E")
        with patch.object(self.core, "load_json", return_value={}), patch.object(self.core, "save_json") as saved, \
             patch.object(self.core, "tp_id", return_value=(1,)):
            self.core.scan_failed_update([result])
        self.assertEqual(saved.call_args[0][1]["pending_failed"], [])

    def test_progress_translates_display_but_keeps_log_text(self):
        progress = Mock()
        scanner = self.scanner.TPScanner(None, [{"position": 130}], 1, on_progress=progress)
        with patch.object(self.scanner, "eligible_slots", return_value=[0]), \
             patch.object(scanner, "free_slots", return_value=[]), \
             patch.object(scanner, "free_total", return_value=[]), \
             patch.object(self.scanner.core, "tp_str", return_value="TP"), \
             patch.object(self.scanner, "log") as log:
            scanner.step()
        self.assertIn("Scan 1/1", progress.call_args[0][0])
        self.assertIn("waiting for a free tuner", progress.call_args[0][0])
        self.assertIn("Skan 1/1", log.call_args[0][0])
        self.assertIn("czekam na wolny tuner", log.call_args[0][0])

    def test_bouquet_picker_switches_language_without_changing_values(self):
        with patch.object(self.pick, "bouquet_names", return_value=["A", "B"]):
            self.assertEqual(self.pick.bouquets_text(""), "all (2)")
            self.assertEqual(self.pick.bouquets_text("A"), "1 of 2")
            type(self).language = "pl"
            self.assertEqual(self.pick.bouquets_text("A"), "1 z 2")
            self.assertEqual(self.pick.ignore_from_selection("", ["B"]), "A")

    def test_candidate_panel_and_help_use_current_language(self):
        entry = {"key": "1:2:3:4", "name": "Old", "provider": "P", "tp": "TP", "type": 1}
        candidate = {"key": "2:2:3:4", "name": "New", "provider": "Q", "tp": "TP",
                     "ratio": 0.6, "in_bq": True, "known_prov": False}
        text = self.sw._cand_info(entry, candidate, 0, 1)
        self.assertIn("Candidate 1/1", text)
        self.assertIn("ALREADY in your bouquets", text)
        self.assertIn("different from previous (P)", text)
        self.assertIn("What to do", self.i18n._(self.sw.SatWatchSetup.HELP["action"]))
        type(self).language = "pl"
        self.assertIn("Kandydat 1/1", self.sw._cand_info(entry, candidate, 0, 1))

    def test_setup_rebuilds_labels_and_preserves_saved_choice(self):
        cfg = self.sw.cfg
        cfg.scan_tuner.value = "auto"
        cfg.logdir.value = "/media/unmounted/satwatch"
        self.sw.SatWatchSetup(None)
        self.assertEqual(cfg.scan_tuner.value, "auto")
        self.assertEqual(cfg.scan_tuner.choices.choices[0][1], "automatically (highest free tuner)")
        self.assertEqual(cfg.action.value, "update")
        self.assertEqual(dict(cfg.action.choices.choices)["update"], "report + update satellites.xml")
        self.assertEqual(cfg.full_day.value, "6")
        self.assertEqual(dict(cfg.full_day.choices.choices)["6"], "Sunday")
        self.assertEqual(cfg.logdir.value, "/media/unmounted/satwatch")
        type(self).language = "pl"
        self.sw.SatWatchSetup(None)
        self.assertEqual(cfg.scan_tuner.choices.choices[0][1], "automatycznie (najwyższy wolny)")
        self.assertEqual(dict(cfg.action.choices.choices)["update"], "raport + aktualizacja satellites.xml")
        self.assertEqual(dict(cfg.full_day.choices.choices)["6"], "niedziela")
        self.assertEqual(cfg.logdir.value, "/media/unmounted/satwatch")

    def test_report_history_parser_finds_meaningful_english_line(self):
        diff = {"added": [], "removed": [], "changed": [], "shifted": []}
        lines = [self.i18n._("SatWatch %s  |  %s") % ("1", "now")]
        lines.extend(self.core.format_tp_diff(130, diff))
        matches = [line for line in lines if line.startswith("SatWatch:") or
                   any(marker in line for marker in ("zmian", "Skan", "Naprawa", "Decyzja"))]
        self.assertEqual(matches, ["SatWatch: Position 13.0E: no changes"])

    def test_catalogue_covers_literal_calls_and_format_placeholders(self):
        pattern = re.compile(r"%(?:\([^)]+\))?[#0 +\-]*\d*(?:\.\d+)?[a-zA-Z%]")
        for msgid, msgstr in self.catalog.items():
            self.assertEqual(pattern.findall(msgid), pattern.findall(msgstr), msgid)
            self.assertTrue(msgstr, msgid)
        for path in (ROOT / "EPGScan" / "satwatch").glob("*.py"):
            for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
                if (isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "_"
                        and len(node.args) == 1 and isinstance(node.args[0], ast.Constant)
                        and isinstance(node.args[0].value, str)):
                    self.assertIn(node.args[0].value, self.catalog, (path, node.lineno))


if __name__ == "__main__":
    unittest.main()
