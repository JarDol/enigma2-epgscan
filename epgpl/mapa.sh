#!/bin/bash
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - przeliczenie mapy kanalow bez pobierania programu (po zmianie z tunera albo w aliasach).
# Publikuje mapa.json i stacje.json (plan dla nastepnego pobrania); plik programu sie nie zmienia.
BASE=${EPGPL_BASE:-/opt/epgpl}
. "$BASE/config.env"
WORK=$BASE/work; WWW=$BASE/www; REPO=$BASE/iptv-epg
mkdir -p "$WORK/preview"
# kolejnosc serwisow ustawiona na dekoderze (EPG Scan) ma pierwszenstwo przed SITES z config.env
DEC_SITES=$(python3 -I -c 'import json,sys
try:
    s = json.load(open(sys.argv[1], encoding="utf-8")).get("sites") or []
    m = {"tm": "teleman", "onet": "onet", "wp": "wp", "naz": "naz"}
    print(",".join(m[t] for t in s if t in m))
except Exception:
    pass' "$WORK/bukiety.json" 2>/dev/null)
[ -n "$DEC_SITES" ] && SITES=$DEC_SITES
# aktualna lista kanalow z dekodera (zmiany w bukietach widoczne od razu); gdy nie odpowiada - ostatnia zapisana
if [ -n "$RECEIVER" ] && curl -sf -m 15 "http://$RECEIVER/api/getallservices" -o "$WORK/kanaly.new.json" \
	&& grep -q '"services"' "$WORK/kanaly.new.json"; then
	python3 -I "$BASE/alternatives.py" "$WORK/kanaly.new.json" "$RECEIVER" >/dev/null 2>&1
	mv "$WORK/kanaly.new.json" "$WORK/kanaly.json"
else
	rm -f "$WORK/kanaly.new.json"
fi
# listy stacji ze stron serwisow, gdy starsze niz godzina (nowy kanal od razu w mapie)
DEC_SITES=$DEC_SITES bash "$BASE/stacje.sh" 60 >/dev/null 2>&1
cp -r "$BASE/sites/." "$REPO/sites/" 2>/dev/null
python3 -I "$BASE/build_map.py" --services "$WORK/kanaly.json" --epgrepo "$REPO" \
	--aliases "$BASE/aliases.dist" --aliases "$BASE/aliases.txt" --tuner-aliases "$BASE/wskazania.txt" \
	--empty "$WORK/empty.json" --copies "${COPIES:-tp}" --sites "${SITES:-teleman,onet,wp,naz}" \
	--scope "$SCOPE" --skip-bouquets "$SKIP_BOUQUETS" --bouquets "$WORK/bukiety.json" --out "$WORK/preview" >/dev/null 2>&1 || exit 1
for f in mapa.json stacje.json; do
	cp "$WORK/preview/$f" "$WWW/.$f" && mv "$WWW/.$f" "$WWW/$f"
done
