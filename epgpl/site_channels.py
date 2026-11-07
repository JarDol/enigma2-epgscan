#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - lista stacji ze strony serwisu -> <site>.channels.xml (format iptv-org)
# Uzycie: site_channels.py teleman|wp|naz|onet strona.html wynik.xml
# Format wyniku i sposob czytania listy stacji onet - jak w iptv-org/epg (https://github.com/iptv-org/epg,
# licencja Unlicense - domena publiczna). Szczegoly: THIRD_PARTY.md.
import html, json, re, sys
from xml.sax.saxutils import escape, quoteattr

kind, src, dst = sys.argv[1:4]
data = open(src, encoding="utf-8", errors="ignore").read()
seen = {}
if kind == "teleman":
    site = "teleman.pl"
    for slug, name in re.findall(r'<a href="/program-tv/stacje/([^"?#]+)"[^>]*>([^<]+)<', data):
        name = html.unescape(name).strip()
        if slug not in seen and name:
            seen[slug] = name
elif kind == "wp":
    site = "pilot.wp.pl"
    m = re.search(r'<script id="__PRELOAD_STATE__" type="application/json">(.*?)</script>', data, re.S)
    if m:
        for c in json.loads(m.group(1)).get("ChannelsStore", {}).get("channels", []):
            if c.get("slug") and c.get("name") and c["slug"] not in seen:
                seen[c["slug"]] = c["name"].strip()
elif kind == "naz":
    site = "programtv.naziemna.info"
    for slug, name in re.findall(r'href="/program/stacja/([^",=?#]+)"[^>]*class="dropdown-item"[^>]*>([^<]+)<', data):
        name = html.unescape(name).strip()
        if slug not in seen and name:
            seen[slug] = name
elif kind == "onet":
    # programtv.onet.pl/stacje - jak channels() z iptv-org (ul.channelList a), ale strona pobierana curl-em:
    # onet odpowiada 429 na zapytania z identyfikatorem "axios"
    site = "programtv.onet.pl"
    # cala strona: odnosniki do stacji maja postac /program-tv/<nazwa>[-<numer>] (listy bywaja zagniezdzone,
    # wiec bez ograniczania do jednego <ul>); nazwa z tekstu odnosnika, a gdy go brak (sam logotyp) - z alt/title
    alt = {}
    for pre, slug, post, inner in re.findall(
            r'<a([^>]*?)href="(?:https://programtv\.onet\.pl)?/program-tv/([a-z0-9][a-z0-9.-]*)/?"([^>]*)>(.*?)</a>', data, re.S | re.I):
        name = re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", inner))).strip()
        if name:
            seen.setdefault(slug, name)
        else:
            m = re.search(r'(?:alt|title)="([^"]+)"', inner + pre + post)
            if m:
                alt.setdefault(slug, html.unescape(m.group(1)).strip())
    for slug, name in alt.items():
        seen.setdefault(slug, name)
else:
    sys.exit("nieznany serwis %s" % kind)
if len(seen) < (200 if kind == "onet" else 20):
    sys.exit("za malo stacji na stronie %s (%d) - zostawiam poprzednia liste" % (site, len(seen)))
with open(dst, "w", encoding="utf-8") as f:
    f.write('<?xml version="1.0" encoding="UTF-8"?>\n<channels>\n')
    for slug, name in seen.items():
        f.write('  <channel site="%s" site_id=%s lang="pl" xmltv_id="">%s</channel>\n' % (site, quoteattr(slug), escape(name)))
    f.write("</channels>\n")
print("%s: %d stacji" % (site, len(seen)))
