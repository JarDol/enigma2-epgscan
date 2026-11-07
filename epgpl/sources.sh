#!/bin/bash
# SPDX-License-Identifier: GPL-2.0-or-later
# epgpl - plik zrodla dla odbiornika (EPG Scan: /etc/enigma2/epgscan_sources/wlasne/epgpl.sources.xml)
# Opis jest staly - EPG Scan zapamietuje wybrane zrodla po opisie.
BASE=${EPGPL_BASE:-/opt/epgpl}
. "$BASE/config.env"
IP=${SERVER_IP:-$(hostname -I | awk '{print $1}')}
mkdir -p "$BASE/www"
cat > "$BASE/www/.epgpl.sources.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<sources>
	<mappings>
		<channel name="epgpl.channels.xml.xz">
			<url>http://$IP:$PORT/pl.channels.xml.xz</url>
		</channel>
	</mappings>
	<sourcecat sourcecatname="EPG PL">
		<source type="gen_xmltv" nocheck="1" channels="epgpl.channels.xml.xz">
			<description>EPG PL (epgpl)</description>
			<url>http://$IP:$PORT/pl.xml.xz</url>
			<report>http://$IP:$PORT/raport.txt</report>
			<epgpl>http://$IP:$PORT/</epgpl>
		</source>
	</sourcecat>
</sources>
XML
mv "$BASE/www/.epgpl.sources.xml" "$BASE/www/epgpl.sources.xml"
