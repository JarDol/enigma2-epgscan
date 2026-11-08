#!/bin/bash
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - aktualizacja z wydan GitHub (JarDol/enigma2-epgscan, tagi epgpl-X.Y.Z)
#   sudo bash /opt/epgpl/update.sh            sprawdz i zaktualizuj (pyta; dziala zawsze, niezaleznie od AUTOUPDATE)
#   sudo bash /opt/epgpl/update.sh -y         bez pytan
#   sudo bash /opt/epgpl/update.sh --check    tylko sprawdz
#   sudo bash /opt/epgpl/update.sh --force    zainstaluj ponownie takze te sama wersje
#   --auto                                    tryb timera (epgpl-update.timer): tylko gdy AUTOUPDATE=1 w config.env
# Ustawienia (config.env, dekoder, wskazania kanalow) zostaja - instalator dziala w trybie aktualizacji (install.sh -y).
BASE=${EPGPL_BASE:-/opt/epgpl}
REPO=JarDol/enigma2-epgscan
AUTO=0; CHECK=0; FORCE=0; YES=0
for a in "$@"; do
	case "$a" in
		--auto) AUTO=1; YES=1 ;;
		--check) CHECK=1 ;;
		--force) FORCE=1 ;;
		-y|--tak) YES=1 ;;
		-h|--help) sed -n 3,9p "$0"; exit 0 ;;
		*) echo "Nieznana opcja: $a"; exit 1 ;;
	esac
done
[ "$(id -u)" = 0 ] || [ "$CHECK" = 1 ] || { echo "Uruchom przez sudo: sudo bash $0"; exit 1; }

LOG=$BASE/log/update.log
say() {
	echo "$*"
	[ "$AUTO" = 1 ] && { mkdir -p "$BASE/log"; echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; }
	return 0
}

if [ "$AUTO" = 1 ]; then
	A=$(sed -n 's/^AUTOUPDATE=\([01]\).*/\1/p' "$BASE/config.env" 2>/dev/null)
	[ "$A" = 1 ] || exit 0
fi

CUR=$(cat "$BASE/VERSION" 2>/dev/null || echo 0)
JSON=$(curl -sfL -m 30 -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$REPO/releases?per_page=50") \
	|| { say "Aktualizacja: brak odpowiedzi GitHub"; exit 1; }
# najnowsza wersja epgpl-X.Y.Z z plikiem epgpl-X.Y.Z.zip (bez wersji roboczych)
read -r NEW URL < <(echo "$JSON" | python3 -I -c '
import json, sys
best = None
for r in json.load(sys.stdin):
    t = r.get("tag_name", "")
    if r.get("draft") or r.get("prerelease") or not t.startswith("epgpl-"):
        continue
    v = t[6:]
    for a in r.get("assets", []):
        if a.get("name") == "epgpl-%s.zip" % v:
            k = tuple(int(x) if x.isdigit() else 0 for x in v.split("."))
            if best is None or k > best[0]:
                best = (k, v, a["browser_download_url"])
if best:
    print(best[1], best[2])
')
[ -n "$NEW" ] || { say "Aktualizacja: brak wydan epgpl na GitHub"; exit 1; }

newer() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]; }
if newer "$NEW" "$CUR"; then
	echo "Zainstalowana: $CUR, dostepna: $NEW"
elif [ "$FORCE" = 1 ]; then
	echo "Zainstalowana: $CUR, najnowsza: $NEW - instalacja ponowna (--force)"
else
	[ "$AUTO" = 1 ] || echo "Masz najnowsza wersje ($CUR)."
	exit 0
fi
[ "$CHECK" = 1 ] && exit 0

if [ "$YES" != 1 ]; then
	read -r -p "Zaktualizowac do $NEW? [T/n] " a </dev/tty || a=""
	case "$(echo "${a:-t}" | tr 'A-Z' 'a-z')" in t|tak|y|yes) ;; *) echo "Bez zmian."; exit 0 ;; esac
fi

# nie w trakcie pobierania programu (instalator i tak by odmowil)
for i in $(seq 1 ${EPGPL_WAIT:-0}); do
	systemctl is-active --quiet epgpl.service || systemctl is-active --quiet epgpl-auto.service || break
	sleep 60
done
if systemctl is-active --quiet epgpl.service || systemctl is-active --quiet epgpl-auto.service; then
	say "Aktualizacja do $NEW odlozona: trwa pobieranie programu"
	exit 0
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -sfL -m 300 -o "$TMP/epgpl.zip" "$URL" || { say "Aktualizacja: pobranie $URL nieudane"; exit 1; }
mkdir "$TMP/x"
if command -v unzip >/dev/null; then
	unzip -q "$TMP/epgpl.zip" -d "$TMP/x"
else
	python3 -I -m zipfile -e "$TMP/epgpl.zip" "$TMP/x"
fi
[ -f "$TMP/x/epgpl/install.sh" ] && [ -f "$TMP/x/epgpl/VERSION" ] || { say "Aktualizacja: nieprawidlowa paczka $NEW"; exit 1; }
say "Aktualizacja epgpl $CUR -> $NEW"
if [ "$AUTO" = 1 ]; then
	bash "$TMP/x/epgpl/install.sh" -y >> "$LOG" 2>&1
else
	bash "$TMP/x/epgpl/install.sh" -y
fi
RC=$?
[ $RC = 0 ] && say "Aktualizacja do $NEW zakonczona" || say "Aktualizacja do $NEW nieudana (kod $RC)"
exit $RC
