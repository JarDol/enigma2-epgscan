import base64
import importlib.util
import os
import unittest


path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "EPGScan", "channelparams.py")
spec = importlib.util.spec_from_file_location("channelparams", path)
channelparams = importlib.util.module_from_spec(spec)
spec.loader.exec_module(channelparams)


class ChannelParametersTest(unittest.TestCase):
	def test_real_pmt_extracts_service_audio_and_languages(self):
		section = base64.b64decode("ArAsQo/DAADhGPAAG+EY8AAE4hjwBgoEcG9sAAbjGPAKegKAAgoEcWFhAEkPdSI=")
		result = channelparams.parse_pmt(section, 17039)
		audio = [item for item in result["streams"] if item["kind"] == "audio"]
		self.assertEqual([(x["pid"], x["languages"]) for x in audio], [(536, ["pol"]), (792, ["qaa"])])
		self.assertEqual(channelparams.choose_audio(audio, ["pol"])["pid"], 536)
		self.assertEqual(channelparams.choose_audio(audio, ["Poland"])["pid"], 536)
		self.assertEqual(audio[1]["codec"], "audio/eac3")

	def test_unknown_and_ambiguous_audio_need_review(self):
		def stream(pid, languages):
			return {"pid": pid, "kind": "audio", "languages": languages}
		self.assertIsNone(channelparams.choose_audio([stream(1, [])], ["pol"]))
		self.assertIsNone(channelparams.choose_audio([stream(1, ["pol"]), stream(2, ["pol"])], ["pol"]))
		self.assertEqual(channelparams.choose_audio([stream(1, ["eng"])], ["pol", "eng"])["pid"], 1)
		self.assertIsNone(channelparams.choose_audio([stream(1, ["und"])], ["und"]))

	def test_pat_finds_service_without_confusing_network_entry(self):
		body = bytes.fromhex("00b0110001c100000000e010426fe133")
		section = body + channelparams._crc32(body).to_bytes(4, "big")
		self.assertEqual(channelparams.parse_pat(section, 17007), 307)
		self.assertIsNone(channelparams.parse_pat(section, 1))

	def test_invalid_and_wrong_service_pmt_are_rejected(self):
		section = base64.b64decode("ArAsQo/DAADhGPAAG+EY8AAE4hjwBgoEcG9sAAbjGPAKegKAAgoEcWFhAEkPdSI=")
		with self.assertRaises(ValueError):
			channelparams.parse_pmt(section, 1)
		with self.assertRaises(ValueError):
			channelparams.parse_pmt(section[:-1], 17039)
		with self.assertRaises(ValueError):
			channelparams.parse_pmt(section[:-1] + bytes([section[-1] ^ 1]), 17039)


if __name__ == "__main__":
	unittest.main()
