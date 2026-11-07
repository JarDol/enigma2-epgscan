# epgpl – kod i dane osób trzecich

## Licencja epgpl

epgpl jest udostępniany na licencji **GNU GPL w wersji 2 lub (według wyboru) dowolnej późniejszej** (GPL-2.0-or-later) – pełny tekst w pliku `LICENSE`. Składniki wymienione niżej mają własne licencje; kod oparty na iptv-org/epg (Unlicense, domena publiczna) można włączać do kodu na GPL.

epgpl nie zawiera w paczce cudzego kodu poza wymienionym niżej. Programy, z których korzysta,
instalator pobiera z ich oficjalnych źródeł i nie są rozpowszechniane razem z epgpl.

## Pobierane przez instalator (nie wchodzą do paczki)

| Składnik | Skąd | Licencja | Do czego |
|---|---|---|---|
| iptv-org/epg (grabber) | https://github.com/iptv-org/epg (git clone do `/opt/epgpl/iptv-epg`) | Unlicense (domena publiczna) | pobieranie programu ze stron serwisów |
| zależności npm grabbera (m.in. axios, cheerio, dayjs) | rejestr npm (`npm ci` w katalogu grabbera) | głównie MIT – licencje w `/opt/epgpl/iptv-epg/node_modules/*/LICENSE` | używane przez grabber i przez własne konfiguracje serwisów epgpl |
| Node.js 22 | https://nodejs.org (archiwum z sumą SHA256, `/opt/epgpl/node`) | MIT (z licencjami składników w `/opt/epgpl/node/LICENSE`) | uruchamianie grabbera |
| pakiety systemowe (git, xz-utils, python3, curl, iproute2, sudo, avahi-daemon) | repozytorium dystrybucji (apt) | licencje dystrybucji | narzędzia |

`run.sh` przy każdym pobieraniu zmienia jedną linię w konfiguracji onet z iptv-org/epg (bez pobierania szczegółów każdej audycji) – zmiana dotyczy tylko kopii lokalnej.

## Kod oparty na cudzym

| Plik epgpl | Na podstawie | Licencja źródła |
|---|---|---|
| `sites/teleman.pl/teleman.pl.config.js`, `sites/pilot.wp.pl/pilot.wp.pl.config.js`, `sites/programtv.naziemna.info/programtv.naziemna.info.config.js` | własny kod w formacie konfiguracji serwisu iptv-org/epg | Unlicense (format) |
| `site_channels.py` – lista stacji onet i format `*.channels.xml` | iptv-org/epg (`channels()` konfiguracji onet, format list kanałów) | Unlicense |

Pozostałe pliki to własny kod epgpl.

## Dane

Program telewizyjny pochodzi ze stron serwisów (teleman.pl, programtv.onet.pl, pilot.wp.pl, programtv.naziemna.info) i należy do ich właścicieli. epgpl pobiera go do użytku prywatnego, na własnym serwerze, dla własnego dekodera – z przerwami między zapytaniami (domyślnie 3000 ms, jak w iptv-org). Udostępnianie pobranego programu innym osobom może naruszać regulaminy serwisów.
