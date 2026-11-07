# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - mapa kanalow budowana na dekoderze (bez serwera): listy stacji serwisow + bukiety odbiornika
# + wskazania (/etc/enigma2/epgscan/wskazania.txt), aliases.txt, aliases.dist. Reguly jak w epgpl build_map.py:
# normalizacja nazw, kolejnosc serwisow, rozszerzenie nazwy, kopie z transponderow Twoich kanalow,
# przejscie na kolejny serwis, gdy stacja nie dala danych (empty.json, ponowna proba po 7 dniach).

import collections
import html
import json
import lzma
import os
import re
import time
import unicodedata
from xml.sax.saxutils import escape

# te same nazwy plikow co na serwerze epgpl (/opt/epgpl): wskazania.txt, aliases.txt, aliases.dist
DIR = "/etc/enigma2/epgscan/"
PINS = DIR + "wskazania.txt"                     # reczne wskazania z ekranu dopasowania
USER_ALIASES = DIR + "aliases.txt"               # wlasne dopasowania tekstowe (opcjonalnie)
DIST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "aliases.dist")   # z paczki
OLD_PINS = "/etc/enigma2/epgscan_kanaly.txt"     # nazwa do 2.3.x
ORDER = ["tm", "onet", "naz", "wp"]
NAMES = {"tm": "teleman", "onet": "onet", "naz": "naziemna", "wp": "WP Pilot"}
SITE = {"tm": "teleman.pl", "onet": "programtv.onet.pl", "naz": "programtv.naziemna.info", "wp": "pilot.wp.pl"}
LIST_URL = {"tm": "https://www.teleman.pl/program-tv/stacje", "onet": "https://programtv.onet.pl/stacje",
	"naz": "https://programtv.naziemna.info/program/stacje", "wp": "https://pilot.wp.pl/program-tv/lista-kanalow"}
LIST_MIN = {"tm": 20, "onet": 200, "naz": 20, "wp": 20}
TV_TYPES = {"1", "11", "16", "19", "1F", "86", "D3"}
DROP = {"hd", "uhd", "4k", "sd", "fhd", "poland", "channel", "tv"}
VARIANT = {"extra", "xtra", "premium", "plus", "max", "family", "kids", "junior", "news", "sport", "sports", "film",
	"films", "life", "fight", "live", "info", "gold", "classic", "hits", "int", "international", "polska",
	"east", "west", "nord", "sud", "rozrywka", "seriale", "dokument", "historia", "kultura", "comedy", "action",
	"music", "xxl", "mix", "one", "two", "start", "fun", "hit", "series", "cinema", "kino", "premiere", "ultra", "wild",
	"people", "science", "turbo", "home", "travel", "women", "men", "baby"}
SKIP_BOUQUETS = {"Last Scanned"}
EMPTY_RETRY = 7 * 86400


# ---------------------------------------------------------------- nazwy
def toks(name):
	s = unicodedata.normalize("NFKD", (name or "").lower().replace("ł", "l")).encode("ascii", "ignore").decode()
	s = s.replace("+", " ").replace("&", " and ")
	s = re.sub(r"\btv\s+(\d+)(?![0-9a-z])", r"tv\1", s)
	tk = [t for t in re.split(r"[^a-z0-9]+", s) if t and t not in DROP]
	return [t[:-2] if len(t) > 4 and t.endswith("tv") and not t[:-2].isdigit() else t for t in tk]


def norm(name):
	return "".join(toks(name))


def ctoks(name):
	return [x for t in toks(name) for x in re.findall(r"[a-z]+|[0-9]+", t)]


def tok_ok(s, t):
	if s == t:
		return True
	if s.isdigit() or t.isdigit() or len(s) < 3 or s[0] != t[0]:
		return False
	if t.startswith(s):
		return True
	it = iter(t)
	return all(ch in it for ch in s)


def extends(st, tt):
	if len(st) < 2 or len(tt) < len(st) or not any(x.isalpha() and len(x) >= 3 for x in st):
		return False
	if not all(tok_ok(a, b) for a, b in zip(st, tt)):
		return False
	return all(x.isalpha() and x not in VARIANT for x in tt[len(st):])


def xid_of(tag, sid):
	return "%s.%s" % (tag, re.sub(r"[^A-Za-z0-9-]", "_", sid))


# ---------------------------------------------------------------- listy stacji serwisow
def parse_list(tag, data):
	seen = {}
	if tag == "tm":
		for slug, name in re.findall(r'<a href="/program-tv/stacje/([^"?#]+)"[^>]*>([^<]+)<', data):
			name = html.unescape(name).strip()
			if slug not in seen and name:
				seen[slug] = name
	elif tag == "wp":
		m = re.search(r'<script id="__PRELOAD_STATE__" type="application/json">(.*?)</script>', data, re.S)
		if m:
			for c in json.loads(m.group(1)).get("ChannelsStore", {}).get("channels", []):
				if c.get("slug") and c.get("name") and c["slug"] not in seen:
					seen[c["slug"]] = c["name"].strip()
	elif tag == "naz":
		for slug, name in re.findall(r'href="/program/stacja/([^",=?#]+)"[^>]*class="dropdown-item"[^>]*>([^<]+)<', data):
			name = html.unescape(name).strip()
			if slug not in seen and name:
				seen[slug] = name
	elif tag == "onet":
		alt = {}
		for pre, slug, post, inner in re.findall(
				r'<a([^>]*?)href="(?:https://programtv\.onet\.pl)?/program-tv/([a-z0-9][a-z0-9.-]*)/?"([^>]*)>(.*?)</a>', data, re.S | re.I):
			name = re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", inner))).strip()
			if name:
				seen.setdefault(slug, name)
			else:
				m = re.search(r'(?:alt|title)="([^"]+)"', inner + pre + post)
				if m:
					alt.setdefault(slug, html.unescape(m.group(1)).strip())
		for slug, name in alt.items():
			seen.setdefault(slug, name)
	return sorted(seen.items(), key=lambda x: x[1].lower())


def refresh_stations(d, msgs):
	"""Odswieza listy stacji (kopia w stacje.json; przy bledzie albo za malej liscie zostaje poprzednia)."""
	from .localgrab import fetch
	path = d + "stacje.json"
	try:
		st = json.load(open(path))
	except Exception:
		st = {}
	for tag in ORDER:
		try:
			lst = parse_list(tag, fetch(LIST_URL[tag], tries=2))
			if len(lst) >= LIST_MIN[tag]:
				st[tag] = lst
			else:
				msgs.append("lista stacji %s: za malo (%d) - zostaje poprzednia" % (NAMES[tag], len(lst)))
		except Exception as e:
			msgs.append("lista stacji %s: %s - zostaje poprzednia" % (NAMES[tag], e))
	tmp = path + ".tmp"
	with open(tmp, "w") as f:
		json.dump(st, f, ensure_ascii=False)
	os.rename(tmp, path)
	return st


# ---------------------------------------------------------------- bukiety odbiornika
def read_bouquets(all_bq=False):
	"""[(bukiet, nazwa, ref10)] uslug TV z bukietow wybranych dla programu z internetu
	(Ustawienia -> Program z internetu -> Kanaly z bukietow); all_bq=True - ze wszystkich."""
	from .common import E2, list_bouquets, service_name, xmltv_bouquet_files
	out = []
	use = None if all_bq else set(xmltv_bouquet_files())
	for fname, bname in list_bouquets():
		if use is not None and fname not in use:
			continue
		seen = set()

		def walk(fn):
			if fn in seen:
				return
			seen.add(fn)
			try:
				with open(os.path.join(E2, fn), encoding="utf-8", errors="ignore") as f:
					lines = f.read().splitlines()
			except Exception:
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
				if len(p) < 10 or p[0] != "1" or p[1] != "0" or p[2].upper() not in TV_TYPES or p[3] == "0":
					continue
				nm = ""
				if i + 1 < len(lines) and lines[i + 1].startswith("#DESCRIPTION"):
					nm = lines[i + 1][13:].strip()
				if not nm and len(p) > 11:
					nm = p[-1]
				r10 = ":".join(p[:10]) + ":"
				out.append((bname, nm or service_name(r10), r10))
		walk(fname)
	return out


# ---------------------------------------------------------------- wskazania (aliasy)
def migrate_files():
	"""2.4.0: epgscan_kanaly.txt -> /etc/enigma2/epgscan/wskazania.txt (ta sama nazwa co na serwerze)."""
	try:
		if not os.path.isdir(DIR):
			os.makedirs(DIR)
		if os.path.exists(OLD_PINS) and not os.path.exists(PINS):
			os.rename(OLD_PINS, PINS)
	except Exception:
		pass


def load_pins(path=None):
	"""{nazwa: cel} - cel: "tag:site_id", "-" albo inna nazwa."""
	if path is None:
		migrate_files()
	path = path or PINS
	out = collections.OrderedDict()
	try:
		for ln in open(path, encoding="utf-8"):
			ln = ln.split("#", 1)[0].strip()
			if "=" in ln:
				k, v = [x.strip() for x in ln.split("=", 1)]
				if k:
					out[k] = v
	except Exception:
		pass
	return out


def save_pins(pins, path=None):
	if path is None:
		migrate_files()
	path = path or PINS
	tmp = path + ".tmp"
	with open(tmp, "w", encoding="utf-8") as f:
		f.write("# EPG Scan - wskazania zrodla programu (Kanaly -> Dopasowanie kanalow: dekoder -> OK na kanale)\n")
		f.write("# Nazwa kanalu = tm:/onet:/naz:/wp:site_id, albo = - (bez programu), albo = inna nazwa. Pierwszenstwo przed automatem.\n")
		for k in sorted(pins, key=str.lower):
			f.write("%s = %s\n" % (k, pins[k]))
	os.rename(tmp, path)


# ---------------------------------------------------------------- budowa mapy
def build(d, stations, order=None):
	"""Buduje mape i zapisuje: pl.channels.xml.xz (xid -> referencje), mapa.json (jak serwer), raport.txt.
	Zwraca (mapa, liczba kanalow do pobrania, linie raportu)."""
	from .common import site_order
	order = [t for t in (order or site_order()) if t in stations]
	services = read_bouquets()
	sat = [(n, r) for b, n, r in services]
	mine = [(b, n, r) for b, n, r in services if b not in SKIP_BOUQUETS]
	sites, by_id = {}, {}
	for tag in order:
		dct = {}
		for sid, name in stations.get(tag, []):
			by_id["%s:%s" % (tag, sid)] = {"tag": tag, "site_id": sid, "name": name}
			k = norm(name)
			if k and k not in dct:
				dct[k] = by_id["%s:%s" % (tag, sid)]
		sites[tag] = dct
	pins = load_pins()
	# kolejnosc waznosci jak na serwerze: aliases.dist < aliases.txt < wskazania.txt
	every = collections.OrderedDict()
	for p in (DIST, USER_ALIASES):
		every.update(load_pins(p))
	every.update(pins)
	aliases = {}
	for name, target in every.items():
		if target == "-":
			aliases[norm(name)] = None
		elif target.split(":", 1)[0] in SITE:
			it = by_id.get(target)
			if it:
				aliases[norm(name)] = it
		else:
			aliases[norm(name)] = ("name", norm(target))
	manual = {norm(n) for n in pins}
	try:
		empty = json.load(open(d + "empty.json"))
		now = time.time()
		empty = {k: v for k, v in empty.items() if now - v < EMPTY_RETRY}
	except Exception:
		empty = {}

	def lookup(*keys):
		first = None
		for tag in order:
			for k in keys:
				it = sites[tag].get(k)
				if it:
					if xid_of(tag, it["site_id"]) not in empty:
						return it, tag + (" (zamiast %s - brak danych)" % first["tag"] if first else "")
					first = first or it
		if first:
			return first, first["tag"] + " (brak danych w poprzednim pobraniu)"
		return None, None

	ext_index = {tag: [(ctoks(it["name"]), it) for it in dct.values()] for tag, dct in sites.items()}

	def lookup_ext(name):
		st = ctoks(name)
		first = None
		for tag in order:
			cand = [it for tt, it in ext_index.get(tag, []) if extends(st, tt)]
			if len(cand) != 1:
				continue
			it = cand[0]
			if xid_of(tag, it["site_id"]) not in empty:
				return it, tag + " (rozszerzenie nazwy)" + (" (zamiast %s - brak danych)" % first["tag"] if first else "")
			first = first or it
		if first:
			return first, first["tag"] + " (rozszerzenie nazwy) (brak danych w poprzednim pobraniu)"
		return None, None

	def find(name):
		k = norm(name)
		if k in aliases:
			it = aliases[k]
			if isinstance(it, tuple):
				it2, how = lookup(it[1], k)
				return it2, ((how + " (alias)") if it2 else how)
			return it, "alias"
		it, how = lookup(k)
		if it:
			return it, how
		return lookup_ext(name)

	def tp_of(ref):
		p = ref.split(":")
		return (p[4], p[5], p[6])
	my_refs = {r for _, _, r in mine}
	my_tps = {tp_of(r) for r in my_refs}
	refs_by_key = collections.defaultdict(set)
	names_by_key = collections.defaultdict(set)
	for name, ref in sat:
		if ref not in my_refs and tp_of(ref) not in my_tps:
			continue                    # kopie z transponderow bez Twoich kanalow (inne platformy/kraje)
		refs_by_key[norm(name)].add(ref)
		names_by_key[norm(name)].add(name)
	report, wanted, seen = [], {}, set()
	for bq, name, ref in mine:
		k = norm(name)
		if k in seen:
			continue
		seen.add(k)
		it, how = find(name)
		report.append((bq, name, it, how, len(refs_by_key[k])))
		if it:
			wanted[k] = it
	chan = collections.OrderedDict()
	for k, it in wanted.items():
		xid = xid_of(it["tag"], it["site_id"])
		e = chan.setdefault(xid, (it, set(), set()))
		e[1].update(refs_by_key[k])
		e[2].update(names_by_key[k])
	tmp = d + "pl.channels.xml.xz.tmp"
	with lzma.open(tmp, "wt", encoding="utf-8") as f:
		f.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- EPG Scan - mapa kanalow z dekodera -->\n<channels>\n')
		for xid, (it, refs, names) in chan.items():
			f.write("  <!-- %s -> %s -->\n" % (escape(", ".join(sorted(names))), escape(it["name"])))
			for r in sorted(refs):
				f.write('  <channel id="%s">%s</channel>\n' % (xid, r))
		f.write("</channels>\n")
	os.rename(tmp, d + "pl.channels.xml.xz")
	mapa = {"generated": int(time.time()), "version": "dekoder", "local": True, "sites": order, "site_names": NAMES,
		"empty": sorted(empty),            # stacje bez danych w ostatnim pobraniu (propozycje zmian w oknie dopasowania)
		"channels": [{"bq": bq, "name": name, "copies": n, "manual": norm(name) in manual,
			"off": (not it and how == "alias"), "how": how or "",
			"tag": it["tag"] if it else "", "site_id": it["site_id"] if it else "", "sname": it["name"] if it else ""}
			for bq, name, it, how, n in report]}
	with open(d + "mapa.json.tmp", "w") as f:
		json.dump(mapa, f, ensure_ascii=False)
	os.rename(d + "mapa.json.tmp", d + "mapa.json")
	ok = [r for r in report if r[2]]
	no = [r for r in report if not r[2] and r[3] != "alias"]
	off = [r for r in report if not r[2] and r[3] == "alias"]
	lines = ["Mapa kanalow z dekodera - %s" % time.strftime("%d.%m.%Y %H:%M"), "",
		"Kanaly z Twoich bukietow: %d, z programem: %d, bez dopasowania: %d, wylaczone: %d, wskazan recznych: %d" % (
			len(report), len(ok), len(no), len(off), len(pins)),
		"Stacji do pobrania: %d, kolejnosc serwisow: %s" % (len(chan), ", ".join(NAMES[t] for t in order)), "",
		"== BEZ DOPASOWANIA (Kanaly -> Dopasowanie kanalow: dekoder -> OK na kanale)"]
	lines += ["  [%s] %s" % (bq, name) for bq, name, it, how, n in no]
	lines += ["", "== DOPASOWANE"]
	lines += ["  [%s] %s -> %s %s (%s)%s" % (bq, name, it["tag"], it["name"], how, "  x%d" % n if n > 1 else "")
		for bq, name, it, how, n in ok]
	with open(d + "raport.txt", "w") as f:
		f.write("\n".join(lines) + "\n")
	return mapa, len(chan), lines[:4]


def update_empty(d, requested, result):
	"""Stacje bez danych w tym pobraniu -> empty.json (przy nastepnej mapie kolejny serwis)."""
	try:
		empty = json.load(open(d + "empty.json"))
	except Exception:
		empty = {}
	now = int(time.time())
	for xid in requested:
		if result.get(xid):
			empty.pop(xid, None)
		elif xid not in empty:
			empty[xid] = now
	with open(d + "empty.json", "w") as f:
		json.dump(empty, f)
	return [x for x in requested if not result.get(x)]


def pins_from_server_map(mapa):
	"""Wskazania z mapy serwera: reczne z tunera i aliasy z aliases.txt -> {nazwa: cel}."""
	out = {}
	for c in mapa.get("channels", []):
		if c.get("manual") or "alias" in (c.get("how") or "") or c.get("off"):
			if c.get("off"):
				out[c["name"]] = "-"
			elif c.get("tag") and c.get("site_id") and c["tag"] in SITE:
				out[c["name"]] = "%s:%s" % (c["tag"], c["site_id"])
	return out
