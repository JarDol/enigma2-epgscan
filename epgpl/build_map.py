#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - budowa mapy kanalow: program z onet.pl / canalplus.pl -> referencje kanalow z satelity
# Wejscie: lista kanalow z odbiornika (OpenWebif /api/getallservices), pliki *.channels.xml z iptv-org/epg,
#          aliases.txt (reczne dopasowania).
# Wyjscie: grab.channels.xml (co pobrac), pl.channels.xml (mapa id -> wszystkie referencje), raport.txt
import argparse
import collections
import json
import os
import re
import sys
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

try:
    VERSION = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "VERSION")).read().strip()
except Exception:
    VERSION = "?"
TV_TYPES = {"1", "11", "16", "19", "1F", "86", "D3"}   # typy uslug TV (radio=2, dane=C pomijane)
DROP = {"hd", "uhd", "4k", "sd", "fhd", "poland", "channel", "tv"}
ALL_SITES = [("onet", "programtv.onet.pl", "sites/programtv.onet.pl/programtv.onet.pl.channels.xml"),
             ("tm", "teleman.pl", "sites/teleman.pl/teleman.pl.channels.xml"),
             ("wp", "pilot.wp.pl", "sites/pilot.wp.pl/pilot.wp.pl.channels.xml"),
             ("naz", "programtv.naziemna.info", "sites/programtv.naziemna.info/programtv.naziemna.info.channels.xml"),
             ("cp", "canalplus.com", "sites/canalplus.com/canalplus.com_pl.channels.xml")]
TAGS = {"onet", "tm", "wp", "naz", "cp"}
SITE_ALIASES = {"teleman": "tm", "canalplus": "cp", "pilot": "wp", "naziemna": "naz"}
SITES = ALL_SITES


def toks(name):
    s = unicodedata.normalize("NFKD", name.lower().replace("ł", "l")).encode("ascii", "ignore").decode()
    s = s.replace("+", " ").replace("&", " and ")
    s = re.sub(r"\btv\s+(\d+)(?![0-9a-z])", r"tv\1", s)          # "TV 4" = "TV4" (inaczej zostaloby samo "4")
    tk = [t for t in re.split(r"[^a-z0-9]+", s) if t and t not in DROP]
    # "WarnerTV" = "Warner TV", "EskaTV" = "Eska TV": doklejone "tv" na koncu dluzszego slowa (nie MTV, TVN, TVP)
    return [t[:-2] if len(t) > 4 and t.endswith("tv") and not t[:-2].isdigit() else t for t in tk]


def norm(name):
    return "".join(toks(name))


# --- rozszerzenie nazwy: cala nazwa z satelity = poczatek nazwy w serwisie ---
# "TVP 3 Gorzow" -> "TVP3 Gorzow Wlkp." / "TVP 3 Gorzow Wielkopolski". Liczby musza sie zgadzac dokladnie
# (Eurosport 3 nie pasuje do Eurosport 1), dopisek w serwisie nie moze byc liczba ani slowem wersji kanalu
# (Extra, Premium, Kids...), a w danym serwisie musi byc dokladnie jeden taki kanal.
VARIANT = {"extra", "xtra", "premium", "plus", "max", "family", "kids", "junior", "news", "sport", "sports", "film",
           "films", "life", "fight", "live", "info", "gold", "classic", "hits", "int", "international", "polska",
           "east", "west", "nord", "sud", "rozrywka", "seriale", "dokument", "historia", "kultura", "comedy", "action",
           "music", "xxl", "mix", "one", "two", "start", "fun", "hit", "series", "cinema", "kino", "premiere", "ultra", "wild", "people", "science", "turbo", "home", "travel", "women", "men", "baby"}


def ctoks(name):
    """Slowa do porownania, z rozdzielonymi literami i cyframi ("tvp3" = "tvp 3")."""
    return [x for t in toks(name) for x in re.findall(r"[a-z]+|[0-9]+", t)]


def tok_ok(s, t):
    if s == t:
        return True
    if s.isdigit() or t.isdigit() or len(s) < 3 or s[0] != t[0]:
        return False
    if t.startswith(s):
        return True                                   # "Gorz" = "Gorzow"
    it = iter(t)
    return all(ch in it for ch in s)                  # skrot: "wlkp" = "wielkopolski"


def extends(st, tt):
    if len(st) < 2 or len(tt) < len(st) or not any(x.isalpha() and len(x) >= 3 for x in st):
        return False
    if not all(tok_ok(a, b) for a, b in zip(st, tt)):
        return False
    return all(x.isalpha() and x not in VARIANT for x in tt[len(st):])


def ref10(ref):
    return ":".join(ref.split(":")[:10]) + ":"


def is_tv(ref):
    p = ref.split(":")
    return len(p) >= 10 and p[0] == "1" and p[1] == "0" and p[2].upper() in TV_TYPES and p[3] != "0"


def load_services(src):
    if src.startswith("http"):
        with urllib.request.urlopen(src, timeout=60) as r:
            data = json.loads(r.read().decode("utf-8", "ignore"))
    else:
        with open(src, encoding="utf-8") as f:
            data = json.load(f)
    return data["services"]


def load_site(root, tag, site, path):
    out = {}
    for c in ET.parse("%s/%s" % (root, path)).getroot():
        name = (c.text or "").strip()
        k = norm(name)
        if not k:
            continue
        item = {"tag": tag, "site": site, "site_id": c.get("site_id"), "name": name, "lang": c.get("lang")}
        # przy kilku wersjach (SD/HD) zostaje pierwsza - ten sam program; kanal oznaczony jako polski ma pierwszenstwo
        old = out.get(k)
        if old is None or (old["lang"] != "pl" and item["lang"] == "pl"):
            out[k] = item
    return out


def xid_of(it):
    return "%s.%s" % (it["tag"], re.sub(r"[^A-Za-z0-9-]", "_", it["site_id"]))


def load_aliases(paths, sites_by_id):
    al = {}
    lines = []
    for path in paths:              # kolejne pliki nadpisuja wczesniejsze (aliases.txt uzytkownika na koncu)
        try:
            lines += open(path, encoding="utf-8").read().splitlines()
        except FileNotFoundError:
            pass
    for ln in lines:
        ln = ln.split("#", 1)[0].strip()
        if "=" not in ln:
            continue
        name, target = [x.strip() for x in ln.split("=", 1)]
        if target == "-":
            al[norm(name)] = None          # swiadomie bez programu
            continue
        if target.split(":", 1)[0] not in TAGS:
            al[norm(name)] = ("name", norm(target))   # inna nazwa - zrodlo wg kolejnosci SITES
            continue
        it = sites_by_id.get(target)
        if it is None and target.split(":")[0] in sites_by_id.get("_off", ()):
            continue                       # zrodlo wylaczone w config.env
        if it:
            al[norm(name)] = it
        else:
            print("aliases.txt: nieznany kanal %s (%s)" % (target, name), file=sys.stderr)
    return al


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--services", required=True, help="adres /api/getallservices albo plik json")
    ap.add_argument("--epgrepo", required=True, help="katalog iptv-org/epg")
    ap.add_argument("--aliases", action="append", help="pliki aliasow (mozna podac kilka, ostatni wygrywa)")
    ap.add_argument("--copies", default="tp", choices=["tp", "all"],
                    help="tp: kopie kanalow tylko z transponderow z Twoimi kanalami; all: wszystkie kopie")
    ap.add_argument("--empty", default="", help="empty.json - kanaly bez danych w poprzednim przebiegu (pomijane w tym zrodle)")
    ap.add_argument("--sites", default="teleman,onet,wp,naz", help="zrodla programu w kolejnosci pierwszenstwa: teleman,onet,wp,naz,cp")
    ap.add_argument("--scope", default="bouquets", choices=["bouquets", "all"])
    ap.add_argument("--skip-bouquets", default="Last Scanned")
    ap.add_argument("--tuner-aliases", default="", help="wskazania.txt - wskazania z tunera (najwyzszy priorytet)")
    ap.add_argument("--bouquets", default="", help="bukiety.json z dekodera: {\"mode\": \"all\"|\"selected\", \"names\": [...]}")
    ap.add_argument("--out", default=".")
    a = ap.parse_args()
    global SITES
    use = [SITE_ALIASES.get(x.strip(), x.strip()) for x in a.sites.split(",") if x.strip()]
    known = {x[0]: x for x in ALL_SITES}
    SITES = [known[t] for t in use if t in known]     # kolejnosc z config.env = pierwszenstwo

    services = load_services(a.services)
    skip = {x.strip() for x in a.skip_bouquets.split(",") if x.strip()}
    only = None              # bukiety wybrane na dekoderze (EPG Scan -> Dopasowanie kanalow -> Lista)
    try:
        bq = json.load(open(a.bouquets, encoding="utf-8")) if a.bouquets and os.path.exists(a.bouquets) else {}
        if bq.get("mode") == "selected":
            only = set(bq.get("names") or [])
    except Exception:
        only = None
    sat = []            # wszystkie uslugi TV z odbiornika (wszystkie bukiety, w tym Last Scanned)
    mine = []           # kanaly z bukietow uzytkownika
    for b in services:
        for s in b.get("subservices", []):
            ref = s.get("servicereference", "")
            if not is_tv(ref):
                continue
            sat.append((s["servicename"], ref10(ref)))
            if b["servicename"] not in skip and (only is None or b["servicename"] in only):
                mine.append((b["servicename"], s["servicename"], ref10(ref)))

    sites = {}
    by_id = {}
    for tag, site, path in SITES:
        if not os.path.exists("%s/%s" % (a.epgrepo, path)):
            print("brak listy kanalow %s - pomijam to zrodlo" % site, file=sys.stderr)
            continue
        d = load_site(a.epgrepo, tag, site, path)
        sites[tag] = d
        for it in d.values():
            by_id["%s:%s" % (tag, it["site_id"])] = it
    # wszystkie wersje (rowniez HD) po site_id - aliasy moga wskazac dowolna
    for tag, site, path in SITES:
        if tag not in sites:
            continue
        for c in ET.parse("%s/%s" % (a.epgrepo, path)).getroot():
            by_id.setdefault("%s:%s" % (tag, c.get("site_id")),
                             {"tag": tag, "site": site, "site_id": c.get("site_id"), "name": (c.text or "").strip(), "lang": c.get("lang")})
    by_id["_off"] = {x[0] for x in ALL_SITES} - {x[0] for x in SITES}
    alias_files = list(a.aliases or ["aliases.txt"])
    tuner_keys = set()
    if a.tuner_aliases:
        alias_files.append(a.tuner_aliases)
        tuner_keys = set(load_aliases([a.tuner_aliases], by_id))
    aliases = load_aliases(alias_files, by_id)

    empty = {}
    if a.empty:
        try:
            empty = json.load(open(a.empty, encoding="utf-8"))
        except Exception:
            empty = {}

    def lookup(*keys):
        """Pierwszy serwis wg kolejnosci SITES, ktory ma kanal pod ktoras z nazw (alias albo nazwa z satelity)."""
        first = None
        for tag, _, _ in SITES:
            for k in keys:
                it = sites.get(tag, {}).get(k)
                if it:
                    if xid_of(it) not in empty:
                        return it, tag + (" (zamiast %s - brak danych)" % first["tag"] if first else "")
                    first = first or it
        if first:
            return first, first["tag"] + " (brak danych w poprzednim przebiegu)"
        return None, None

    def find(name):
        k = norm(name)
        if k in aliases:
            it = aliases[k]
            if isinstance(it, tuple):
                # alias nazwy: nazwa z aliasu i nazwa z satelity sa rownorzedne - decyduje kolejnosc serwisow
                # (serwis moze w miedzyczasie zmienic nazwe na taka jak na satelicie)
                it2, how = lookup(it[1], k)
                if it2 and norm(it2["name"]) == k:
                    return it2, how            # nazwa w serwisie taka jak na satelicie - alias niepotrzebny
                return it2, (how + " (alias)") if it2 else how
            elif it is None or xid_of(it) not in empty:
                return it, "alias"
        it, how = lookup(k)
        if it:
            return it, how
        return lookup_ext(name)

    ext_index = {tag: [(ctoks(it["name"]), it) for it in d.values()] for tag, d in sites.items()}

    def lookup_ext(name):
        """Brak dokladnej nazwy: kanal, ktorego nazwa w serwisie zaczyna sie cala nazwa z satelity."""
        st = ctoks(name)
        first = None
        for tag, _, _ in SITES:
            cand = [it for tt, it in ext_index.get(tag, []) if extends(st, tt)]
            if len(cand) != 1:
                continue                              # brak albo niejednoznaczne - nie zgadujemy
            it = cand[0]
            note = " (rozszerzenie nazwy)"
            if xid_of(it) not in empty:
                return it, tag + note + (" (zamiast %s - brak danych)" % first["tag"] if first else "")
            first = first or it
        if first:
            return first, first["tag"] + " (rozszerzenie nazwy) (brak danych w poprzednim przebiegu)"
        return None, None

    # referencje z satelity wg znormalizowanej nazwy
    refs_by_key = collections.defaultdict(set)
    names_by_key = collections.defaultdict(set)
    # kopie kanalow: domyslnie tylko z transponderow, na ktorych sa Twoje kanaly (polskie platformy);
    # kopie z innych transponderow to zwykle wersje innych platform/krajow z innym programem
    def tp_of(ref):
        p = ref.split(":")
        return (p[4], p[5], p[6])
    my_refs = {r for _, _, r in mine}
    my_tps = {tp_of(r) for r in my_refs}
    skipped_copies = 0
    for name, ref in sat:
        if a.copies == "tp" and ref not in my_refs and tp_of(ref) not in my_tps:
            skipped_copies += 1
            continue
        refs_by_key[norm(name)].add(ref)
        names_by_key[norm(name)].add(name)

    wanted = {}          # klucz -> item
    report_mine = []
    seen_keys = set()
    for bq, name, ref in mine:
        k = norm(name)
        if k in seen_keys:
            continue
        seen_keys.add(k)
        it, how = find(name)
        report_mine.append((bq, name, it, how, len(refs_by_key[k])))
        if it:
            wanted[k] = it
    if a.scope == "all":
        for k in refs_by_key:
            if k not in wanted:
                it, how = find(sorted(names_by_key[k])[0])
                if it:
                    wanted[k] = it

    # id kanalu XMLTV = zrodlo:site_id (stale, niezalezne od nazwy)
    chan = collections.OrderedDict()      # xid -> (item, set(refs), set(names))
    for k, it in wanted.items():
        xid = xid_of(it)
        e = chan.setdefault(xid, (it, set(), set()))
        e[1].update(refs_by_key[k])
        e[2].update(names_by_key[k])

    with open("%s/grab.channels.xml" % a.out, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<channels>\n')
        for xid, (it, refs, names) in chan.items():
            f.write('  <channel site="%s" site_id="%s" lang="%s" xmltv_id="%s">%s</channel>\n' % (
                it["site"], escape(it["site_id"]), it.get("lang") or "pl", xid, escape(it["name"])))
        f.write("</channels>\n")
    nref = 0
    with open("%s/pl.channels.xml" % a.out, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- epgpl %s -->\n<channels>\n' % VERSION)
        for xid, (it, refs, names) in chan.items():
            f.write("  <!-- %s -> %s -->\n" % (escape(", ".join(sorted(names))), escape(it["name"])))
            for r in sorted(refs):
                f.write('  <channel id="%s">%s</channel>\n' % (xid, r))
                nref += 1
        f.write("</channels>\n")

    lines = ["epgpl %s - raport mapy kanalow" % VERSION, ""]
    ok = [r for r in report_mine if r[2]]
    no = [r for r in report_mine if not r[2] and r[3] != "alias"]
    off = [r for r in report_mine if not r[2] and r[3] == "alias"]
    lines.append("Kanaly z Twoich bukietow: %d, z programem: %d, bez dopasowania: %d, wylaczone (z tunera albo w aliases.txt): %d" % (
        len(report_mine), len(ok), len(no), len(off)))
    lines.append("Kanalow do pobrania: %d, referencji w mapie: %d (zakres: %s, zrodla: %s)" % (len(chan), nref, a.scope, a.sites))
    if a.copies == "tp":
        lines.append("Pominiete uslugi z transponderow bez Twoich kanalow (inne platformy/kraje): %d - COPIES=all w config.env wlacza je" % skipped_copies)
    lines.append("")
    lines.append("== BEZ DOPASOWANIA (wskaz zrodlo z tunera: EPG Scan -> Dopasowanie kanalow -> OK na kanale;")
    lines.append("   albo recznie w aliases.txt: Nazwa = onet:site_id / tm: / wp: / naz:site_id, albo Nazwa = - )")
    # propozycje: podobne nazwy we wszystkich zrodlach (do skopiowania jako linia aliasu)
    import difflib
    allk = {}
    for tag, _, _ in SITES:
        for k2, it2 in sites.get(tag, {}).items():
            allk.setdefault(k2, []).append(it2)
    for bq, name, it, how, n in no:
        lines.append("  [%s] %s" % (bq, name))
        cand = [it2 for k2 in difflib.get_close_matches(norm(name), list(allk), n=3, cutoff=0.8) for it2 in allk[k2]]
        if cand:
            lines.append("        podobne nazwy w zrodlach (sprawdz, czy to ten sam kanal - wtedy skopiuj linie do aliases.txt):")
        for it2 in cand[:3]:
            lines.append("          %s = %s:%s      # %s" % (name, it2["tag"], it2["site_id"], it2["name"]))
    lines.append("")
    lines.append("== DOPASOWANE (kanal na satelicie -> zrodlo programu, kopii na satelicie)")
    for bq, name, it, how, n in ok:
        lines.append("  [%s] %s -> %s %s (%s)%s" % (bq, name, it["tag"], it["name"], how, "  x%d" % n if n > 1 else ""))
    with open("%s/raport.txt" % a.out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    # dla tunera (okno "Dopasowanie kanalow" w EPG Scan): przydzial kanalow i listy stacji serwisow
    names = {"tm": "teleman", "onet": "onet", "wp": "WP Pilot", "naz": "naziemna", "cp": "Canal+"}
    mapa = {"generated": int(__import__("time").time()), "version": VERSION,
            "sites": [t for t, _, _ in SITES], "site_names": names, "channels": [],
            "bouquet_filter": sorted(only) if only is not None else "all",
            "empty": sorted(empty)}       # stacje bez danych w ostatnim pobraniu (propozycje zmian w EPG Scan)
    for bq, name, it, how, n in report_mine:
        k = norm(name)
        mapa["channels"].append({
            "bq": bq, "name": name, "copies": n,
            "manual": k in tuner_keys,
            "off": (not it and how == "alias"),
            "how": how or "",
            "tag": it["tag"] if it else "", "site_id": it["site_id"] if it else "", "sname": it["name"] if it else ""})
    with open("%s/mapa.json" % a.out, "w", encoding="utf-8") as f:
        json.dump(mapa, f, ensure_ascii=False)
    stacje = {}
    for tag, site, path in SITES:
        stacje[tag] = sorted({(it2["site_id"], it2["name"]) for k2, it2 in by_id.items()
                              if k2 != "_off" and isinstance(it2, dict) and it2["tag"] == tag}, key=lambda x: x[1].lower())
    with open("%s/stacje.json" % a.out, "w", encoding="utf-8") as f:
        json.dump(stacje, f, ensure_ascii=False)
    print("\n".join(lines[:4]))


if __name__ == "__main__":
    main()
