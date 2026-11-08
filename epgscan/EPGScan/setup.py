# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - ustawienia i wybor bukietow

import os
import time

from Screens.Screen import Screen
from Screens.MessageBox import MessageBox
from Components.ActionMap import ActionMap
from Components.Label import Label
from Components.ConfigList import ConfigListScreen
from Components.config import getConfigListEntry, NoSave, ConfigSelection
from Components.SelectionList import SelectionList, SelectionEntryComponent
from enigma import eTimer, eEPGCache

from .common import S, cfg, log, VERSION, profile_default, profile_text, log_path, log_count, save_cfg, list_bouquets, selected_bouquets, epg_path, copy_backup, list_backups, \
	SCHED_DAYS, DAYS, next_run, fmt_ts, keys_skin, init_keys, set_keys, apply_clear_choices, single_flush_supported


def all_items():
	return [cfg.scope, cfg.max_parallel, cfg.reserve, cfg.min_time, cfg.idle_time, cfg.max_time, cfg.clear_mode,
		cfg.skip_empty, cfg.skip_recheck, cfg.eit_mode, cfg.sat_import, cfg.eit_force_off, cfg.sched_mode, cfg.sched_time, cfg.sched_interval, cfg.sched_when,
		cfg.sched_skip_rec, cfg.sched_wakeup, cfg.sched_deep_after, cfg.notify, cfg.backup, cfg.backup_dir,
		cfg.backup_keep, cfg.restore, cfg.log_place, cfg.xmltv, cfg.xmltv_mode, cfg.xmltv_copies, cfg.epgpl_grab, cfg.xmltv_bouquets, cfg.xmltv_gaps,
		cfg.local_grab, cfg.local_days, cfg.local_conn_tm, cfg.local_conn_onet, cfg.local_conn_naz, cfg.local_conn_wp,
		cfg.local_conn_max, cfg.local_pause, cfg.local_errlog, cfg.src_order, cfg.site_order, cfg.map_diff, cfg.one_place, cfg.cand_night, cfg.sched_on, cfg.map_mode, cfg.rs_mode, cfg.rs_days, cfg.rs_mem,
		cfg.cl_on, cfg.cl_dir, cfg.cl_ts_hours, cfg.cl_crash_keep, cfg.cl_e2s_days, cfg.cl_every, cfg.cli_api, cfg.cli_port, cfg.cli_token, cfg.cli_pack, cfg.upd_auto,
		cfg.topup, cfg.topup_every, cfg.topup_hours, cfg.topup_when, cfg.topup_fixed, cfg.topup_fixed_time] + SCHED_DAYS


ROW_H = 46


def big_list(lst):
	"""Wieksza czcionka i wysokosc wiersza listy z polami wyboru (czytelnosc z kanapy)."""
	try:
		from enigma import gFont
		from .common import S
		lst.l.setFont(0, gFont("Regular", S(30)))
		lst.l.setItemHeight(S(ROW_H))
	except Exception:
		pass


def wide_entry(entry, width=1400):
	"""Pozycja SelectionEntryComponent poszerzona do szerokosci okna i wysrodkowana w wyzszym wierszu."""
	try:
		out = [entry[0]]
		for el in entry[1:]:
			el = list(el)
			if isinstance(el[7] if len(el) > 7 else None, str):        # tekst: (typ, x, y, w, h, font, flags, tekst, ...)
				from enigma import RT_VALIGN_CENTER
				el[2], el[3], el[4] = 0, S(width), S(ROW_H)
				el[6] = el[6] | RT_VALIGN_CENTER
			else:                                                        # ikona pola wyboru: wysrodkuj w pionie
				el[2] = max(0, (S(ROW_H) - el[4]) // 2)
			out.append(tuple(el))
		return out
	except Exception:
		return entry


def is_sw_full(item):
	try:
		from .satwatch.sw import cfg as swcfg
		return item is swcfg.full_enable
	except Exception:
		return False


class EPGScanSetup(Screen, ConfigListScreen):
	skin = """
	<screen position="center,center" size="1500,920" title="EPG Scan - ustawienia">
		<widget name="config" position="20,15" size="1460,552" font="Regular;30" secondfont="Regular;30" itemHeight="46" scrollbarMode="showOnDemand" />
		<eLabel position="20,579" size="1460,2" backgroundColor="#00606060" />
		<widget name="help" position="20,591" size="1460,240" font="Regular;28" foregroundColor="#00b0b0b0" />
		%s
	</screen>""" % keys_skin(20, 845, 1460)

	TITLES = {"sat": "satelita - EPG", "net": "program z internetu", "sched": "harmonogram i doczytywanie",
		"misc": "kopie, log, powiadomienia, restart, sprzatanie"}

	def __init__(self, session, section="sat"):
		Screen.__init__(self, session)
		self.section = section
		self.setTitle("EPG Scan %s - %s" % (VERSION, self.TITLES.get(section, "ustawienia")))
		self["help"] = Label("")
		init_keys(self)
		self.p_pack = NoSave(ConfigSelection(choices=[("v", "OK - przygotuj teraz")]))   # paczka dla E2Stream recznie
		self.p_upd = NoSave(ConfigSelection(choices=[("v", "OK - sprawdz i zainstaluj")]))   # reczna aktualizacja
		self.p_cldirs = NoSave(ConfigSelection(choices=[("v", cl_dirs_text())]))   # katalogi sprzatania - wybor z listy (OK)
		ConfigListScreen.__init__(self, [], session=session, on_change=self.changed)
		self["actions"] = ActionMap(["SetupActions", "ColorActions"], {
			"ok": self.ok,
			"save": self.save,
			"green": self.save,
			"cancel": self.cancel,
			"red": self.cancel,
			"yellow": self.yellow,
		}, -2)
		self.bk_timer = eTimer()
		self.bk_timer.callback.append(self.backup_done)
		self._then = None
		apply_clear_choices()
		self.build()
		try:
			self["config"].onSelectionChanged.append(self.update_help)
		except Exception:
			pass
		self.onLayoutFinish.append(self.update_help)

	def changed(self):
		cur = self["config"].getCurrent()
		if cur and cur[1] in (cfg.backup, cfg.scope, cfg.sched_mode, cfg.sched_wakeup, cfg.skip_empty, cfg.xmltv, cfg.topup,
				cfg.topup_fixed, cfg.sat_import, cfg.rs_mode, cfg.cl_on, cfg.cli_api) or (cur and is_sw_full(cur[1])):
			self.build()
		if cur and cur[1] is cfg.xmltv and cfg.xmltv.value:
			self.check_sources()
		self.update_help()

	# --- listy zrodel XMLTV (pakiet enigma2-plugin-extensions-epgimport-sources) ---
	def check_sources(self, then=None):
		from .xmltv import sources_present
		if sources_present():
			if then:
				then()
			return
		self._then = then
		self.session.openWithCallback(self.install_answer, MessageBox,
			"Brak list zrodel XMLTV (/etc/epgimport).\nZainstalowac pakiet enigma2-plugin-extensions-epgimport-sources?",
			MessageBox.TYPE_YESNO, default=True)

	def install_answer(self, yes):
		if not yes:
			return
		from .xmltv import install_sources
		self["help"].setText("Instalowanie pakietu list zrodel XMLTV (opkg)... to moze potrwac do minuty.")
		install_sources(self.install_done)

	def install_done(self, ok, msg):
		self.session.open(MessageBox, msg, MessageBox.TYPE_INFO if ok else MessageBox.TYPE_ERROR, timeout=8)
		self.update_help()
		if ok and self._then:
			self._then()

	def build(self):
		cur = self["config"].getCurrent() if self["config"].list else None
		sec = self.section
		e = getConfigListEntry
		lst = []
		if sec == "sat":
			lst.append(e("Import EPG z satelity (skan transponderow)", cfg.sat_import))
			if not cfg.sat_import.value:
				lst.append(e("  Wymuszaj wylaczenie EIT", cfg.eit_force_off))
			else:
				lst.append(e("  Wymuszaj wlaczenie EIT", cfg.eit_mode))
			lst += [e("Kanaly z bukietow (OK - lista)", cfg.scope), e("Maks. rownoleglych skanow", cfg.max_parallel),
				e("Rezerwa wolnych tunerow", cfg.reserve), e("Min. czas na transponderze [s]", cfg.min_time),
				e("Koniec po braku nowych zdarzen [s]", cfg.idle_time), e("Maks. czas na transponderze [s]", cfg.max_time),
				e("Pomijaj transpondery bez EPG", cfg.skip_empty)]
			if cfg.skip_empty.value:
				lst.append(e("  Sprawdzaj je ponownie co [dni]", cfg.skip_recheck))
		elif sec == "net":
			lst.append(e("Program z internetu (XMLTV)", cfg.xmltv))
			if cfg.xmltv.value:
				lst += [e("  Kolejnosc zrodel (po satelicie)", cfg.src_order),
					e("  Kolejnosc serwisow (pierwszenstwo stacji)", cfg.site_order),
					e("  Mapa kanalow dla dekodera", cfg.map_mode),
					e("  Roznice w dopasowaniu (dekoder / serwer)", cfg.map_diff),
					e("  Pobieranie w jednym miejscu (kto pierwszy)", cfg.one_place),
					e("  Codziennie program kandydatow (okno wyboru stacji)", cfg.cand_night),
					e("  Co uzupelniac", cfg.xmltv_mode),
					e("  Kanaly z bukietow (OK - lista)", cfg.xmltv_bouquets),
					e("  Takze kopie kanalow spoza bukietow", cfg.xmltv_copies),
					e("  Audycja zastepcza w przerwach", cfg.xmltv_gaps),
					e("Dekoder: dni do przodu", cfg.local_days),
					e("  Polaczen: teleman", cfg.local_conn_tm),
					e("  Polaczen: onet", cfg.local_conn_onet),
					e("  Polaczen: naziemna", cfg.local_conn_naz),
					e("  Polaczen: WP Pilot", cfg.local_conn_wp),
					e("  Maks. polaczen lacznie", cfg.local_conn_max),
					e("  Przerwa miedzy stronami", cfg.local_pause),
					e("  Zapisuj bledy pobierania", cfg.local_errlog)]
		elif sec == "sched":
			lst.append(e("Harmonogram wlaczony", cfg.sched_on))
			lst.append(e("  Tryb", cfg.sched_mode))
			if cfg.sched_mode.value == "daily":
				lst.append(e("  Godzina", cfg.sched_time))
				for i, d in enumerate(SCHED_DAYS):
					lst.append(e("  " + DAYS[i], d))
			elif cfg.sched_mode.value == "interval":
				lst.append(e("  Co ile godzin", cfg.sched_interval))
			if cfg.sched_mode.value != "off":
				lst += [e("  Kiedy uruchamiac", cfg.sched_when), e("  Czekaj, gdy trwa nagrywanie", cfg.sched_skip_rec),
					e("  Wybudzaj z glebokiego czuwania", cfg.sched_wakeup)]
				if cfg.sched_wakeup.value:
					lst.append(e("  Po skanie wroc do glebokiego czuwania", cfg.sched_deep_after))
			try:
				from .satwatch.sw import cfg as swcfg
				lst.append(e("SatWatch: pelny skan raz w tygodniu", swcfg.full_enable))
				if swcfg.full_enable.value:
					lst.append(e("  dzien (w pozostale dni: zmiany)", swcfg.full_day))
			except Exception:
				pass
			if cfg.sat_import.value:
				lst.append(e("Doczytywanie brakow w ciagu dnia", cfg.topup))
				if cfg.topup.value:
					lst += [e("  Sprawdzaj co [h]", cfg.topup_every),
						e("  Doczytaj, gdy EPG konczy sie za mniej niz", cfg.topup_hours),
						e("  Kiedy", cfg.topup_when), e("  Dodatkowo raz dziennie o stalej godzinie", cfg.topup_fixed)]
					if cfg.topup_fixed.value:
						lst.append(e("    Godzina", cfg.topup_fixed_time))
		else:
			lst += [e("Powiadomienie po pracy w tle", cfg.notify), e("Kopia EPG po skanie", cfg.backup)]
			if cfg.backup.value:
				lst += [e("Katalog kopii (OK = wybierz)", cfg.backup_dir), e("Liczba zachowanych kopii", cfg.backup_keep)]
			lst += [e("Odtwarzanie EPG po starcie, gdy puste", cfg.restore), e("Zapisuj log", cfg.log_place),
				e("Restart po nocnym przebiegu", cfg.rs_mode)]
			if cfg.rs_mode.value == "need":
				lst += [e("  Profilaktycznie co [dni]", cfg.rs_days), e("  Gdy wolnej pamieci mniej niz [MB]", cfg.rs_mem)]
			lst += [e("Aktualizacja automatyczna", cfg.upd_auto), e("Aktualizuj teraz (wersja %s)" % VERSION, self.p_upd)]
			lst.append(e("Serwer dla aplikacji E2Stream (klienci streamingu)", cfg.cli_api))
			if cfg.cli_api.value:
				lst += [e("  Port", cfg.cli_port), e("  Token (puste = bez, siec lokalna)", cfg.cli_token),
					e("  Dane dla aplikacji po harmonogramie (EPG, ikony, referencje)", cfg.cli_pack),
					e("  Przygotuj dane teraz", self.p_pack)]
			lst.append(e("Sprzatanie dysku (timeshift, crash logi)", cfg.cl_on))
			if cfg.cl_on.value:
				lst += [e("  Katalogi (OK - lista)", self.p_cldirs), e("  Timeshift starszy niz [h]", cfg.cl_ts_hours),
					e("  Zostaw crash logow", cfg.cl_crash_keep), e("  Zrzuty TS z E2Stream starsze niz [dni]", cfg.cl_e2s_days),
					e("  Co ile minut", cfg.cl_every)]
		self["config"].list = lst
		self["config"].l.setList(lst)
		if cur is not None:
			for i, x in enumerate(lst):
				if x[1] is cur[1]:
					self["config"].setCurrentIndex(i)
					break

	def is_sw_full(self, item):
		try:
			from .satwatch.sw import cfg as swcfg
			return item is swcfg.full_enable or item is swcfg.full_day
		except Exception:
			return False

	def update_help(self):
		cur = self["config"].getCurrent()
		item = cur[1] if cur else None
		nb = len(selected_bouquets())
		allb = len(list_bouquets())
		txt = "Bukiety: %s.  Plik EPG: %s" % ("wszystkie (%d)" % allb if cfg.scope.value == "all" else "%d z %d" % (nb, allb), epg_path())
		if item in (cfg.upd_auto, self.p_upd):
			txt = "Nowe wersje wtyczki pobierane z wydan GitHub (JarDol/enigma2-epgscan). Automatycznie: raz na dobe sprawdza, " \
				"instaluje, a Enigma2 restartuje dopiero w czuwaniu, gdy nic nie nagrywa i nie trwa skan. " \
				"'Aktualizuj teraz' (OK) dziala zawsze - takze przy wylaczonej automatycznej (pyta o restart)."
			if cfg.upd_pending.value and cfg.upd_pending.value != VERSION:
				txt += " Zainstalowana %s czeka na restart Enigma2." % cfg.upd_pending.value
		elif item in (cfg.cli_pack, self.p_pack):
			from .clientpack import last_text, pack_dir
			txt = "Ostatni krok pracy z harmonogramu (po skanie satelity i imporcie EPG): paczka dla E2Stream - EPG kanalow " \
				"ze wszystkich bukietow TV, ikony kanalow i zamiany referencji (np. po zmianach SatWatch). Aplikacja pobiera ja " \
				"przy pierwszym uruchomieniu w danym dniu i gdy pojawi sie nowsza. OK na 'Przygotuj dane teraz' - od razu. " \
				"Katalog: %s. Ostatnio: %s" % (pack_dir(), last_text())
		elif item in (cfg.cli_api, cfg.cli_port, cfg.cli_token):
			try:
				from .clients import summary
				st = summary()
			except Exception:
				st = "?"
			txt = "Aplikacja E2Stream (Android TV, tablet, telefon) pobiera z dekodera bukiety, kanaly z PID-ami i zapisuje " \
				"tu swoje ustawienia i dane kanalow (czesc wspolna dla nowych odbiornikow + plik kazdego odbiornika). " \
				"Dziala w sieci lokalnej; token - opcjonalne zabezpieczenie (ten sam wpisz w aplikacji). Stan: %s" % st
		elif item is cfg.scope:
			txt = "Ktore kanaly skanowac z satelity. OK - lista bukietow do zaznaczenia (zapis ustawia 'wybrane bukiety'); " \
				"strzalki - tryb. Wszystkie bukiety: takze bukiety dodane w przyszlosci. Wybrane: tylko zaznaczone. " \
				"Wszystkie transpondery: cala lista kanalow (lamedb) - skan kilka razy dluzszy, wiekszy plik EPG " \
				"i zuzycie pamieci. Nowe kanaly sa wykrywane zawsze same."
		elif item in (cfg.sched_mode, cfg.sched_time, cfg.sched_interval, cfg.sched_when) or item in SCHED_DAYS:
			txt = "Nastepny skan z harmonogramu: %s. Tylko w czuwaniu: gdy o tej porze odbiornik jest uzywany, " \
				"skan ruszy po przejsciu w czuwanie (do 12 h)." % fmt_ts(next_run())
		elif item in (cfg.sched_wakeup, cfg.sched_deep_after):
			txt = "Odbiornik wstanie z glebokiego czuwania ok. 2 min przed skanem. Po skanie wroci do glebokiego " \
				"czuwania, jesli nikt go nie uzywa i nie ma nagrania w ciagu 15 min."
		elif item in (cfg.skip_empty, cfg.skip_recheck):
			txt = "Transponder, ktory w 2 kolejnych skanach nie dal zadnego EPG, jest pomijany i sprawdzany " \
				"ponownie po podanej liczbie dni."
		elif item is cfg.sat_import:
			txt = "Domyslnie w profilu (OK, harmonogram; zmiana takze w Akcjach). Satelita jest zawsze pierwszym zrodlem - " \
				"EIT ma w Enigma2 pierwszenstwo przed importem, XMLTV uzupelnia tylko braki i dalsze dni. Wylaczone: " \
				"sam import XMLTV, bez strojenia tunerow i doczytywania; przy \"Wymuszaj wylaczenie EIT\" = Tak EIT jest " \
				"wylaczane, wiec EPG pochodzi tylko z XMLTV."
			if not cfg.sat_import.value and not cfg.xmltv.value:
				txt += " UWAGA: program z internetu jest wylaczony - nic nie bedzie importowane."
		elif item is cfg.eit_force_off:
			from .common import eit_on
			st = eit_on()
			txt = "Tak: EIT w ustawieniach EPG Enigma2 jest wylaczane na stale - EPG pochodzi tylko z XMLTV, ogladany " \
				"transponder go nie nadpisuje. Nie: ustawienie EIT zostaje jak w Enigma2. Teraz EIT jest %s." % (
				"wlaczone" if st else ("wylaczone" if st is False else "niedostepne w tym obrazie"))
		elif item is cfg.eit_mode:
			from .common import eit_on
			st = eit_on()
			txt = "Skan potrzebuje EPG z satelity (EIT). Gdy w ustawieniach EPG Enigma2 jest ono wylaczone: " \
				"'na czas skanu' - wlacza je tylko na czas skanu i doczytywania, potem wylacza; 'na stale' - " \
				"wlacza i zapisuje w ustawieniach. Teraz EIT jest %s." % (
				"wlaczone" if st else ("wylaczone" if st is False else "niedostepne w tym obrazie"))
		elif item is cfg.xmltv_copies:
			txt = "Ta sama stacja czesto nadaje na satelicie w kilku kopiach (np. wersja Polsat Box i Canal+). " \
				"Wlaczone: program z XMLTV trafia do wszystkich kopii z listy kanalow odbiornika, nie tylko do tych " \
				"z bukietow. Kopia bez EPG z satelity dostaje caly program z XMLTV."
		elif item is cfg.local_grab:
			txt = "Tak: przy kazdym skanie / imporcie XMLTV dekoder sam pobiera program z teleman, onet, naziemna i WP " \
				"(ok. 5-10 min, rownolegle ze skanem satelity), zapisuje go jako zrodlo \"EPG PL (dekoder)\" i dopiero " \
				"potem importuje XMLTV. Zaznacz to zrodlo w Zrodlach XMLTV (przed EPG PL z serwera, ktore zostaje zapasem). " \
				"Mapa kanalow - z serwera epgpl, z kopia na dysku."
		elif item is cfg.local_days:
			txt = "Ile dni do przodu (plus dzien poprzedni - trwajaca noc)."
		elif item in (cfg.local_conn_tm, cfg.local_conn_onet, cfg.local_conn_naz, cfg.local_conn_wp, cfg.local_conn_max):
			txt = "Ile stron naraz z danego serwisu (serwisy pobierane rownolegle) i ile lacznie ze wszystkich. Wiecej = " \
				"szybciej, ale wieksze ryzyko blokady (onet odpowiada wtedy 429 - strony sa ponawiane na koncu, wolniej)."
		elif item is cfg.local_pause:
			txt = "Przerwa po kazdej stronie na kazdym polaczeniu - zeby nie obciazac serwisow. Gdy serwis odpowiada 429/503 " \
				"(ogranicza zapytania), pobieranie samo wydluza przerwe (do 5 s), a nieudane strony ponawia na koncu, wolniej. " \
				"1 s: pelne pobieranie kilka - kilkanascie minut. 0,3 s szybciej, ale wieksze ryzyko blokady."
		elif item is cfg.local_errlog:
			from .common import backup_dir
			txt = "Kazda nieudana proba pobrania strony (kod, Retry-After, adres, poczatek odpowiedzi) trafia do %slokalne/bledy.txt " \
				"(plik od nowa przy kazdym pobraniu)." % backup_dir()
		elif item is cfg.xmltv_gaps:
			txt = "Multi EPG (Teraz/Nastepny/Wiecej) zaczyna od audycji trwajacej teraz - kanal bez niej jest pusty, " \
				"choc ma program na pozniej (np. w nocy, ktorej serwis nie podaje). Od teraz do pierwszej audycji: " \
				"kanal bez EPG z satelity dostaje audycje \"Brak programu do HH:MM\". Takze przerwy: to samo dla " \
				"przerw od 30 min w srodku programu (np. noce kanalow sportowych)."
		elif item is cfg.xmltv_bouquets:
			from .common import xmltv_bouquet_files
			txt = "Ktore bukiety dostaja program z internetu i sa dopasowywane w mapie kanalow dekodera. Wszystkie: " \
				"takze bukiety, ktorych nie skanujesz z satelity (np. IPTV). Wybrane: tylko zaznaczone - OK otwiera liste " \
				"(zapis ustawia 'wybrane bukiety'), strzalki - tryb " \
				"(teraz %d bukietow). Do skanowania: jak w Satelita - EPG -> Kanaly z bukietow." % len(xmltv_bouquet_files())
		elif item is cfg.epgpl_grab:
			txt = "Dotyczy wybranych zrodel z serwera epgpl i skanu z harmonogramu. Gotowy plik: import tego, co serwer " \
				"pobral sam. Pobieranie na serwerze: na starcie skanu dekoder uruchamia pobieranie na serwerze (ok. 10 min, " \
				"rownolegle ze skanem satelity), XMLTV jest importowane po jego zakonczeniu. Dekoder zglasza serwerowi swoj " \
				"harmonogram - serwer pomija wtedy wlasne pobieranie w oknie +-3 h (TUNER_WINDOW, epgpl 0.10.0+)."
		elif item is cfg.xmltv:
			txt = "Program z internetu: dekoder (pobiera sam), serwer epgpl, pozostale zaznaczone zrodla XMLTV (zolty). " \
				"Ktore z nich biora udzial i czy pobierac swieze dane - w profilu (zielony na ekranie glownym -> Akcje)."
		elif item is cfg.src_order:
			txt = "Kolejnosc zrodel XMLTV po satelicie: kanal bierze program z pierwszego, ktore go ma. Satelita zawsze " \
				"pierwszy (EIT ma pierwszenstwo przed importem). Teraz: %s" % profile_text(profile_default())
		elif item is cfg.site_order:
			txt = "Gdy stacja jest w kilku serwisach, kanal bierze ja z pierwszego. Dekoder jest centrum ustawien: ta sama " \
				"kolejnosc trafia na serwer epgpl (razem z wyborem bukietow) i zastepuje tam SITES z config.env. " \
				"Teleman ma najlepsze opisy, naziemna - regionalne TVP3."
		elif item is cfg.cand_night:
			txt = "Po kazdym pobraniu programu - program na dobe dla wszystkich stacji, ktore okno wyboru proponuje " \
				"kanalom z bukietow (ok. 1000 stacji, kilka-kilkanascie minut, dodatkowe zapytania do serwisow). Tylko do " \
				"podgladu w oknie wyboru, nie trafia do EPG. Bez tego: Menu w oknie wyboru stacji - program zaznaczonej " \
				"stacji albo wszystkich kandydatow kanalu, na zadanie. Ustawienie obowiazuje tez na serwerze epgpl."
		elif item is cfg.one_place:
			txt = "Harmonogram: program pobiera tylko jedno miejsce. Gdy serwer epgpl pobral dzis pelny program, dekoder " \
				"bierze jego gotowa paczke zamiast pobierac sam; gdy pierwszy byl dekoder - wysyla paczke na serwer, a serwer " \
				"pomija swoje pobieranie. Paczka: program, mapa referencji i program kandydatow. Start profilu (harmonogram, " \
				"OK, Akcje): wg \"Pobierz swieze dane\" - w jednym miejscu albo na nowo; \"Dekoder: pobierz program teraz\" " \
				"zawsze pobiera. Wymaga epgpl 0.18.0."
		elif item is cfg.map_diff:
			txt = "Gdy dopasowanie kanalow na dekoderze i na serwerze epgpl sie rozni (inna stacja albo reczne wskazanie " \
				"tylko po jednej stronie). Wygrywa dekoder / serwer: przy otwarciu dopasowania druga strona dostaje te same " \
				"wskazania. Wybor za kazdym razem: pytanie przy otwarciu. Pozostaw rozne: tylko licznik roznic. " \
				"Recznie zawsze: Dopasowanie kanalow -> Info/EPG."
		elif item is cfg.p_srv:
			txt = "Zapas: gotowy plik serwera jako kolejne zrodlo - uzupelnia kanaly, ktorych dekoder nie ma, i zastepuje " \
				"dekoder, gdy ten zawiedzie (blad pobrania albo plik starszy niz 30 h); serwer nie jest proszony o pobranie. " \
				"Zawsze: to samo, ale przy pobieraniu swiezych danych serwer tez pobiera (import czeka na niego). " \
				"Nie: bez serwera. Ustawienie domyslne - w Akcjach mozna je zmienic na jeden start."
		elif item is cfg.rs_mode:
			from .health import mem_available_mb, uptime_days
			txt = "Po nocnym przebiegu (tylko w czuwaniu, bez nagrania w ciagu 15 min, najwyzej raz na dobe). Gdy potrzebny: " \
				"tuner nie stroi (pelny restart), malo wolnej pamieci lub Enigma2 urosla o ponad 250 MB, SatWatch zmienil " \
				"satellites.xml bez przeladowania (restart Enigma2), dekoder dziala dluzej niz podana liczba dni (pelny restart). " \
				"Powod w logu i w podsumowaniu nocy. Teraz: wolne %d MB, dziala %.1f dnia." % (
				mem_available_mb(), uptime_days())
		elif item in (cfg.cl_on, cfg.cl_dir, cfg.cl_ts_hours, cfg.cl_crash_keep, cfg.cl_e2s_days, cfg.cl_every, self.p_cldirs):
			try:
				from .cleanup import run_cleanup
				n, mb, _ = run_cleanup(dry=True)
				now = "Teraz do usuniecia: %d plikow, %d MB." % (n, mb)
			except Exception:
				now = ""
			txt = "Co podana liczbe minut wtyczka usuwa z zaznaczonych katalogow pliki timeshift starsze niz podana liczba " \
				"godzin (aktywny timeshift jest pomijany) i nadmiar plikow enigma2_crash_*.log - zostaje tyle najnowszych " \
				"(lacznie), ile ustawiono, oraz zrzuty TS aplikacji E2Stream (stall-*.ts w jej katalogu na dekoderze) starsze niz " \
				"podana liczba dni. Katalogi: OK - lista (nosniki, katalog timeshift i crash logow z ustawien Enigma2, " \
				"wlasne). Kazde usuniecie jest zapisywane w logu EPG Scan. %s" % now
		elif item in (cfg.rs_days, cfg.rs_mem):
			txt = "Profilaktycznie: pelny restart, gdy odbiornik dziala dluzej niz tyle dni (nie = wylaczone). Pamiec: restart " \
				"Enigma2, gdy wolnej pamieci w systemie jest mniej niz prog."
		elif item is cfg.map_mode:
			txt = "Skad dekoder wie, ktory kanal bierze program z ktorej stacji serwisu. Z serwera: mapa serwera epgpl " \
				"(kopia na dysku). Na dekoderze: dekoder sam odswieza listy stacji i buduje mape z Twoich bukietow (te same " \
				"reguly co serwer); wskazania w Kanaly -> Dopasowanie kanalow: dekoder (mozna przeniesc z serwera)."
		elif item is cfg.sched_on:
			txt = "Wylacznik harmonogramu - ustawienia ponizej zostaja zapisane. Nastepny start: %s. Co robi start z " \
				"harmonogramu: profil domyslny (%s)." % (fmt_ts(next_run()), profile_text(profile_default()))
		elif item is cfg.notify:
			txt = "Komunikat po skanie, gdy okno wtyczki jest zamkniete. Nie jest pokazywany w czuwaniu."
		elif item is cfg.clear_mode:
			txt = "Nie czysc: nowe dane dopisywane. Tylko przy harmonogramie: nocny skan buduje EPG od zera, " \
				"skany reczne i doczytywanie tylko dopisuja (zalecane). Przy kazdym skanie: pelny reset zawsze."
			if not single_flush_supported():
				txt += " Czyszczenie pojedynczych kanalow jest niedostepne w tym obrazie."
		elif item is cfg.topup:
			txt = "Krotki skan w ciagu dnia - tylko transpondery, na ktorych kanalom konczy sie EPG. Bez czyszczenia, " \
				"wolnymi tunerami z rezerwa. Ten sam transponder jest doczytywany najwyzej raz na \"Sprawdzaj co\"."
		elif item is cfg.topup_every:
			txt = "Jak czesto sprawdzac, czy jakims kanalom konczy sie EPG - to rowniez podstawowy odstep miedzy " \
				"skanami tego samego transpondera. Bez zysku odstep rosnie 2x: najwyzej do 2x podstawowego, jesli " \
				"transponder dal dane w ostatnich 3 dniach, inaczej do 24 h. Po zysku wraca do podstawowego."
		elif item is cfg.topup_hours:
			txt = "Prog: kanal trafia do doczytania, gdy jego EPG z satelity siega mniej niz tyle do przodu. " \
				"Przy duzym progu (np. 3 dni) doczytywanie bedzie czestsze; Polsat Box nadaje zwykle ok. 1 doby, " \
				"wiec przy progu ponad 1 dzien jego transpondery beda doczytywane przy kazdym sprawdzeniu."
		elif item in (cfg.topup_fixed, cfg.topup_fixed_time):
			txt = "Raz dziennie o tej godzinie doczytuje wszystkie transpondery z krotkim EPG, bez wzgledu na odstepy " \
				"i ich wydluzanie. Dobre do zlapania pory, o ktorej nadawca (np. Polsat Box) dosyla nowe dni - " \
				"w logach widac ja po wpisach \"zasieg: +N h\"."
		elif item is cfg.topup_when:
			txt = "Zawsze: korzysta tylko z wolnych tunerow i oddaje je, gdy zmienisz kanal. " \
				"Tylko w czuwaniu: doczytywanie czeka, az odbiornik przejdzie w czuwanie."
		elif item is cfg.min_time:
			txt = "Najkrotszy czas na transponderze, nawet jesli nie przychodza nowe dane. Daje czas na dalsze dni " \
				"z karuzeli EPG, ktore nadawca wysyla rzadziej."
		elif item is cfg.idle_time:
			txt = "Transponder konczy sie, gdy przez tyle sekund nie przybedzie zadne zdarzenie EPG (po uplywie " \
				"czasu minimalnego). Dluzej = pelniejsze dane, ale dluzszy skan."
		elif item is cfg.max_time:
			txt = "Gorna granica czasu na transponderze - skan przechodzi dalej, nawet jesli dane wciaz splywaja " \
				"(w logu: \"gotowe (limit czasu)\")."
		elif item is cfg.backup:
			txt = "Po kazdym skanie wtyczka zapisuje EPG na dysk i robi kopie pliku EPG do katalogu kopii. " \
				"Kopia sluzy do odtworzenia EPG (zolty na ekranie glownym) i do kontroli po starcie."
		elif item is cfg.backup_dir:
			txt = "Katalog kopii EPG (OK = wybor). Powinien lezec na dysku lub USB - nie w pamieci odbiornika. " \
				"Teraz: %s" % cfg.backup_dir.value
		elif item is cfg.backup_keep:
			txt = "Ile ostatnich kopii EPG trzymac. Tyle samo (min. 2) jest zachowywanych logow skanu. " \
				"Kopii teraz: %d" % len(list_backups())
		elif item is cfg.restore:
			txt = "Kilka minut po starcie Enigma2 sprawdza EPG; gdy jest w nim mniej niz 30%% zdarzen " \
				"z ostatniego skanu, wczytuje najnowsza kopie. Kopii: %d" % len(list_backups())
		elif item in (cfg.xmltv, cfg.xmltv_mode):
			n = len([x for x in cfg.xmltv_sources.value.split("|") if x])
			txt = "Po skanie z satelity: kanaly bez EPG (rowniez IPTV z bukietow) dostaja program z XMLTV, " \
				"a w trybie \"dalsze dni\" pozostale kanaly takze audycje po koncu danych z satelity. " \
				"Zrodla wybierasz zoltym przyciskiem - wybrano %d." % n
		elif item is cfg.log_place:
			txt = "Log: %s (zachowywanych logow: %d - tyle, ile kopii EPG, min. 2). W katalogu kopii log przetrwa " \
				"restart dekodera i wybudzenie z glebokiego czuwania." % (log_path(), log_count())
		elif item is cfg.max_parallel:
			txt = "Gorna granica wlasnych skanow naraz. Nowy tuner jest zajmowany tylko wtedy, gdy po tym zostanie " \
				"rezerwa wolnych - liczone sa wszystkie zajete (ogladanie, nagrania, streamy, PiP). Np. 8 tunerow, " \
				"limit 6, rezerwa 1, ogladanie + nagranie: skan zajmie najwyzej 5. Skan na transponderze ogladanego " \
				"kanalu nie zajmuje dodatkowego tunera."
		elif item is cfg.reserve:
			txt = "Tyle tunerow skan zawsze zostawia wolnych - na zmiane kanalu lub nowy stream. Dodatkowo 2 min " \
				"przed nagraniem z timera skan zwalnia/trzyma tyle tunerow, ile nagran ma ruszyc."
			try:
				from .satwatch.scanner import tuner_advice
				txt += "\n" + tuner_advice()[0] + " Pelna porada: Info/EPG -> Raporty -> Konfiguracja tunerow."
			except Exception:
				pass
		elif self.is_sw_full(item):
			txt = "Raz w tygodniu, w wybrany dzien, nocny przebieg robi pelny skan satelity SatWatch (wszystkie TP " \
				"monitorowanych pozycji, kilka minut, jeden wolny tuner); w pozostale dni SatWatch tylko sprawdza zmiany " \
				"satellites.xml. To samo ustawienie: Ustawienia -> SatWatch."
		elif item is cfg.sched_skip_rec:
			txt = "Skan ruszy dopiero po zakonczeniu nagrania. Przy wielu tunerach zwykle zbedne - tuneru " \
				"pilnuje rezerwa, a nagrania maja pierwszenstwo. Przydatne przy 1-2 tunerach."
		self["help"].setText(txt)
		set_keys(self, red="Anuluj", green="Zapisz", yellow={
			"net": "Pozostale zrodla XMLTV"}.get(self.section, ""))

	def ok(self):
		cur = self["config"].getCurrent()
		if cur and cur[1] in (cfg.xmltv_bouquets, cfg.scope):
			target = "xmltv" if cur[1] is cfg.xmltv_bouquets else "scan"
			self.session.openWithCallback(lambda saved=None: self.bq_done(cur[1], saved), EPGScanBouquets, target)
			return
		if cur and cur[1] is self.p_cldirs:
			self.session.openWithCallback(self.cl_dirs_done, EPGScanDirs)
			return
		if cur and cur[1] is self.p_upd:
			self.upd_check()
			return
		if cur and cur[1] is self.p_pack:
			from .clientpack import build
			if build(lambda res: (self.session.open(MessageBox, "Dane dla E2Stream: %s" % res, MessageBox.TYPE_INFO, timeout=10),
					self.update_help())):
				self["help"].setText("Przygotowywanie danych dla E2Stream (EPG, ikony, referencje)...")
			else:
				self.session.open(MessageBox, "Dane dla E2Stream sa wlasnie przygotowywane.", MessageBox.TYPE_INFO, timeout=5)
			return
		if cur and cur[1] is cfg.backup_dir:
			from Screens.LocationBox import LocationBox
			self.session.openWithCallback(self.dir_cb, LocationBox, text="Katalog kopii EPG", currDir=cfg.backup_dir.value)

	# ------------------------------------------------------------ aktualizacja reczna
	def upd_check(self):
		from .updater import check_async
		self["help"].setText("Sprawdzanie wydan na GitHub...")
		check_async(self.upd_checked)

	def upd_checked(self, info):
		from .updater import newer
		if not info:
			self.session.open(MessageBox, "Nie udalo sie sprawdzic wydan (brak internetu lub GitHub). Szczegoly w logu.",
				MessageBox.TYPE_ERROR, timeout=8)
			self.update_help()
			return
		ver, url = info
		if newer(ver, VERSION):
			q = "Dostepna wersja %s (zainstalowana %s). Zainstalowac?" % (ver, VERSION)
		else:
			q = "Masz najnowsza wersje (%s). Zainstalowac ja ponownie?" % VERSION
		self.update_help()
		self.session.openWithCallback(lambda yes: yes and self.upd_install(url, ver), MessageBox, q, MessageBox.TYPE_YESNO,
			default=newer(ver, VERSION))

	def upd_install(self, url, ver):
		from .updater import install_async
		self["help"].setText("Pobieranie i instalacja EPG Scan %s..." % ver)
		install_async(url, ver, lambda err: self.upd_done(err, ver))

	def upd_done(self, err, ver):
		self.update_help()
		if err:
			self.session.open(MessageBox, "Instalacja nieudana: %s" % err, MessageBox.TYPE_ERROR, timeout=10)
			return
		self.session.openWithCallback(self.upd_restart, MessageBox,
			"Zainstalowano EPG Scan %s. Zrestartowac teraz Enigma2?\n(Nie - nowa wersja zadziala po najblizszym restarcie.)" % ver,
			MessageBox.TYPE_YESNO, default=True)

	def upd_restart(self, yes):
		if yes:
			from .updater import restart_gui
			restart_gui(self.session)

	def bq_done(self, item, saved):
		"""Po zapisie listy bukietow: tryb 'wybrane bukiety' (wybor ma dzialac od razu)."""
		if saved:
			item.value = "selected"
			self["config"].invalidateCurrent()
			self.build()
		self.update_help()

	def cl_dirs_done(self, *a):
		try:
			self.p_cldirs.setChoices([("v", cl_dirs_text())], "v")
			self["config"].invalidateCurrent()
		except Exception:
			pass
		self.update_help()

	def dir_cb(self, path):
		if path:
			cfg.backup_dir.value = path
			self["config"].invalidateCurrent()
			self.update_help()

	def open_sources(self):
		self.session.openWithCallback(lambda *a: self.update_help(), EPGScanXmltvSources)

	def yellow(self):
		if self.section == "net":
			from .xmltv import cache_present
			if cache_present():
				self.open_sources()
			else:
				self.check_sources(self.open_sources)

	def bouquets(self):
		"""Zolty: wybor - bukiety do skanowania, zrodla XMLTV, dopasowanie kanalow (serwer epgpl)."""
		from .common import EPGChoiceBox as ChoiceBox
		scope = {"all": "wszystkie bukiety", "tps": "wszystkie transpondery"}.get(cfg.scope.value)
		opts = [("Bukiety do skanowania" + ("  (zakres: %s - wybor nie jest teraz uzywany)" % scope if scope else ""), "bouquets"),
			("Zrodla XMLTV" + ("" if cfg.xmltv.value else "  (uzupelnianie z XMLTV wylaczone)"), "xmltv")]
		try:
			from .xmltv import load_sources
			seen = set()
			for src in load_sources()[0]:
				if src.epgpl and src.epgpl not in seen:
					seen.add(src.epgpl)
					opts.append(("Dopasowanie kanalow - %s" % src.desc, ("mapping", src.epgpl, src.desc)))
		except Exception:
			pass
		self.session.openWithCallback(self.yellow_choice, ChoiceBox, title="Bukiety / XMLTV", list=opts)

	def yellow_choice(self, choice):
		if not choice:
			return
		act = choice[1]
		if act == "bouquets":
			self.session.openWithCallback(lambda *a: self.update_help(), EPGScanBouquets)
		elif act == "xmltv":
			from .xmltv import cache_present
			if cache_present():
				self.open_sources()            # sa kopie list (wlasne / pakietu) - okno od razu
			else:
				self.check_sources(self.open_sources)
		elif isinstance(act, tuple) and act[0] == "mapping":
			from .mapping import EPGScanMapping
			self.session.open(EPGScanMapping, act[1], act[2])

	def backup_now(self):
		try:
			eEPGCache.getInstance().save()
		except Exception:
			pass
		self["help"].setText("Zapis EPG i kopia...")
		self.bk_timer.start(5000, True)

	def backup_done(self):
		dst = copy_backup()
		self.session.open(MessageBox, ("Kopia EPG zapisana:\n%s" % dst) if dst else ("Kopia nieudana - szczegoly w %s" % log_path()),
			MessageBox.TYPE_INFO if dst else MessageBox.TYPE_ERROR, timeout=6)
		self.update_help()

	def save(self):
		if cfg.xmltv_bouquets.value == "selected" and not cfg.xmltv_bq_sel.value:
			# wybrane bukiety, ale nic nie wybrano - najpierw wybor (inaczej program z internetu nie trafi nigdzie)
			self.session.openWithCallback(lambda *a: cfg.xmltv_bq_sel.value and self.save(), EPGScanBouquets, "xmltv")
			return
		if cfg.xmltv_bouquets.isChanged():
			cfg.xmltv_bq_asked.value = True
			cfg.xmltv_bq_asked.save()
		cli_changed = cfg.cli_api.isChanged() or cfg.cli_port.isChanged() or cfg.cli_token.isChanged()
		sync = cfg.site_order.isChanged() or cfg.xmltv_bouquets.isChanged() or cfg.one_place.isChanged() or cfg.cand_night.isChanged()
		for x in all_items():
			x.save()
		try:
			from .satwatch.sw import cfg as swcfg
			swcfg.full_enable.save()
			swcfg.full_day.save()
		except Exception:
			pass
		save_cfg()
		try:
			from .common import apply_eit_policy
			apply_eit_policy()
		except Exception as e:
			log("EIT: %s" % e)
		if cli_changed:
			try:
				from .clients import start as clients_start
				clients_start()
			except Exception as e:
				log("Serwer dla klientow: %s" % e)
		if sync:
			try:
				from .mapping import sync_settings_bg
				sync_settings_bg()     # mapa dekodera i serwer epgpl wg nowej kolejnosci / bukietow
			except Exception as e:
				log("Ustawienia mapy: %s" % e)
		try:
			from .mapping import report_schedule
			report_schedule()
		except Exception as e:
			log("Zgloszenie harmonogramu do serwera epgpl nieudane: %s" % e)
		self.close(True)

	def cancel(self):
		for x in all_items():
			x.cancel()
		self.close(False)


class EPGScanBouquets(Screen):
	skin = """
	<screen position="center,center" size="1100,880" title="EPG Scan - bukiety">
<widget name="list" position="20,15" size="1060,700" scrollbarMode="showOnDemand" />
		<eLabel position="20,725" size="1060,2" backgroundColor="#00606060" />
		<widget name="keys" position="20,735" size="1060,60" font="Regular;28" foregroundColor="#00b0b0b0" />
		%s
	</screen>""" % keys_skin(20, 805, 1060)

	def __init__(self, session, target="scan"):
		"""target: "scan" - bukiety do skanowania satelity, "xmltv" - bukiety dla programu z internetu i mapy kanalow."""
		Screen.__init__(self, session)
		self.target = target
		self.setTitle("EPG Scan - kanaly z bukietow: skan EPG z satelity" if target == "scan" else
			"EPG Scan - kanaly z bukietow: program z internetu i dopasowanie kanalow")
		sel = [x for x in (cfg.bouquets if target == "scan" else cfg.xmltv_bq_sel).value.split(",") if x]
		items = [SelectionEntryComponent(name, fname, i, fname in sel) for i, (fname, name) in enumerate(list_bouquets())]
		self["list"] = SelectionList(items)
		big_list(self["list"])
		self["keys"] = Label("OK: zaznacz / odznacz.  Zielony: zapisz.  Czerwony: anuluj.")
		init_keys(self)
		# uwaga: onLayoutFinish przyjmuje tylko metody ekranu (inne obiekty Enigma2 wykonuje przez exec)
		self.onLayoutFinish.append(self.layout_done)

	def layout_done(self):
		big_list(self["list"])
		set_keys(self, red="Anuluj", green="Zapisz")
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions"], {
			"ok": self["list"].toggleSelection,
			"green": self.save,
			"red": self.close,
			"cancel": self.close,
		}, -1)

	def save(self):
		chosen = [x[1] for x in self["list"].getSelectionsList()]
		if self.target != "scan":
			cfg.xmltv_bq_sel.value = ",".join(chosen)
			cfg.xmltv_bq_sel.save()
			cfg.xmltv_bq_asked.value = True
			cfg.xmltv_bq_asked.save()
			save_cfg()
			self.close(True)
			return
		cfg.bouquets.value = ",".join(chosen)
		cfg.bouquets.save()
		cfg.known_bouquets.value = ",".join(b[0] for b in list_bouquets())
		cfg.known_bouquets.save()
		save_cfg()
		self.close(True)


def cl_dirs():
	"""Zaznaczone katalogi sprzatania (cfg.cl_dir: sciezki rozdzielone '|'; dawniej jedna)."""
	return [d.rstrip("/") or "/" for d in (cfg.cl_dir.value or "").split("|") if d.strip()]


def cl_dirs_text():
	d = cl_dirs()
	if not d:
		return "brak"
	return d[0] if len(d) == 1 else "%d katalogi" % len(d) if len(d) < 5 else "%d katalogow" % len(d)


def cl_candidates():
	"""[(sciezka, opis)] - nosniki z /media, katalog timeshift i crash logow z ustawien Enigma2, wlasne, zaznaczone."""
	out = []

	def add(path, why):
		path = (path or "").rstrip("/")
		if not path:
			return
		for i, (p, w) in enumerate(out):
			if p == path:
				if why not in w:
					out[i] = (p, w + ", " + why)
				return
		out.append((path, why))
	try:
		from Components.config import config
		add(config.usage.timeshift_path.value, "timeshift (Enigma2)")
	except Exception:
		pass
	for attr in ("debug_path", "log_path"):
		try:
			from Components.config import config
			add(getattr(config.crash, attr).value, "crash logi (Enigma2)")
			break
		except Exception:
			pass
	for d in ("/media/hdd", "/media/usb", "/media/mmc"):
		if os.path.isdir(d):
			add(d, "nosnik")
	try:
		for d in sorted(os.listdir("/media")):
			p = os.path.join("/media", d)
			if d not in ("net", "autofs") and os.path.ismount(p):
				add(p, "nosnik")
	except Exception:
		pass
	add("/home/root/logs", "crash logi") if os.path.isdir("/home/root/logs") else None
	for d in (cfg.cl_dirs_own.value or "").split("|"):
		if d.strip():
			add(d, "wlasny")
	for d in cl_dirs():
		add(d, "zaznaczony")
	return out


class EPGScanDirs(Screen):
	"""Katalogi sprzatania dysku: OK - zaznacz / odznacz, zolty - dodaj wlasny, niebieski - usun wlasny, zielony - zapisz."""
	skin = """
	<screen position="center,center" size="1300,880" title="EPG Scan - katalogi sprzatania dysku">
		<widget name="list" position="20,15" size="1260,690" scrollbarMode="showOnDemand" />
		<widget name="keys" position="20,725" size="1260,70" font="Regular;26" />
		%s
	</screen>""" % keys_skin(20, 805, 1260)

	def __init__(self, session):
		Screen.__init__(self, session)
		self.setTitle("EPG Scan - katalogi sprzatania dysku")
		self.own = [d.rstrip("/") for d in (cfg.cl_dirs_own.value or "").split("|") if d.strip()]
		self["list"] = SelectionList([])
		self["keys"] = Label("OK: zaznacz / odznacz.  Zolty: dodaj wlasny katalog.  Niebieski: usun wlasny.  Zielony: zapisz.")
		init_keys(self)
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions"], {
			"ok": self.toggle,
			"green": self.save,
			"yellow": self.add,
			"blue": self.remove,
			"red": self.close,
			"cancel": self.close,
		}, -1)
		self.onLayoutFinish.append(self.layout)

	def layout(self):
		self.fill(set(cl_dirs()))
		set_keys(self, red="Anuluj", green="Zapisz", yellow="Dodaj katalog", blue="Usun wlasny")

	def fill(self, chosen, keep=None):
		saved = cfg.cl_dirs_own.value
		cfg.cl_dirs_own.value = "|".join(self.own)
		cands = cl_candidates()
		cfg.cl_dirs_own.value = saved
		items = [SelectionEntryComponent("%s   [%s%s]" % (p, w, "" if os.path.isdir(p) else ", brak"), p, i, p in chosen)
			for i, (p, w) in enumerate(cands)]
		self["list"].setList(items)
		big_list(self["list"])
		if keep:
			for i, x in enumerate(items):
				if x[0][1] == keep:
					self["list"].moveToIndex(i)
					break

	def chosen(self):
		return set(x[1] for x in self["list"].getSelectionsList())

	def toggle(self):
		self["list"].toggleSelection()

	def add(self):
		from Screens.LocationBox import LocationBox
		self.session.openWithCallback(self.added, LocationBox, text="Katalog do sprzatania", currDir="/media/")

	def added(self, path):
		if not path:
			return
		path = path.rstrip("/") or "/"
		ch = self.chosen()
		ch.add(path)
		if path not in self.own:
			self.own.append(path)
		self.fill(ch, path)

	def remove(self):
		cur = self["list"].getCurrent()
		if not cur:
			return
		p = cur[0][1]
		if p in self.own:
			self.own.remove(p)
			ch = self.chosen()
			ch.discard(p)
			self.fill(ch)

	def save(self):
		ch = [x[1] for x in self["list"].getSelectionsList()]
		if not ch:
			self.session.open(MessageBox, "Zaznacz co najmniej jeden katalog (albo wylacz sprzatanie).", MessageBox.TYPE_INFO, timeout=5)
			return
		cfg.cl_dir.value = "|".join(ch)
		cfg.cl_dir.save()
		cfg.cl_dirs_own.value = "|".join(self.own)
		cfg.cl_dirs_own.save()
		save_cfg()
		self.close(True)


class EPGScanXmltvSources(Screen):
	HELP = ("OK: zaznacz / odznacz. Kanal dostaje dane z pierwszego zrodla, ktore je ma.\n"
		"[wlasne] - Twoje listy (zostaja zawsze), [kopia pakietu] - z odinstalowanego pakietu zrodel.")
	skin = """
	<screen position="center,center" size="1500,940" title="EPG Scan - zrodla XMLTV">
		<widget name="list" position="20,15" size="1460,560" scrollbarMode="showOnDemand" />
		<widget name="info" position="20,585" size="1460,160" font="Regular;28" foregroundColor="#c0c0c0" />
		<widget name="keys" position="20,755" size="1460,80" font="Regular;28" />
		%s
	</screen>""" % keys_skin(20, 855, 1460)

	def __init__(self, session):
		Screen.__init__(self, session)
		self.setTitle("EPG Scan - zrodla XMLTV do uzupelniania")
		self["list"] = SelectionList([])
		big_list(self["list"])
		self["info"] = Label("")
		self["keys"] = Label(self.HELP)
		init_keys(self)
		self["actions"] = ActionMap(["OkCancelActions", "ColorActions"], {
			"ok": self.toggle,
			"green": self.save,
			"red": self.close,
			"cancel": self.close,
			"yellow": self.delete,
			"blue": self.add,
		}, -1)
		self["info_actions"] = ActionMap(["EPGSelectActions"], {"info": self.report}, -1)
		try:                                  # zrodlo "EPG PL (dekoder)" zawsze na liscie (plik programu moze jeszcze nie istniec)
			from .localrun import write_sources, out_dir, SRC_FILE
			if not os.path.exists(SRC_FILE):
				write_sources(out_dir())
		except Exception:
			pass
		self.srv_info = {}
		self.probe_servers()
		from .xmltv import epgimport_selected
		sel = [x for x in cfg.xmltv_sources.value.split("|") if x]
		if not sel:
			sel = epgimport_selected()   # na start: to, co bylo zaznaczone w EPG Import
		self.fill(sel)
		try:
			self["list"].onSelectionChanged.append(self.show_info)
		except Exception:
			pass
		self.onLayoutFinish.append(self.layout_done)

	def selected(self):
		"""Zaznaczone zrodla w kolejnosci listy (takze w zwinietych grupach)."""
		return [x.desc for x in getattr(self, "srcs", []) if x.desc in self.sel_set]

	def fill(self, sel=None, keep_pos=False):
		"""Buduje liste: blok wlasnych (plasko) i blok pakietu pogrupowany jak w EPG Import (OK rozwija grupe)."""
		from .xmltv import load_sources, load_state, state_label, SOURCES_DIR
		if sel is not None:
			self.sel_set = set(sel)
		if not hasattr(self, "opened"):
			self.opened = set()
		pos = self["list"].getSelectedIndex() if keep_pos else 0
		srcs, maps = load_sources()
		st = load_state()
		self.srcs = srcs
		items = []
		from .xmltv import DEK_DESC
		ctl = [(i, s) for i, s in enumerate(srcs) if s.desc == DEK_DESC or s.epgpl]
		own = [(i, s) for i, s in enumerate(srcs) if s.origin == "wlasne" and (i, s) not in ctl]
		pkg = [(i, s) for i, s in enumerate(srcs) if s.origin != "wlasne" and (i, s) not in ctl]
		for i, s in ctl:
			self.sel_set.discard(s.desc)      # sterowane z Akcji - zaznaczenie tutaj nic nie zmienia

		def entry(i, s, indent):
			lab = state_label(s.desc, st)
			org = ""
			if s.origin == "pakiet" and s.file and not os.path.exists(os.path.join(SOURCES_DIR, s.file)):
				org = "  [kopia pakietu]"
			name = s.desc if indent else "%s - %s" % (s.cat, s.desc)
			return wide_entry(SelectionEntryComponent("%s%s%s%s" % ("      " if indent else "", name, org,
				("   [%s]" % lab) if lab else ""), s.desc, i, s.desc in self.sel_set))

		items.append(self.header("Dekoder i serwer epgpl - wlaczane w Akcjach (zielony na ekranie glownym)", "ctl"))
		for i, s in ctl:
			items.append(self.ctl_row(i, s, st))
		items.append(self.header("Wlasne zrodla" if own else "Wlasne zrodla: brak - niebieski: dodaj liste z adresu", "own"))
		for i, s in own:
			items.append(entry(i, s, False))
		if pkg:
			items.append(self.header("EPG Import Sources (pakiet zrodel)", "pkg"))
			groups = []
			for i, s in pkg:
				if not groups or groups[-1][0] != s.cat:
					groups.append((s.cat, []))
				groups[-1][1].append((i, s))
			for cat, members in groups:
				nsel = len([1 for i, s in members if s.desc in self.sel_set])
				items.append(self.group_row(cat, len(members), nsel, cat in self.opened))
				if cat in self.opened:
					for i, s in members:
						items.append(entry(i, s, True))
		self["list"].setList(items)
		if keep_pos:
			self["list"].moveToIndex(min(pos, len(items) - 1))

	def header(self, text, grp):
		"""Naglowek bloku (nie da sie go zaznaczyc)."""
		from Components.MultiContent import MultiContentEntryText
		from enigma import RT_HALIGN_LEFT, RT_VALIGN_CENTER
		return [(text, None, "hdr_" + grp, False),
			MultiContentEntryText(pos=(S(5), 0), size=(S(1420), S(ROW_H)), font=0, flags=RT_HALIGN_LEFT | RT_VALIGN_CENTER,
				text="--  %s  --" % text, color=0x00e0b030)]

	def ctl_label(self, s, st):
		from .xmltv import state_label, DEK_DESC
		lab = state_label(s.desc, st)
		if s.desc == DEK_DESC:
			try:
				from .localrun import out_dir
				f = out_dir() + "pl.xml.xz"
				if os.path.exists(f):
					return "plik z %s%s" % (time.strftime("%d.%m %H:%M", time.localtime(os.path.getmtime(f))),
						("; import: %s" % lab) if lab else "")
			except Exception:
				pass
			return "brak pliku - zaznacz Dekoder w Akcjach i uruchom"
		inf = self.srv_info.get(s.epgpl)
		if inf is None:
			txt = "sprawdzam serwer..."
		elif inf.get("err"):
			txt = "serwer nie odpowiada"
		elif inf.get("guide"):
			txt = "plik serwera z %s%s%s" % (time.strftime("%d.%m %H:%M", time.localtime(inf["guide"])),
				", serwer pobiera teraz" if inf.get("running") else "",
				", serwer spoza sieci lokalnej - tylko plik programu" if inf.get("remote") else "")
		else:
			txt = "serwer bez pliku programu"
		return txt + (("; import: %s" % lab) if lab else "")

	def ctl_row(self, i, s, st):
		"""Zrodlo sterowane z Akcji (dekoder, serwer epgpl): bez pola wyboru, z data danych."""
		from Components.MultiContent import MultiContentEntryText
		from enigma import RT_HALIGN_LEFT, RT_VALIGN_CENTER
		return [(s.desc, None, "ctl:%d" % i, False),
			MultiContentEntryText(pos=(S(15), 0), size=(S(1410), S(ROW_H)), font=0, flags=RT_HALIGN_LEFT | RT_VALIGN_CENTER,
				text="%s   [%s]" % (s.desc, self.ctl_label(s, st)), color=0x00c0c0c0)]

	def probe_servers(self):
		"""Data pliku programu na serwerach epgpl (w tle, /api/stan)."""
		import threading
		from enigma import eTimer
		from .mapping import epgpl_servers, http_get_json

		def work():
			for base, desc in epgpl_servers():
				try:
					st = http_get_json(base + "api/stan", timeout=6)
					self.srv_info[base] = {"guide": int(st.get("guide") or 0), "running": bool(st.get("running"))}
				except Exception:
					# API odpowiada tylko w sieci lokalnej - serwer z internetu: data z naglowka pliku programu
					try:
						import urllib.request
						import email.utils
						req = urllib.request.Request(base + "pl.xml.xz", method="HEAD", headers={"User-Agent": "EPGScan"})
						lm = urllib.request.urlopen(req, timeout=8).headers.get("Last-Modified")
						self.srv_info[base] = {"guide": int(email.utils.parsedate_to_datetime(lm).timestamp()) if lm else 0,
							"remote": True}
					except Exception:
						self.srv_info[base] = {"err": True}
		self.probe_th = threading.Thread(target=work)
		self.probe_th.daemon = True
		self.probe_th.start()
		self.probe_timer = eTimer()
		self.probe_timer.callback.append(self.probe_poll)
		self.probe_timer.start(500, False)

	def probe_poll(self):
		if self.probe_th.is_alive():
			return
		self.probe_timer.stop()
		try:
			self.fill(keep_pos=True)
			self.show_info()
		except Exception:
			pass

	def group_row(self, cat, total, nsel, opened):
		"""Grupa zrodel (sourcecat) - OK rozwija / zwija."""
		from Components.MultiContent import MultiContentEntryText
		from enigma import RT_HALIGN_LEFT, RT_VALIGN_CENTER
		txt = "%s  %s   (%s%d)" % ("[-]" if opened else "[+]", cat, ("zaznaczone %d z " % nsel) if nsel else "", total)
		return [(cat, None, "grp:" + cat, False),
			MultiContentEntryText(pos=(S(15), 0), size=(S(1410), S(ROW_H)), font=0, flags=RT_HALIGN_LEFT | RT_VALIGN_CENTER,
				text=txt, color=0x0080c8ff if nsel else 0x00c0c0c0)]

	def toggle(self):
		cur = self["list"].getCurrent()
		if not cur:
			return
		key = cur[0][2]
		if cur[0][1] is None:
			if isinstance(key, str) and key.startswith("grp:"):
				cat = key[4:]
				if cat in self.opened:
					self.opened.discard(cat)
				else:
					self.opened.add(cat)
				self.fill(keep_pos=True)
			return                      # naglowek bloku
		desc = cur[0][1]
		if desc in self.sel_set:
			self.sel_set.discard(desc)
		else:
			self.sel_set.add(desc)
		self.fill(keep_pos=True)

	def layout_done(self):
		big_list(self["list"])
		set_keys(self, red="Anuluj", green="Zapisz", yellow="Usun wlasne", blue="Adres listy")
		# lista budowana ponownie po zaladowaniu skorki: przy jej nakladaniu SelectionList odtwarza wiersze
		# jako zwykle pozycje z polem wyboru, przez co naglowki blokow dostawaly kwadrat
		self.fill(self.selected())
		self.show_info()

	def show_info(self):
		from .xmltv import own_meta, fmt_age
		s = self.current()
		if not s:
			cur = self["list"].getCurrent()
			hdr = cur[0][2] if cur else ""
			if isinstance(hdr, str) and hdr.startswith("grp:"):
				cat = hdr[4:]
				on = [x.desc for x in self.srcs if x.cat == cat and x.desc in self.sel_set]
				self["info"].setText("Grupa %s - OK: %s.\nZaznaczone: %s" % (cat,
					"zwin" if cat in self.opened else "rozwin", ", ".join(on) if on else "brak"))
				return
			self["info"].setText({
				"hdr_ctl": "Program pobierany przez dekoder i serwer epgpl wlacza sie w Akcjach (zielony) - tam tez kolejnosc\n"
					"i zapas. Tutaj tylko data danych; niebieski na serwerze: dopasowanie kanalow, adres.",
				"hdr_own": "Twoje listy (adres z niebieskiego albo pliki w /etc/enigma2/epgscan_sources/wlasne).\n"
					"Sa sprawdzane jako pierwsze - maja pierwszenstwo przed listami pakietu.",
				"hdr_pkg": "Listy z pakietu enigma2-plugin-extensions-epgimport-sources (Rytec i inne).\n"
					"Po odinstalowaniu pakietu dzialaja z kopii wtyczki."}.get(hdr, ""))
			return
		txt = "Lista: %s" % (s.file or "wbudowana")
		if s.origin == "wlasne":
			e = own_meta().get(s.file, {})
			if e.get("url"):
				txt += "  [wlasne]\nAdres: %s\nZmieniona: %s, sprawdzona: %s%s" % (e["url"], fmt_age(e.get("updated")),
					fmt_age(e.get("checked")), "  (ostatnio blad pobierania)" if e.get("status") == "blad" else "")
			else:
				txt += "  [wlasne]\nAdres nieznany - niebieski: ustaw adres, wtedy lista bedzie aktualizowana sama"
		elif s.origin == "pakiet":
			txt += "  (pakiet zrodel EPG Import)"
		if s.report:
			txt += "\nInfo/EPG: raport zrodla"
		self["info"].setText(txt)

	def report(self):
		s = self.current()
		if not s or not s.report:
			self.session.open(MessageBox, "To zrodlo nie udostepnia raportu.", MessageBox.TYPE_INFO, timeout=4)
			return
		self.session.open(EPGScanReport, s.desc, s.report)

	def current(self):
		cur = self["list"].getCurrent()
		try:
			k = cur[0][2]
			if isinstance(k, str) and k.startswith("ctl:"):
				return self.srcs[int(k[4:])]   # dekoder / serwer epgpl (bez zaznaczania)
			if cur[0][1] is None:
				return None             # naglowek bloku
			return self.srcs[cur[0][2]]
		except Exception:
			return None

	def delete(self):
		s = self.current()
		if not s:
			return
		from .xmltv import DEK_DESC
		if s.desc == DEK_DESC:
			self.session.open(MessageBox, "Zrodlo dekodera jest czescia wtyczki - wylaczasz je w Akcjach (Dekoder: nie).",
				MessageBox.TYPE_INFO, timeout=6)
			return
		if s.origin != "wlasne":
			self.session.open(MessageBox, "To zrodlo pochodzi z pakietu zrodel EPG Import - nie mozna go usunac.\n"
				"Mozesz je tylko odznaczyc.", MessageBox.TYPE_INFO, timeout=6)
			return
		inside = [x.desc for x in self.srcs if x.file == s.file]
		self.to_delete = s.file
		self.session.openWithCallback(self.delete_cb, MessageBox, "Usunac liste zrodel %s?\nZawiera: %s" % (
			s.file, ", ".join(inside)), MessageBox.TYPE_YESNO, default=False)

	def delete_cb(self, yes):
		if not yes:
			return
		from .xmltv import delete_own
		gone = delete_own(self.to_delete) or []
		keep = [x for x in self.selected() if x not in gone]
		cfg.xmltv_sources.value = "|".join(x for x in cfg.xmltv_sources.value.split("|") if x and x not in gone)
		cfg.xmltv_sources.save()
		save_cfg()
		self.fill(keep)

	def add(self):
		from .common import EPGChoiceBox as ChoiceBox
		from .xmltv import own_meta
		s = self.current()
		meta = own_meta()
		opts = [("Dodaj nowa liste z adresu", "new"), ("Szukaj serwera epgpl w sieci (Avahi i podsiec)", "find")]
		if s and s.epgpl:
			opts.insert(0, ("Dopasowanie kanalow (%s)" % s.desc, "mapping"))
		if s and s.origin == "wlasne":
			url = meta.get(s.file, {}).get("url")
			if url:
				opts.append(("Aktualizuj %s teraz" % s.file, "upd"))
				opts.append(("Zmien adres listy %s" % s.file, "set"))
			else:
				opts.append(("Ustaw adres listy %s (bedzie aktualizowana)" % s.file, "set"))
		if any(e.get("url") for e in meta.values()):
			opts.append(("Aktualizuj wszystkie wlasne listy", "all"))
		from .xmltv import url_history
		if url_history():
			opts.append(("Usun zapamietane adresy", "forget"))
		self.session.openWithCallback(self.add_choice, ChoiceBox, title="Listy zrodel z adresu", list=opts)

	def add_choice(self, choice):
		if not choice:
			return
		from .common import EPGChoiceBox as ChoiceBox
		from .xmltv import own_meta
		s = self.current()
		act = choice[1]
		if act == "mapping":
			from .mapping import EPGScanMapping
			self.session.open(EPGScanMapping, s.epgpl, s.desc)
			return
		if act == "find":
			return self.find_start()
		if act == "new":
			from .xmltv import url_history, fmt_age
			h = url_history()
			if h:
				used = set(e.get("url") for e in own_meta().values())
				lst = [("Wpisz nowy adres...", "")] + [("%s   (%s, %s%s)" % (x["url"], x.get("file", ""), fmt_age(x.get("used")),
					", w uzyciu" if x["url"] in used else ""), x["url"]) for x in h]
				self.session.openWithCallback(self.history_choice, ChoiceBox, title="Dodaj liste z adresu", list=lst)
				return
		if act == "forget":
			from .xmltv import url_history
			lst = [("Usun wszystkie zapamietane adresy", None)] + [(x["url"], x["url"]) for x in url_history()]
			self.session.openWithCallback(self.forget_choice, ChoiceBox, title="Usun zapamietany adres", list=lst)
			return
		if act == "upd":
			self.run_download("upd", own_meta()[s.file]["url"], s.file)
		elif act == "all":
			self.run_download("all", None, None)
		else:
			from Screens.VirtualKeyBoard import VirtualKeyBoard
			self.target = s.file if act == "set" else None
			cur = own_meta().get(s.file, {}).get("url") if act == "set" else None
			self.session.openWithCallback(self.add_cb, VirtualKeyBoard, title="Adres listy zrodel (*.sources.xml)",
				text=cur or "http://")

	# --- szukanie serwera epgpl w sieci (2.3.1) ---
	def find_start(self):
		from enigma import eTimer
		from .netfind import Finder, DEFAULT_PORT
		ports = [DEFAULT_PORT]
		try:
			from .mapping import epgpl_servers
			import re as _re
			for b, d in epgpl_servers():
				m = _re.search(r":(\d+)/", b)
				if m:
					ports.append(int(m.group(1)))
		except Exception:
			pass
		self.finder = Finder(ports)
		self.finder.start()
		self.find_timer = eTimer()
		self.find_timer.callback.append(self.find_poll)
		self.find_timer.start(500, False)
		self["keys"].setText(self.finder.text)

	def find_poll(self):
		f = self.finder
		self["keys"].setText(f.text)
		if not f.done:
			return
		self.find_timer.stop()
		self["keys"].setText(self.HELP)
		from .netfind import label
		if not f.found:
			self.session.open(MessageBox, "Nie znaleziono serwera epgpl (Avahi i podsiec odbiornika, port %s).\n"
				"Sprawdz, czy serwer dziala (http://<adres>:8088/epgpl.sources.xml), albo dodaj adres recznie." %
				", ".join(str(p) for p in f.ports), MessageBox.TYPE_INFO)
			return
		from .common import EPGChoiceBox as ChoiceBox
		from .xmltv import epgpl_current
		cur = epgpl_current().rstrip("/")
		self.session.openWithCallback(self.find_choice, ChoiceBox,
			title="Znalezione serwery epgpl - wybierz jeden (moze byc tylko jeden serwer)",
			list=[(("[uzywany] " if i["base"].rstrip("/") == cur else "") + label(i), i["url"]) for i in f.found])

	def find_choice(self, choice):
		if choice and choice[1]:
			self.confirm_epgpl(choice[1])
		elif not choice:
			self.add()

	def confirm_epgpl(self, url):
		"""Serwer epgpl moze byc tylko jeden - przed zastapieniem innego pytanie."""
		from .xmltv import epgpl_current
		cur = epgpl_current()
		base = url.rsplit("/", 1)[0] + "/"
		if cur and cur.rstrip("/") != base.rstrip("/"):
			self.session.openWithCallback(lambda yes: yes and self.run_download("new", url, None), MessageBox,
				"Skonfigurowany jest juz serwer epgpl:\n%s\n\nMoze byc tylko jeden serwer. Zastapic go serwerem:\n%s ?\n"
				"(reczne wskazania kanalow dla serwera sa na serwerze - nowy serwer ma wlasne)" % (cur, base),
				MessageBox.TYPE_YESNO, default=False)
			return
		self.run_download("new", url, None)

	def history_choice(self, choice):
		if not choice:
			return self.add()
		if choice[1]:
			if choice[1].endswith("epgpl.sources.xml"):
				return self.confirm_epgpl(choice[1])
			self.run_download("new", choice[1], None)
		else:
			from Screens.VirtualKeyBoard import VirtualKeyBoard
			self.target = None
			self.session.openWithCallback(self.add_cb, VirtualKeyBoard, title="Adres listy zrodel (*.sources.xml)",
				text="http://")

	def forget_choice(self, choice):
		if not choice:
			return self.add()
		from .xmltv import forget_url
		n = forget_url(choice[1])
		self.session.open(MessageBox, "Usunieto zapamietanych adresow: %d\n(listy zrodel i ich aktualizacja sie nie zmieniaja)" % n,
			MessageBox.TYPE_INFO, timeout=5)

	def add_cb(self, url):
		if not url or not url.strip() or url.strip() in ("http://", "https://"):
			return
		if not self.target and url.strip().endswith("epgpl.sources.xml"):
			return self.confirm_epgpl(url.strip())
		self.run_download("set" if self.target else "new", url.strip(), self.target)

	def run_download(self, act, url, name):
		self.url = url
		self.sel_now = self.selected()
		import threading
		from enigma import eTimer
		self.dl = {}

		def work():
			from .xmltv import download_source, refresh_own
			if act == "all":
				res = refresh_own()
				self.dl["res"] = (True, "\n".join("%s: %s" % r for r in res) or "Brak list z zapamietanym adresem")
			else:
				self.dl["res"] = download_source(url, name)
		self.dl_thread = threading.Thread(target=work)
		self.dl_thread.daemon = True
		self.dl_thread.start()
		self.dl_timer = eTimer()
		self.dl_timer.callback.append(self.add_poll)
		self.dl_timer.start(300, False)
		self["keys"].setText("Pobieranie: %s ..." % (url or "wszystkie wlasne listy"))

	def add_poll(self):
		if self.dl_thread.is_alive():
			return
		self.dl_timer.stop()
		ok, msg = self.dl.get("res", (False, "Blad pobierania"))
		self["keys"].setText(self.HELP)
		if ok:
			self.fill(self.sel_now)
		self.show_info()
		if ok and (self.url or "").endswith("epgpl.sources.xml"):
			# nowy serwer: ustawienia dekodera na serwer i porownanie recznych wskazan (po zamknieciu komunikatu)
			url = self.url
			msg += "\n\nDekoder wysle teraz na serwer swoje ustawienia i porowna dopasowanie kanalow."

			def after(*a):
				from .sync import new_server
				new_server(self.session, url)
			self.session.openWithCallback(after, MessageBox, msg, MessageBox.TYPE_INFO)
			return
		self.session.open(MessageBox, msg, MessageBox.TYPE_INFO if ok else MessageBox.TYPE_ERROR)

	def save(self):
		cfg.xmltv_sources.value = "|".join(self.selected())
		cfg.xmltv_sources.save()
		save_cfg()
		self.close()


class EPGScanReport(Screen):
	"""Podglad raportu zrodla XMLTV (tekst z adresu <report>, np. raport.txt z serwera epgpl)."""
	skin = """
	<screen position="center,center" size="1600,920" title="EPG Scan - raport zrodla">
		<widget name="text" position="20,10" size="1560,820" font="Regular;30" />
		<widget name="keys" position="20,845" size="1560,50" font="Regular;28" />
	</screen>"""

	def __init__(self, session, desc, url):
		Screen.__init__(self, session)
		from Components.ScrollLabel import ScrollLabel
		self.setTitle("Raport: %s" % desc)
		self.url = url
		self["text"] = ScrollLabel("Pobieranie %s ..." % url)
		self["keys"] = Label("Gora/Dol, Lewo/Prawo: przewijanie     Exit/OK: zamknij     (%s)" % url)
		self["actions"] = ActionMap(["OkCancelActions", "DirectionActions"], {
			"ok": self.close,
			"cancel": self.close,
			"up": self["text"].pageUp,
			"down": self["text"].pageDown,
			"left": self["text"].pageUp,
			"right": self["text"].pageDown,
		}, -1)
		self.res = {}
		import threading
		self.th = threading.Thread(target=self.fetch)
		self.th.daemon = True
		self.th.start()
		self.timer = eTimer()
		self.timer.callback.append(self.poll)
		self.timer.start(300, False)
		self.onClose.append(self.timer.stop)

	def fetch(self):
		import urllib.request
		try:
			req = urllib.request.Request(self.url, headers={"User-Agent": "EPGScan"})
			with urllib.request.urlopen(req, timeout=20) as r:
				self.res["text"] = r.read(1024 * 1024).decode("utf-8", "replace")
		except Exception as e:
			self.res["text"] = "Nie udalo sie pobrac raportu:\n%s\n\n%s" % (self.url, e)

	def poll(self):
		if self.th.is_alive():
			return
		self.timer.stop()
		self["text"].setText(self.res.get("text", ""))


from .common import scale_skins as _scale_skins  # noqa: E402
_scale_skins(globals())
