# epgpl – TV listings server for EPG Scan

epgpl is a home-network server (Debian/Ubuntu) that once a day downloads Polish TV listings from four websites,
matches them to the channels in your bouquets and serves the receiver a ready XMLTV file. The receiver with the
EPG Scan plugin imports it as the source **"EPG PL (epgpl)"** – as a fallback for its own download, or always.

The server is optional: EPG Scan can download the listings itself. The server is useful when the receiver has a weak
CPU or little memory, when you want the receiver to download less often, or when several things should run at night
in parallel.

**Language:** the installer, the configuration questions and the logs are in Polish; this document explains them.
Polish version: [README.md](README.md).

## Contents

1. How it works
2. Requirements
3. Installation
4. Configuration
5. Settings file config.env
6. The nightly download step by step
7. Channel matching and choices from the receiver
8. Working with EPG Scan
9. Files, directories and services
10. Update, repair, removal
11. Diagnostics and FAQ

---

## 1. How it works

| Component | Role |
|---|---|
| **iptv-org/epg** grabber (Node.js 22) | downloads listings from the websites: teleman.pl, programtv.onet.pl, pilot.wp.pl, programtv.naziemna.info |
| **run.sh** (service `epgpl.service`, timer `epgpl.timer`) | nightly run: channel list from the receiver → channel map → download → clean-up → publishing |
| **server.py** (service `epgpl-http.service`, port 8088) | serves files to the receiver and accepts commands from EPG Scan (preview, channel choices, bouquet selection, download on demand) |
| **Avahi** (`_epgpl._tcp`) | announces the server on the network – EPG Scan finds it by itself |

Listings are downloaded **for your channels**: the server reads the channel list from the receiver (OpenWebif), matches
stations from the websites to them and downloads only those stations.

## 2. Requirements

- Debian or Ubuntu with systemd (x86_64, arm64 or armv7), about 1.5 GB free in `/opt`.
- Internet access (listings websites, GitHub, nodejs.org during installation).
- An Enigma2 receiver with **OpenWebif** reachable without a password on the local network (channel list).
- EPG Scan 2.3.1 or newer on the receiver (server discovery); full cooperation – synchronised choices, downloading in
  one place – from EPG Scan 2.10.

The installer checks and installs: `git`, `xz-utils`, `python3`, `curl`, `iproute2`, `sudo`, `avahi-daemon` and a
private copy of **Node.js 22** in `/opt/epgpl/node` (the system Node.js is not touched).

## 3. Installation

```
cd /tmp && rm -rf epgpl && unzip -o epgpl-X.Y.Z.zip && cd epgpl && sudo bash install.sh
```

The installer goes through 11 steps. Each first checks the current state: when a requirement is met it shows **[OK]**,
when something has to change – **[ZMIANA]** (change) and asks (T/n = yes/no). When a feature is already on (Avahi,
sudo rule), it asks whether to switch it off (default "no").

| Step | What it checks and changes |
|---|---|
| 1. System | Debian/Ubuntu, systemd, architecture, free space; stops if a download is running |
| 2. Packages | missing packages – list and question |
| 3. Node.js 22 | private copy from nodejs.org, with SHA256 check |
| 4. User and files | system user `epgpl` (no login), files in `/opt/epgpl`, `config.env` completed from older versions |
| 5. Grabber | iptv-org/epg repository (about 200 MB) and npm dependencies – only when they changed |
| 6. Configuration | **first installation: receiver address** (checks that OpenWebif answers), port, time, server address; then a question about the download settings |
| 7. systemd services | HTTP server and the daily download |
| 8. sudo rule | `epgpl` may run only `systemctl start epgpl.service` (download on demand from the receiver) |
| 9. Firewall | ufw: port 8088/tcp and 5353/udp only for your subnet; firewalld: port and mdns service; no firewall – nothing |
| 10. Avahi | `_epgpl._tcp` announcement with the server port |
| 11. Check | whether the HTTP server and the receiver answer; when there is no listings file yet – offers a first download |

On an update the installer first asks: **1** – update only (settings and earlier yes/no answers are kept, questions
only about new features, with a description), **2** – update with full reconfiguration (all questions).

Unattended: `sudo bash install.sh -y --dekoder 192.168.1.20` (questions whose default is "no" are skipped).
Options: `--bez-avahi` (no Avahi), `--bez-zapory` (no firewall changes).

Everything the installer changes in the system (installed packages, firewall rules) is recorded in
`/opt/epgpl/.instalacja` – the uninstaller works from that.

On a new installation (or with empty choices) the installer reads, via OpenWebif, the manual channel choices made in
EPG Scan on the receiver (`/etc/enigma2/epgscan/wskazania.txt`) and offers to copy them – so the channel map is the
same on both sides from the start. If OpenWebif does not expose the file, EPG Scan offers synchronisation when the
server is added or when channel matching is opened.

## 4. Configuration

```
sudo bash /opt/epgpl/configure.sh                 # both parts
sudo bash /opt/epgpl/configure.sh --podstawowe    # basic: receiver, port, time, server address
sudo bash /opt/epgpl/configure.sh --pobieranie    # download: websites, delay, connections, days
```

Every question has a description and a recommendation; after saving, changes are applied at once (HTTP server
restart, timer, source file).

**Basic part**

| Question | Meaning |
|---|---|
| Receiver IP address | where the server gets your channel list (OpenWebif); checked at once, shows model and image |
| Port | HTTP server port (default 8088); checked whether free |
| Download time | e.g. `03:00`, several: `03:00,15:00` |
| Server address for the receiver | Enter = detected automatically (recommended at home); type a name (e.g. `server-linux.local`), another address when the server has several, or a public address/domain when sharing over the internet |

**Download settings**

| Question | Meaning | Recommended |
|---|---|---|
| Websites | order of priority – a channel takes listings from the first website that has it; a website can be left out | `teleman,onet,wp,naz` |
| Delay [ms] | pause after every page on every connection | 3000 (as iptv-org) |
| Connections per website | parallel requests to one website | 2 |
| Days | how many days of listings | 7 |
| Previous day | the current night is listed by the websites under the previous day | 1 (yes) |
| Receiver window [h] | the server skips its own download when the receiver starts one within ±N h; only without "downloading in one place" | 3 |
| Logs | how many download logs to keep | 7 |
| Daily candidate listings | one day of listings for stations offered in the EPG Scan station selection window (about 1000 stations); the receiver's setting wins | 0 (no) |

**Delay – be careful.** 3000 ms is the iptv-org value: safe for the websites, a full download takes about an hour
(no problem at night). A lower value is faster (500 ms → about 3 minutes for 180 stations), but websites may start
rejecting requests (HTTP 429/403, dropped connections): channels stay without listings, and with heavy overuse a
website may temporarily block the server's address. Do not go below 300 ms; if 429 errors appear in the log, go
back to 3000.

## 5. Settings file config.env

`/opt/epgpl/config.env` – every option has a description on the same line; it can be edited directly
(`sudo nano /opt/epgpl/config.env`). Most options apply from the next download, without a restart.

| Option | Default | Description | After a change |
|---|---|---|---|
| `RECEIVER` | – | receiver address (OpenWebif) | – |
| `SERVER_IP` | empty | server address in the files for the receiver; empty = automatic | `sudo -u epgpl bash /opt/epgpl/sources.sh` |
| `PORT` | 8088 | HTTP server port | `sudo systemctl restart epgpl-http`, firewall and Avahi: `sudo bash install.sh` |
| `GRAB_TIME` | 01:15 | download time (several separated by commas) | `sudo bash /opt/epgpl/timer.sh` |
| `DAYS` | 7 | days of listings | – |
| `SITES` | teleman,onet,wp,naz | websites in order of priority; when the receiver sets the order (EPG Scan 2.4.0+), the receiver's order is used | – |
| `GRAB_DELAY` | 3000 | delay [ms] between pages | – |
| `MAX_CONNECTIONS` | 2 | connections per website (reduced automatically on errors) | – |
| `GRAB_YESTERDAY` | 1 | also download the previous day | – |
| `TUNER_WINDOW` | 3 | window [h] in which the server's schedule gives way to the receiver; 0 = never; not used with "downloading in one place" (EPG Scan) – then whoever downloads first decides | – |
| `SCOPE` | bouquets | `bouquets` = channels from your bouquets (+ their copies), `all` = everything possible | – |
| `COPIES` | tp | channel copies in the map: `tp` = only from transponders carrying your channels, `all` = all | – |
| `SKIP_BOUQUETS` | "Last Scanned" | bouquets ignored when selecting channels | – |
| `KEEP_LOGS` | 7 | how many logs to keep | – |
| `KANDYDACI` | 0 | daily candidate listings (EPG Scan station selection window); the EPG Scan setting wins | – |

## 6. The nightly download step by step

1. **Lock** – one download at a time (schedule, receiver, manual start).
2. **Downloading in one place** (EPG Scan setting) – a scheduled start waits while the receiver reports that it is
   downloading, and is skipped when the receiver sent a full package today.
   **Receiver window** – a scheduled start is skipped when EPG Scan reported that it starts its own download within
   ±`TUNER_WINDOW` h (entry in `log/pominiete.log`).
3. **Grabber update** – iptv-org/epg from GitHub (on error – the previous version) and the websites' station lists;
   a fix for onet (no download of every event's details – many times faster).
4. **Channel list from the receiver** (`/api/getallservices`). If the receiver does not answer – the last saved list.
5. **Channel map** – which channels get listings from which station (chapter 7).
6. **Download** – websites in parallel, each with its own connection limit; from the previous day (when
   `GRAB_YESTERDAY=1`). On rate-limit errors (429/403) – failed channels are retried after 2 min with fewer connections.
7. **Clean-up** – events from the previous file for the current night and for channels without new data, removal of
   events older than 2 h, check of channels without data (with the next map – the next website).
8. **Publishing** – first to temporary files, then swapped: the receiver never gets half a file. On error the previous
   file stays and the report gives the reason. The full-download marker (`pelny.json`) tells the receiver it can
   take the ready package.
9. **Candidate listings** – separate service `epgpl-kandydaci.service`, only when enabled (the receiver does not wait for it).

## 7. Channel matching and choices from the receiver

The automatic matching works by name: ignoring "HD", "Poland" etc., with a name-extension rule (e.g. regional TVP3);
copies of a channel on other transponders get the same listings. Priority, highest first:

1. **choices from the receiver** – `wskazania.txt` (EPG Scan → Menu → *Kanały* → *Dopasowanie kanałów: serwer*),
2. **your mappings** – `aliases.txt` (format: `Channel name on the satellite = Name on the websites` or
   `= onet:site_id`, `= tm:…`, `= wp:…`, `= naz:…`; `= -` – deliberately no listings),
3. **package mappings** – `aliases.dist`,
4. automatic matching.

The websites' station lists are refreshed directly from their pages on every download and when the map is
recalculated (if older than an hour) – a new channel on a website gets into the map without waiting for iptv-org.
A server update does not overwrite them.

**Candidates (0.16.0; off by default since 0.19.0):** when enabled (`KANDYDACI=1`, or in EPG Scan: *Codziennie
program kandydatów* – the receiver's setting wins), after a successful download a separate service
`epgpl-kandydaci.service` downloads one day of listings for the stations that the EPG Scan station selection window
offers to bouquet channels and that are not in the listings file (`kandydaci.xml.xz` – preview only, not imported
into the EPG; the receiver does not wait for this step). Manually (regardless of the setting):
`sudo -u epgpl bash /opt/epgpl/kandydaci.sh --zawsze`, log: `/opt/epgpl/log/kandydaci.log`. From the station
selection window candidate listings can also be fetched at once (`POST /api/kandydaci` → `teraz.xml.xz`, kept 24 h).

For stations in the listings file, the EPG Scan station selection window shows the average description length, the
number of days and the current event – the server provides them via `/api/staty` (since 0.15.0).

Channels with alternatives (a group of several services under one name in a bouquet) are expanded to the services of
the group via OpenWebif – the same as on the receiver.

The **website order** (EPG Scan → *Ustawienia* → *Program z internetu*) and the **bouquets to match** are set on the
receiver – it is the settings centre. Bouquets are chosen in channel matching (blue "Bukiety": all / selected / those
being scanned). The choice is sent to the server (`work/bukiety.json`) and the map is recalculated at once – e.g.
without IPTV bouquets.

Every change from the receiver recalculates the map at once (`mapa.sh`); listings for a new match arrive with the next
download or after a download on demand.

## 8. Working with EPG Scan

| EPG Scan feature | What the server does |
|---|---|
| Search for an epgpl server on the network | answers Avahi (`_epgpl._tcp`) and on the HTTP port (`epgpl.sources.xml`) |
| Source "EPG PL (epgpl)" | files `pl.xml.xz` (listings) and `pl.channels.xml.xz` (reference map) |
| Actions → epgpl server: fallback / always | fallback: ready file when the receiver has none; always: also downloads on the server |
| Channel matching: server | map (`mapa.json`), station lists (`stacje.json`), choices (`/api/alias`), bouquet list (`/api/bukiety`) |
| Station listings preview | `/api/podglad` – from the listings file or downloaded on demand (a dozen seconds or so) |
| Download on the server | `/api/pobierz` – starts `epgpl.service` (sudo rule from step 8) |
| Receiver schedule | `/api/tuner` – the receiver reports its download, the server skips its own within ±`TUNER_WINDOW` h |
| File date in the source list | `/api/stan` – listings file time, whether a download is running, last run result, full-download marker (`pelny.json`) |
| Downloading in one place (0.18.0) | whoever is first downloads: the receiver takes the server's full package from today or uploads its own (`/api/paczka`: `pl.xml.xz`, `pl.channels.xml.xz`, `kandydaci.xml.xz`); while the receiver downloads, `/api/zajety` – the server's schedule waits (at most 90 min), and when the receiver sent a package today, the download is skipped (`log/pominiete.log`) |
| Matching differences / synchronisation | `/api/wskazania` (the server's manual choices), `/api/alias` with a list of choices (`items`) – many at once, one map recalculation; on reconfiguration the installer compares choices with the receiver and asks only when they differ |
| Candidate listings | `kandydaci.xml.xz` (at night), `teraz.xml.xz` (on demand from the selection window, or sent by the receiver – `/api/teraz`), `/api/staty` |

The server accepts commands (`/api/…`) **only from local network addresses**. From the internet only the listings
files are available. Only one epgpl server can be assigned to a receiver; adding another replaces the previous one
(after confirmation).

## 9. Files, directories and services

| Path | Contents |
|---|---|
| `/opt/epgpl/config.env` | settings |
| `/opt/epgpl/aliases.txt`, `wskazania.txt` | your mappings / choices from the receiver (`wskazania.txt.przed` – copy from before taking over the receiver's choices) |
| `/opt/epgpl/work/` | channel list from the receiver, map, download result, `bukiety.json`, `grab.log` (progress) |
| `/opt/epgpl/www/` | files for the receiver: `pl.xml.xz`, `pl.channels.xml.xz`, `epgpl.sources.xml`, `mapa.json`, `stacje.json`, `raport.txt`, `pelny.json` (full-download marker), `kandydaci.xml.xz` and `teraz.xml.xz` (candidate listings) |
| `/opt/epgpl/.instalacja`, `.decyzje` | what the installer changed (for removal) and yes/no answers (not asked again on update) |
| `/opt/epgpl/log/` | logs of the runs, `pominiete.log` (skipped runs) |
| `/opt/epgpl/node`, `/opt/epgpl/iptv-epg` | Node.js 22 and the grabber |
| `/opt/epgpl/tools/` | `porownaj.py` (compare websites with satellite EPG), `kopie.py` (check channel copies) |
| `/etc/systemd/system/epgpl*.service`, `epgpl.timer` | services and timer |
| `/etc/sudoers.d/epgpl` | the only permission: start the download |
| `/etc/avahi/services/epgpl.service` | network announcement |

## 10. Update, repair, removal

| What | Command |
|---|---|
| Update / repair (settings stay) | `sudo bash install.sh` from the unpacked package → **1** (questions only about new features) |
| Update with all questions | `sudo bash install.sh --rekonfiguracja` (or **2** at the start) |
| Change parameters only | `sudo bash /opt/epgpl/configure.sh` |
| Remove including configuration | `sudo bash /opt/epgpl/uninstall.sh` |
| Fresh installation | `uninstall.sh`, then `install.sh` |

`uninstall.sh` shows what it found, asks for confirmation and always backs up the settings and channel choices to
`/root/epgpl-kopia-<date>.tgz`. It removes the services, timer, sudo rule, Avahi announcement, firewall rules added by
the installer, `/opt/epgpl` and the `epgpl` user. Installed packages are removed only on explicit request. It also
works for installations of older versions.

Restoring channel choices from the backup:

```
sudo tar xzf /root/epgpl-kopia-<date>.tgz -C /opt/epgpl wskazania.txt && sudo chown epgpl:epgpl /opt/epgpl/wskazania.txt && sudo -u epgpl bash /opt/epgpl/mapa.sh
```

## 11. Diagnostics and FAQ

| What to check | How |
|---|---|
| Download now and progress | `sudo systemctl start --no-block epgpl.service; tail -f /opt/epgpl/work/grab.log` |
| Service state | `systemctl status epgpl-http epgpl.timer` |
| Last run | `ls -t /opt/epgpl/log/ \| head -1` and `less` on that file |
| Which channels have listings and from where | `http://SERVER:8088/raport.txt` |
| HTTP server | `journalctl -u epgpl-http` |

**429/403 errors in the log.** A website is rate-limiting – increase `GRAB_DELAY` (3000) or reduce `MAX_CONNECTIONS`.

**A channel without listings.** Check `raport.txt`; choose the station from the receiver (*Dopasowanie kanałów:
serwer* – with a listings preview) or add it to `aliases.txt`.

**The receiver does not find the server.** Check that `avahi-daemon` is running and the firewall lets 5353/udp
through; EPG Scan also searches the subnet on port 8088. The address can be typed manually:
`http://SERVER:8088/epgpl.sources.xml`.

**The receiver was off at night.** The server uses the last saved channel list – the listings are downloaded anyway.

**Server on the internet.** Technically possible: set `SERVER_IP` to the public address or domain and open the port
(only the listings files are reachable from the internet; the channel map is built from your bouquets). Keep in mind
that sharing downloaded listings with others may breach the websites' terms – see *Disclaimer*. Recommended: home
network only, or VPN access to your own devices.

## Disclaimer

TV listings downloaded from the websites (teleman.pl, programtv.onet.pl, pilot.wp.pl, programtv.naziemna.info) belong to
their owners. The plugin and the server download them for private use on your own receiver – with pauses between
requests and a limited number of connections. You are responsible for using them in line with those websites' terms;
do not share the downloaded listings with others. The repository contains only code and configuration – no downloaded
listings. The software comes without any warranty (see the GPL license for details).

## License

epgpl – GNU GPL version 2 or (at your option) any later version (GPL-2.0-or-later), full text in `LICENSE`.
Code and programs by other authors it uses (among others iptv-org/epg – Unlicense, Node.js – MIT), their licenses and
notes on data from TV listings websites: `THIRD_PARTY.md` (Polish).
