# E2Stream Windows – historia zmian

Sekcja `## X.Y.Z` jest opisem wydania testowego `e2stream-windows-X.Y.Z-test` na GitHubie.
Workflow bierze wersję z `packageVersion` w `build.gradle.kts` i publikuje tylko wtedy, gdy ta sekcja istnieje.

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
