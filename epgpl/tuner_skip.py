#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - czy pominac pobieranie z harmonogramu serwera, bo pobieranie uruchamia dekoder (EPG Scan).
# Uzycie: tuner_skip.py <katalog work> <pl.xml.xz> <okno w godzinach>
# Wypisuje powod pominiecia (pusto = pobierac). Bezpieczniki: dekoder musial sie zglosic w ciagu 3 dni,
# a program na serwerze nie moze byc starszy niz 30 h - inaczej serwer pobiera mimo wszystko.
import json, os, sys, time

work, guide, win = sys.argv[1], sys.argv[2], float(sys.argv[3])
now = time.time()
try:
    t = json.load(open(os.path.join(work, "tuner.json"), encoding="utf-8"))
except Exception:
    sys.exit(0)
if not t.get("grab") or win <= 0:
    sys.exit(0)
if now - t.get("seen", 0) > 3 * 86400:
    sys.exit(0)                      # dekoder dawno sie nie zglaszal
if not os.path.exists(guide) or now - os.path.getmtime(guide) > 30 * 3600:
    sys.exit(0)                      # program za stary - pobieramy mimo harmonogramu dekodera
try:
    last = float(open(os.path.join(work, "tuner_last")).read().strip())
except Exception:
    last = 0
fmt = lambda x: time.strftime("%d.%m %H:%M", time.localtime(x))
if last and now - last < win * 3600:
    print("dekoder uruchomil pobieranie %s (okno +-%g h, TUNER_WINDOW w config.env)" % (fmt(last), win))
elif t.get("next") and 0 <= t["next"] - now <= win * 3600:
    print("dekoder uruchomi pobieranie %s (okno +-%g h, TUNER_WINDOW w config.env)" % (fmt(t["next"]), win))
