#!/bin/sh
# EPG Scan - usuniecie wtyczki i calej jej konfiguracji (stan "jakby nigdy nie byla zainstalowana").
# Przed usunieciem robi kopie wszystkiego do /media/hdd/epgscan_reset-<data>.tar.gz (albo /tmp, gdy brak dysku).
#   sh /tmp/epgscan_reset-1.1.sh          podglad - pokazuje, co zostanie usuniete
#   sh /tmp/epgscan_reset-1.1.sh --run    usuniecie (zatrzymuje Enigma2 na czas operacji)

E2=${E2:-/etc/enigma2}
PLUG=${PLUG:-/usr/lib/enigma2/python/Plugins/Extensions/EPGScan}
INIT=${INIT:-init}
RUN=0
[ "$1" = "--run" ] && RUN=1

# katalog kopii EPG / logow z ustawien (gdy zmieniony), domyslnie /media/hdd/epgscan
BDIR=$(sed -n 's/^config\.plugins\.epgscan\.backup_dir=//p' $E2/settings 2>/dev/null | tail -1)
[ -z "$BDIR" ] && BDIR=/media/hdd/epgscan/
SWLOG=$(sed -n 's/^config\.plugins\.satwatch\.logdir=//p' $E2/settings 2>/dev/null | tail -1)

LIST=""
add() { for p in "$@"; do [ -e "$p" ] && LIST="$LIST $p"; done; }
add $PLUG
add $E2/epgscan_tps.json $E2/epgscan_wakeup $E2/epgscan_xmltv.json $E2/epgscan_kanaly.txt $E2/epgscan_sources
add $E2/satwatch $E2/epgscan
# katalog kopii: caly tylko, gdy to wlasny katalog wtyczki (.../epgscan); inaczej tylko pliki wtyczki
BDIR=${BDIR%/}
if [ "$(basename "$BDIR")" = "epgscan" ]; then add $BDIR; else add $BDIR/epg-*.dat $BDIR/epgscan.log* $BDIR/lokalne; fi
[ -n "$SWLOG" ] && [ "$(basename "$SWLOG")" = "satwatch" ] && add $SWLOG
add /media/hdd/satwatch /media/usb/satwatch /media/mmc/satwatch /media/hdd/satwatch_sim_backup
add /tmp/epgscan.log /tmp/epgscan_xmltv
# satellites.xml zapisany przez SatWatch (Enigma wroci do systemowego /etc/tuxbox/satellites.xml)
SATX=""
if [ -f $E2/satellites.xml ] && grep -q "SatWatch" $E2/satellites.xml; then SATX=$E2/satellites.xml; LIST="$LIST $SATX"; fi
NSET=$(grep -c "^config\.plugins\.\(epgscan\|satwatch\)\." $E2/settings 2>/dev/null)

echo "Do usuniecia:"
for p in $LIST; do echo "  $p ($(du -sh $p 2>/dev/null | cut -f1))"; done
echo "  wpisy w $E2/settings: $NSET (config.plugins.epgscan.* i config.plugins.satwatch.*)"
echo
echo "Zostaje bez zmian: bukiety, lamedb, ustawienia tunerow, /etc/tuxbox/satellites.xml, EPGImport i jego zrodla,"
echo "plik EPG Enigmy (epg.dat) oraz ustawienie EIT w Menu -> EPG."
if [ $RUN -eq 0 ]; then
	echo
	echo "Podglad - nic nie usunieto. Usuniecie: sh $0 --run"
	exit 0
fi

if [ -d /media/hdd ] && [ -w /media/hdd ]; then OUT=/media/hdd; else OUT=/tmp; fi
ARCH=$OUT/epgscan_reset-$(date +%Y%m%d-%H%M%S).tar.gz
echo "Zatrzymuje Enigma2..."
$INIT 4
sleep 3
grep "^config\.plugins\.\(epgscan\|satwatch\)\." $E2/settings > /tmp/epgscan_settings_lines.txt 2>/dev/null
tar czf $ARCH $LIST /tmp/epgscan_settings_lines.txt 2>/dev/null
echo "Kopia: $ARCH"
cp $E2/settings $E2/settings.epgscan-reset.bak
sed -i '/^config\.plugins\.epgscan\./d; /^config\.plugins\.satwatch\./d' $E2/settings
# zainstalowana z paczki .ipk - najpierw usuniecie paczki (zeby opkg nie widzial jej jako zainstalowanej)
if opkg status enigma2-plugin-extensions-epgscan 2>/dev/null | grep -q "^Status:.*installed"; then
	opkg remove enigma2-plugin-extensions-epgscan >/dev/null 2>&1 && echo "Usunieto paczke enigma2-plugin-extensions-epgscan"
fi
rm -rf $LIST /tmp/epgscan_settings_lines.txt
echo "Usunieto. Kopia ustawien Enigmy: $E2/settings.epgscan-reset.bak"
echo "Uruchamiam Enigma2..."
$INIT 3
