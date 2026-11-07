# EPG Scan – complete EPG for Enigma2 (+ epgpl server)

**English** | [Polski](README.md)

**EPG Scan** is a plugin for Enigma2 receivers (tested on OpenPLi 9.x, VU+ Uno 4K SE) that builds a complete TV guide
from three sources:

- **satellite scan** – collects EPG (EIT) from the transponders of your bouquets, in parallel on free tuners, keeping a
  reserve for watching and recordings;
- **internet listings (XMLTV)** – Polish TV listings websites (teleman.pl, onet.pl, pilot.wp.pl,
  programtv.naziemna.info) downloaded on the receiver or from the **epgpl** server, matched to your bouquet channels;
- **SatWatch** – watches for changes on the satellite (`satellites.xml`, transponder scan) and repairs bouquets when a
  broadcaster changes a SID or transponder.

Plus: a schedule (night run in standby), EPG backups, reports, disk cleanup and a restart after the night run when
needed. The satellite scan, XMLTV import and SatWatch work with any orbital position; the built-in internet listings
cover Polish channels.

The on-screen texts are in Polish; the English descriptions map every menu item.

## Repository contents

| Directory | What it is | Description |
|---|---|---|
| [`epgscan/`](epgscan/) | Enigma2 plugin (`/usr/lib/enigma2/python/Plugins/Extensions/EPGScan`) | [epgscan/EPGScan/README.en.md](epgscan/EPGScan/README.en.md) |
| [`epgpl/`](epgpl/) | **optional** TV listings server for the home network (Debian / Ubuntu) | [epgpl/README.en.md](epgpl/README.en.md) |

The server is not required – the receiver can download the internet listings itself. epgpl is useful when you want
to download on a computer that runs at night anyway, or to serve several receivers.

## Installing the plugin

Packages are in [Releases](../../releases). Copy the file to `/tmp` on the receiver and install:

```
opkg install /tmp/enigma2-plugin-extensions-epgscan_X.Y.Z_all.ipk && init 4; sleep 2; init 3
```

or from the ZIP archive:

```
rm -rf /usr/lib/enigma2/python/Plugins/Extensions/EPGScan;cd /tmp && unzip -o epgscan-X.Y.Z.zip -d /usr/lib/enigma2/python/Plugins/Extensions/;init 4; sleep 2; init 3
```

First steps, all settings and FAQ: [plugin description](epgscan/EPGScan/README.en.md).
Complete removal including configuration: [`epgscan/tools/epgscan_reset.sh`](epgscan/tools/epgscan_reset.sh).

## Installing the epgpl server (optional)

```
cd /tmp && rm -rf epgpl && unzip -o epgpl-X.Y.Z.zip && cd epgpl && sudo bash install.sh
```

Details: [epgpl/README.en.md](epgpl/README.en.md).

## Building packages from source

```
sh epgscan/build.sh                       # dist/epgscan-X.Y.Z.zip and dist/enigma2-plugin-extensions-epgscan_X.Y.Z_all.ipk
mkdir -p dist && zip -qr dist/epgpl-$(cat epgpl/VERSION).zip epgpl
```

Versions: EPG Scan – `VERSION` in [`epgscan/EPGScan/common.py`](epgscan/EPGScan/common.py), epgpl – the
[`epgpl/VERSION`](epgpl/VERSION) file. Release tags: `epgscan-X.Y.Z`, `epgpl-X.Y.Z`. History (Polish):
[CHANGELOG.md](CHANGELOG.md).

## Bugs and ideas

[Issues](../../issues) – please include the plugin version, image and receiver model, tuner type and the log
(Info/EPG → *Raporty* → *Log EPG Scan*).

## License

GNU GPL version 2 or (at your option) any later version – [LICENSE](LICENSE), `SPDX-License-Identifier:
GPL-2.0-or-later` in the source files. Code and programs by other authors used by the plugin and the server, and notes
on data from TV listings websites: [epgscan/EPGScan/THIRD_PARTY.md](epgscan/EPGScan/THIRD_PARTY.md),
[epgpl/THIRD_PARTY.md](epgpl/THIRD_PARTY.md) (Polish).

Copyright (C) 2026 JarDol
