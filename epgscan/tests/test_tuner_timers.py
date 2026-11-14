import importlib
import sys
import types
import unittest
from unittest.mock import patch

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


if __name__ == "__main__":
	unittest.main()
