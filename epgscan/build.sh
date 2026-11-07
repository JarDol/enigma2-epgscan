#!/bin/sh
# SPDX-License-Identifier: GPL-2.0-or-later
# Buduje paczki EPG Scan: dist/epgscan-X.Y.Z.zip i dist/enigma2-plugin-extensions-epgscan_X.Y.Z_all.ipk
# Uzycie: sh epgscan/build.sh   (z katalogu glownego repozytorium albo z epgscan/)
set -e
cd "$(dirname "$0")"
V=$(sed -n 's/^VERSION = "\(.*\)"/\1/p' EPGScan/common.py | head -1)
[ -n "$V" ] || { echo "Nie znaleziono VERSION w EPGScan/common.py"; exit 1; }
OUT=../dist
TMP=$(mktemp -d)
mkdir -p "$OUT"
find EPGScan -name __pycache__ -prune -exec rm -rf {} +

# ZIP (rozpakowywany do /usr/lib/enigma2/python/Plugins/Extensions/)
rm -f "$OUT/epgscan-$V.zip"
zip -qr "$OUT/epgscan-$V.zip" EPGScan

# IPK (ar: debian-binary, control.tar.gz, data.tar.gz)
D="$TMP/data/usr/lib/enigma2/python/Plugins/Extensions"
mkdir -p "$D" "$TMP/ctrl"
cp -r EPGScan "$D/"
SIZE=$(du -sk "$TMP/data" | cut -f1)
sed "s/@VERSION@/$V/; s/@SIZE@/$SIZE/" ipk/control > "$TMP/ctrl/control"
cp ipk/postrm "$TMP/ctrl/postrm"
chmod 755 "$TMP/ctrl/postrm"
echo "2.0" > "$TMP/debian-binary"
tar czf "$TMP/control.tar.gz" -C "$TMP/ctrl" --owner=0 --group=0 .
tar czf "$TMP/data.tar.gz" -C "$TMP/data" --owner=0 --group=0 .
IPK="$OUT/enigma2-plugin-extensions-epgscan_${V}_all.ipk"
rm -f "$IPK"
(cd "$TMP" && ar r "$OLDPWD/$IPK" debian-binary control.tar.gz data.tar.gz 2>/dev/null)
rm -rf "$TMP"
echo "Gotowe: $OUT/epgscan-$V.zip, $IPK"
