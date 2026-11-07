#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - pomocnicze operacje na plikach grabbera
#  split  grab.channels.xml katalog        -> katalog/<serwis>.channels.xml (osobna lista dla kazdego serwisu)
#  failed grab-<serwis>.log kanaly.xml out  -> out: kanaly, przy ktorych byly bledy (bez 404 = brak dnia w serwisie);
#                                              wypisuje "bledow ograniczen" i "wszystkich bledow"
#  merge  wynik.xml plik1.xml plik2.xml...  -> jeden plik XMLTV (kanaly bez powtorzen, wszystkie audycje)
import re, sys
import xml.etree.ElementTree as ET


def write_channels(path, chans):
    with open(path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<channels>\n')
        for c in chans:
            f.write("  " + ET.tostring(c, encoding="unicode").strip() + "\n")
        f.write("</channels>\n")


cmd = sys.argv[1]
if cmd == "split":
    src, outdir = sys.argv[2:4]
    by = {}
    for c in ET.parse(src).getroot():
        by.setdefault(c.get("site"), []).append(c)
    for site, chans in by.items():
        write_channels("%s/%s.channels.xml" % (outdir, site), chans)
        print(site, len(chans))
elif cmd == "failed":
    logf, chf, out = sys.argv[2:5]
    lines = open(logf, encoding="utf-8", errors="ignore").read().splitlines()
    bad, limit, total = set(), 0, 0
    for i, ln in enumerate(lines):
        if "ERR:" not in ln or i == 0:
            continue
        if " 404" in ln:
            continue                       # brak dnia w serwisie (np. onet po 13 dniach) - to nie blad
        total += 1
        if re.search(r"\b(403|429|503)\b|ECONNRESET|socket disconnected|timeout", ln):
            limit += 1
        m = re.search(r"\]\s+\S+\s+\([^)]*\)\s+-\s+(\S+)\s+-", lines[i - 1])
        if m:
            bad.add(m.group(1))
    chans = [c for c in ET.parse(chf).getroot() if c.get("xmltv_id") in bad]
    write_channels(out, chans)
    print(limit, total, len(chans))
elif cmd == "merge":
    out, files = sys.argv[2], sys.argv[3:]
    root = ET.Element("tv")
    seen = set()
    progs = []
    for fn in files:
        try:
            r = ET.parse(fn).getroot()
        except Exception:
            continue
        for el in r:
            if el.tag == "channel":
                if el.get("id") not in seen:
                    seen.add(el.get("id"))
                    root.append(el)
            elif el.tag == "programme":
                progs.append(el)
    for p in progs:
        root.append(p)
    ET.ElementTree(root).write(out, encoding="UTF-8", xml_declaration=True)
    print(len(seen), len(progs))
