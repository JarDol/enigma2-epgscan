# E2Stream — odtwarzacz Enigma2 dla Android TV

Część repozytorium EPG Scan: aplikacja kliencka współpracująca z wtyczką (moduł „serwer dla klientów”).
Działa też bez wtyczki — wtedy przez OpenWebif i FTP.

Odtwarzacz strumieni z dekodera Enigma2 (OpenPLi / OpenWebif) dla Android TV. Wymaga Androida 6.0 (API 23) lub nowszego.

* ExoPlayer (Media3) z **tunelowaniem wideo**
  (sprzętowy deinterlacer telewizora, płynne paski informacyjne).
* **Zostaw obraz poprzedniego kanału** (domyślnie włączone) – przy przełączaniu ostatnia klatka starego kanału zostaje
  na ekranie do pierwszej klatki nowego (jak w wersji Windows), zamiast czarnego ekranu. Przy tunelowaniu niektóre
  telewizory nie pozwalają skopiować obrazu — wtedy jak dotąd czarny ekran (wpis w logu).
* **Przyspiesz start kanałów** (ExoPlayer, domyślnie włączone) – przy starcie kanału: gdy dane z dekodera już są
  (zapas ≥ bufor startowy + 1,5 s), a odtwarzacz od 2 s nie rusza (np. pierwsza klatka i obraz stoi), strumień jest
  wznawiany od razu, bez czekania na limit buforowania. Gdy danych jeszcze nie ma, działa zwykły watchdog. Wyłączone —
  zachowanie jak w 0.18.46.
* **Watchdog** – gdy obraz się zawiesi (licznik klatek dekodera albo zegar odtwarzania stoi
  dłużej niż ustawiony limit, zbyt długie buforowanie, błąd lub zerwane połączenie),
  strumień jest automatycznie otwierany ponownie. Przy braku komunikacji z dekoderem aplikacja
  sprawdza jego dostępność mniej więcej co 3 s i wznawia ten sam kanał po odzyskaniu połączenia.
  Podczas oglądania powtarzające się błędy VLC nie włączają trybu bezpiecznego; po sprawdzeniu
  dekodera można ponowić próbę lub otworzyć ustawienia.
* Bukiety i kanały pobierane z OpenWebif (`/api/getservices`), EPG „teraz” (`/api/epgnow`).
* Strumień: `http://DEKODER:8001/<referencja>` (opcjonalnie z logowaniem Basic).
* Wyszukiwanie dekoderów przez mDNS i w sieci lokalnej (ustawienia → „Szukaj dekoderów w sieci”).
* Wybór ścieżki dźwięku zapamiętywany osobno dla każdego kanału; lista ścieżek ma wspólny format dla VLC i ExoPlayera.
  Preferowany język akceptuje kody ISO i nazwy (np. `pl`, `pol`, `Polish`) i normalizuje je do jednego kodu.
* Timeshift zachowuje tę samą ścieżkę audio co strumień na żywo, także przy silniku VLC.
* Odświeżanie ekranu: bez zmian / zawsze 50 Hz / automatycznie wg treści.
* Analizator strumienia TS: błędy CC, TEI, pakiety kodowane, utrata synchronizacji, skoki PCR,
  zmiany PMT, przerwy w napływie danych — w statystykach i w logu.
* Przy zawieszeniu zapis ostatnich sekund strumienia (`stall-*.ts`, bufor 16 MB) do analizy np. TSDuck; na urządzeniu najwyżej 3 zrzuty, po wysłaniu na dekoder kopia lokalna jest usuwana (na dekoderze sprząta je EPG Scan: Sprzątanie dysku → zrzuty starsze niż N dni).
* Miejsce zapisu: pamięć urządzenia lub pendrive/karta (katalog `Android/data/pl.jardol.e2stream/files/`).
* EPG pobierane codziennie wg harmonogramu (i na żądanie) z zapisem na urządzeniu; picony kanałów z dekodera (OpenWebif /picon/ lub FTP). Brakujące picony można zastąpić logo E2Stream; wyłączenie zastępowania zostawia puste miejsce, zachowując układ.
* Logi i zrzuty idą za dyskiem timeshiftu: po wybraniu dysku na timeshift miejsce logów przechodzi na ten dysk (chyba że wybrano je ręcznie); gdy dysk jest odłączony, logi trafiają do pamięci urządzenia, a po ponownym podłączeniu wracają na dysk.
* Ustawienia → Logi i zrzuty → *Pamięć zajmowana przez aplikację*: podział (ustawienia, EPG, picony, pamięć podręczna, logi, timeshift, nagrania) i *Wyczyść pamięć podręczną* (ustawień nie rusza). Przy starcie aplikacja usuwa pobrane pliki aktualizacji (APK ~100 MB każdy) i stare pliki pamięci podręcznej, a zajętość zapisuje w logu.
* Opcjonalnie EIT „teraz/następne” ze strumienia (Ustawienia → EPG i picony kanałów → *EIT ze strumienia*): włączenie jest możliwe tylko, gdy Enigma2 wysyła EIT w strumieniu (*Include EIT in http streams*; sprawdza to wtyczka EPG Scan 2.19.1+). Zmiana programu nadawcy (inny tytuł lub przesunięte godziny bieżącej/następnej audycji) trafia do logu i — przy *Podmieniaj EPG po odebraniu EIT* — do EPG aplikacji do następnej aktualizacji z dekodera; bez podmiany dane z EIT są pokazywane tylko w pamięci, do końca audycji. Do sprawdzenia służy (tylko w wersjach beta) *Test EIT: EPG tylko ze strumienia* (usuwa EPG aplikacji i wyłącza pobieranie z dekodera, także bieżącą audycję listy kanałów) oraz linia EIT w statystykach odtwarzania.
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

Na kanale na żywo aplikacja sprawdza wybór przez dwie sekundy od pojawienia się dostępnych ścieżek, bez opóźniania odtwarzania. Jeśli zapamiętana ścieżka się nie pojawi, próbuje użyć preferowanego języka; ręczny wybór przerywa sprawdzanie. Automatyczny wybór nie nadpisuje zapamiętanej preferencji, a szczegóły wyboru trafiają do logu.

W trybie bezpiecznym przycisk „Otwórz menu” pokazuje menu pozwalające ponowić normalny start, otworzyć ustawienia lub zamknąć aplikację. Samo otwarcie menu nie uruchamia strumienia ani połączenia z dekoderem.

Identyfikatory ścieżek mogą się zmieniać, a dekoder może udostępniać kilka ścieżek o tych samych metadanych. W takim przypadku aplikacja nie ma danych, by odróżnić ich zawartość; może zostać wybrana pierwsza pasująca ścieżka lub domyślny wybór odtwarzacza. Należy wtedy wybrać ścieżkę ponownie. Preferowany język (np. `pl`, `pol` lub `Polish`; `en`, `eng` lub `English`) jest wskazówką dla odtwarzacza, a nie gwarancją wyboru, gdy język jest niedostępny.

Podczas timeshiftu z VLC aplikacja próbuje zachować bieżącą ścieżkę przez dopasowanie PID-u w buforze. Jeśli odpowiedniego PID-u nie ma, stosuje zapamiętany wybór dla kanału.

## Wyszukiwanie dekodera

E2Stream szuka usługi `_epgscan._tcp` przez mDNS oraz sprawdza lokalne podsieci IPv4 /24 na ustawionym porcie OpenWebif.
Opcjonalnie w **Dekoder Enigma2 → Dodatkowa podsieć IPv4** podaj zakres CIDR /24–/30, np. `192.168.10.0/24`;
adres dekodera nadal można wpisać ręcznie. mDNS może przejść między VLAN-ami, jeśli działa reflektor mDNS;
router i zapora muszą także przepuszczać zwykłe połączenia do portów OpenWebif, strumienia i API EPG Scan.
Na dekoderze wymagany jest uruchomiony Avahi oraz włączony serwer E2Stream w EPG Scan. Wykryte porty są przejmowane
z ogłoszenia, jeśli są w nim dostępne. Wyszukiwanie nie wysyła hasła ani tokenu do sprawdzanych adresów.
Odpowiedź HTTP 401 nie potwierdza, że urządzenie jest dekoderem: takie pozycje są oznaczone jako niepotwierdzone
i wymagają świadomego wyboru. Potwierdzenie rozpoznaje odpowiedź API, ale nie jest uwierzytelnieniem kryptograficznym.

## Synchronizacja z dekoderem

Na dekoderze w `<katalog FTP>/sync/` (domyślnie `/media/hdd/e2stream/sync/`):

* `common.json` — wartości domyślne dla nowych odbiorników i wspólne PMT kanałów,
* `device-<id>.json` — ustawienia, kopia ustawień i wybrane ścieżki dźwięku każdego odbiornika (z nazwą i modelem). Z wtyczką EPG Scan 2.19.1+ nazwa to `device-android-<model>-<znacznik>.json` (np. `device-android-MIBOX4-F532.json`; znacznik jak `[F532]` w nazwie odbiornika) — dawny plik `device-<ANDROID_ID>.json` jest przenoszony przy pierwszej synchronizacji. Przez FTP (bez wtyczki) zostaje dawna nazwa.

Przy użyciu API wtyczki aktualna PMT jest przechowywana we wspólnym `common.json`, nie w profilach urządzeń.
EPG Scan usuwa przestarzałe wpisy PMT z profili z zachowaniem ich kopii; lokalny cache PMT odtwarzacza pozostaje bez zmian.
Kopie profili mogą nadal zawierać stare PMT na potrzeby odtworzenia danych.

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
| REC | nagraj (nagranie lokalne; w trakcie — zakończenie nagrania) |
| WSTECZ ×2 | wyjście |

Do dowolnego klawisza można też przypisać: nagrania z dekodera, nagrania lokalne, buforowane kanały, zaplanowane
przełączenia, timery dekodera, uruchomienie kanału od nowa, następny / poprzedni bukiet (podczas oglądania otwiera
listę kanałów na sąsiednim bukiecie; na liście bukiety jak dotąd ◀ ▶), wysłanie logu na dekoder oraz — z PIN-em, jak
wejście do ustawień — silnik odtwarzania (ExoPlayer / VLC), tunelowanie obrazu, odtwarzanie przez bufor i bufor
poprzednich kanałów (zmiana działa od razu, z komunikatem na ekranie).

EPG kanału pokazuje datę oraz godzinę rozpoczęcia i zakończenia każdej audycji; ◀ ▶ przewijają listę o stronę. Jeśli brak krótkiego opisu, lista pokazuje początek opisu rozszerzonego. Multi EPG pokazuje zakresy godzin bieżącej i następnej audycji oraz nazwy kanałów w maksymalnie dwóch liniach. Listy EPG kanału, kanałów Multi EPG i wyboru bukietu przewijają się w pętli.

Przy włączonym timeshifcie długie OK na pasku kanału otwiera pasek przewijania.
Jeśli timeshift jest wyłączony, nie wybrano miejsca bufora lub wybrany dysk jest niedostępny,
pojawia się komunikat z przejściem do pozycji Timeshift w ustawieniach (z zachowaniem blokady PIN).
Przy niedostępnym dysku można kontynuować, jeśli wcześniej zezwolono na użycie pamięci urządzenia.
Strzałki przewijają tylko na widocznym pasku timeshiftu, również po dotarciu do transmisji na żywo;
na pasku kanału i po ukryciu paska działają jak podczas zwykłego oglądania.
Pasek i jego skala pokazują zakres dostępny do przewijania: od początku z zachowanym 10-sekundowym
marginesem bezpieczeństwa do transmisji na żywo. Przy cofaniu wskazanie nie wykracza poza ten początek.
Podczas odtwarzania z bufora pasek pokazuje zarówno przesunięcie względem transmisji, jak i dostępny czas bufora.
W ustawieniach Ogólne dostępne są osobne mapowania OK i strzałek dla ekranu bez paska oraz z paskiem kanału.
Domyślnie strzałki zachowują dotychczasowe przypisania, również zmienione w starszych wersjach,
a OK zachowuje dotychczasowe działanie. Mapowanie nie zmienia działania długiego OK ani paska timeshiftu;
mapowanie nie dotyczy też listy kanałów ani odtwarzania nagrań. Każde z dwóch mapowań można osobno przywrócić.
Na nagraniu długie OK otwiera wybór Odtwórz/Usuń; usunięcie wymaga potwierdzenia.
PIN do ustawień może mieć maksymalnie 6 cyfr. Historię przełączeń można czyścić po 7, 14 lub 28 dniach albo zachować bez limitu.
Pasek timeshiftu pozostaje na ekranie w czasie pauzy, a po wznowieniu znika po czasie ustawionym w Ogólnych.

**Nagrania lokalne z bufora timeshiftu.** Menu (długie OK przy schowanym pasku) → *Nagraj…* / *Zatrzymaj nagrywanie* (pozycje na górze, do ukrycia w konfiguracji menu); długie OK na pasku timeshiftu otwiera od razu wybór nagrania:
*Audycja* (wycięta z bufora: od początku bieżącej audycji do jej końca wg EPG, +1 min), *Bufor do teraz* (natychmiast) albo
*Cały bufor do zmiany kanału*. Kawałki bufora nie są kopiowane — przenoszone (zmiana nazwy) do katalogu `nagrania/`
obok katalogu bufora (ten sam nośnik); po zakończeniu nagrania timeshift zaczyna od nowa, a odtwarzanie z bufora wraca na żywo.
Podczas nagrywania pasek pokazuje *● REC*, a pierwszą pozycją menu długiego OK jest *Zatrzymaj nagrywanie*. Nagranie kończy się
też przy zmianie kanału, wyjściu z aplikacji, po limicie jednego nagrania (1–6 h, domyślnie 3 h) i po osiągnięciu limitu miejsca
(domyślnie 70% pojemności nośnika — reszta dla timeshiftu). W pamięci urządzenia nagrania wymagają osobnej zgody.
Nagrania są w bukiecie *Nagrania lokalne* (wznawianie, opis z EPG, długie OK → Odtwórz / Eksportuj do jednego pliku .ts / Usuń); można go ukryć w *Widoczne bukiety*.
Bufor timeshiftu na bieżąco zapisuje kolejne audycje kanału (z EPG, a przy włączonym EIT — z EIT ze strumienia). Nagranie
*bufor do teraz* / *do zmiany kanału* dostaje spis audycji z czasami od początku nagrania i ich opisy, a w tytule — kanał,
godziny i nazwy audycji. Podczas odtwarzania każdego nagrania lokalnego przytrzymane OK otwiera menu: *Odtwórz od początku*,
*Audycje* (gdy nagranie ma ich kilka — lista, wybór przeskakuje do początku audycji), *Konwertuj na jeden plik .ts* (znika po
konwersji), *Eksportuj kopię* i *Usuń* (konwersja i usunięcie najpierw kończą odtwarzanie). Bez EIT granice pochodzą z ramówki i mogą się przesuwać o kilka minut.
*Konwertuj na jeden plik .ts (bez kopii)* dokleja kolejne kawałki do pierwszego i od razu je usuwa (dodatkowe miejsce ≈ jeden kawałek; przerwanie, także zanik zasilania, nie psuje nagrania — konwersję można dokończyć), na końcu plik w katalogu nagrania dostaje czytelną nazwę i nadal jest w *Nagraniach lokalnych*. *Eksportuj kopię do jednego pliku .ts* skleja kawałki w tle (pasek postępu, czas do końca, *Ukryj* — eksport trwa dalej, *Anuluj* — częściowy plik usunięty) do pliku `nagrania/RRRR-MM-DD GG-MM Kanał - Tytuł.ts`; nagranie w kawałkach zostaje bez zmian, plik wlicza się do limitu miejsca.
Opcja „Wstecz na pustym ekranie → na żywo” pozwala wrócić z cofniętego strumienia do transmisji na żywo.
Wyjście z aplikacji wymaga dwóch osobnych naciśnięć WSTECZ; przytrzymanie nie zamyka aplikacji.
Tam też można ustawić osobne czasy zamykania listy kanałów oraz EPG wydarzenia, kanału i Multi EPG.
Okna te zamykają się po czasie bezczynności (domyślnie 30 s); 0 wyłącza automatyczne zamykanie.
Korzystanie z pilota lub dotyku zeruje licznik, a otwarcie innego okna wstrzymuje go.

## Ustawienia

Diagnostyka wybudzania (status i test alarmu) jest dostępna tylko w wersjach beta;
ustawienie budzenia TV dla harmonogramu pozostaje dostępne również w wersji stabilnej.
Po włączeniu tej opcji zdarzenie używa alarmu systemowego w chwili początku audycji
i próbuje wybudzić urządzenie oraz otworzyć E2Stream; awaryjnie wysyła powiadomienie.
Uruchamianie w tle może wymagać zgody na wyświetlanie nad innymi aplikacjami, a włączenie
telewizora zależy od obsługi i ustawień HDMI-CEC. Dostarczenie alarmu i wybudzenie nie są
gwarantowane na wszystkich urządzeniach. Log podaje planowany i faktyczny czas alarmu.

* adres dekodera, port OpenWebif (80), port streamingu (8001), login/hasło,
* silnik odtwarzania: ExoPlayer (domyślnie) albo VLC – dla każdego odbiornika osobno; przy VLC wybór usuwania przeplotu i dekodowania sprzętowego/programowego,
* tunelowanie (ExoPlayer, domyślnie włączone),
* watchdog i próg zawieszenia (domyślnie 3 s),
* bufor startowy – szybki obraz po przełączeniu (domyślnie 1500 ms; starsze instalacje mogły zachować 700 ms).
  Dla VLC można testowo ustawić 300 ms, ale mniejszy bufor może pogorszyć płynność. Log pokazuje czas do
  pierwszych bajtów i pierwszej klatki według licznika VLC (próbkowanie do 250 ms, a po 15 s do 1 s); sam sygnał utworzenia
  wyjścia wideo VLC nie jest dowodem wyświetlenia klatki,
* zapas w trakcie oglądania (domyślnie 4500 ms): po starcie odtwarzanie jest lekko zwalniane (0,95×, wysokość dźwięku bez zmian), aż bufor dojdzie do zapasu; przerwy w paczkach z dekodera nie powodują wtedy czkawki. Przy tunelowaniu zwolnienia nie ma (zmiana prędkości psuła płynność obrazu) – wtedy o płynności decyduje bufor startowy (na MiBox 4 zalecane 2500 ms), a zapas buduje się po pierwszym niedoborze,
* odświeżanie ekranu (bez zmian / 50 Hz / automatycznie),
* pasek komunikatów odtwarzacza: włączony domyślnie, czas wyświetlania 1–10 s (domyślnie 3 s).
  Wyłączenie ukrywa zwykłe komunikaty, ale nie powiadomienia o odciążeniu nośnika ani ostrzeżenia o problemie z zapisem; okna dialogowe działają niezależnie.
  Przy poważnych opóźnieniach zapisu aplikacja odciąża nośnik, zamykając bufory w tle bez aktywnego nagrania
  (wraz z przypisanym PiP). Nie zamyka bufora oglądanego kanału ani aktywnych nagrań. Jeśli opóźnienia trwają
  lub nie ma bezpiecznego bufora do zamknięcia, pokazuje nazwę nośnika w ostrzeżeniu nie częściej niż raz na minutę.
  Po wybraniu nośnika „Brak” ustawienia buforowania, PiP i timeshiftu są ukryte poza informacjami o wymaganym
  nośniku. Ich zapisane wartości pozostają i wracają po wybraniu nośnika, z zachowaniem zależności od przełączników.
  Wielkość tekstu paska komunikatów jest zgodna z ustawieniem czcionki paska kanału,
* preferowany język dźwięku i AC3,
* log przez FTP: użytkownik (root), hasło, katalog na dekoderze.

**Pasek kanału** – w linii z nazwą kanału stan buforów: „B 12:34 · tło 2/3 (nagr. 1)” (B — ile w buforze oglądanego
kanału, czerwone ● — nagrywa; tło — kanały w tle / limit, nagr. — ile z nich nagrywa); pod logo kanału bieżąca godzina
hh:mm:ss.

**Przesunięcie dźwięku** – od −1 do +1 s co 50 ms (ujemne — dźwięk wcześniej, gdy obraz wyprzedza dźwięk), jedno dla
ExoPlayera (z tunelowaniem i bez) i VLC; w ustawieniach odtwarzania, w menu i pod klawiszem pilota. Nie zmienia bufora
ani nagrań — działa przy odtwarzaniu.

**Historia** – ostatnio oglądane kanały ze wszystkich bukietów (najnowszy na górze, do 50). Osobne okno pod klawiszem
▼ (▲ — zwykła lista kanałów; przypisanie do zmiany w klawiszach, akcja *Lista kanałów — historia*). Dodatkowo bukiet
„Historia” na końcu listy bukietów (◀ z pierwszego bukietu), do ukrycia w *Widocznych bukietach*.

**Pamięć masowa** – jedno miejsce na nośnik i wszystko, co z niego korzysta:
* nośnik: dysk USB / karta, pamięć urządzenia (tylko za zgodą — zużycie pamięci flash) albo „Brak”,
* odtwarzanie przez bufor (domyślnie wyłączone): jedno połączenie z dekoderem zapisuje strumień do bufora na
  nośniku, a odtwarzacz (ExoPlayer, albo VLC przez lokalny serwer 127.0.0.1) czyta koniec bufora. Pauza, cofnięcie
  i powrót na żywo nie otwierają nowego połączenia (bez dziury w buforze). Z timeshiftem bufor jest wspólny; bez
  timeshiftu ma stałe 5 min. Bez nośnika (albo po jego awarii) odtwarzanie wraca do bezpośredniego, a po podłączeniu
  dysku znów idzie przez bufor (od następnego startu kanału),
* bufor poprzednich kanałów (przy odtwarzaniu przez bufor): *Wyłączony / 1 / 2 / 3 kanały* zapisywane w tle; przy
  włączonym osobna pozycja *Czas bufora poprzednich kanałów*: taki sam jak timeshift albo ręczny w minutach (najwyżej jak
  timeshift). Przy kolejnym przełączeniu wypada najdawniej opuszczony kanał. Powrót na taki kanał — obraz od razu z jego
  bufora (od klatki kluczowej z zapasem ≥ bufor startowy + 1 s), z możliwością cofnięcia do tego, co leciało w tym
  czasie. Na listach kanałów (także w historii) kanały z buforem — w tle i oglądany, gdy gra przez bufor — mają znacznik
  **B** (● B — trwa zapis). Długie OK na kanale w tle: bieżąca audycja i drugi rząd przycisków *Zakończ bufor* i
  *Nagraj bufor…* (*Audycja: tytuł* / *Bufor do teraz* / *Do usunięcia bufora*; w trakcie zapisu *Zakończ zapis z
  bufora*). To samo w menu → *Buforowane kanały* (pozycja do ustawienia w kolejności i widoczności menu; widoczna, gdy
  jest jakiś bufor — także samego oglądanego kanału). Nagrań z buforów naraz najwyżej tyle, ile kanałów w tle
  (bufor oglądanego kanału zawsze zostaje); ponad limit — komunikat. Nagranie audycji chroni swój bufor do jej końca
  (przy przełączaniu wypada inny), zapis „do usunięcia bufora” trwa także w tle i kończy się, gdy bufor wypada.
  Opcje: komunikat o zajętych buforach przy przełączaniu, stan buforów przy przełączaniu („Bufory: 2/3, nagrywa 1”),
  Odtwarzanie nagrania (z dekodera albo lokalnego) nie kasuje buforów: bufor ostatnio oglądanego kanału zapisuje się dalej
  w tle (także przy wyłączonym buforze poprzednich kanałów), zostają też kanały w tle z trwającym zapisem; pozostałe
  bufory są usuwane. Po wyjściu z nagrania powrót na ostatni kanał — od razu z jego bufora,
* PiP (eksperymentalne, domyślnie wyłączone) — do 2 okien z kanałami z bufora w tle: klatka co 1/2/3/5 s z ostatniej
  klatki kluczowej w pamięci bufora (własny demultiplekser — także kanały kodowane po odszyfrowaniu), tylko dekoder
  programowy (jeden na okno, bez sprzętowego; gdy go brak — „brak dekodera”), położenie okien; kanał do okna z listy
  kanałów (◀ ▶ bukiety, OK dodaje okno). Bezpiecznik: po awarii albo zawieszeniu z otwartym PiP przy następnym starcie
  PiP wyłącza się samo i proponuje wysłanie logu PiP (osobny plik e2stream-pip.log, 3 ostatnie sesje),
* timeshift (długość bufora, automatyczny start — przy odtwarzaniu przez bufor zawsze od razu, kroki przewijania, WSTECZ na pustym ekranie),
* nagrania lokalne (limit nagrania, limit zajętości nośnika) — wymagają włączonego timeshiftu,
* miejsce logów i zrzutów (domyślnie podąża za nośnikiem) oraz pamięć zajmowana przez aplikację.

Bez wybranego lub przy odłączonym nośniku pozycje są nieaktywne (opis zamiast wartości), a timeshift się nie
uruchamia; ustawienia zostają i po podłączeniu dysku wszystko działa jak przedtem (od następnego kanału).

## Budowanie

Workflow `.github/workflows/e2stream.yml` (w katalogu głównym repozytorium) buduje APK przy każdej zmianie w `e2stream/`
i wydaje go jako `e2stream-X.Y.Z` z plikiem `e2stream-X.Y.Z.apk`. Aplikacja aktualizuje się z tych wydań.

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
