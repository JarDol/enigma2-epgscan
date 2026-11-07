#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - "pobieranie w jednym miejscu" (ustawienie w EPG Scan): czy harmonogram serwera ma pominac pobieranie,
# bo dekoder dzis juz pobral pelny program (i przyslal paczke) albo wlasnie pobiera.
# Uzycie: jedno_miejsce.py <katalog work> <katalog www>  -> wypisuje powod pominiecia (pusto = pobierac).
# Gdy dekoder zglosil, ze pobiera - czeka (najwyzej 90 min) na jego paczke.
import json, os, sys, time

work, www = sys.argv[1], sys.argv[2]


def load(p):
    try:
        return json.load(open(p, encoding="utf-8"))
    except Exception:
        return {}


def midnight():
    t = time.localtime()
    return time.mktime((t.tm_year, t.tm_mon, t.tm_mday, 0, 0, 0, 0, 0, -1))


# dekoder zglasza "pobieram" i wysyla paczke tylko z wlaczonym "Pobieranie w jednym miejscu" - nie trzeba tu
# osobno sprawdzac ustawienia (moglo jeszcze nie dotrzec na serwer)
t0 = time.time()
while time.time() - t0 < 90 * 60:
    busy = load(os.path.join(work, "dekoder_pobiera.json")).get("until", 0)
    if busy <= time.time():
        break
    time.sleep(60)
full = load(os.path.join(www, "pelny.json"))
if full.get("source") == "dekoder" and full.get("time", 0) >= midnight():
    print("dekoder pobral dzis pelny program (%s) i przyslal paczke - pobieranie w jednym miejscu (EPG Scan)" %
          time.strftime("%H:%M", time.localtime(full["time"])))
