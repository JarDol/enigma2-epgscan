# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - pobieranie programu na dekoderze (bez serwera epgpl): teleman.pl, programtv.onet.pl,
# programtv.naziemna.info, pilot.wp.pl - parsery przeniesione z konfiguracji epgpl / iptv-org.
# Parser programtv.onet.pl (onet_parse) przepisany na Pythona na podstawie konfiguracji serwisu z projektu
# iptv-org/epg (https://github.com/iptv-org/epg, licencja Unlicense - domena publiczna). Pozostale parsery:
# wlasne (epgpl). Szczegoly: THIRD_PARTY.md.
# Lista stacji (ktory kanal z bukietow bierze ktora stacje) - z mapy serwera (mapa.json, kopia na dysku).

import calendar
import html
import lzma
import os
import re
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape, quoteattr

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
LI = re.compile(r'<li id="prog\d+"[^>]*>(.*?)</li>', re.S)
EM = re.compile(r"<em>\s*(\d{1,2}):(\d{2})\s*</em>")
DETAIL = re.compile(r'<div class="detail">(.*)', re.S)
A = re.compile(r"<a[^>]*>(.*?)</a>", re.S)
P = re.compile(r'<p( class="genre")?[^>]*>(.*?)</p>', re.S)
TAG = re.compile(r"<[^>]+>")


def text(s):
	return re.sub(r"\s+", " ", html.unescape(TAG.sub(" ", s or ""))).strip()


def fetch(url, tries=3, wait429=5, on_err=None):
	"""on_err(url, proba, kod, retry_after, poczatek_odpowiedzi, blad) - zapis bledow (opcja w ustawieniach)."""
	err = None
	for i in range(tries):
		try:
			req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "pl,en;q=0.5"})
			with urllib.request.urlopen(req, timeout=25) as r:
				return r.read().decode("utf-8", "ignore")
		except Exception as e:
			err = e
			code = getattr(e, "code", 0)
			ra = ""
			try:
				ra = (e.headers.get("Retry-After") or "") if getattr(e, "headers", None) else ""
			except Exception:
				pass
			if on_err:
				body = ""
				try:
					body = e.read()[:200].decode("utf-8", "ignore") if hasattr(e, "read") else ""
				except Exception:
					pass
				try:
					on_err(url, i + 1, code, ra, body, str(e))
				except Exception:
					pass
			if code == 404:
				break
			wait = wait429 * (i + 1) if code in (429, 503) else 2
			if ra.isdigit():
				wait = max(wait, min(int(ra), 120))     # serwis podal, ile czekac
			time.sleep(wait)
	raise err


def teleman_parse(page, day):
	"""[(start, tytul, opis, ostatnia_na_stronie)] z jednej strony dnia; day = (rok, mies, dzien)."""
	out = []
	offset = 0
	prev = -1
	for li in LI.findall(page):
		m = EM.search(li)
		if not m:
			continue
		h, mi = int(m.group(1)), int(m.group(2))
		mins = h * 60 + mi
		if prev >= 0 and mins < prev:
			offset += 1                    # przejscie przez polnoc
		prev = mins
		d = DETAIL.search(li)
		if not d:
			continue
		det = d.group(1)
		t = A.search(det)
		title = text(t.group(1)) if t else ""
		if not title:
			continue
		genre, desc = "", []
		for g, body in P.findall(det):
			if g:
				genre = text(body)
			else:
				x = text(body)
				if x:
					desc.append(x)
		st = int(time.mktime((day[0], day[1], day[2] + offset, h, mi, 0, 0, 0, -1)))
		out.append([st, title, "\n".join(x for x in (genre, " ".join(desc)) if x), False, None])
	if out:
		out[-1][3] = True
	return out


ONET_LI = re.compile(r'<li[^>]*>\s*<div class="hours">\s*<span class="hour">\s*(\d{1,2}):(\d{2})\s*</span>.*?<div class="titles">(.*?)</div>\s*</li>', re.S)
ONET_A = re.compile(r"<a[^>]*>(.*?)</a\s*>", re.S)
ONET_P = re.compile(r"<p>(.*?)</p>", re.S)
NAZ_CELL = re.compile(r'my-record-cell[^>]*>(.*?)(?=my-record-cell|$)', re.S)
NAZ_TIME = re.compile(r'<span class="text-monospace">\s*(\d{1,2}):(\d{2})')
NAZ_TITLE = re.compile(r"<strong>(.*?)</strong>", re.S)
WP_STATE = re.compile(r'<script id="__PRELOAD_STATE__" type="application/json">(.*?)</script>', re.S)


def _day_items(rows, day):
	"""[(h, m, tytul, opis)] w kolejnosci strony -> [start, tytul, opis, ostatnia, None] (przejscie przez polnoc)."""
	out, offset, prev = [], 0, -1
	for h, mi, title, desc in rows:
		mins = h * 60 + mi
		if prev >= 0 and mins < prev:
			offset += 1
		prev = mins
		st = int(time.mktime((day[0], day[1], day[2] + offset, h, mi, 0, 0, 0, -1)))
		out.append([st, title, desc, False, None])
	if out:
		out[-1][3] = True
	return out


def onet_parse(page, day):
	rows = []
	for h, mi, body in ONET_LI.findall(page):
		a = ONET_A.search(body)
		title = text(a.group(1)) if a else ""
		if title:
			p = ONET_P.search(body)
			rows.append((int(h), int(mi), title, text(p.group(1)) if p else ""))
	return _day_items(rows, day)


def naz_parse(page, day):
	rows = []
	for cell in NAZ_CELL.findall(page):
		p = re.search(r"<p>(.*?)</p>", cell, re.S)
		if not p:
			continue
		body = p.group(1)
		m = NAZ_TIME.search(body)
		t = NAZ_TITLE.search(body)
		if not m or not t or not text(t.group(1)):
			continue
		genre = re.sub(r"<span.*?</span>|<strong.*?</strong>|<br[^>]*>", " ", body, flags=re.S)
		genre = re.sub(r"[|\s]+", " ", text(genre)).strip()
		rows.append((int(m.group(1)), int(m.group(2)), text(t.group(1)), genre))
	return _day_items(rows, day)


def wp_parse(page, day=None):
	"""Strona kanalu WP Pilot: caly tydzien w __PRELOAD_STATE__ (czasy w ms)."""
	import json
	m = WP_STATE.search(page)
	if not m:
		return []
	try:
		st = json.loads(m.group(1))
	except Exception:
		return []
	out = []
	for e in ((st.get("ProgramStore") or {}).get("singleChannelEntries") or {}).values():
		if e.get("start") and e.get("end") and e.get("title"):
			desc = "\n".join(x for x in (e.get("episode_title"), e.get("genre")) if x)
			out.append([int(e["start"]) // 1000, e["title"], desc, False, int(e["end"]) // 1000])
	out.sort()
	return out


def merge(days):
	"""Strony kolejnych dni -> [(start, stop, tytul, opis)]: bez powtorzen, koniec = poczatek nastepnej
	(ostatnia na stronie: najwyzej +1 h, jak w epgpl), bez audycji zakonczonych ponad 2 h temu."""
	byst = {}
	for page in days:
		for st, title, desc, last, stop in page:
			old = byst.get(st)
			if not old or (len(desc) > len(old[1]) and not last):
				byst[st] = (title, desc, last, stop)
	starts = sorted(byst)
	out = []
	old = time.time() - 7200
	for i, st in enumerate(starts):
		title, desc, last, stop = byst[st]
		nxt = starts[i + 1] if i + 1 < len(starts) else None
		if stop:
			stop = min(stop, nxt) if nxt else stop
		else:
			stop = (min(nxt, st + 3600) if last else nxt) if nxt else st + 3600
		if stop > old:
			out.append((st, stop, title, desc))
	return out


SITE_NAMES = {"tm": "teleman", "onet": "onet", "naz": "naziemna", "wp": "WP Pilot"}
MONTHS = ("stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca", "lipca", "sierpnia",
	"wrze%C5%9Bnia", "pa%C5%BAdziernika", "listopada", "grudnia")


def site_url(tag, sid, day, idx):
	"""Adres strony; idx = numer dnia wzgledem dzisiejszego (dzien poprzedni = -1)."""
	if tag == "tm":
		return "https://www.teleman.pl/program-tv/stacje/%s?date=%04d-%02d-%02d&hour=-1" % (sid, day[0], day[1], day[2])
	if tag == "onet":
		return "https://programtv.onet.pl/program-tv/%s?dzien=%d" % (sid, idx)
	if tag == "naz":
		return "https://programtv.naziemna.info/program/stacja/%s,%02d-%s" % (sid, day[2], MONTHS[day[1] - 1])
	if tag == "wp":
		return "https://pilot.wp.pl/program-tv/kanal/%s" % sid
	return None


PARSERS = {"tm": teleman_parse, "onet": onet_parse, "naz": naz_parse, "wp": wp_parse}


class LocalGrab(object):
	"""Pobieranie w watkach, kazdy serwis rownolegle z wlasna pula (conns: {serwis: polaczen}), lacznie
	najwyzej max_total polaczen naraz;
	postep w self.done/self.total (i per serwis), wynik w self.result
	{xid: [(start, stop, tytul, opis)]}. stations = [(tag, site_id, xid, nazwa)]."""

	def __init__(self, stations, days=15, conns=None, max_total=10, pause=0.3, errlog=None, back=1):
		self.stations = [s for s in stations if s[0] in PARSERS]
		self.days = days
		self.back = back                      # od ilu dni wstecz (1 = od wczoraj: trwajaca noc)
		self.conns = conns or {}
		self.max_total = max_total
		self.slots = threading.BoundedSemaphore(max(1, max_total))
		self.errlog = errlog                  # sciezka pliku z bledami albo None
		self.errlock = threading.Lock()
		if errlog:
			try:
				with open(errlog, "w") as f:
					f.write("# EPG Scan - bledy pobierania %s\n# czas | proba | kod | Retry-After | adres | blad | poczatek odpowiedzi\n" %
						time.strftime("%d.%m.%Y %H:%M"))
			except Exception:
				self.errlog = None
		self.pause = pause
		self.done = self.errors = self.missing = 0      # missing: strony 404 (dzien poza zasiegiem serwisu)
		self.site_done, self.site_total, self.site_err = {}, {}, {}
		self.result = {}
		self.cancel = False
		self.t0 = time.time()
		self.t_end = 0
		self.lock = threading.Lock()

	def run(self):
		now = time.time()
		start = time.localtime(now - 86400 * self.back)              # domyslnie od wczoraj: trwajaca noc
		today = time.localtime(now)[:3]
		dates = [time.localtime(time.mktime((start.tm_year, start.tm_mon, start.tm_mday + i, 12, 0, 0, 0, 0, -1)))[:3]
			for i in range(self.days)]
		import datetime
		utc_today = datetime.date(*time.localtime(now)[:3])      # onet liczy dni wg czasu polskiego
		jobs = []
		for i, d in enumerate(dates):                             # dzien po dniu, serwisy na przemian
			for tag, sid, xid, name in self.stations:
				if tag == "wp" and i > 0:
					continue                                     # WP: jedna strona = caly tydzien
				idx = (datetime.date(*d) - utc_today).days
				if tag == "onet" and idx < 0:
					continue                                     # onet: tylko od dzis (dzien wstecz niepewny)
				jobs.append((tag, sid, xid, d, (datetime.date(*d) - utc_today).days))
		self.total = len(jobs)
		for j in jobs:
			self.site_total[j[0]] = self.site_total.get(j[0], 0) + 1
		pages = {}

		queues = {}                                                # osobna kolejka i pula polaczen na serwis (jak na serwerze)
		for j in jobs:
			queues.setdefault(j[0], []).append(j)

		failed = []
		self.gone = {}                                             # (serwis, stacja) -> pierwszy dzien z 404

		def worker(q):
			while not self.cancel:
				with self.lock:
					if not q:
						return
					job = q.pop(0)
				tag, sid, xid, d, idx = job
				if (tag, sid) in self.gone and d > self.gone[(tag, sid)]:
					with self.lock:                   # serwis nie ma juz dalszych dni tej stacji
						self.done += 1
						self.missing += 1
						self.site_done[tag] = self.site_done.get(tag, 0) + 1
					continue
				try:
					with self.slots:
						page = fetch(site_url(tag, sid, d, idx), on_err=self.log_err)
					items = PARSERS[tag](page, d)
					with self.lock:
						pages.setdefault(xid, []).append((d, items))
				except Exception as e:
					with self.lock:
						if getattr(e, "code", 0) == 404:
							self.missing += 1         # brak strony = brak danych na ten dzien, nie blad
							if idx > 0:
								old = self.gone.get((tag, sid))
								self.gone[(tag, sid)] = d if old is None else min(old, d)
						else:
							failed.append(job)        # druga proba na koncu, wolniej
				with self.lock:
					self.done += 1
					self.site_done[tag] = self.site_done.get(tag, 0) + 1
				time.sleep(self.pause)
		ths = [threading.Thread(target=worker, args=(q,)) for t, q in queues.items()
			for _ in range(min(int(self.conns.get(t, 2)), len(q)))]
		for t in ths:
			t.daemon = True
			t.start()
		for t in ths:
			t.join()
		# ponowienie stron z bledem (zwykle 429 - serwis chwilowo ogranicza): jedno polaczenie na serwis,
		# dluzsze przerwy - jak ponowienia z polowa polaczen na serwerze
		self.retried = len(failed)
		if failed and not self.cancel:
			self.phase = "ponawianie %d stron" % len(failed)
			rq = {}
			for j in failed:
				rq.setdefault(j[0], []).append(j)

			def retry(q):
				while q and not self.cancel:
					tag, sid, xid, d, idx = q.pop(0)
					try:
						with self.slots:
							page = fetch(site_url(tag, sid, d, idx), tries=4, wait429=20, on_err=self.log_err)
						items = PARSERS[tag](page, d)
						with self.lock:
							pages.setdefault(xid, []).append((d, items))
					except Exception as e:
						with self.lock:
							if getattr(e, "code", 0) == 404:
								self.missing += 1
							else:
								self.errors += 1
								self.site_err[tag] = self.site_err.get(tag, 0) + 1
					time.sleep(2)
			ths = [threading.Thread(target=retry, args=(q,)) for q in rq.values()]
			for t in ths:
				t.daemon = True
				t.start()
			for t in ths:
				t.join()
			self.phase = ""
		for xid, lst in pages.items():
			lst.sort(key=lambda x: x[0])
			self.result[xid] = merge([x[1] for x in lst])
		self.t_end = time.time()

	def log_err(self, url, attempt, code, ra, body, err):
		if not self.errlog:
			return
		line = "%s | %d | %s | %s | %s | %s | %s\n" % (time.strftime("%H:%M:%S"), attempt, code or "-", ra or "-", url,
			err.replace("\n", " ")[:120], body.replace("\n", " ").replace("\r", " ")[:200])
		with self.errlock:
			try:
				with open(self.errlog, "a") as f:
					f.write(line)
			except Exception:
				pass

	def progress(self):
		if getattr(self, "phase", ""):
			return "%s (zostalo bledow: %d)" % (self.phase, self.errors)
		return ", ".join("%s %d%%%s" % (SITE_NAMES.get(t, t), 100 * self.site_done.get(t, 0) // max(1, n),
			(" (bledow %d)" % self.site_err[t]) if self.site_err.get(t) else "")
			for t, n in sorted(self.site_total.items(), key=lambda x: -x[1]))


def xmltv_time(t):
	return time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))


def write_xmltv(path, result, names):
	tmp = path + ".tmp"
	with lzma.open(tmp, "wt", encoding="utf-8") as f:
		f.write('<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="EPG Scan (dekoder)">\n')
		for xid in sorted(result):
			f.write("  <channel id=%s><display-name>%s</display-name></channel>\n" % (quoteattr(xid), escape(names.get(xid, xid))))
		for xid in sorted(result):
			for st, sp, title, desc in result[xid]:
				f.write('  <programme start="%s" stop="%s" channel=%s><title lang="pl">%s</title>%s</programme>\n' % (
					xmltv_time(st), xmltv_time(sp), quoteattr(xid), escape(title),
					('<desc lang="pl">%s</desc>' % escape(desc)) if desc else ""))
		f.write("</tv>\n")
	os.rename(tmp, path)


def parse_time(s):
	m = re.match(r"(\d{14})\s*([+-]\d{4})?", s or "")
	if not m:
		return None
	t = calendar.timegm(time.strptime(m.group(1), "%Y%m%d%H%M%S"))
	if m.group(2):
		o = int(m.group(2))
		t -= (1 if o >= 0 else -1) * ((abs(o) // 100) * 3600 + (abs(o) % 100) * 60)
	return t


def server_progs(url, xids):
	"""Audycje wybranych kanalow z pliku programu serwera (pl.xml.xz)."""
	req = urllib.request.Request(url, headers={"User-Agent": "EPGScan"})
	data = urllib.request.urlopen(req, timeout=120).read()
	out = {}
	import io
	for ev, el in ET.iterparse(lzma.open(io.BytesIO(data))):
		if el.tag == "programme":
			ch = el.get("channel")
			if ch in xids:
				out.setdefault(ch, []).append((parse_time(el.get("start")), parse_time(el.get("stop")),
					(el.findtext("title") or "").strip(), (el.findtext("desc") or "").strip()))
			el.clear()
	for v in out.values():
		v.sort()
	return out


def norm_title(s):
	return re.sub(r"[^a-z0-9]", "", (s or "").lower())


def compare(local, server, names):
	"""Raport tekstowy: lokalnie vs serwer, w oknie od teraz do +7 dni."""
	now = time.time()
	end = now + 7 * 86400
	rows = []
	tot = {"l": 0, "s": 0, "match": 0, "title": 0, "ld": 0, "sd": 0, "lh": [], "sh": []}
	for xid in sorted(local, key=lambda x: names.get(x, x).lower()):
		lp = [p for p in local.get(xid, []) if p[1] > now and p[0] < end]
		sp = [p for p in server.get(xid, []) if p[1] and p[1] > now and p[0] < end]
		sst = {}
		for p in sp:
			sst.setdefault(p[0], p)
		m = t = 0
		for p in lp:
			q = sst.get(p[0])
			if q:
				m += 1
				if norm_title(q[2]) == norm_title(p[2]):
					t += 1
		lh = (max(p[1] for p in local[xid]) - now) / 86400.0 if local.get(xid) else 0
		sh = (max(p[1] or p[0] for p in server[xid]) - now) / 86400.0 if server.get(xid) else 0
		ld = sum(len(p[3]) for p in lp) / float(len(lp)) if lp else 0
		sd = sum(len(p[3]) for p in sp) / float(len(sp)) if sp else 0
		tot["l"] += len(lp)
		tot["s"] += len(sp)
		tot["match"] += m
		tot["title"] += t
		tot["ld"] += sum(len(p[3]) for p in lp)
		tot["sd"] += sum(len(p[3]) for p in sp)
		tot["lh"].append(lh)
		tot["sh"].append(sh)
		flag = "" if lp and abs(len(lp) - len(sp)) <= max(3, len(sp) // 20) else "  <--"
		rows.append("%-26s %5d / %5d   %3d%% / %3d%%   %4.1f / %4.1f dni   opis %3d / %3d%s" % (
			names.get(xid, xid)[:26], len(lp), len(sp), (100 * m // len(lp)) if lp else 0, (100 * t // len(lp)) if lp else 0,
			lh, sh, ld, sd, flag))
	med = lambda v: sorted(v)[len(v) // 2] if v else 0
	head = [
		"Kanalow: %d.  Audycji w 7 dniach: dekoder %d / serwer %d" % (len(local), tot["l"], tot["s"]),
		"Zgodnosc z serwerem: ta sama godzina %d%%, ten sam tytul %d%%" % (
			(100 * tot["match"] // tot["l"]) if tot["l"] else 0, (100 * tot["title"] // tot["l"]) if tot["l"] else 0),
		"Zasieg (mediana): dekoder %.1f / serwer %.1f dni.  Srednia dlugosc opisu: dekoder %d / serwer %d znakow" % (
			med(tot["lh"]), med(tot["sh"]), (tot["ld"] // tot["l"]) if tot["l"] else 0, (tot["sd"] // tot["s"]) if tot["s"] else 0),
		"",
		"%-26s %13s   %11s   %16s   %s" % ("kanal", "audycji d/s", "godz./tytul", "zasieg d/s", "opis d/s (<-- rozne)"),
	]
	return "\n".join(head + rows)
