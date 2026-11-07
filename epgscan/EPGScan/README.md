# EPG Scan

Wtyczka do odbiorników Enigma2 (OpenPLi 9.x, Python 3), która sama dba o pełny i aktualny przewodnik
programowy (EPG). *English: [README.en.md](README.en.md).* Łączy trzy źródła programu i jedno narzędzie do pilnowania listy kanałów:

- **satelita** – równoległe skanowanie transponderów na wolnych tunerach (EIT),
- **program z internetu** – pobieranie polskiego programu TV bezpośrednio na dekoderze
  (teleman.pl, programtv.onet.pl, programtv.naziemna.info, pilot.wp.pl) oraz import XMLTV,
- **serwer epgpl** (opcjonalny) – gotowy plik programu przygotowany na serwerze w sieci domowej,
- **SatWatch** – pilnowanie zmian na satelicie (`satellites.xml`, transpondery) i naprawa bukietów,
  gdy nadawca zmieni parametry lub identyfikatory kanałów.

Całość może działać w nocy w czuwaniu, według harmonogramu, a rano pokazuje krótkie podsumowanie.

Wtyczka jest przygotowana z myślą o polskich kanałach na Hotbird 13.0E, ale skan satelity, XMLTV
i SatWatch działają z dowolną pozycją i dowolnymi bukietami.

---

## Spis treści

1. [Instalacja i odinstalowanie](#1-instalacja-i-odinstalowanie)
2. [Pierwsze uruchomienie](#2-pierwsze-uruchomienie)
3. [Ekran główny](#3-ekran-główny)
4. [Akcje i profil](#4-akcje-i-profil)
5. [Przebieg: co dzieje się po kolei](#5-przebieg-co-dzieje-się-po-kolei)
6. [Satelita – skan transponderów](#6-satelita--skan-transponderów)
7. [Program z internetu](#7-program-z-internetu)
8. [SatWatch – zmiany na satelicie i bukiety](#8-satwatch--zmiany-na-satelicie-i-bukiety)
   - [8.4 Konfiguracja tunerów – co ustawić w systemie, a co we wtyczce](#84-konfiguracja-tunerów--co-ustawić-w-systemie-a-co-we-wtyczce)
9. [Harmonogram, praca nocna i restart](#9-harmonogram-praca-nocna-i-restart)
10. [Kopie EPG, log, sprzątanie dysku](#10-kopie-epg-log-sprzątanie-dysku)
11. [Menu Kanały](#11-menu-kanały)
12. [Raporty](#12-raporty)
13. [Wszystkie ustawienia](#13-wszystkie-ustawienia)
14. [Pliki tworzone przez wtyczkę](#14-pliki-tworzone-przez-wtyczkę)
15. [Najczęstsze pytania](#15-najczęstsze-pytania)

---

## 1. Instalacja i odinstalowanie

**Paczka .ipk** (zalecane):

```
opkg install /tmp/enigma2-plugin-extensions-epgscan_X.Y.Z_all.ipk && init 4; sleep 2; init 3
```

**Archiwum ZIP**:

```
rm -rf /usr/lib/enigma2/python/Plugins/Extensions/EPGScan;cd /tmp && unzip -o epgscan-X.Y.Z.zip -d /usr/lib/enigma2/python/Plugins/Extensions/;init 4; sleep 2; init 3
```

Wtyczka pojawia się w **Menu → Rozszerzenia** i na liście wtyczek jako **EPG Scan**.

Opcjonalnie: pakiet `enigma2-plugin-extensions-epgimport-sources` (listy źródeł XMLTV Rytec i inne).
Wtyczka EPG Import **nie jest potrzebna** – EPG Scan ma własny import XMLTV.

**Odinstalowanie**: `opkg remove enigma2-plugin-extensions-epgscan` usuwa samą wtyczkę (ustawienia zostają).
Pełne usunięcie razem z konfiguracją, raportami i kopiami: skrypt `epgscan_reset-X.Y.sh` (najpierw podgląd,
potem `--run`). Skrypt robi kopię wszystkiego, co usuwa (patrz [rozdział 14](#14-pliki-tworzone-przez-wtyczkę)).

---

## 2. Pierwsze uruchomienie

Po instalacji nic nie dzieje się samo: harmonogram jest wyłączony, program z internetu i SatWatch też.
Wtyczka tylko po starcie Enigma2 sprawdza, czy EPG nie jest puste (i odtwarza kopię, jeśli jakąś zrobiła).

Proponowana kolejność:

1. **Niebieski → Satelita – EPG** – *Kanały z bukietów (OK – lista)*: bukiety do skanowania (domyślnie wszystkie) i liczba tunerów.
2. **OK** – pierwszy skan satelity. Na ekranie widać każdy transponder i postęp.
3. **Niebieski → Program z internetu** – włącz *Program z internetu (XMLTV)*, jeśli chcesz pełny
   program na 14 dni także dla kanałów, które z satelity mają tylko kilka godzin albo nic.
4. **Zielony (Akcje)** – zaznacz źródła (np. *Dekoder*), **żółty** zapisuje to jako profil domyślny.
5. **Menu → Kanały → Dopasowanie kanałów: dekoder** – sprawdź, czy każdy kanał dostał właściwą stację.
6. **Niebieski → Harmonogram** – włącz nocny przebieg (np. 3:00, w czuwaniu).

---

## 3. Ekran główny

U góry stan: liczba transponderów i kanałów, limit równoległych skanów, wolne tunery, zmiany od
ostatniego skanu. Niżej ostatnie wyniki każdej części (SatWatch, satelita, pobieranie, import),
aktualny profil i termin następnego startu z harmonogramu, a pod spodem lista transponderów z ich stanem.

Listy wyboru wtyczki (menu, filtry, synchronizacja, sortowanie) zajmują pełną szerokość ekranu, bez okienka
z obrazem – długie wiersze mieszczą się w całości. Obraz z bieżącego kanału zostaje tam, gdzie jest potrzebny:
w oknie wyboru stacji, w podglądzie programu i w oknie decyzji SatWatch.

| Klawisz | Działanie |
|---|---|
| **OK** | start według profilu domyślnego |
| **Zielony – Akcje** | jednorazowy start z innymi ustawieniami albo zmiana profilu |
| **Żółty – Kopie EPG** | odtworzenie EPG z kopii / zrobienie kopii teraz |
| **Niebieski – Ustawienia** | grupy ustawień, także SatWatch (w trakcie pracy: wyjście z ekranu, praca trwa w tle) |
| **Menu – Kanały** | SatWatch, dopasowanie kanałów do stacji, raport mapy (też w trakcie skanu) |
| **Info/EPG – Raporty** | statystyki, zasięg EPG, duble, raporty SatWatch, log, opis wtyczki i serwera (README) |
| **Czerwony / Exit** | wyjście (praca trwa w tle) albo przerwanie |

Ekran można zamknąć w każdej chwili – skan, pobieranie i import działają w tle, a po zakończeniu
pojawia się powiadomienie.

---

## 4. Akcje i profil

**Akcje** (zielony) to lista kroków przebiegu. To, co jest tu zaznaczone, robi **OK** na ekranie
głównym i harmonogram (po zapisaniu żółtym jako domyślne). Zielony/OK w Akcjach uruchamia przebieg
jednorazowo, bez zmiany profilu.

| Pozycja | Znaczenie |
|---|---|
| **SatWatch** | *nie* / *zmiany* (porównanie `satellites.xml`, skan tylko zmienionych transponderów, naprawa bukietów) / *pełny skan transponderów* |
| **Czyść EPG przed startem** | *nie czyść* (scalanie z istniejącym) / *całe EPG tylko przy harmonogramie* / *całe EPG przy każdym skanie* / *tylko skanowane kanały* (jeśli obraz to obsługuje) |
| **Satelita** | skan transponderów (EIT) |
| **Dekoder** | program pobierany przez dekoder z polskich serwisów |
| **Serwer epgpl** | *zapas* (gotowy plik serwera, gdy dekoder nie ma swojego) / *zawsze* / *nie* |
| **Pozostałe źródła XMLTV** | listy XMLTV zaznaczone w ustawieniach |
| **Pobierz świeże dane** | *tak – w jednym miejscu*: gdy druga strona (serwer albo dekoder) ma dzisiejszą pełną paczkę – ona zamiast pobierania; *tak – pobierz na nowo*: zawsze własne pobieranie; *nie*: pliki, które są (pobiera i tak, jeśli pliku brak albo jest starszy niż 30 h) |

Pod listą widać opis wybranej pozycji i pełną kolejność kroków, np.
`SatWatch (zmiany) -> satelita -> dekoder -> serwer (zapas) -> pozostałe`.

---

## 5. Przebieg: co dzieje się po kolei

1. **SatWatch** (jeśli włączony) – sprawdza `satellites.xml`. Brak zmian = od razu dalej, bez zajmowania
   tunerów. Zmiany = skan zmienionych transponderów (czeka na wolny tuner najwyżej 15 min), naprawa
   bukietów, potem 10 s przerwy na zwolnienie tunerów.
2. **Czyszczenie EPG** – według profilu.
3. **Satelita** – równoległe strojenie transponderów na wolnych tunerach (z rezerwą dla użytkownika),
   zbieranie EIT aż przestaną przybywać nowe audycje.
4. **Pobieranie programu** – równolegle ze skanem: dekoder pobiera polskie serwisy, serwer epgpl
   (jeśli jest) – według profilu.
5. **Import XMLTV** – po skanie satelity: kanały bez EPG z satelity dostają cały program, kanały
   z EPG z satelity – tylko dalsze dni (bez dubli).
6. **Kopia EPG** na dysk, podsumowanie, ewentualny restart (tylko po nocnym przebiegu i tylko gdy potrzebny).

Satelita zawsze ma pierwszeństwo: Enigma2 i tak przyjmuje EIT przed importem, więc import XMLTV tylko
uzupełnia to, czego satelita nie dał.

---

## 6. Satelita – skan transponderów

- Skanowane są transpondery kanałów z wybranych bukietów (albo wszystkie z `lamedb`).
- Kilka transponderów naraz (*Maks. równoległych skanów*), zawsze z *rezerwą wolnych tunerów*.
  Gdy użytkownik albo nagranie potrzebuje tunera, skan oddaje go i wraca do transpondera później.
- Transponder, na którym już pracuje tuner (oglądany kanał), jest skanowany „przy okazji”, bez
  zajmowania kolejnego tunera.
- Koniec na transponderze, gdy przez *N s* nie przybywa nowych audycji (min./maks. czas do ustawienia).
- Transpondery bez EPG można pomijać i sprawdzać ponownie co kilka dni.
- **EIT wyłączone w ustawieniach EPG Enigma2**: wtyczka włącza je na czas skanu albo na stałe.
  Gdy import z satelity jest wyłączony – może wymuszać wyłączenie EIT (EPG tylko z XMLTV).
- **Zawieszony tuner**: 3 nieudane strojenia z rzędu na tym samym tunerze (różne transpondery) =
  wpis w logu ze stanem wszystkich tunerów i w podsumowaniu nocy; w nocy w czuwaniu restart Enigma2
  i powtórka przebiegu (najwyżej raz na noc), przy ręcznym starcie pytanie o restart.
- Przed nagraniem z timera wtyczka trzyma tunery wolne i wstrzymuje nowe skany.

---

## 7. Program z internetu

### 7.1 Pobieranie na dekoderze

Dekoder sam pobiera program polskich stacji z czterech serwisów: **teleman.pl, programtv.onet.pl,
programtv.naziemna.info, pilot.wp.pl** – domyślnie 14 dni do przodu, w tle, zwykle w kilka minut.

- Osobna liczba połączeń dla każdego serwisu i limit łączny oraz przerwa po każdej stronie (domyślnie 1 s) – żeby nie
  przeciążać serwisów. Gdy serwis ogranicza zapytania (HTTP 429/503), przerwa na tym serwisie sama się wydłuża (do 5 s).
- Strony, które się nie pobrały, są ponawiane; brak danych w serwisie na dany dzień nie jest błędem.
- Wynik: plik XMLTV `pl.xml.xz` w katalogu kopii (`…/epgscan/lokalne/`) i źródło XMLTV
  **„EPG PL (dekoder)”**. Plik zostaje podmieniony tylko wtedy, gdy pobranie się udało.
- Błędy pobierania zapisywane do `lokalne/bledy.txt` (opcjonalnie).

### 7.2 Mapa kanałów (który kanał bierze którą stację)

Budowana na dekoderze z Twoich bukietów i list stacji serwisów:
dopasowanie po nazwie (bez „HD”, „Poland” itp., z regułą rozszerzenia nazwy, np. regionalne TVP3),
kopie kanałów na innych transponderach dostają ten sam program. Ręczne wskazania z ekranu
**Dopasowanie kanałów** mają pierwszeństwo i są zapisywane w `/etc/enigma2/epgscan/wskazania.txt`. Dalej liczą się Twoje
dopasowania tekstowe `/etc/enigma2/epgscan/aliases.txt` i dopasowania z paczki `aliases.dist` – te same pliki i zasady co na serwerze epgpl.

Alternatywnie mapa może przychodzić z serwera epgpl (*Mapa kanałów dla dekodera*).

### 7.3 Import XMLTV

Własny import (bez wtyczki EPG Import):

- źródła: *EPG PL (dekoder)*, serwer epgpl, listy z pakietu `epgimport-sources` (np. Rytec), własne
  listy pod adresem URL albo z `/etc/enigma2/epgscan_sources/wlasne`; gdy brak pakietu – wbudowana lista luster,
- kolejność źródeł do ustawienia (np. dekoder → serwer → pozostałe),
- *Co uzupełniać*: tylko kanały bez EPG z satelity albo także dalsze dni pozostałych,
- kanały z wybranych bukietów (wszystkie / wybrane / do skanowania) i opcjonalnie kopie kanałów spoza bukietów,
- **audycje zastępcze** („Brak programu do HH:MM”) w przerwach – żeby kanał był widoczny w Multi EPG
  także wtedy, gdy pierwsza audycja zaczyna się później.

### 7.4 Serwer epgpl (opcjonalny)

Osobny projekt uruchamiany na serwerze w sieci domowej (Linux), który przygotowuje gotowy plik
programu. EPG Scan traktuje go jako **zapas** (gdy dekoder nie ma świeżego pliku) albo źródło
**zawsze**. Bez serwera wtyczka działa w pełni – wszystko robi dekoder.

**Dodanie serwera**: Ustawienia → Program z internetu → żółty (źródła XMLTV) → niebieski →
*Szukaj serwera epgpl w sieci (Avahi i podsieć)*. EPG Scan pyta przez Avahi (mDNS, usługa `_epgpl._tcp`),
a potem przeszukuje podsieć odbiornika (port 8088, około 20 s). Znalezione serwery pokazuje z datą pliku
programu; wybór dodaje źródło. Można też wpisać adres ręcznie. Po dodaniu serwera dekoder wysyła na niego swoje
ustawienia (bukiety, kolejność serwisów) i porównuje dopasowanie kanałów – przy różnicach postępuje według ustawienia
*Różnice w dopasowaniu* (pytanie / wygrywa dekoder / wygrywa serwer / pozostaw).

**Pobieranie w jednym miejscu** (Program z internetu, domyślnie *tak*; epgpl 0.18.0+): przy pobieraniu z harmonogramu
program pobiera tylko ten, kto jest pierwszy. Gdy serwer pobrał dziś pełny program (udany przebieg, tyle dni, ile chce
dekoder, dane dla co najmniej 90% stacji), dekoder bierze jego paczkę – program, mapę referencji i program kandydatów –
zamiast pobierać sam. Gdy serwer właśnie pobiera, dekoder czeka na koniec (najwyżej 90 min). Gdy pierwszy był
dekoder, po pobraniu (i programie kandydatów) wysyła paczkę na serwer, a harmonogram serwera dziś już nie pobiera;
w trakcie pobierania dekoder zgłasza serwerowi, że pobiera, więc serwer nie rusza równolegle. Paczka jest przyjmowana
także przy różnicach w dopasowaniu – tego dnia obowiązuje mapa strony, która pobierała. Start profilu (harmonogram, OK, Akcje) – według
Akcje → **Pobierz świeże dane**: *nie* / *tak – w jednym miejscu* (gotowa dzisiejsza paczka drugiej strony, gdy jest)
/ *tak – pobierz na nowo*; „Dekoder: pobierz program teraz” w dopasowaniu kanałów zawsze pobiera. Program kandydatów pobrany na dekoderze na żądanie trafia też na serwer (stacje, których serwer nie ma),
a okno wyboru przy mapie dekodera pokazuje także stacje, które ma tylko serwer.

---

## 8. SatWatch – zmiany na satelicie i bukiety

### 8.1 Sprawdzenie zmian

SatWatch porównuje transpondery monitorowanych pozycji (domyślnie 13.0E) z aktualnym plikiem
`satellites.xml` OpenPLi (GitHub):

- **brak zmian** – nic nie robi i nie zajmuje tunerów,
- **zmiany** – aktualizuje `/etc/enigma2/satellites.xml` (z kopią; plik systemowy `/etc/tuxbox/satellites.xml`
  nigdy nie jest zmieniany) i skanuje tylko zmienione/nowe transpondery,
- **brak pliku lokalnego albo pozycji** – odtwarza plik z wersji zdalnej, bez skanu, zgłasza potrzebę restartu,
- nieudane transpondery są ponawiane w tle, gdy wszystkie tunery będą wolne,
- **pełny skan** wszystkich transponderów pozycji – z Akcji albo raz w tygodniu w wybrany dzień.

Skan zajmuje jeden wolny tuner (oglądanie trwa dalej, o ile zostaje „rezerwa wolnych tunerów”).
**Preferowany tuner do skanu** wybiera się w ustawieniach SatWatch z listy tunerów DVB-S dekodera; gdy jest
zajęty, skan od razu bierze inny wolny (bez czekania). „Automatycznie” – najwyższy wolny tuner.
Postęp (np. `Skan 19/73 (26%, zostało ok. 4 min) 11258 H 27500 (tuner H)`) widać w oknie SatWatch
i na ekranie głównym EPG Scan; szczegóły w logu `satwatch.log`.

Przy tunerze FBC i Unicable Enigma może odmówić skanu, gdy zajęty jest tuner z tej samej grupy (to samo wejście
antenowe), np. przez oglądanie, szybkie przełączanie (FCC) albo streaming. Pomaga drugi kabel na wejście B
i podział tunerów na dwie grupy – jedna do oglądania, druga (z preferowanym tunerem) do skanu.

Po skanie: serwisy, których już nie ma na zeskanowanym transponderze, mogą być usuwane z `lamedb`
(z zabezpieczeniem przed „podejrzanym” transponderem, na którym znaleziono mniej niż połowę kanałów).
Przy wyłączonym usuwaniu raport i tak wypisuje serwisy, których skan nie znalazł, a kanały z bukietów
wskazujące na nie trafiają do podmiany albo decyzji – tak jak zgubione (lista: `/etc/enigma2/satwatch/nieaktualne.json`).

### 8.2 Naprawa bukietów

SatWatch trzyma zrzut `lamedb` i bukietów. Gdy kanał z bukietu zniknie (zmiana SID/TSID, przeniesienie
na inny transponder, zmiana nazwy), szuka kandydata w aktualnym `lamedb` i **podmienia go sam**, gdy:

1. nazwa jest identyczna,
2. nazwa jest taka sama po pominięciu HD/4K/Poland i spacji,
3. jest dokładnie jeden podobny kandydat (≥ 75%) z tym samym pierwszym słowem.

Przy kilku kandydatach rozstrzygają kolejno: *już w Twoich bukietach*, *nowy*, *ten sam strumień (TS)*,
*ten sam SID*. Pozostałe kanały trafiają **do decyzji**. Przed każdą zmianą robiona jest kopia bukietów.

Kandydaci są szukani po nazwie i SID-zie; nadawca i transponder podnoszą ich pozycję. Gdy nadawca zmieni
naraz nazwę i SID, kandydatami do wyboru są kanały z tego samego strumienia (TS) albo od tego samego nadawcy
na tym transponderze – najpierw nowe po skanie i nieobecne w bukietach. Takie kanały nigdy nie są podmieniane
automatycznie.

### 8.3 Kanały do decyzji

**Menu → Kanały → SatWatch → Kanały do decyzji**: lista kanałów, a po wybraniu – ekran decyzji:

- u góry dane dawnego kanału (nazwa, nadawca, transponder, SID, typ, gdzie w bukietach, powód),
- lista kandydatów (nazwa, SID, znaczniki „SID zgodny”, „w bukietach”, „z tego TP”),
- **okno podglądu** – zielony przełącza na kandydata; po wyjściu wraca poprzednio oglądany kanał,
- pod listą szczegóły kandydata: SID zgodny/inny, ten sam/inny transponder, nadawca, zgodność nazwy,
  typ, czy kanał jest już w bukietach (podmiana zrobiłaby dubel),
- **OK** – podmień, **żółty** – usuń kanał z bukietów, **niebieski** – zostaw jak jest.

**Menu → Kanały → SatWatch** – jedna lista wszystkich akcji (ustawienia SatWatch: Ustawienia → SatWatch – transpondery):

| Akcja | Co się dzieje |
|---|---|
| Sprawdź teraz, Pełny skan (po potwierdzeniu), Napraw bukiety | start w tle, krótki komunikat, powrót do listy; postęp na górze listy (odświeżany na bieżąco: „Przerwij skan (Skan 21/73 – 27%, zostało ok. 4 min)”, po końcu „Skan zakończony: …”) i na ekranie głównym; wynik w Raportach |
| Kanały do decyzji | lista kanałów i ekran decyzji (8.3) |
| Podgląd bukietów | okno z porównaniem bukietów ze zrzutem |
| Zapisz zrzut, Akceptuj obecny stan, Przeładuj transpondery | wynik w komunikacie, powrót do listy |
| Przywróć kopię bukietów / `satellites.xml` | lista kopii (najnowsze na górze); po wyborze przywrócenie, przeładowanie list / transponderów i komunikat |
| Konfiguracja tunerów | porada: co ustawić w systemie, EPG Scan i SatWatch (8.4) |

Raporty SatWatch (ostatni, historia) i log są w **Info/EPG → Raporty**.

### 8.4 Konfiguracja tunerów – co ustawić w systemie, a co we wtyczce

Skan EPG (EIT) i skan satelity (SatWatch) potrzebują tunerów. Żeby działały bez obsługi i nie przeszkadzały
w oglądaniu, nagrywaniu i streamingu, część ustawia się w systemie (Enigma2), a część we wtyczce.
Wtyczka sama rozpoznaje układ tunerów i podpowiada: **Raporty → Konfiguracja tunerów** albo
**Menu → Kanały → SatWatch → Konfiguracja tunerów**; krótka wskazówka jest też pod ustawieniami „Rezerwa wolnych tunerów”
i „Preferowany tuner do skanu”.

| Dekoder | System (Enigma2) | EPG Scan | SatWatch |
|---|---|---|---|
| **1 tuner** | nic | rezerwa 0, harmonogram w nocy, „Czekaj, gdy trwa nagrywanie” | rezerwa 0, preferowany: automatycznie |
| **2 i więcej zwykłych tunerów** | ostatni tuner (np. B) – priorytet **1** (niższy niż AUTO), pozostałe AUTO | rezerwa 1 | preferowany = ten tuner (B), rezerwa 1 |
| **FBC, jedno wejście antenowe** | wszystkie tunery w jednej grupie – patrz niżej | rezerwa 1 | skan satelity zwykle tylko przy wolnych tunerach (w nocy, w czuwaniu) |
| **FBC, dwa wejścia** (zalecane) | A + połączone C…H – priorytet AUTO; **B bez połączonych – priorytet 1** | rezerwa 1 | preferowany = **B**, rezerwa 1 |

Priorytet tunera: menu Enigmy → Ustawienia → Tunery i skanowanie → Konfiguracja tunerów → wybrany tuner (nazwy menu
mogą się różnić w zależności od obrazu). Tuner z niższym priorytetem Enigma bierze do oglądania i nagrań na końcu,
więc zwykle jest wolny dla skanu. Gdy preferowany tuner jest zajęty, SatWatch od razu bierze inny wolny; gdy Enigma
odmówi tunera, próbuje kolejnego.

**Jeden tuner.** Skan EPG i skan satelity zajmują jedyny tuner – w tym czasie nie obejrzysz ani nie nagrasz kanału
z innego transpondera. Dlatego oba pracują w nocy, w czuwaniu. Rezerwa musi być 0, inaczej skan nigdy nie ruszy.

**Dwa tunery.** Jeden (zwykle drugi) ustaw w systemie z niższym priorytetem i wybierz go w SatWatch jako
preferowany. Oglądanie i nagrania idą na pierwszy, skan – na drugi.

**FBC (Full Band Capture).** Jeden moduł FBC DVB-S2 do VU+ to jeden tuner z **dwoma wejściami** antenowymi (A, B)
i **8 demodulatorami** (A–H). C–H nie mają własnego gniazda – każdy korzysta z wejścia A albo B (podział ustawia
się w konfiguracji tunerów; fabrycznie 3 + 3, ale może być np. 6 + 0). Liczba tunerów nie zależy od liczby kabli:
z jednym kablem (B przez wewnętrzną pętlę od A) też jest 8.
Przy jednym wejściu Enigma może **odmawiać skanu satelity**, gdy w grupie tego wejścia coś pracuje – oglądanie,
szybkie przełączanie (FCC), stream do klienta. Skan EPG działa normalnie. Rozwiązanie: drugi kabel na wejście B
i dwie grupy – duża do oglądania, mała (B) do skanu. Przy Unicable każdy tuner musi mieć własny SCR,
niepowtarzalny na swoim kablu.

**Przykład: VU+ Uno 4K SE (OpenPLi 9.2), FBC 8 tunerów, Unicable (SCR 9–16), Hotbird 13.0E, FCC dla 3 kanałów,
streaming do klientów.**

| Konfiguracja | Wynik testu |
|---|---|
| jeden kabel; A: C, E, G; B (pętla od A): D, F, H | pełny skan satelity (73 TP, ok. 5 min) **tylko w czuwaniu**; przy oglądaniu albo podłączonym kliencie streamingu Enigma nie dawała tunera do skanu |
| jeden kabel; B…H połączone z A; B priorytet 1, w SatWatch preferowany B | B nie gra podczas oglądania, ale skan i tak **odrzucony** – B jest w tej samej grupie FBC co zajęty A |
| dwa kable; A: C, E, G; B: D, F, H | skan działa podczas oglądania, ale przy FCC (3 kanały) i streamingu zajęte bywają też tunery grupy B – skan nie ma wolnej grupy |
| **dwa kable; A + C, D, E, F, G, H – priorytet AUTO; B sam – priorytet 1; SatWatch: preferowany B** | **oglądanie + FCC + streaming na grupie A, skan satelity na B w dowolnym momencie** |

Test SatWatch (zmyślony SID dwóch kanałów, potem pełny skan): kanał z tą samą nazwą na satelicie – podmiana
albo decyzja z kandydatami „ta sama nazwa”; kanał ze zmienioną nazwą i SID – w decyzji kanały z tego samego TP
(od 2.10.8). Przy wyłączonym „Usuwaj nieaktualne serwisy” SatWatch od 2.10.8 też wykrywa takie kanały.

---

## 9. Harmonogram, praca nocna i restart

- **Tryb**: o godzinie w wybrane dni albo co N godzin; **tylko w czuwaniu** (czeka na czuwanie) albo zawsze.
- Opcjonalnie: czekanie, gdy trwa nagrywanie; **wybudzanie z głębokiego czuwania** i powrót do niego po pracy.
- Przebieg przerwany restartem odbiornika jest powtarzany po starcie.
- **Doczytywanie braków w ciągu dnia**: co N godzin sprawdza kanały, którym EPG kończy się wkrótce,
  i doczytuje tylko te transpondery (zawsze albo tylko w czuwaniu, opcjonalnie o stałej godzinie).
- **SatWatch: pełny skan raz w tygodniu** w wybrany dzień (w pozostałe dni – sprawdzenie zmian).
- **Restart po nocnym przebiegu** (*nigdy* / *gdy potrzebny* / *zawsze*). „Gdy potrzebny” oznacza:
  tunery nie stroją, mało wolnej pamięci, Enigma2 zajmuje dużo więcej pamięci niż po starcie,
  SatWatch zaktualizował `satellites.xml`, opcjonalnie profilaktycznie co N dni. Restart tylko w czuwaniu,
  bez nagrań teraz i w ciągu 15 min, najwyżej raz na 20 h.
- **Podsumowanie nocy** – po włączeniu odbiornika (po ok. minucie faktycznego używania) okienko
  z najważniejszymi wydarzeniami: restart i jego powód, zawieszony tuner, kanały do decyzji, błędy.

---

## 10. Kopie EPG, log, sprzątanie dysku

- **Kopia EPG po skanie** do katalogu kopii (domyślnie `/media/hdd/epgscan/`), zachowywanych N ostatnich.
- **Odtwarzanie po starcie, gdy EPG puste** – automatycznie albo z pytaniem (dwa pomiary w odstępie minuty).
- **Żółty – Kopie EPG** na ekranie głównym: odtworzenie wybranej kopii albo kopia teraz.
- **Log** `epgscan.log` w katalogu kopii (albo w `/tmp`); kilka poprzednich przebiegów w `epgscan.log.1`, `.2`…
- **Sprzątanie dysku** (domyślnie wyłączone): co N minut usuwa z zaznaczonych katalogów pliki timeshift starsze
  niż N godzin (pomija używane) i zostawia N najnowszych crash logów Enigma2 (łącznie ze wszystkich katalogów).
  Katalogi wybiera się z listy: nośniki, katalog timeshift i crash logów z ustawień Enigma2 oraz własne.

Wybór bukietów wygląda wszędzie tak samo – **Kanały z bukietów (OK – lista)**: w Satelita – EPG (skan),
w Programie z internetu (program i mapa kanałów) i w SatWatch (pilnowane bukiety). OK otwiera listę bukietów,
OK na pozycji zaznacza / odznacza, zielony zapisuje, czerwony anuluje.

---

## 11. Menu Kanały

1. **SatWatch** – lista akcji SatWatch: sprawdzenie, skan, kanały do decyzji (rozdział 8.3), zrzut, kopie, konfiguracja
   tunerów (ustawienia – w Ustawieniach, raporty i log SatWatch – w Raportach).
2. **Dopasowanie kanałów: dekoder** – lista kanałów z bukietów i przypisanych stacji.
   Klawisze: **OK** – wskaż źródło (okno wyboru stacji), **Menu** – sortowanie (zgodnie z bukietami / alfabetycznie) i pobieranie programu (na serwerze, gotowy plik, na dekoderze),
   **Info/EPG** – różnice z drugą mapą i synchronizacja (niżej), czerwony – zamknij, zielony – odśwież,
   żółty – filtr (lista z liczbą kanałów: wszystkie / bez dopasowania / bez programu / tylko ręczne / teleman / onet /
   naziemna / WP Pilot / różni się od drugiej mapy / propozycje zmian),
   niebieski – bukiety (wszystkie / wybrane / do skanowania; to samo ustawienie decyduje, które kanały dostają
   program z internetu; przy pierwszym otwarciu wybór jest wymagany).

   **Okno wyboru stacji** (to samo dla dekodera i serwera): u góry kanał, bukiet, obecne źródło i okienko z kanałem
   na żywo – wtyczka sama przełącza na kanał (gdy nie ma wolnego tunera, pokazuje komunikat), a po wyjściu wraca
   poprzedni kanał. Lista: na samej górze obecne źródło, dalej najlepsi kandydaci (przy równej ocenie w kolejności serwisów), potem stacje
   ze wspólnym słowem w nazwie (np. *Historia Poland* → *Discovery Historia*). Pod listą: serwis, stacja, średnia
   długość opisów, na ile dni jest program i bieżąca audycja – dla stacji, które już są w pliku programu (pozostałe: „–”).
   Klawisze: **OK** – wybierz, czerwony – wróć, zielony – **automat** (usuń ręczne wskazanie kanału), żółty – bez programu,
   niebieski – szukaj stacji, **Menu** – pobierz program zaznaczonej stacji albo wszystkich kandydatów bez programu (w tle, okno można
   zamknąć; przy propozycji zmiany także „Pomiń propozycję”), **Info/EPG** – podgląd programu stacji
   od 2 h wstecz do 4 h naprzód (z zielonym „Zapisz to źródło”).

   **Różnice dekoder / serwer:** gdy jest serwer epgpl, lista porównuje się z drugą mapą (inna stacja albo ręczne
   wskazanie tylko po jednej stronie) i pokazuje „Różni się od serwera: N” (w dopasowaniu serwera – „od dekodera”).
   **Info/EPG**: pokaż różnice (osobne okno: lista kanałów, pod nią pełne źródła dekodera i serwera; zielony – weź z dekodera, żółty – weź z serwera, niebieski – wszystkie naraz), synchronizuj – wygrywa dekoder /
   wygrywa serwer, porównaj ponownie. Gdy wygrywa ręczne wskazanie – trafia na drugą stronę; gdy wygrywa automat –
   druga strona traci ręczne wskazanie (wraca automat); gdy po obu stronach jest automat z różnymi źródłami (inne listy
   stacji) – źródło zwycięskiej strony jest wskazywane po obu stronach.
   Ustawienie **Program z internetu → Różnice w dopasowaniu**: wybór za każdym razem (domyślnie – pytanie przy
   otwarciu dopasowania) / wygrywa dekoder / wygrywa serwer (synchronizacja sama przy otwarciu) / pozostaw różne.
   Porównanie obejmuje też ręczne wskazania kanałów spoza wspólnej mapy (np. bukiet wybrany tylko po jednej stronie)
   – na liście różnic „(poza wspólną mapą)”; wymaga epgpl 0.19.3. Porównanie odbywa się przy każdym otwarciu
   dopasowania kanałów i po dodaniu serwera. Synchronizacja wielu kanałów naraz wymaga epgpl 0.17.0.

   **Propozycje zmian `[!]`:** przy każdym wczytaniu mapy kanały z ręcznym wskazaniem są sprawdzane: wskazanej stacji
   nie ma już w serwisie, stacja nie dała programu w ostatnim pobraniu albo jest stacja o dokładnie tej samej nazwie co
   kanał. Na liście `[!]`, w nagłówku „Propozycje zmian: N”, pod listą powód i proponowana stacja; w oknie wyboru
   proponowana stacja jest zaraz pod obecną (`[propozycja]`). Menu w oknie wyboru – „Pomiń propozycję” (wraca, gdy
   wskazanie się zmieni). Dla mapy serwera wymagany epgpl 0.17.1 (informacja o stacjach bez danych).

   **Program kandydatów:** opcja *Program z internetu → Codziennie program kandydatów* (domyślnie **wyłączona**; obowiązuje też
   na serwerze epgpl). Gdy włączona, po każdym udanym pobraniu programu (na dekoderze przy mapie z dekodera, na serwerze epgpl
   0.16.0+ przy mapie z serwera) w tle pobierany jest program na dobę dla wszystkich kandydatów, których okno
   proponuje kanałom z bukietów (bez stacji, które już są w pliku programu). Służy tylko do podglądu w oknie wyboru –
   nie trafia do EPG. Dzięki temu bieżąca audycja, opisy i podgląd są od razu, bez czekania. Oznaczenia na liście:
   `[w programie]` – stacja z pliku programu, `[podglad]` – program kandydatów albo podgląd pobrany przed chwilą.
3. **Dopasowanie kanałów: serwer** – to samo dla mapy serwera epgpl (gdy jest serwer).
4. **Przenieś wskazania z serwera do dekodera**.
5. **Raport mapy kanałów dekodera**.

---

## 12. Raporty

**Info/EPG** na ekranie głównym:

- **SatWatch** – ostatni raport, historia (30 ostatnich), log,
- **Statystyka EPG** – liczba audycji, kanały z EPG, zasięg w dniach, duble,
- **Kanały – zasięg EPG** (od najkrótszego), **Duble** – nakładające się audycje,
- **Pobieranie na dekoderze** – ostatni wynik, plik, stacje według serwisu, błędy,
- **Porównanie dekoder / serwer** i **test porównawczy** (gdy jest serwer epgpl),
- **Log EPG Scan** (najnowsze na górze), **raporty serwera** epgpl,
- **Opis wtyczki (README)** – ten opis na ekranie dekodera, **Opis serwera epgpl (README)** – opis z serwera (epgpl 0.19.4+).

---

## 13. Wszystkie ustawienia

**Niebieski → Ustawienia**, pięć grup, od SatWatch – transpondery. Wartości domyślne w nawiasach.

### SatWatch – transpondery
| Ustawienie | Opis |
|---|---|
| Akcja przy zmianach satellites.xml | tylko raport / raport + aktualizacja `/etc/enigma2/satellites.xml` z kopią (raport + aktualizacja) |
| Skanuj zmienione TP automatycznie | po aktualizacji pliku skan tylko nowych i zmienionych TP (tak) |
| Pełny skan raz w tygodniu, dzień | w nocnym przebiegu EPG Scan wszystkie TP monitorowanych pozycji (tak, niedziela) |
| Usuwaj nieaktualne serwisy po skanie | usuwa z lamedb serwisy nieznalezione na zeskanowanym TP; wyłączone – raport i decyzje bez usuwania (tak) |
| Podmieniaj kanały w bukietach automatycznie | tylko przy pewnym kandydacie, reszta do decyzji (tak) |
| Czas na skan mimo zajętych tunerów | ile czekać na wolny tuner (2 h) |
| Wznów przerwany skan, gdy tunery wolne | dokończenie później, do 24 h (tak) |
| Rezerwa wolnych tunerów | (1; przy jednym tunerze 0) |
| Preferowany tuner do skanu | lista tunerów DVB-S dekodera; zajęty – inny wolny (automatycznie) |
| Monitorowane pozycje | OK – lista pozycji z `satellites.xml` do zaznaczania, na górze pozycje z tunerów (13.0E) |
| Zakres aktualizacji pliku | wybrane pozycje / cały plik (wybrane pozycje) |
| Tolerancja częstotliwości [MHz] | przesunięcie TP, które nie jest zmianą (3) |
| Kanały z bukietów (OK – lista) | lista bukietów TV, a pod nimi radiowe z dopiskiem *(radio)*; pilnowane są zaznaczone, nowe bukiety od razu (wszystkie oprócz Last Scanned) |
| Zrzut i raport bukietów przy sprawdzaniu | (tak) |
| Przeładuj transpondery po aktualizacji | bez restartu GUI (nie) |
| Katalog logu i historii raportów | pamięć wewnętrzna / dysk / USB (pamięć wewnętrzna) |
| Powiadomienie na ekranie | nigdy / tylko przy zmianach / zawsze (tylko przy zmianach) |
| Liczba kopii satellites.xml | (5) |
| Weryfikacja certyfikatu SSL | (tak) |
| Adres satellites.xml | OK – lista: wbudowany OpenPLi tuxbox-xml i dopisane (żółty – dodaj, sprawdzany przed zapisem; zielony – popraw; niebieski – usuń; OK – domyślny) (OpenPLi) |

Każda opcja ma opis pod listą ustawień (w EPG Scan i w SatWatch).

### Satelita – EPG: skan transponderów, EIT, bukiety
| Ustawienie | Opis |
|---|---|
| Import EPG z satelity | skan transponderów (tak) |
| Wymuszaj włączenie EIT | gdy EIT wyłączone w Enigma2: *na czas skanu* / *na stałe* (na czas skanu) |
| Wymuszaj wyłączenie EIT | gdy import z satelity wyłączony (tak) |
| Kanały z bukietów (OK – lista) | strzałki: wszystkie bukiety / wybrane bukiety / wszystkie transpondery z lamedb; OK – lista bukietów, zapis ustawia *wybrane bukiety* (wszystkie bukiety) |
| Maks. równoległych skanów | (4) |
| Rezerwa wolnych tunerów | dla użytkownika i nagrań (1) |
| Min. / maks. czas na transponderze | (30 s / 180 s) |
| Koniec po braku nowych zdarzeń | (60 s) |
| Pomijaj transpondery bez EPG / sprawdzaj ponownie co | (nie / 7 dni) |

### Program z internetu
| Ustawienie | Opis |
|---|---|
| Program z internetu (XMLTV) | włącza całą część internetową (nie) |
| Kolejność źródeł | dekoder / serwer / pozostałe w dowolnej kolejności (dekoder, serwer, pozostałe) |
| Kolejność serwisów | pierwszeństwo stacji, gdy jest w kilku serwisach (teleman, onet, naziemna, WP); obowiązuje też na serwerze epgpl |
| Mapa kanałów dla dekodera | budowana na dekoderze / z serwera epgpl (dekoder) |
| Różnice w dopasowaniu (dekoder / serwer) | wybór za każdym razem / wygrywa dekoder / wygrywa serwer / pozostaw różne (wybór za każdym razem) |
| Codziennie program kandydatów (okno wyboru stacji) | program na dobę dla kandydatów po każdym pobraniu, dekoder i serwer – ok. 1000 stacji; bez tego program na żądanie z Menu w oknie wyboru (nie) |
| Pobieranie w jednym miejscu (kto pierwszy) | harmonogram: program pobiera dekoder albo serwer – kto pierwszy, drugi bierze paczkę (tak) |
| Co uzupełniać | kanały bez EPG z satelity / także dalsze dni pozostałych (także dalsze dni) |
| Kanały z bukietów (OK – lista) | strzałki: wszystkie bukiety (także IPTV) / wybrane bukiety / bukiety do skanowania; OK – lista bukietów, zapis ustawia *wybrane bukiety*; te same bukiety są dopasowywane w mapie kanałów (wszystkie) |
| Także kopie kanałów spoza bukietów | (tak) |
| Audycja zastępcza w przerwach | nie / od teraz do pierwszej audycji / także przerwy od 30 min (od teraz do pierwszej audycji) |
| Dekoder: dni do przodu | (14) |
| Połączeń: teleman / onet / naziemna / WP Pilot, maks. łącznie | (4 / 3 / 2 / 1, 10) |
| Przerwa między stronami | po każdej stronie na każdym połączeniu; przy 429/503 wydłużana sama do 5 s (1 s) |
| Zapisuj błędy pobierania | (tak) |
| Żółty: pozostałe źródła XMLTV | zaznaczanie list, dodawanie własnych adresów (niebieski) |

### Harmonogram i doczytywanie
| Ustawienie | Opis |
|---|---|
| Harmonogram włączony / Tryb | wyłącznik (tak); tryb: wyłączony / o godzinie w wybrane dni / co N godzin (wyłączony) |
| Godzina, dni tygodnia, co ile godzin | (3:30, wszystkie dni, 6 h) |
| Kiedy uruchamiać | tylko w czuwaniu / zawsze (tylko w czuwaniu) |
| Czekaj, gdy trwa nagrywanie | (nie) |
| Wybudzaj z głębokiego czuwania / wróć po skanie | (nie / tak) |
| SatWatch: pełny skan raz w tygodniu, dzień | (tak, niedziela) |
| Doczytywanie braków w ciągu dnia | (nie); sprawdzaj co (3 h), próg (6 h), kiedy (zawsze), dodatkowo o stałej godzinie (nie, 14:00) |

### Kopie EPG, log, powiadomienia, restart, sprzątanie dysku
| Ustawienie | Opis |
|---|---|
| Powiadomienie po pracy w tle | (tak) |
| Kopia EPG po skanie, katalog, liczba kopii | (tak, `/media/hdd/epgscan/`, 3) |
| Odtwarzanie EPG po starcie, gdy puste | wyłączone / zapytaj / automatycznie (automatycznie) |
| Zapisuj log | w katalogu kopii / w `/tmp` (w katalogu kopii) |
| Restart po nocnym przebiegu | nigdy / gdy potrzebny / zawsze (gdy potrzebny) |
| Profilaktycznie co [dni], próg wolnej pamięci | (nie, 100 MB) |
| Sprzątanie dysku | (nie); katalogi (OK – lista: nośniki, katalog timeshift i crash logów z ustawień Enigma2, własne – żółty dodaj, niebieski usuń; `/media/hdd`), timeshift starszy niż (6 h), zostaw crash logów – łącznie (10), co ile minut (15) |

---

## 14. Pliki tworzone przez wtyczkę

| Plik / katalog | Zawartość |
|---|---|
| `/etc/enigma2/settings` | ustawienia `config.plugins.epgscan.*` i `config.plugins.satwatch.*` |
| `/etc/enigma2/epgscan/wskazania.txt`, `aliases.txt` | ręczne wskazania stacji, własne dopasowania (te same nazwy co na serwerze) |
| `/etc/enigma2/epgscan_sources/` | kopie list źródeł XMLTV, własne listy, źródło „EPG PL (dekoder)” |
| `/etc/enigma2/epgscan_tps.json`, `epgscan_xmltv.json`, `epgscan_wakeup` | stan transponderów, źródeł, wybudzania |
| `/etc/enigma2/satwatch/` | stan SatWatch, zrzut bukietów, kopie bukietów i `satellites.xml` |
| `/etc/enigma2/satellites.xml` | tylko gdy SatWatch zaktualizował transpondery |
| `/media/hdd/epgscan/` | kopie EPG, log, `lokalne/` (pobrany program, mapa kanałów, raporty) |
| `/media/hdd/satwatch/` | log i historia raportów SatWatch (gdy wybrano ten katalog) |

Wtyczka **nie zmienia**: bukietów (poza naprawą przez SatWatch, zawsze z kopią), ustawień tunerów,
pliku systemowego `/etc/tuxbox/satellites.xml`, crona. Zmienia ustawienie EIT w Menu → EPG tylko
zgodnie z opcjami w grupie *Satelita*.

---

## 15. Najczęstsze pytania

**Czy potrzebuję serwera epgpl?** Nie. Dekoder sam pobiera program i buduje mapę kanałów.

**Czy potrzebuję wtyczki EPG Import?** Nie. Pakiet źródeł `epgimport-sources` jest opcjonalny.

**Dlaczego kanał nie ma EPG?** Info/EPG → *Kanały – zasięg EPG* oraz Menu → Kanały →
*Dopasowanie kanałów: dekoder* (filtr *bez programu*). Można wskazać stację ręcznie i zobaczyć
podgląd jej programu przed zapisem.

**Oglądam, a SatWatch czeka na tuner.** Przy oglądaniu, szybkim przełączaniu (FCC) albo streamingu Enigma2 może nie
dać tunera do skanu – zwłaszcza przy tunerze FBC z jednym wejściem antenowym. SatWatch czeka (najwyżej 15 min
w przebiegu), a gdy nie ma zmian – w ogóle nie sięga po tuner. Jak ustawić tunery, żeby skan działał także podczas
oglądania: [rozdział 8.4](#84-konfiguracja-tunerów--co-ustawić-w-systemie-a-co-we-wtyczce).

**Kanał zniknął z bukietu po zmianie na satelicie.** Menu → Kanały → SatWatch → *Kanały do decyzji*.

**Gdzie są logi?** Info/EPG → *Log EPG Scan* i *SatWatch – log*; pliki w `/media/hdd/epgscan/`
i `/media/hdd/satwatch/`.

## Zastrzeżenie

Program telewizyjny pobierany z serwisów (teleman.pl, programtv.onet.pl, pilot.wp.pl, programtv.naziemna.info) należy
do ich właścicieli. Wtyczka i serwer pobierają go do prywatnego użytku na własnym dekoderze – z przerwami między
zapytaniami i ograniczoną liczbą połączeń. Użytkownik odpowiada za to, by korzystanie z nich było zgodne z regulaminami
tych serwisów; nie udostępniaj pobranego programu innym osobom. Repozytorium zawiera wyłącznie kod i konfigurację – nie
zawiera pobranego programu. Oprogramowanie jest dostarczane bez jakiejkolwiek gwarancji (szczegóły w licencji GPL).

## Licencja

EPG Scan – GNU GPL w wersji 2 lub (według wyboru) dowolnej późniejszej (GPL-2.0-or-later), pełny tekst w pliku `LICENSE`.
Kod i programy innych autorów, z których korzysta (m.in. iptv-org/epg – Unlicense, Enigma2/OpenPLi – GPL-2.0), ich licencje oraz uwagi o danych
z serwisów programu TV: `THIRD_PARTY.md`.

