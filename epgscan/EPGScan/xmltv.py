# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - wbudowany import XMLTV (zamiast wtyczki EPG Import)
#
# Zrodla: pliki *.sources.xml z /etc/epgimport (pakiet enigma2-plugin-extensions-epgimport-sources),
# awaryjnie wbudowana lista. Import po skanie z satelity:
#  - kanaly bez EPG z satelity (rowniez IPTV z bukietow) dostaja caly program z XMLTV,
#  - kanaly z EPG z satelity - tylko audycje zaczynajace sie po ostatniej audycji z satelity.
# Dzieki temu w chwili importu nic sie nie naklada (brak dubli).

import calendar
import gzip
import json
import os
import re
import shutil
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

from enigma import eTimer, eEPGCache

from .common import cfg, log, E2

SOURCES_DIR = "/etc/epgimport"
TMP = "/tmp/epgscan_xmltv"
EPGIMPORT_CONF = "/etc/enigma2/epgimport.conf"
STATE_FILE = "/etc/enigma2/epgscan_xmltv.json"   # stan zrodel z ostatniego przebiegu
CACHE_DIR = "/etc/enigma2/epgscan_sources"      # kopie list zrodel:
PKG_DIR = CACHE_DIR + "/pakiet"                 #  - pliki pakietu epgimport-sources (lustro pakietu, zostaje po odinstalowaniu)
OWN_DIR = CACHE_DIR + "/wlasne"                 #  - wlasne listy (spoza pakietu i pobrane z adresu) - usuwane tylko recznie
OPKG_INFO = ("/var/lib/opkg/info", "/usr/lib/opkg/info")
SOURCES_PKG = "enigma2-plugin-extensions-epgimport-sources"
RECHECK_BAD = 24 * 3600                          # zrodla nieaktualne/niedostepne - sprawdzaj raz na dobe


def _source_files(d):
	try:
		return sorted(f for f in os.listdir(d) if f.endswith(".sources.xml"))
	except Exception:
		return []


def sources_present():
	return bool(_source_files(SOURCES_DIR))


def cache_present():
	return bool(_source_files(PKG_DIR) or _source_files(OWN_DIR))


def _cache_files(d):
	"""Pliki do kopii: listy zrodel i lokalne mapy kanalow."""
	try:
		return sorted(f for f in os.listdir(d) if f.endswith(".sources.xml") or
			re.search(r"\.channels\.xml(\.gz|\.xz)?$", f))
	except Exception:
		return []


def pkg_files():
	"""Nazwy plikow z /etc/epgimport nalezacych do pakietu zrodel; None, gdy pakiet nie jest zainstalowany."""
	for d in OPKG_INFO:
		p = os.path.join(d, SOURCES_PKG + ".list")
		if os.path.exists(p):
			try:
				with open(p) as f:
					return set(os.path.basename(x.strip().split("\t")[0]) for x in f
						if x.strip().startswith(SOURCES_DIR + "/"))
			except Exception:
				return None
	return None


def _copy_if_changed(src, dst):
	if not os.path.exists(dst) or os.path.getmtime(src) != os.path.getmtime(dst) \
			or os.path.getsize(src) != os.path.getsize(dst):
		shutil.copy2(src, dst)
		return True
	return False


def _cache_sources():
	"""Aktualizacja obu kopii.
	Pakiet zainstalowany: kopia pakietu = dokladnie pliki pakietu (nowe/zmienione kopiowane, usuniete z pakietu znikaja).
	Pakiet odinstalowany: kopia pakietu zostaje bez zmian.
	Pliki spoza pakietu w /etc/epgimport trafiaja do kopii wlasnej; z niej nic nie znika samo."""
	try:
		for d in (PKG_DIR, OWN_DIR):
			if not os.path.isdir(d):
				os.makedirs(d)
		owned = pkg_files()
		live = _cache_files(SOURCES_DIR)
		# przeniesienie kopii z 1.8.1 (plaski katalog)
		for f in _cache_files(CACHE_DIR):
			src = os.path.join(CACHE_DIR, f)
			if owned is not None and f not in owned and f in live:
				dst = os.path.join(OWN_DIR, f)
			elif owned is None and f.startswith("epgpl"):
				dst = os.path.join(OWN_DIR, f)
			else:
				dst = os.path.join(PKG_DIR, f)
			if not os.path.exists(dst):
				shutil.move(src, dst)
			else:
				os.remove(src)
		for f in live:
			src = os.path.join(SOURCES_DIR, f)
			if owned is not None and f in owned:
				_copy_if_changed(src, os.path.join(PKG_DIR, f))
			else:
				_copy_if_changed(src, os.path.join(OWN_DIR, f))
		if owned is not None:
			for f in _cache_files(PKG_DIR):
				if f not in owned:
					os.remove(os.path.join(PKG_DIR, f))
					log("XMLTV: %s nie nalezy juz do pakietu zrodel - usunieto z kopii pakietu" % f)
	except Exception as e:
		log("XMLTV: kopia list zrodel nieudana: %s" % e)


from_cache = []      # listy zrodel wczytane z kopii przy ostatnim load_sources()


def source_files():
	"""[(katalog, plik, pochodzenie)] - najpierw wlasne listy, potem listy pakietu epgimport-sources.
	Kolejnosc = pierwszenstwo przy imporcie (kanal dostaje dane z pierwszego zrodla, ktore je ma).
	Plik z /etc/epgimport ma pierwszenstwo przed kopia o tej samej nazwie.
	pochodzenie: 'wlasne' (moze je usunac uzytkownik) / 'pakiet'."""
	_cache_sources()
	owned = pkg_files() or set()
	live = _source_files(SOURCES_DIR)

	def is_pkg(f):
		return f in owned or (os.path.exists(os.path.join(PKG_DIR, f)) and not os.path.exists(os.path.join(OWN_DIR, f)))
	out, seen = [], set()
	groups = (
		("wlasne", [(SOURCES_DIR, f) for f in live if not is_pkg(f)] + [(OWN_DIR, f) for f in _source_files(OWN_DIR)]),
		("pakiet", [(SOURCES_DIR, f) for f in live if is_pkg(f)] + [(PKG_DIR, f) for f in _source_files(PKG_DIR)]),
	)
	for org, files in groups:
		for d, f in files:
			if f not in seen:
				out.append((d, f, org))
				seen.add(f)
	return out


def delete_own(fn):
	"""Usuwa wlasna liste zrodel (kopia wlasna i /etc/epgimport). Zwraca opisy usunietych zrodel."""
	if fn in (pkg_files() or set()):
		return None
	descs = []
	for d in (OWN_DIR, SOURCES_DIR):
		p = os.path.join(d, fn)
		if os.path.exists(p):
			try:
				root = ET.parse(p).getroot()
				descs += [x.findtext("description", "").strip() for x in root.iter("source")]
			except Exception:
				pass
			os.remove(p)
	m = own_meta()
	e = m.pop(fn, None)
	if e is not None:
		_save_meta(m)
		if e.get("url"):
			remember_url(e["url"], fn)     # adres zostaje w historii - mozna z niego pobrac liste ponownie
	log("XMLTV: usunieto wlasna liste zrodel %s" % fn)
	return [x for x in set(descs) if x]


OWN_META = "adresy.json"     # w OWN_DIR: {plik: {"url", "updated", "checked", "status"}}


def fmt_age(ts):
	if not ts:
		return "-"
	return time.strftime("%d.%m %H:%M", time.localtime(ts))


def own_meta():
	try:
		with open(os.path.join(OWN_DIR, OWN_META)) as f:
			return json.load(f)
	except Exception:
		return {}


def _save_meta(m):
	try:
		if not os.path.isdir(OWN_DIR):
			os.makedirs(OWN_DIR)
		tmp = os.path.join(OWN_DIR, OWN_META + ".tmp")
		with open(tmp, "w") as f:
			json.dump(m, f, indent=1, sort_keys=True)
		os.rename(tmp, os.path.join(OWN_DIR, OWN_META))
	except Exception as e:
		log("XMLTV: zapis adresow list nieudany: %s" % e)


HISTORY = "historia.json"   # w OWN_DIR: adresy, z ktorych pobierano listy (zostaja po usunieciu listy)


def url_history():
	"""[{"url", "file", "used"}] - od ostatnio uzytego."""
	try:
		with open(os.path.join(OWN_DIR, HISTORY)) as f:
			h = json.load(f)
	except Exception:
		h = []
	return sorted([x for x in h if x.get("url")], key=lambda x: -x.get("used", 0))


def _save_history(h):
	try:
		if not os.path.isdir(OWN_DIR):
			os.makedirs(OWN_DIR)
		tmp = os.path.join(OWN_DIR, HISTORY + ".tmp")
		with open(tmp, "w") as f:
			json.dump(h, f, indent=1)
		os.rename(tmp, os.path.join(OWN_DIR, HISTORY))
	except Exception as e:
		log("XMLTV: zapis historii adresow nieudany: %s" % e)


def remember_url(url, name):
	h = [x for x in url_history() if x["url"] != url]
	h.append({"url": url, "file": name, "used": int(time.time())})
	_save_history(h)


def forget_url(url=None):
	"""Usuwa adres z historii (None = wszystkie). Zwraca liczbe usunietych."""
	h = url_history()
	keep = [] if url is None else [x for x in h if x["url"] != url]
	_save_history(keep)
	return len(h) - len(keep)


def _fetch_list(url):
	"""(dane, [opisy zrodel]) albo wyjatek z czytelnym opisem."""
	import urllib.request
	req = urllib.request.Request(url, headers={"User-Agent": "EPGScan"})
	with urllib.request.urlopen(req, timeout=30) as r:
		data = r.read(2 * 1024 * 1024)
	root = ET.fromstring(data)
	srcs = [x.findtext("description", "").strip() for x in root.iter("source") if x.findtext("url")]
	if root.tag != "sources" or not srcs:
		raise ValueError("pod tym adresem nie ma listy zrodel w formacie EPG Import (*.sources.xml)")
	return data, srcs


def list_name(url):
	name = re.sub(r"[^A-Za-z0-9._-]", "_", url.split("?")[0].rstrip("/").split("/")[-1] or "zrodla")
	if not name.endswith(".sources.xml"):
		name = re.sub(r"\.xml$", "", name) + ".sources.xml"
	if name in (pkg_files() or set()):
		name = "wlasne-" + name
	return name


def download_source(url, name=None):
	"""Pobiera liste zrodel spod adresu do kopii wlasnej i zapamietuje adres.
	name=None: nazwa pliku z adresu (istniejaca lista o tej nazwie jest aktualizowana).
	Zwraca (ok, komunikat)."""
	name = name or list_name(url)
	try:
		data, srcs = _fetch_list(url)
	except Exception as e:
		return False, "Nie udalo sie pobrac listy zrodel:\n%s" % e
	if not os.path.isdir(OWN_DIR):
		os.makedirs(OWN_DIR)
	replaced = ""
	if b"<epgpl>" in data:
		# serwer epgpl moze byc tylko jeden: zawsze ten sam plik - nowy serwer zastepuje poprzedni
		name = EPGPL_FILE
		try:
			old_b = re.search(rb"<epgpl>\s*([^<\s]+)", open(os.path.join(OWN_DIR, name), "rb").read())
			new_b = re.search(rb"<epgpl>\s*([^<\s]+)", data)
			if old_b and new_b and old_b.group(1) != new_b.group(1):
				replaced = old_b.group(1).decode("utf-8", "replace")
		except Exception:
			pass
		for other in list(own_meta()):
			if other != name and os.path.exists(os.path.join(OWN_DIR, other)):
				try:
					if b"<epgpl>" in open(os.path.join(OWN_DIR, other), "rb").read():
						os.remove(os.path.join(OWN_DIR, other))        # stara kopia serwera pod inna nazwa
				except Exception:
					pass
	path = os.path.join(OWN_DIR, name)
	old = None
	if os.path.exists(path):
		with open(path, "rb") as f:
			old = f.read()
	if old != data:
		with open(path, "wb") as f:
			f.write(data)
	m = own_meta()
	now = int(time.time())
	e = m.get(name, {})
	e.update({"url": url, "checked": now, "status": "ok"})
	if old != data:
		e["updated"] = now
	elif not e.get("updated"):
		e["updated"] = int(os.path.getmtime(path))
	m[name] = e
	_save_meta(m)
	remember_url(url, name)
	if old is None:
		what = "Dodano"
	elif old != data:
		what = "Zaktualizowano (lista sie zmienila)"
	else:
		what = "Bez zmian - lista jest aktualna"
	if replaced:
		what = "Serwer epgpl zastapiony (poprzedni: %s)" % replaced
	log("XMLTV: %s - %s, adres %s, zrodel %d" % (name, what.lower(), url, len(srcs)))
	return True, "%s: %s\nzrodel: %d\n%s" % (what, name, len(srcs), "\n".join(srcs[:8]))


EPGPL_FILE = "epgpl.sources.xml"


def epgpl_current():
	"""Adres (url listy) obecnie skonfigurowanego serwera epgpl albo ""."""
	try:
		m = re.search(r"<epgpl>\s*([^<\s]+)", open(os.path.join(OWN_DIR, EPGPL_FILE), encoding="utf-8",
			errors="ignore").read())
		return m.group(1) if m else ""
	except Exception:
		return ""


def set_list_url(name, url):
	"""Zapamietuje adres dla istniejacej wlasnej listy i od razu ja z niego aktualizuje."""
	return download_source(url, name)


def refresh_own(names=None):
	"""Aktualizuje wlasne listy z zapamietanych adresow (wszystkie albo podane). Zwraca [(plik, opis wyniku)]."""
	m = own_meta()
	out = []
	for name, e in sorted(m.items()):
		if names and name not in names:
			continue
		if not e.get("url") or not os.path.exists(os.path.join(OWN_DIR, name)):
			continue
		ok, msg = download_source(e["url"], name)
		if not ok:
			m = own_meta()
			m.setdefault(name, {}).update({"checked": int(time.time()), "status": "blad"})
			_save_meta(m)
			log("XMLTV: aktualizacja listy %s z %s nieudana - zostaje poprzednia" % (name, e["url"]))
		out.append((name, msg.split("\n")[0] if ok else "blad - zostaje poprzednia"))
	return out


_installer = None


def install_sources(callback):
	"""Instaluje pakiet list zrodel przez opkg (w tle) i sprawdza wynik. callback(ok, komunikat)."""
	global _installer
	from enigma import eConsoleAppContainer
	out = []
	c = eConsoleAppContainer()

	def data(d):
		try:
			out.append(d.decode("utf-8", "ignore") if isinstance(d, bytes) else d)
		except Exception:
			pass

	def closed(ret):
		global _installer
		_installer = None
		files = _source_files(SOURCES_DIR)
		n = 0
		if files:
			srcs, maps = load_sources()
			n = len(srcs)
		if files and n:
			msg = "Zainstalowano pakiet zrodel XMLTV: %d plikow, %d zrodel" % (len(files), n)
			log(msg)
			callback(True, msg)
		else:
			tail = " ".join("".join(out).split())[-200:]
			msg = "Instalacja pakietu zrodel XMLTV nieudana (kod %s): %s" % (ret, tail or "brak komunikatu")
			log(msg)
			callback(False, msg)

	c.dataAvail.append(data)
	c.appClosed.append(closed)
	_installer = (c, data, closed)
	log("XMLTV: instalacja pakietu %s" % SOURCES_PKG)
	if c.execute("opkg update >/dev/null 2>&1; opkg install %s 2>&1" % SOURCES_PKG):
		_installer = None
		callback(False, "Nie mozna uruchomic opkg")


def load_state():
	try:
		with open(STATE_FILE) as f:
			return json.load(f)
	except Exception:
		return {}


def save_state(st):
	try:
		with open(STATE_FILE, "w") as f:
			json.dump(st, f)
	except Exception:
		pass


def state_label(desc, st=None):
	st = st if st is not None else load_state()
	x = st.get(desc)
	if not x:
		return ""
	when = time.strftime("%d.%m %H:%M", time.localtime(x.get("time", 0)))
	s = x.get("state")
	if s == "ok":
		return "OK, dane do %s (%s)" % (x.get("last", "?"), when)
	if s == "stale":
		return "NIEAKTUALNE, dane do %s (%s)" % (x.get("last", "?"), when)
	if s == "down":
		return "NIEDOSTEPNE (%s)" % when
	if s == "corrupt":
		return "USZKODZONY PLIK (%s)" % when
	return ""

# awaryjna lista (gdy brak /etc/epgimport): Azman Polska
BUILTIN = {
	"mappings": {"azman.channels.xml.xz": [
		"http://www.xmltvepg.nl/azman.channels.xml.xz",
		"http://rytecepg.wanwizard.eu/azman.channels.xml.xz",
		"http://epg.vuplus-community.net/azman.channels.xml.xz",
		"http://www.topolowa4.pl/azman.channels.xml.xz"]},
	"sources": [
		("[azman] Poland XMLTV", "Polska - Podstawowy", "azman.channels.xml.xz", "azman_basic.xml.xz"),
		("[azman] Poland XMLTV", "Polska - Dodatkowy", "azman.channels.xml.xz", "azman_misc.xml.xz"),
		("[azman] Poland XMLTV", "Polska - IPTV", "azman.channels.xml.xz", "azman_iptv.xml.xz"),
	],
	"mirrors": ["http://www.xmltvepg.nl/", "http://rytecepg.wanwizard.eu/",
		"http://epg.vuplus-community.net/", "http://www.topolowa4.pl/"],
}


class Source(object):
	def __init__(self, cat, desc, channels, urls):
		self.cat = cat
		self.desc = desc
		self.channels = channels      # nazwa mapy albo adres/sciezka
		self.urls = urls
		self.file = ""
		self.origin = ""              # "pakiet" / "wlasne"
		self.report = ""              # adres raportu zrodla (<report> - np. epgpl), opcjonalny
		self.epgpl = ""               # adres serwera epgpl (<epgpl>) - okno "Dopasowanie kanalow"


def load_sources():
	"""(sources, mappings) ze wszystkich *.sources.xml; mappings: nazwa -> [adresy]."""
	global from_cache
	sources = []
	mappings = {}
	files = source_files()
	from_cache = ["%s (%s)" % (f, "kopia pakietu" if org == "pakiet" else "wlasne") for d, f, org in files if d != SOURCES_DIR]
	for base, fn, org in files:
		try:
			root = ET.parse(os.path.join(base, fn)).getroot()
		except Exception as e:
			log("XMLTV: blad odczytu %s: %s" % (fn, e))
			continue
		for ch in root.iter("channel"):
			name = ch.get("name")
			if name:
				mappings[name] = [u.text.strip() for u in ch.findall("url") if u.text]
		for cat in root.iter("sourcecat"):
			cname = cat.get("sourcecatname", fn)
			for s in cat.findall("source"):
				if s.get("type", "gen_xmltv") != "gen_xmltv":
					continue
				d = s.findtext("description", "").strip()
				urls = [u.text.strip() for u in s.findall("url") if u.text]
				if d and urls:
					src = Source(cname, d, s.get("channels", ""), urls)
					src.file, src.origin = fn, org
					src.report = (s.findtext("report") or "").strip()
					src.epgpl = (s.findtext("epgpl") or "").strip()
					sources.append(src)
	if not sources:
		mappings.update(BUILTIN["mappings"])
		for cat, d, ch, f in BUILTIN["sources"]:
			sources.append(Source(cat, d, ch, [m + f for m in BUILTIN["mirrors"]]))
	return sources, mappings


def epgimport_selected():
	"""Opisy zrodel zaznaczonych w EPG Import (do wstepnego wyboru)."""
	try:
		with open(EPGIMPORT_CONF, "rb") as f:
			data = f.read().decode("utf-8", "ignore")
	except Exception:
		return []
	srcs, _ = load_sources()
	return [s.desc for s in srcs if s.desc in data]


DEK_DESC = "EPG PL (dekoder)"
PROFILE = None       # profil biezacego startu (engine) - zrodla wg profilu zamiast samej listy zaznaczonych


def local_ok():
	"""Plik pobrany przez dekoder jest uzywalny: istnieje, mlodszy niz 30 h, ostatnia proba sie udala."""
	try:
		from .localrun import out_dir, local_job
		if local_job().last_ok is False:
			return False
		f = out_dir() + "pl.xml.xz"
		return os.path.exists(f) and time.time() - os.path.getmtime(f) < 30 * 3600
	except Exception:
		return False


def selected_sources():
	want = [x for x in cfg.xmltv_sources.value.split("|") if x]
	srcs, maps = load_sources()
	p = PROFILE
	if p is None:
		return [s for s in srcs if s.desc in want], maps
	# dekoder: tylko uzywalny plik (swiezy, ostatnia proba udana); serwer "zapas" i "zawsze": zawsze w kolejce -
	# kanal bierze program z pierwszego zrodla, ktore go ma, wiec serwer uzupelnia to, czego dekoder nie dal
	dek = [s for s in srcs if s.desc == DEK_DESC] if p.get("dek") and local_ok() else []
	srv = [s for s in srcs if s.epgpl] if p.get("srv") in ("zapas", "zawsze") else []
	other = [s for s in srcs if s.desc in want and s.desc != DEK_DESC and not s.epgpl] if p.get("other") else []
	groups = {"d": dek, "s": srv, "o": other}
	out = []
	for c in cfg.src_order.value:
		out += groups[c]
	return out, maps


# ---------------------------------------------------------------- pomocnicze

def ref_key(ref):
	"""(sid, tsid, onid, ns) z referencji kanalu (DVB lub IPTV)."""
	p = ref.split(":")
	try:
		return (int(p[3], 16), int(p[4], 16), int(p[5], 16), int(p[6], 16))
	except Exception:
		return None


def tidy_events(evs):
	"""Sortuje audycje kanalu, usuwa powtorzenia startu i przycina koniec do poczatku nastepnej
	(zrodla XMLTV czesto wpisuja ostatniej audycji dnia +1 h - bez tego w EPG powstaja duble)."""
	evs = sorted(evs)
	out = []
	for e in evs:
		if out and out[-1][0] == e[0]:
			continue
		if out and out[-1][0] + out[-1][1] > e[0]:
			last = list(out[-1])
			last[1] = e[0] - last[0]
			out[-1] = tuple(last)
		out.append(e)
	return [e for e in out if e[1] > 0]


def fill_gaps(evs, now, covered_now, mode):
	"""Audycje zastepcze "Brak programu do HH:MM": Multi EPG (Teraz/Nastepny) zaczyna od audycji trwajacej
	teraz - kanal bez niej (np. noc, ktorej zrodlo nie podaje) jest w Multi EPG pusty mimo programu na pozniej.
	Zwraca (audycje, liczba dodanych)."""
	if mode == "off" or not evs:
		return evs, 0
	hm = lambda t: time.strftime("%H:%M", time.localtime(t))
	desc = "Zrodlo XMLTV nie podaje programu na te godziny (audycja zastepcza EPG Scan)."
	out = list(evs)
	added = 0
	if not covered_now and now + 60 < out[0][0] <= now + 18 * 3600:
		st = int(now) - 60
		out.insert(0, (st, out[0][0] - st, "Brak programu do %s" % hm(out[0][0]), "", desc, 0))
		added += 1
	if mode == "all":
		res = []
		for a, b in zip(out, out[1:]):
			res.append(a)
			end = a[0] + a[1]
			if b[0] - end >= 1800:
				res.append((end, b[0] - end, "Brak programu do %s" % hm(b[0]), "", desc, 0))
				added += 1
		res.append(out[-1])
		out = res
	return out, added


def key_ref(k):
	return "1:0:1:%X:%X:%X:%X:0:0:0:" % k


_good_hosts = []     # serwery, ktore zadzialaly w tym przebiegu
_bad_hosts = set()   # serwery z bledem


def _host(u):
	return u.split("/")[2] if "//" in u else ""


def fetch(urls, dest):
	"""Pobiera pierwszy dzialajacy adres z listy; zwraca uzyty adres albo None.
	Serwery, ktore juz zadzialaly, sa probowane najpierw, a te z bledem - na koncu."""
	import urllib.request
	urls = sorted(urls, key=lambda u: (0 if _host(u) in _good_hosts else 2 if _host(u) in _bad_hosts else 1))
	for u in urls:
		try:
			if u.startswith("/"):
				shutil.copyfile(u, dest)
			else:
				req = urllib.request.Request(u, headers={"User-Agent": "EPGScan"})
				with urllib.request.urlopen(req, timeout=60) as r, open(dest, "wb") as f:
					shutil.copyfileobj(r, f)
					want = r.headers.get("Content-Length")
				if want and want.isdigit() and os.path.getsize(dest) != int(want):
					raise IOError("pobrano %d z %s bajtow" % (os.path.getsize(dest), want))
			if os.path.getsize(dest) > 100:
				h = _host(u)
				if h and h not in _good_hosts:
					_good_hosts.append(h)
				return u
		except Exception as e:
			if _host(u) not in _bad_hosts:
				log("XMLTV: %s - %s" % (u, e))
			_bad_hosts.add(_host(u))
	return None


def open_stream(path, name):
	"""Strumien rozpakowanych danych (.xz przez lzma albo systemowe xz, .gz przez gzip)."""
	if name.endswith(".xz"):
		try:
			import lzma
			return lzma.open(path, "rb"), None
		except ImportError:
			p = subprocess.Popen(["xz", "-dc", path], stdout=subprocess.PIPE)
			return p.stdout, p
	if name.endswith(".gz"):
		return gzip.open(path, "rb"), None
	return open(path, "rb"), None


_TZ = re.compile(r"^(\d{8,14})\s*([+-]\d{4})?")


def xmltv_time(s):
	"""Czas XMLTV: YYYYMMDDhhmm[ss] [+-hhmm] (Azman podaje bez sekund, Rytec z sekundami)."""
	m = _TZ.match((s or "").strip())
	if not m:
		return None
	digits = (m.group(1) + "000000")[:14]
	t = calendar.timegm(time.strptime(digits, "%Y%m%d%H%M%S"))
	if m.group(2):
		off = int(m.group(2))
		sign = 1 if off >= 0 else -1
		off = abs(off)
		t -= sign * ((off // 100) * 3600 + (off % 100) * 60)
	return t


# ---------------------------------------------------------------- import

class XmltvImport(object):
	"""Import w tle: pobieranie i parsowanie w osobnym watku, zapis do EPG w glownym watku."""

	def __init__(self, targets, done_cb, names=None):
		self.targets = targets          # {key: horyzont satelity (koniec ostatniej audycji) albo 0}
		self.names = names or {}        # {key: nazwa kanalu} - do logu
		self.done_cb = done_cb
		self.status = "przygotowanie"
		self.cancelled = False
		self.force = False              # bez pomijania zrodel oznaczonych jako nieaktualne/niedostepne
		self.results = {}               # key -> [events]
		self.covered = set()            # kanaly, ktore dostaly dane z wczesniejszego zrodla
		self.stats = []                 # (zrodlo, kanalow, audycji)
		self.thread = None
		self.timer = eTimer()
		self.timer.callback.append(self.poll)
		self.imp_timer = eTimer()
		self.imp_timer.callback.append(self.import_step)
		self.queue = []
		self.imported = 0
		self.state = load_state()

	def start(self):
		if not [x for x in cfg.xmltv_sources.value.split("|") if x]:
			log("XMLTV: nie wybrano zrodel")
			return False
		try:
			os.makedirs(TMP)
		except Exception:
			pass
		self.thread = threading.Thread(target=self.prepare_and_work)
		self.thread.daemon = True
		self.thread.start()
		self.timer.start(1000, False)
		return True

	def cancel(self):
		self.cancelled = True

	# --- watek roboczy (bez wywolan Enigma2) ---
	def prepare_and_work(self):
		"""W watku: aktualizacja wlasnych list z zapamietanych adresow, potem import."""
		self.status = "aktualizacja list zrodel"
		try:
			for name, res in refresh_own():
				log("XMLTV: lista %s: %s" % (name, res))
		except Exception as e:
			log("XMLTV: aktualizacja list zrodel nieudana: %s" % e)
		srcs, maps = selected_sources()
		if from_cache:
			log("XMLTV: listy zrodel z kopii wtyczki (brak w /etc/epgimport): %s" % ", ".join(from_cache))
		if not srcs:
			log("XMLTV: zadne z wybranych zrodel nie jest dostepne")
			self.status = "zapis do EPG"
			return
		self.work(srcs, maps)

	def work(self, srcs, maps):
		chmaps = {}
		now = time.time()
		del _good_hosts[:]
		_bad_hosts.clear()
		for i, s in enumerate(srcs):
			if self.cancelled:
				break
			try:
				prev = self.state.get(s.desc) or {}
				if not (self.force and s.epgpl) and prev.get("state") in ("stale", "down") and now - prev.get("time", 0) < RECHECK_BAD:
					log("XMLTV: %s - pomijam (%s, ponowne sprawdzenie po %s)" % (s.desc,
						"nieaktualne" if prev.get("state") == "stale" else "niedostepne",
						time.strftime("%d.%m %H:%M", time.localtime(prev.get("time", 0) + RECHECK_BAD))))
					continue
				self.status = "%s (%d/%d): mapa kanalow" % (s.desc, i + 1, len(srcs))
				ids = chmaps.get(s.channels)
				if ids is None:
					ids = self.load_map(s.channels, maps)
					chmaps[s.channels] = ids
				need = {}
				for cid, keys in ids.items():
					ks = [k for k in keys if k in self.targets and k not in self.covered]
					if ks:
						need[cid] = ks
				if not need:
					log("XMLTV: %s - brak potrzebnych kanalow, pomijam" % s.desc)
					continue
				name = s.urls[0].split("/")[-1].split("?")[0] or "data.xml"
				dest = os.path.join(TMP, "data_" + name)
				got = None
				used = None
				stale_last = None
				ordered = sorted(s.urls, key=lambda u: (0 if _host(u) in _good_hosts else 2 if _host(u) in _bad_hosts else 1))
				tries = {}
				queue = list(ordered)
				while queue:
					u = queue.pop(0)
					if self.cancelled:
						break
					self.status = "%s (%d/%d): pobieranie" % (s.desc, i + 1, len(srcs))
					if not fetch([u], dest):
						continue
					self.status = "%s (%d/%d): przetwarzanie" % (s.desc, i + 1, len(srcs))
					try:
						res, maxend, cnt = self.parse(dest, name, need, now)
					except Exception as e:
						tries[u] = tries.get(u, 0) + 1
						if tries[u] < 2:
							queue.insert(0, u)       # uszkodzony plik - jeszcze jedna proba z tego adresu
							log("XMLTV: %s (%s) - plik uszkodzony lub niepelny (%s) - probuje ponownie" % (s.desc, _host(u), e))
						else:
							log("XMLTV: %s (%s) - plik ponownie uszkodzony - nastepny serwer" % (s.desc, _host(u)))
						continue
					finally:
						try:
							os.remove(dest)
						except Exception:
							pass
					if cnt and maxend <= now:
						stale_last = time.strftime("%d.%m", time.localtime(maxend))
						log("XMLTV: %s (%s) - dane nieaktualne, ostatnia audycja %s - probuje nastepnego serwera" % (
							s.desc, _host(u), stale_last))
						continue
					got, used = res, u
					self.state[s.desc] = {"state": "ok", "time": int(now),
						"last": time.strftime("%d.%m", time.localtime(maxend)) if maxend else "?"}
					break
				if got is None:
					if tries and not stale_last:
						self.state[s.desc] = {"state": "corrupt", "time": int(now)}
						log("XMLTV: %s - plik uszkodzony na serwerze, sprobuje przy nastepnym skanie" % s.desc)
						continue
					if stale_last:
						self.state[s.desc] = {"state": "stale", "time": int(now), "last": stale_last}
						log("XMLTV: %s - wszystkie serwery maja nieaktualne dane" % s.desc)
					else:
						self.state[s.desc] = {"state": "down", "time": int(now)}
						log("XMLTV: %s - zaden adres nie odpowiada" % s.desc)
					continue
				nch = len(got)
				nev = sum(len(v) for v in got.values())
				for k, evs in got.items():
					self.results[k] = evs
					self.covered.add(k)
				self.stats.append((s.desc, nch, nev))
				named = [self.names[k] for k in got if k in self.names]
				extra = nch - len(named)
				log("XMLTV: %s (%s) - potrzebnych %d, dostalo dane %d, audycji %d%s%s" % (
					s.desc, _host(used) or used, len(need), nch, nev,
					(": " + ", ".join(named)) if named else "",
					(" (+%d kopii kanalow spoza bukietow)" % extra) if extra else ""))
			except Exception as e:
				log("XMLTV: %s - blad: %s" % (s.desc, e))
		save_state(self.state)
		self.status = "zapis do EPG"

	def load_map(self, channels, maps):
		urls = maps.get(channels)
		if not urls:
			urls = [channels] if ("/" in channels) else [os.path.join(x, channels) for x in (SOURCES_DIR, OWN_DIR, PKG_DIR)]
		name = channels.split("/")[-1]
		dest = os.path.join(TMP, "map_" + name)
		if not fetch(urls, dest):
			log("XMLTV: brak mapy kanalow %s" % channels)
			return {}
		ids = {}
		stream, proc = open_stream(dest, name)
		try:
			for ev, el in ET.iterparse(stream, events=("end",)):
				if el.tag == "channel" and el.get("id") and el.text:
					k = ref_key(el.text.strip())
					if k:
						ids.setdefault(el.get("id"), []).append(k)
				el.clear()
		finally:
			stream.close()
			if proc:
				proc.wait()
			try:
				os.remove(dest)
			except Exception:
				pass
		return ids

	def parse(self, path, name, need, now):
		"""(audycje dla potrzebnych kanalow, koniec najpozniejszej audycji w pliku, liczba audycji)."""
		got = {}
		maxend = 0
		cnt = 0
		stream, proc = open_stream(path, name)
		try:
			for ev, el in ET.iterparse(stream, events=("end",)):
				if self.cancelled:
					break
				if el.tag != "programme":
					if el.tag not in ("title", "sub-title", "desc", "category"):
						el.clear()
					continue
				cnt += 1
				if cnt % 200 == 0 or not maxend:
					e0 = xmltv_time(el.get("stop"))
					if e0 and e0 > maxend:
						maxend = e0
				keys = need.get(el.get("channel"))
				if keys:
					b = xmltv_time(el.get("start"))
					e = xmltv_time(el.get("stop"))
					if e and e > maxend:
						maxend = e
					if b and e and e > b and e > now:
						title = el.findtext("title", "") or ""
						sub = el.findtext("sub-title", "") or ""
						desc = el.findtext("desc", "") or ""
						for k in keys:
							hz = self.targets.get(k, 0)
							bb = b
							if hz and b < hz:
								if e <= hz + 60:
									continue      # ten czas pokrywa satelita
								bb = hz           # audycja trwa w chwili konca danych z satelity - od tego miejsca,
								                  # bez dziury na styku satelita/XMLTV (Multi EPG "nastepny" sie na niej urywa)
							got.setdefault(k, []).append((bb, e - bb, title, sub, desc, 0))
				el.clear()
		finally:
			stream.close()
			if proc:
				proc.wait()
		return got, maxend, cnt

	# --- glowny watek ---
	def poll(self):
		if self.thread and self.thread.is_alive():
			return
		self.timer.stop()
		if self.cancelled:
			log("XMLTV: import przerwany")
			self.finish()
			return
		self.queue = list(self.results.items())
		self.imp_timer.start(50, False)

	def import_step(self):
		cache = eEPGCache.getInstance()
		for _ in range(10):
			if not self.queue or self.cancelled:
				self.imp_timer.stop()
				self.finish()
				return
			k, evs = self.queue.pop(0)
			evs = tidy_events(evs)
			hz = self.targets.get(k, 0)
			evs, n = fill_gaps(evs, time.time(), bool(hz and hz > time.time()), cfg.xmltv_gaps.value)
			self.fillers = getattr(self, "fillers", 0) + n
			ref = key_ref(k)
			try:
				if hasattr(cache, "importEvents"):
					cache.importEvents([ref], tuple(evs))
				else:
					cache.importEvent(ref, tuple(evs))
				self.imported += len(evs)
			except Exception as e:
				log("XMLTV: zapis do EPG nieudany (%s): %s" % (ref, e))
			self.status = "zapis do EPG (%d kanalow zostalo)" % len(self.queue)

	def finish(self):
		log("XMLTV: koniec, zapisano %d audycji dla %d kanalow%s" % (self.imported, len(self.results),
			(" (w tym %d zastepczych \"Brak programu\")" % self.fillers) if getattr(self, "fillers", 0) else ""))
		left = [self.names[k] for k in self.targets if k in self.names and not self.targets[k] and k not in self.results]
		if left:
			log("XMLTV: bez EPG (brak w wybranych zrodlach) %d: %s%s" % (len(left), ", ".join(left[:40]),
				(" ... i %d innych" % (len(left) - 40)) if len(left) > 40 else ""))
		try:
			shutil.rmtree(TMP)
		except Exception:
			pass
		cb = self.done_cb
		self.done_cb = None
		if cb:
			cb()
