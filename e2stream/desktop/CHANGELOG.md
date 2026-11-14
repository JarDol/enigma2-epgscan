# E2Stream Windows – historia zmian

Sekcja `## X.Y.Z` jest opisem wydania testowego `e2stream-windows-X.Y.Z-test` na GitHubie.
Workflow bierze wersję z `packageVersion` w `build.gradle.kts` i publikuje tylko wtedy, gdy ta sekcja istnieje.

## 0.3.5

- Brak obrazu: VLC w kółko zmieniał format klatek (wysokość 1088 / 1090) i nie oddawał żadnej klatki — format ustalany raz na strumień; gdy po 5 s nadal brak klatek, odtwarzacz sam próbuje bez usuwania przeplotu VLC (komunikat i wpis w logu)
- Ostrzeżenia i błędy VLC trafiają do logu aplikacji (każdy komunikat najwyżej 3 razy)

## 0.3.4

- Dekoder Enigma2 — brakujące opcje z Androida: dodatkowa podsieć IPv4 do wyszukiwania, „Blokuj zmiany przy braku komunikacji z dekoderem” (okno do powrotu dekodera), sekcja „Synchronizacja z dekoderem”: synchronizuj ustawienia i dane kanałów, nazwa tego komputera, synchronizuj teraz, przenieś ustawienia z innego odbiornika
- Usunięta pozycja „Bukiet” z ustawień dekodera (to samo co ◀ ▶ na liście kanałów)
- „Śledzenie pilota w logu” — zwykły przełącznik (wcześniej tylko zmienną środowiskową i na niewidoczną konsolę)

## 0.3.3

- Brak obrazu (był sam dźwięk): klatki z VLC w formacie RGBA (w RV32 kanał przezroczystości był pusty i obraz rysował się jako przezroczysty). W logu „obraz: format klatek …” i „obraz: pierwsza klatka …”
- Przy rysowaniu obrazu w oknie aplikacji „Akceleracja sprzętowa: automatycznie” oznacza teraz dekodowanie programowe; przy D3D11VA / DXVA2, gdy przez 5 s nie przyjdzie żadna klatka, odtwarzacz sam przełącza się na programowe (wpis w logu). W logu „obraz: pierwsza klatka …”
- „Aktywuj okno dowolnym klawiszem pilota” (domyślnie włączone): gdy E2Stream jest w tle, pierwsze naciśnięcie dowolnego klawisza pilota HID / IR przywołuje okno, potem klawisze działają zwykle
- „Aktywuj okno klawiszem” nie odbiera już klawisza innym przyciskom (wcześniej przypisanie zabierało np. Power); krótsze nazwy klawiszy HID

## 0.3.2

- Aktualizacja: pobrany instalator nie uruchamiał się („Unsupported URI content”) — teraz uruchamiany przez Windows (Start-Process, z pytaniem UAC), w zapasie przez Eksplorator. Tę wersję trzeba zainstalować ręcznie, kolejne już przez „Sprawdź i zainstaluj aktualizację”

## 0.3.1

- „Aktywuj okno klawiszem”: Ustawienia → Pilot i klawiatura (także pozycja w Menu) — dowolne klawisze pilota HID, IR (WinLIRC) albo klawiatury, które przywołują okno E2Stream, gdy jest w tle (jak Power, ale bez okna wyłączania); na wierzchu klawisz działa zwykle

## 0.3.0

- Obraz rysowany w oknie aplikacji (VLC oddaje klatki), a paski, menu, EPG, listy i ustawienia są warstwami w tym samym oknie — skalują się razem z oknem, nie wychodzą poza nie i nie zabierają fokusu (koniec z chowaniem okna po Wstecz). Pasek kanału i przyciski myszy leżą na obrazie, obraz nie zmienia rozmiaru
- Nagrania lokalne z bufora timeshiftu (jak ustalone dla Androida): długie OK na pasku kanału — timeshift, ponowne długie OK na pasku timeshiftu — „Nagraj z timeshiftu” (też w Menu): audycja (gdy jej początku nie ma w buforze — od najstarszego miejsca), bufor do teraz (bufor zaczyna od nowa), cały bufor do zmiany kanału. Kawałki bufora są przenoszone, nie kopiowane; czerwony wskaźnik ● REC; zmiana kanału albo wyjście kończy nagranie bez pytania
- Nagrania w katalogu „nagrania” obok bufora timeshiftu; najwyżej 70% pojemności dysku (z ostrzeżeniem); najdłuższe nagranie 1–6 h (domyślnie 3 h, Ustawienia → Odtwarzanie i obraz)
- Bukiet „Nagrania lokalne” na liście kanałów (do ukrycia w Widocznych bukietach): odtwarzanie z przewijaniem i wznawianiem; klawisz Menu na nagraniu — Odtwórz / Eksportuj do jednego pliku .ts / Usuń
- „Zapisz bufor timeshiftu” z 0.2.1 zastąpione nagraniami lokalnymi

## 0.2.1

- Watchdog jak w Androidzie: gdy obraz stoi (domyślnie 3 s + 2 s, po starcie kanału 15 s), strumień jest otwierany ponownie (kolejne próby po 0, 1, 2, 5, 10 s); gdy dekoder nie odpowiada — komunikat i sprawdzanie co 3 s, potem wznowienie tego samego kanału lub nagrania. Ustawienia → Odtwarzanie i obraz → Watchdog
- Aktualizacja aplikacji z GitHuba: Ustawienia → Informacje i aktualizacje → „Sprawdź i zainstaluj aktualizację” (ręcznie) i „Aktualizacja automatyczna” (raz na dobę przy uruchomieniu, z pytaniem); kanał tylko stabilne albo także testowe. Instalator uruchamia się, a E2Stream zamyka
- Menu → „Zapisz bufor timeshiftu” (gdy timeshift działa): cały bufor, od początku bieżącej audycji albo ostatnie 5/15/30 min do pliku .ts w katalogu nagrań (domyślnie Wideo\E2Stream; zmiana i otwarcie katalogu w Ustawienia → Odtwarzanie i obraz)

## 0.2.0

Pierwsza wersja stabilna.

- Pasek kanału jak w Androidzie: większa ikona kanału, bieżąca audycja z postępem i czasem do końca, następna audycja
- EPG z EIT strumienia (teraz/następne, jak w Androidzie): Ustawienia → EPG i ikony kanałów → „EIT ze strumienia” (sprawdza ustawienie Enigma2 przez EPG Scan) i „Podmieniaj EPG po odebraniu EIT”
- Długie OK: na obrazie otwiera Menu, przy widocznym pasku kanału — timeshift (jak w Androidzie)
- Wybór kanału cyframi pilota (OK zatwierdza od razu)
- Klawiatura ekranowa w polach tekstowych (strzałki + OK), cyfry z pilota, Kanał − usuwa znak, Kanał + spacja, dyktowanie Windows (Win+H); nowe klawisze do przypisania: „Usuń znak” i „Mikrofon / dyktowanie” (na obrazie otwiera wyszukiwanie EPG z dyktowaniem)
- Wyszukiwanie dekoderów także przez mDNS (wtyczka EPG Scan) — z portami wtyczki, OpenWebif i strumienia
- Po wybraniu dekodera pytanie o import kopii ustawień z innego odbiornika (Android albo Windows); własna kopia zapisywana na dekoderze przez EPG Scan
- Klawisze dawnej mapy (I, G, S…) uruchamiają EPG, Multi EPG i wyszukiwanie zamiast komunikatu „niedostępne”
- Rutynowe komunikaty (kanały w bukiecie, łączenie) tylko w logu — bez paska pod obrazem
- „Ścieżki dźwiękowe wspólne” (dawniej „Ścieżki wspólne z Androidem”)

## 0.1.8

- Ustawienia w układzie Androida (sekcje i listy opcji, okno bez paska tytułu, obsługa pilotem, klawiaturą i myszą); test pilota i mapowanie OK/strzałek
- EPG i ikony kanałów: paczka EPG Scan, FTP dekodera lub OpenWebif; EPG zapisywane w `%APPDATA%\E2Stream\epg`
- Przewodnik EPG: opis audycji (INFO), EPG kanału, Multi EPG (GUIDE), wyszukiwanie (SEARCH); przełączenie, zaplanowane przełączenie i nagranie na dekoderze z opisu audycji
- Nagrania z dysku dekodera (bukiet „Nagrania”, wznawianie od zapisanej pozycji) i timeshift buforowany na dysku komputera
- Pełny ekran bez paska zadań, wybór skalowania obrazu („Obraz”) nad wideo
- Ogólne jak w Androidzie: pozycje i kolejność menu, czasy okien, język aplikacji; przełączenia wg harmonogramu i harmonogramy dekodera
- Ścieżki dźwięku zapamiętywane dla każdego kanału, czytelne polskie nazwy, synchronizacja wyborów z Androidem przez wtyczkę EPG Scan
- Głośność i wyciszenie aplikacji (0–150%) niezależne od głośności Windows
- Logi z wysyłaniem na dekoder, statystyki odtwarzania VLC, pilot IR przez WinLIRC/LIRC

Wymaga VLC 3.x 64-bit (instalowany osobno). Wersja testowa – nie wszystko działa jeszcze tak jak na Androidzie.

## 0.1.7

- Szybsza obsługa pilota HID: mniej powtarzanego odpytywania urządzenia i krótsza kolejka aktualizacji diagnostycznych
- Pełny ekran, tryby skalowania obrazu oraz lista kanałów bez paska okna
