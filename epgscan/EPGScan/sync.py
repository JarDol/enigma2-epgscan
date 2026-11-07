# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - porownanie i synchronizacja dopasowania kanalow: mapa dekodera <-> mapa serwera epgpl.
# Roznica = inne zrodlo programu kanalu albo reczne wskazanie tylko po jednej stronie.
# Synchronizacja kopiuje wskazania strony "wygrywajacej" na druga (dekoder: wskazania.txt, serwer: POST api/alias).


def src_of(c):
	"""Zrodlo kanalu z mapy: "tag:site_id", "-" (bez programu) albo "" (brak dopasowania)."""
	if c.get("off"):
		return "-"
	return "%s:%s" % (c["tag"], c["site_id"]) if c.get("tag") else ""


def pin_row(name, target):
	"""Wiersz jak w mapie z samego wskazania (kanal spoza wspolnej mapy): target albo None (brak wskazania)."""
	r = {"name": name, "manual": target is not None, "pin": target, "tag": "", "site_id": "", "sname": "", "off": target == "-"}
	if target and ":" in target:
		r["tag"], r["site_id"] = target.split(":", 1)
		r["sname"] = r["site_id"]
	elif target and target != "-":
		r["sname"] = "= " + target              # wskazanie inna nazwa
	return r


def diffs(dek, srv, dek_pins=None, srv_pins=None):
	"""Roznice: [{name, bq, dek, srv}] - kanaly obecne w obu mapach z innym zrodlem albo recznym wskazaniem tylko
	po jednej stronie, a gdy podane wskazania obu stron (wskazania.txt) - takze rozne wskazania kanalow spoza
	wspolnej mapy (np. bukiet tylko po jednej stronie)."""
	a = dict((c["name"], c) for c in (dek or {}).get("channels", []))
	b = dict((c["name"], c) for c in (srv or {}).get("channels", []))
	out = []
	for name in sorted(set(a) & set(b), key=str.lower):
		x, y = a[name], b[name]
		if src_of(x) != src_of(y) or bool(x.get("manual")) != bool(y.get("manual")):
			out.append({"name": name, "bq": x.get("bq") or y.get("bq"), "dek": x, "srv": y})
	if dek_pins is not None and srv_pins is not None:
		both = set(a) & set(b)
		for name in sorted(set(dek_pins) | set(srv_pins), key=str.lower):
			if name in both or dek_pins.get(name) == srv_pins.get(name):
				continue
			out.append({"name": name, "bq": "poza wspolna mapa", "dek": pin_row(name, dek_pins.get(name)),
				"srv": pin_row(name, srv_pins.get(name)), "pins_only": True})
	return out


def plan(items, winner):
	"""Zmiany wskazan: (dla dekodera, dla serwera) - {nazwa: cel}, "" = usun reczne wskazanie.
	Wygrywa reczne wskazanie - kopiowane na druga strone. Wygrywa automat: druga strona z recznym wskazaniem
	traci je (wraca automat); gdy obie strony maja automat, a zrodla sa rozne (inne listy stacji) - zrodlo
	zwycieskiej strony jest przypinane po obu stronach."""
	win, lose = {}, {}
	for d in items:
		w, l = (d["dek"], d["srv"]) if winner == "dekoder" else (d["srv"], d["dek"])
		if d.get("pins_only"):                     # same wskazania (kanal spoza wspolnej mapy) - kopia 1:1
			lose[d["name"]] = w["pin"] or ""
			continue
		ws, ls = src_of(w), src_of(l)
		if w.get("manual"):
			if ws != ls or not l.get("manual"):
				lose[d["name"]] = ws
		elif l.get("manual"):
			lose[d["name"]] = ""                    # automat wygrywa - druga strona wraca do automatu
		elif ws != ls and ws:
			lose[d["name"]] = ws                    # dwa automaty, rozne zrodla - przypiecie po obu stronach
			win[d["name"]] = ws
	return (win, lose) if winner == "dekoder" else (lose, win)


def apply_local(changes):
	"""Wskazania na dekoderze (jedno zapisanie, jedno przeliczenie mapy)."""
	from .localmap import load_pins, save_pins
	from .mapping import local_map_job
	pins = load_pins()
	for name, t in changes.items():
		if t:
			pins[name] = t
		else:
			pins.pop(name, None)
	save_pins(pins)
	local_map_job(rebuild=True)
	return {"ok": True, "applied": len(changes), "errors": []}


def apply_server(base, changes):
	"""Wskazania na serwerze: wszystkie naraz (epgpl 0.17.0+), starszy serwer - po jednym."""
	from .mapping import http_post_json
	items = [{"name": n, "target": t} for n, t in changes.items()]
	r = http_post_json(base + "api/alias", {"items": items})
	if r.get("ok") and "applied" in r:
		return r
	import time
	ok, errors = 0, []
	for it in items:
		x = http_post_json(base + "api/alias", it)
		if x.get("ok"):
			ok += 1
		else:
			errors.append({"name": it["name"], "error": x.get("error") or "?"})
		time.sleep(0.5)
	return {"ok": ok > 0 or not items, "applied": ok, "errors": errors}


def server_base():
	from .mapping import epgpl_servers
	s = epgpl_servers()
	return s[0][0] if s else None


def pins_both(base):
	"""(wskazania dekodera, wskazania serwera) albo (None, None) - serwer starszy niz 0.19.3."""
	from .localmap import load_pins
	from .mapping import http_get_json
	try:
		r = http_get_json(base + "api/wskazania", timeout=15)
		if r.get("ok"):
			return dict(load_pins()), r.get("pins") or {}
	except Exception:
		pass
	return None, None


def load_both(base):
	"""(mapa dekodera, mapa serwera) - do porownania."""
	from .mapping import local_map_job, http_get_json
	dek = local_map_job()[0]
	srv = http_get_json(base + "mapa.json")
	return dek, srv


def side_text(d, side, site_names):
	"""Zrodlo jednej strony roznicy (side = "dek" | "srv") jako tekst."""
	c = d[side]
	if d.get("pins_only"):
		p = c.get("pin")
		return "brak wskazania" if p is None else ("bez programu" if p == "-" else p)
	s = src_of(c)
	if s == "-":
		t = "bez programu"
	elif not s:
		t = "brak dopasowania"
	else:
		t = "%s: %s" % (site_names.get(c["tag"], c["tag"]), c.get("sname") or c["site_id"])
	return t + ("  [reczne]" if c.get("manual") else "  [automat]")


def row_text(d, site_names):
	def one(c):
		s = src_of(c)
		if s == "-":
			t = "bez programu"
		elif not s:
			t = "brak"
		else:
			t = "%s: %s" % (site_names.get(c["tag"], c["tag"]), c.get("sname") or c["site_id"])
		return t + (" [r]" if c.get("manual") else "")

	def pin(c):
		p = c.get("pin")
		return "brak wskazania" if p is None else ("bez programu" if p == "-" else p)
	if d.get("pins_only"):
		return "%s (poza wspolna mapa)  |  dek: %s  |  srv: %s" % (d["name"], pin(d["dek"]), pin(d["srv"]))
	return "%s  |  dek: %s  |  srv: %s" % (d["name"], one(d["dek"]), one(d["srv"]))


# ---------------------------------------------------------------- propozycje zmian recznych wskazan
SKIP_FILE = "/etc/enigma2/epgscan/propozycje_pominiete.txt"


def skipped():
	"""{strona|kanal: wskazanie w chwili pominiecia} - propozycja wraca, gdy wskazanie sie zmieni."""
	out = {}
	try:
		for ln in open(SKIP_FILE, encoding="utf-8"):
			if "=" in ln:
				k, v = ln.rstrip("\n").split("=", 1)
				out[k.strip()] = v.strip()
	except Exception:
		pass
	return out


def skip(side, c):
	d = skipped()
	d["%s|%s" % (side, c["name"])] = src_of(c)
	try:
		import os
		if not os.path.isdir(os.path.dirname(SKIP_FILE)):
			os.makedirs(os.path.dirname(SKIP_FILE))
		with open(SKIP_FILE, "w", encoding="utf-8") as f:
			f.write("# EPG Scan - pominiete propozycje zmian (strona|kanal = wskazanie w chwili pominiecia)\n")
			for k in sorted(d):
				f.write("%s = %s\n" % (k, d[k]))
	except Exception:
		pass


def proposals(mapa, stacje, side):
	"""Kanaly z recznym wskazaniem stacji, ktore warto sprawdzic: {nazwa: (powod, "tag:site_id" albo "")}.
	- wskazanej stacji nie ma juz w liscie stacji serwisu,
	- wskazana stacja nie dala programu w ostatnim pobraniu,
	- jest stacja o dokladnie tej samej nazwie co kanal, a wskazana ma inna."""
	from .mapping import norm
	from .station import candidates, xid_of
	order = {t: i for i, t in enumerate(mapa.get("sites", []))}
	stations = [(tag, sid, name) for tag in mapa.get("sites", []) for sid, name in (stacje or {}).get(tag, [])]
	ids = set((t, s) for t, s, n in stations)
	by_norm = {}
	for t, s, n in stations:
		by_norm.setdefault(norm(n), []).append((order.get(t, 9), t, s, n))
	empty = set(mapa.get("empty") or [])
	sk = skipped()
	out = {}
	for c in mapa.get("channels", []):
		if not c.get("manual") or c.get("off") or not c.get("tag"):
			continue
		if sk.get("%s|%s" % (side, c["name"])) == src_of(c):
			continue
		cur = (c["tag"], c["site_id"])

		def best():
			a, b = candidates(c, stations, order)
			for t, s, n in a:
				if (t, s) != cur and xid_of(t, s) not in empty:
					return "%s:%s" % (t, s), n
			return "", ""
		if stations and cur not in ids:
			t, n = best()
			out[c["name"]] = ("wskazanej stacji nie ma juz w serwisie", t, n)
		elif xid_of(*cur) in empty:
			t, n = best()
			out[c["name"]] = ("wskazana stacja nie dala programu w ostatnim pobraniu", t, n)
		else:
			k = norm(c["name"])
			if k and norm(c.get("sname") or "") != k and by_norm.get(k):
				o, t, s, n = sorted(by_norm[k])[0]
				if (t, s) != cur:
					out[c["name"]] = ("jest stacja o tej samej nazwie co kanal", "%s:%s" % (t, s), n)
	return out


# ---------------------------------------------------------------- nowy serwer: ustawienia i porownanie wskazan
class NewServer(object):
	"""Po dodaniu serwera epgpl: wyslanie ustawien dekodera (bukiety, kolejnosc serwisow), porownanie map
	i - wg ustawienia "Roznice w dopasowaniu" - pytanie albo synchronizacja wskazan."""

	def __init__(self, session, base):
		from .mapping import Background
		self.session, self.base = session, base if base.endswith("/") else base + "/"
		self.bg = Background()

	def start(self):
		from .mapping import server_bq_job, local_map_job
		base = self.base

		def job():
			srv = server_bq_job(base)[0]
			dek = local_map_job()[0]
			return diffs(dek, srv, *pins_both(base))
		self.bg.run(job, self.compared)

	def compared(self, data, err):
		from .common import cfg, log
		from Screens.MessageBox import MessageBox
		from .common import EPGChoiceBox as ChoiceBox
		if err or data is None:
			log("Nowy serwer epgpl %s - porownanie dopasowania: %s" % (self.base, err))
			return
		self.items = data
		log("Nowy serwer epgpl %s: roznic w dopasowaniu %d" % (self.base, len(data)))
		if not data:
			return
		pol = cfg.map_diff.value
		if pol in ("dekoder", "serwer"):
			self.apply(pol)
			return
		if pol == "keep":
			return
		man_d = len([1 for d in data if d["dek"].get("manual")])
		man_s = len([1 for d in data if d["srv"].get("manual")])
		self.session.openWithCallback(lambda c: c and c[1] and self.apply(c[1]), ChoiceBox,
			title="Nowy serwer epgpl: dopasowanie kanalow rozni sie od dekodera w %d kanalach "
				"(recznych wskazan: dekoder %d, serwer %d)" % (len(data), man_d, man_s), list=[
				("Wygrywa dekoder - serwer dostaje wskazania dekodera", "dekoder"),
				("Wygrywa serwer - dekoder dostaje wskazania serwera", "serwer"),
				("Zostaw rozne (pozniej: Dopasowanie kanalow -> Info/EPG)", None)])

	def apply(self, winner):
		from .common import log
		for_dek, for_srv = plan(self.items, winner)
		base = self.base

		def job():
			res = {"applied": 0, "errors": []}
			if for_srv:
				r = apply_server(base, for_srv)
				res["applied"] += r.get("applied", 0)
				res["errors"] += r.get("errors") or []
			if for_dek:
				res["applied"] += apply_local(for_dek).get("applied", 0)
			return res

		def done(data, err):
			from Screens.MessageBox import MessageBox
			errs = (data or {}).get("errors") or []
			log("Nowy serwer epgpl - synchronizacja (wygrywa %s): zmian %d, bledow %d" % (winner, (data or {}).get("applied", 0), len(errs)))
			msg = "Synchronizacja z nowym serwerem (wygrywa %s): zmienionych wskazan %d." % (winner, (data or {}).get("applied", 0))
			if err:
				msg = "Synchronizacja z nowym serwerem nieudana: %s" % err
			elif errs:
				msg += "\nNie przeniesione (%d): %s" % (len(errs), ", ".join(e["name"] for e in errs[:8]))
			self.session.open(MessageBox, msg, MessageBox.TYPE_ERROR if err else MessageBox.TYPE_INFO, timeout=8)
		self.bg.run(job, done)


_NEW = []


def new_server(session, url):
	"""Wywolanie po dodaniu listy zrodel serwera epgpl (adres .../epgpl.sources.xml)."""
	ns = NewServer(session, url.rsplit("/", 1)[0] + "/")
	_NEW[:] = [ns]                     # obiekt zyje do konca (timer w tle)
	ns.start()
