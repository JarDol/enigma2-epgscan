#!/bin/bash
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - usuniecie serwera razem z konfiguracja (stan jak przed instalacja).
# Dziala takze dla instalacji ze starszych wersji (sprawdza, co faktycznie jest w systemie).
#   sudo bash uninstall.sh        z pytaniami
#   sudo bash uninstall.sh -y     bez pytan (kopia ustawien zawsze; doinstalowane pakiety zostaja)
[ "$(id -u)" = 0 ] || { echo "Uruchom przez sudo: sudo bash uninstall.sh"; exit 1; }
DIR=$(cd "$(dirname "$0")" && pwd)
. "$DIR/common.sh"
[ "$1" = "-y" ] && YES=1
BASE=/opt/epgpl
ST=$BASE/.instalacja
PORT=$(conf_get PORT); PORT=${PORT:-8088}

echo "${C_H}epgpl - usuniecie serwera i konfiguracji${C_0}"
head1 "Co jest zainstalowane"
FOUND=0
for u in epgpl.service epgpl-auto.service epgpl-http.service epgpl-kandydaci.service epgpl.timer; do
	[ -f /etc/systemd/system/$u ] && { info "usluga $u"; FOUND=1; }
done
[ -f /etc/sudoers.d/epgpl ] && { info "regula sudo /etc/sudoers.d/epgpl"; FOUND=1; }
[ -f /etc/avahi/services/epgpl.service ] && { info "ogloszenie Avahi /etc/avahi/services/epgpl.service"; FOUND=1; }
UFW_RULES=$(grep "^ufw allow" $ST 2>/dev/null | sed 's/^ufw //')
if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
	EXTRA=$(ufw status | grep -E "# epgpl" | sed 's/  */ /g')
	[ -n "$UFW_RULES$EXTRA" ] && { info "reguly zapory ufw:"; echo "$EXTRA" | sed 's/^/      /'; FOUND=1; }
fi
FWD=$(grep "^firewalld" $ST 2>/dev/null)
[ -n "$FWD" ] && { info "firewalld: $(echo $FWD | tr '\n' ' ')"; FOUND=1; }
[ -d $BASE ] && { info "katalog $BASE ($(du -sh $BASE 2>/dev/null | cut -f1)) - program, Node.js, grabber, pliki EPG, logi, ustawienia"; FOUND=1; }
id epgpl >/dev/null 2>&1 && { info "uzytkownik systemowy epgpl"; FOUND=1; }
PK=$(grep "^pakiet " $ST 2>/dev/null | cut -d' ' -f2 | tr '\n' ' ')
[ -n "$PK" ] && info "pakiety doinstalowane przez instalator: $PK(usuwane tylko na zyczenie)"
[ "$FOUND" = 1 ] || { ok "epgpl nie jest zainstalowany - nic do usuniecia"; exit 0; }

echo
if [ "$YES" != 1 ]; then
	ask "Usunac epgpl razem z konfiguracja?" N || { echo "Anulowano."; exit 0; }
fi

# kopia ustawien i wskazan (zawsze)
if [ -d $BASE ]; then
	KOPIA=/root/epgpl-kopia-$(date +%Y%m%d-%H%M%S).tgz
	(cd $BASE && tar czf $KOPIA config.env aliases.txt wskazania.txt aliases.tuner.txt aliases.txt.stary work/bukiety.json .instalacja .decyzje 2>/dev/null)
	[ -s $KOPIA ] && ok "kopia ustawien i wskazan kanalow: $KOPIA" || rm -f $KOPIA
fi

head1 "Usuwanie"
for u in epgpl.timer epgpl-http.service epgpl-kandydaci.service epgpl-auto.service epgpl.service; do
	systemctl stop $u >/dev/null 2>&1
	systemctl disable $u >/dev/null 2>&1
	[ -f /etc/systemd/system/$u ] && rm -f /etc/systemd/system/$u && ok "usluga $u"
done
systemctl daemon-reload >/dev/null 2>&1
systemctl reset-failed >/dev/null 2>&1
[ -f /etc/sudoers.d/epgpl ] && rm -f /etc/sudoers.d/epgpl && ok "regula sudo"
if [ -f /etc/avahi/services/epgpl.service ]; then
	rm -f /etc/avahi/services/epgpl.service
	systemctl reload avahi-daemon >/dev/null 2>&1 || true
	ok "ogloszenie Avahi"
fi
if command -v ufw >/dev/null; then
	echo "$UFW_RULES" | while read -r r; do
		[ -n "$r" ] && ufw delete $r >/dev/null 2>&1 && ok "zapora: ufw delete $r"
	done
	# reguly z komentarzem epgpl dodane recznie albo starsza wersja
	while N=$(ufw status numbered 2>/dev/null | grep "# epgpl" | head -1 | sed -n 's/^\[ *\([0-9]*\)\].*/\1/p') && [ -n "$N" ]; do
		ufw --force delete "$N" >/dev/null 2>&1 || break
		ok "zapora: regula ufw nr $N (epgpl)"
	done
fi
if command -v firewall-cmd >/dev/null; then
	grep -q "^firewalld $PORT/tcp" $ST 2>/dev/null && firewall-cmd --permanent --remove-port=$PORT/tcp >/dev/null 2>&1 && ok "firewalld: port $PORT/tcp"
	grep -q "^firewalld mdns" $ST 2>/dev/null && firewall-cmd --permanent --remove-service=mdns >/dev/null 2>&1 && ok "firewalld: mdns"
	firewall-cmd --reload >/dev/null 2>&1 || true
fi
pkill -u epgpl >/dev/null 2>&1; sleep 1
[ -d $BASE ] && rm -rf $BASE && ok "katalog $BASE"
id epgpl >/dev/null 2>&1 && userdel epgpl >/dev/null 2>&1 && ok "uzytkownik epgpl"
rm -f /tmp/node-v*-linux-*.tar.xz

if [ -n "$PK" ]; then
	echo
	info "Instalator doinstalowal pakiety: $PK"
	info "Moga byc uzywane przez inne programy (git, curl, python3...)."
	if ask "Odinstalowac je?" N; then
		DEBIAN_FRONTEND=noninteractive apt-get remove -y -qq $PK >/dev/null && ok "odinstalowano: $PK"
	fi
fi
echo
echo "${C_H}epgpl usuniety.${C_0} ${KOPIA:+Ustawienia i wskazania kanalow: $KOPIA}"
echo "Ponowna instalacja od zera: sudo bash install.sh (zapyta o adres dekodera)."
