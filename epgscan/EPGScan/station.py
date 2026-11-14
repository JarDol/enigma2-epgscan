# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - okno wyboru stacji dla kanalu (Dopasowanie kanalow: dekoder i serwer - to samo okno).
# U gory: kanal, bukiet, obecne zrodlo i okienko z kanalem na zywo (przelaczenie przy wejsciu, powrot przy wyjsciu).
# Lista: najlepsi kandydaci, potem stacje zgodne tylko jednym slowem. Pod lista: serwis, stacja,
# srednia dlugosc opisow, ile dni programu i biezaca audycja (dla stacji, ktore juz sa w pliku programu).

import difflib
import os
import re
import threading
import time

from enigma import eTimer
from Screens.Screen import Screen
from Screens.MessageBox import MessageBox
from Components.ActionMap import ActionMap
from Components.Label import Label
from Components.MenuList import MenuList

from .i18n import _
from .common import keys_skin, init_keys, set_keys, hints_skin, init_hints, log

CAND = {}        # pobieranie programu kandydatow na zadanie (jedno naraz, trwa po zamknieciu okna)
HINTS = (("OK", "Wybierz"), ("Menu", "Pobierz program"), ("Info/EPG", "Podglad programu"))


def xid_of(tag, sid):
	return "%s.%s" % (tag, re.sub(r"[^A-Za-z0-9-]", "_", sid))


# ---------------------------------------------------------------- statystyki pliku programu
_cache = {}      # sciezka -> (mtime, {xid: {"avg", "days", "progs": [(start, stop, tytul)]}})


def file_stats(path):
	"""Statystyki stacji z pliku XMLTV (.xz): srednia dlugosc opisu, dni programu od teraz, audycje.
	Liczone raz na wersje pliku (cache po czasie modyfikacji)."""
	try:
		mt = os.path.getmtime(path)
	except Exception:
		return {}
	old = _cache.get(path)
	if old and old[0] == mt:
		return old[1]
	import lzma
	import xml.etree.ElementTree as ET
	from .localgrab import parse_time
	acc = {}
	now = time.time()
	with lzma.open(path) as f:
		for ev, el in ET.iterparse(f):
			if el.tag == "programme":
				x = el.get("channel")
				st, sp = parse_time(el.get("start")), parse_time(el.get("stop"))
				if x and st:
					a = acc.setdefault(x, [0, 0, 0, []])       # audycji, znakow opisu, koniec, audycje
					a[0] += 1
					a[1] += len((el.findtext("desc") or "").strip())
					a[2] = max(a[2], sp or st)
					if (sp or st + 1800) > now - 3 * 3600 and st < now + 6 * 3600:
						a[3].append((st, sp or 0, (el.findtext("title") or "").strip()))
				el.clear()
	out = {}
	for x, (n, chars, end, progs) in acc.items():
		progs.sort()
		for i, (a, b, t) in enumerate(progs):           # brak konca (np. WP) - poczatek nastepnej audycji
			if not b and i + 1 < len(progs):
				progs[i] = (a, progs[i + 1][0], t)
		out[x] = {"avg": int(chars / n) if n else 0, "days": max(0.0, (end - now) / 86400.0), "progs": progs}
	_cache[path] = (mt, out)
	return out


def file_progs(path, xid):
	"""Audycje jednej stacji z pliku XMLTV (.xz) do podgladu: od 3 h wstecz do 2 dni naprzod."""
	if not os.path.exists(path):
		return []
	import lzma
	import xml.etree.ElementTree as ET
	from .localgrab import parse_time
	now = time.time()
	out = []
	with lzma.open(path) as f:
		for ev, el in ET.iterparse(f):
			if el.tag == "programme":
				if el.get("channel") == xid:
					st, sp = parse_time(el.get("start")), parse_time(el.get("stop"))
					if st and (sp or st + 1800) > now - 3 * 3600 and st < now + 2 * 86400:
						out.append({"start": st, "stop": sp or 0, "title": (el.findtext("title") or "").strip(), "sub": "",
							"desc": (el.findtext("desc") or "").strip()[:500]})
				el.clear()
	out.sort(key=lambda p: p["start"])
	return out[:120]


def now_prog(progs, now=None):
	now = now or time.time()
	for st, sp, title in progs or []:
		if st <= now < (sp or st + 1800):
			return st, sp, title
	return None


def local_stats(targets):
	"""Dla mapy z dekodera: statystyki z pliku programu dekodera, potem z programu kandydatow z nocy."""
	from .localrun import out_dir
	files = [("plik", file_stats(out_dir() + "pl.xml.xz")), ("kandydaci", file_stats(out_dir() + "kandydaci.xml.xz"))]
	res = {}
	now = time.time()
	for t in targets:
		tag, sid = t.split(":", 1)
		for src, st in files:
			s = st.get(xid_of(tag, sid))
			if s and s["days"] > 0:
				p = now_prog(s["progs"], now)
				res[t] = {"avg": s["avg"], "days": s["days"], "now": list(p) if p else None, "src": src}
				break
	return res


def stats_from_preview(base, t):
	"""Statystyki z podgladu albo z pobrania na zadanie (pamietane 3 h)."""
	from .mapping import cached_preview, fill_stops
	d = cached_preview(base, t)
	progs = fill_stops([dict(p) for p in (d or {}).get("programmes", [])])
	if not progs:
		return None
	now = time.time()
	cur = [p for p in progs if p["start"] <= now < p["stop"]]
	return {"avg": int(sum(len(p.get("desc") or "") for p in progs) / len(progs)),
		"days": max(0.0, (progs[-1]["stop"] - now) / 86400.0),
		"now": [cur[0]["start"], cur[0]["stop"], cur[0]["title"]] if cur else None, "src": "teraz"}


def grab_window():
	"""(dni, wstecz) dla pobrania kandydatow teraz: w nocy od wczoraj, wieczorem z jutrem, inaczej sam dzien biezacy."""
	h = time.localtime().tm_hour
	if h < 6:
		return 2, 1
	return (2 if h >= 20 else 1), 0


def local_grab(stations, days, back, progress=None):
	"""Pobranie na dekoderze: stations = [(tag, site_id, nazwa)] -> {target: [audycje jak w podgladzie]}."""
	from .localgrab import LocalGrab
	st = [(t, sid, xid_of(t, sid), n) for t, sid, n in stations]
	g = LocalGrab(st, days=days, conns=dict((t, 2) for t, _, _ in stations), max_total=4, pause=0.3, back=back)
	if progress is not None:
		progress.append(g)
	g.run()
	out = {}
	for t, sid, xid, n in st:
		pr = g.result.get(xid) or []
		if pr:
			out["%s:%s" % (t, sid)] = [{"start": a, "stop": b, "title": ti, "sub": "", "desc": d or ""} for a, b, ti, d in pr]
	return out, g


def send_teraz(res, names):
	"""Program kandydatow pobrany na dekoderze na zadanie -> serwer epgpl (tylko stacje, ktorych serwer nie ma)."""
	try:
		from .mapping import epgpl_servers
		srv = epgpl_servers()
		if not srv or not res:
			return
		base = srv[0][0]
		have = server_stats(base, sorted(res)) or {}
		miss = [t for t in res if t not in have]
		if not miss:
			return
		import urllib.request
		from .localgrab import write_xmltv
		from .localrun import out_dir
		path = out_dir() + "teraz.xml.xz"
		result, nm = {}, {}
		for t in miss:
			tag, sid = t.split(":", 1)
			x = xid_of(tag, sid)
			result[x] = [(p["start"], p["stop"], p["title"], p.get("desc") or "") for p in res[t]]
			nm[x] = names.get(t, sid)
		write_xmltv(path, result, nm)
		req = urllib.request.Request(base + "api/teraz", data=open(path, "rb").read(),
			headers={"Content-Type": "application/octet-stream", "User-Agent": "EPGScan"})
		urllib.request.urlopen(req, timeout=60).read()
		os.remove(path)
		log("Program kandydatow pobrany na dekoderze wyslany na serwer: %d stacji" % len(miss))
	except Exception as e:
		log("Program kandydatow - wyslanie na serwer: %s" % e)


_prep = {}


def prep(name):
	"""norm / bez samoglosek / slowa nazwy stacji - liczone raz (lista kandydatow dla wszystkich kanalow)."""
	v = _prep.get(name)
	if v is None:
		from .mapping import norm, tokens
		n = norm(name)
		v = _prep[name] = (n, re.sub(r"[aeiouy]", "", n), tokens(name))
	return v


def candidate_stations(mapa, stacje):
	"""Wszyscy kandydaci (najlepsi i jednym slowem) dla kanalow mapy, bez stacji juz w pliku programu:
	[(tag, site_id, nazwa)] - pobierani nocą na dobe do podgladu w oknie wyboru stacji."""
	order = {t: i for i, t in enumerate(mapa.get("sites", []))}
	stations = [(tag, sid, name) for tag in mapa.get("sites", []) for sid, name in stacje.get(tag, [])]
	have = {(c.get("tag"), c.get("site_id")) for c in mapa.get("channels", []) if c.get("tag")}
	want = {}
	for c in mapa.get("channels", []):
		if c.get("off"):
			continue
		a, b = candidates(c, stations, order)
		for tag, sid, name in a + b:
			if (tag, sid) not in have:
				want[(tag, sid)] = name
	return [(t, s, n) for (t, s), n in sorted(want.items())]


def server_stats(base, targets):
	"""Dla mapy serwera: GET api/staty (epgpl 0.15.0+); starszy serwer - brak statystyk."""
	import urllib.parse
	from .mapping import http_get_json
	try:
		d = http_get_json(base + "api/staty?t=" + urllib.parse.quote(",".join(targets), safe=",:"), timeout=30)
		return d.get("stats") or {} if d.get("ok") else {}
	except Exception:
		return None


# ---------------------------------------------------------------- kandydaci
def candidates(chan, stations, order):
	"""(najlepsi, wspolne slowo): listy (tag, site_id, nazwa) - najlepsi wg podobienstwa, przy rownej ocenie
	w kolejnosci serwisow; 'jedno slowo' - stacje, z ktorymi kanal ma wspolne tylko jedno slowo nazwy."""
	from .mapping import norm, tokens, token_score
	k = norm(chan["name"])
	kt = tokens(chan["name"])
	ka = re.sub(r"[aeiouy]", "", k)
	strong, weak = [], []
	for tag, sid, name in stations:
		n, na, nt = prep(name)
		plain = difflib.SequenceMatcher(None, k, n).ratio()
		# skroty ("Nat. Geo. Wild"): porownanie bez samoglosek tylko przy podobnym zapisie - inaczej
		# "Historia" (hstr) pasowaloby do "Star" czy "Hustler"
		abbr = difflib.SequenceMatcher(None, ka, na).ratio() if plain >= 0.55 and len(ka) >= 4 else 0.0
		seq = max(plain, abbr)
		ts = token_score(kt, nt)                                                       # Historia -> Discovery Historia
		r = max(seq, ts)
		key = (-round(r * 20) / 20.0, order.get(tag, 9), name.lower())
		if plain >= 0.62 or abbr >= 0.8 or ts >= 0.8:
			strong.append(key + (tag, sid, name))
		elif ts > 0:                                         # wspolne slowo nazwy (albo History ~ Historia)
			weak.append(key + (tag, sid, name))
	strong.sort()
	weak.sort()

	def cap(rows, per_site, total):
		per, out = {}, []
		for x in rows:
			if per.get(x[3], 0) < per_site:
				per[x[3]] = per.get(x[3], 0) + 1
				out.append(x[3:])
		return out[:total]
	return cap(strong, 6, 24), cap(weak, 4, 16)


# ---------------------------------------------------------------- okno
class EPGScanStation(Screen):
	skin = """
	<screen position="center,center" size="1600,920" title="EPG Scan - wybor stacji">
		<widget name="head" position="20,15" size="740,150" font="Regular;26" foregroundColor="#00ffcc66" />
		<widget name="list" position="20,175" size="740,440" font="Regular;26" itemHeight="44" scrollbarMode="showOnDemand" />
		<eLabel position="778,13" size="804,454" backgroundColor="#00505050" />
		<widget source="session.VideoPicture" render="Pig" position="780,15" size="800,450" zPosition="1" backgroundColor="#ff000000" />
		<widget name="pig_info" position="780,475" size="800,140" font="Regular;24" foregroundColor="#c0c0c0" />
		<eLabel position="20,625" size="1560,2" backgroundColor="#00505050" />
		<widget name="info" position="20,635" size="1560,145" font="Regular;27" />
		%s
		%s
	</screen>""" % (hints_skin(20, 788, 1560, hints=HINTS), keys_skin(20, 850, 1560))

	def __init__(self, session, chan, stations, site_names, order, base=None, ref=None, side="dekoder"):
		"""chan - wiersz mapy (mapa.json), stations - [(tag, site_id, nazwa)], base - adres serwera albo None (dekoder).
		Zamkniecie: "tag:site_id" (wybrana stacja), "-" (bez programu), "" (automat) albo None (bez zmian)."""
		Screen.__init__(self, session)
		self.chan, self.stations, self.names, self.base, self.ref, self.side = chan, stations, site_names, base, ref, side
		self.order = {t: i for i, t in enumerate(order)}
		self.setTitle(_("Wybor stacji: %s") % chan["name"])
		cur = ("%s: %s  (%s)" % (self.site_name(chan["tag"]), chan["sname"], _("reczne") if chan.get("manual") else
			("alias" if "alias" in (chan.get("how") or "") else _("automat")))) if chan.get("tag") else \
			(_("bez programu") + (_(" (wylaczony recznie)") if chan.get("manual") else "") if chan.get("off") else _("brak dopasowania"))
		pr = chan.get("propose")
		self["head"] = Label(_("Kanal:    %s\nBukiet:   %s\nObecnie: %s\n%s") % (
			chan["name"], chan.get("bq") or "?", cur, ("[!] " + _(pr[0])) if pr else
			_("Kolejnosc serwisow: ") + ", ".join(self.site_name(t) for t in order)))
		self["list"] = MenuList([])
		self["pig_info"] = Label("")
		self["info"] = Label(_("Szukanie kandydatow..."))
		init_hints(self, HINTS)
		init_keys(self)
		set_keys(self, red=_("Wroc"), green=_("Automat"), yellow=_("Bez programu"), blue=_("Szukaj stacji"))
		self.stats = {}
		self.stats_state = "wait"
		self.search_text = ""
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions", "MenuActions", "EPGSelectActions"], {
			"ok": self.pick,
			"green": self.auto,
			"cancel": self.back,
			"red": self.back,
			"yellow": self.no_prog,
			"blue": self.search,
			"menu": self.menu,
			"info": self.preview,
		}, -1)
		self.orig = None
		self.zapped = False
		self.zap_timer = eTimer()
		self.zap_timer.callback.append(self.zap)
		self.check_timer = eTimer()
		self.check_timer.callback.append(self.check_tuner)
		self.poll_timer = eTimer()
		self.poll_timer.callback.append(self.poll)
		self.cand_timer = eTimer()
		self.cand_timer.callback.append(self.cand_tick)
		self.onClose.append(self.cleanup)
		self.onLayoutFinish.append(self.layout_done)

	def site_name(self, tag):
		return self.names.get(tag, tag)

	# --- lista ---
	def layout_done(self):
		try:
			self["list"].onSelectionChanged.append(self.show_info)
		except Exception:
			pass
		self.strong, self.weak = candidates(self.chan, self.stations, self.order)
		self.fill()
		self.load_stats([(t, s) for t, s, n in self.strong + self.weak])
		self.zap_timer.start(300, True)            # przelaczenie po pokazaniu okna
		if self.cand_busy() and CAND.get("base") == self.base:
			self.cand_timer.start(1000, False)

	def row(self, tag, sid, name):
		mark = ""
		c = self.chan
		if c.get("tag") == tag and c.get("site_id") == sid:
			mark = _("   [obecne]")
		elif self.chan.get("propose") and self.chan["propose"][1] == "%s:%s" % (tag, sid):
			mark = _("   [propozycja]")
		elif self.stats.get("%s:%s" % (tag, sid)):
			mark = _("   [w programie]") if self.stats["%s:%s" % (tag, sid)].get("src", "plik") == "plik" else _("   [podglad]")
		return ("%s: %s%s" % (self.site_name(tag), name, mark), (tag, sid, name))

	def fill(self, keep=None):
		c = self.chan
		rows = []
		if self.search_text:
			q = self.search_text
			rows.append((_("--  wyniki: %s  (niebieski: nowe szukanie, czerwony: wroc)  --") % q, None))
			rows += [self.row(*x) for x in self.found]
		else:
			best = list(self.strong)
			if c.get("tag"):                                                   # obecne zrodlo zawsze na samej gorze
				best = [(c["tag"], c["site_id"], c["sname"])] + [x for x in best if (x[0], x[1]) != (c["tag"], c["site_id"])]
			pr = c.get("propose")
			if pr and pr[1]:                                                   # propozycja zmiany - zaraz pod obecnym
				pt, ps = pr[1].split(":", 1)
				best = best[:1] + [(pt, ps, pr[2])] + [x for x in best[1:] if (x[0], x[1]) != (pt, ps)]
				self.weak = [x for x in self.weak if (x[0], x[1]) != (pt, ps)]
				self.weak = [x for x in self.weak if (x[0], x[1]) != (c["tag"], c["site_id"])]
			rows += [self.row(*x) for x in best]
			if self.weak:
				rows.append((_("--  wspolne slowo w nazwie  --"), None))
				rows += [self.row(*x) for x in self.weak]
			if not best and not self.weak:
				rows.append((_("--  brak podobnych stacji - niebieski: szukaj  --"), None))
		self["list"].setList(rows)
		idx = 0
		for i, r in enumerate(rows):
			if r[1] and (keep is None or r[1][:2] == keep):
				idx = i
				break
		try:
			self["list"].moveToIndex(idx)
		except Exception:
			pass
		self.show_info()

	def current(self):
		cur = self["list"].getCurrent()
		return cur[1] if cur else None

	def show_info(self):
		x = self.current()
		if not x:
			self["info"].setText(_("Wybierz stacje z listy (OK), zolty - kanal bez programu, niebieski - szukaj po nazwie."))
			return
		tag, sid, name = x
		t = "%s:%s" % (tag, sid)
		lines = [_("%s: %s   (identyfikator: %s)") % (self.site_name(tag), name, sid)]
		s = self.stats.get(t)
		if self.stats_state == "wait":
			lines.append(_("Statystyki: liczenie..."))
		elif s:
			span = (_("%s dni") % ("%.1f" % s["days"]).replace(".", ",")) if s["days"] >= 1 else ("%d h" % max(1, round(s["days"] * 24)))
			lines.append(_("Opisy: srednio %d znakow   |   program na %s%s") % (s["avg"], span, {
				"kandydaci": _("   (program kandydatow z nocy, tylko podglad)"), "teraz": _("   (pobrane teraz, tylko podglad)"),
				"serwer": _("   (z serwera epgpl, tylko podglad)")}.get(s.get("src"), "")))
			p = s.get("now")
			lines.append(_("Teraz: %s-%s  %s") % (time.strftime("%H:%M", time.localtime(p[0])),
				time.strftime("%H:%M", time.localtime(p[1])) if p[1] else "?", p[2]) if p else _("Teraz: -"))
		else:
			lines.append(_("Opisy: -   |   program: -   (%s)") % (
				_("brak statystyk - serwer starszy niz 0.15.0") if self.stats_state == "none" else
				_("brak programu tej stacji - Menu: pobierz program kandydatow, Info/EPG: podglad")))
		self["info"].setText("\n".join(lines))

	# --- statystyki w tle ---
	def load_stats(self, keys):
		targets = sorted({"%s:%s" % k for k in keys} | ({"%s:%s" % (self.chan["tag"], self.chan["site_id"])}
			if self.chan.get("tag") else set()))
		self.res = {}
		base = self.base

		def work():
			try:
				if base:
					self.res["data"] = server_stats(base, targets)
				else:
					d = local_stats(targets)
					miss = [t for t in targets if t not in d]
					from .mapping import epgpl_servers
					srv = epgpl_servers() if miss else []
					if srv:                            # mapa dekodera: stacje, ktore ma tylko serwer (kandydaci, pobrane na zadanie)
						for t, v in (server_stats(srv[0][0], miss) or {}).items():
							v["src"] = "serwer"
							d[t] = v
					self.res["data"] = d
			except Exception as e:
				self.res["error"] = str(e)
		self.th = threading.Thread(target=work)
		self.th.daemon = True
		self.th.start()
		self.stats_state = "wait"
		self.poll_timer.start(300, False)

	def poll(self):
		if self.th.is_alive():
			return
		self.poll_timer.stop()
		d = self.res.get("data")
		if d is None:
			self.stats_state = "none" if self.base else "err"
			if self.res.get("error"):
				log("Statystyki stacji: %s" % self.res["error"])
		else:
			self.stats.update(d)
			self.stats_state = "ok"
		self.add_cached()
		x = self.current()
		self.fill(keep=x[:2] if x else None)

	# --- okienko z kanalem ---
	def zap(self):
		if not self.ref:
			self["pig_info"].setText(_("Kanalu %s nie znaleziono w bukietach - okienko pokazuje biezacy kanal.") % self.chan["name"])
			return
		try:
			from enigma import eServiceReference
			cur = self.session.nav.getCurrentlyPlayingServiceOrGroup()
			if cur and cur.toString().startswith(self.ref.rstrip(":")):
				self["pig_info"].setText(_("W okienku: %s (na zywo).\nPorownaj z biezaca audycja pod lista.") % self.chan["name"])
				return
			self.orig = cur
			self.session.nav.playService(eServiceReference(self.ref))
			self.zapped = True
			self["pig_info"].setText(_("Przelaczanie na %s...") % self.chan["name"])
			self.check_timer.start(2500, True)
		except Exception as e:
			log("Wybor stacji - przelaczenie na %s: %s" % (self.ref, e))
			self["pig_info"].setText(_("Nie udalo sie przelaczyc na kanal: %s") % e)

	def check_tuner(self):
		ok = False
		try:
			svc = self.session.nav.getCurrentService()
			fe = svc and svc.frontendInfo()
			data = fe and fe.getFrontendData() or {}
			ok = bool(data) and data.get("tuner_state") not in ("FAILED", "LOSTLOCK", "IDLE")
		except Exception:
			ok = False
		if ok:
			self["pig_info"].setText(_("W okienku: %s (na zywo).\nPorownaj z biezaca audycja pod lista.\n"
				"Po wyjsciu wroci poprzedni kanal.") % self.chan["name"])
		else:
			self["pig_info"].setText(_("Nie mozna pokazac kanalu %s:\nbrak wolnego tunera (nagrywanie, skan, inne urzadzenie) "
				"albo brak sygnalu.\nWybor stacji dziala normalnie.") % self.chan["name"])

	def restore(self):
		try:
			import Screens.Standby
			if Screens.Standby.inStandby:
				self.zapped = False
				self.orig = None
				return
		except Exception:
			pass
		if self.zapped and self.orig is not None:
			try:
				self.session.nav.playService(self.orig)
			except Exception:
				pass
		self.zapped = False

	def cleanup(self):
		for t in (self.zap_timer, self.check_timer, self.poll_timer, self.cand_timer):
			t.stop()
		self.restore()

	# --- klawisze ---
	def pick(self):
		x = self.current()
		if not x:
			return
		self.close("%s:%s" % (x[0], x[1]))

	def no_prog(self):
		self.session.openWithCallback(lambda yes: yes and self.close("-"), MessageBox,
			_("Kanal %s bez programu z internetu?") % self.chan["name"], MessageBox.TYPE_YESNO, default=False)

	def back(self):
		if self.search_text:
			self.search_text = ""
			self.fill()
			return
		self.close(None)

	def menu(self):
		"""Menu: program zaznaczonej stacji albo wszystkich kandydatow bez programu; przy propozycji - jej pominiecie."""
		if self.cand_busy():
			self.session.open(MessageBox, _("Pobieranie programu kandydatow juz trwa."), MessageBox.TYPE_INFO, timeout=3)
			return
		missing = self.missing_targets()
		x = self.current()
		opts = []
		if x and "%s:%s" % (x[0], x[1]) not in self.stats:
			opts.append((_("Pobierz program zaznaczonej stacji: %s: %s") % (self.site_name(x[0]), x[2]), "one"))
		if missing:
			opts.append((_("Pobierz program wszystkich kandydatow bez programu (%d stacji)") % len(missing), "grab"))
		if self.chan.get("propose"):
			opts.append((_("Pomin propozycje zmiany (wroci, gdy wskazanie sie zmieni)"), "skip"))
		if not opts:
			self.session.open(MessageBox, _("Wszyscy kandydaci z listy maja juz program."), MessageBox.TYPE_INFO, timeout=3)
			return
		from .common import EPGChoiceBox as ChoiceBox
		self.session.openWithCallback(self.menu_done, ChoiceBox, title=self.chan["name"], list=opts)

	def auto(self):
		"""Zielony: automat - usuniecie recznego wskazania kanalu."""
		if not self.chan.get("manual"):
			self.session.open(MessageBox, _("Kanal %s nie ma recznego wskazania - zrodlo juz dobiera automat.") % self.chan["name"],
				MessageBox.TYPE_INFO, timeout=4)
			return
		self.session.openWithCallback(lambda yes: yes and self.close(""), MessageBox,
			_("Usunac reczne wskazanie kanalu %s?\nZrodlo dobierze automat.") % self.chan["name"], MessageBox.TYPE_YESNO, default=False)

	def menu_done(self, c):
		if not c:
			return
		if c[1] == "auto":
			self.session.openWithCallback(lambda yes: yes and self.close(""), MessageBox,
				_("Usunac reczne wskazanie kanalu %s?\nZrodlo dobierze automat.") % self.chan["name"], MessageBox.TYPE_YESNO, default=False)
		elif c[1] == "skip":
			from .sync import skip
			skip(self.side, self.chan)
			self.close(None)
		elif c[1] == "grab":
			self.cand_start(self.missing_targets())
		elif c[1] == "one":
			x = self.current()
			if x:
				self.cand_start([x])

	def missing_targets(self):
		rows = self.found if self.search_text else self.strong + self.weak
		return [(t, s, n) for t, s, n in rows if "%s:%s" % (t, s) not in self.stats]

	# --- program kandydatow teraz (w tle; okno mozna zamknac - wynik pamietany 3 h) ---
	def cand_busy(self):
		return bool(CAND.get("busy"))

	def cand_start(self, stations):
		days, back = grab_window()
		CAND.clear()
		CAND.update(busy=True, total=len(stations), done=0, base=self.base, t0=time.time(), err="")
		base = self.base

		def work():
			try:
				if base:
					from .mapping import http_post_json, http_get_json
					r = http_post_json(base + "api/kandydaci", {"targets": ["%s:%s" % (t, s) for t, s, n in stations]})
					if not r.get("ok"):
						raise Exception(r.get("error") or _("serwer nie przyjal (wymagany epgpl 0.16.0)"))
					while True:
						time.sleep(3)
						st = http_get_json(base + "api/kandydaci", timeout=15)
						CAND["done"] = st.get("done", 0)
						if not st.get("running"):
							break
				else:
					from .mapping import PREVIEW_CACHE
					prog = []
					CAND["grab"] = prog
					res, g = local_grab(stations, days, back, prog)
					now = time.time()
					for t, pr in res.items():
						PREVIEW_CACHE[("", t)] = (now, {"ok": True, "from": "pobrane teraz przez dekoder", "programmes": pr})
					send_teraz(res, dict(("%s:%s" % (t, s), n) for t, s, n in stations))
			except Exception as e:
				CAND["err"] = str(e)
			CAND["busy"] = False
		th = threading.Thread(target=work)
		th.daemon = True
		th.start()
		self.cand_timer.start(1000, False)
		self.cand_tick()

	def cand_tick(self):
		g = (CAND.get("grab") or [None])[0]
		done = g.done if g is not None else CAND.get("done", 0)
		total = g.total if g is not None else CAND.get("total", 0)
		el = int(time.time() - CAND.get("t0", time.time()))
		if CAND.get("busy"):
			self["pig_info"].setText(_("Pobieranie programu kandydatow (%s): %d/%d %s, %d:%02d\nMozna wybierac dalej albo zamknac okno - "
				"wynik bedzie pamietany 3 h.") % (_("serwer") if self.base else _("dekoder"), done, total,
				_("stacji") if self.base else _("stron"), el // 60, el % 60))
			return
		self.cand_timer.stop()
		if CAND.get("err"):
			self["pig_info"].setText(_("Pobieranie programu kandydatow nieudane:\n%s") % CAND["err"])
			return
		self["pig_info"].setText(_("Program kandydatow pobrany (%d:%02d). Stacje bez programu w serwisie zostaja z \"-\".") % (el // 60, el % 60))
		x = self.current()
		if self.base:
			self.load_stats([(t, s) for t, s, n in self.strong + self.weak + (self.found if self.search_text else [])])
		else:
			self.add_cached()
			self.fill(keep=x[:2] if x else None)

	def add_cached(self):
		"""Stacje z podgladu / pobrania na zadanie (dekoder: pamiec, 3 h)."""
		for t, s, n in self.strong + self.weak + (self.found if self.search_text else []):
			k = "%s:%s" % (t, s)
			if k not in self.stats:
				v = stats_from_preview(self.base, k)
				if v:
					self.stats[k] = v

	def search(self):
		from Screens.VirtualKeyBoard import VirtualKeyBoard
		self.session.openWithCallback(self.searched, VirtualKeyBoard, title=_("Szukaj stacji (fragment nazwy)"),
			text=self.search_text)

	def searched(self, text):
		if not text or not text.strip():
			return
		from .mapping import norm
		q = norm(text)
		low = text.strip().lower()
		found = [(t, s, n) for t, s, n in self.stations if (q and q in norm(n)) or low in n.lower()]
		if not found:
			self.session.open(MessageBox, _("Nie znaleziono stacji: %s") % text, MessageBox.TYPE_INFO, timeout=4)
			return
		found.sort(key=lambda x: (self.order.get(x[0], 9), x[2].lower()))
		self.found = found[:60]
		self.search_text = text.strip()
		self.fill()
		self.load_stats([(t, s) for t, s, n in self.found if "%s:%s" % (t, s) not in self.stats])

	def preview(self):
		x = self.current()
		if not x:
			return
		from .mapping import EPGScanPreview
		t = "%s:%s" % (x[0], x[1])
		pbase = self.base
		if not pbase and (self.stats.get(t) or {}).get("src") == "serwer":
			from .mapping import epgpl_servers
			srv = epgpl_servers()
			pbase = srv[0][0] if srv else None        # stacja z programem tylko na serwerze - podglad z serwera
		self.session.openWithCallback(lambda save=False: self.preview_done(t, save), EPGScanPreview, pbase, t,
			"%s: %s" % (self.site_name(x[0]), x[2]), self.chan["name"], True, None, None, (-2 * 3600, 4 * 3600))


def _preview_done(self, t, save):
	"""Po podgladzie: zapis albo statystyki z pobranego podgladu (pamietane 3 h - ponowny podglad bez pobierania)."""
	if save:
		self.close(t)
		return
	if t not in self.stats:
		v = stats_from_preview(self.base, t)
		if v:
			self.stats[t] = v
			x = self.current()
			self.fill(keep=x[:2] if x else None)


EPGScanStation.preview_done = _preview_done


from .common import scale_skins as _scale_skins  # noqa: E402
_scale_skins(globals())
