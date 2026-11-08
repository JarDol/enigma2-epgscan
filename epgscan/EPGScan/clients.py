# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - serwer dla klientow streamingu (aplikacja E2Stream na Android TV / tablet / telefon)
#
# API (HTTP, siec lokalna, domyslnie port 8770; opcjonalny token: naglowek X-E2S-Token albo ?token=):
#   GET  /api/info                      - wersja wtyczki, wersja API, model dekodera
#   GET  /api/bouquets                  - bukiety TV [{name, ref}]
#   GET  /api/services?bRef=<ref>       - kanaly bukietu z rozwiazanymi alternatywami i PID-ami z lamedb
#   GET  /api/sync/state?id=<id>        - dane wspolne + plik odbiornika
#   POST /api/sync/push?id=<id>         - zmiany odbiornika; serwer scala (nowszy wpis wygrywa) i dopisuje
#                                         brakujace klucze do czesci wspolnej (PMT: nowszy wygrywa)
#   GET  /api/sync/devices              - profile wszystkich odbiornikow (przenoszenie ustawien)
#
# Dane: /etc/enigma2/epgscan/clients/common.json, device-<id>.json

import json
import os
import re
import time

from enigma import eServiceReference, eServiceCenter

from .common import cfg, log, VERSION, E2

API = 1
DIR = os.path.join(E2, "epgscan", "clients")
COMMON = os.path.join(DIR, "common.json")

_server = None      # port nasluchu (twisted)
_lamedb = {"mtime": None, "pids": {}}

# indeksy pamieci podrecznej PID-ow w lamedb (eDVBService::cacheID)
C_VPID, C_APID, C_TPID, C_PCR, C_AC3, C_VTYPE = 0, 1, 2, 3, 4, 5
C_DDP, C_AAC, C_LATM = 0x0c, 0x0d, 0x0f


# ---------------------------------------------------------------- pomocnicze

def _now_ms():
	return int(time.time() * 1000)


def _load(path, default=None):
	try:
		with open(path, encoding="utf-8") as f:
			return json.load(f)
	except Exception:
		return default


def _save(path, data):
	try:
		if not os.path.isdir(DIR):
			os.makedirs(DIR)
		tmp = path + ".tmp"
		with open(tmp, "w", encoding="utf-8") as f:
			json.dump(data, f, ensure_ascii=False, indent=1)
		os.rename(tmp, path)
	except Exception as e:
		log("Klienci: zapis %s nieudany: %s" % (path, e))


def _safe_id(s):
	return re.sub(r"[^A-Za-z0-9_-]", "_", s or "")[:64]


def _dev_path(dev_id):
	return os.path.join(DIR, "device-%s.json" % _safe_id(dev_id))


def svc_key(sid, tsid, onid, ns):
	return "%x:%x:%x:%x" % (sid, tsid, onid, ns)


def ref_key(ref):
	"""Klucz SID:TSID:ONID:NS z referencji 1:0:T:SID:TSID:ONID:NS:..."""
	try:
		p = ref.split(":")
		return svc_key(int(p[3], 16), int(p[4], 16), int(p[5], 16), int(p[6], 16))
	except Exception:
		return None


# ---------------------------------------------------------------- PID-y z lamedb

def lamedb_pids():
	"""{svc_key: {cache_id: wartosc}} - zapamietane przez Enigma2 PID-y kazdego serwisu."""
	path = None
	for p in ("lamedb5", "lamedb"):
		if os.path.exists(os.path.join(E2, p)):
			path = os.path.join(E2, p)
			break
	if path is None:
		return {}
	try:
		mt = os.path.getmtime(path)
	except Exception:
		mt = None
	if mt == _lamedb["mtime"]:
		return _lamedb["pids"]
	pids = {}

	def parse_cache(sid, ns, tsid, onid, extra):
		cache = {}
		for part in extra.split(","):
			if part.startswith("c:") and len(part) >= 8:
				try:
					cache[int(part[2:4], 16)] = int(part[4:8], 16)
				except Exception:
					pass
		if cache:
			try:
				pids[svc_key(int(sid, 16), int(tsid, 16), int(onid, 16), int(ns, 16))] = cache
			except Exception:
				pass

	try:
		with open(path, encoding="utf-8", errors="ignore") as f:
			lines = f.read().splitlines()
		if path.endswith("lamedb5"):
			for line in lines:
				if line.startswith("s:"):
					head, _, rest = line[2:].partition(",")
					p = head.split(":")
					if len(p) >= 5:
						# rest: "nazwa",p:...,c:...
						extra = rest.rsplit('"', 1)[-1] if '"' in rest else rest
						parse_cache(p[0], p[1], p[2], p[3], extra)
		else:
			i = lines.index("services") + 1
			while i + 2 < len(lines) and lines[i] != "end":
				p = lines[i].split(":")
				if len(p) >= 5:
					parse_cache(p[0], p[1], p[2], p[3], lines[i + 2])
				i += 3
	except Exception as e:
		log("Klienci: blad odczytu %s: %s" % (path, e))
	_lamedb["mtime"] = mt
	_lamedb["pids"] = pids
	return pids


def pid_info(ref):
	"""PID-y serwisu w postaci dla klienta (lub None)."""
	k = ref_key(ref)
	c = lamedb_pids().get(k) if k else None
	if not c:
		return None
	out = {}
	if C_VPID in c:
		out["video"] = c[C_VPID]
		out["vtype"] = c.get(C_VTYPE, 0)
	if C_PCR in c:
		out["pcr"] = c[C_PCR]
	audio = []
	if C_APID in c:
		audio.append({"pid": c[C_APID], "type": "mpeg"})
	if C_AC3 in c:
		audio.append({"pid": c[C_AC3], "type": "ac3"})
	if C_DDP in c:
		audio.append({"pid": c[C_DDP], "type": "eac3"})
	if C_AAC in c:
		audio.append({"pid": c[C_AAC], "type": "aac"})
	if C_LATM in c:
		audio.append({"pid": c[C_LATM], "type": "latm"})
	if audio:
		out["audio"] = audio
	if C_TPID in c:
		out["teletext"] = c[C_TPID]
	return out or None


# ---------------------------------------------------------------- bukiety i kanaly

def _list(ref_str):
	ref = eServiceReference(ref_str)
	lst = eServiceCenter.getInstance().list(ref)
	return lst.getContent("SN", True) if lst else []


def bouquets():
	out = []
	for ref, name in _list('1:7:1:0:0:0:0:0:0:0:FROM BOUQUET "bouquets.tv" ORDER BY bouquet'):
		out.append({"name": name, "ref": ref})
	return out


def services(bref):
	out = []
	n = 0
	for ref, name in _list(bref):
		try:
			flags = int(ref.split(":")[1])
		except Exception:
			flags = 0
		if flags & 64 or (flags & 1 and not flags & 128):   # znacznik / podkatalog
			continue
		n += 1
		play = ref
		if flags & 128:   # alternatywy - pierwsza grajaca
			for r2, _n2 in _list(ref):
				try:
					f2 = int(r2.split(":")[1])
				except Exception:
					f2 = 0
				if not f2 & (64 | 128 | 1):
					play = r2
					break
		item = {"num": n, "name": name, "ref": ref, "play": play}
		pi = pid_info(play)
		if pi:
			item["pids"] = pi
		out.append(item)
	return out


# ---------------------------------------------------------------- synchronizacja

def _is_pmt(k):
	return k.startswith("pmts_")


def state(dev_id):
	return {"common": _load(COMMON, {"data": {}}), "device": _load(_dev_path(dev_id))}


def push(dev_id, body):
	"""Scala plik odbiornika (nowszy wpis wygrywa) i uzupelnia czesc wspolna."""
	dev = body.get("device") or {}
	data = (dev.get("data") or {})
	path = _dev_path(dev_id)
	cur = _load(path, {}) or {}
	cdata = cur.get("data") or {}
	for k, e in data.items():
		if not isinstance(e, dict):
			continue
		old = cdata.get(k)
		if old is None or int(e.get("t", 0)) >= int(old.get("t", 0)):
			cdata[k] = e
	cur["data"] = cdata
	cur["device"] = dev.get("device") or cur.get("device") or {}
	cur["device"]["seen"] = _now_ms()
	_save(path, cur)

	common = _load(COMMON, {}) or {}
	cd = common.get("data") or {}
	changed = False
	for k, e in list(data.items()) + list((body.get("common_add") or {}).items()):
		if not isinstance(e, dict):
			continue
		old = cd.get(k)
		if _is_pmt(k):
			if old is None or int(e.get("t", 0)) > int(old.get("t", 0)):
				cd[k] = e
				changed = True
		elif old is None:
			cd[k] = e      # domyslne dla nowych odbiornikow
			changed = True
	if changed or "data" not in common:
		common["data"] = cd
		common["updated"] = _now_ms()
		_save(COMMON, common)
	return {"common": common, "device": cur}


def devices():
	out = []
	try:
		for n in sorted(os.listdir(DIR)):
			if n.startswith("device-") and n.endswith(".json"):
				d = _load(os.path.join(DIR, n))
				if d:
					d["file"] = n
					out.append(d)
	except Exception:
		pass
	return out


# ---------------------------------------------------------------- HTTP

def _resource():
	from twisted.web import resource

	class Api(resource.Resource):
		isLeaf = True

		def _json(self, request, obj, code=200):
			request.setResponseCode(code)
			request.setHeader(b"Content-Type", b"application/json; charset=utf-8")
			request.setHeader(b"Access-Control-Allow-Origin", b"*")
			return json.dumps(obj, ensure_ascii=False).encode("utf-8")

		def _arg(self, request, name):
			v = request.args.get(name.encode())
			return v[0].decode("utf-8", "ignore") if v else ""

		def _auth(self, request):
			tok = cfg.cli_token.value.strip()
			if not tok:
				return True
			h = request.getHeader(b"X-E2S-Token") or request.getHeader("X-E2S-Token")
			if isinstance(h, bytes):
				h = h.decode("utf-8", "ignore")
			return h == tok or self._arg(request, "token") == tok

		def render(self, request):
			path = request.path.decode("utf-8", "ignore").rstrip("/")
			try:
				if not self._auth(request):
					return self._json(request, {"error": "token"}, 401)
				if path == "/api/info":
					try:
						from Components.SystemInfo import BoxInfo
						model = BoxInfo.getItem("displaymodel") or BoxInfo.getItem("model")
					except Exception:
						model = ""
					return self._json(request, {"name": "EPG Scan", "version": VERSION, "api": API,
						"features": ["services", "pids", "sync", "pack"], "model": model, "time": int(time.time())})
				if path == "/api/bouquets":
					return self._json(request, {"bouquets": bouquets()})
				if path == "/api/services":
					return self._json(request, {"services": services(self._arg(request, "bRef"))})
				if path == "/api/sync/state":
					return self._json(request, state(self._arg(request, "id")))
				if path == "/api/sync/push" and request.method == b"POST":
					body = json.loads(request.content.read().decode("utf-8") or "{}")
					return self._json(request, push(self._arg(request, "id"), body))
				if path == "/api/sync/devices":
					return self._json(request, {"devices": devices()})
				if path == "/api/pack/info":
					from .clientpack import info as pack_info, running as pack_running
					return self._json(request, dict(pack_info(), building=pack_running()))
				if path.startswith("/api/pack/"):
					from .clientpack import file_path
					fp = file_path(path[len("/api/pack/"):])
					if not fp:
						return self._json(request, {"error": "brak paczki"}, 404)
					with open(fp, "rb") as f:
						data = f.read()
					request.setHeader(b"Content-Type", b"application/octet-stream")
					request.setHeader(b"Content-Length", str(len(data)).encode())
					return data
				return self._json(request, {"error": "not found"}, 404)
			except Exception as e:
				log("Klienci: blad %s: %s" % (path, e))
				return self._json(request, {"error": str(e)}, 500)

	return Api()


def _listen():
	global _server
	if not cfg.cli_api.value:
		return
	try:
		from twisted.internet import reactor
		from twisted.web.server import Site
		_server = reactor.listenTCP(int(cfg.cli_port.value), Site(_resource()))
		log("Serwer dla klientow (E2Stream): port %d" % int(cfg.cli_port.value))
	except Exception as e:
		_server = None
		log("Serwer dla klientow: start nieudany: %s" % e)


def start():
	"""Uruchamia serwer (przy starcie Enigma2 i po zmianie ustawien) - po zwolnieniu poprzedniego portu."""
	d = stop()
	if d is not None:
		d.addBoth(lambda _r: _listen())
	else:
		_listen()


def stop():
	global _server
	d = None
	if _server is not None:
		try:
			d = _server.stopListening()
		except Exception:
			d = None
		_server = None
	return d


def summary():
	n = len([x for x in devices()])
	c = (_load(COMMON, {}) or {}).get("data") or {}
	pmt = len([k for k in c if _is_pmt(k)])
	return "port %d, odbiornikow: %d, PMT kanalow: %d" % (int(cfg.cli_port.value), n, pmt) if cfg.cli_api.value \
		else "wylaczony"
