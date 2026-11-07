#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - rozwija alternatywy w liscie kanalow z dekodera (kanaly.json z /api/getallservices).
# Kanal z alternatywami ma w bukiecie odnosnik 1:134:...FROM BOUQUET "alternatives...." zamiast uslugi;
# EPG Scan na dekoderze wchodzi do takiej grupy i bierze uslugi z niej - serwer robi to samo przez OpenWebif.
# Uzycie: alternatives.py kanaly.json ADRES_DEKODERA   (plik poprawiany w miejscu; przy bledzie bez zmian)
import json, os, sys, urllib.parse, urllib.request

path, rec = sys.argv[1], sys.argv[2]
data = json.load(open(path, encoding="utf-8"))
cache = {}
changed = 0


def members(ref):
    if ref not in cache:
        try:
            url = "http://%s/api/getservices?sRef=%s" % (rec, urllib.parse.quote(ref, safe=""))
            with urllib.request.urlopen(url, timeout=15) as r:
                d = json.loads(r.read().decode("utf-8", "ignore"))
            cache[ref] = [s for s in d.get("services", []) if s.get("servicereference", "").startswith("1:0:")]
        except Exception as e:
            print("alternatywy: %s - %s" % (ref, e), file=sys.stderr)
            cache[ref] = []
    return cache[ref]


for b in data.get("services", []):
    out = []
    for s in b.get("subservices", []):
        ref = s.get("servicereference", "")
        if "FROM BOUQUET" in ref and "alternatives." in ref:
            m = members(ref)
            if m:
                for x in m:
                    out.append({"servicereference": x["servicereference"],
                                "servicename": (x.get("servicename") or s.get("servicename") or "").strip(),
                                "alternative": s.get("servicename", "")})
                changed += 1
                continue
        out.append(s)
    b["subservices"] = out

if changed:
    tmp = path + ".alt"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    os.rename(tmp, path)
print(changed)
