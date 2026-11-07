#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - przeniesienie audycji z poprzedniego pliku programu (www/pl.xml.xz) do nowego:
#  - serwisy podaja program "dniami telewizyjnymi" (od ok. 5-6 rano), wiec pobranie o 3:00 nie ma
#    trwajacej nocy - jest ona na stronie poprzedniego dnia, pobranej w poprzednim przebiegu;
#    dla kazdego kanalu przenoszone sa stare audycje, ktore koncza sie po teraz i zaczynaja przed
#    pierwsza audycja z nowego pobrania,
#  - kanal, ktorego nowe pobranie nie dalo wcale (blad serwisu), dostaje stare audycje od teraz.
# Uzycie: carry_over.py guide.xml stary.pl.xml.xz   (guide.xml nadpisywany; potem fix_guide.py porzadkuje)
import calendar, lzma, os, re, sys, time
import xml.etree.ElementTree as ET

new_path, old_path = sys.argv[1], sys.argv[2]
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


if not os.path.exists(old_path):
    print("0 0")
    sys.exit(0)
now = time.time()
tree = ET.parse(new_path)
root = tree.getroot()
chans = {c.get("id") for c in root.findall("channel")}
first = {}
for p in root.findall("programme"):
    st = parse(p.get("start"))
    ch = p.get("channel")
    if st is not None and (ch not in first or st < first[ch]):
        first[ch] = st
added, nch = 0, set()
try:
    with lzma.open(old_path) as f:
        for ev, el in ET.iterparse(f):
            if el.tag != "programme":
                continue
            ch = el.get("channel")
            if ch in chans:
                st, sp = parse(el.get("start")), parse(el.get("stop"))
                if st is not None and sp and sp > now and st < first.get(ch, float("inf")):
                    p = ET.SubElement(root, "programme", dict(el.attrib))
                    for c in el:
                        p.append(c)
                    added += 1
                    nch.add(ch)
            el.clear()
except Exception as e:
    print("uwaga: odczyt poprzedniego pliku programu nieudany: %s" % e, file=sys.stderr)
if added:
    tree.write(new_path, encoding="utf-8", xml_declaration=True)
print("%d %d" % (added, len(nch)))
