# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan 2.14.0 - rownolegle odswiezanie EPG dla Enigma2 (OpenPLi)
#
# 1.1.0: postep liczony dla wszystkich kanalow z bukietow na danym transponderze
# 1.2.0: dynamiczny przydzial tunerow (rezerwa wolnych), wspoldzielenie tunerow
# 1.3.0: strojenie po jednym (Unicable), liczenie EPG co 5 s
# 1.3.1: dluzszy limit strojenia, tuner w logu przy bledzie
# 1.3.2: bez odczytu stanu sygnalu ze sterownika (blokowal Enigma2)
# 1.4.0: ustawienia, wlasna lista kanalow z wybranych bukietow (bez EPG Refresh),
#        tryby czyszczenia EPG, kopie zapasowe i odtwarzanie EPG po starcie,
#        transpondery wspoldzielone nie licza sie do limitu
# 1.4.1: skan w tle (Exit nie przerywa), zakres skanowania, informacja o zmianach,
#        licznik wszystkich kanalow transpondera (lamedb), optymalizacje
# 1.5.0: harmonogram (o godzinie w wybrane dni / co N godzin, tylko w czuwaniu,
#        pomijanie przy nagrywaniu, wybudzanie i powrot do glebokiego czuwania),
#        zakres "wszystkie transpondery", opcjonalne pomijanie transponderow bez EPG,
#        opcja powiadomienia po skanie w tle, kontrola EPG po starcie z dwoma pomiarami
# 1.5.1: kolorowe przyciski z opisami, statystyka EPG (Info, log, kontrola po starcie),
#        "czekaj, gdy trwa nagrywanie" domyslnie wylaczone i z opisem
# 1.5.2: naprawiony restart Enigma2 przy otwieraniu wyboru bukietow,
#        poprzedni log zachowany jako epgscan.log.1, wpis o starcie z harmonogramu
# 1.5.3: statystyka EPG w logu 3/6/10/15 min po starcie
# 1.5.4: limit rownoleglych tylko w ustawieniach, pole Exit na ekranie, log w katalogu
#        kopii EPG z rotacja (tyle logow, ile kopii, min. 2)
# 1.6.0: wbudowany import XMLTV (bez wtyczki EPG Import): uzupelnianie kanalow bez EPG
#        z satelity (takze IPTV) i dalszych dni, wybor zrodel, licznik dubli w statystyce,
#        domyslnie 60 s bez nowych zdarzen
# 1.6.1: czerwony = Wyjscie poza skanem (pole Exit tylko w trakcie), ostrzezenia przy przerywaniu
# 1.6.2: czas XMLTV bez sekund (Azman), najpierw dzialajace serwery, nazwy kanalow w logu importu
# 1.6.3: odrzucanie nieaktualnych danych XMLTV (proba kolejnego serwera), stan zrodel na liscie wyboru
# 1.6.4: 4 pola przyciskow, opis klawisza EPG
# 1.6.5: w trakcie skanu niebieskie pole = szare Exit (niebieski zamyka okno)
# 1.6.6: linia skrotow (Info/EPG, gora, dol) na cala szerokosc, sciezka logu tylko w statystykach
# 1.6.7: raport dubli w logu (kanaly z najwieksza liczba nakladajacych sie audycji + przyklad)
# 1.6.8: Info/EPG zapisuje wszystkie nakladajace sie pary do duble.txt
# 1.6.9: ponowne pobranie niepelnego/uszkodzonego pliku XMLTV, powtorka skanu z harmonogramu
#        przerwanego restartem, licznik dubli tylko dla przyszlych audycji
# 1.7.0: czyszczenie "tylko przy skanie z harmonogramu", doczytywanie brakow w ciagu dnia,
#        doinstalowanie i kopia list zrodel XMLTV, zrodla nieaktualne sprawdzane raz na dobe,
#        ponowne pobranie tylko uszkodzonego pliku, ukrycie nieobslugiwanego czyszczenia kanalow
# 1.7.1: wieksze zakresy czasow na transponderze, prog doczytywania do 7 dni, opisy wszystkich opcji
# 1.7.2: 2 min przed nagraniem z timera zwalnianie/trzymanie tunerow, dokladniejszy opis limitu
# 1.7.3: zwalnianie dokladnie tylu tunerow, ile brakuje; bez nowych strojen przed nagraniem; opis pominietego doczytywania
# 1.7.4: doczytywanie bez reguly "po polnocy" - odstep miedzy skanami transpondera = "Sprawdzaj co"
# 1.7.5: doczytywanie mierzone wydluzeniem zasiegu, odstep per transponder (x2 bez zysku, maks. 24 h),
#        czas skanu zapisywany per transponder z zapasem 15 min
# 1.7.6: odstep najwyzej 2x podstawowy dla transponderow z niedawnym zyskiem, doczytywanie o stalej godzinie,
#        powod pominiecia w logu, nowy ekran statystyk (kanaly od najkrotszego EPG, cala pamiec, plik epg.dat)
# 1.7.7: statystyka: podsumowanie, z niego zielony = kanaly od najkrotszego EPG, zolty = duble
# 1.7.8: naprawiony restart Enigma2 przy otwieraniu statystyki (konflikt nazwy metody keys)
# 2.2.1: sprzatanie dysku (timeshift, crash logi)
# 2.12.0: serwer dla klientow streamingu (aplikacja E2Stream): API w sieci lokalnej (port 8770) - bukiety, kanaly z rozwiazanymi alternatywami i PID-ami z lamedb, synchronizacja ustawien i danych kanalow odbiornikow (czesc wspolna + plik kazdego odbiornika)
# 2.11.3: kultura zapytan - przerwa miedzy stronami przy pobieraniu na dekoderze (domyslnie 1 s, ustawienie), samoczynne wydluzanie przerwy na serwisie po 429/503 (do 5 s); zastrzezenie o danych z serwisow w opisie; opis po angielsku (README.en.md)
# 2.11.2: listy bukietow jednakowe w calej wtyczce (ten sam uklad okna); SatWatch bez grup alternatyw na liscie, bukiety radiowe z dopiskiem (radio)
# 2.11.1: jednolity wybor bukietow "Kanaly z bukietow (OK - lista)" w Satelita - EPG, Programie z internetu i SatWatch (bez zoltego w Satelicie); sprzatanie dysku - kilka katalogow z listy (nosniki, timeshift i crash logi z Enigma2, wlasne)
# 2.11.0: SatWatch 0.4.0 - pozycje, bukiety i adres satellites.xml wybierane z listy; lista SatWatch: dlugie akcje w tle z postepem odswiezanym na zywo, krotkie z komunikatem, przywracanie kopii bez okna posredniego, bez ustawien (sa w Ustawieniach); kolejnosc grup ustawien (SatWatch pierwszy); opisy sprzatania i restartu bez odniesien do crona
# 2.10.17: naprawiony crash przy pelnym skanie / zrzucie z listy SatWatch (pytanie otwierane po pokazaniu okna); okno SatWatch tylko podglad postepu i wyniku - bez kolorowych klawiszy i MENU
# 2.10.16: opis pod kazda opcja ustawien SatWatch; ustawienia EPG Scan - lista bez polowy wiersza, kreska nad opisem; opis pelnego skanu SatWatch w harmonogramie; README 13 - wartosci domyslne i tabela SatWatch
# 2.10.15: Kanaly -> jedna pozycja SatWatch z lista wszystkich akcji (raporty i log w Raportach); listy raportow SatWatch i kanalow do decyzji bez numeracji
# 2.10.14: MENU znowu bezposrednio Kanaly (niebieski = Ustawienia, w trakcie pracy Exit)
# 2.10.13: niebieski znowu = Ustawienia (w trakcie pracy Exit); MENU = menu: Ustawienia (tylko gdy nic nie pracuje) i Kanaly (tez w trakcie skanu)
# 2.10.12: ekran glowny - MENU = Ustawienia, niebieski = Kanaly (SatWatch, dopasowanie); README 8.4 - test z B polaczonym z A
# 2.10.11: ustawienia SatWatch w EPG Scan -> Ustawienia; niebieski w SatWatch = akcje (MENU); log pokazuje rzeczywista kolejnosc prob tunerow (preferowany pierwszy)
# 2.10.10: porada konfiguracji tunerow wg wykrytego ukladu (Raporty, SatWatch -> MENU, podpowiedzi w ustawieniach), README 8.4 z przykladem VU+ Uno 4K SE (FBC, dwa wejscia)
# 2.10.9: SatWatch 0.3.2 - preferowany tuner do skanu (lista z konfiguracji dekodera; zajety - od razu inny wolny), postep skanu TP (procent, czas do konca) w SatWatch i na ekranie glownym EPG Scan
# 2.10.8: SatWatch 0.3.1 - kandydaci z tego samego TP / nadawcy, gdy nazwa i SID nie pasuja (tylko do recznego wyboru); przy wylaczonym usuwaniu nieaktualne serwisy w raporcie, a kanaly z bukietow na nich - do podmiany / decyzji; opis klawisza MENU
# 2.10.7: Exit cofa o jedno okno wszedzie: Raporty, Kanaly, historia raportow SatWatch, kanaly do decyzji, Menu -> Pobieranie programu, okno roznic -> menu synchronizacji, listy zrodel z adresu, kopie w SatWatch
# 2.10.6: naprawiony crash przy zamykaniu okna roznic dekoder / serwer (atrybut items zaslanial metode ekranu)
# 2.10.5: listy wyboru (menu, filtry, synchronizacja, sortowanie) bez okienka z obrazem - pelna szerokosc; obraz zostaje w oknie wyboru stacji, podgladzie programu i oknie SatWatch
# 2.10.4: wlasne okno roznic dekoder / serwer (cala szerokosc, oba zrodla pod lista; zielony/zolty - strona dla kanalu)
# 2.10.3: Raporty (Info/EPG) - opis wtyczki (README) i opis serwera epgpl (README z serwera 0.19.4+)
# 2.10.2: synchronizacja - wygrywa automat: druga strona traci reczne wskazanie (bez przypinania); Info/EPG zawsze z menu
# 2.10.1: roznice dekoder/serwer obejmuja tez wskazania kanalow spoza wspolnej mapy (epgpl 0.19.3)
# 2.10.0: codzienny program kandydatow - opcja, domyslnie wylaczona (dekoder i serwer); Menu w oknie wyboru: program zaznaczonej stacji albo wszystkich kandydatow
# 2.9.3: Akcje -> Pobierz swieze dane: nie / w jednym miejscu / na nowo - takze start reczny korzysta z paczki drugiej strony
# 2.9.2: paczka dekodera zastepuje starsza paczke dekodera na serwerze; tytul menu pobierania na dekoderze
# 2.9.1: dopasowanie kanalow - Menu: sortowanie i pobieranie programu (wrocilo menu pobierania); propozycje i roznice w jednej linii
# 2.9.0: pobieranie w jednym miejscu (paczka serwera albo dekodera, kto pierwszy); synchronizacja wskazan po dodaniu serwera; program kandydatow pobrany na zadanie -> serwer; okno wyboru przy mapie dekodera widzi stacje serwera
# 2.8.1: okno wyboru stacji - Menu: pobierz program kandydatow, zielony: automat, zolty: bez programu
# 2.8.0: dopasowanie kanalow - filtry (zolty: lista z liczbami; serwis, rozni sie od drugiej mapy, propozycje zmian); propozycje zmian recznych wskazan ([!])
# 2.7.0: roznice dopasowania dekoder <-> serwer: licznik, lista, synchronizacja (Info/EPG) i ustawienie "Roznice w dopasowaniu"; licencja GPL-2.0-or-later, THIRD_PARTY.md
# 2.6.1: obecne zrodlo zawsze na gorze listy; kandydaci "wspolne slowo" tylko przy wspolnym slowie nazwy (bez przypadkowych zbieznosci liter)
# 2.6.0: program kandydatow: nocą na dobe (po pobraniu na dekoderze / serwer epgpl 0.16.0) i na zadanie (Menu w oknie wyboru stacji); podglad najpierw z plikow
# 2.5.2: okno wyboru stacji z wiekszym obrazem; biezaca audycja WP (brak czasu konca); podglad pamietany 3 h
# 2.5.1: wiekszy obraz w podgladzie programu; Menu w oknie wyboru stacji - tylko usuniecie recznego wskazania
# 2.5.0: nowe okno wyboru stacji (dekoder i serwer): kanal na zywo, najlepsi kandydaci i zgodni jednym slowem, statystyki stacji (opisy, dni, biezaca audycja), podglad -2h..+4h; lista: Menu - sortowanie, Info/EPG - synchronizacja
# 2.4.0: wspolne pliki z serwerem (/etc/enigma2/epgscan/wskazania.txt, aliases.txt, aliases.dist); kolejnosc serwisow ustawiana na dekoderze i wysylana na serwer
# 2.3.10: dopasowanie kanalow serwera - Lista bukietow (wybor wysylany na serwer epgpl 0.13.3+)
# 2.3.9: menu ustawien - w 4. grupie widac restart i sprzatanie dysku
# 2.3.8: jeden serwer epgpl - nowy zastepuje poprzedni (z pytaniem); serwer spoza sieci lokalnej: data pliku z naglowka
# 2.3.7: ekran glowny przy samym imporcie XMLTV: bez mylacego 'Skan z satelity: import XMLTV'
# 2.3.6: zrodla XMLTV: dekoder i serwer epgpl bez pola wyboru (sterowane z Akcji), z data danych; dekoder zawsze na liscie
# 2.3.5: Zatrzymaj przerywa tez pobieranie programu na dekoderze; nowy start po przerwanym pobieraniu rusza od nowa
# 2.3.4: naprawiony crash przy pierwszym otwarciu dopasowania (wybor bukietow otwierany po pokazaniu ekranu)
# 2.3.3: okna skalowane do rozdzielczosci skorki (FHD/HD); filtr 'tylko reczne' bez wylaczonych
# 2.3.2: bukiety dla programu z internetu i mapy kanalow: wszystkie / wybrane / do skanowania (niebieski w dopasowaniu, wymagany przy pierwszym otwarciu)
# 2.3.1: szukanie serwera epgpl w sieci (Avahi + podsiec); dopasowanie kanalow: wiecej propozycji (slowa nazwy), przelaczanie na kanal i okienko podgladu
# 2.3.0: wersja publiczna - domyslnie: sprzatanie dysku wylaczone, bez restartu profilaktycznego, mapa kanalow z dekodera; README.md
# 2.2.11: Raporty: SatWatch - ostatni raport, historia (30), log
# 2.2.10: skroty wysrodkowane w ramkach; ekran decyzji z powrotem 1200x660 (wiekszy podglad zostaje)
# 2.2.9: SatWatch - ekran decyzji: duze okno podgladu, dane dawnego kanalu w kilku liniach, SID na liscie kandydatow
# 2.2.8: skroty OK/Menu/Info jako klawisze w ramkach; w menu Kanaly SatWatch na gorze
# 2.2.7: SatWatch - okienko podgladu kanalu w ekranie decyzji (po wyjsciu wraca poprzedni kanal); podsumowanie liczy podmiany sprzed skanu
# 2.2.6: SatWatch - ekran decyzji: krotka lista kandydatow, szczegoly pod lista (SID zgodny/inny, TS, nadawca, nazwa, typ, dubel)
# 2.2.5: SatWatch - ekran kanalow do decyzji: wybor kandydata (z podgladem), usun z bukietow, zostaw
# 2.2.4: SatWatch - brak satellites.xml/pozycji: odtworzenie z pliku zdalnego bez skanu; brak tunera na pozycji: od razu koniec, bez ponowien
# 2.2.3: wykrywanie zawieszonego tunera (3 nieudane strojenia z rzedu): stan tunerow w logu, w nocy restart Enigma2 i powtorka skanu, recznie pytanie; 10 s przerwy po skanie SatWatch
# 2.2.2: SatWatch w przebiegu - bez zmian od razu skan EPG; ponowienia nieudanych TP w tle, gdy tunery wolne
# 2.2.0: SatWatch w EPG Scan (krok przed skanem EPG, dogrywka po zmianach w tle), restart tylko gdy potrzebny, podsumowanie nocy po wlaczeniu odbiornika
# 2.1.2: menu Kanaly - SatWatch (otwarcie wtyczki, ostatni raport), gdy zainstalowany
# 2.1.1: brak pliku (lub ponad 30 h) na dekoderze / serwerze = pobieranie mimo "Pobierz swieze dane: Nie"; log stanu pobierania przed importem
# 2.1.0: mapa kanalow budowana na dekoderze (localmap.py), dopasowanie kanalow: dekoder, przeniesienie wskazan z serwera; tryb serwera tylko w Akcjach
# 2.0.1: poprawka crasha okien tekstowych (raporty, log); serwer "zapas" nigdy nie pobiera; Info: kanaly, duble, podsumowanie pobierania na dekoderze
# 2.0.0: nowy uklad - ekran glowny (stan etapow, OK = profil, zielony Akcje, zolty Kopie, Menu Kanaly, Info Raporty), ustawienia w dzialach, profil zrodel (satelita, dekoder, serwer, pozostale, kolejnosc), wylacznik harmonogramu
# 1.15.5: pobieranie na dekoderze - 404 = brak dnia w serwisie (nie blad, bez ponawiania, dalsze dni stacji pomijane)
# 1.15.4: pobieranie na dekoderze - polaczenia osobno na serwis i limit laczny, zapis bledow (lokalne/bledy.txt), Retry-After
# 1.15.3: pobieranie na dekoderze - ponowienie stron z bledem (429) na koncu, jedno polaczenie, dluzsze przerwy
# 1.15.2: ustawienia - dni i polaczenia dla pobierania na dekoderze zawsze widoczne (dzialaja tez dla recznego pobrania i testu)
# 1.15.1: pobieranie na dekoderze - serwisy rownolegle, osobna pula polaczen na serwis
# 1.15.0: pobieranie na dekoderze - teleman, onet, naziemna, WP; zrodlo "EPG PL (dekoder)", pobieranie przed importem XMLTV
# 1.14.0: pobieranie na dekoderze - etap 1: test teleman.pl (localgrab.py) z porownaniem z serwerem
# 1.13.2: ekran glowny - postep pobierania na serwerze uruchomionego z dekodera, blokada startu, przerwanie czekania
# 1.13.1: XMLTV - audycja zastepcza "Brak programu do HH:MM" (od teraz do pierwszej audycji, opcjonalnie w przerwach) - Multi EPG
# 1.13.0: "Import EPG z satelity" (wylaczony = sam XMLTV, wymuszanie wylaczenia EIT; wlaczony = wymuszanie wlaczenia EIT); gotowy plik z serwera - 4 warianty importu
# 1.12.4: XMLTV - audycja trwajaca na koncu danych z satelity wchodzi od tego miejsca (bez dziury na styku)
# 1.12.3: XMLTV - "Kanaly z bukietow": domyslnie wszystkie bukiety (takze IPTV niewybrane do skanu)
# 1.12.2: sam import XMLTV - ekran pokazuje wynik importu zamiast listy z poprzedniego skanu; wymuszenie tylko dla zrodel epgpl
# 1.12.1: pelny skan po doczytywaniu skanowal tylko transpondery z doczytywania - lista budowana od nowa
# 1.12.0: XMLTV - "Serwer epgpl przy harmonogramie": pobieranie na serwerze w trakcie skanu, import po jego koncu; zgloszenie harmonogramu do serwera
# 1.11.1: dopasowanie kanalow - zolty: wszystkie / bez dopasowania / bez programu / tylko reczne (opis filtrow)
# 1.11.0: dopasowanie kanalow - podglad programu stacji przed zapisem; niebieski: pobieranie na serwerze z opcjonalnym importem XMLTV / skanem
# 1.10.2: ustawienia - zolty zawsze "Bukiety / XMLTV": wybor bukietow, zrodel XMLTV lub dopasowania kanalow
# 1.10.1: statystyka (Info/EPG) - niebieski: raport z serwera wlasnego zrodla XMLTV (wybor przy kilku)
# 1.10.0: okno "Dopasowanie kanalow" dla serwera epgpl (przeglad przydzialu, reczne wskazanie stacji)
# 1.9.7: XMLTV takze dla kopii kanalow spoza bukietow (opcja, domyslnie wlaczona)
# 1.9.6: klawisz Exit w trakcie skanu w kolorze niebieskim (spojnosc z pozostalymi)
# 1.9.5: import XMLTV przycina nakladajace sie audycje (ostatnia audycja dnia +1 h w zrodlach) - bez dubli
# 1.9.4: krotszy napis na szarym klawiszu Exit w trakcie skanu
# 1.9.3: tresc okien czcionka 30, opisy/pomoc/klawisze 28
# 1.9.2: wieksza czcionka samej listy opcji w ustawieniach (font/secondfont/itemHeight)
# 1.9.1: czcionki 28-30 wg uwag uzytkownika
# 1.9.0: wieksze okna i czcionki we wszystkich ekranach (czytelnosc), poprawiony uklad okna zrodel
# 1.8.8: podglad raportu zrodla (Info/EPG w oknie zrodel), adres raportu z <report> w liscie zrodel
# 1.8.7: zrodla z pakietu pogrupowane jak w EPG Import (OK rozwija grupe), licznik zaznaczonych w grupie
# 1.8.6: naglowki blokow w oknie zrodel rysowane poprawnie od razu po otwarciu
# 1.8.5: wlasne listy zrodel na poczatku (i z pierwszenstwem przy imporcie); okno zrodel podzielone na bloki
# 1.8.4: historia adresow list (zostaje po usunieciu listy), wybor z historii przy dodawaniu, usuwanie zapamietanych adresow
# 1.8.3: wlasne listy pamietaja adres i aktualizuja sie przed kazdym importem; okno zrodel: opis listy, menu adresu, poprawiony uklad
# 1.8.2: dwie kopie list zrodel (pakiet epgimport-sources / wlasne); usuwanie wlasnych i pobieranie listy z adresu w oknie zrodel
# 1.8.1: kopia list zrodel XMLTV laczona z /etc/epgimport - brakujace listy (np. po odinstalowaniu pakietu) brane z kopii
# 1.8.0: wylaczone EIT jest wlaczane na czas skanu (albo na stale - opcja); ostrzezenie na ekranie
# 1.7.9: czytelniejszy log: ile zdarzen bylo przed strojeniem (z innych transponderow), a ile dolozylo strojenie

from Plugins.Plugin import PluginDescriptor
from Screens.MessageBox import MessageBox
from enigma import eTimer

from .common import cfg, log, list_backups, restore_backup, count_events, all_refs, backup_label, RESTORE_RATIO, \
	epg_stats, stats_text

_auto = None
_stats = None


class StartupStats(object):
	"""Diagnostyka: statystyka EPG w logu kilka razy po starcie Enigma2."""
	TIMES = (3, 6, 10, 15)   # minuty po starcie

	def __init__(self):
		self.i = 0
		self.t0 = __import__("time").time()
		self.timer = eTimer()
		self.timer.callback.append(self.run)
		self.timer.start(self.TIMES[0] * 60 * 1000, True)

	def run(self):
		try:
			import time
			m = int((time.time() - self.t0) / 60)
			log("Statystyka EPG %d min po starcie (bukiety): %s" % (m, stats_text(epg_stats(all_refs()))))
		except Exception as e:
			log("Statystyka EPG nieudana: %s" % e)
		self.i += 1
		if self.i < len(self.TIMES):
			self.timer.start((self.TIMES[self.i] - self.TIMES[self.i - 1]) * 60 * 1000, True)


class AutoRestore(object):
	"""Po starcie Enigma2 sprawdza, czy EPG nie jest puste, i ewentualnie odtwarza kopie.
	Dwa pomiary w odstepie minuty - odtwarza tylko, gdy oba ponizej progu i EPG nie rosnie."""

	def __init__(self, session):
		self.session = session
		self.first = None
		self.timer = eTimer()
		self.timer.callback.append(self.check)
		self.timer.start(150 * 1000, True)

	def check(self):
		if self.first is None:
			try:
				log("Statystyka EPG po starcie (bukiety): " + stats_text(epg_stats(all_refs())))
			except Exception:
				pass
		if cfg.restore.value == "off" or cfg.last_events.value <= 0 or not list_backups():
			return
		now, _ = count_events(all_refs())
		need = int(cfg.last_events.value * RESTORE_RATIO)
		if self.first is None:
			log("Kontrola EPG po starcie: %d zdarzen (ostatni skan %d, prog %d)" % (now, cfg.last_events.value, need))
			if now >= need:
				return
			self.first = now
			self.timer.start(60 * 1000, True)
			return
		log("Kontrola EPG po starcie (2. pomiar): %d zdarzen" % now)
		if now >= need or now > self.first * 1.2 + 100:
			log("EPG wczytuje sie lub jest wystarczajace - bez odtwarzania")
			return
		self.path = list_backups()[0]
		if cfg.restore.value == "auto":
			restore_backup(self.path)
		else:
			self.session.openWithCallback(self.answer, MessageBox,
				"EPG Scan: EPG wyglada na puste (%d zdarzen, zwykle ok. %d).\nOdtworzyc z kopii %s?" % (
					now, cfg.last_events.value, backup_label(self.path)),
				MessageBox.TYPE_YESNO, timeout=60, default=True)

	def answer(self, yes):
		if yes:
			restore_backup(self.path)


def sessionstart(reason, session=None, **kwargs):
	global _auto, _stats
	if reason == 0 and session is not None and _auto is None:
		_auto = AutoRestore(session)
		_stats = StartupStats()
		try:
			from .common import apply_clear_choices, apply_eit_policy, migrate_v2
			apply_clear_choices()
			migrate_v2()
			from .localmap import migrate_files
			migrate_files()
			apply_eit_policy()
		except Exception:
			pass
		from .engine import get_engine
		eng = get_engine(session)
		try:
			from .satwatch.sw import init as sw_init
			sw_init(session)
			eng.hook_satwatch()
		except Exception as e:
			from .common import log
			log("SatWatch: start nieudany: %s" % e)
		try:
			from .health import start as health_start
			health_start(session)
		except Exception:
			pass
		try:
			from .cleanup import start as cleanup_start
			cleanup_start()
		except Exception:
			pass
		try:
			from .clients import start as clients_start
			clients_start()
		except Exception as e:
			from .common import log
			log("Serwer dla klientow: %s" % e)
		try:
			from .updater import start as updater_start
			updater_start(session)
		except Exception as e:
			from .common import log
			log("Aktualizacja: %s" % e)
		from .schedule import start_scheduler
		start_scheduler(session)


def wakeup(**kwargs):
	from .schedule import wakeup_time
	return wakeup_time()


def main(session, **kwargs):
	from .scan import EPGScanScreen
	session.open(EPGScanScreen)


def Plugins(**kwargs):
	return [
		PluginDescriptor(
			name="EPG Scan",
			description="Rownolegle odswiezanie EPG z satelity",
			where=[PluginDescriptor.WHERE_PLUGINMENU, PluginDescriptor.WHERE_EXTENSIONSMENU],
			fnc=main),
		PluginDescriptor(where=PluginDescriptor.WHERE_SESSIONSTART, fnc=sessionstart, wakeupfnc=wakeup),
	]
