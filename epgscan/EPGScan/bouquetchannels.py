# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
"""Flatten channel entries from Enigma2 bouquets without hiding unsupported services."""

import os
import re


def read_bouquet_channels(files, directory, name_for_ref):
	out = []
	seen_files = set()
	seen_refs = set()

	def walk(fname):
		if fname in seen_files:
			return
		seen_files.add(fname)
		try:
			with open(os.path.join(directory, fname), encoding="utf-8", errors="ignore") as f:
				lines = f.read().splitlines()
		except Exception as e:
			out.append({"ref": "", "name": fname, "supported": False, "error": "Nie mozna odczytac bukietu: %s" % e})
			return
		for i, line in enumerate(lines):
			if not line.startswith("#SERVICE "):
				continue
			ref = line[9:].strip()
			m = re.search(r'FROM BOUQUET "([^"]+)"', ref)
			if m:
				walk(m.group(1))
				continue
			p = ref.split(":")
			try:
				flags = int(p[1])
			except (IndexError, ValueError):
				continue
			if flags & 64 or len(p) < 10:
				continue
			if ref in seen_refs:
				continue
			supported = p[0] == "1" and p[3] != "0" and "%3a" not in ref.lower()
			if supported:
				ref = ":".join(p[:10]) + ":"
			if ref in seen_refs:
				continue
			seen_refs.add(ref)
			name = ""
			if i + 1 < len(lines) and lines[i + 1].startswith("#DESCRIPTION "):
				name = lines[i + 1][13:].strip()
			out.append({"ref": ref, "name": name or name_for_ref(ref), "supported": supported,
				"error": "" if supported else "Nieobslugiwany typ kanalu (np. IPTV)"})

	for fname in files:
		walk(fname)
	return out
