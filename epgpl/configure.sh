#!/bin/bash
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - konfiguracja: adres dekodera, port HTTP, godzina pobierania, adres serwera.
# Uruchamiana przez install.sh; pozniej recznie: sudo bash /opt/epgpl/configure.sh
#   bez opcji: podstawowe (dekoder, port, godzina, adres) + pytanie o ustawienia pobierania
#   --podstawowe / --pobieranie: tylko jedna czesc
[ "$(id -u)" = 0 ] || { echo "Uruchom przez sudo: sudo bash $0"; exit 1; }
DIR=$(cd "$(dirname "$0")" && pwd)
. "$DIR/common.sh"
BASE=/opt/epgpl
CONF=$BASE/config.env
[ -f $CONF ] || die "brak $CONF - najpierw: sudo bash install.sh"
MODE=all
case "$1" in --podstawowe) MODE=basic ;; --pobieranie) MODE=grab ;; esac

if [ "$MODE" != grab ]; then
head1 "Konfiguracja podstawowa"

# --- dekoder (OpenWebif): lista kanalow do mapy ---
REC=${EPGPL_RECEIVER:-$(conf_get RECEIVER)}
while :; do
	REC=$(input "Adres IP dekodera (Enigma2 z OpenWebif)" "$REC")
	if [ -z "$REC" ]; then
		err "adres dekodera jest wymagany (z niego serwer bierze liste Twoich kanalow)"
		[ "$YES" = 1 ] && exit 1
		continue
	fi
	valid_ip "$REC" || { err "to nie jest adres IPv4: $REC"; [ "$YES" = 1 ] && exit 1; REC=""; continue; }
	if INFO=$(curl -sf -m 6 "http://$REC/api/statusinfo"); then
		MODEL=$(echo "$INFO" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('brand',''), d.get('model',''), '-', d.get('imagedistro',''), d.get('imagever',''))" 2>/dev/null)
		ok "dekoder odpowiada: ${MODEL:-OpenWebif}"
		break
	fi
	err "dekoder $REC nie odpowiada (http://$REC/api/statusinfo) - wlaczony? OpenWebif bez hasla?"
	ask "Zapisac mimo to (sprawdzisz pozniej)?" N && break
	[ "$YES" = 1 ] && exit 1
done
conf_set RECEIVER "$REC"

# --- port HTTP ---
PORT=$(conf_get PORT); PORT=${PORT:-8088}
while :; do
	P=$(input "Port serwera HTTP dla dekodera" "$PORT")
	echo "$P" | grep -Eq '^[0-9]+$' && [ "$P" -ge 1024 ] && [ "$P" -le 65535 ] || { err "port 1024-65535"; [ "$YES" = 1 ] && exit 1; continue; }
	USED=$(ss -ltnp 2>/dev/null | awk -v p=":$P" '$4 ~ p"$" {print $6}' | head -1)
	if [ -n "$USED" ] && ! echo "$USED" | grep -q "python3"; then
		err "port $P jest zajety: $USED"
		[ "$YES" = 1 ] && exit 1
		continue
	fi
	break
done
conf_set PORT "$P"

# --- godzina pobierania ---
T=$(conf_get GRAB_TIME); T=${T:-01:15}
while :; do
	NT=$(input "Godzina codziennego pobierania programu (kilka: 03:00,15:00)" "$T")
	echo "$NT" | grep -Eq '^([0-2][0-9]:[0-5][0-9])(,[0-2][0-9]:[0-5][0-9])*$' && break
	err "format HH:MM (np. 01:15)"
	[ "$YES" = 1 ] && exit 1
done
conf_set GRAB_TIME "$NT"

# --- adres serwera w plikach dla dekodera ---
read -r MYIP MYNET <<< "$(lan_info)"
SIP=$(conf_get SERVER_IP)
info "Adres, pod ktorym dekoder laczy sie z serwerem (wpisywany do pliku zrodla epgpl.sources.xml)."
info "Teraz wykryty: ${MYIP:-?} (podsiec ${MYNET:-?})."
info "Enter = wykrywany automatycznie (zalecane w sieci domowej)."
info "Wpisz tylko: nazwe (np. server-linux.local), inny adres, gdy serwer ma ich kilka,"
info "albo adres publiczny/domene przy udostepnianiu w internecie."
SIP=$(input "Adres serwera dla dekodera [Enter = automatycznie]" "$SIP")
conf_set SERVER_IP "$SIP"

fi

# --- ustawienia pobierania (zaawansowane) ---
num_in() {   # num_in KLUCZ "pytanie" domyslna min max
	local v
	while :; do
		v=$(input "$2" "$(conf_get $1)")
		v=${v:-$3}
		echo "$v" | grep -Eq '^[0-9]+$' && [ "$v" -ge "$4" ] && [ "$v" -le "$5" ] && break
		err "liczba $4-$5"
		[ "$YES" = 1 ] && { v=$3; break; }
	done
	conf_set $1 "$v"
}
if [ "$MODE" = grab ] || { [ "$MODE" = all ] && head1 "Ustawienia pobierania" && \
	info "Serwisy (kolejnosc pierwszenstwa): $(sites_now)   opoznienie: $(conf_get GRAB_DELAY) ms   polaczen na serwis: $(conf_get MAX_CONNECTIONS)" && \
	info "Dni: $(conf_get DAYS)   dzien poprzedni: $(conf_get GRAB_YESTERDAY)   okno dekodera: $(conf_get TUNER_WINDOW) h   logow: $(conf_get KEEP_LOGS)   kandydaci: $(conf_get KANDYDACI)" && \
	ask "Zmienic ustawienia pobierania (kolejnosc serwisow, opoznienie, polaczenia, dni, kandydaci)?" N; }; then
	[ "$MODE" = grab ] && head1 "Ustawienia pobierania"
	echo
	DS=$(python3 -I -c 'import json,sys; print(",".join(json.load(open(sys.argv[1])).get("sites") or []))' $BASE/work/bukiety.json 2>/dev/null)
	if [ -n "$DS" ]; then
		info "UWAGA: kolejnosc serwisow ustawia dekoder (EPG Scan -> Ustawienia -> Program z internetu): $DS"
		info "Ponizsze SITES obowiazuje tylko, gdy dekoder jej nie przysle."
	fi
	info "Serwisy z programem w kolejnosci pierwszenstwa: kanal bierze program z pierwszego serwisu, ktory go ma."
	info "Dostepne: teleman (najlepsze opisy), onet, wp (pilot.wp.pl), naz (programtv.naziemna.info - regionalne TVP3)."
	info "Mozna pominac serwis, np. teleman,onet,naz."
	while :; do
		v=$(input "Serwisy" "$(conf_get SITES)")
		v=$(echo "$v" | tr -d ' ' | tr 'A-Z' 'a-z')
		bad=$(echo "$v" | tr ',' '\n' | grep -vxE 'teleman|tm|onet|wp|pilot|naz|naziemna' | head -1)
		[ -n "$v" ] && [ -z "$bad" ] && break
		err "nieznany serwis: ${bad:-pusto} (teleman, onet, wp, naz)"
		[ "$YES" = 1 ] && { v=$(conf_get SITES); break; }
	done
	conf_set SITES "$v"
	echo
	info "Opoznienie [ms] po kazdej stronie na kazdym polaczeniu. Domyslnie 3000 - tak podaje iptv-org (zrodlo grabbera):"
	info "bezpiecznie dla serwisow, pelne pobieranie trwa wtedy ok. godziny (w nocy to nie przeszkadza)."
	info "Nizej = szybciej (500 -> ok. 3 min dla 180 stacji), ale serwisy moga zaczac odrzucac zapytania (HTTP 429/403,"
	info "zerwane polaczenia): kanaly zostaja bez programu, a przy duzej przesadzie serwis moze czasowo zablokowac adres IP."
	info "Nie schodz ponizej 300. Bledy 429 w logu -> wroc do 3000."
	while :; do
		v=$(input "Opoznienie [ms] (iptv-org: 3000)" "$(conf_get GRAB_DELAY)")
		v=${v:-3000}
		echo "$v" | grep -Eq '^[0-9]+$' && [ "$v" -le 10000 ] || { err "0-10000"; [ "$YES" = 1 ] && { v=3000; break; }; continue; }
		[ "$v" -ge 300 ] && break
		ask "Ponizej 300 ms ryzyko blokady przez serwisy jest duze. Zostawic $v ms?" N && break
		[ "$YES" = 1 ] && { v=3000; break; }
	done
	conf_set GRAB_DELAY "$v"
	echo
	info "Rownolegle polaczenia na kazdy serwis (serwisy i tak pobierane sa jednoczesnie). Wiecej niz 3 - ryzyko blokad;"
	info "przy bledach skrypt sam zmniejsza liczbe polaczen. Zalecane: 2."
	num_in MAX_CONNECTIONS "Polaczen na serwis" 2 1 6
	info "Ile dni programu pobierac (dekoder pobiera wlasna liczbe dni - Ustawienia EPG Scan). Zalecane: 7."
	num_in DAYS "Dni" 7 1 14
	info "Dzien poprzedni: trwajaca noc jest na stronach serwisow pod poprzednim dniem - bez tego po polnocy kanaly"
	info "nie maja biezacej audycji. Zalecane: 1."
	num_in GRAB_YESTERDAY "Pobierac dzien poprzedni (1 = tak, 0 = nie)" 1 0 1
	info "Okno dekodera [h]: tylko gdy w EPG Scan wylaczone jest \"Pobieranie w jednym miejscu\" (z nim serwer i dekoder"
	info "same ustalaja, kto pobiera). Gdy EPG Scan uruchamia pobieranie na serwerze w swoim harmonogramie, wlasne"
	info "pobieranie serwera w oknie +-N h jest pomijane. 0 = serwer pobiera zawsze. Zalecane: 3."
	num_in TUNER_WINDOW "Okno dekodera [h]" 3 0 12
	num_in KEEP_LOGS "Ile logow pobierania przechowywac" 7 1 60
	echo
	info "Program kandydatow: po kazdym pobraniu program na dobe dla stacji, ktore okno wyboru stacji w EPG Scan proponuje"
	info "kanalom z bukietow (ok. 1000 stacji, dodatkowe zapytania do serwisow; tylko podglad, nie trafia do EPG)."
	info "Bez tego: program kandydatow na zadanie z okna wyboru (Menu). Ustawienie w EPG Scan (Program z internetu ->"
	info "Codziennie program kandydatow) ma pierwszenstwo. Zalecane: 0."
	num_in KANDYDACI "Codziennie program kandydatow (1 = tak, 0 = nie)" 0 0 1
fi

chown epgpl:epgpl $CONF 2>/dev/null
P=$(conf_get PORT); NT=$(conf_get GRAB_TIME)
ok "zapisano $CONF"

# uruchomione recznie (nie z install.sh): od razu zastosuj
if [ -z "$EPGPL_FROM_INSTALL" ]; then
	systemctl restart epgpl-http.service 2>/dev/null && ok "serwer HTTP uruchomiony ponownie (port $P)"
	bash $BASE/timer.sh >/dev/null && ok "pobieranie codziennie o $NT"
	sudo -u epgpl bash $BASE/sources.sh && ok "plik zrodla dla dekodera odswiezony"
	info "Zmieniony port? Uruchom jeszcze raz instalator (sudo bash install.sh) - poprawi zapore i Avahi."
fi
