# epgpl – serwer programu TV dla EPG Scan

epgpl to serwer dla sieci domowej (Debian/Ubuntu), który raz dziennie pobiera polski program TV z czterech serwisów, dopasowuje go do kanałów z Twoich bukietów i udostępnia dekoderowi gotowy plik XMLTV. Dekoder z wtyczką EPG Scan importuje go jako źródło **„EPG PL (epgpl)”** – jako zapas dla własnego pobierania albo zawsze.

*English: [README.en.md](README.en.md).*

Serwer jest opcjonalny: EPG Scan potrafi pobierać program sam. Serwer przydaje się, gdy dekoder ma słaby procesor albo mało pamięci, gdy chcesz pobierać rzadziej z dekodera, albo gdy kilka elementów ma pracować w nocy równolegle.

## Spis treści

1. Jak to działa
2. Wymagania
3. Instalacja
4. Konfiguracja
5. Plik ustawień config.env
6. Nocne pobieranie krok po kroku
7. Dopasowanie kanałów i wskazania z dekodera
8. Współpraca z EPG Scan
9. Pliki, katalogi i usługi
10. Aktualizacja, naprawa, usunięcie
11. Diagnostyka i najczęstsze pytania

---

## 1. Jak to działa

| Element | Rola |
|---|---|
| Grabber **iptv-org/epg** (Node.js 22) | pobiera program ze stron serwisów: teleman.pl, programtv.onet.pl, pilot.wp.pl, programtv.naziemna.info |
| **run.sh** (usługa `epgpl.service`, timer `epgpl.timer`) | nocny przebieg: lista kanałów z dekodera → mapa kanałów → pobieranie → porządkowanie → publikacja |
| **server.py** (usługa `epgpl-http.service`, port 8088) | udostępnia pliki dekoderowi i przyjmuje polecenia z EPG Scan (podgląd, wskazania kanałów, wybór bukietów, pobieranie na żądanie) |
| **Avahi** (`_epgpl._tcp`) | ogłasza serwer w sieci – EPG Scan znajduje go sam |

Program pobierany jest **dla Twoich kanałów**: serwer bierze listę kanałów z dekodera (OpenWebif), dopasowuje do nich stacje z serwisów i pobiera tylko te stacje.

## 2. Wymagania

- Debian lub Ubuntu z systemd (x86_64, arm64 albo armv7), ok. 1,5 GB wolnego miejsca w `/opt`.
- Dostęp do internetu (serwisy z programem, GitHub, nodejs.org przy instalacji).
- Dekoder Enigma2 z **OpenWebif** dostępnym bez hasła w sieci lokalnej (lista kanałów).
- EPG Scan 2.3.1 lub nowszy na dekoderze (wyszukiwanie serwera); pełna współpraca – synchronizacja wskazań, pobieranie w jednym miejscu – od EPG Scan 2.10.

Instalator sam sprawdza i doinstalowuje: `git`, `xz-utils`, `python3`, `curl`, `iproute2`, `sudo`, `avahi-daemon` oraz prywatną kopię **Node.js 22** w `/opt/epgpl/node` (systemowy Node.js nie jest zmieniany).

## 3. Instalacja

```
cd /tmp && rm -rf epgpl && unzip -o epgpl-X.Y.Z.zip && cd epgpl && sudo bash install.sh
```

Instalator przechodzi przez 11 kroków. Każdy najpierw sprawdza stan: gdy wymaganie jest spełnione, pokazuje **[OK]**, gdy trzeba coś zmienić – **[ZMIANA]** i pyta (T/n). Gdy funkcja jest już włączona (Avahi, reguła sudo), pyta, czy ją wyłączyć (domyślnie „Nie”).

| Krok | Co sprawdza i co zmienia |
|---|---|
| 1. System | Debian/Ubuntu, systemd, architektura, wolne miejsce; przerywa, gdy trwa pobieranie |
| 2. Pakiety | brakujące pakiety – lista i pytanie |
| 3. Node.js 22 | prywatna kopia z nodejs.org, z kontrolą sumy SHA256 |
| 4. Użytkownik i pliki | użytkownik systemowy `epgpl` (bez logowania), pliki w `/opt/epgpl`, uzupełnienie `config.env` ze starszych wersji |
| 5. Grabber | repozytorium iptv-org/epg (ok. 200 MB) i zależności npm – tylko gdy się zmieniły |
| 6. Konfiguracja | **pierwsza instalacja: adres dekodera** (z kontrolą, czy OpenWebif odpowiada), port, godzina, adres serwera; potem pytanie o ustawienia pobierania |
| 7. Usługi systemd | serwer HTTP i codzienne pobieranie |
| 8. Reguła sudo | `epgpl` może uruchomić wyłącznie `systemctl start epgpl.service` (pobieranie na żądanie z dekodera) |
| 9. Zapora | ufw: port 8088/tcp i 5353/udp tylko dla Twojej podsieci; firewalld: port i usługa mdns; brak zapory – nic |
| 10. Avahi | ogłoszenie `_epgpl._tcp` z portem serwera |
| 11. Sprawdzenie | czy serwer HTTP i dekoder odpowiadają; gdy nie ma pliku programu – propozycja pierwszego pobrania |

Przy aktualizacji instalator pyta na początku: **1** – tylko aktualizacja (ustawienia i wcześniejsze odpowiedzi „tak/nie” zostają, pytania tylko o nowe funkcje, z opisem), **2** – aktualizacja z ponowną konfiguracją (wszystkie pytania).

Bez pytań: `sudo bash install.sh -y --dekoder 192.168.1.20` (pytania z domyślnym „Nie” są wtedy pomijane). Opcje: `--bez-avahi`, `--bez-zapory`.

Wszystko, co instalator zmienia w systemie (doinstalowane pakiety, reguły zapory), zapisuje w `/opt/epgpl/.instalacja` – na tej podstawie działa usuwanie.

Przy nowej instalacji (albo pustych wskazaniach) instalator czyta przez OpenWebif ręczne wskazania kanałów z EPG Scan na dekoderze (`/etc/enigma2/epgscan/wskazania.txt`) i proponuje ich przeniesienie – mapa kanałów od razu taka sama po obu stronach. Gdy OpenWebif nie udostępnia pliku, EPG Scan zaproponuje synchronizację przy dodaniu serwera albo otwarciu dopasowania.

## 4. Konfiguracja

```
sudo bash /opt/epgpl/configure.sh                 # obie części
sudo bash /opt/epgpl/configure.sh --podstawowe    # dekoder, port, godzina, adres serwera
sudo bash /opt/epgpl/configure.sh --pobieranie    # serwisy, opóźnienie, połączenia, dni
```

Każde pytanie ma opis i zalecenie; po zapisie zmiany są od razu stosowane (restart serwera HTTP, timer, plik źródła).

**Część podstawowa**

| Pytanie | Znaczenie |
|---|---|
| Adres IP dekodera | skąd serwer bierze listę Twoich kanałów (OpenWebif); sprawdzany od razu, pokazuje model i obraz |
| Port | port serwera HTTP (domyślnie 8088); sprawdzane, czy wolny |
| Godzina pobierania | np. `03:00`, kilka: `03:00,15:00` |
| Adres serwera dla dekodera | Enter = wykrywany automatycznie (zalecane w sieci domowej); wpisz nazwę (np. `server-linux.local`), inny adres, gdy serwer ma ich kilka, albo adres publiczny/domenę przy udostępnianiu w internecie |

**Ustawienia pobierania**

| Pytanie | Znaczenie | Zalecane |
|---|---|---|
| Serwisy | kolejność pierwszeństwa – kanał bierze program z pierwszego serwisu, który go ma; można pominąć serwis | `teleman,onet,wp,naz` |
| Opóźnienie [ms] | przerwa po każdej stronie na każdym połączeniu | 3000 (jak iptv-org) |
| Połączeń na serwis | równoległe zapytania do jednego serwisu | 2 |
| Dni | ile dni programu pobierać | 7 |
| Dzień poprzedni | trwająca noc jest u serwisów pod poprzednim dniem | 1 (tak) |
| Okno dekodera [h] | serwer pomija własne pobieranie, gdy dekoder uruchamia je w oknie ±N h; tylko bez „pobierania w jednym miejscu” | 3 |
| Logów | ile logów pobierania trzymać | 7 |
| Codziennie program kandydatów | program na dobę dla stacji proponowanych w oknie wyboru stacji EPG Scan (ok. 1000 stacji); ustawienie z dekodera ma pierwszeństwo | 0 (nie) |

**Opóźnienie – na co uważać.** 3000 ms to wartość iptv-org: bezpieczna dla serwisów, pełne pobieranie trwa ok. godziny (w nocy to nie przeszkadza). Niższa wartość przyspiesza (500 ms → ok. 3 minuty dla 180 stacji), ale serwisy mogą zacząć odrzucać zapytania (HTTP 429/403, zerwane połączenia): kanały zostają bez programu, a przy dużej przesadzie serwis może czasowo zablokować adres serwera. Nie schodź poniżej 300 ms; gdy w logu pojawiają się błędy 429 – wróć do 3000.

## 5. Plik ustawień config.env

`/opt/epgpl/config.env` – każda opcja ma opis w tej samej linii; można edytować bezpośrednio (`sudo nano /opt/epgpl/config.env`). Większość działa od następnego pobrania, bez restartu.

| Opcja | Domyślnie | Opis | Po zmianie |
|---|---|---|---|
| `RECEIVER` | – | adres dekodera (OpenWebif) | – |
| `SERVER_IP` | puste | adres serwera w plikach dla dekodera; puste = automatycznie | `sudo -u epgpl bash /opt/epgpl/sources.sh` |
| `PORT` | 8088 | port serwera HTTP | `sudo systemctl restart epgpl-http`, zapora i Avahi: `sudo bash install.sh` |
| `GRAB_TIME` | 01:15 | godzina pobierania (kilka po przecinku) | `sudo bash /opt/epgpl/timer.sh` |
| `DAYS` | 7 | dni programu | – |
| `SITES` | teleman,onet,wp,naz | serwisy w kolejności pierwszeństwa; gdy kolejność ustawia dekoder (EPG Scan 2.4.0+), obowiązuje ta z dekodera | – |
| `GRAB_DELAY` | 3000 | opóźnienie [ms] między stronami | – |
| `MAX_CONNECTIONS` | 2 | połączeń na serwis (przy błędach skrypt sam zmniejsza) | – |
| `GRAB_YESTERDAY` | 1 | pobieraj też dzień poprzedni | – |
| `TUNER_WINDOW` | 3 | okno [h], w którym harmonogram serwera ustępuje dekoderowi; 0 = nigdy; nie działa przy „pobieraniu w jednym miejscu” (EPG Scan) – wtedy decyduje, kto pierwszy pobierze | – |
| `SCOPE` | bouquets | `bouquets` = kanały z Twoich bukietów (+ ich kopie), `all` = wszystko, co się da | – |
| `COPIES` | tp | kopie kanałów w mapie: `tp` = tylko z transponderów z Twoimi kanałami, `all` = wszystkie | – |
| `SKIP_BOUQUETS` | "Last Scanned" | bukiety pomijane przy wyborze kanałów | – |
| `KEEP_LOGS` | 7 | ile logów przechowywać | – |
| `KANDYDACI` | 0 | codzienny program kandydatów (okno wyboru stacji w EPG Scan); ustawienie z EPG Scan ma pierwszeństwo | – |

## 6. Nocne pobieranie krok po kroku

1. **Blokada** – jedno pobieranie naraz (harmonogram, dekoder, ręczne uruchomienie).
2. **Pobieranie w jednym miejscu** (ustawienie w EPG Scan) – start z harmonogramu czeka, gdy dekoder zgłosił, że właśnie pobiera, i jest pomijany, gdy dekoder przysłał dziś pełną paczkę.
   **Okno dekodera** – start z harmonogramu jest pomijany, gdy EPG Scan zgłosił, że sam uruchamia pobieranie w oknie ±`TUNER_WINDOW` h (wpis w `log/pominiete.log`).
3. **Aktualizacja grabbera** iptv-org/epg z GitHuba (przy błędzie – poprzednia wersja) i list stacji serwisów; poprawka dla onet (bez pobierania szczegółów każdej audycji – wielokrotnie szybciej).
4. **Lista kanałów z dekodera** (`/api/getallservices`). Gdy dekoder nie odpowiada – ostatnia zapisana lista.
5. **Mapa kanałów** – które kanały dostają program z której stacji (rozdział 7).
6. **Pobieranie** – serwisy równolegle, każdy z własnym limitem połączeń; od dnia poprzedniego (gdy `GRAB_YESTERDAY=1`). Przy błędach ograniczeń serwisu (429/403) – po 2 min ponowienie nieudanych kanałów z mniejszą liczbą połączeń.
7. **Porządkowanie** – audycje z poprzedniego pliku dla trwającej nocy i kanałów bez nowych danych, usunięcie audycji starszych niż 2 h, kontrola kanałów bez danych (przy następnej mapie kolejny serwis).
8. **Publikacja** – najpierw do plików tymczasowych, potem podmiana: dekoder nigdy nie dostanie połowy pliku. Przy błędzie zostaje poprzedni plik, a raport podaje przyczynę. Znacznik pełnego pobrania (`pelny.json`) mówi dekoderowi, że może wziąć gotową paczkę.
9. **Program kandydatów** – osobna usługa `epgpl-kandydaci.service`, tylko gdy włączony (dekoder na nią nie czeka).

## 7. Dopasowanie kanałów i wskazania z dekodera

Automat dopasowuje kanały po nazwie: bez „HD”, „Poland” itp., z regułą rozszerzenia nazwy (np. regionalne TVP3), kopie kanału na innych transponderach dostają ten sam program. Pierwszeństwo mają, od najwyższego:

1. **wskazania z dekodera** – `wskazania.txt` (EPG Scan → Menu → Kanały → Dopasowanie kanałów: serwer),
2. **Twoje dopasowania** – `aliases.txt` (format: `Nazwa kanału na satelicie = Nazwa w serwisach` albo `= onet:site_id`, `= tm:…`, `= wp:…`, `= naz:…`; `= -` – świadomie bez programu),
3. **dopasowania z pakietu** – `aliases.dist`,
4. automat.

Listy stacji serwisów są odświeżane prosto z ich stron przy każdym pobieraniu i przy przeliczeniu mapy (gdy starsze niż godzina) – nowy kanał w serwisie trafia do mapy bez czekania na iptv-org. Aktualizacja serwera ich nie nadpisuje.

**Kandydaci (0.16.0; od 0.19.0 domyślnie wyłączone):** gdy włączone (`KANDYDACI=1` albo w EPG Scan: *Codziennie program kandydatów* – ustawienie z dekodera ma pierwszeństwo), po udanym pobraniu osobna usługa `epgpl-kandydaci.service` pobiera program na dobę dla stacji, które okno wyboru stacji w EPG Scan proponuje kanałom z bukietów, a których nie ma w pliku programu (`kandydaci.xml.xz` – tylko podgląd, nie trafia do EPG; dekoder nie czeka na ten krok). Ręcznie (niezależnie od ustawienia): `sudo -u epgpl bash /opt/epgpl/kandydaci.sh --zawsze`, log: `/opt/epgpl/log/kandydaci.log`. Z okna wyboru stacji można też pobrać program kandydatów od razu (`POST /api/kandydaci` → `teraz.xml.xz`, pamiętane 24 h).

Okno wyboru stacji w EPG Scan 2.5.0 pokazuje dla stacji z pliku programu średnią długość opisów, liczbę dni i bieżącą audycję – serwer podaje je przez `/api/staty` (od 0.15.0).

Kanały z alternatywami (w bukiecie grupa kilku usług pod jedną nazwą) są rozwijane do usług z grupy przez OpenWebif – tak samo jak na dekoderze.

**Kolejność serwisów** (EPG Scan → Ustawienia → Program z internetu) i **bukiety do dopasowania** ustawia się na dekoderze – to centrum ustawień. Bukiety wybiera się w Dopasowaniu kanałów (niebieski „Bukiety”: wszystkie / wybrane / do skanowania). Wybór trafia na serwer (`work/bukiety.json`), mapa jest od razu przeliczana – np. bez bukietów IPTV.

Każda zmiana z dekodera przelicza mapę od razu (`mapa.sh`); program dla nowego dopasowania przychodzi z następnym pobraniem albo po pobraniu na żądanie.

## 8. Współpraca z EPG Scan

| Funkcja w EPG Scan | Co robi serwer |
|---|---|
| Szukaj serwera epgpl w sieci | odpowiada na Avahi (`_epgpl._tcp`) i na porcie HTTP (`epgpl.sources.xml`) |
| Źródło „EPG PL (epgpl)” | pliki `pl.xml.xz` (program) i `pl.channels.xml.xz` (mapa referencji) |
| Akcje → Serwer epgpl: zapas / zawsze | zapas: gotowy plik, gdy dekoder nie ma swojego; zawsze: także pobieranie na serwerze |
| Dopasowanie kanałów: serwer | mapa (`mapa.json`), listy stacji (`stacje.json`), wskazania (`/api/alias`), lista bukietów (`/api/bukiety`) |
| Podgląd programu stacji | `/api/podglad` – z pliku programu albo pobrane na żądanie (kilkanaście sekund) |
| Pobierz na serwerze | `/api/pobierz` – start `epgpl.service` (reguła sudo z kroku 8) |
| Harmonogram dekodera | `/api/tuner` – dekoder zgłasza swoje pobieranie, serwer pomija własne w oknie ±`TUNER_WINDOW` h |
| Data pliku na liście źródeł | `/api/stan` – czas pliku programu, czy pobieranie trwa, wynik ostatniego przebiegu, znacznik pełnego pobrania (`pelny.json`) |
| Pobieranie w jednym miejscu (0.18.0) | kto pierwszy, ten pobiera: dekoder bierze dzisiejszą pełną paczkę serwera albo wysyła swoją (`/api/paczka`: `pl.xml.xz`, `pl.channels.xml.xz`, `kandydaci.xml.xz`); w trakcie pobierania na dekoderze `/api/zajety` – harmonogram serwera czeka (najwyżej 90 min), a gdy dekoder dziś przysłał paczkę, pomija pobieranie (`log/pominiete.log`) |
| Różnice w dopasowaniu / synchronizacja | `/api/wskazania` (ręczne wskazania serwera), `/api/alias` z listą wskazań (`items`) – wiele naraz, jedno przeliczenie mapy; instalator przy ponownej konfiguracji porównuje wskazania z dekoderem i pyta tylko przy różnicach |
| Program kandydatów | `kandydaci.xml.xz` (nocą), `teraz.xml.xz` (na żądanie z okna wyboru albo przysłany przez dekoder – `/api/teraz`), `/api/staty` |

Polecenia (`/api/…`) serwer przyjmuje **tylko z adresów sieci lokalnej**. Z internetu dostępne są wyłącznie pliki z programem. Do dekodera może być przypisany tylko jeden serwer epgpl; dodanie innego zastępuje poprzedni (po potwierdzeniu).

## 9. Pliki, katalogi i usługi

| Ścieżka | Zawartość |
|---|---|
| `/opt/epgpl/config.env` | ustawienia |
| `/opt/epgpl/aliases.txt`, `wskazania.txt` | Twoje dopasowania / wskazania z dekodera (`wskazania.txt.przed` – kopia sprzed przeniesienia z dekodera) |
| `/opt/epgpl/work/` | lista kanałów z dekodera, mapa, wynik pobierania, `bukiety.json`, `grab.log` (postęp) |
| `/opt/epgpl/www/` | pliki dla dekodera: `pl.xml.xz`, `pl.channels.xml.xz`, `epgpl.sources.xml`, `mapa.json`, `stacje.json`, `raport.txt`, `pelny.json` (znacznik pełnego pobrania), `kandydaci.xml.xz` i `teraz.xml.xz` (program kandydatów) |
| `/opt/epgpl/.instalacja`, `.decyzje` | co zmienił instalator (dla usuwania) i odpowiedzi „tak/nie” (nie są powtarzane przy aktualizacji) |
| `/opt/epgpl/log/` | logi kolejnych przebiegów, `pominiete.log` |
| `/opt/epgpl/node`, `/opt/epgpl/iptv-epg` | Node.js 22 i grabber |
| `/opt/epgpl/tools/` | `porownaj.py` (porównanie serwisów z EPG z satelity), `kopie.py` (kontrola kopii kanałów) |
| `/etc/systemd/system/epgpl*.service`, `epgpl.timer` | usługi i timer |
| `/etc/sudoers.d/epgpl` | jedyne uprawnienie: start pobierania |
| `/etc/avahi/services/epgpl.service` | ogłoszenie w sieci |

## 10. Aktualizacja, naprawa, usunięcie

| Co | Komenda |
|---|---|
| Aktualizacja z GitHub (ustawienia zostają, działa zawsze) | `sudo bash /opt/epgpl/update.sh` (`--check` – tylko sprawdź, `-y` – bez pytań, `--force` – ponownie ta sama wersja) |
| Aktualizacja automatyczna | `AUTOUPDATE=1` w `/opt/epgpl/config.env` – codziennie ok. 12:40 (`epgpl-update.timer`), nie w trakcie pobierania programu; log: `/opt/epgpl/log/update.log` |
| Aktualizacja / naprawa (ustawienia zostają) | `sudo bash install.sh` z rozpakowanej paczki → **1** (pytania tylko o nowe funkcje) |
| Aktualizacja ze wszystkimi pytaniami | `sudo bash install.sh --rekonfiguracja` (albo **2** na początku) |
| Tylko zmiana parametrów | `sudo bash /opt/epgpl/configure.sh` |
| Usunięcie razem z konfiguracją | `sudo bash /opt/epgpl/uninstall.sh` |
| Instalacja od zera | `uninstall.sh`, potem `install.sh` |

`uninstall.sh` pokazuje, co znalazł, pyta o potwierdzenie i zawsze robi kopię ustawień i wskazań kanałów do `/root/epgpl-kopia-<data>.tgz`. Usuwa usługi, timer, regułę sudo, ogłoszenie Avahi, reguły zapory dodane przez instalator, `/opt/epgpl` i użytkownika `epgpl`. Doinstalowane pakiety odinstalowuje tylko na wyraźne życzenie. Działa też dla instalacji ze starszych wersji.

Przywrócenie wskazań kanałów z kopii:

```
sudo tar xzf /root/epgpl-kopia-<data>.tgz -C /opt/epgpl wskazania.txt && sudo chown epgpl:epgpl /opt/epgpl/wskazania.txt && sudo -u epgpl bash /opt/epgpl/mapa.sh
```

## 11. Diagnostyka i najczęstsze pytania

| Co sprawdzić | Jak |
|---|---|
| Pobranie teraz i postęp | `sudo systemctl start --no-block epgpl.service; tail -f /opt/epgpl/work/grab.log` |
| Stan usług | `systemctl status epgpl-http epgpl.timer` |
| Ostatni przebieg | `ls -t /opt/epgpl/log/ \| head -1` i `less` na tym pliku |
| Które kanały mają program i skąd | `http://SERWER:8088/raport.txt` |
| Serwer HTTP | `journalctl -u epgpl-http` |

**Błędy 429/403 w logu.** Serwis ogranicza zapytania – zwiększ `GRAB_DELAY` (3000) albo zmniejsz `MAX_CONNECTIONS`.

**Kanał bez programu.** Sprawdź `raport.txt`; wskaż stację z dekodera (Dopasowanie kanałów: serwer – z podglądem programu) albo dopisz w `aliases.txt`.

**Dekoder nie znajduje serwera.** Sprawdź, czy działa `avahi-daemon` i czy zapora przepuszcza 5353/udp; EPG Scan szuka też w podsieci na porcie 8088. Adres można wpisać ręcznie: `http://SERWER:8088/epgpl.sources.xml`.

**Dekoder był wyłączony w nocy.** Serwer użyje ostatniej zapisanej listy kanałów – program i tak zostanie pobrany.

**Serwer w internecie.** Technicznie możliwe: ustaw `SERVER_IP` na adres publiczny lub domenę i otwórz port (z internetu dostępne są tylko pliki programu; mapa kanałów jest zbudowana z Twoich bukietów). Pamiętaj jednak, że udostępnianie pobranego programu innym osobom może naruszać regulaminy serwisów – zobacz *Zastrzeżenie*. Zalecane: tylko sieć domowa albo dostęp przez VPN do własnych urządzeń.

## Zastrzeżenie

Program telewizyjny pobierany z serwisów (teleman.pl, programtv.onet.pl, pilot.wp.pl, programtv.naziemna.info) należy
do ich właścicieli. Wtyczka i serwer pobierają go do prywatnego użytku na własnym dekoderze – z przerwami między
zapytaniami i ograniczoną liczbą połączeń. Użytkownik odpowiada za to, by korzystanie z nich było zgodne z regulaminami
tych serwisów; nie udostępniaj pobranego programu innym osobom. Repozytorium zawiera wyłącznie kod i konfigurację – nie
zawiera pobranego programu. Oprogramowanie jest dostarczane bez jakiejkolwiek gwarancji (szczegóły w licencji GPL).

## Licencja

epgpl – GNU GPL w wersji 2 lub (według wyboru) dowolnej późniejszej (GPL-2.0-or-later), pełny tekst w pliku `LICENSE`.
Kod i programy innych autorów, z których korzysta (m.in. iptv-org/epg – Unlicense, Node.js – MIT), ich licencje oraz uwagi o danych
z serwisów programu TV: `THIRD_PARTY.md`.

