# EPG Scan – kod i dane osób trzecich

## Licencja EPG Scan

EPG Scan jest udostępniany na licencji **GNU GPL w wersji 2 lub (według wyboru) dowolnej późniejszej** (GPL-2.0-or-later) – pełny tekst w pliku `LICENSE`. Składniki wymienione niżej mają własne licencje; kod oparty na iptv-org/epg (Unlicense, domena publiczna) można włączać do kodu na GPL.

## Środowisko

| Składnik | Licencja | Uwagi |
|---|---|---|
| Enigma2 (OpenPLi) – interfejs wtyczek, `enigma`, `Components`, `Screens` | GPL-2.0 | wtyczka działa wewnątrz Enigma2 i korzysta z jej interfejsu; nie zawiera kodu Enigma2 |
| EPGImport (wtyczka) | własna licencja wtyczki | EPG Scan tylko zapisuje dla niej pliki źródeł `*.sources.xml` i uruchamia import; kod EPGImport nie jest dołączony |
| Python 3 (biblioteka standardowa) | PSF License | z obrazu systemu |

## Kod oparty na cudzym

| Plik | Na podstawie | Licencja źródła |
|---|---|---|
| `localgrab.py` – parser programtv.onet.pl (`onet_parse`) | konfiguracja serwisu programtv.onet.pl z iptv-org/epg (https://github.com/iptv-org/epg), przepisana na Pythona | Unlicense (domena publiczna) |
| `localgrab.py` – parsery teleman.pl, pilot.wp.pl, programtv.naziemna.info | własne konfiguracje serwera epgpl (ten sam autor) | – |

Pozostałe pliki to własny kod EPG Scan (w tym SatWatch, szukanie serwera przez mDNS – `netfind.py`).

## Dane

Program telewizyjny z serwisów (teleman.pl, programtv.onet.pl, pilot.wp.pl, programtv.naziemna.info) należy do ich właścicieli. Wtyczka pobiera go do użytku prywatnego na własnym dekoderze, z przerwami między zapytaniami. Udostępnianie pobranego programu innym może naruszać regulaminy serwisów. Lista źródeł XMLTV innych autorów (np. pakiet epgimport-sources) jest tylko wskazywana – nie jest dołączona.
