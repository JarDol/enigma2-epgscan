#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - kontrola kopii kanalow: dla kazdego kanalu z Twoich bukietow pokazuje wszystkie jego kopie z listy
# odbiornika (ta sama nazwa), czy sa w mapie XMLTV i ile maja teraz audycji w EPG odbiornika.
# Uzycie: sudo -u epgpl python3 /opt/epgpl/tools/kopie.py [--wszystkie]
import json, os, re, sys, time, urllib.parse, urllib.request, collections
import xml.etree.ElementTree as ET
import lzma

BASE = os.environ.get("EPGPL_BASE", "/opt/epgpl")
sys.path.insert(0, BASE)
import build_map as bm  # noqa: E402

cfg = open(os.path.join(BASE, "config.env")).read()
R = re.search(r"^RECEIVER=(\S+)", cfg, re.M).group(1)
skip = re.search(r'^SKIP_BOUQUETS="?([^"#\n]*)', cfg, re.M)
skip = {x.strip() for x in (skip.group(1) if skip else "Last Scanned").split(",") if x.strip()}

services = json.load(open(os.path.join(BASE, "work", "kanaly.json")))["services"]
mapped = set()
try:
    with lzma.open(os.path.join(BASE, "www", "pl.channels.xml.xz")) as f:
        for c in ET.parse(f).getroot():
            if c.text:
                mapped.add(bm.ref10(c.text.strip()))
except Exception as e:
    print("brak mapy kanalow: %s" % e)

by_key = collections.defaultdict(dict)        # norm -> {ref: nazwa}
mine = collections.OrderedDict()
for b in services:
    for s in b.get("subservices", []):
        ref = s.get("servicereference", "")
        if not bm.is_tv(ref):
            continue
        k = bm.norm(s["servicename"])
        by_key[k][bm.ref10(ref)] = s["servicename"]
        if b["servicename"] not in skip:
            mine.setdefault(k, s["servicename"])


def events(ref):
    try:
        u = "http://%s/api/epgservice?sRef=%s" % (R, urllib.parse.quote(ref, safe=""))
        with urllib.request.urlopen(u, timeout=30) as f:
            ev = json.loads(f.read().decode("utf-8", "ignore")).get("events", [])
        now = time.time()
        ends = [int(e["begin_timestamp"]) + int(e.get("duration_sec", 0)) for e in ev]
        return len(ev), (max(ends) - now) / 86400.0 if ends else 0.0
    except Exception:
        return -1, 0.0


everything = "--wszystkie" in sys.argv
bad = 0
print("Kanal z bukietu / kopie na satelicie: [w mapie XMLTV] audycji, zasieg dni, referencja\n")
for k, name in mine.items():
    copies = by_key.get(k, {})
    if len(copies) < 2 and not everything:
        continue
    rows = []
    for ref, nm in sorted(copies.items()):
        n, days = events(ref)
        rows.append((ref, nm, n, days))
    problem = any(n == 0 and r in mapped for r, _, n, _ in rows)
    if not problem and not everything:
        continue
    bad += problem
    print("%s  (%d kopii)" % (name, len(rows)))
    for ref, nm, n, days in rows:
        print("   %-6s %5s audycji  %5.1f dni   %-28s %s" % ("mapa" if ref in mapped else "poza", n if n >= 0 else "?",
            days, nm[:28], ref))
print("\nKanalow z kopia w mapie, ale bez EPG na odbiorniku: %d" % bad)
print("mapa  = kopia dostanie program z XMLTV (EPG Scan 1.9.7+, albo gdy jest w ulubionych)")
print("poza  = kopia z transpondera bez Twoich kanalow (zwykle inna platforma/kraj) - celowo bez programu z XMLTV;")
print("        trafi do mapy sama, gdy dodasz ja albo inny kanal z jej transpondera do ulubionych")
