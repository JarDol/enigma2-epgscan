import ast
import gettext
import importlib.util
import importlib
import os
import re
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import test_actions_ui
import test_channelparamscan


ROOT = os.path.dirname(os.path.dirname(__file__))
I18N_PATH = os.path.join(ROOT, "EPGScan", "i18n.py")
spec = importlib.util.spec_from_file_location("epgscan_i18n_test", I18N_PATH)
i18n = importlib.util.module_from_spec(spec)
spec.loader.exec_module(i18n)


class Selection:
	def __init__(self):
		self.value = "selected"
		self.default = "all"
		self.choices = types.SimpleNamespace(choices=[
			("all", "wszystkie bukiety"), ("selected", "wybrane bukiety")])

	def setChoices(self, choices, default=None):
		self.choices.choices = choices
		self.default = default
		self.value = default


class LocalizationTests(unittest.TestCase):
	def test_initial_language_uses_loaded_config_value(self):
		package_name = "epgscan_language_initial_test"
		package = types.ModuleType(package_name)
		package.__path__ = [os.path.join(ROOT, "EPGScan")]
		bouquets = types.ModuleType(package_name + ".bouquetchannels")
		bouquets.read_bouquet_channels = lambda *args: []

		class LoadedConfig(test_actions_ui.FakeConfig):
			def __init__(self, *args, **kwargs):
				super().__init__(*args, **kwargs)
				if kwargs.get("choices") and [x[0] for x in kwargs["choices"]] == ["enigma", "pl", "en"]:
					self.value = "en"

		config_module = types.ModuleType("Components.config")
		for name in ("ConfigSubsection", "ConfigYesNo", "ConfigText", "ConfigDirectory",
				"ConfigInteger", "ConfigSelectionNumber", "ConfigClock"):
			setattr(config_module, name, getattr(test_actions_ui.config_module, name))
		config_module.ConfigSelection = LoadedConfig
		config_module.config = types.SimpleNamespace(plugins=types.SimpleNamespace())
		modules = dict(test_actions_ui.modules, **{
			package_name: package, package_name + ".bouquetchannels": bouquets,
			"Components.config": config_module,
		})
		with patch.dict(sys.modules, modules):
			common = importlib.import_module(package_name + ".common")
			language_module = importlib.import_module(package_name + ".i18n")
			self.assertEqual(common.cfg.ui_language.value, "en")
			self.assertEqual(language_module.language(), "en")
			self.assertEqual(language_module._("Jezyk wtyczki"), "Plugin language")

	def test_follows_enigma_changes_and_manual_override(self):
		state = {"choice": "enigma", "system": "en_GB"}
		i18n.configure(lambda: state["choice"])
		module = types.ModuleType("Components.Language")
		module.language = types.SimpleNamespace(getLanguage=lambda: state["system"])
		with patch.dict(sys.modules, {"Components.Language": module}):
			self.assertEqual(i18n.language(), "en")
			state["system"] = "pl_PL"
			self.assertEqual(i18n.language(), "pl")
			state["choice"] = "en"
			self.assertEqual(i18n.language(), "en")
			state["choice"] = "pl"
			state["system"] = "en_US"
			self.assertEqual(i18n.language(), "pl")
			state["choice"] = "enigma"
			state["system"] = "de_DE"
			self.assertEqual(i18n.language(), "en")

	def test_catalogue_format_and_fallback(self):
		i18n.configure(lambda: "en")
		self.assertEqual(i18n._("EPG Scan %s - ustawienia") % "1.2", "EPG Scan 1.2 - settings")
		self.assertEqual(i18n._("unknown external channel"), "unknown external channel")
		i18n.configure(lambda: "pl")
		self.assertEqual(i18n._("Jezyk wtyczki"), "Jezyk wtyczki")

	def test_missing_catalog_and_receiver_language_are_logged(self):
		old_catalog, old_warning = i18n._catalog, i18n._language_warning
		try:
			i18n.configure(lambda: "en")
			i18n._catalog = None
			with patch.object(i18n.gettext, "translation", side_effect=OSError("missing")), \
					self.assertLogs(i18n.__name__, level="WARNING") as logs:
				self.assertEqual(i18n._("Jezyk wtyczki"), "Jezyk wtyczki")
				self.assertEqual(i18n._("Jezyk wtyczki"), "Jezyk wtyczki")
			self.assertEqual(len(logs.output), 1)
			i18n.configure(lambda: "enigma")
			i18n._language_warning = False
			module = types.ModuleType("Components.Language")
			module.language = types.SimpleNamespace(getLanguage=Mock(side_effect=RuntimeError("unavailable")))
			with patch.dict(sys.modules, {"Components.Language": module}), \
					self.assertLogs(i18n.__name__, level="WARNING") as logs:
				self.assertEqual(i18n.language(), "pl")
				self.assertEqual(i18n.language(), "pl")
			self.assertEqual(len(logs.output), 1)
		finally:
			i18n._catalog, i18n._language_warning = old_catalog, old_warning

	def test_compiled_catalog_matches_source_and_covers_literal_messages(self):
		root = Path(ROOT)
		catalog_dir = root / "EPGScan" / "locale" / "en" / "LC_MESSAGES"
		po, mo = catalog_dir / "EPGScan.po", catalog_dir / "EPGScan.mo"
		spec = importlib.util.spec_from_file_location("epgscan_catalog_compiler", root / "tools" / "compile_translations.py")
		compiler = importlib.util.module_from_spec(spec)
		spec.loader.exec_module(compiler)
		with tempfile.TemporaryDirectory() as directory:
			rebuilt = Path(directory) / "EPGScan.mo"
			compiler.compile_catalogue(po, rebuilt)
			self.assertEqual(rebuilt.read_bytes(), mo.read_bytes())
		with mo.open("rb") as stream:
			messages = gettext.GNUTranslations(stream)._catalog
		pattern = re.compile(r"%(?:\([^)]+\))?[#0\- +]*(?:\*|\d+)?(?:\.(?:\*|\d+))?[hlL]?[diouxXeEfFgGcrsa%]")
		for msgid, msgstr in messages.items():
			if msgid:
				self.assertEqual(pattern.findall(msgid), pattern.findall(msgstr), msgid)
		for source in (root / "EPGScan").rglob("*.py"):
			for node in ast.walk(ast.parse(source.read_text(encoding="utf-8"))):
				if (isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "_"
						and node.args and isinstance(node.args[0], ast.Constant)
						and isinstance(node.args[0].value, str)):
					self.assertIn(node.args[0].value, messages, (source, node.lineno))

	def test_choice_labels_change_without_changing_value(self):
		choice = Selection()
		i18n.configure(lambda: "en")
		i18n.translate_choices(choice)
		self.assertEqual(choice.value, "selected")
		self.assertEqual(choice.default, "all")
		self.assertEqual(choice.choices.choices[1], ("selected", "selected bouquets"))
		i18n.configure(lambda: "pl")
		i18n.translate_choices(choice)
		self.assertEqual(choice.value, "selected")
		self.assertEqual(choice.default, "all")
		self.assertEqual(choice.choices.choices[1], ("selected", "wybrane bukiety"))

	def test_numeric_choices_leave_the_value_and_options_unchanged(self):
		choice = Selection()
		choice.value = 4
		choice.choices.choices = ["1", "4", "10"]
		i18n.configure(lambda: "en")
		with patch.object(choice, "setChoices", wraps=choice.setChoices) as set_choices:
			i18n.translate_choices(choice)
			set_choices.assert_not_called()
		self.assertEqual(choice.value, 4)
		self.assertEqual(choice.choices.choices, ["1", "4", "10"])

	def test_unlabelled_choices_are_translated_without_changing_values(self):
		choice = Selection()
		choice.value = "dopisz braki"
		choice.choices.choices = ["wylaczona", "dopisz braki"]
		i18n.configure(lambda: "en")
		i18n.translate_choices(choice)
		self.assertEqual(choice.value, "dopisz braki")
		self.assertEqual(choice.choices.choices,
			[("wylaczona", "disabled"), ("dopisz braki", "add missing entries")])
		i18n.configure(lambda: "pl")
		i18n.translate_choices(choice)
		self.assertEqual(choice.value, "dopisz braki")
		self.assertEqual(choice.choices.choices,
			[("wylaczona", "wylaczona"), ("dopisz braki", "dopisz braki")])

	def test_dictionary_choices_keep_original_labels_and_selection(self):
		choice = Selection()
		choice.value = "missing"
		choice.choices.choices = {"off": "wylaczona", "missing": "dopisz braki"}
		i18n.configure(lambda: "en")
		i18n.translate_choices(choice)
		self.assertEqual(choice.value, "missing")
		self.assertEqual(choice.choices.choices,
			[("off", "disabled"), ("missing", "add missing entries")])
		i18n.configure(lambda: "pl")
		i18n.translate_choices(choice)
		self.assertEqual(choice.value, "missing")
		self.assertEqual(choice.choices.choices,
			[("off", "wylaczona"), ("missing", "dopisz braki")])

	def test_language_change_is_applied_explicitly(self):
		common = test_actions_ui.common
		original = common.cfg.ui_language.value
		active = common._active_language
		try:
			common.cfg.ui_language.value = "pl"
			common.apply_ui_language()
			common.cfg.ui_language.value = "en"
			self.assertEqual(common._active_language, "pl")
			common.cfg.ui_language.save()
			common.apply_ui_language()
			self.assertEqual(common._active_language, "en")
		finally:
			common.cfg.ui_language.value = original
			common._active_language = active

	def test_settings_save_applies_language_and_cancel_keeps_saved_language(self):
		common = test_actions_ui.common
		enigma = types.ModuleType("enigma")
		enigma.__dict__.update(test_actions_ui.enigma_module.__dict__)
		enigma.eTimer = type("eTimer", (), {})
		selection = types.ModuleType("Components.SelectionList")
		selection.SelectionList = type("SelectionList", (), {})
		selection.SelectionEntryComponent = lambda *args: args
		mapping = types.ModuleType(test_actions_ui.PACKAGE + ".mapping")
		mapping.report_schedule = lambda: None
		modules = dict(test_actions_ui.modules, **{
			"enigma": enigma, "Components.SelectionList": selection,
			test_actions_ui.PACKAGE + ".mapping": mapping,
		})
		cfg = common.cfg
		old_value, old_active = cfg.ui_language.value, common._active_language
		try:
			with patch.dict(sys.modules, modules):
				setup = importlib.import_module(test_actions_ui.PACKAGE + ".setup")
				self.assertTrue(any(item is cfg.ui_language for item in setup.all_items()))
				screen = setup.EPGScanSetup.__new__(setup.EPGScanSetup)
				screen.close = Mock()
				cfg.ui_language.value = "en"
				common._active_language = "pl"
				with patch.object(setup, "all_items", return_value=[cfg.ui_language]), \
						patch.object(cfg.ui_language, "save") as save, \
						patch.object(setup, "save_cfg") as save_cfg, \
						patch.object(common, "apply_eit_policy"), \
						patch.object(cfg.xmltv_bouquets, "isChanged", return_value=False, create=True), \
						patch.object(cfg.cli_api, "isChanged", return_value=False, create=True), \
						patch.object(cfg.cli_port, "isChanged", return_value=False, create=True), \
						patch.object(cfg.cli_token, "isChanged", return_value=False, create=True), \
						patch.object(cfg.site_order, "isChanged", return_value=False, create=True), \
						patch.object(cfg.one_place, "isChanged", return_value=False, create=True), \
						patch.object(cfg.cand_night, "isChanged", return_value=False, create=True):
					screen.save()
					save.assert_called_once()
					save_cfg.assert_called_once()
					self.assertEqual(common._active_language, "en")
					screen.close.assert_called_once_with(True)
				screen.close.reset_mock()
				cfg.ui_language.value = "pl"
				with patch.object(setup, "all_items", return_value=[cfg.ui_language]), \
						patch.object(cfg.ui_language, "cancel", create=True,
							side_effect=lambda: setattr(cfg.ui_language, "value", "en")) as cancel:
					screen.cancel()
					cancel.assert_called_once()
					self.assertEqual(cfg.ui_language.value, "en")
					self.assertEqual(common._active_language, "en")
					screen.close.assert_called_once_with(False)
		finally:
			cfg.ui_language.value = old_value
			common._active_language = old_active

	def test_installed_local_readme_follows_active_language(self):
		common = test_actions_ui.common
		menus = test_actions_ui.menus
		active = common._active_language
		try:
			common._active_language = "en"
			self.assertIn("EPG Scan", menus.readme_text())
			self.assertIn("Interface language", menus.readme_text())
			common._active_language = "pl"
			self.assertIn("Język wtyczki", menus.readme_text())
		finally:
			common._active_language = active

	def test_audio_options_translate_labels_not_action_values(self):
		ui = test_channelparamscan.channelparamsui
		screen = ui.ChannelAudioScreen.__new__(ui.ChannelAudioScreen)
		screen.session = Mock()
		i18n.configure(lambda: "en")
		with patch.object(ui, "_", i18n._):
			screen.options_menu()
		args, kwargs = screen.session.openWithCallback.call_args
		self.assertEqual(kwargs["title"], "Audio options")
		self.assertEqual(kwargs["list"], [
			("Enigma2 automatic", "automatic"), ("1st language", 0), ("2nd language", 1)])

	def test_channel_details_keep_external_data_and_refs(self):
		ui = test_channelparamscan.channelparamsui
		screen = ui.ChannelParamsInfoScreen.__new__(ui.ChannelParamsInfoScreen)
		result = {"name": "Canal Test", "ref": "1:0:1:123", "pmtPid": 42,
			"pat": "PAT-DATA", "section": "PMT-DATA",
			"streams": [{"kind": "audio", "pid": 100, "codec": "AAC", "languages": ["pol"]}]}
		i18n.configure(lambda: "en")
		with patch.object(ui, "_", i18n._):
			text = screen._format(result)
		self.assertIn("Channel: Canal Test", text)
		self.assertIn("Service reference: 1:0:1:123", text)
		self.assertIn("audio PID 100 | AAC | languages: pol", text)
		self.assertIn("PAT-DATA", text)
		self.assertIn("PMT-DATA", text)

	def test_parameter_report_translates_status_without_changing_stored_data(self):
		scanner = test_channelparamscan.scanner_module
		store = test_channelparamscan.store_module
		report = {"scanned": 1, "errors": [{"name": "TV", "error": "brak dostrojenia (tuner A)"}],
			"unsupported": [], "sync": ["Zapisano ustawienia w 2 odbiornikach. Pominiete konflikty: pmts_42"]}
		i18n.configure(lambda: "en")
		with patch.object(scanner, "_", i18n._), patch.object(store, "_", i18n._):
			self.assertEqual(scanner.display_state("zbieranie PAT/PMT"), "collecting PAT/PMT")
			self.assertEqual(scanner.display_state("BLAD: brak dostrojenia (tuner A)"),
				"ERROR: tuning failed (tuner A)")
			text = scanner.format_report(report)
		self.assertIn("Saved settings on 2 receivers. Skipped conflicts: pmts_42", text)
		self.assertIn("tuning failed (tuner A)", text)
		self.assertEqual(report["errors"][0]["error"], "brak dostrojenia (tuner A)")
		self.assertIn("Pominiete konflikty:", report["sync"][0])

	def test_scan_summaries_and_errors_translate_without_changing_source(self):
		i18n.configure(lambda: "en")
		summary = "2/23 transponderow, 1 bledow, 3 pominietych, czas 5:09"
		self.assertEqual(i18n.display_scan_summary(summary),
			"2/23 transponders, 1 errors, 3 skipped, time 5:09")
		self.assertEqual(summary, "2/23 transponderow, 1 bledow, 3 pominietych, czas 5:09")
		self.assertEqual(i18n.display_scan_summary(
			"doczytywanie: 2/23 transponderow, zasieg wydluzony na 4 (maks. +1.5 h), czas 0:42"),
			"top-up: 2/23 transponders, coverage extended on 4 (max. +1.5 h), time 0:42")
		self.assertEqual(i18n.display_scan_summary("import XMLTV po wyczyszczeniu EPG: external detail"),
			"XMLTV import after clearing EPG: external detail")
		self.assertEqual(i18n.display_engine_note("Skan parametrow nieudany: tuner B"),
			"Parameter scan failed: tuner B")
		self.assertEqual(i18n.display_engine_note("Skan przerwany"), "Scan stopped")
		self.assertEqual(i18n.display_engine_note("external service: detail"), "external service: detail")

	def test_download_progress_and_server_labels_keep_internal_values(self):
		with patch.dict(sys.modules, test_actions_ui.modules):
			grab_module = importlib.import_module(test_actions_ui.PACKAGE + ".localgrab")
			netfind = importlib.import_module(test_actions_ui.PACKAGE + ".netfind")
		grab = grab_module.LocalGrab.__new__(grab_module.LocalGrab)
		grab.phase, grab.retried, grab.errors = "ponawianie 4 stron", 4, 1
		server = {"ip": "192.0.2.1", "port": 8088, "via": ["podsiec"], "guide": 0}
		i18n.configure(lambda: "en")
		with patch.object(grab_module, "_", i18n._), patch.object(netfind, "_", i18n._):
			self.assertEqual(grab.progress(localized=True), "retrying 4 pages (errors remaining: 1)")
			self.assertIn("subnet; no programme file", netfind.label(server))
		self.assertEqual(grab.progress(), "ponawianie 4 stron (zostalo bledow: 1)")
		self.assertEqual(server["via"], ["podsiec"])


if __name__ == "__main__":
	unittest.main()
