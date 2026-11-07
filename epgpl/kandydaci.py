#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - kandydaci do okna wyboru stacji (EPG Scan): dla kazdego kanalu z mapy te same stacje, ktore pokazuje
# okno na dekoderze (najlepsi i zgodni jednym slowem), bez stacji juz pobieranych do pliku programu.
# Wynik: <katalog>/<serwis>.channels.xml (format iptv-org) - run.sh pobiera dla nich program na dobe.
# Uzycie: kandydaci.py mapa.json stacje.json katalog
# Dopasowanie jak w EPG Scan (station.py: candidates) - zmiany trzeba robic w obu miejscach.
import difflib, json, os, re, sys, unicodedata
from xml.sax.saxutils import escape, quoteattr

SITE_OF = {"onet": "programtv.onet.pl", "tm": "teleman.pl", "wp": "pilot.wp.pl", "naz": "programtv.naziemna.info"}
DROP = {"hd", "uhd", "4k", "sd", "fhd", "poland", "channel", "tv"}
STOPW = DROP | {"polska", "pl", "kanal", "the", "hevc", "h265", "extra"}


def ascii_low(name):
    return unicodedata.normalize("NFKD", (name or "").lower().replace("ł", "l")).encode("ascii", "ignore").decode()


def norm(name):
    s = ascii_low(name).replace("+", " ").replace("&", " and ")
    s = re.sub(r"\btv\s+(\d+)(?![0-9a-z])", r"tv\1", s)
    return "".join(t for t in re.split(r"[^a-z0-9]+", s) if t and t not in DROP)


def tokens(name):
    s = ascii_low(name).replace("+", " plus ").replace("&", " and ")
    return [t for t in re.split(r"[^a-z0-9]+", s) if t and t not in STOPW]


def token_score(kt, nt):
    if not kt or not nt:
        return 0.0
    tot = float(sum(len(t) for t in kt))
    got = 0.0
    for t in kt:
        best = 0.0
        for u in nt:
            if t == u:
                best = 1.0
                break
            if t.isdigit() or u.isdigit():
                continue
            cp = len(os.path.commonprefix([t, u]))
            if cp >= 5 and cp >= 0.7 * min(len(t), len(u)):
                best = max(best, 0.8)
        got += best * len(t)
    return 0.45 + 0.45 * got / tot if got else 0.0


def candidates(name, prepared, order):
    k = norm(name)
    kt = tokens(name)
    ka = re.sub(r"[aeiouy]", "", k)
    strong, weak = [], []
    for tag, sid, sname, n, na, nt in prepared:
        plain = difflib.SequenceMatcher(None, k, n).ratio()
        abbr = difflib.SequenceMatcher(None, ka, na).ratio() if plain >= 0.55 and len(ka) >= 4 else 0.0
        seq = max(plain, abbr)
        ts = token_score(kt, nt)
        r = max(seq, ts)
        key = (-round(r * 20) / 20.0, order.get(tag, 9), sname.lower())
        if plain >= 0.62 or abbr >= 0.8 or ts >= 0.8:
            strong.append(key + (tag, sid, sname))
        elif ts > 0:
            weak.append(key + (tag, sid, sname))
    strong.sort()
    weak.sort()

    def cap(rows, per_site, total):
        per, out = {}, []
        for x in rows:
            if per.get(x[3], 0) < per_site:
                per[x[3]] = per.get(x[3], 0) + 1
                out.append(x[3:])
        return out[:total]
    return cap(strong, 6, 24) + cap(weak, 4, 16)


def main():
    mapa = json.load(open(sys.argv[1], encoding="utf-8"))
    stacje = json.load(open(sys.argv[2], encoding="utf-8"))
    out = sys.argv[3]
    sites = [t for t in mapa.get("sites", []) if t in SITE_OF]
    order = {t: i for i, t in enumerate(sites)}
    prepared = []
    for tag in sites:
        for sid, sname in stacje.get(tag, []):
            n = norm(sname)
            prepared.append((tag, sid, sname, n, re.sub(r"[aeiouy]", "", n), tokens(sname)))
    have = {(c.get("tag"), c.get("site_id")) for c in mapa.get("channels", []) if c.get("tag")}
    want = {}
    for c in mapa.get("channels", []):
        if c.get("off"):
            continue
        for tag, sid, sname in candidates(c["name"], prepared, order):
            if (tag, sid) not in have:
                want[(tag, sid)] = sname
    os.makedirs(out, exist_ok=True)
    by = {}
    for (tag, sid), sname in sorted(want.items()):
        by.setdefault(tag, []).append((sid, sname))
    for tag, lst in by.items():
        site = SITE_OF[tag]
        with open(os.path.join(out, site + ".channels.xml"), "w", encoding="utf-8") as f:
            f.write('<?xml version="1.0" encoding="UTF-8"?>\n<channels>\n')
            for sid, sname in lst:
                xid = "%s.%s" % (tag, re.sub(r"[^A-Za-z0-9-]", "_", sid))
                f.write('  <channel site="%s" site_id=%s lang="pl" xmltv_id="%s">%s</channel>\n' % (
                    site, quoteattr(sid), xid, escape(sname)))
            f.write("</channels>\n")
    print(len(want), " ".join("%s:%d" % (t, len(l)) for t, l in sorted(by.items())))


if __name__ == "__main__":
    main()
