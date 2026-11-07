#!/bin/bash
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - kandydaci do okna wyboru stacji w EPG Scan: stacje spoza pliku programu, ktore okno proponuje dla kanalow
# z bukietow - program na dobe (od poprzedniego dnia: trwajaca noc) -> www/kandydaci.xml.xz (tylko podglad, nie EPG).
# Uruchamiane po udanym pobieraniu (epgpl-kandydaci.service, OnSuccess w epgpl.service) - dekoder nie czeka na koniec.
BASE=${EPGPL_BASE:-/opt/epgpl}
. "$BASE/config.env"
WORK=$BASE/work; WWW=$BASE/www; REPO=$BASE/iptv-epg
export PATH="$BASE/node/bin:$PATH"
export HOME=$BASE
log() { echo "$(date '+%H:%M:%S') $*"; }
# ustawienie z dekodera (EPG Scan: "Codziennie program kandydatow") ma pierwszenstwo przed KANDYDACI z config.env
DEC=$(python3 -I -c 'import json,sys
try:
    v = json.load(open(sys.argv[1], encoding="utf-8")).get("cand")
    print("" if v is None else int(bool(v)))
except Exception:
    pass' "$WORK/bukiety.json" 2>/dev/null)
[ -n "$DEC" ] && KANDYDACI=$DEC
[ "$1" = "--zawsze" ] || [ "${KANDYDACI:-0}" = 1 ] || exit 0
[ -s "$WWW/pl.xml.xz" ] && [ -s "$WWW/mapa.json" ] || exit 0
# juz zrobione dla tego pliku programu (np. pobieranie z harmonogramu pominiete)
[ "$1" != "--zawsze" ] && [ "$WWW/kandydaci.xml.xz" -nt "$WWW/pl.xml.xz" ] && exit 0
exec 8>"$WORK/kandydaci.lock"
flock -n 8 || exit 0
LOG=$BASE/log/kandydaci.log
exec > >(tee "$LOG") 2>&1
KD=$WORK/kand; rm -rf "$KD"; mkdir -p "$KD"
if KN=$(python3 -I "$BASE/kandydaci.py" "$WWW/mapa.json" "$WWW/stacje.json" "$KD" 2>>"$WORK/grab.log"); then
	log "kandydaci do okna wyboru stacji: $KN - pobieranie programu na dobe"
	t0=$(date +%s); KP=""
	KCURR=$(date -u -d "-1 day" +%Y-%m-%dT%H:%M:%SZ)
	for chf in "$KD"/*.channels.xml; do
		[ -s "$chf" ] || continue
		site=$(basename "$chf" .channels.xml)
		(cd "$REPO" && CURR_DATE="$KCURR" npm run --silent grab --- --channels="$chf" --output="$KD/$site.xml" --days=2 \
			--maxConnections="$MAX_CONNECTIONS" --delay="${GRAB_DELAY:-3000}") 2>&1 | sed -u "s/^/[kandydaci $site] /" >> "$WORK/grab.log" &
		KP="$KP $!"
	done
	[ -n "$KP" ] && wait $KP
	if ls "$KD"/*.xml 2>/dev/null | grep -qv channels; then
		python3 -I "$BASE/grab_tools.py" merge "$KD/kandydaci.xml" $(ls "$KD"/*.xml | grep -v channels) > /dev/null \
			&& xz -6 -c "$KD/kandydaci.xml" > "$WWW/.kandydaci.xml.xz" && mv "$WWW/.kandydaci.xml.xz" "$WWW/kandydaci.xml.xz" \
			&& log "kandydaci: gotowe w $(( ($(date +%s) - t0) / 60 )) min ($(du -k $WWW/kandydaci.xml.xz | cut -f1) kB)"
	else
		log "uwaga: kandydaci - nic nie pobrano"
	fi
else
	log "uwaga: lista kandydatow nieudana"
fi
