#!/bin/bash
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - wspolne funkcje skryptow instalacji, konfiguracji i usuwania (dolaczane przez ". common.sh")

YES=${YES:-0}               # 1 = wszystkie pytania "tak" (opcja -y)

if [ -t 1 ]; then
	C_OK=$'\e[32m'; C_CH=$'\e[33m'; C_ERR=$'\e[31m'; C_H=$'\e[1m'; C_0=$'\e[0m'
else
	C_OK=""; C_CH=""; C_ERR=""; C_H=""; C_0=""
fi

head1() { echo; echo "${C_H}== $* ==${C_0}"; }
ok()    { echo "  ${C_OK}[OK]${C_0} $*"; }
todo()  { echo "  ${C_CH}[ZMIANA]${C_0} $*"; }
info()  { echo "  $*"; }
err()   { echo "  ${C_ERR}[BLAD]${C_0} $*"; }
die()   { err "$*"; exit 1; }

# ask "pytanie" [T|N]  -> 0 = tak
ask() {
	local q="$1" def="${2:-T}" a
	if [ "$YES" = 1 ]; then          # -y: odpowiedz domyslna (T = tak, N = nie)
		if [ "$def" = T ]; then echo "  $q -> tak (-y)"; return 0; fi
		echo "  $q -> nie (-y, domyslnie)"; return 1
	fi
	if [ "$def" = T ]; then q="$q [T/n] "; else q="$q [t/N] "; fi
	read -r -p "  $q" a </dev/tty || a=""
	a=$(echo "$a" | tr 'A-Z' 'a-z')
	[ -z "$a" ] && a=$(echo "$def" | tr 'A-Z' 'a-z')
	case "$a" in t|tak|y|yes) return 0 ;; *) return 1 ;; esac
}

# input "pytanie" "domyslna" -> echo odpowiedzi
input() {
	local q="$1" def="$2" a
	if [ "$YES" = 1 ]; then echo "$def"; return; fi
	read -r -p "  $q${def:+ [$def]}: " a </dev/tty || a=""
	echo "${a:-$def}"
}

valid_ip() {
	echo "$1" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$' || return 1
	local IFS=.; set -- $1
	[ "$1" -le 255 ] && [ "$2" -le 255 ] && [ "$3" -le 255 ] && [ "$4" -le 255 ]
}

# adres i podsiec glownego interfejsu (np. 192.168.1.10 192.168.1.0/24)
lan_info() {
	local dev cidr
	dev=$(ip route show default 2>/dev/null | awk '{print $5; exit}')
	cidr=$(ip -o -4 addr show ${dev:+dev $dev} scope global 2>/dev/null | awk '{print $4; exit}')
	[ -n "$cidr" ] || return 1
	python3 -c "import ipaddress,sys; i=ipaddress.ip_interface(sys.argv[1]); print(i.ip, i.network)" "$cidr"
}

# wpis w pliku stanu instalacji (co zostalo zmienione - dla uninstall.sh)
STATE=/opt/epgpl/.instalacja
state_add() {
	mkdir -p "$(dirname $STATE)"
	grep -qxF "$1" $STATE 2>/dev/null || echo "$1" >> $STATE
}

conf_get() {   # conf_get KLUCZ [plik]
	sed -n "s/^$1=\([^ #]*\).*/\1/p" "${2:-/opt/epgpl/config.env}" 2>/dev/null | tail -1 | tr -d '"'
}

conf_set() {   # conf_set KLUCZ WARTOSC [plik] - zmienia wartosc, komentarz w linii zostaje
	local f="${3:-/opt/epgpl/config.env}"
	if grep -q "^$1=" "$f"; then
		sed -i -E "s~^$1=[^ #]*~$1=$2~" "$f"
	else
		echo "$1=$2" >> "$f"
	fi
}

# codzienny program kandydatow: z dekodera (work/bukiety.json "cand"), a gdy nie przyslal - KANDYDACI z config.env
cand_now() {
	local v
	v=$(python3 -I -c 'import json,sys
c = json.load(open(sys.argv[1], encoding="utf-8")).get("cand")
print("" if c is None else ("tak" if c else "nie"))' /opt/epgpl/work/bukiety.json 2>/dev/null)
	if [ -n "$v" ]; then echo "$v (z dekodera)"; else [ "$(conf_get KANDYDACI)" = 1 ] && echo tak || echo nie; fi
}

# kolejnosc serwisow faktycznie uzywana: z dekodera (work/bukiety.json), a gdy jej nie przyslal - SITES z config.env
sites_now() {
	local ds
	ds=$(python3 -I -c 'import json,sys
m = {"tm": "teleman", "onet": "onet", "wp": "wp", "naz": "naz"}
print(",".join(m[t] for t in json.load(open(sys.argv[1], encoding="utf-8")).get("sites") or [] if t in m))' /opt/epgpl/work/bukiety.json 2>/dev/null)
	if [ -n "$ds" ]; then echo "$ds (z dekodera)"; else echo "$(conf_get SITES)"; fi
}
