#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - podsumowanie pobranego programu i lista kanalow bez danych.
# Kanal, ktory w zrodle nie dal zadnej audycji, trafia do empty.json - przy nastepnej budowie mapy
# dostanie program z kolejnego zrodla. Po RETRY dniach pierwsze zrodlo jest probowane ponownie.
# Uzycie: check_guide.py guide.xml grab.channels.xml empty.json  -> wypisuje "audycji kanalow"
import json, sys, time
import xml.etree.ElementTree as ET

RETRY = 7 * 86400
guide, chans, emptyf = sys.argv[1:4]
n = 0
got = set()
try:
    for ev, el in ET.iterparse(guide):
        if el.tag == "programme":
            n += 1
            got.add(el.get("channel"))
        el.clear()
except Exception:
    pass
want = {}
try:
    for c in ET.parse(chans).getroot():
        want[c.get("xmltv_id")] = (c.text or "").strip()
except Exception:
    pass
try:
    empty = json.load(open(emptyf))
except Exception:
    empty = {}
now = int(time.time())
if n > 100:                                   # pobranie udane - mozna oceniac pojedyncze kanaly
    for x in want:
        if x in got:
            empty.pop(x, None)
        elif x not in empty:
            empty[x] = {"name": want[x], "since": now}
    for x in list(empty):
        if now - empty[x].get("since", now) > RETRY and x not in want:
            del empty[x]                      # po tygodniu sprobuj znow pierwszego zrodla
    json.dump(empty, open(emptyf, "w"), indent=1, ensure_ascii=False)
    miss = [want[x] + " (" + x.split(".")[0] + ")" for x in want if x not in got]
    if miss:
        sys.stderr.write("bez programu w tym przebiegu (nastepnym razem z innego zrodla): %s\n" % ", ".join(miss))
print(n, len(got))
