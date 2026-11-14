import os
import tempfile
import unittest

import test_actions_ui

common = test_actions_ui.common


class PathMigrationTests(unittest.TestCase):
	def test_old_files_move_to_plugin_directory_once_without_overwriting(self):
		with tempfile.TemporaryDirectory() as root:
			old_file = os.path.join(root, "epgscan_tps.json")
			old_dir = os.path.join(root, "epgscan_sources", "wlasne")
			kept = os.path.join(root, "epgscan_wakeup")
			os.makedirs(old_dir)
			for path, text in ((old_file, "tps"), (os.path.join(old_dir, "a.sources.xml"), "src"), (kept, "old")):
				with open(path, "w") as f:
					f.write(text)
			data = os.path.join(root, "epgscan")
			os.makedirs(data)
			with open(os.path.join(data, "wakeup"), "w") as f:
				f.write("new")
			pairs = [(old_file, os.path.join(data, "tps.json")),
				(os.path.join(root, "epgscan_sources"), os.path.join(data, "sources")),
				(kept, os.path.join(data, "wakeup")),
				(os.path.join(root, "missing"), os.path.join(data, "missing"))]
			moved = common.migrate_paths(pairs)
			self.assertEqual(len(moved), 2)
			self.assertEqual(open(os.path.join(data, "tps.json")).read(), "tps")
			self.assertTrue(os.path.exists(os.path.join(data, "sources", "wlasne", "a.sources.xml")))
			self.assertFalse(os.path.exists(old_file))
			self.assertEqual(open(os.path.join(data, "wakeup")).read(), "new")   # nowe nie nadpisane
			self.assertTrue(os.path.exists(kept))                                 # stare zostaje
			self.assertEqual(common.migrate_paths(pairs), [])                     # drugi raz nic

	def test_all_plugin_files_live_under_one_directory(self):
		for path in (common.TP_STATS, common.WAKEUP_FILE, common.XMLTV_STATE_FILE, common.PLUGIN_SOURCES_DIR,
				common.CHANNEL_PARAMS_FILE):
			self.assertTrue(path.startswith("/etc/enigma2/epgscan/"), path)
		# pliki stanu SatWatch: bez podkatalogu, z przedrostkiem satwatch-
		targets = [new for old, new in common.OLD_PATHS if "/satwatch/" in old]
		self.assertIn("/etc/enigma2/epgscan/satwatch-state.json", targets)
		self.assertTrue(all(os.path.dirname(t) == "/etc/enigma2/epgscan" for t in targets))

	def test_rotated_logs_and_reports_older_than_setting_are_removed(self):
		import time
		with tempfile.TemporaryDirectory() as root:
			names = ["epgscan.log", "epgscan.log.1", "epgscan.log.2", "satwatch.log.1", "inne.log.1"]
			os.makedirs(os.path.join(root, "raporty"))
			names += [os.path.join("raporty", "satwatch-1.txt"), os.path.join("raporty", "satwatch-2.txt")]
			old = time.time() - 40 * 86400
			for name in names:
				p = os.path.join(root, name)
				with open(p, "w") as f:
					f.write("x")
				if name != "epgscan.log.1":
					os.utime(p, (old, old))
			os.utime(os.path.join(root, "raporty", "satwatch-2.txt"), (old + 10, old + 10))
			from unittest.mock import patch
			with patch.object(common, "log_path", return_value=os.path.join(root, "epgscan.log")), \
					patch.object(common, "log_days", return_value=30):
				removed = common.prune_logs()
			self.assertEqual(removed, 3)       # epgscan.log.2, satwatch.log.1, starszy raport
			left = sorted(os.listdir(root)) + sorted(os.listdir(os.path.join(root, "raporty")))
			self.assertEqual(left, ["epgscan.log", "epgscan.log.1", "inne.log.1", "raporty", "satwatch-2.txt"])


if __name__ == "__main__":
	unittest.main()
