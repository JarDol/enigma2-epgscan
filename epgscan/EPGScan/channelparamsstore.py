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
from .i18n import _


def display_message(message):
	base, separator, conflicts = message.partition(" Pominiete konflikty: ")
	base, cleanup_separator, cleanup = base.partition(" Usunieto ")
	patterns = (
		(r"^Dodano (\d+) wartosci jako domyslne dla nowych urzadzen\.$",
			_("Dodano %d wartosci jako domyslne dla nowych urzadzen.")),
		(r"^Zapisano ustawienia w (\d+) odbiornikach\.$",
			_("Zapisano ustawienia w %d odbiornikach.")),
	)
	for pattern, template in patterns:
		match = re.match(pattern, base)
		if match:
			base = template % int(match.group(1))
			break
	else:
		base = _(base)
	if cleanup_separator:
		match = re.match(r"^(\d+) nieuzywanych PMT z profili\.$", cleanup)
		base += (_(" Usunieto %d nieuzywanych PMT z profili.") % int(match.group(1))
			if match else cleanup_separator + cleanup)
	return base + (_(" Pominiete konflikty: %s") % conflicts if separator else "")


def _audio(result, pid):
	return next((stream for stream in result.get("streams", [])
		if stream.get("kind") == "audio" and stream.get("pid") == pid), None)


def _audio_value(stream):
	language = (stream.get("languages") or [""])[0]
	return "%s|%s|%s" % (stream["pid"], language, stream.get("codec", ""))


def _stored_value(entry):
	return entry.get("v") if isinstance(entry, dict) else entry


def _valid_audio_entry(entry, result):
	try:
		pid = int(str(_stored_value(entry)).split("|", 1)[0])
	except (TypeError, ValueError):
		return False
	return _audio(result, pid) is not None


def _current_pmt(result):
	try:
		return parse_pmt(base64.b64decode(result["section"], validate=True), int(result["sid"]))
	except Exception:
		return None


def _mode(overwrite, mode, allowed):
	mode = mode if mode is not None else ("overwrite" if overwrite else "missing")
	if mode not in allowed:
		raise ValueError("Nieznany tryb synchronizacji: %s" % mode)
	return mode


def _sync_key(ref):
	return re.sub(r"[^A-Z0-9]", "_", ref.strip().rstrip(":").upper()).strip("_")


def _sync_payload(results, include_pmt=True):
	audio = {}
	audio_sources = {}
	pmts = {}
	collisions = set()
	for result in results.values():
		pid = result.get("selectedAudioPid")
		stream = _audio(result, pid) if result.get("selectedConfirmed") and pid is not None else None
		if stream is not None:
			key = "audio_" + _sync_key(result["ref"])
			audio[key] = _audio_value(stream)
			audio_sources[key] = result
		if not include_pmt:
			continue
		sid = result.get("sid")
		section = result.get("section")
		if not section:
			continue
		try:
			key = "pmts_%d" % int(sid)
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
	return audio, audio_sources, pmts, collisions


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


def add_common_defaults(results, overwrite=False, mode=None):
	from . import clients
	mode = _mode(overwrite, mode, ("missing", "overwrite"))
	audio, _audio_sources, pmts, conflicts = _sync_payload(results)
	if not audio and not pmts:
		if conflicts:
			return "PMT pominieto z powodu kolizji SID; niczego nie zapisano.", sorted(conflicts)
		raise ValueError("Brak prawidlowych PMT lub potwierdzonych wyborow audio do zapisania")
	data = _read_json(clients.COMMON)
	values = data.get("data", {})
	if not isinstance(values, dict):
		raise ValueError("Nieprawidlowa sekcja data w common.json")
	changed = []
	for key, value in audio.items():
		old = values.get(key)
		if mode == "overwrite" or key not in values:
			values[key] = {"v": value, "t": int(time.time() * 1000)}
			changed.append(key)
		elif _stored_value(old) != value:
			conflicts.add(key)
	for key, value in pmts.items():
		old = values.get(key)
		if mode == "overwrite" or key not in values:
			values[key] = {"v": value, "t": int(time.time() * 1000)}
			changed.append(key)
		elif _stored_value(old) != value:
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


def device_profiles():
	from . import clients
	if not os.path.isdir(clients.DIR):
		raise ValueError("Brak katalogu profili E2Stream")
	return sorted(name for name in os.listdir(clients.DIR)
		if name.startswith("device-") and name.endswith(".json") and os.path.isfile(os.path.join(clients.DIR, name)))


def apply_to_devices(results, selected_files, overwrite=False, mode=None):
	from . import clients
	mode = _mode(overwrite, mode, ("missing", "repair", "overwrite"))
	audio, audio_sources, _pmts, conflicts = _sync_payload(results, include_pmt=False)
	allowed = set(device_profiles())
	targets = list(dict.fromkeys(n for n in selected_files if n in allowed))
	if not targets:
		raise ValueError("Nie wybrano aktywnego odbiornika")

	common_audio = {}
	if mode == "repair":
		common = _read_json(clients.COMMON).get("data", {})
		if not isinstance(common, dict):
			raise ValueError("Nieprawidlowa sekcja data w common.json")
		for key, entry in common.items():
			if key.startswith("audio_"):
				value = _stored_value(entry)
				if not isinstance(value, str) or not value:
					raise ValueError("Nieprawidlowy wpis %s w common.json" % key)
				common_audio[key] = value

	stamp = int(time.time() * 1000)
	originals = {}
	updated = {}
	removed_pmts = 0
	for name in targets:
		path = os.path.join(clients.DIR, name)
		with open(path, "rb") as f:
			raw = f.read()
		data = json.loads(raw.decode("utf-8"))
		if not isinstance(data, dict) or not isinstance(data.get("data", {}), dict):
			raise ValueError("Nieprawidlowy profil odbiornika: %s" % name)
		originals[path] = raw
		entries = data.setdefault("data", {})
		changed = False
		for key in list(entries):
			if key.startswith("pmts_"):
				del entries[key]
				removed_pmts += 1
				changed = True
		for key, value in common_audio.items():
			if key not in entries:
				entries[key] = {"v": value, "t": stamp}
				changed = True
		for key, value in audio.items():
			old = entries.get(key)
			current = _current_pmt(audio_sources[key]) if mode == "repair" else None
			invalid = current is not None and not _valid_audio_entry(old, {"streams": current["streams"]})
			if mode == "overwrite" or key not in entries or (mode == "repair" and invalid):
				if mode == "repair" and (current is None or
						not _valid_audio_entry(value, {"streams": current["streams"]})):
					conflicts.add(key)
					continue
				entries[key] = {"v": value, "t": stamp}
				changed = True
			elif _stored_value(old) != value:
				conflicts.add(key)
		if changed:
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
	message = "Zapisano ustawienia w %d odbiornikach." % len(written)
	if removed_pmts:
		message += " Usunieto %d nieuzywanych PMT z profili." % removed_pmts
	return message, sorted(conflicts)
