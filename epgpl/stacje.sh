#!/bin/bash
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - odswiezenie list stacji prosto ze stron serwisow -> sites/<serwis>/<serwis>.channels.xml
# Wywolywane przez run.sh (kazde pobieranie) i mapa.sh (przeliczenie mapy; gdy lista starsza niz STACJE_MAX_MIN minut).
# Przy bledzie zostaje poprzednia lista.
BASE=${EPGPL_BASE:-/opt/epgpl}
. "$BASE/config.env"
WORK=$BASE/work; REPO=$BASE/iptv-epg
MAXMIN=${1:-0}
log() { echo "$(date '+%H:%M:%S') $*"; }
refresh_list() {         # $1=rodzaj (teleman|wp|naz|onet) $2=katalog serwisu $3=adres listy stacji
	local dir=$BASE/sites/$2 f
	f=$dir/$2.channels.xml
	mkdir -p "$dir"
	if [ "$MAXMIN" -gt 0 ] && [ -s "$f" ] && [ -z "$(find "$f" -mmin +"$MAXMIN" 2>/dev/null)" ]; then
		return 0
	fi
	if curl -sf -m 60 -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" "$3" -o "$WORK/$1-stacje.html" \
		&& python3 -I "$BASE/site_channels.py" "$1" "$WORK/$1-stacje.html" "$f.new" \
		&& [ "$(grep -c '<channel ' "$f.new")" -ge 20 ]; then
		mv "$f.new" "$f"
		mkdir -p "$REPO/sites/$2" && cp "$f" "$REPO/sites/$2/"
	else
		rm -f "$f.new"
		log "uwaga: nie udalo sie odswiezyc listy stacji $2 - uzywam poprzedniej"
	fi
}
# kolejnosc ustawiona na dekoderze ma pierwszenstwo przed SITES z config.env (jak w mapa.sh / run.sh)
S=",${DEC_SITES:-${SITES:-teleman,onet,wp,naz}},"
# onet: curl z naglowkiem przegladarki - onet odpowiada 429 na channels:parse z iptv-org (identyfikator "axios")
case "$S" in *,onet,*) refresh_list onet programtv.onet.pl https://programtv.onet.pl/stacje ;; esac
case "$S" in *,teleman,*|*,tm,*) refresh_list teleman teleman.pl https://www.teleman.pl/program-tv/stacje ;; esac
case "$S" in *,wp,*|*,pilot,*) refresh_list wp pilot.wp.pl https://pilot.wp.pl/program-tv/lista-kanalow ;; esac
case "$S" in *,naz,*|*,naziemna,*) refresh_list naz programtv.naziemna.info https://programtv.naziemna.info/program/stacje ;; esac
exit 0
