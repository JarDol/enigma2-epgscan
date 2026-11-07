#!/bin/bash
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - nocne pobranie programu i budowa plikow dla odbiornika
set -u
BASE=${EPGPL_BASE:-/opt/epgpl}
. "$BASE/config.env"
export PATH="$BASE/node/bin:$PATH"
export HOME=$BASE
REPO=$BASE/iptv-epg
WORK=$BASE/work
WWW=$BASE/www
LOGD=$BASE/log
mkdir -p "$WORK" "$WWW" "$LOGD"
# kolejnosc serwisow ustawiona na dekoderze (EPG Scan) ma pierwszenstwo przed SITES z config.env
DEC_SITES=$(python3 -I -c 'import json,sys
try:
    s = json.load(open(sys.argv[1], encoding="utf-8")).get("sites") or []
    m = {"tm": "teleman", "onet": "onet", "wp": "wp", "naz": "naz"}
    print(",".join(m[t] for t in s if t in m))
except Exception:
    pass' "$WORK/bukiety.json" 2>/dev/null)
[ -n "$DEC_SITES" ] && SITES=$DEC_SITES

# jedno pobieranie naraz (harmonogram serwera, dekoder, reczne uruchomienie)
exec 9>"$WORK/run.lock"
flock -n 9 || { echo "pobieranie juz trwa - koncze"; exit 0; }
# harmonogram serwera: pominiecie, gdy pobieranie uruchamia dekoder w oknie +-TUNER_WINDOW h
if [ "${1:-}" = "--auto" ]; then
	# pobieranie w jednym miejscu (EPG Scan): dekoder dzis juz pobral i przyslal paczke albo wlasnie pobiera
	WHY=$(python3 -I "$BASE/jedno_miejsce.py" "$WORK" "$WWW" 2>/dev/null)
	# okno dekodera (TUNER_WINDOW) - tylko bez "pobierania w jednym miejscu"; z nim decyduje, kto pierwszy pobierze
	ONE=$(python3 -I -c 'import json,sys
try:
    print(1 if json.load(open(sys.argv[1], encoding="utf-8")).get("one_place") else 0)
except Exception:
    print(0)' "$WORK/bukiety.json" 2>/dev/null)
	[ -z "$WHY" ] && [ "$ONE" != 1 ] && WHY=$(python3 -I "$BASE/tuner_skip.py" "$WORK" "$WWW/pl.xml.xz" "${TUNER_WINDOW:-3}" 2>/dev/null)
	if [ -n "$WHY" ]; then
		echo "$(date '+%d.%m %H:%M') harmonogram serwera pominiety: $WHY" >> "$LOGD/pominiete.log"
		exit 0
	fi
fi
LOG=$LOGD/run-$(date +%Y%m%d-%H%M).log
exec > >(tee -a "$LOG") 2>&1
log() { echo "$(date '+%H:%M:%S') $*"; }
# raport dla odbiornika: na gorze podsumowanie ostatniego przebiegu (bledy, blokady serwisow, czasy),
# pod nim mapa kanalow. Przy nieudanym przebiegu raport mowi to wprost - odbiornik ma wtedy poprzedni plik.
report() {          # $1 = ok|blad  $2 = opis
	local mapa="$WORK/raport.txt" out="$WWW/.raport.txt"
	[ "$1" = "blad" ] && mapa="$WWW/raport.mapa.txt"
	{
		echo "== OSTATNI PRZEBIEG: $(date '+%d.%m.%Y %H:%M') - $([ "$1" = ok ] && echo "udany" || echo "NIEUDANY")"
		[ "$1" = "blad" ] && echo "   $2" && echo "   Odbiornik dostaje poprzedni plik programu z $(date -r "$WWW/pl.xml.xz" '+%d.%m %H:%M' 2>/dev/null || echo '-')."
		grep -E "gotowe w|bledow|ponawiam|poprzedniego pliku|bez programu w tym|porzadkowanie|pobrano|uwaga|BLAD" "$LOG" | sed 's/^/   /'
		echo
		cat "$mapa" 2>/dev/null
	} > "$out" && mv "$out" "$WWW/raport.txt"
	[ "$1" = "ok" ] && cp "$WORK/raport.txt" "$WWW/raport.mapa.txt"
}
fail() { log "BLAD: $*"; mkdir -p "$WWW"; report blad "$*"; exit 1; }

log "epgpl $(cat $BASE/VERSION) start"

# 1. aktualizacja grabbera (iptv-org/epg) + nasza poprawka onet (bez pobierania obrazkow do kazdej audycji)
git -C "$REPO" fetch -q --depth 1 origin master && git -C "$REPO" reset -q --hard FETCH_HEAD || log "uwaga: aktualizacja iptv-org/epg nieudana, uzywam poprzedniej wersji"
LOCK=$(sha1sum "$REPO/package-lock.json" | cut -d' ' -f1)
if [ "$LOCK" != "$(cat $WORK/lock.sha1 2>/dev/null)" ]; then
	log "instalacja zaleznosci npm"
	(cd "$REPO" && npm ci --no-audit --no-fund --loglevel=error) || fail "npm ci"
	echo "$LOCK" > "$WORK/lock.sha1"
fi
# wlasne grabbery (teleman.pl) - kopiowane po kazdej aktualizacji repozytorium
cp -r "$BASE/sites/." "$REPO/sites/"
# listy stacji prosto ze stron serwisow (nowe kanaly bez czekania na iptv-org)
DEC_SITES=$DEC_SITES bash "$BASE/stacje.sh" || true
ONET=$REPO/sites/programtv.onet.pl/programtv.onet.pl.config.js
if grep -q "const details = await loadProgramDetails(programUrl)" "$ONET"; then
	sed -i "s/const details = await loadProgramDetails(programUrl)/const details = null/" "$ONET"
else
	log "uwaga: nie znaleziono miejsca poprawki onet (zmienil sie grabber) - pobieranie moze trwac dluzej"
fi

# 2. lista kanalow z odbiornika (przy braku odpowiedzi - ostatnia zapisana)
[ -n "$RECEIVER" ] || [ -s "$WORK/kanaly.json" ] || fail "brak adresu dekodera (RECEIVER) - ustaw: sudo bash $BASE/configure.sh"
if curl -sf -m 60 "http://$RECEIVER/api/getallservices" -o "$WORK/kanaly.new.json" && grep -q '"services"' "$WORK/kanaly.new.json"; then
	ALT=$(python3 -I "$BASE/alternatives.py" "$WORK/kanaly.new.json" "$RECEIVER")
	mv "$WORK/kanaly.new.json" "$WORK/kanaly.json"
	log "lista kanalow pobrana z odbiornika $RECEIVER${ALT:+ (kanaly z alternatywami: $ALT)}"
else
	[ -s "$WORK/kanaly.json" ] || fail "odbiornik $RECEIVER nie odpowiada i brak zapisanej listy kanalow"
	log "uwaga: odbiornik nie odpowiada - uzywam listy z $(date -r $WORK/kanaly.json '+%d.%m %H:%M')"
fi

# 3. mapa kanalow
python3 -I "$BASE/build_map.py" --services "$WORK/kanaly.json" --epgrepo "$REPO" \
	--aliases "$BASE/aliases.dist" --aliases "$BASE/aliases.txt" --tuner-aliases "$BASE/wskazania.txt" --empty "$WORK/empty.json" --copies "${COPIES:-tp}" --sites "${SITES:-teleman,onet,wp,naz}" \
	--scope "$SCOPE" --skip-bouquets "$SKIP_BOUQUETS" --bouquets "$WORK/bukiety.json" --out "$WORK" || fail "budowa mapy"

# 4. pobranie programu - kazdy serwis rownolegle, z wlasnym limitem polaczen (MAX_CONNECTIONS na serwis).
#    Przy bledach ograniczen (403/429/zerwania) serwis jest ponawiany dla nieudanych kanalow z mniejsza liczba polaczen.
log "pobieranie programu na $DAYS dni (serwisy rownolegle, do $MAX_CONNECTIONS polaczen na serwis)"
rm -rf "$WORK/grab" && mkdir -p "$WORK/grab"
rm -f "$WORK/guide.xml"
: > "$WORK/grab.log"
python3 -I "$BASE/grab_tools.py" split "$WORK/grab.channels.xml" "$WORK/grab" > "$WORK/grab/split.txt"

# dzien poprzedni: serwisy podaja program "dniami telewizyjnymi" (od ok. 5-6 rano) - trwajaca noc jest na
# stronie poprzedniego dnia. GRAB_YESTERDAY=1 pobiera go dodatkowo (ok. +15% czasu); minione audycje odpadaja.
if [ "${GRAB_YESTERDAY:-1}" = 1 ]; then
	CURR=$(date -u -d "-1 day" +%Y-%m-%dT%H:%M:%SZ); GDAYS=$((DAYS + 1))
	log "pobieranie od dnia poprzedniego (trwajaca noc) - GRAB_YESTERDAY w config.env"
else
	CURR=$(date -u +%Y-%m-%dT%H:%M:%SZ); GDAYS=$DAYS
fi
grab_site() {          # $1 = serwis (np. teleman.pl)
	local site=$1 conn=$MAX_CONNECTIONS round=0 chf="$WORK/grab/$1.channels.xml" t0=$(date +%s)
	local outs=""
	while :; do
		local out="$WORK/grab/$site.$round.xml" lg="$WORK/grab/$site.$round.log"
		(cd "$REPO" && CURR_DATE="$CURR" npm run --silent grab --- --channels="$chf" --output="$out" --days="$GDAYS" --maxConnections="$conn" --delay="${GRAB_DELAY:-3000}") 2>&1 \
			| tee "$lg" | sed -u "s/^/[$site] /" >> "$WORK/grab.log"
		outs="$outs $out"
		read LIM ALL NBAD < <(python3 -I "$BASE/grab_tools.py" failed "$lg" "$chf" "$WORK/grab/$site.retry.xml")
		if [ "${NBAD:-0}" -eq 0 ] || [ "${LIM:-0}" -eq 0 ] || [ $round -ge 2 ]; then
			[ "${ALL:-0}" -gt 0 ] && log "$site: bledow $ALL (w tym ograniczen serwisu $LIM), kanalow z bledami $NBAD"
			break
		fi
		round=$((round + 1))
		conn=$(( conn > 1 ? conn / 2 : 1 ))
		log "$site: $LIM bledow ograniczen serwisu - ponawiam $NBAD kanalow z $conn polaczeniami po 2 min przerwy"
		cp "$WORK/grab/$site.retry.xml" "$WORK/grab/$site.r$round.channels.xml"
		chf="$WORK/grab/$site.r$round.channels.xml"
		sleep 120
	done
	echo "$outs" > "$WORK/grab/$site.outputs"
	log "$site: gotowe w $(( ($(date +%s) - t0) / 60 )) min"
}

PIDS=""
while read site n; do
	log "$site: kanalow $n"
	grab_site "$site" &
	PIDS="$PIDS $!"
done < "$WORK/grab/split.txt"
wait $PIDS          # tylko pobierania (samo "wait" czekaloby tez na zapis logu w tle)
python3 -I "$BASE/grab_tools.py" merge "$WORK/guide.xml" $(cat "$WORK/grab/"*.outputs) > /dev/null
# trwajaca noc (na stronach serwisow nalezy do poprzedniego dnia) i kanaly bez nowych danych - z poprzedniego pliku
read CN CC < <(python3 -I "$BASE/carry_over.py" "$WORK/guide.xml" "$WWW/pl.xml.xz")
[ "${CN:-0}" -gt 0 ] && log "z poprzedniego pliku programu: $CN audycji dla $CC kanalow (trwajaca noc, kanaly bez nowych danych)"
python3 -I "$BASE/fix_guide.py" "$WORK/guide.xml" || log "uwaga: porzadkowanie programu nieudane"
read N NCH < <(python3 -I "$BASE/check_guide.py" "$WORK/guide.xml" "$WORK/grab.channels.xml" "$WORK/empty.json")
tail -5 "$WORK/grab.log"
[ "$N" -gt 100 ] || fail "pobrano tylko $N audycji - zostawiam poprzednie pliki (szczegoly: $WORK/grab.log)"
log "pobrano $N audycji dla $NCH kanalow"

# 5. publikacja (najpierw do plikow tymczasowych, potem podmiana - odbiornik nigdy nie dostanie polowy pliku)
xz -9 -c "$WORK/guide.xml" > "$WWW/.pl.xml.xz" && mv "$WWW/.pl.xml.xz" "$WWW/pl.xml.xz"
xz -9 -c "$WORK/pl.channels.xml" > "$WWW/.pl.channels.xml.xz" && mv "$WWW/.pl.channels.xml.xz" "$WWW/pl.channels.xml.xz"
bash "$BASE/sources.sh"
IP=${SERVER_IP:-$(hostname -I | awk '{print $1}')}
log "gotowe: http://$IP:$PORT/pl.xml.xz ($(du -k $WWW/pl.xml.xz | cut -f1) kB), raport: http://$IP:$PORT/raport.txt"
report ok
for f in mapa.json stacje.json; do cp "$WORK/$f" "$WWW/.$f" && mv "$WWW/.$f" "$WWW/$f"; done
# znacznik pelnego, udanego pobrania - dla dekodera (pobieranie w jednym miejscu: dekoder bierze gotowa paczke)
python3 -I -c 'import json, sys, time
m = json.load(open(sys.argv[1], encoding="utf-8"))
tot = len({(c["tag"], c["site_id"]) for c in m.get("channels", []) if c.get("tag")})
json.dump({"time": int(time.time()), "source": "serwer", "days": int(sys.argv[2]), "channels": int(sys.argv[3]),
           "total": tot}, open(sys.argv[4], "w"))' "$WWW/mapa.json" "$DAYS" "${NCH:-0}" "$WWW/.pelny.json" && mv "$WWW/.pelny.json" "$WWW/pelny.json"

# 6. kandydaci do okna wyboru stacji - osobna usluga epgpl-kandydaci.service (OnSuccess), dekoder nie czeka

ls -1t "$LOGD"/run-*.log | tail -n +$((KEEP_LOGS + 1)) | xargs -r rm -f
