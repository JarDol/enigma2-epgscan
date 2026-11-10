# E2Stream — odtwarzacz Enigma2 dla Android TV

Część repozytorium EPG Scan: aplikacja kliencka współpracująca z wtyczką (moduł „serwer dla klientów”).
Działa też bez wtyczki — wtedy przez OpenWebif i FTP.

Odtwarzacz strumieni z dekodera Enigma2 (OpenPLi / OpenWebif) dla Android TV.

* ExoPlayer (Media3) z **tunelowaniem wideo**
  (sprzętowy deinterlacer telewizora, płynne paski informacyjne).
* **Watchdog** – gdy obraz się zawiesi (licznik klatek dekodera albo zegar odtwarzania stoi
  dłużej niż ustawiony limit, zbyt długie buforowanie, błąd lub zerwane połączenie),
  strumień jest automatycznie otwierany ponownie. To samo, co ręczne „przełącz i wróć”, tylko w ok. 1–2 s.
* Bukiety i kanały pobierane z OpenWebif (`/api/getservices`), EPG „teraz” (`/api/epgnow`).
* Strumień: `http://DEKODER:8001/<referencja>` (opcjonalnie z logowaniem Basic).
* Wyszukiwanie dekoderów w sieci lokalnej (ustawienia → „Szukaj dekoderów w sieci”).
* Wybór ścieżki dźwięku zapamiętywany osobno dla każdego kanału; preferowany język i opcjonalnie AC3.
* Odświeżanie ekranu: bez zmian / zawsze 50 Hz / automatycznie wg treści.
* Analizator strumienia TS: błędy CC, TEI, pakiety kodowane, utrata synchronizacji, skoki PCR,
  zmiany PMT, przerwy w napływie danych — w statystykach i w logu.
* Przy zawieszeniu zapis ostatnich sekund strumienia (`stall-*.ts`, bufor 16 MB) do analizy np. TSDuck; na urządzeniu najwyżej 3 zrzuty, po wysłaniu na dekoder kopia lokalna jest usuwana (na dekoderze sprząta je EPG Scan: Sprzątanie dysku → zrzuty starsze niż N dni).
* Miejsce zapisu: pamięć urządzenia lub pendrive/karta (katalog `Android/data/pl.jardol.e2stream/files/`).
* EPG pobierane codziennie wg harmonogramu (i na żądanie) z zapisem na urządzeniu; ikony kanałów z dekodera (OpenWebif /picon/ lub FTP).
* EPG kanału, multi-EPG bukietu i wyszukiwanie w EPG; z audycji: przełącz na kanał lub nagraj na dekoderze.
* Wybór widocznych bukietów, PIN do ustawień (odblokowanie na wybrany czas, wyjście z aplikacji zawsze blokuje), aktualizacja aplikacji z GitHub (menu → Aktualizacja).
* Log diagnostyczny: lokalnie w `Android/data/pl.jardol.e2stream/files/e2stream.log`
  i co 2 minuty przez FTP na dekoder (domyślnie `/media/hdd/e2stream/e2stream-<nazwa odbiornika>-<znacznik>.log`, np. `e2stream-Salon-3F2A.log`; znacznik to 4 znaki z identyfikatora urządzenia, więc dwa takie same modele w sieci nie nadpisują sobie plików; nazwa ze znacznikiem jest też w liście odbiorników i w nazwach zrzutów TS).

## Synchronizacja z dekoderem

Na dekoderze w `<katalog FTP>/sync/` (domyślnie `/media/hdd/e2stream/sync/`):

* `common.json` — wartości domyślne dla nowych odbiorników i wspólne PMT kanałów,
* `device-<id>.json` — ustawienia i wybrane ścieżki dźwięku każdego odbiornika (z nazwą i modelem).

Nowy odbiornik przy pierwszym uruchomieniu bierze swój profil (reinstalacja) albo wartości domyślne.
Zmiany zapisują się w pliku odbiornika; klucz nieobecny w części wspólnej jest tam dopisywany jako domyślny.
Synchronizacja działa w tle: przy starcie, kilka sekund po zmianie, co 2 minuty i przy wyjściu.
Ustawienia → Synchronizacja: nazwa odbiornika, synchronizuj teraz, przenieś ustawienia z innego odbiornika.

## Pilot

| Klawisz | Działanie (domyślnie — można zmienić: Ustawienia → Mapowanie klawiszy) |
|---|---|
| OK | lista kanałów (◀ ▶ zmiana bukietu; długie OK na kanale — opis bieżącej audycji) |
| przytrzymanie OK | menu: EPG wydarzenia, EPG kanału, Multi EPG, Szukaj w EPG, Ścieżka dźwięku, Ustawienia |
| ▲ / ▼, CH+ / CH− | następny / poprzedni kanał |
| ◀ | EPG kanału |
| ▶ | Multi EPG bukietu (▲ ▼ kanały, ◀ ▶ zmiana czasu o 30 min, przytrzymanie OK — wybór bukietu) |
| INFO | EPG wydarzenia — opis bieżącej audycji |
| GUIDE / EPG | Multi EPG |
| SEARCH | szukaj w EPG |
| MENU | menu |
| cyfry | wybór numeru kanału (OK zatwierdza od razu) |
| LAST / ⏮ | poprzedni kanał |
| WSTECZ ×2 | wyjście |

EPG kanału pokazuje datę oraz godzinę rozpoczęcia i zakończenia każdej audycji; ◀ ▶ przewijają listę o stronę. Jeśli brak krótkiego opisu, lista pokazuje początek opisu rozszerzonego. Multi EPG pokazuje zakresy godzin bieżącej i następnej audycji oraz nazwy kanałów w maksymalnie dwóch liniach. Listy EPG kanału, kanałów Multi EPG i wyboru bukietu przewijają się w pętli.

Przy włączonym timeshifcie długie OK na pasku kanału otwiera pasek przewijania.
Strzałki przewijają tylko na widocznym pasku timeshiftu, również po dotarciu do transmisji na żywo;
na pasku kanału i po ukryciu paska działają jak podczas zwykłego oglądania.
Pasek timeshiftu pozostaje na ekranie w czasie pauzy, a po wznowieniu znika po czasie ustawionym w Ogólnych.
Opcja „Wstecz na pustym ekranie → na żywo” pozwala wrócić z cofniętego strumienia do transmisji na żywo.
Wyjście z aplikacji wymaga dwóch osobnych naciśnięć WSTECZ; przytrzymanie nie zamyka aplikacji.
Tam też można ustawić osobne czasy zamykania listy kanałów oraz EPG wydarzenia, kanału i Multi EPG.
Okna te zamykają się po czasie bezczynności (domyślnie 30 s); 0 wyłącza automatyczne zamykanie.
Korzystanie z pilota lub dotyku zeruje licznik, a otwarcie innego okna wstrzymuje go.

## Ustawienia

Diagnostyka wybudzania (status i test alarmu) jest dostępna tylko w wersjach beta;
ustawienie budzenia TV dla harmonogramu pozostaje dostępne również w wersji stabilnej.

* adres dekodera, port OpenWebif (80), port streamingu (8001), login/hasło,
* silnik odtwarzania: ExoPlayer (domyślnie) albo VLC – dla każdego odbiornika osobno; przy VLC wybór usuwania przeplotu i dekodowania sprzętowego/programowego,
* tunelowanie (ExoPlayer, domyślnie włączone),
* watchdog i próg zawieszenia (domyślnie 3 s),
* bufor startowy – szybki obraz po przełączeniu (domyślnie 700 ms),
* zapas w trakcie oglądania (domyślnie 4000 ms): po starcie odtwarzanie jest lekko zwalniane (0,95×, wysokość dźwięku bez zmian), aż bufor dojdzie do zapasu; przerwy w paczkach z dekodera nie powodują wtedy czkawki. Przy tunelowaniu zwolnienia nie ma (zmiana prędkości psuła płynność obrazu) – wtedy o płynności decyduje bufor startowy (na MiBox 4 zalecane 2500 ms), a zapas buduje się po pierwszym niedoborze,
* odświeżanie ekranu (bez zmian / 50 Hz / automatycznie),
* preferowany język dźwięku i AC3,
* log przez FTP: użytkownik (root), hasło, katalog na dekoderze.

## Budowanie

Workflow `.github/workflows/e2stream.yml` (w katalogu głównym repozytorium) buduje APK przy każdej zmianie w `e2stream/`
i wydaje go jako `e2stream-X.Y.Z` z plikiem `e2stream-X.Y.Z.apk`. Aplikacja aktualizuje się z tych wydań.

Lokalnie: `cd e2stream && ./gradlew assembleRelease` (JDK 17, Android SDK 35).

APK jest podpisywany stałym kluczem `keystore/e2stream.jks` – kolejne wersje instalują się jako aktualizacja.

## Instalacja na TV

* `adb connect IP_TV && adb install -r e2stream-X.Y.Z.apk`, albo
* aplikacja *Downloader* → link do pliku APK z GitHub Release.

## Licencja

GPL-2.0-or-later (z Media3 na Apache-2.0 rozpowszechniana jako całość na warunkach GPL-3.0). Korzysta z AndroidX Media3 (Apache-2.0), libVLC (LGPL-2.1+) i JLayer (LGPL-2.1) – szczegóły w [THIRD_PARTY.md](THIRD_PARTY.md).

## Współpraca z wtyczką EPG Scan

Gdy na dekoderze działa EPG Scan 2.12.0+ z włączonym „Serwerem dla aplikacji E2Stream” (port 8770):

* bukiety i kanały (z rozwiązanymi alternatywami) pobierane są z wtyczki — bez loginu do OpenWebif,
* PID-y kanałów z `lamedb` pozwalają zbudować PMT, gdy dekoder go nie wysyła — także przy pierwszym oglądaniu kanału,
* synchronizacja ustawień i danych kanałów idzie przez API wtyczki (bez FTP), a z EPG Scan 2.15.0+ także log i zrzuty TS – FTP potrzebne jest tylko bez wtyczki; dane na dekoderze:
  `/etc/enigma2/epgscan/clients/common.json` i `device-<id>.json`,
* aplikacja sprawdza paczkę EPG Scan zgodnie z wybranym trybem aktualizacji.

## Współpraca z wtyczką EPG Scan

Gdy na dekoderze działa EPG Scan 2.12.0+ z włączonym „Serwerem dla aplikacji E2Stream” (port 8770):

* bukiety i kanały (z rozwiązanymi alternatywami) pobierane są z wtyczki — bez loginu do OpenWebif,
* PID-y kanałów z `lamedb` pozwalają zbudować PMT, gdy dekoder go nie wysyła — także przy pierwszym oglądaniu kanału,
* synchronizacja ustawień i danych kanałów idzie przez API wtyczki (bez FTP), a z EPG Scan 2.15.0+ także log i zrzuty TS – FTP potrzebne jest tylko bez wtyczki; dane na dekoderze:
  `/etc/enigma2/epgscan/clients/common.json` i `device-<id>.json`,
* aplikacja sprawdza paczkę EPG Scan zgodnie z wybranym trybem aktualizacji.

## Wtyczka EPG Scan na dekoderze

**E2Stream 0.18.14:** wybór sprawdzania paczki raz dziennie albo przy każdym uruchomieniu;
automatyczny import tylko po zmianie pliku EPG.

Gdy na dekoderze jest EPG Scan (2.14.0+), aplikacja sprawdza paczkę raz dziennie (domyślnie, także po północy
podczas działania) albo przy każdym uruchomieniu i powrocie z ekranu głównego. Tryb wybiera się w **Ustawienia →
EPG i ikony kanałów → Aktualizacja z EPG Scan**. Automatycznie pobiera i importuje paczkę tylko po zmianie jej
pliku EPG; ręczne **Pobierz EPG i ikony teraz** wymusza ponowny import, np. po zmianie liczby dni EPG.
Nowe wersje wtyczki identyfikują plik przez SHA-256, starsze przez czas i rozmiar paczki.
Paczka zawiera EPG wszystkich kanałów, ikony i zamiany referencji
(kanał startowy i ostatni przechodzą na nową referencję, gdy kanał zmienił transponder). Bez paczki – jak dotąd, kanał po kanale.
