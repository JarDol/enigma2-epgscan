#!/bin/bash
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - instalacja i aktualizacja serwera programu TV dla EPG Scan (Debian / Ubuntu).
# Kazdy krok najpierw sprawdza stan: gdy wymaganie jest spelnione - [OK], gdy trzeba cos zmienic - pyta.
#   sudo bash install.sh            instalacja / aktualizacja z pytaniami
#   sudo bash install.sh -y         bez pytan (wszystko "tak"; adres dekodera: --dekoder IP)
#   sudo bash install.sh --rekonfiguracja   aktualizacja z pytaniami o wszystkie ustawienia
#   opcje: --dekoder IP  --bez-avahi  --bez-zapory
# Aktualizacja (domyslnie): ustawienia i wczesniejsze decyzje zostaja, pytania tylko o nowe funkcje.
set -e
[ "$(id -u)" = 0 ] || { echo "Uruchom przez sudo: sudo bash install.sh"; exit 1; }
SRC=$(cd "$(dirname "$0")" && pwd)
. "$SRC/common.sh"
BASE=/opt/epgpl
NEW=0
AVAHI=${AVAHI:-1}
FIREWALL=1
while [ $# -gt 0 ]; do
	case "$1" in
		-y|--tak) YES=1 ;;
		--dekoder) shift; export EPGPL_RECEIVER="$1" ;;
		--bez-avahi) AVAHI=0 ;;
		--bez-zapory) FIREWALL=0 ;;
		--rekonfiguracja) REKONF=1 ;;
		-h|--help) sed -n 2,6p "$0"; exit 0 ;;
		*) echo "Nieznana opcja: $1"; exit 1 ;;
	esac
	shift
done
export YES
VER=$(cat "$SRC/VERSION")
echo "${C_H}epgpl $VER - instalacja serwera programu TV dla EPG Scan${C_0}"
UPD=0
if [ -d $BASE ] && [ -f $BASE/VERSION ]; then
	echo "Zainstalowana wersja: $(cat $BASE/VERSION) -> $VER"
	if [ "$REKONF" = 1 ]; then
		UPD=0
	elif [ "$YES" = 1 ]; then
		UPD=1
	else
		echo "  1 = aktualizacja: ustawienia i wczesniejsze decyzje zostaja, pytania tylko o nowe funkcje (zalecane)"
		echo "  2 = aktualizacja i ponowna konfiguracja: pytania o wszystkie ustawienia"
		while :; do
			M=$(input "Wybierz" "1")
			case "$M" in 1) UPD=1; break ;; 2) UPD=0; break ;; esac
		done
	fi
else
	NEW=1
fi
DEC=$BASE/.decyzje        # odpowiedzi na pytania "wlaczyc?" - przy aktualizacji nie sa powtarzane
decided() { grep -qx "$1" $DEC 2>/dev/null; }
decide() { mkdir -p $BASE; sed -i "/^${1%%=*}=/d" $DEC 2>/dev/null; echo "$1" >> $DEC; }
# pytanie o zmiane czegos, co juz dziala - przy aktualizacji pomijane
change_ask() { [ "$UPD" = 1 ] && return 1; ask "$@"; }
# pytanie o wlaczenie (klucz decyzji, pytanie, domyslna) - przy aktualizacji tylko, gdy jeszcze nie bylo odpowiedzi
enable_ask() {
	local key=$1; shift
	if [ "$UPD" = 1 ] && decided "$key=nie"; then info "pominieto - wczesniej: nie (zmiana: sudo bash install.sh --rekonfiguracja)"; return 1; fi
	if ask "$@"; then decide "$key=tak"; return 0; fi
	decide "$key=nie"
	return 1
}

# ----------------------------------------------------------------------------- 1. system
head1 "1. System"
. /etc/os-release 2>/dev/null || true
case "${ID:-}${ID_LIKE:-}" in
	*debian*|*ubuntu*) ok "system: ${PRETTY_NAME:-$ID}" ;;
	*) err "system ${PRETTY_NAME:-nieznany} - instalator obsluguje Debian/Ubuntu (apt)"; ask "Kontynuowac mimo to?" N || exit 1 ;;
esac
command -v systemctl >/dev/null || die "brak systemd (systemctl)"
ok "systemd"
case $(uname -m) in x86_64|aarch64|armv7l) ok "architektura $(uname -m)" ;; *) die "nieobslugiwana architektura $(uname -m)" ;; esac
FREE=$(df -Pm /opt | awk 'NR==2{print $4}')
[ "${FREE:-0}" -ge 1500 ] && ok "wolne miejsce w /opt: ${FREE} MB" || { err "wolne miejsce w /opt: ${FREE} MB (potrzeba ok. 1,5 GB)"; ask "Kontynuowac?" N || exit 1; }
if systemctl is-active --quiet epgpl.service || systemctl is-active --quiet epgpl-auto.service; then
	die "trwa pobieranie programu (epgpl.service) - zainstaluj po jego zakonczeniu albo: sudo systemctl stop epgpl.service"
fi

# ----------------------------------------------------------------------------- 2. pakiety
head1 "2. Pakiety systemowe"
PKGS="git xz-utils python3 curl iproute2 sudo"
[ "$AVAHI" = 1 ] && PKGS="$PKGS avahi-daemon"
MISSING=""
for p in $PKGS; do
	if dpkg -s "$p" >/dev/null 2>&1; then ok "$p"; else MISSING="$MISSING $p"; todo "$p - brak"; fi
done
if [ -n "$MISSING" ]; then
	if ask "Zainstalowac:$MISSING?"; then
		apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $MISSING >/dev/null
		for p in $MISSING; do state_add "pakiet $p"; done
		ok "zainstalowano:$MISSING"
	else
		echo "$MISSING" | grep -q avahi-daemon && AVAHI=0
		for p in $MISSING; do [ "$p" = avahi-daemon ] || die "bez pakietu $p instalacja nie jest mozliwa"; done
	fi
fi

# ----------------------------------------------------------------------------- 3. Node.js
head1 "3. Node.js 22 (dla grabbera iptv-org/epg)"
NODE=$BASE/node
if [ -x $NODE/bin/node ] && [ "$($NODE/bin/node -v | sed 's/v//;s/\..*//')" -ge 22 ]; then
	ok "prywatna kopia $($NODE/bin/node -v) w $NODE (systemowy Node.js nie jest ruszany)"
else
	todo "Node.js 22 nie zainstalowany - prywatna kopia w $NODE (ok. 30 MB, systemowy Node.js bez zmian)"
	ask "Pobrac Node.js 22 z nodejs.org?" || die "Node.js 22 jest wymagany"
	case $(uname -m) in x86_64) ARCH=x64 ;; aarch64) ARCH=arm64 ;; armv7l) ARCH=armv7l ;; esac
	URL=https://nodejs.org/dist/latest-v22.x
	LINE=$(curl -fsS $URL/SHASUMS256.txt | grep " node-v.*-linux-$ARCH.tar.xz$")
	FILE=$(echo "$LINE" | awk '{print $2}')
	[ -n "$FILE" ] || die "nie znaleziono Node.js 22 dla $ARCH"
	curl -fsS -o /tmp/$FILE $URL/$FILE
	(cd /tmp && echo "$(echo $LINE | awk '{print $1}')  $FILE" | sha256sum -c --quiet) || die "bledna suma kontrolna Node.js"
	rm -rf $NODE && mkdir -p $NODE
	tar -xJf /tmp/$FILE -C $NODE --strip-components=1
	rm -f /tmp/$FILE
	ok "Node.js $($NODE/bin/node -v)"
fi
export PATH=$NODE/bin:$PATH

# ----------------------------------------------------------------------------- 4. uzytkownik i pliki
head1 "4. Uzytkownik i pliki programu"
if id epgpl >/dev/null 2>&1; then
	ok "uzytkownik systemowy epgpl"
else
	todo "uzytkownik systemowy epgpl (bez logowania) - pod nim dziala pobieranie i serwer HTTP"
	ask "Utworzyc uzytkownika epgpl?" || die "uzytkownik epgpl jest wymagany"
	useradd -r -d $BASE -s /usr/sbin/nologin epgpl
	state_add "uzytkownik epgpl"
	ok "utworzono"
fi
mkdir -p $BASE
[ "$NEW" = 1 ] && todo "pliki programu -> $BASE" || todo "aktualizacja plikow programu w $BASE ($(cat $BASE/VERSION) -> $VER)"
install -m 755 "$SRC/run.sh" "$SRC/sources.sh" "$SRC/build_map.py" "$SRC/site_channels.py" "$SRC/alternatives.py" "$SRC/stacje.sh" "$SRC/kandydaci.py" "$SRC/kandydaci.sh" "$SRC/jedno_miejsce.py" "$SRC/check_guide.py" "$SRC/fix_guide.py" \
	"$SRC/grab_tools.py" "$SRC/server.py" "$SRC/mapa.sh" "$SRC/migrate_aliases.py" "$SRC/tuner_skip.py" "$SRC/carry_over.py" \
	"$SRC/timer.sh" "$SRC/configure.sh" "$SRC/common.sh" "$SRC/uninstall.sh" $BASE/
mkdir -p $BASE/sites $BASE/tools && install -m 755 "$SRC"/tools/*.py $BASE/tools/
# grabbery (config.js) zawsze z paczki; listy stacji (channels.xml) tylko gdy ich brak - odswiezone ze stron sa nowsze
for d in "$SRC"/sites/*/; do
	n=$(basename "$d"); mkdir -p $BASE/sites/$n
	for f in "$d"*; do
		case "$f" in *.channels.xml) [ -s $BASE/sites/$n/$(basename "$f") ] || cp "$f" $BASE/sites/$n/ ;; *) cp "$f" $BASE/sites/$n/ ;; esac
	done
done
rm -f $BASE/teleman_channels.py
install -m 644 "$SRC/VERSION" "$SRC/aliases.dist" "$SRC/aliases.legacy.txt" "$SRC/README.txt" "$SRC/README.md" "$SRC/LICENSE" "$SRC/THIRD_PARTY.md" $BASE/
# opis serwera dla dekodera (EPG Scan -> Raporty -> Opis serwera epgpl)
mkdir -p $BASE/www && install -m 644 "$SRC/README.md" $BASE/www/README.md
[ -f $BASE/config.env ] || install -m 644 "$SRC/config.env" $BASE/
# uzupelnienie config.env ze starszych wersji
SITESLINE="SITES=teleman,onet,wp,naz      # zrodla programu w kolejnosci pierwszenstwa: teleman, onet, wp (pilot.wp.pl), naz (programtv.naziemna.info), cp (Canal+ - nie dziala)"
if ! grep -q "^SITES=" $BASE/config.env; then
	sed -i "/^SCOPE=/i $SITESLINE" $BASE/config.env
elif grep -qE "^SITES=(onet|onet,teleman|onet,teleman,wp) *#" $BASE/config.env; then
	sed -i -E "s~^SITES=(onet|onet,teleman|onet,teleman,wp) *#.*~$SITESLINE~" $BASE/config.env
elif ! grep -qE "^SITES=[^ #]*naz" $BASE/config.env; then
	sed -i -E "s~^(SITES=[^ #]+)~\1,naz~" $BASE/config.env
fi
grep -q "^COPIES=" $BASE/config.env || sed -i "/^SKIP_BOUQUETS=/i COPIES=tp                      # kopie kanalow w mapie: tp = tylko z transponderow z Twoimi kanalami (bez obcych platform), all = wszystkie" $BASE/config.env
grep -q "^GRAB_TIME=" $BASE/config.env || {
	OLD=$(sed -n 's/^OnCalendar=[*]-[*]-[*] \([0-9:,]*\).*/\1/p' /etc/systemd/system/epgpl.timer 2>/dev/null)
	echo "GRAB_TIME=${OLD:-01:15}                # godzina codziennego pobierania (kilka: 03:00,15:00); po zmianie: sudo bash /opt/epgpl/configure.sh" >> $BASE/config.env; }
grep -q "^GRAB_DELAY=" $BASE/config.env || echo "GRAB_DELAY=3000                # przerwa [ms] po kazdej stronie na kazdym polaczeniu; 3000 = jak iptv-org (ok. 1 h), 500 = ok. 3 min, ale ryzyko blokad (429); nie mniej niz 300" >> $BASE/config.env
grep -q "^GRAB_YESTERDAY=" $BASE/config.env || echo "GRAB_YESTERDAY=1               # pobieraj tez dzien poprzedni (trwajaca noc); 0 = nie" >> $BASE/config.env
grep -q "^TUNER_WINDOW=" $BASE/config.env || echo "TUNER_WINDOW=3                 # harmonogram serwera pomijany, gdy dekoder pobiera w oknie +-N h; 0 = nigdy" >> $BASE/config.env
if [ -f $BASE/aliases.txt ] && grep -q "^# epgpl - reczne dopasowania: Nazwa kanalu na satelicie = zrodlo:site_id" $BASE/aliases.txt; then
	mv $BASE/aliases.txt $BASE/aliases.txt.stary
fi
[ -f $BASE/aliases.txt ] || install -m 644 "$SRC/aliases.txt" $BASE/
# 0.14.0: wskazania z dekodera - jedna nazwa pliku na dekoderze i serwerze (wskazania.txt)
if [ -f $BASE/aliases.tuner.txt ] && [ ! -f $BASE/wskazania.txt ]; then
	mv $BASE/aliases.tuner.txt $BASE/wskazania.txt
	todo "aliases.tuner.txt -> wskazania.txt (ta sama nazwa co na dekoderze)"
fi
chown -R epgpl:epgpl $BASE
state_add "katalog $BASE"
ok "pliki w $BASE"

# ----------------------------------------------------------------------------- 5. grabber
head1 "5. Grabber iptv-org/epg"
if [ -d $BASE/iptv-epg/.git ]; then
	ok "repozytorium iptv-org/epg"
else
	todo "pobranie repozytorium github.com/iptv-org/epg (ok. 200 MB)"
	ask "Pobrac?" || die "grabber jest wymagany"
	sudo -u epgpl git clone -q --depth 1 https://github.com/iptv-org/epg $BASE/iptv-epg
	ok "pobrano"
fi
LOCK=$(sha1sum $BASE/iptv-epg/package-lock.json | cut -d' ' -f1)
if [ -d $BASE/iptv-epg/node_modules ] && [ "$(cat $BASE/work/lock.sha1 2>/dev/null)" = "$LOCK" ]; then
	ok "zaleznosci npm aktualne"
else
	todo "instalacja zaleznosci npm grabbera (kilka minut)"
	ask "Zainstalowac?" || die "zaleznosci npm sa wymagane"
	(cd $BASE/iptv-epg && sudo -u epgpl env PATH="$PATH" HOME=$BASE npm ci --no-audit --no-fund --loglevel=error)
	sudo -u epgpl mkdir -p $BASE/work $BASE/www
	echo "$LOCK" | sudo -u epgpl tee $BASE/work/lock.sha1 >/dev/null
	ok "zainstalowano"
fi
sudo -u epgpl mkdir -p $BASE/work $BASE/www $BASE/log

# ----------------------------------------------------------------------------- 6. konfiguracja
head1 "6. Konfiguracja (dekoder, port, godzina, pobieranie)"
REC=$(conf_get RECEIVER)
if [ -z "$REC" ] || [ -n "$EPGPL_RECEIVER" ]; then
	EPGPL_FROM_INSTALL=1 bash $BASE/configure.sh --podstawowe || die "konfiguracja nieudana"
else
	ok "dekoder $REC, port $(conf_get PORT), pobieranie o $(conf_get GRAB_TIME), adres serwera $(conf_get SERVER_IP | sed 's/^$/automatycznie/')"
	if change_ask "Zmienic konfiguracje podstawowa (dekoder, port, godzina, adres)?" N; then
		EPGPL_FROM_INSTALL=1 bash $BASE/configure.sh --podstawowe || die "konfiguracja nieudana"
	fi
fi
ok "serwisy (kolejnosc): $(sites_now), opoznienie $(conf_get GRAB_DELAY) ms, polaczen na serwis $(conf_get MAX_CONNECTIONS), dni $(conf_get DAYS), codziennie kandydaci: $(cand_now)"
if change_ask "Zmienic ustawienia pobierania (kolejnosc serwisow, opoznienie, polaczenia, dni, kandydaci)?" N; then
	EPGPL_FROM_INSTALL=1 bash $BASE/configure.sh --pobieranie || die "konfiguracja nieudana"
fi
PORT=$(conf_get PORT); PORT=${PORT:-8088}

# nowe funkcje: opcja, ktorej nie ma jeszcze w config.env - jedno pytanie z opisem (takze przy aktualizacji)
new_feature() {     # klucz wersja "opis" "pytanie" T|N
	grep -q "^$1=" $BASE/config.env && return 0
	[ -n "$NF_HEAD" ] || { echo; echo "  ${C_H}Nowe funkcje${C_0}"; NF_HEAD=1; }
	info "$2: $3"
	local v=0
	ask "$4" "$5" && v=1
	echo "$1=$v$(printf '%*s' $((28 - ${#1})) '')# $3" >> $BASE/config.env
	chown epgpl:epgpl $BASE/config.env
	[ $v = 1 ] && ok "$1 wlaczone" || ok "$1 wylaczone (wlaczenie: $1=1 w $BASE/config.env)"
}
new_feature KANDYDACI 0.16.0 "program na dobe dla kandydatow z okna wyboru stacji w EPG Scan (po nocnym pobieraniu, ok. 1000 stacji, tylko podglad; ustawienie z EPG Scan ma pierwszenstwo)" \
	"Wlaczyc codzienny program kandydatow?" N

# wskazania kanalow z dekodera (EPG Scan): przy nowej instalacji albo pustych wskazaniach - przez OpenWebif
REC=$(conf_get RECEIVER)
if [ -n "$REC" ] && { [ "$UPD" = 0 ] || [ ! -s $BASE/wskazania.txt ]; }; then
	TMPW=$(mktemp)
	for f in /etc/enigma2/epgscan/wskazania.txt /etc/enigma2/epgscan_kanaly.txt; do
		curl -sf -m 8 "http://$REC/file?file=$f" -o "$TMPW" 2>/dev/null && grep -q " = " "$TMPW" && break
		: > "$TMPW"
	done
	NW=$(grep -cE "^[^#].* = " "$TMPW" 2>/dev/null || true)
	norm_pins() { tr -d '\r' < "$1" 2>/dev/null | grep -E "^[^#].* = " | sed 's/ *= */ = /' | sort; }
	if [ "${NW:-0}" -gt 0 ] && [ "$(norm_pins "$TMPW")" = "$(norm_pins $BASE/wskazania.txt)" ]; then
		ok "wskazania kanalow takie same na dekoderze i serwerze ($NW)"
	elif [ "${NW:-0}" -gt 0 ]; then
		NS=$(grep -cE "^[^#].* = " $BASE/wskazania.txt 2>/dev/null || true)
		ND=$(diff <(norm_pins "$TMPW") <(norm_pins $BASE/wskazania.txt) | grep -c "^[<>]" || true)
		echo
		info "Na dekoderze $REC jest ${NW} recznych wskazan kanalow (EPG Scan), na serwerze ${NS:-0}; roznych linii: ${ND:-?}."
		info "Ta sama mapa kanalow po obu stronach = ten sam program. Wskazania serwera zostana zastapione (kopia: wskazania.txt.przed)."
		if ask "Wziac wskazania kanalow z dekodera?" T; then
			[ -s $BASE/wskazania.txt ] && cp $BASE/wskazania.txt $BASE/wskazania.txt.przed
			{ echo "# epgpl - wskazania ustawione z tunera (EPG Scan -> Dopasowanie kanalow). Pierwszenstwo przed automatem."
			  echo "# Przeniesione z dekodera $REC przy instalacji $(date '+%d.%m.%Y %H:%M')."
			  tr -d '\r' < "$TMPW" | grep -E "^[^#].* = " ; } > $BASE/wskazania.txt
			chown epgpl:epgpl $BASE/wskazania.txt
			ok "wskazania z dekodera: $NW"
		fi
	elif [ "$UPD" = 0 ] || [ "$NEW" = 1 ]; then
		info "Wskazan kanalow z dekodera nie udalo sie odczytac przez OpenWebif - EPG Scan zaproponuje synchronizacje"
		info "przy otwarciu Dopasowania kanalow (ustawienie: Program z internetu -> Roznice w dopasowaniu)."
	fi
	rm -f "$TMPW"
fi

# ----------------------------------------------------------------------------- 7. uslugi systemd
head1 "7. Uslugi systemd (serwer HTTP i codzienne pobieranie)"
CHANGED=0
for u in epgpl.service epgpl-auto.service epgpl-http.service epgpl-kandydaci.service; do
	if cmp -s "$SRC/systemd/$u" /etc/systemd/system/$u; then ok "$u"; else todo "$u - nowa / zmieniona"; CHANGED=1; fi
done
if [ "$CHANGED" = 1 ]; then
	ask "Zainstalowac uslugi systemd?" || die "uslugi sa wymagane"
	install -m 644 "$SRC"/systemd/epgpl.service "$SRC"/systemd/epgpl-auto.service "$SRC"/systemd/epgpl-http.service "$SRC"/systemd/epgpl-kandydaci.service /etc/systemd/system/
	state_add "systemd"
fi
systemctl daemon-reload
systemctl enable epgpl-http.service >/dev/null 2>&1
systemctl restart epgpl-http.service
ok "serwer HTTP dziala (port $PORT)"
bash $BASE/timer.sh >/dev/null
ok "pobieranie codziennie o $(conf_get GRAB_TIME) (epgpl.timer)"
if [ -s $BASE/work/guide.xml ] && [ -s $BASE/work/grab.channels.xml ]; then
	sudo -u epgpl python3 -I $BASE/check_guide.py $BASE/work/guide.xml $BASE/work/grab.channels.xml $BASE/work/empty.json >/dev/null || true
fi
sudo -u epgpl bash $BASE/sources.sh
ok "plik zrodla dla dekodera: epgpl.sources.xml"

# ----------------------------------------------------------------------------- 8. uprawnienie
head1 "8. Pobieranie na zadanie z dekodera"
RULE="epgpl ALL=(root) NOPASSWD: /usr/bin/systemctl start --no-block epgpl.service, /bin/systemctl start --no-block epgpl.service"
if [ -f /etc/sudoers.d/epgpl ] && grep -qF "$RULE" /etc/sudoers.d/epgpl; then
	ok "uprawnienie /etc/sudoers.d/epgpl jest wlaczone (tylko start pobierania)"
	if change_ask "Wylaczyc (dekoder nie bedzie mogl uruchomic pobierania na serwerze)?" N; then
		rm -f /etc/sudoers.d/epgpl
		ok "wylaczono"
	fi
else
	todo "regula sudo: uzytkownik epgpl moze uruchomic wylacznie: systemctl start epgpl.service"
	info "(EPG Scan -> Dopasowanie kanalow -> pobierz na serwerze; bez tego dekoder nie uruchomi pobierania)"
	if enable_ask sudo "Dodac regule?"; then
		printf '# epgpl: uruchomienie pobierania programu z dekodera (EPG Scan)\n%s\n' "$RULE" > /etc/sudoers.d/epgpl.tmp
		chmod 440 /etc/sudoers.d/epgpl.tmp
		if visudo -cf /etc/sudoers.d/epgpl.tmp >/dev/null; then mv /etc/sudoers.d/epgpl.tmp /etc/sudoers.d/epgpl; state_add "sudoers"; ok "dodano"
		else rm -f /etc/sudoers.d/epgpl.tmp; err "nie udalo sie dodac reguly"; fi
	fi
fi

# ----------------------------------------------------------------------------- 9. zapora
head1 "9. Zapora sieciowa"
read -r MYIP MYNET <<< "$(lan_info)"
if [ "$FIREWALL" = 0 ]; then
	info "pominieto (--bez-zapory)"
elif command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
	if ufw status | grep -E "^$PORT/tcp|^$PORT " | grep -q ALLOW; then
		ok "ufw: port $PORT otwarty"
	else
		todo "ufw jest wlaczony: otwarcie portu $PORT/tcp dla sieci lokalnej ${MYNET:-?}"
		if [ -n "$MYNET" ] && enable_ask ufw-port "Otworzyc port $PORT dla ${MYNET}?"; then
			ufw allow from "$MYNET" to any port "$PORT" proto tcp comment epgpl >/dev/null
			state_add "ufw allow from $MYNET to any port $PORT proto tcp"
			ok "otwarto"
		fi
	fi
	if [ "$AVAHI" = 1 ]; then
		if ufw status | grep -E "^5353/udp|^5353 " | grep -q ALLOW; then
			ok "ufw: 5353/udp (Avahi) otwarty"
		elif [ -n "$MYNET" ] && enable_ask ufw-mdns "Otworzyc 5353/udp (Avahi - dekoder sam znajdzie serwer) dla ${MYNET}?"; then
			ufw allow from "$MYNET" to any port 5353 proto udp comment epgpl >/dev/null
			state_add "ufw allow from $MYNET to any port 5353 proto udp"
			ok "otwarto"
		fi
	fi
elif command -v firewall-cmd >/dev/null && firewall-cmd --state >/dev/null 2>&1; then
	if firewall-cmd --list-ports | grep -qw "$PORT/tcp"; then
		ok "firewalld: port $PORT otwarty"
	else
		todo "firewalld jest wlaczony: otwarcie portu $PORT/tcp"
		if enable_ask fw-port "Otworzyc port $PORT/tcp?"; then
			firewall-cmd --permanent --add-port=$PORT/tcp >/dev/null && firewall-cmd --reload >/dev/null
			state_add "firewalld $PORT/tcp"
			ok "otwarto"
		fi
	fi
	if [ "$AVAHI" = 1 ] && ! firewall-cmd --list-services | grep -qw mdns; then
		enable_ask fw-mdns "Wlaczyc w firewalld usluge mdns (Avahi)?" && firewall-cmd --permanent --add-service=mdns >/dev/null && \
			firewall-cmd --reload >/dev/null && state_add "firewalld mdns" && ok "wlaczono"
	fi
else
	ok "brak aktywnej zapory (ufw / firewalld) - nic do otwierania"
fi

# ----------------------------------------------------------------------------- 10. Avahi
head1 "10. Avahi - dekoder sam znajduje serwer"
AV=/etc/avahi/services/epgpl.service
if [ "$AVAHI" = 1 ] && [ -d /etc/avahi ]; then
	WANT=$(cat <<XML
<?xml version="1.0" standalone="no"?>
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<!-- epgpl: serwer programu TV dla EPG Scan (Enigma2) -->
<service-group>
	<name replace-wildcards="yes">epgpl na %h</name>
	<service>
		<type>_epgpl._tcp</type>
		<port>$PORT</port>
		<txt-record>path=/epgpl.sources.xml</txt-record>
		<txt-record>version=$VER</txt-record>
	</service>
</service-group>
XML
)
	if [ -f $AV ] && grep -q "<port>$PORT</port>" $AV; then
		ok "ogloszenie _epgpl._tcp jest wlaczone (port $PORT)"
		[ "$(cat $AV)" = "$WANT" ] || echo "$WANT" > $AV          # tylko numer wersji w ogloszeniu
		if change_ask "Wylaczyc ogloszenie Avahi (dekoder znajdzie serwer jeszcze przeszukujac podsiec)?" N; then
			rm -f $AV
			systemctl reload avahi-daemon >/dev/null 2>&1 || true
			ok "wylaczono"
		fi
	else
		[ -f $AV ] && todo "ogloszenie Avahi z innym portem - poprawa na $PORT" || \
			todo "ogloszenie serwera w sieci lokalnej jako _epgpl._tcp (port $PORT)"
		if enable_ask avahi "Wlaczyc ogloszenie Avahi?"; then
			mkdir -p /etc/avahi/services && echo "$WANT" > $AV
			state_add "avahi"
			ok "wlaczono"
		fi
	fi
	systemctl enable --now avahi-daemon >/dev/null 2>&1 || true
	systemctl is-active --quiet avahi-daemon && ok "avahi-daemon dziala" || err "avahi-daemon nie dziala"
else
	info "pominieto - dekoder znajdzie serwer przeszukujac podsiec"
fi

# ----------------------------------------------------------------------------- 11. jednorazowo: dawne aliasy -> wskazania
if [ ! -f $BASE/work/.aliasy_przeniesione ] && [ -s $BASE/work/kanaly.json ]; then
	sudo -u epgpl cp -r $BASE/sites/. $BASE/iptv-epg/sites/
	. $BASE/config.env
	sudo -u epgpl mkdir -p $BASE/work/migracja
	sudo -u epgpl python3 -I $BASE/build_map.py --services $BASE/work/kanaly.json --epgrepo $BASE/iptv-epg \
		--aliases $BASE/aliases.legacy.txt --aliases $BASE/aliases.txt --tuner-aliases $BASE/wskazania.txt \
		--empty $BASE/work/empty.json --copies "${COPIES:-tp}" --sites "${SITES:-teleman,onet,wp,naz}" \
		--scope "$SCOPE" --skip-bouquets "$SKIP_BOUQUETS" --out $BASE/work/migracja >/dev/null 2>&1 \
	&& sudo -u epgpl python3 -I $BASE/migrate_aliases.py $BASE/work/migracja/mapa.json $BASE/wskazania.txt \
	&& sudo -u epgpl touch $BASE/work/.aliasy_przeniesione || true
fi
[ -s $BASE/work/kanaly.json ] && sudo -u epgpl bash $BASE/mapa.sh >/dev/null 2>&1 || true

# ----------------------------------------------------------------------------- 12. sprawdzenie
head1 "11. Sprawdzenie"
sleep 1
if curl -sf -m 5 "http://127.0.0.1:$PORT/epgpl.sources.xml" | grep -q "<epgpl>"; then ok "serwer HTTP odpowiada: http://${MYIP:-127.0.0.1}:$PORT/"
else err "serwer HTTP nie odpowiada na porcie $PORT - journalctl -u epgpl-http"; fi
REC=$(conf_get RECEIVER)
curl -sf -m 6 "http://$REC/api/statusinfo" >/dev/null && ok "dekoder $REC odpowiada (OpenWebif)" || err "dekoder $REC nie odpowiada - przy pobieraniu uzyta zostanie ostatnia zapisana lista kanalow"
if [ -s $BASE/www/pl.xml.xz ]; then
	ok "plik programu z $(date -r $BASE/www/pl.xml.xz '+%d.%m %H:%M')"
else
	todo "brak pliku programu - pierwsze pobranie trwa ok. 5-60 min (zalezy od GRAB_DELAY i liczby kanalow)"
	if ask "Uruchomic pobieranie teraz (w tle)?"; then
		systemctl start --no-block epgpl.service
		ok "uruchomiono - postep: tail -f $BASE/work/grab.log"
	fi
fi

echo
echo "${C_H}Gotowe: epgpl $VER w $BASE${C_0}"
echo
echo "${C_H}Dekoder${C_0} (EPG Scan 2.3.1+): Ustawienia -> Program z internetu -> zolty -> niebieski -> Szukaj serwera epgpl w sieci"
echo "  albo adres recznie: http://${MYIP:-SERWER}:$PORT/epgpl.sources.xml"
echo "  Raport kanalow (ktore maja program i skad): http://${MYIP:-SERWER}:$PORT/raport.txt"
echo
echo "${C_H}Plik ustawien${C_0}: $BASE/config.env  (opisy przy kazdej opcji; edycja np.: sudo nano $BASE/config.env)"
echo "  Wiekszosc opcji dziala od nastepnego pobrania, bez restartu. Wyjatki:"
echo "    GRAB_TIME (godzina pobierania) -> sudo bash $BASE/timer.sh"
echo "    PORT                           -> sudo systemctl restart epgpl-http; zapora i Avahi: sudo bash install.sh"
echo "    SERVER_IP                      -> sudo -u epgpl bash $BASE/sources.sh (albo od nastepnego pobrania)"
echo
echo "${C_H}Ponowna konfiguracja${C_0} - w kazdej chwili:"
echo "  tylko zmiana parametrow (pytania z opisami):  sudo bash $BASE/configure.sh"
echo "  aktualizacja / naprawa, ustawienia zostaja:   sudo bash install.sh   (z rozpakowanej paczki; 1 = tylko nowe funkcje)"
echo "  aktualizacja ze wszystkimi pytaniami:         sudo bash install.sh --rekonfiguracja"
echo "  od zera, z usunieciem poprzedniej:            sudo bash $BASE/uninstall.sh   potem   sudo bash install.sh"
echo
echo "${C_H}Przydatne${C_0}:"
echo "  pobranie teraz:   sudo systemctl start --no-block epgpl.service;  postep: tail -f $BASE/work/grab.log"
echo "  stan uslug:       systemctl status epgpl-http epgpl.timer;  logi pobierania: $BASE/log/"
echo "  wlasne dopasowania kanalow: $BASE/aliases.txt  (z dekodera: EPG Scan -> Kanaly -> Dopasowanie: serwer)"
