# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - paczka danych dla aplikacji E2Stream (ostatni krok pracy z harmonogramu albo recznie):
#   epg.json.gz  - EPG kanalow ze wszystkich bukietow TV (na tyle dni, ile jest w bazie, maks. 14),
#   picons.zip   - ikony kanalow (nazwy plikow takie, jakich szuka aplikacja),
#   refs.json    - kanaly bukietow i zamiany referencji (ta sama nazwa w bukiecie, inna referencja,
#                  np. po zmianach SatWatch) - aplikacja przenosi na nie kanal startowy i ostatni.
# Odczyt EPG w paczkach co 30 kanalow na watku glownym (eEPGCache), zapis plikow w tle.

import gzip
import json
import os
import time
import unicodedata
import zipfile

from enigma import eEPGCache, eTimer

from .common import cfg, log, save_cfg

PICON_DIRS = ["/usr/share/enigma2/picon", "/media/hdd/picon", "/media/usb/picon", "/media/mmc/picon",
	"/picon", "/data/picon", "/media/hdd/XPicons/picon", "/usr/share/enigma2/XPicons/picon"]
CHUNK = 30
KEEP_REMAP_DAYS = 30

_job = None


def pack_dir():
	"""Na dysku (obok kopii EPG), gdy jest; inaczej /tmp (odbudowywane po restarcie)."""
	cands = []
	try:
		b = cfg.backup_dir.value
		if b:
			cands.append(os.path.join(b.rstrip("/"), "epgscan-klienci"))
	except Exception:
		pass
	cands += ["/media/hdd/epgscan-klienci", "/tmp/epgscan-klienci"]
	for d in cands:
		base = os.path.dirname(d)
		if d.startswith("/tmp") or (os.path.isdir(base) and os.access(base, os.W_OK) and _mount_of(base) != "/"):
			try:
				if not os.path.isdir(d):
					os.makedirs(d)
				return d
			except Exception:
				continue
	return "/tmp/epgscan-klienci"


def _mount_of(path):
	p = os.path.abspath(path)
	while not os.path.ismount(p):
		p = os.path.dirname(p)
	return p


def ref_key(ref):
	"""Jak OpenWebif.key w aplikacji: bez koncowych ':' i wielkimi literami."""
	return ref.strip().rstrip(":").upper()


def info():
	d = pack_dir()
	meta = _read_json(os.path.join(d, "pack.json")) or {}
	if not meta.get("at"):
		return {"at": 0}
	for k, fn in (("epg_size", "epg.json.gz"), ("picons_size", "picons.zip"), ("refs_size", "refs.json")):
		try:
			meta[k] = os.path.getsize(os.path.join(d, fn))
		except Exception:
			meta[k] = 0
	return meta


def file_path(name):
	if name not in ("epg.json.gz", "picons.zip", "refs.json"):
		return None
	p = os.path.join(pack_dir(), name)
	return p if os.path.isfile(p) else None


def _read_json(path):
	try:
		with open(path) as f:
			return json.load(f)
	except Exception:
		return None


def _snp(name):
	n = unicodedata.normalize("NFD", (name or "").lower().replace("ł", "l"))
	n = "".join(c for c in n if unicodedata.category(c) != "Mn")
	n = n.replace("&", "and").replace("+", "plus").replace("*", "star")
	return "".join(c for c in n if c.isalnum() and c.isascii())


def _picon_index():
	dirs = []
	try:
		from Components.config import config
		pd = config.usage.picon_dir.value
		if pd:
			dirs.append(pd.rstrip("/"))
	except Exception:
		pass
	dirs += PICON_DIRS
	idx = {}
	for d in dirs:
		try:
			for fn in os.listdir(d):
				if fn.lower().endswith(".png"):
					idx.setdefault(fn.lower(), os.path.join(d, fn))
		except Exception:
			continue
	return idx


def _picon_for(idx, ref, name):
	f = ref.rstrip(":").split(":")
	cands = []
	if len(f) >= 10:
		base = [x.upper() for x in f[:10]]
		cands.append("_".join(base))
		b1 = list(base)
		b1[2] = "1"
		cands.append("_".join(b1))
	cands.append(_snp(name))
	for c in cands:
		p = idx.get((c + ".png").lower())
		if p:
			return p
	return None


class PackJob(object):
	def __init__(self, done=None):
		self.done = done
		self.chans = []          # (key, play_ref, name)
		self.bq = []             # [{"name", "ch": [[name, key], ...]}]
		self.epg = {}
		self.i = 0
		self.t0 = time.time()
		self.timer = eTimer()
		self.timer.callback.append(self.step)

	def start(self):
		from .clients import bouquets, services
		seen = set()
		for b in bouquets():
			chs = []
			try:
				lst = services(b["ref"])
			except Exception as e:
				log("Dane dla E2Stream: bukiet %s: %s" % (b.get("name"), e))
				continue
			for s in lst:
				k = ref_key(s.get("play") or s.get("ref"))
				chs.append([s.get("name", ""), k])
				if k not in seen:
					seen.add(k)
					self.chans.append((k, s.get("play") or s.get("ref"), s.get("name", "")))
			self.bq.append({"name": b.get("name", ""), "ch": chs})
		log("Dane dla E2Stream: %d kanalow z %d bukietow - EPG, ikony, referencje" % (len(self.chans), len(self.bq)))
		self.timer.start(10, True)

	def step(self):
		"""EPG kolejnych kanalow (eEPGCache tylko na watku glownym)."""
		try:
			ec = eEPGCache.getInstance()
			part = self.chans[self.i:self.i + CHUNK]
			limit = int(time.time()) + 14 * 86400
			for k, ref, name in part:
				try:
					res = ec.lookupEvent(["IBDTSE", (ref, 0, -1, -1)]) or []
				except Exception:
					res = []
				ev = []
				for r in res:
					try:
						if not r[1] or r[1] > limit:
							continue
						ev.append([int(r[0] or 0), int(r[1]), int(r[2] or 0), r[3] or "", r[4] or "", r[5] or ""])
					except Exception:
						continue
				if ev:
					self.epg[k] = {"n": name, "e": ev}
			self.i += CHUNK
		except Exception as e:
			log("Dane dla E2Stream: EPG: %s" % e)
			self.i = len(self.chans)
		if self.i < len(self.chans):
			self.timer.start(20, True)
			return
		from twisted.internet import threads
		d = threads.deferToThread(self.write)
		d.addCallback(self.finished)
		d.addErrback(lambda f: self.finished("blad: %s" % f.value))

	def write(self):
		d = pack_dir()
		now = int(time.time())
		tmp = os.path.join(d, "epg.json.gz.tmp")
		with gzip.open(tmp, "wt", encoding="utf-8") as f:
			json.dump({"at": now, "ch": self.epg}, f, ensure_ascii=False, separators=(",", ":"))
		os.rename(tmp, os.path.join(d, "epg.json.gz"))
		events = sum(len(v["e"]) for v in self.epg.values())

		idx = _picon_index()
		tmp = os.path.join(d, "picons.zip.tmp")
		npic = 0
		missing = []
		with zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED) as z:
			for k, ref, name in self.chans:
				p = _picon_for(idx, ref, name)
				if p:
					z.write(p, k.replace(":", "_") + ".png")
					npic += 1
				else:
					missing.append(name)
		os.rename(tmp, os.path.join(d, "picons.zip"))

		# zamiany referencji: ta sama nazwa w tym samym bukiecie, inna referencja niz poprzednio
		old = _read_json(os.path.join(d, "refs.json")) or {}
		remap = {k: v for k, v in (old.get("remap") or {}).items() if now - v.get("t", 0) < KEEP_REMAP_DAYS * 86400}
		prev = {}
		for b in old.get("bouquets") or []:
			for name, k in b.get("ch") or []:
				prev[(b.get("name"), name)] = k
		cur_keys = set(k for k, _r, _n in self.chans)
		new_remaps = 0
		for b in self.bq:
			for name, k in b["ch"]:
				o = prev.get((b["name"], name))
				if o and o != k and o not in cur_keys:
					remap[o] = {"to": k, "name": name, "t": now}
					new_remaps += 1
		for o in list(remap):            # lancuchy A->B->C skracane do A->C
			seen = set()
			t = remap[o]["to"]
			while t in remap and t not in seen:
				seen.add(t)
				t = remap[t]["to"]
			remap[o]["to"] = t
		tmp = os.path.join(d, "refs.json.tmp")
		with open(tmp, "w") as f:
			json.dump({"at": now, "bouquets": self.bq, "remap": remap}, f, ensure_ascii=False)
		os.rename(tmp, os.path.join(d, "refs.json"))

		meta = {"at": now, "channels": len(self.chans), "epg_channels": len(self.epg), "events": events,
			"picons": npic, "remap": len(remap), "remap_new": new_remaps, "time": int(time.time() - self.t0)}
		with open(os.path.join(d, "pack.json"), "w") as f:
			json.dump(meta, f)
		if missing:
			log("Dane dla E2Stream: brak ikon dla %d kanalow: %s" % (len(missing), ", ".join(missing[:30])))
		return "%d kanalow, EPG %d kanalow / %d audycji, ikon %d, zamian referencji %d (nowych %d), %d s, %s" % (
			len(self.chans), len(self.epg), events, npic, len(remap), new_remaps, meta["time"], d)

	def finished(self, res):
		global _job
		_job = None
		log("Dane dla E2Stream: %s" % res)
		try:
			cfg.cli_pack_last.value = "%d|%s" % (int(time.time()), res)
			cfg.cli_pack_last.save()
			save_cfg()
		except Exception:
			pass
		if self.done:
			try:
				self.done(res)
			except Exception as e:
				log("Dane dla E2Stream: %s" % e)


def build(done=None):
	"""Buduje paczke; done(opis) po zakonczeniu. False, gdy juz trwa."""
	global _job
	if _job is not None:
		return False
	_job = PackJob(done)
	try:
		_job.start()
	except Exception as e:
		_job = None
		log("Dane dla E2Stream: start nieudany: %s" % e)
		if done:
			done("blad: %s" % e)
	return True


def running():
	return _job is not None


def last_text():
	v = cfg.cli_pack_last.value or ""
	if "|" not in v:
		return "jeszcze nie przygotowane"
	t, res = v.split("|", 1)
	try:
		return "%s: %s" % (time.strftime("%d.%m %H:%M", time.localtime(int(t))), res)
	except Exception:
		return res
