#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - jednorazowe przeniesienie dawnych aliasow do wskazan recznych (wskazania.txt).
# Przenoszone sa tylko te, bez ktorych kanal nie zostalby znaleziony (pod obecnym serwisem i stacja),
# oraz wylaczenia "= -". Istniejace wskazania z tunera maja pierwszenstwo.
# Uzycie: migrate_aliases.py mapa.json wskazania.txt
import json, os, sys

mapa, tuner = sys.argv[1:3]
chans = json.load(open(mapa, encoding="utf-8"))["channels"]
cur = {}
try:
    for ln in open(tuner, encoding="utf-8"):
        ln = ln.split("#", 1)[0].strip()
        if "=" in ln:
            k, v = [x.strip() for x in ln.split("=", 1)]
            cur[k] = v
except FileNotFoundError:
    pass
added = 0
for c in chans:
    if c["name"] in cur:
        continue
    if c["off"]:
        cur[c["name"]] = "-"
        added += 1
    elif c["tag"] and "alias" in c["how"]:
        cur[c["name"]] = "%s:%s" % (c["tag"], c["site_id"])
        added += 1
tmp = tuner + ".tmp"
with open(tmp, "w", encoding="utf-8") as f:
    f.write("# epgpl - wskazania ustawione z tunera (EPG Scan -> Dopasowanie kanalow). Pierwszenstwo przed automatem.\n")
    f.write("# Mozna edytowac recznie; usuniecie linii = powrot do automatu.\n")
    for k in sorted(cur, key=str.lower):
        f.write("%s = %s\n" % (k, cur[k]))
os.replace(tmp, tuner)
print("przeniesiono do wskazan recznych: %d" % added)
