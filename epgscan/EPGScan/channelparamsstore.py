# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
"""Explicit, backup-protected persistence for E2Stream channel settings."""

import base64
import json
import os
import re
import shutil
import time

from .channelparams import parse_pmt


def _audio(result, pid):
	return next((stream for stream in result.get("streams", [])
		if stream.get("kind") == "audio" and stream.get("pid") == pid), None)


def _audio_value(stream):
	language = (stream.get("languages") or [""])[0]
	return "%s|%s|%s" % (stream["pid"], language, stream.get("codec", ""))


def _sync_key(ref):
	return re.sub(r"[^A-Z0-9]", "_", ref.strip().rstrip(":").upper()).strip("_")


def _sync_payload(results):
	audio = {}
	pmts = {}
	collisions = set()
	for result in results.values():
		pid = result.get("selectedAudioPid")
		if not result.get("selectedConfirmed") or pid is None:
			continue
		stream = _audio(result, pid)
		if stream is None:
			continue
		audio["audio_" + _sync_key(result["ref"])] = _audio_value(stream)
		sid = result.get("sid")
		section = result.get("section")
		if sid is None or not section:
			continue
		key = "pmts_%d" % int(sid)
		try:
			parsed = parse_pmt(base64.b64decode(section, validate=True), int(sid))
		except Exception as e:
			raise ValueError("Nieprawidlowa PMT dla %s: %s" % (result.get("name", result["ref"]), e))
		value = base64.b64encode(parsed["section"]).decode("ascii")
		if key in pmts and pmts[key] != value:
			collisions.add(key)
		else:
			pmts[key] = value
	for key in collisions:
		pmts.pop(key, None)
	return audio, pmts, collisions


def _atomic_json(path, data):
	tmp = path + ".tmp"
	try:
		with open(tmp, "w", encoding="utf-8") as f:
			json.dump(data, f, ensure_ascii=False, indent=1, sort_keys=True)
			f.flush()
			os.fsync(f.fileno())
		os.replace(tmp, path)
	except Exception:
		try:
			os.unlink(tmp)
		except OSError:
			pass
		raise


def _read_json(path):
	if not os.path.exists(path):
		return {}
	with open(path, encoding="utf-8") as f:
		data = json.load(f)
	if not isinstance(data, dict):
		raise ValueError("Oczekiwano obiektu JSON w %s" % path)
	return data


def _backup(path, stamp):
	name = path + ".bak-" + str(stamp)
	i = 1
	while os.path.exists(name):
		name = path + ".bak-%s-%d" % (stamp, i)
		i += 1
	shutil.copy2(path, name)
	return name


def add_common_defaults(results):
	from . import clients
	audio, pmts, conflicts = _sync_payload(results)
	if not audio and not pmts:
		raise ValueError("Brak potwierdzonych wyborow sciezki do zapisania")
	data = _read_json(clients.COMMON)
	values = data.get("data") or {}
	if not isinstance(values, dict):
		raise ValueError("Nieprawidlowa sekcja data w common.json")
	changed = []
	for key, value in list(audio.items()) + list(pmts.items()):
		old = values.get(key)
		if old is None:
			values[key] = {"v": value, "t": int(time.time() * 1000)}
			changed.append(key)
		elif not isinstance(old, dict) or old.get("v") != value:
			conflicts.add(key)
	if not changed:
		return "Wartosci wspolne juz istnieja; niczego nie nadpisano.", sorted(conflicts)
	if not os.path.isdir(clients.DIR):
		os.makedirs(clients.DIR)
	if os.path.exists(clients.COMMON):
		_backup(clients.COMMON, int(time.time() * 1000))
	data["data"] = values
	data["updated"] = int(time.time() * 1000)
	_atomic_json(clients.COMMON, data)
	return "Dodano %d wartosci jako domyslne dla nowych urzadzen." % len(changed), sorted(conflicts)


def apply_to_devices(results, selected_files):
	from . import clients
	audio, pmts, conflicts = _sync_payload(results)
	if not audio and not pmts:
		raise ValueError("Brak potwierdzonych wyborow sciezki do zapisania")
	if not os.path.isdir(clients.DIR):
		raise ValueError("Brak katalogu profili E2Stream")
	allowed = set(n for n in os.listdir(clients.DIR) if n.startswith("device-") and n.endswith(".json"))
	targets = [n for n in selected_files if n in allowed]
	if not targets:
		raise ValueError("Nie wybrano aktywnego odbiornika")

	stamp = int(time.time() * 1000)
	originals = {}
	updated = {}
	for name in targets:
		path = os.path.join(clients.DIR, name)
		with open(path, "rb") as f:
			raw = f.read()
		data = json.loads(raw.decode("utf-8"))
		if not isinstance(data, dict) or not isinstance(data.get("data", {}), dict):
			raise ValueError("Nieprawidlowy profil odbiornika: %s" % name)
		originals[path] = raw
		entries = data.setdefault("data", {})
		for key, value in audio.items():
			entries[key] = {"v": value, "t": stamp}
		for key, value in pmts.items():
			old = entries.get(key)
			if old is None or (isinstance(old, dict) and old.get("v") == value):
				entries[key] = {"v": value, "t": stamp}
			else:
				conflicts.add(key)
		data["updated"] = stamp
		updated[path] = data

	written = []
	try:
		for path, data in updated.items():
			_backup(path, stamp)
			_atomic_json(path, data)
			written.append(path)
	except Exception as e:
		rollback_errors = []
		for path in written:
			try:
				tmp = path + ".rollback"
				with open(tmp, "wb") as f:
					f.write(originals[path])
					f.flush()
					os.fsync(f.fileno())
				os.replace(tmp, path)
			except OSError as rollback_error:
				rollback_errors.append("%s: %s" % (path, rollback_error))
		if rollback_errors:
			raise RuntimeError("Blad zapisu profilu (%s); rollback niepelny: %s" %
				(e, "; ".join(rollback_errors)))
		raise
	return "Zapisano ustawienia w %d odbiornikach." % len(written), sorted(conflicts)
