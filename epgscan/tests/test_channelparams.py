import base64
import errno
import importlib.util
import os
import struct
import sys
import types
import unittest
from unittest.mock import patch


path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "EPGScan", "channelparams.py")
spec = importlib.util.spec_from_file_location("channelparams", path)
channelparams = importlib.util.module_from_spec(spec)
spec.loader.exec_module(channelparams)


class ChannelParametersTest(unittest.TestCase):
	def test_section_reader_sets_filter_and_handles_nonblocking_reads(self):
		ioctl = unittest.mock.Mock()
		module = types.SimpleNamespace(ioctl=ioctl)
		section = base64.b64decode("ArAsQo/DAADhGPAAG+EY8AAE4hjwBgoEcG9sAAbjGPAKegKAAgoEcWFhAEkPdSI=")
		with patch.dict(sys.modules, {"fcntl": module}), \
				patch.object(channelparams.platform, "machine", return_value="armv7l"), \
				patch.object(channelparams.os, "O_NONBLOCK", getattr(os, "O_NONBLOCK", 0x800), create=True), \
				patch.object(channelparams.os, "open", return_value=42) as open_device, \
				patch.object(channelparams.os, "read", side_effect=[BlockingIOError(errno.EAGAIN, "busy"), section]), \
				patch.object(channelparams.os, "close") as close:
			reader = channelparams.SectionReader(0, 3, 307, 2)
			open_device.assert_called_once_with("/dev/dvb/adapter0/demux3", os.O_RDONLY | os.O_NONBLOCK)
			fd, request, params = ioctl.call_args.args
			pid, filt, mask, mode, timeout, flags = struct.unpack("@H16s16s16sII", params)
			self.assertEqual((fd, request, pid, filt[0], mask[0], timeout, flags),
				(42, channelparams.SectionReader.DMX_SET_FILTER, 307, 2, 255, 0, 5))
			self.assertIsNone(reader.read())
			self.assertEqual(reader.read(), section)
			reader.close()
			reader.close()
			close.assert_called_once_with(42)

	def test_section_reader_preserves_multiple_and_partial_sections(self):
		body = bytes.fromhex("00b0110001c100000000e010426fe133")
		section = body + channelparams._crc32(body).to_bytes(4, "big")
		module = types.SimpleNamespace(ioctl=unittest.mock.Mock())
		with patch.dict(sys.modules, {"fcntl": module}), \
				patch.object(channelparams.platform, "machine", return_value="armv7l"), \
				patch.object(channelparams.os, "O_NONBLOCK", getattr(os, "O_NONBLOCK", 0x800), create=True), \
				patch.object(channelparams.os, "open", return_value=42), \
				patch.object(channelparams.os, "read", side_effect=[
					section + section + section[:4], BlockingIOError(errno.EAGAIN, "busy"), section[4:]
				]) as read, \
				patch.object(channelparams.os, "close"):
			reader = channelparams.SectionReader(0, 0, 0, 0)
			self.assertEqual(reader.read(), section)
			self.assertEqual(reader.read(), section)
			self.assertEqual(read.call_count, 1)
			self.assertIsNone(reader.read())
			self.assertEqual(reader.read(), section)
			reader.close()

	def test_section_reader_closes_demux_if_filter_setup_fails(self):
		module = types.SimpleNamespace(ioctl=unittest.mock.Mock(side_effect=OSError("filter failed")))
		with patch.dict(sys.modules, {"fcntl": module}), \
				patch.object(channelparams.platform, "machine", return_value="armv7l"), \
				patch.object(channelparams.os, "O_NONBLOCK", getattr(os, "O_NONBLOCK", 0x800), create=True), \
				patch.object(channelparams.os, "open", return_value=42), \
				patch.object(channelparams.os, "close") as close:
			with self.assertRaises(OSError):
				channelparams.SectionReader(0, 0, 0, 0)
			close.assert_called_once_with(42)

	def test_section_reader_rejects_unknown_ioctl_architecture(self):
		with patch.object(channelparams.platform, "machine", return_value="mips"):
			with self.assertRaisesRegex(OSError, "Unsupported DVB ioctl architecture"):
				channelparams.SectionReader(0, 0, 0, 0)

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
		self.assertEqual(channelparams.parse_pat_entries(section), {17007: 307})

	def test_pat_extracts_multiple_services_from_one_section(self):
		body = bytes.fromhex("00b0150001c100000000e010426fe133428fe134")
		section = body + channelparams._crc32(body).to_bytes(4, "big")
		self.assertEqual(channelparams.parse_pat_entries(section), {17007: 307, 17039: 308})

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
