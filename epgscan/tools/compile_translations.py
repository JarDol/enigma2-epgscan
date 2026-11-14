#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Compile this plugin's singular gettext catalogue without external tools."""

import ast
import struct
from pathlib import Path


def compile_catalogue(source, destination):
	messages = {}
	entry = {}
	field = None
	fuzzy = False

	def finish():
		if "msgid" in entry and "msgstr" in entry and not fuzzy:
			key = entry["msgid"]
			if key in messages:
				raise ValueError("Duplicate msgid: %r" % key)
			messages[key] = entry["msgstr"]
		entry.clear()

	for line in source.read_text(encoding="utf-8").splitlines() + [""]:
		line = line.strip()
		if not line:
			finish()
			field = None
			fuzzy = False
		elif line.startswith("#,"):
			fuzzy = "fuzzy" in line.split()
		elif line.startswith("#"):
			continue
		elif line.startswith("msgid ") or line.startswith("msgstr "):
			field, value = line.split(None, 1)
			entry[field] = ast.literal_eval(value)
		elif line.startswith('"') and field:
			entry[field] += ast.literal_eval(line)
		else:
			raise ValueError("Unsupported catalogue line: %s" % line)
	items = sorted((key.encode("utf-8"), value.encode("utf-8"))
		for key, value in messages.items() if not key or value)
	count = len(items)
	offset = 28 + count * 16
	ids = b""
	values = b""
	id_table = []
	value_table = []
	for key, value in items:
		id_table.append((len(key), offset + len(ids)))
		ids += key + b"\0"
	offset += len(ids)
	for key, value in items:
		value_table.append((len(value), offset + len(values)))
		values += value + b"\0"
	header = struct.pack("<7I", 0x950412de, 0, count, 28, 28 + count * 8, 0, 0)
	table = b"".join(struct.pack("<2I", *row) for row in id_table + value_table)
	destination.write_bytes(header + table + ids + values)


if __name__ == "__main__":
	path = Path(__file__).resolve().parent.parent / "EPGScan" / "locale" / "en" / "LC_MESSAGES"
	compile_catalogue(path / "EPGScan.po", path / "EPGScan.mo")
