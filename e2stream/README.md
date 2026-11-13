# E2Stream — odtwarzacz Enigma2 dla Android TV

Część repozytorium EPG Scan: aplikacja kliencka współpracująca z wtyczką (moduł „serwer dla klientów”).
Działa też bez wtyczki — wtedy przez OpenWebif i FTP.

Odtwarzacz strumieni z dekodera Enigma2 (OpenPLi / OpenWebif) dla Android TV. Wymaga Androida 6.0 (API 23) lub nowszego.

* ExoPlayer (Media3) z **tunelowaniem wideo**
  (sprzętowy deinterlacer telewizora, płynne paski informacyjne).
* **Watchdog** – gdy obraz się zawiesi (licznik klatek dekodera albo zegar odtwarzania stoi
  dłużej niż ustawiony limit, zbyt długie buforowanie, błąd lub zerwane połączenie),
  strumień jest automatycznie otwierany ponownie. To samo, co ręczne „przełącz i wróć”, tylko w ok. 1–2 s.
* Bukiety i kanały pobierane z OpenWebif (`/api/getservices`), EPG „teraz” (`/api/epgnow`).
* Strumień: `http://DEKODER:8001/<referencja>` (opcjonalnie z logowaniem Basic).
* Wyszukiwanie dekoderów w sieci lokalnej (ustawienia → „Szukaj dekoderów w sieci”).
* Wybór ścieżki dźwięku zapamiętywany osobno dla każdego kanału; lista ścieżek ma wspólny format dla VLC i ExoPlayera.
  Preferowany język akceptuje kody ISO i nazwy (np. `pl`, `pol`, `Polish`) i normalizuje je do jednego kodu.
* Timeshift zachowuje tę samą ścieżkę audio co strumień na żywo, także przy silniku VLC.
* Odświeżanie ekranu: bez zmian / zawsze 50 Hz / automatycznie wg treści.
* Analizator strumienia TS: błędy CC, TEI, pakiety kodowane, utrata synchronizacji, skoki PCR,
  zmiany PMT, przerwy w napływie danych — w statystykach i w logu.
* Przy zawieszeniu zapis ostatnich sekund strumienia (`stall-*.ts`, bufor 16 MB) do analizy np. TSDuck; na urządzeniu najwyżej 3 zrzuty, po wysłaniu na dekoder kopia lokalna jest usuwana (na dekoderze sprząta je EPG Scan: Sprzątanie dysku → zrzuty starsze niż N dni).
* Miejsce zapisu: pamięć urządzenia lub pendrive/karta (katalog `Android/data/pl.jardol.e2stream/files/`).
* EPG pobierane codziennie wg harmonogramu (i na żądanie) z zapisem na urządzeniu; picony kanałów z dekodera (OpenWebif /picon/ lub FTP). Brakujące picony można zastąpić logo E2Stream; wyłączenie zastępowania zostawia puste miejsce, zachowując układ.
* EPG kanału, multi-EPG bukietu i wyszukiwanie w EPG; z audycji: przełącz na kanał, zaplanuj przełączenie lub nagraj na dekoderze.
* Długie OK w EPG kanału i Multi-EPG otwiera wybór kanału/bukietu, a wyszukiwanie jest na końcu menu.
* W opisie EPG „Oglądaj” dla aktualnie oglądanego kanału zamyka opis bez ponownego strojenia. Wybór innego kanału („Przełącz na kanał”), także dla bieżącej audycji, wymaga potwierdzenia.
* Akcje „Przełącz o…”, „Nagraj na dekoderze” i „Usuń przełączenie” wymagają potwierdzenia przed wykonaniem; WSTECZ anuluje. Po wykonaniu pokazywany jest wynik.
* Okna akcji i potwierdzenia domyślnie zamykają się po 30 sekundach; okna informacyjne po 15 sekundach. Czas każdego rodzaju ustawia się osobno w ustawieniach Ogólne (0 = bez zamykania); okna informacyjne można też wyłączyć.
* Menu podręczne z konfigurowalną widocznością i kolejnością pozycji; Ustawienia są zawsze dostępne.
* Domyślne mapowanie OK i strzałek można zmieniać osobno z paskiem kanału i bez niego.
* Przegląd timerów nagrań dekodera z usuwaniem oraz usuwanie plików nagrań z menu długiego OK.
* Historia przełączeń z konfigurowalnym czasem przechowywania; długie OK na zrealizowanej pozycji otwiera szczegóły z potwierdzanym usuwaniem. Jeśli nagranie z historii zostało już skasowane, szczegóły pozwalają usunąć nieaktualny wpis.
* Wybór widocznych bukietów, PIN do ustawień (odblokowanie na wybrany czas, wyjście z aplikacji zawsze blokuje), aktualizacja aplikacji z GitHub (menu → Aktualizacja).
* Język interfejsu: polski, angielski lub automatycznie według języka systemu; wybór jest zapisywany w kopii ustawień urządzenia.
* Po utracie komunikacji z dekoderem aplikacja wstrzymuje ponawianie strumienia, co 3 s sprawdza dostępność i — zależnie od ustawienia „Blokuj zmiany przy braku komunikacji z dekoderem” — blokuje sterowanie do czasu powrotu połączenia. Przy pierwszym uruchomieniu popup oferuje też Ustawienia, aby skorygować adres dekodera.
* Log diagnostyczny: lokalnie w `Android/data/pl.jardol.e2stream/files/e2stream.log`
  i co 2 minuty przez FTP na dekoder (domyślnie `/media/hdd/e2stream/e2stream-<nazwa odbiornika>-<znacznik>.log`, np. `e2stream-Salon-3F2A.log`; znacznik to 4 znaki z identyfikatora urządzenia, więc dwa takie same modele w sieci nie nadpisują sobie plików; nazwa ze znacznikiem jest też w liście odbiorników i w nazwach zrzutów TS).

## Ścieżki audio

Wybór z menu „Ścieżka dźwięku” jest zapamiętywany osobno dla kanału i odbiornika, a następnie synchronizowany w profilu tego urządzenia. Przy kolejnym odtwarzaniu aplikacja próbuje najpierw dopasować zapisany identyfikator ścieżki. Jeśli dekoder nadał jej inny identyfikator, dopasowanie odbywa się po języku i kodeku.

Identyfikatory ścieżek mogą się zmieniać, a dekoder może udostępniać kilka ścieżek o tych samych metadanych. W takim przypadku aplikacja nie ma danych, by odróżnić ich zawartość; może zostać wybrana pierwsza pasująca ścieżka lub domyślny wybór odtwarzacza. Należy wtedy wybrać ścieżkę ponownie. Preferowany język (np. `pl`, `pol` lub `Polish`; `en`, `eng` lub `English`) jest wskazówką dla odtwarzacza, a nie gwarancją wyboru, gdy język jest niedostępny.

Podczas timeshiftu z VLC aplikacja próbuje zachować bieżącą ścieżkę przez dopasowanie PID-u w buforze. Jeśli odpowiedniego PID-u nie ma, stosuje zapamiętany wybór dla kanału.

## Synchronizacja z dekoderem

Na dekoderze w `<katalog FTP>/sync/` (domyślnie `/media/hdd/e2stream/sync/`):

* `common.json` — wartości domyślne dla nowych odbiorników i wspólne PMT kanałów,
* `device-<id>.json` — ustawienia, kopia ustawień i wybrane ścieżki dźwięku każdego odbiornika (z nazwą i modelem).

Po nowej instalacji i wybraniu dekodera aplikacja sprawdza wtyczkę EPG Scan i proponuje import znalezionej kopii
albo pozostanie przy ustawieniach domyślnych. Można wybrać także profil innego odbiornika. Starsze profile
zawierają tylko ustawienia zapisywane przez wcześniejsze wersje. Przy imporcie pozostaje wybrany adres dekodera.
Kopia obejmuje wszystkie ustawienia aplikacji, w tym PIN, dane logowania, ustawienia odtwarzania, menu i lokalne
ścieżki; przed przeniesieniem na inne urządzenie sprawdź ścieżki i zgodność odtwarzacza. Kopia nie obejmuje danych
użytkowych, np. lokalnego harmonogramu ani plików timeshiftu. Kopie na dekoderze są zapisane bez szyfrowania,
dlatego zabezpiecz dostęp do sieci i API wtyczki. Aktualizacja kopii wymaga włączonej synchronizacji i dostępu do dekodera;
przed odinstalowaniem aplikacji upewnij się, że synchronizacja się powiodła.
Zmiany zapisują się w pliku odbiornika; klucz nieobecny w części wspólnej jest tam dopisywany jako domyślny.
Synchronizacja działa w tle: przy starcie, kilka sekund po zmianie, co 2 minuty i przy wyjściu.
Ustawienia → Synchronizacja: nazwa odbiornika, synchronizuj teraz, przenieś ustawienia z innego odbiornika.

## Pilot

| Klawisz | Działanie (domyślnie — można zmienić: Ustawienia → Mapowanie klawiszy) |
|---|---|
| OK | lista kanałów (◀ ▶ zmiana bukietu; długie OK na kanale — opis bieżącej audycji; na nagraniu — opis i Odtwórz) |
| przytrzymanie OK | menu (pozycje i kolejność można zmienić w Ustawieniach; Ustawienia zawsze widoczne) |
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
W ustawieniach Ogólne dostępne są osobne mapowania OK i strzałek dla ekranu bez paska oraz z paskiem kanału.
Domyślnie strzałki zachowują dotychczasowe przypisania, również zmienione w starszych wersjach,
a OK zachowuje dotychczasowe działanie. Długie OK i obsługa paska timeshiftu pozostają bez zmian;
mapowanie nie dotyczy też listy kanałów ani odtwarzania nagrań. Każde z dwóch mapowań można osobno przywrócić.
Na nagraniu długie OK otwiera wybór Odtwórz/Usuń; usunięcie wymaga potwierdzenia.
PIN do ustawień może mieć maksymalnie 6 cyfr. Historię przełączeń można czyścić po 7, 14 lub 28 dniach albo zachować bez limitu.
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

Gałąź `beta` buduje dwa prerelease'y porównawcze libVLC: 3.7.0 i 3.7.7. Wersja stabilna korzysta z 3.6.5.
Są widoczne w APK Updaterze w wyborze `Wersje`; wersja biblioteki jest częścią numeru wydania.
Testuj je po kolei rosnącą wersją — Android może zablokować instalację starszego APK bez odinstalowania aplikacji.

Lokalnie: `cd e2stream && ./gradlew assembleRelease` (JDK 17, Android SDK 36).

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
EPG i picony kanałów → Aktualizacja z EPG Scan**. Automatycznie pobiera i importuje paczkę tylko po zmianie jej
pliku EPG; ręczne **Pobierz EPG i picony teraz** wymusza ponowny import, np. po zmianie liczby dni EPG.
Bez paczki ta opcja pobiera brakujące picony, pozostawiając już zapisane. **Usuń i pobierz ikony od nowa** najpierw
usuwa lokalne picony i włącza ich pobieranie, a następnie pobiera również EPG; przy błędzie pobierania picony
mogą pozostać niedostępne do kolejnej udanej próby.
Nowe wersje wtyczki identyfikują plik przez SHA-256, starsze przez czas i rozmiar paczki.
Paczka zawiera EPG wszystkich kanałów, picony i zamiany referencji
(kanał startowy i ostatni przechodzą na nową referencję, gdy kanał zmienił transponder). Bez paczki – jak dotąd, kanał po kanale.
