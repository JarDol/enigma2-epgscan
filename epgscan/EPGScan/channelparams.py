# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
"""DVB program tables used by the channel-parameter scanner."""


def _crc32(data):
	crc = 0xffffffff
	for byte in data:
		crc ^= byte << 24
		for _ in range(8):
			crc = ((crc << 1) ^ (0x04c11db7 if crc & 0x80000000 else 0)) & 0xffffffff
	return crc


def _section(data, table_id, minimum):
	if len(data) < 3 or data[0] != table_id or not data[1] & 0x80:
		raise ValueError("Invalid DVB table header")
	length = ((data[1] & 0x0f) << 8) | data[2]
	end = 3 + length
	if end < minimum or end > len(data) or length > 1021:
		raise ValueError("Incomplete or oversized DVB table")
	data = data[:end]
	if _crc32(data) != 0:
		raise ValueError("Invalid DVB table CRC")
	if not data[5] & 1:
		raise ValueError("DVB table is not current")
	return data


def parse_pat(data, service_id):
	"""Return the PMT PID for a service from one complete PAT section."""
	data = _section(data, 0x00, 12)
	end = len(data) - 4
	if (end - 8) % 4:
		raise ValueError("Invalid PAT entries")
	for offset in range(8, end, 4):
		sid = (data[offset] << 8) | data[offset + 1]
		if sid == service_id:
			return ((data[offset + 2] & 0x1f) << 8) | data[offset + 3]
	return None


def _descriptors(data):
	items = []
	pos = 0
	while pos < len(data):
		if pos + 2 > len(data):
			raise ValueError("Incomplete descriptor header")
		length = data[pos + 1]
		end = pos + 2 + length
		if end > len(data):
			raise ValueError("Incomplete descriptor body")
		items.append((data[pos], data[pos + 2:end]))
		pos = end
	return items


def _languages(descriptors, tag, step):
	languages = []
	for descriptor, body in descriptors:
		if descriptor != tag:
			continue
		if len(body) % step:
			raise ValueError("Invalid language descriptor")
		for offset in range(0, len(body), step):
			try:
				lang = body[offset:offset + 3].decode("ascii").lower()
			except UnicodeDecodeError:
				raise ValueError("Invalid language code")
			if lang not in languages:
				languages.append(lang)
	return languages


def parse_pmt(data, service_id):
	"""Parse a complete, current PMT while preserving its raw section."""
	data = _section(data, 0x02, 16)
	sid = (data[3] << 8) | data[4]
	if sid != service_id:
		raise ValueError("PMT belongs to a different service")
	if data[6] != 0 or data[7] != 0:
		raise ValueError("Multi-section PMT is not supported")
	end = len(data) - 4
	program_length = ((data[10] & 0x0f) << 8) | data[11]
	pos = 12 + program_length
	if pos > end:
		raise ValueError("Invalid program descriptors")
	_descriptors(data[12:pos])
	streams = []
	while pos < end:
		if pos + 5 > end:
			raise ValueError("Incomplete PMT stream")
		stream_type = data[pos]
		pid = ((data[pos + 1] & 0x1f) << 8) | data[pos + 2]
		length = ((data[pos + 3] & 0x0f) << 8) | data[pos + 4]
		next_pos = pos + 5 + length
		if next_pos > end:
			raise ValueError("Incomplete PMT stream descriptors")
		descriptors = _descriptors(data[pos + 5:next_pos])
		tags = {tag for tag, _ in descriptors}
		kind, codec = "other", ""
		if stream_type in (0x01, 0x02, 0x1b, 0x24):
			kind, codec = "video", {0x01: "MPEG-1", 0x02: "MPEG-2", 0x1b: "H.264", 0x24: "H.265"}[stream_type]
		elif stream_type in (0x03, 0x04):
			kind, codec = "audio", "audio/mpeg-L2"
		elif stream_type in (0x0f, 0x11):
			kind, codec = "audio", "audio/mp4a-latm"
		elif stream_type == 0x87 or 0x7a in tags:
			kind, codec = "audio", "audio/eac3"
		elif stream_type == 0x81 or 0x6a in tags:
			kind, codec = "audio", "audio/ac3"
		elif 0x59 in tags:
			kind, codec = "subtitle", "DVB"
		elif 0x56 in tags:
			kind, codec = "teletext", "DVB"
		languages = _languages(descriptors, 0x0a, 4)
		if kind == "subtitle":
			languages = _languages(descriptors, 0x59, 8)
		elif kind == "teletext":
			languages = _languages(descriptors, 0x56, 5)
		streams.append({"pid": pid, "type": stream_type, "kind": kind, "codec": codec, "languages": languages})
		pos = next_pos
	return {"sid": sid, "pcr_pid": ((data[8] & 0x1f) << 8) | data[9],
		"version": (data[5] >> 1) & 0x1f, "streams": streams, "section": data}


def choose_audio(streams, preferred):
	"""Choose only an unambiguous language match; unknowns need review."""
	for language in preferred:
		lang = language.strip().lower()
		if lang in ("pl", "pol", "polish", "poland", "polski"):
			lang = "pol"
		elif lang in ("en", "eng", "english"):
			lang = "eng"
		if not lang or lang in ("und", "unknown", "none"):
			continue
		candidates = [s for s in streams if s["kind"] == "audio" and lang in s["languages"]]
		if len(candidates) == 1:
			return candidates[0]
		if len(candidates) > 1:
			return None
	return None
