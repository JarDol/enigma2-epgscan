import unittest

import test_actions_ui


class CoverageTextLocalizationTests(unittest.TestCase):
	def test_epg_span_in_both_languages(self):
		common = test_actions_ui.common
		previous = common._active_language
		try:
			common._active_language = "en"
			self.assertEqual(common.fmt_span(0), "none")
			self.assertEqual(common.fmt_span(5400), "1.5 h")
			self.assertEqual(common.fmt_span(172800), "2.0 days")
			common._active_language = "pl"
			self.assertEqual(common.fmt_span(0), "brak")
			self.assertEqual(common.fmt_span(172800), "2.0 dni")
		finally:
			common._active_language = previous

	def test_profile_and_stats_translate_for_display_but_not_logs(self):
		common = test_actions_ui.common
		profile = {"sw": "zmiany", "sat": True, "dek": True, "srv": "zapas",
			"other": False, "channelparams": True}
		stats = {"future": 1, "past": 2, "channels": 3, "dups": 4,
			"min": 1, "median": 2, "max": 3, "until": "tomorrow"}
		previous = common._active_language
		try:
			common._active_language = "en"
			display = common.profile_text(profile, localized=True)
			self.assertIn("SatWatch (changes)", display)
			self.assertIn("satellite", display)
			self.assertIn("receiver", display)
			self.assertIn("server (fallback)", display)
			self.assertIn("channel parameter scan", display)
			self.assertIn("satelita", common.profile_text(profile))
			self.assertIn("zapas", common.profile_text(profile))
			self.assertIn("\ncoverage", common.stats_text(stats, localized=True))
			self.assertIn(", zasieg dni:", common.stats_text(stats))
		finally:
			common._active_language = previous


if __name__ == "__main__":
	unittest.main()
