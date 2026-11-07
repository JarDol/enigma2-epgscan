# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - szukanie serwera epgpl w sieci lokalnej: Avahi/mDNS (_epgpl._tcp) i przeszukanie podsieci odbiornika.
# Serwer = adres, ktory zwraca plik zrodla epgpl.sources.xml z elementem <epgpl>.

import json
import os
import re
import socket
import struct
import subprocess
import threading
import time
import urllib.request

SERVICE = "_epgpl._tcp.local"
DEFAULT_PORT = 8088
MDNS = ("224.0.0.251", 5353)


# --------------------------------------------------------------------------
# mDNS (Avahi): zapytanie "legacy unicast" - odpowiedz wraca na nasz port, bez zajmowania 5353
# --------------------------------------------------------------------------

def _qname(name):
	out = b""
	for part in name.strip(".").split("."):
		b = part.encode("utf-8")
		out += struct.pack("B", len(b)) + b
	return out + b"\x00"


def _query(name, qtype):
	return struct.pack(">HHHHHH", 0x4550, 0, 1, 0, 0, 0) + _qname(name) + struct.pack(">HH", qtype, 0x0001)


def _read_name(data, off, depth=0):
	labels = []
	jumped = False
	end = off
	while depth < 20:
		ln = data[off]
		if ln == 0:
			off += 1
			break
		if ln & 0xC0 == 0xC0:
			ptr = ((ln & 0x3F) << 8) | data[off + 1]
			if not jumped:
				end = off + 2
			jumped = True
			off = ptr
			depth += 1
			continue
		labels.append(data[off + 1:off + 1 + ln].decode("utf-8", "replace"))
		off += 1 + ln
	return ".".join(labels), (end if jumped else off)


def _parse(data):
	"""Rekordy odpowiedzi: [(nazwa, typ, rdata_offset, rdlen)] + dane."""
	qd, an, ns, ar = struct.unpack(">HHHH", data[4:12])
	off = 12
	for _ in range(qd):
		_, off = _read_name(data, off)
		off += 4
	recs = []
	for _ in range(an + ns + ar):
		name, off = _read_name(data, off)
		typ, _cls, _ttl, rdlen = struct.unpack(">HHIH", data[off:off + 10])
		off += 10
		recs.append((name.lower(), typ, off, rdlen))
		off += rdlen
	return recs


def mdns_browse(timeout=3.0):
	"""[(ip, port, nazwa)] serwerow ogloszonych przez Avahi jako _epgpl._tcp."""
	found = {}
	try:
		s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
		s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
		s.bind(("", 0))
		s.settimeout(0.5)
	except Exception:
		return []
	q = _query(SERVICE, 12)
	t_end = time.time() + timeout
	sent = 0
	while time.time() < t_end:
		if sent < 3 and time.time() > t_end - timeout + sent * 1.0:
			try:
				s.sendto(q, MDNS)
			except Exception:
				pass
			sent += 1
		try:
			data, addr = s.recvfrom(9000)
		except socket.timeout:
			continue
		except Exception:
			break
		try:
			recs = _parse(data)
		except Exception:
			continue
		inst, srv, a = [], {}, {}
		for name, typ, off, ln in recs:
			if typ == 12 and name == SERVICE:
				inst.append(_read_name(data, off)[0].lower())
			elif typ == 33:
				_p, _w, port = struct.unpack(">HHH", data[off:off + 6])
				srv[name] = (port, _read_name(data, off + 6)[0].lower())
			elif typ == 1 and ln == 4:
				a[name] = socket.inet_ntoa(data[off:off + 4])
		for i in inst or list(srv):
			port, target = srv.get(i, (DEFAULT_PORT, ""))
			ip = a.get(target) or addr[0]
			label = i.split("._epgpl")[0] if "._epgpl" in i else i
			found[(ip, port)] = label
	try:
		s.close()
	except Exception:
		pass
	return [(ip, port, n) for (ip, port), n in found.items()]


# --------------------------------------------------------------------------
# podsiec odbiornika
# --------------------------------------------------------------------------

def own_networks():
	"""[(ip, prefix)] interfejsow IPv4 odbiornika (bez lo)."""
	out = []
	try:
		txt = subprocess.check_output(["ip", "-o", "-4", "addr", "show"], universal_newlines=True, timeout=5)
		for m in re.finditer(r"^\d+:\s+(\S+)\s+inet\s+(\d+\.\d+\.\d+\.\d+)/(\d+)", txt, re.M):
			if m.group(1) != "lo":
				out.append((m.group(2), int(m.group(3))))
	except Exception:
		pass
	if not out:
		try:
			s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
			s.connect(("192.0.2.1", 9))         # nic nie jest wysylane - tylko wybor interfejsu
			out.append((s.getsockname()[0], 24))
			s.close()
		except Exception:
			pass
	return out


def hosts_of(ip, prefix):
	"""Adresy do sprawdzenia: cala podsiec, ale najwyzej /24 wokol odbiornika (wieksze sieci - za dlugo)."""
	n = struct.unpack(">I", socket.inet_aton(ip))[0]
	prefix = max(prefix, 24)
	mask = (0xFFFFFFFF << (32 - prefix)) & 0xFFFFFFFF
	net = n & mask
	return [socket.inet_ntoa(struct.pack(">I", h)) for h in range(net + 1, (net | ~mask & 0xFFFFFFFF)) if h != n]


def port_open(ip, port, timeout=0.6):
	try:
		c = socket.create_connection((ip, port), timeout)
		c.close()
		return True
	except Exception:
		return False


# --------------------------------------------------------------------------
# sprawdzenie i opis serwera
# --------------------------------------------------------------------------

def check_server(ip, port, timeout=4):
	"""dict z opisem serwera epgpl albo None."""
	base = "http://%s:%d/" % (ip, port)
	try:
		req = urllib.request.Request(base + "epgpl.sources.xml", headers={"User-Agent": "EPGScan"})
		body = urllib.request.urlopen(req, timeout=timeout).read(65536).decode("utf-8", "replace")
	except Exception:
		return None
	if "<sources" not in body or "<epgpl>" not in body:
		return None
	info = {"ip": ip, "port": port, "url": base + "epgpl.sources.xml", "base": base, "guide": 0, "running": False,
		"stale_ip": False}
	m = re.search(r"<epgpl>\s*http://([^:/<]+)", body)
	if m and m.group(1) != ip:
		info["stale_ip"] = True                  # plik zrodla podaje inny adres (serwer zmienil IP)
	try:
		req = urllib.request.Request(base + "api/stan", headers={"User-Agent": "EPGScan"})
		st = json.loads(urllib.request.urlopen(req, timeout=timeout).read().decode("utf-8", "replace"))
		info["guide"] = int(st.get("guide") or 0)
		info["running"] = bool(st.get("running"))
	except Exception:
		pass
	return info


class Finder(object):
	"""Szukanie w watku: Avahi, potem podsiec. Postep w self.text, wynik w self.found (lista dict)."""

	def __init__(self, ports=(DEFAULT_PORT,), threads=48):
		self.ports = [p for i, p in enumerate(ports) if p and p not in ports[:i]] or [DEFAULT_PORT]
		self.threads = threads
		self.found = []
		self.text = "Szukanie serwera epgpl..."
		self.done = False
		self.cancel = False
		self.lock = threading.Lock()

	def add(self, info, via):
		with self.lock:
			for f in self.found:
				if f["ip"] == info["ip"] and f["port"] == info["port"]:
					if via not in f["via"]:
						f["via"].append(via)
					return
			info["via"] = [via]
			self.found.append(info)

	def run(self):
		try:
			self.text = "Avahi (mDNS): pytam o _epgpl._tcp..."
			for ip, port, name in mdns_browse():
				info = check_server(ip, port)
				if info:
					info["name"] = name
					self.add(info, "Avahi")
			nets = own_networks()
			todo = []
			for ip, prefix in nets:
				for h in hosts_of(ip, prefix):
					for p in self.ports:
						todo.append((h, p))
			total = len(todo)
			pos = [0]
			hits = []

			def worker():
				while not self.cancel:
					with self.lock:
						if pos[0] >= total:
							return
						h, p = todo[pos[0]]
						pos[0] += 1
					if port_open(h, p):
						hits.append((h, p))
			ths = [threading.Thread(target=worker) for _ in range(min(self.threads, max(1, total)))]
			for t in ths:
				t.daemon = True
				t.start()
			net = ", ".join("%s/%d" % x for x in nets) or "?"
			while any(t.is_alive() for t in ths):
				self.text = "Podsiec %s: sprawdzono %d z %d adresow, znaleziono %d" % (net, pos[0], total, len(self.found))
				time.sleep(0.3)
			for h, p in hits:
				if self.cancel:
					break
				info = check_server(h, p)
				if info:
					self.add(info, "podsiec")
			self.text = "Gotowe: znaleziono serwerow epgpl: %d" % len(self.found)
		except Exception as e:
			self.text = "Blad szukania: %s" % e
		self.done = True

	def start(self):
		th = threading.Thread(target=self.run)
		th.daemon = True
		th.start()


def label(info):
	g = info.get("guide") or 0
	age = ("plik programu %s" % time.strftime("%d.%m %H:%M", time.localtime(g))) if g else "brak pliku programu"
	extra = []
	if info.get("running"):
		extra.append("pobiera teraz")
	if info.get("stale_ip"):
		extra.append("plik zrodla z innym IP - uruchom sources.sh na serwerze")
	return "%s:%d  (%s; %s%s)" % (info["ip"], info["port"], ", ".join(info.get("via", [])), age,
		("; " + "; ".join(extra)) if extra else "")
