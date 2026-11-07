#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - porzadkowanie pobranego programu przed publikacja:
#  - audycje kazdego kanalu sortowane po starcie, powtorzenia (ten sam start) usuwane,
#  - koniec audycji przycinany do poczatku nastepnej,
#  - plik zapisany uporzadkowany: kanaly, potem audycje kanal po kanale wg czasu.
# Grabbery nie znaja konca ostatniej audycji dnia (strona konczy sie np. o 3:00) i wpisuja +1 h;
# nastepny dzien zaczyna sie wczesniej, wiec bez przyciecia powstaja nakladajace sie audycje (duble w EPG).
import calendar, re, sys, time
import xml.etree.ElementTree as ET

path = sys.argv[1]
TS = re.compile(r"^(\d{14})\s*([+-]\d{4})?")


def parse(s):
    m = TS.match(s or "")
    if not m:
        return None
    t = calendar.timegm(time.strptime(m.group(1), "%Y%m%d%H%M%S"))
    if m.group(2):
        o = int(m.group(2))
        t -= (1 if o >= 0 else -1) * ((abs(o) // 100) * 3600 + (abs(o) % 100) * 60)
    return t


def fmt(t):
    return time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))


tree = ET.parse(path)
root = tree.getroot()
progs = [p for p in root.findall("programme")]
by_ch = {}
for p in progs:
    by_ch.setdefault(p.get("channel"), []).append(p)
removed = clipped = dropped = 0
keep = set()
for ch, lst in by_ch.items():
    items = [(parse(p.get("start")), i, p) for i, p in enumerate(lst)]
    items = [x for x in items if x[0] is not None]
    items.sort(key=lambda x: (x[0], x[1]))
    out = []
    for st, i, p in items:
        if out and out[-1][0] == st:
            removed += 1               # ta sama godzina startu - zostaje pierwsza
            continue
        out.append((st, p))
    old = time.time() - 2 * 3600
    for k, (st, p) in enumerate(out):
        stop0 = parse(p.get("stop"))
        nxt0 = out[k + 1][0] if k + 1 < len(out) else None
        end = min(x for x in (stop0, nxt0) if x is not None) if (stop0 or nxt0) else st
        if end < old:
            dropped += 1               # zakonczone ponad 2 h temu (dzien poprzedni) - niepotrzebne odbiornikowi
            continue
        keep.add(id(p))
        if k + 1 < len(out):
            nxt = out[k + 1][0]
            stop = parse(p.get("stop"))
            if stop is None or stop > nxt:
                p.set("stop", fmt(nxt))
                clipped += 1
# zapis uporzadkowany: najpierw kanaly, potem audycje kanal po kanale, w kolejnosci czasu
chans = [el for el in root if el.tag == "channel"]
ordered = []
for ch in sorted(by_ch):
    lst = [(parse(p.get("start")), p) for p in by_ch[ch] if id(p) in keep]
    ordered += [p for st, p in sorted(lst, key=lambda x: x[0])]
for el in list(root):
    root.remove(el)
for el in chans + ordered:
    root.append(el)
tree.write(path, encoding="UTF-8", xml_declaration=True)
sys.stderr.write("porzadkowanie programu: przycieto %d audycji, usunieto %d powtorzen, %d minionych\n" % (clipped, removed, dropped))
