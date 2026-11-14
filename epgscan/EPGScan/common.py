# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-2.0-or-later
# EPG Scan - wspolne funkcje: konfiguracja, kanaly, tunery, kopie EPG

import json
import os
import re
import shutil
import time

from Components.config import config, ConfigSubsection, ConfigSelection, ConfigYesNo, \
	ConfigText, ConfigDirectory, ConfigInteger, ConfigSelectionNumber, ConfigClock
from enigma import eServiceReference, eServiceCenter, eEPGCache, iServiceInformation
from .bouquetchannels import read_bouquet_channels
from .i18n import _, configure as configure_language

try:
	from Components.NimManager import nimmanager
except Exception:
	nimmanager = None

VERSION = "2.18.0-beta.15"
LOG = "/tmp/epgscan.log"
E2 = "/etc/enigma2"
CHANNEL_PARAMS_FILE = os.path.join(E2, "epgscan", "channelparams.json")
TP_STATS = "/etc/enigma2/epgscan_tps.json"
WAKEUP_FILE = "/etc/enigma2/epgscan_wakeup"
RADIO_TYPES = {2, 10}
DAYS = ["Poniedzialek", "Wtorek", "Sroda", "Czwartek", "Piatek", "Sobota", "Niedziela"]

# stale techniczne (nie w ustawieniach)
TUNE_TIMEOUT = 30   # brak dostrojenia po tylu sekundach = blad
MAX_RETRIES = 3     # ile razy probowac transponder, ktory sie nie dostroil
COUNT_EVERY = 5     # co ile sekund liczyc zdarzenia EPG
TUNE_GAP = 2        # min. odstep miedzy kolejnymi strojeniami [s] (Unicable)
NO_TUNER_WAIT = 15  # przy braku tunera ponow probe po tylu sekundach
USAGE_EVERY = 5     # co ile sekund sprawdzac zajetosc tunerow
TIMER_AHEAD = 120   # tyle sekund przed nagraniem/przelaczeniem zwalniamy/trzymamy tuner
FREQ_TOL = 5000     # tolerancja czestotliwosci przy porownaniu transponderow [kHz]
REP_CHECK = 5       # ile kanalow transpondera sprawdzic przy wyborze kanalu do strojenia
COUNT_CAP = 80      # maks. kanalow transpondera liczonych przy postepie (duze listy)
VIDEO_RADIO = {1, 2, 4, 10, 17, 22, 25, 27, 31, 32, 134, 195, 211}
RESTORE_RATIO = 0.3 # odtworz kopie, gdy w EPG jest mniej niz 30% zdarzen z ostatniego skanu

# ---------------------------------------------------------------- konfiguracja

config.plugins.epgscan = ConfigSubsection()
cfg = config.plugins.epgscan
cfg.ui_language = ConfigSelection(default="enigma", choices=[
	("enigma", "Jezyk Enigma2"), ("pl", "Polski"), ("en", "English")])
_active_language = cfg.ui_language.value
configure_language(lambda: _active_language)


def apply_ui_language():
	global _active_language
	_active_language = cfg.ui_language.value


cfg.max_parallel = ConfigSelectionNumber(1, 8, 1, default=4)
cfg.reserve = ConfigSelectionNumber(0, 8, 1, default=1)
cfg.min_time = ConfigSelectionNumber(10, 600, 10, default=30)
cfg.idle_time = ConfigSelectionNumber(10, 600, 10, default=60)
cfg.max_time = ConfigSelectionNumber(60, 1800, 30, default=180)
CLEAR_CHOICES = [
	("none", "nie czysc (scalanie z istniejacym)"),
	("sched", "cale EPG, tylko przy skanie z harmonogramu"),
	("all", "cale EPG, przy kazdym skanie"),
	("scanned", "tylko skanowane kanaly")]
cfg.clear_mode = ConfigSelection(default="none", choices=CLEAR_CHOICES)
cfg.scope = ConfigSelection(default="all", choices=[
	("all", "wszystkie bukiety"),
	("selected", "wybrane bukiety"),
	("tps", "wszystkie transpondery (lamedb)")])
cfg.bouquets = ConfigText(default="")      # wybrane pliki bukietow oddzielone ","
cfg.known_bouquets = ConfigText(default="")  # bukiety znane przy ostatnim wyborze
cfg.last_tps = ConfigText(default="")      # transpondery z ostatniego skanu
cfg.last_channels = ConfigInteger(default=0)
cfg.backup = ConfigYesNo(default=True)
cfg.backup_dir = ConfigDirectory(default="/media/hdd/epgscan/")
cfg.backup_keep = ConfigSelectionNumber(1, 10, 1, default=3)
cfg.restore = ConfigSelection(default="auto", choices=[
	("off", "wylaczone"),
	("ask", "zapytaj"),
	("auto", "automatycznie")])
cfg.notify = ConfigYesNo(default=True)
cfg.skip_empty = ConfigYesNo(default=False)
# import EPG z satelity (skan transponderow); wylaczony = tylko XMLTV
cfg.sat_import = ConfigYesNo(default=True)
# import z satelity wlaczony, EIT wylaczone w ustawieniach EPG Enigma2: wlacz na czas skanu (i potem wylacz) albo na stale
cfg.eit_mode = ConfigSelection(default="temp", choices=[
	("temp", "na czas skanu"), ("perm", "na stale")])
# import z satelity wylaczony: wylacz EIT w Enigma2 (EPG tylko z XMLTV, bez dopisywania z ogladanego transpondera)
cfg.eit_force_off = ConfigYesNo(default=True)
cfg.skip_recheck = ConfigSelectionNumber(1, 30, 1, default=7)
# harmonogram
cfg.sched_mode = ConfigSelection(default="off", choices=[
	("off", "wylaczony"),
	("daily", "o godzinie, w wybrane dni"),
	("interval", "co N godzin")])
cfg.sched_time = ConfigClock(default=int(time.mktime((2000, 1, 1, 3, 30, 0, 0, 1, -1))))
# ConfigSubsection przyjmuje tylko elementy konfiguracji - lista trzymana osobno
SCHED_DAYS = []
for _i in range(7):
	setattr(cfg, "sched_day%d" % _i, ConfigYesNo(default=True))
	SCHED_DAYS.append(getattr(cfg, "sched_day%d" % _i))
cfg.sched_interval = ConfigSelectionNumber(1, 24, 1, default=6)
cfg.sched_when = ConfigSelection(default="standby", choices=[
	("standby", "tylko w czuwaniu (poczekaj na czuwanie)"),
	("always", "zawsze")])
cfg.sched_skip_rec = ConfigYesNo(default=False)
cfg.sched_wakeup = ConfigYesNo(default=False)
cfg.sched_deep_after = ConfigYesNo(default=True)
cfg.last_auto_run = ConfigInteger(default=0)
# skan PID-ow i wybor sciezki dzwiekowej
cfg.cp_language_source = ConfigSelection(default="enigma", choices=[
	("enigma", "preferencje jezykow z Enigma2"),
	("manual", "wlasna kolejnosc jezykow")])
cfg.cp_languages = ConfigText(default="pol,eng", fixed_size=False)
cfg.cp_group_tp = ConfigYesNo(default=True)
cfg.cp_retry_failed = ConfigYesNo(default=False)
cfg.cp_interval = ConfigSelection(default="7", choices=[(str(x), "%d dni" % x) for x in (1, 2, 4, 7)])
cfg.cp_last_run = ConfigInteger(default=0)
cfg.cp_auto_in_progress = ConfigYesNo(default=False)
cfg.cp_sync_common = ConfigSelection(default="off", choices=[
	("off", "wylaczona"), ("missing", "dopisz braki"), ("overwrite", "nadpisz wszystko")])
cfg.cp_sync_devices = ConfigSelection(default="off", choices=[
	("off", "wylaczona"), ("missing", "dopisz braki"),
	("repair", "popraw nieistniejace i dopisz braki"), ("overwrite", "nadpisz wszystko")])
cfg.cp_common_overwrite = ConfigYesNo(default=False)
cfg.cp_devices_overwrite = ConfigYesNo(default=False)
# doczytywanie brakow w ciagu dnia
cfg.topup = ConfigYesNo(default=False)
cfg.topup_mode = ConfigSelection(default="all", choices=[
	("all", "aktualizuj wszystkie transpondery"),
	("short", "tylko kanaly z konczacym sie EPG")])
cfg.topup_every = ConfigSelectionNumber(1, 12, 1, default=3)
TOPUP_CHOICES = [(str(h), "%d h" % h) for h in (2, 4, 6, 8, 10, 12, 18)] + \
	[(str(d * 24), "1 dzien" if d == 1 else "%d dni" % d) for d in range(1, 8)]
cfg.topup_hours = ConfigSelection(default="6", choices=TOPUP_CHOICES)
cfg.topup_when = ConfigSelection(default="always", choices=[
	("always", "zawsze (wolne tunery, z rezerwa)"),
	("standby", "tylko w czuwaniu")])
cfg.last_topup = ConfigInteger(default=0)
cfg.topup_fixed = ConfigYesNo(default=False)
cfg.topup_fixed_time = ConfigClock(default=int(time.mktime((2000, 1, 1, 14, 0, 0, 0, 1, -1))))
cfg.last_topup_fixed = ConfigInteger(default=0)
cfg.auto_in_progress = ConfigYesNo(default=False)   # skan z harmonogramu w toku (wykrywanie przerwania)
cfg.xmltv = ConfigYesNo(default=False)
cfg.xmltv_mode = ConfigSelection(default="tail", choices=[
	("missing", "tylko kanaly bez EPG z satelity"),
	("tail", "kanaly bez EPG + luki i dalsze dni pozostalych")])
cfg.xmltv_copies = ConfigYesNo(default=True)    # XMLTV takze dla kopii kanalow spoza bukietow (wszystkie kanaly TV z lamedb)
cfg.xmltv_sources = ConfigText(default="")    # opisy zrodel oddzielone "|"
cfg.local_grab = ConfigYesNo(default=False)                    # pobieranie programu na dekoderze
cfg.local_days = ConfigSelection(default="14", choices=[(str(x), str(x)) for x in (3, 5, 7, 10, 14)])
cfg.local_threads = ConfigSelection(default="4", choices=[(str(x), str(x)) for x in (2, 3, 4, 6)])   # stare (1.15.x)
_CONN = [(str(x), str(x)) for x in (1, 2, 3, 4, 5, 6, 8)]
cfg.local_conn_tm = ConfigSelection(default="4", choices=_CONN)
cfg.local_conn_onet = ConfigSelection(default="3", choices=_CONN)
cfg.local_conn_naz = ConfigSelection(default="2", choices=_CONN)
cfg.local_conn_wp = ConfigSelection(default="1", choices=_CONN)
cfg.local_conn_max = ConfigSelection(default="10", choices=[(str(x), str(x)) for x in (2, 4, 6, 8, 10, 12, 16, 20)])
# przerwa po kazdej stronie na kazdym polaczeniu (kultura zapytan; przy 429/503 pobieranie samo zwalnia)
cfg.local_pause = ConfigSelection(default="1", choices=[("0.3", "0,3 s"), ("0.5", "0,5 s"), ("1", "1 s"), ("2", "2 s"), ("3", "3 s")])
cfg.local_errlog = ConfigYesNo(default=True)
# serwer dla klientow streamingu (aplikacja E2Stream)
cfg.cli_api = ConfigYesNo(default=True)
cfg.cli_port = ConfigInteger(default=8770, limits=(1024, 65535))
cfg.cli_token = ConfigText(default="", fixed_size=False)
cfg.cli_pack = ConfigYesNo(default=True)                         # paczka EPG/ikon/referencji dla E2Stream po pracy z harmonogramu
cfg.cli_pack_last = ConfigText(default="", fixed_size=False)     # "czas|wynik" ostatniej paczki
# aktualizacja wtyczki z wydan GitHub (reczna dziala zawsze)
cfg.upd_auto = ConfigYesNo(default=False)
cfg.upd_channel = ConfigSelection(default="stable", choices=[
	("stable", "stabilne"), ("beta", "beta i stabilne")])
cfg.upd_last = ConfigInteger(default=0, limits=(0, 4000000000))      # ostatnie sprawdzenie automatyczne
cfg.upd_pending = ConfigText(default="", fixed_size=False)           # zainstalowana wersja czekajaca na restart


def local_conns():
	return {"tm": cfg.local_conn_tm.value, "onet": cfg.local_conn_onet.value, "naz": cfg.local_conn_naz.value,
		"wp": cfg.local_conn_wp.value}
cfg.xmltv_gaps = ConfigSelection(default="start", choices=[      # audycje zastepcze w przerwach XMLTV
	("off", "nie"),
	("start", "od teraz do pierwszej audycji"),
	("all", "takze przerwy od 30 min")])
XB_CHOICES = [("all", "wszystkie bukiety (takze IPTV)"), ("selected", "wybrane bukiety"),
	("scan", "bukiety do skanowania")]
cfg.xmltv_bouquets = ConfigSelection(default="all", choices=XB_CHOICES)   # kanaly dla XMLTV i mapy kanalow dekodera
cfg.xmltv_bq_sel = ConfigText(default="")       # wybrane bukiety dla XMLTV (pliki oddzielone ",")
cfg.xmltv_bq_asked = ConfigYesNo(default=False)  # wybor bukietow dla mapy kanalow juz byl (pierwsze otwarcie dopasowania)
cfg.epgpl_grab = ConfigSelection(default="ready", choices=[     # serwer epgpl przy skanie z harmonogramu
	("ready", "bierz gotowy plik z serwera"),
	("grab", "uruchom pobieranie na serwerze")])
cfg.log_place = ConfigSelection(default="backup", choices=[
	("backup", "w katalogu kopii EPG"),
	("tmp", "w /tmp (znika przy restarcie dekodera)")])
cfg.last_events = ConfigInteger(default=0)
cfg.last_summary = ConfigText(default="")
cfg.last_fetch = ConfigText(default="")       # ostatnie pobranie programu (dekoder) - ekran glowny
cfg.last_import = ConfigText(default="")      # ostatni import XMLTV - ekran glowny

# --- 2.0: profil (Akcje / OK / harmonogram) i zrodla programu ---
cfg.p_dek = ConfigYesNo(default=False)        # zrodlo: plik pobrany przez dekoder
SRV_CHOICES = [("zapas", "zapas (gotowy plik serwera)"), ("zawsze", "zawsze (serwer tez pobiera)"), ("nie", "nie")]
cfg.p_srv = ConfigSelection(default="zapas", choices=SRV_CHOICES)
cfg.p_other = ConfigYesNo(default=True)       # pozostale zaznaczone zrodla XMLTV
cfg.p_fetch = ConfigYesNo(default=True)       # pobierz swieze dane (dekoder / serwer) przed importem
cfg.p_one = ConfigYesNo(default=True)         # ... w jednym miejscu: gdy druga strona ma dzisiejsza pelna paczke - ona zamiast pobierania
cfg.p_channelparams = ConfigYesNo(default=False)  # skan PAT/PMT jako etap recznego profilu
cfg.src_order = ConfigSelection(default="dso", choices=[
	("dso", "dekoder, serwer, pozostale"), ("dos", "dekoder, pozostale, serwer"),
	("sdo", "serwer, dekoder, pozostale"), ("sod", "serwer, pozostale, dekoder"),
	("ods", "pozostale, dekoder, serwer"), ("osd", "pozostale, serwer, dekoder")])
SITE_TAGS = ("tm", "onet", "naz", "wp")
SITE_LABEL = {"tm": "teleman", "onet": "onet", "naz": "naziemna", "wp": "WP"}


def _site_choices():
	import itertools
	return [(",".join(p), ", ".join(SITE_LABEL[t] for t in p)) for p in itertools.permutations(SITE_TAGS)]


# kolejnosc serwisow (pierwszenstwo stacji w mapie kanalow) - dekoder jest centrum ustawien, serwer epgpl dostaje ja
cfg.site_order = ConfigSelection(default="tm,onet,naz,wp", choices=_site_choices())
cfg.one_place = ConfigYesNo(default=True)   # harmonogram: pobieranie programu w jednym miejscu (dekoder albo serwer - kto pierwszy)
cfg.cand_night = ConfigYesNo(default=False)  # codziennie (po pobraniu) program na dobe dla kandydatow z okna wyboru stacji
cfg.map_diff = ConfigSelection(default="ask", choices=[("ask", "wybor za kazdym razem"), ("dekoder", "wygrywa dekoder"),
	("serwer", "wygrywa serwer"), ("keep", "pozostaw rozne")])   # roznice dopasowania dekoder <-> serwer epgpl
cfg.map_sort = ConfigSelection(default="bq", choices=[("bq", "zgodnie z bukietami"), ("abc", "alfabetycznie")])   # lista dopasowania kanalow


def site_order():
	return [t for t in cfg.site_order.value.split(",") if t in SITE_TAGS] or list(SITE_TAGS)


cfg.sched_on = ConfigYesNo(default=True)      # wylacznik harmonogramu (ustawienia zostaja)
SW_CHOICES = [("nie", "nie"), ("zmiany", "zmiany (satellites.xml, zmienione TP, bukiety)"), ("pelny", "pelny skan transponderow")]
cfg.p_sw = ConfigSelection(default="nie", choices=SW_CHOICES)   # krok SatWatch przed skanem EPG
cfg.last_sw = ConfigText(default="")
cfg.night_note = ConfigText(default="")       # podsumowanie nocy (okienko po wlaczeniu odbiornika)
cfg.rs_mode = ConfigSelection(default="need", choices=[
	("never", "nigdy"), ("need", "gdy potrzebny"), ("always", "zawsze")])
cfg.rs_days = ConfigSelection(default="0", choices=[(str(x), "nie" if x == 0 else str(x)) for x in (0, 3, 5, 7, 14, 30)])
cfg.rs_mem = ConfigSelection(default="100", choices=[(str(x), str(x)) for x in (50, 80, 100, 150, 200)])
cfg.last_restart = ConfigInteger(default=0)
cfg.hang_restart = ConfigInteger(default=0)   # ostatni restart z powodu zawieszonego tunera
# sprzatanie dysku: stare pliki timeshift i nadmiar crash logow
cfg.cl_on = ConfigYesNo(default=False)
cfg.cl_dir = ConfigText(default="/media/hdd")      # katalogi sprzatania, rozdzielone "|"
cfg.cl_dirs_own = ConfigText(default="")         # katalogi dopisane recznie (lista wyboru), "|"
cfg.cl_ts_hours = ConfigSelection(default="6", choices=[(str(x), str(x)) for x in (1, 2, 3, 6, 12, 24)])
cfg.cl_crash_keep = ConfigSelection(default="10", choices=[(str(x), str(x)) for x in (3, 5, 10, 20, 50)])
cfg.cl_e2s_days = ConfigSelection(default="7", choices=[("0", "nie usuwaj")] + [(str(x), str(x)) for x in (1, 3, 7, 14, 30)])  # zrzuty TS z E2Stream
cfg.cl_every = ConfigSelection(default="15", choices=[(str(x), str(x)) for x in (5, 15, 30, 60)])
cfg.map_mode = ConfigSelection(default="dekoder", choices=[   # skad dekoder bierze mape kanalow
	("serwer", "z serwera epgpl (kopia na dysku)"), ("dekoder", "budowana na dekoderze")])
cfg.v2_migrated = ConfigYesNo(default=False)


def migrate_v2():
	"""Jednorazowo z 1.x: pobieranie na dekoderze / serwer przy harmonogramie -> profil."""
	if cfg.v2_migrated.value:
		return
	try:
		cfg.p_dek.value = bool(cfg.local_grab.value)
		cfg.p_srv.value = "zawsze" if cfg.epgpl_grab.value == "grab" else "zapas"
		cfg.p_fetch.value = True
		if cfg.sched_mode.value == "off":
			cfg.sched_on.value = False
		cfg.v2_migrated.value = True
		for x in (cfg.p_dek, cfg.p_srv, cfg.p_fetch, cfg.sched_on, cfg.v2_migrated):
			x.save()
		save_cfg()
		log("2.0: ustawienia przeniesione do profilu (dekoder %s, serwer %s)" % (cfg.p_dek.value, cfg.p_srv.value))
	except Exception as e:
		log("2.0: migracja ustawien nieudana: %s" % e)


def profile_default():
	"""Profil startu: OK na ekranie glownym i harmonogram (Akcje -> Zapisz jako domyslne)."""
	return {"sw": cfg.p_sw.value, "clear": cfg.clear_mode.value, "sat": bool(cfg.sat_import.value), "dek": bool(cfg.p_dek.value),
		"srv": cfg.p_srv.value, "other": bool(cfg.p_other.value), "fetch": bool(cfg.p_fetch.value),
		"one": bool(cfg.p_one.value and cfg.one_place.value), "channelparams": bool(cfg.p_channelparams.value)}


def profile_text(p, localized=False):
	tr = _ if localized else lambda text: text
	order = {"d": tr("dekoder"), "s": tr("serwer"), "o": tr("pozostale")}
	on = {"d": p["dek"], "s": p["srv"] != "nie", "o": p["other"]}
	parts = (["SatWatch (%s)" % tr(p["sw"])] if p.get("sw", "nie") != "nie" else []) + ([tr("satelita")] if p["sat"] else []) + ["%s%s" % (order[c], tr(" (zapas)") if c == "s" and p["srv"] == "zapas" else "")
		for c in cfg.src_order.value if on[c]]
	if p.get("channelparams"):
		parts.append(tr("skan parametrow kanalow"))
	return " -> ".join(parts) or tr("nic")


def xmltv_in_profile(p):
	return bool(cfg.xmltv.value and (p["dek"] or p["srv"] != "nie" or (p["other"] and cfg.xmltv_sources.value)))


# migracja z 1.4.0: zapisany wybor bukietow = tryb "wybrane"
if getattr(cfg.scope, "saved_value", None) is None and cfg.bouquets.value:
	cfg.scope.value = "selected"


def on_mounted_disk(path):
	"""True, gdy sciezka lezy na zamontowanym nosniku (nie na pustym punkcie montowania w pamieci flash)."""
	p = os.path.abspath(path)
	while p and p != "/":
		if os.path.ismount(p):
			return True
		p = os.path.dirname(p)
	return not path.startswith("/media/")


def log_path():
	"""Log w katalogu kopii EPG (gdy istnieje/da sie utworzyc), inaczej w /tmp."""
	if cfg.log_place.value == "backup":
		d = cfg.backup_dir.value or "/media/hdd/epgscan/"
		try:
			if on_mounted_disk(d):
				if not os.path.isdir(d):
					os.makedirs(d)
				return os.path.join(d, "epgscan.log")
		except Exception:
			pass
	return LOG


def log_count():
	try:
		return max(2, int(cfg.backup_keep.value))
	except Exception:
		return 2


def rotate_logs():
	"""epgscan.log -> .1 -> .2 ...; zostaje tyle logow, ile kopii EPG (min. 2)."""
	p = log_path()
	n = log_count()
	try:
		for i in range(n - 1, 0, -1):
			src = p if i == 1 else "%s.%d" % (p, i - 1)
			if os.path.exists(src):
				os.rename(src, "%s.%d" % (p, i))
		for extra in range(n, n + 10):
			if os.path.exists("%s.%d" % (p, extra)):
				os.remove("%s.%d" % (p, extra))
	except Exception:
		pass


def log(msg):
	line = "%s %s" % (time.strftime("%H:%M:%S"), msg)
	print("[EPGScan] " + msg)
	try:
		with open(log_path(), "a") as f:
			f.write(line + "\n")
	except Exception:
		pass


def eit_on():
	"""Czy Enigma2 przyjmuje EPG z satelity (EIT). None, gdy obraz nie ma tego ustawienia."""
	try:
		from Components.config import config
		return bool(config.epg.eit.value)
	except Exception:
		return None


def ensure_eit():
	"""Przed skanem: wlacza EIT, jesli wylaczone. Zwraca True, gdy po skanie trzeba je z powrotem wylaczyc."""
	try:
		from Components.config import config, configfile
		e = config.epg.eit
	except Exception:
		return False
	if e.value:
		return False
	e.value = True                      # powiadomienie w Enigma2 od razu wlacza odbior EIT (setEpgSources)
	if cfg.eit_mode.value == "perm":
		e.save()
		configfile.save()
		log("EIT bylo wylaczone w ustawieniach EPG - wlaczone na stale")
		return False
	log("EIT bylo wylaczone w ustawieniach EPG - wlaczone na czas skanu")
	return True


def apply_eit_policy():
	"""Import z satelity wylaczony + wymuszanie: EIT w Enigma2 wylaczone na stale."""
	if cfg.sat_import.value or not cfg.eit_force_off.value:
		return
	try:
		from Components.config import config, configfile
		e = config.epg.eit
	except Exception:
		return
	if e.value:
		e.value = False
		e.save()
		configfile.save()
		log("Import z satelity wylaczony - EIT w ustawieniach EPG wylaczone (wymuszanie w EPG Scan)")


def restore_eit():
	"""Po skanie: wylacza EIT wlaczone tylko na czas skanu (ustawienie zapisane w Enigma2 nie bylo zmieniane)."""
	try:
		from Components.config import config
		config.epg.eit.value = False
		log("EIT wylaczone z powrotem (jak w ustawieniach EPG)")
	except Exception as e:
		log("Nie udalo sie wylaczyc EIT: %s" % e)


def pause_eit_for_xmltv():
	try:
		from Components.config import config
		e = config.epg.eit
	except (ImportError, AttributeError) as e:
		log("XMLTV: nie mozna sterowac EIT w tym obrazie: %s" % e)
		return False
	if not e.value:
		return False
	try:
		e.value = False
	except Exception:
		e.value = True
		raise
	log("XMLTV: EIT wylaczone na czas importu")
	return True


def resume_eit_after_xmltv():
	from Components.config import config
	config.epg.eit.value = True
	log("XMLTV: przywrocono EIT po imporcie")


def save_cfg():
	try:
		from Components.config import configfile
		cfg.save()
		configfile.save()
	except Exception:
		pass


# ---------------------------------------------------------------- bukiety i kanaly

def list_bouquets():
	"""[(plik, nazwa)] bukietow TV z bouquets.tv."""
	out = []
	try:
		with open(os.path.join(E2, "bouquets.tv"), encoding="utf-8", errors="ignore") as f:
			files = re.findall(r'FROM BOUQUET "([^"]+)"', f.read())
	except Exception:
		files = []
	for fname in files:
		name = fname
		try:
			with open(os.path.join(E2, fname), encoding="utf-8", errors="ignore") as f:
				first = f.readline().strip()
			if first.startswith("#NAME "):
				name = first[6:].strip()
		except Exception:
			pass
		out.append((fname, name))
	return out


def selected_bouquets():
	allb = [b[0] for b in list_bouquets()]
	if cfg.scope.value in ("all", "tps"):
		return allb
	sel = [x for x in cfg.bouquets.value.split(",") if x]
	return [x for x in allb if x in sel]


def xmltv_bouquet_files():
	"""Bukiety, ktore dostaja program z internetu i sa dopasowywane w mapie kanalow dekodera."""
	allb = [b[0] for b in list_bouquets()]
	m = cfg.xmltv_bouquets.value
	if m == "scan":
		return selected_bouquets()
	if m == "selected":
		sel = [x for x in cfg.xmltv_bq_sel.value.split(",") if x]
		return [x for x in allb if x in sel]
	return allb


def xmltv_bouquets_text():
	m = cfg.xmltv_bouquets.value
	if m == "selected":
		return "wybrane bukiety (%d)" % len(xmltv_bouquet_files())
	return dict(XB_CHOICES).get(m, m)


def new_bouquets():
	"""Bukiety, ktore pojawily sie od ostatniego wyboru (tylko tryb "wybrane")."""
	if cfg.scope.value != "selected":
		return []
	known = [x for x in cfg.known_bouquets.value.split(",") if x]
	if not known:
		return []
	return [b for b in list_bouquets() if b[0] not in known]


def tp_key(ref):
	p = ref.split(":")
	try:
		return (int(p[4], 16), int(p[5], 16), int(p[6], 16))
	except Exception:
		return None


def bouquet_refs(files):
	"""Kanaly DVB z podanych bukietow, pogrupowane wg transpondera (w kolejnosci)."""
	groups = {}
	seen = set()

	def walk(fname):
		if fname in seen:
			return
		seen.add(fname)
		try:
			with open(os.path.join(E2, fname), encoding="utf-8", errors="ignore") as f:
				lines = f.read().splitlines()
		except Exception:
			return
		for line in lines:
			if not line.startswith("#SERVICE "):
				continue
			ref = line[9:].strip()
			m = re.search(r'FROM BOUQUET "([^"]+)"', ref)
			if m:
				walk(m.group(1))
				continue
			p = ref.split(":")
			if len(p) < 10 or p[0] != "1" or p[1] == "64" or p[3] == "0" or "%3a" in ref.lower():
				continue
			ref = ":".join(p[:10]) + ":"
			k = tp_key(ref)
			if k is not None:
				lst = groups.setdefault(k, [])
				if ref not in lst:
					lst.append(ref)

	for f in files:
		walk(f)
	return groups


def channel_params_bouquet_channels(files):
	"""Wszystkie pozycje kanalow bukietu, lacznie z IPTV i nieobslugiwanymi ref."""
	return read_bouquet_channels(files, E2, service_name)


_lamedb = {"mtime": None, "groups": {}}


def lamedb_groups():
	"""{tp_key: [ref,...]} wszystkich kanalow TV/radio z lamedb (z pamiecia podreczna)."""
	path = None
	for p in ("lamedb5", "lamedb"):
		if os.path.exists(os.path.join(E2, p)):
			path = os.path.join(E2, p)
			break
	if path is None:
		return {}
	try:
		mt = os.path.getmtime(path)
	except Exception:
		mt = None
	if mt == _lamedb["mtime"]:
		return _lamedb["groups"]
	groups = {}

	def add(sid, ns, tsid, onid, stype):
		try:
			st = int(stype)
			if st not in VIDEO_RADIO:
				return
			ref = "1:0:%X:%X:%X:%X:%X:0:0:0:" % (st, int(sid, 16), int(tsid, 16), int(onid, 16), int(ns, 16))
			k = (int(tsid, 16), int(onid, 16), int(ns, 16))
		except Exception:
			return
		groups.setdefault(k, []).append(ref)

	try:
		with open(path, encoding="utf-8", errors="ignore") as f:
			lines = f.read().splitlines()
		if path.endswith("lamedb5"):
			for line in lines:
				if line.startswith("s:"):
					p = line[2:].split(",")[0].split(":")
					if len(p) >= 5:
						add(p[0], p[1], p[2], p[3], p[4])
		else:
			i = lines.index("services") + 1
			while i < len(lines) and lines[i] != "end":
				p = lines[i].split(":")
				if len(p) >= 5:
					add(p[0], p[1], p[2], p[3], p[4])
				i += 3
	except Exception as e:
		log("Blad odczytu %s: %s" % (path, e))
	_lamedb["mtime"] = mt
	_lamedb["groups"] = groups
	return groups


def service_name(ref):
	name = ref
	try:
		sref = eServiceReference(ref)
		info = eServiceCenter.getInstance().info(sref)
		if info:
			name = info.getName(sref) or ref
	except Exception:
		pass
	return name.replace("\xc2\x86", "").replace("\xc2\x87", "").replace("\x86", "").replace("\x87", "")


def tp_params(ref):
	"""(czestotliwosc, polaryzacja, pozycja) transpondera kanalu z lamedb."""
	try:
		sref = eServiceReference(ref)
		info = eServiceCenter.getInstance().info(sref)
		d = info.getInfoObject(sref, iServiceInformation.sTransponderData) if info else None
		if d and d.get("frequency"):
			return (d.get("frequency"), d.get("polarization"), d.get("orbital_position"))
	except Exception:
		pass
	return None


def same_tp(a, b):
	if not a or not b or a[0] is None or b[0] is None:
		return False
	if abs(int(a[0]) - int(b[0])) > FREQ_TOL:
		return False
	if a[1] is not None and b[1] is not None and a[1] != b[1]:
		return False
	if a[2] is not None and b[2] is not None and a[2] != b[2]:
		return False
	return True


def count_detail(refs):
	"""{ref: liczba zdarzen} dla listy kanalow."""
	cache = eEPGCache.getInstance()
	out = {}
	for ref in refs:
		try:
			res = cache.lookupEvent(["I", (ref, 0, -1, -1)])
			out[ref] = len([r for r in res if r and r[0]]) if res else 0
		except Exception:
			out[ref] = 0
	return out


def count_events(refs):
	"""Zwraca (liczba zdarzen, liczba kanalow z EPG) dla listy kanalow."""
	cache = eEPGCache.getInstance()
	total = 0
	covered = 0
	for ref in refs:
		try:
			res = cache.lookupEvent(["I", (ref, 0, -1, -1)])
			n = len([r for r in res if r and r[0]]) if res else 0
		except Exception:
			n = 0
		total += n
		if n:
			covered += 1
	return total, covered


class Job(object):
	def __init__(self, ref, name, refs, all_refs):
		self.ref = ref
		self.name = name
		self.refs = refs              # kanaly z bukietow
		self.all_refs = all_refs      # wszystkie kanaly transpondera (lamedb + bukiety)
		self.tp = tp_params(ref)
		self.reset()

	def reset(self):
		self.state = "czeka"
		self.rec = None
		self.t0 = 0
		self.tuned_at = 0
		self.last_change = 0
		self.events = 0
		self.events_start = 0
		self.covered = 0
		self.all_events = 0
		self.all_start = 0
		self.all_covered = 0
		self.tuner = "-"
		self.tuner_no = -1
		self.shared = False
		self.retries = 0
		self.duration = 0
		self.skip = getattr(self, "skip", False)
		self.last_count = 0
		self.not_before = 0


def _stype(ref):
	try:
		return int(ref.split(":")[2], 16)
	except Exception:
		return 1


def build_jobs():
	"""Jeden kanal na transponder; preferuje kanal z bukietow, ktory ma EPG.
	Zakres "tps" dodaje transpondery z lamedb bez kanalow w bukietach."""
	jobs = []
	lame = lamedb_groups()
	bq = bouquet_refs(selected_bouquets())
	keys = list(bq.keys())
	if cfg.scope.value == "tps":
		keys += [k for k in lame.keys() if k not in bq]
	key = lambda r: tuple(r.split(":")[3:7])
	for k in keys:
		refs = list(bq.get(k, []))
		have = set(key(r) for r in refs)
		extra = [r for r in lame.get(k, []) if key(r) not in have]
		cand = refs or [r for r in extra if _stype(r) not in RADIO_TYPES] or extra
		if not cand:
			continue
		rep = cand[0]
		for r in cand[:REP_CHECK]:
			if count_events([r])[0] > 0:
				rep = r
				break
		all_refs = (refs + extra)[:max(COUNT_CAP, len(refs))]
		jobs.append(Job(rep, service_name(rep), refs, all_refs))
	apply_skip(jobs)
	return jobs


# ---------------------------------------------------------------- statystyki transponderow

def load_tp_stats():
	try:
		with open(TP_STATS) as f:
			return json.load(f)
	except Exception:
		return {}


def save_tp_stats(stats):
	try:
		with open(TP_STATS, "w") as f:
			json.dump(stats, f)
	except Exception as e:
		log("Zapis %s nieudany: %s" % (TP_STATS, e))


def apply_skip(jobs):
	"""Oznacza transpondery bez EPG (2 skany z rzedu) do pominiecia, gdy opcja wlaczona."""
	for j in jobs:
		j.skip = False
	if not cfg.skip_empty.value:
		return
	stats = load_tp_stats()
	now = time.time()
	for j in jobs:
		st = stats.get(tp_id(tp_key(j.ref)))
		if st and st.get("zero", 0) >= 2 and now - st.get("check", 0) < cfg.skip_recheck.value * 86400:
			j.skip = True


def update_tp_stats(jobs):
	stats = load_tp_stats()
	now = int(time.time())
	for j in jobs:
		if not j.state.startswith("gotowe"):
			continue
		k = tp_id(tp_key(j.ref))
		st = stats.get(k, {"zero": 0, "check": 0})
		st["zero"] = st.get("zero", 0) + 1 if j.all_events == 0 else 0
		st["check"] = int(getattr(j, "done_at", 0) or now)
		stats[k] = st
	save_tp_stats(stats)


def horizons(refs):
	"""{ref: koniec ostatniej audycji} - do pomiaru, czy doczytywanie wydluzylo EPG."""
	cache = eEPGCache.getInstance()
	out = {}
	for ref in refs:
		try:
			res = cache.lookupEvent(["BD", (ref, 0, -1, -1)]) or []
		except Exception:
			res = []
		ends = [int(r[0]) + int(r[1] or 0) for r in res if r and r[0] is not None]
		out[ref] = max(ends) if ends else 0
	return out


TOPUP_MAX_GAP = 24     # maks. odstep [h] dla transpondera, ktory od dawna nic nie daje
TOPUP_WIN_DAYS = 3     # transponder z zyskiem w tylu ostatnich dniach: odstep najwyzej 2x podstawowy


def update_topup_stats(jobs):
	"""Po doczytywaniu: transponder bez wydluzenia EPG dostaje 2x dluzszy odstep (max 24 h),
	z wydluzeniem - wraca do podstawowego. Zwraca liste opisow do logu."""
	stats = load_tp_stats()
	base = cfg.topup_every.value
	notes = []
	for j in jobs:
		if not j.state.startswith("gotowe"):
			continue
		k = tp_id(tp_key(j.ref))
		st = stats.get(k, {"zero": 0, "check": 0})
		gap = st.get("topup_gap", base)
		if getattr(j, "gained", 0) > 0:
			gap = base
			st["topup_win"] = int(time.time())
		else:
			recent_win = time.time() - st.get("topup_win", 0) < TOPUP_WIN_DAYS * 86400
			cap = base * 2 if recent_win else TOPUP_MAX_GAP
			gap = min(max(gap, base) * 2, cap)
			notes.append("%s: bez nowych danych - kolejna proba za %d h%s" % (
				j.name, gap, " (zysk w ostatnich dniach - odstep najwyzej 2x)" if recent_win else ""))
		st["topup_gap"] = gap
		st["topup_last"] = int(getattr(j, "done_at", 0) or time.time())
		stats[k] = st
	save_tp_stats(stats)
	return notes


def tp_id(k):
	return "%X:%X:%X" % k


def changes_since_last(jobs):
	"""Tekst o zmianach od ostatniego skanu (nowe/usuniete transpondery, kanaly)."""
	last = set(x for x in cfg.last_tps.value.split(",") if x)
	if not last:
		return ""
	now = set(tp_id(tp_key(j.ref)) for j in jobs)
	ch = sum(len(j.refs) for j in jobs)
	parts = []
	if now - last:
		parts.append("+%d transp." % len(now - last))
	if last - now:
		parts.append("-%d transp." % len(last - now))
	d = ch - cfg.last_channels.value
	if d:
		parts.append("%+d kanalow" % d)
	return ("Zmiany od ostatniego skanu: " + ", ".join(parts)) if parts else ""


def remember_scan(jobs):
	cfg.last_tps.value = ",".join(tp_id(tp_key(j.ref)) for j in jobs)
	cfg.last_channels.value = sum(len(j.refs) for j in jobs)


def all_refs():
	refs = []
	for lst in bouquet_refs(selected_bouquets()).values():
		refs.extend(lst)
	return refs


# ---------------------------------------------------------------- tunery

def configured_tuners():
	n = 0
	try:
		for slot in nimmanager.nim_slots:
			if not slot.isCompatible("DVB-S"):
				continue
			mode = getattr(slot, "config_mode", None)
			if mode is None:
				try:
					mode = slot.config.dvbs.configMode.value
				except Exception:
					mode = "simple"
			if mode != "nothing":
				n += 1
	except Exception:
		pass
	return n or 1


def frontend_of(service):
	"""(numer tunera, parametry transpondera) - tylko dane zapisane w Enigma2,
	bez odczytu stanu sygnalu ze sterownika (to blokowalo glowna petle)."""
	try:
		fe = service.frontendInfo()
		if fe is None:
			return -1, None
		fd = fe.getFrontendData() or {}
		no = fd.get("tuner_number", -1)
		if no is None:
			no = -1
		params = None
		td = fe.getTransponderData(True) or {}
		if td.get("frequency"):
			params = (td.get("frequency"), td.get("polarization"), td.get("orbital_position"))
		return no, params
	except Exception:
		return -1, None


def epgrefresh_active():
	try:
		return bool(config.plugins.epgrefresh.enabled.value)
	except Exception:
		return False


# ---------------------------------------------------------------- czyszczenie EPG

_flush_single_ok = None


def flush_all():
	try:
		eEPGCache.getInstance().flushEPG()
		log("Wyczyszczono cale EPG")
		return True
	except Exception as e:
		log("Czyszczenie EPG nieudane: %s" % e)
		return False


def single_flush_supported():
	"""Czy obraz pozwala czyscic EPG pojedynczego kanalu. Test na nieistniejacym kanale - nic nie usuwa."""
	global _flush_single_ok
	if _flush_single_ok is not None:
		return _flush_single_ok
	cache = eEPGCache.getInstance()
	try:
		cache.flushEPG(eServiceReference("1:0:1:0:0:0:0:0:0:0:"))
		_flush_single_ok = True
	except Exception:
		try:
			cache.flushEPG(0, 0, 0)
			_flush_single_ok = True
		except Exception:
			_flush_single_ok = False
	return _flush_single_ok


def apply_clear_choices():
	"""Ukrywa "tylko skanowane kanaly", gdy obraz tego nie obsluguje (z migracja ustawienia)."""
	if single_flush_supported():
		return
	was = cfg.clear_mode.value
	try:
		cfg.clear_mode.setChoices([c for c in CLEAR_CHOICES if c[0] != "scanned"], default="none")
	except Exception:
		pass
	if was == "scanned":
		cfg.clear_mode.value = "none"
		cfg.clear_mode.save()
		save_cfg()
		log('Czyszczenie pojedynczych kanalow nieobslugiwane w tym obrazie - ustawiono "nie czysc"')


def clears_now(auto, mode=None):
	"""Czy skan ma wyczyscic cale EPG (z uwzglednieniem trybu "tylko przy harmonogramie")."""
	m = mode or cfg.clear_mode.value
	return m == "all" or (m == "sched" and auto)


def flush_refs(refs):
	"""Czysci EPG podanych kanalow. Zwraca False, gdy obraz tego nie obsluguje."""
	global _flush_single_ok
	if _flush_single_ok is False:
		return False
	cache = eEPGCache.getInstance()
	for ref in refs:
		p = ref.split(":")
		done = False
		try:
			cache.flushEPG(eServiceReference(ref))
			done = True
		except Exception:
			try:
				cache.flushEPG(int(p[3], 16), int(p[5], 16), int(p[4], 16))
				done = True
			except Exception:
				pass
		if not done:
			if _flush_single_ok is None:
				log("Czyszczenie pojedynczych kanalow nie jest obslugiwane w tym obrazie - pomijam")
			_flush_single_ok = False
			return False
	_flush_single_ok = True
	return True


# ---------------------------------------------------------------- kopie EPG

def epg_path():
	try:
		return config.misc.epgcache_filename.value
	except Exception:
		return "/media/hdd/epg.dat"


def backup_dir():
	d = cfg.backup_dir.value or "/media/hdd/epgscan/"
	return d if d.endswith("/") else d + "/"


def list_backups():
	d = backup_dir()
	try:
		files = [f for f in os.listdir(d) if f.startswith("epg-") and f.endswith(".dat")]
	except Exception:
		return []
	files.sort(reverse=True)
	return [os.path.join(d, f) for f in files]


def copy_backup():
	"""Kopiuje aktualny plik EPG do katalogu kopii (po wczesniejszym save())."""
	src = epg_path()
	if not os.path.exists(src) or os.path.getsize(src) < 1024:
		log("Kopia EPG pominieta: brak lub pusty plik %s" % src)
		return None
	d = backup_dir()
	if not on_mounted_disk(d):
		log("Kopia EPG pominieta: nosnik dla %s nie jest zamontowany" % d)
		return None
	try:
		if not os.path.isdir(d):
			os.makedirs(d)
		dst = os.path.join(d, "epg-%s.dat" % time.strftime("%Y%m%d-%H%M%S"))
		shutil.copy2(src, dst)
		for old in list_backups()[cfg.backup_keep.value:]:
			try:
				os.remove(old)
			except Exception:
				pass
		log("Kopia EPG: %s (%d kB)" % (dst, os.path.getsize(dst) // 1024))
		return dst
	except Exception as e:
		log("Kopia EPG nieudana: %s" % e)
		return None


def restore_backup(path):
	"""Wczytuje kopie: podmienia plik EPG i laduje go do pamieci."""
	dst = epg_path()
	try:
		if os.path.abspath(path) != os.path.abspath(dst):
			shutil.copy2(path, dst)
		eEPGCache.getInstance().load()
		log("Odtworzono EPG z kopii %s" % path)
		return True
	except Exception as e:
		log("Odtworzenie EPG nieudane: %s" % e)
		return False


def backup_label(path):
	try:
		st = os.stat(path)
		return "%s  (%d kB)" % (time.strftime("%Y-%m-%d %H:%M", time.localtime(st.st_mtime)), st.st_size // 1024)
	except Exception:
		return os.path.basename(path)


# ---------------------------------------------------------------- harmonogram

def _at(day_ts, h, m):
	t = time.localtime(day_ts)
	return int(time.mktime((t.tm_year, t.tm_mon, t.tm_mday, h, m, 0, 0, 0, -1)))


def last_slot(now):
	"""Ostatni zaplanowany moment <= now (tryb daily), albo 0."""
	h, m = cfg.sched_time.value
	for d in range(0, 8):
		ts = _at(now - d * 86400, h, m)
		if ts <= now and SCHED_DAYS[time.localtime(ts).tm_wday].value:
			return ts
	return 0


def next_run(now=None):
	"""Nastepny planowany start (timestamp) albo 0, gdy harmonogram wylaczony."""
	now = int(now or time.time())
	if not cfg.sched_on.value:
		return 0
	mode = cfg.sched_mode.value
	if mode == "daily":
		h, m = cfg.sched_time.value
		for d in range(0, 8):
			ts = _at(now + d * 86400, h, m)
			if ts > now and SCHED_DAYS[time.localtime(ts).tm_wday].value:
				return ts
		return 0
	if mode == "interval":
		last = cfg.last_auto_run.value
		return max(last + cfg.sched_interval.value * 3600, now + 60) if last else now + 60
	return 0


def sched_active():
	return bool(cfg.sched_on.value and cfg.sched_mode.value != "off")


def is_due(now=None):
	now = int(now or time.time())
	if not cfg.sched_on.value:
		return False
	mode = cfg.sched_mode.value
	if mode == "daily":
		slot = last_slot(now)
		return bool(slot) and slot > cfg.last_auto_run.value and now - slot < 12 * 3600
	if mode == "interval":
		return now - cfg.last_auto_run.value >= cfg.sched_interval.value * 3600
	return False


def fmt_ts(ts):
	return time.strftime("%a %d.%m %H:%M", time.localtime(ts)) if ts else "-"


# ---------------------------------------------------------------- przyciski

KEY_COLORS = (("red", "#9f1313"), ("green", "#1f771f"), ("yellow", "#a08000"), ("blue", "#18188b"))
KEY_EXIT = ("exit", "#505050")


# --- skalowanie okien: skorki EPG Scan sa rysowane dla 1920x1080 (FHD); przy skorce HD (1280x720) albo innej
#     wszystkie polozenia, rozmiary i czcionki sa przeliczane wg szerokosci pulpitu Enigma2 ---
def _ui_scale():
	try:
		from enigma import getDesktop
		w = getDesktop(0).size().width()
		return w / 1920.0 if w else 1.0
	except Exception:
		return 1.0


UI_SCALE = _ui_scale()


def S(v):
	"""Rozmiar w pikselach FHD -> pikseli biezacej skorki."""
	return max(1, int(round(v * UI_SCALE))) if v else 0


_SKIN_ATTR = re.compile(r'\b(position|size|font|secondfont|itemHeight|borderWidth)="([^"]*)"')


def scale_skin(skin):
	if abs(UI_SCALE - 1.0) < 0.01:
		return skin

	def rep(m):
		k, v = m.group(1), m.group(2)
		if k in ("font", "secondfont"):
			name, _, n = v.partition(";")
			return '%s="%s;%d"' % (k, name, S(int(n))) if n.strip().isdigit() else m.group(0)
		return '%s="%s"' % (k, ",".join(str(S(int(p))) if p.strip().isdigit() else p for p in v.split(",")))
	return _SKIN_ATTR.sub(rep, skin)


def scale_skins(ns):
	"""Na koncu modulu: przelicza skorki wszystkich ekranow zdefiniowanych w tym module (raz)."""
	for obj in list(ns.values()):
		if isinstance(obj, type) and obj.__module__ == ns.get("__name__") and "skin" in obj.__dict__ \
				and not obj.__dict__.get("_skin_scaled"):
			obj.skin = scale_skin(obj.skin)
			obj._skin_scaled = True


def keys_skin(x, y, width, height=55, font=28, exit_key=False):
	"""Fragment skorki: kolorowe pola z opisami (kolor jako tlo, niezalezny od skorki);
	exit_key=True dodaje piate, szare pole dla klawisza Exit."""
	gap = 10
	keys = KEY_COLORS + ((KEY_EXIT,) if exit_key else ())
	cnt = len(keys)
	w = (width - (cnt - 1) * gap) // cnt
	out = []
	for i, (name, color) in enumerate(keys):
		out.append('<widget name="key_%s" position="%d,%d" size="%d,%d" font="Regular;%d" halign="center" '
			'valign="center" backgroundColor="%s" foregroundColor="#ffffff" transparent="0" />' % (
			name, x + i * (w + gap), y, w, height, font, color))
	return "\n".join(out)


# lista wyboru wtyczki: bez okienka z obrazem (ChoiceBox ze skorki OpenPLi ma podglad i waska liste, ktora ucina
# dlugie pozycje); obraz zostaje tylko tam, gdzie jest potrzebny - okno wyboru stacji, podglad programu, SatWatch
try:
	from Screens.ChoiceBox import ChoiceBox as _ChoiceBox

	class EPGChoiceBox(_ChoiceBox):
		skin = """
		<screen name="EPGScanChoiceBox" position="center,center" size="1400,760" title="EPG Scan">
			<widget name="text" position="20,10" size="1360,80" font="Regular;28" />
			<widget name="list" position="20,100" size="1360,580" scrollbarMode="showOnDemand" />
			<widget name="description" position="20,690" size="1360,60" font="Regular;24" />
		</screen>"""

		def __init__(self, *a, **k):
			_ChoiceBox.__init__(self, *a, **k)
			self.skinName = ["EPGScanChoiceBox"]

	EPGChoiceBox.skin = scale_skin(EPGChoiceBox.skin)
except Exception:            # poza Enigma2 (testy)
	EPGChoiceBox = None


HINTS = (("OK", "Start (profil)"), ("Menu", "Kanaly"), ("Info/EPG", "Raporty"))


def hints_skin(x, y, width, height=50, hints=None):
	"""Linia skrotow: 3 rowne pola w ramce; w kazdym, wysrodkowane: 'klawisz' (jasny prostokat z nazwa) i opis."""
	gap = 10
	w = (width - 2 * gap) // 3
	out = []
	for i, (cap, txt) in enumerate(hints or HINTS):
		bx = x + i * (w + gap)
		cw = max(80, len(cap) * 15 + 30)          # klawisz (czcionka 24)
		tw = len(txt) * 15 + 12                   # opis (czcionka 28), szacunek szerokosci
		x0 = bx + max(10, (w - cw - 14 - tw) // 2)
		kh = height - 14
		out.append('<eLabel position="%d,%d" size="%d,%d" backgroundColor="#00808890" zPosition="0" />' % (bx, y, w, height))
		out.append('<eLabel position="%d,%d" size="%d,%d" backgroundColor="#002a2f36" zPosition="1" />' % (
			bx + 2, y + 2, w - 4, height - 4))
		out.append('<eLabel position="%d,%d" size="%d,%d" backgroundColor="#00d0d4da" zPosition="2" />' % (x0, y + 7, cw, kh))
		out.append('<eLabel position="%d,%d" size="%d,%d" backgroundColor="#00566070" zPosition="3" />' % (
			x0 + 1, y + 8, cw - 2, kh - 2))
		out.append('<widget name="hint_cap%d" position="%d,%d" size="%d,%d" font="Regular;24" halign="center" '
			'valign="center" foregroundColor="#ffffff" transparent="1" zPosition="4" />' % (i, x0 + 1, y + 8, cw - 2, kh - 2))
		out.append('<widget name="hint_txt%d" position="%d,%d" size="%d,%d" font="Regular;28" valign="center" '
			'foregroundColor="#ffffff" transparent="1" zPosition="4" />' % (i, x0 + cw + 14, y + 2, bx + w - x0 - cw - 18, height - 4))
	return "\n".join(out)


def init_hints(screen, hints=None):
	from Components.Label import Label
	for i, (cap, txt) in enumerate(hints or HINTS):
		screen["hint_cap%d" % i] = Label(cap)
		screen["hint_txt%d" % i] = Label(_(txt))


def init_keys(screen, exit_key=False):
	from Components.Label import Label
	for name, color in KEY_COLORS + ((KEY_EXIT,) if exit_key else ()):
		screen["key_" + name] = Label("")


def set_key_color(screen, name, color):
	"""Zmienia kolor tla pola przycisku w locie (np. zielone pole jako szare Exit)."""
	w = screen["key_" + name]
	if getattr(w, "_color", None) == color:
		return
	try:
		from enigma import gRGB
		w.instance.setBackgroundColor(gRGB(int(color.lstrip("#"), 16)))
		w.instance.invalidate()
		w._color = color
	except Exception:
		pass


def set_keys(screen, red="", green="", yellow="", blue="", exit=None):
	items = [("red", red), ("green", green), ("yellow", yellow), ("blue", blue)]
	if exit is not None:
		items.append(("exit", exit))
	for name, txt in items:
		w = screen["key_" + name]
		w.setText(_(txt))
		try:
			if txt:
				w.show()
			else:
				w.hide()
		except Exception:
			pass


# ---------------------------------------------------------------- statystyka EPG

def epg_stats(refs):
	"""Zdarzenia minione (z historii) i przyszle oraz zasieg danych w dniach do przodu."""
	cache = eEPGCache.getInstance()
	now = int(time.time())
	since = now - 3 * 86400
	past = future = dups = 0
	ends = []
	for ref in refs:
		try:
			res = cache.lookupEvent(["BD", (ref, 0, since, -1)]) or []
		except Exception:
			res = []
		last = 0
		prev_end = 0
		for r in sorted(x for x in res if x and x[0] is not None):
			b = int(r[0])
			d = int(r[1] or 0)
			if prev_end and b < prev_end - 60 and b + d > now:
				dups += 1           # przyszla audycja zaczyna sie przed koncem poprzedniej (nakladanie)
			prev_end = max(prev_end, b + d)
			if b + d <= now:
				past += 1
			else:
				future += 1
			last = max(last, b + d)
		if last > now:
			ends.append(last)
	ends.sort()
	days = lambda t: (t - now) / 86400.0
	return {
		"dups": dups,
		"past": past,
		"future": future,
		"channels": len(ends),
		"max": days(ends[-1]) if ends else 0,
		"median": days(ends[len(ends) // 2]) if ends else 0,
		"min": days(ends[0]) if ends else 0,
		"until": time.strftime("%d.%m %H:%M", time.localtime(ends[-1])) if ends else "-",
	}


def stats_text(st, localized=False):
	if localized:
		template = _("przyszle %d, minione %d, kanalow z EPG %d, nakladajacych sie (duble) %d\nzasieg dni: min %.1f / mediana %.1f / maks %.1f (do %s)")
	else:
		template = "przyszle %d, minione %d, kanalow z EPG %d, nakladajacych sie (duble) %d, zasieg dni: min %.1f / mediana %.1f / maks %.1f (do %s)"
	return template % (
		st["future"], st["past"], st["channels"], st["dups"], st["min"], st["median"], st["max"], st["until"])


# ---------------------------------------------------------------- cele importu XMLTV

def bouquet_keys(files, names=None):
	"""Klucze (sid, tsid, onid, ns) wszystkich kanalow z bukietow - DVB i IPTV.
	names: opcjonalny slownik do wypelnienia {klucz: nazwa}."""
	keys = []
	seen = set()

	def walk(fname):
		if fname in seen:
			return
		seen.add(fname)
		try:
			with open(os.path.join(E2, fname), encoding="utf-8", errors="ignore") as f:
				lines = f.read().splitlines()
		except Exception:
			return
		for i, line in enumerate(lines):
			if not line.startswith("#SERVICE "):
				continue
			ref = line[9:].strip()
			m = re.search(r'FROM BOUQUET "([^"]+)"', ref)
			if m:
				walk(m.group(1))
				continue
			p = ref.split(":")
			if len(p) < 10 or p[1] == "64":
				continue
			try:
				k = (int(p[3], 16), int(p[4], 16), int(p[5], 16), int(p[6], 16))
			except Exception:
				continue
			if k[0] and k not in keys:
				keys.append(k)
				if names is not None:
					nm = ""
					if i + 1 < len(lines) and lines[i + 1].startswith("#DESCRIPTION"):
						nm = lines[i + 1][13:].strip()
					if not nm and len(p) > 11 and p[0] != "1":
						nm = p[-1]                # IPTV: nazwa na koncu referencji
					names[k] = nm or service_name(":".join(p[:10]) + ":")

	for f in files:
		walk(f)
	return keys


def xmltv_targets(names=None, coverage=None, event_ids=None):
	"""{klucz: horyzont} - horyzont = koniec ostatniej audycji z satelity (0 = brak EPG).
	W trybie "missing" pomija kanaly, ktore maja EPG z satelity."""
	cache = eEPGCache.getInstance()
	now = int(time.time())
	out = {}
	from .epgintervals import merge_intervals

	def add_target(k):
		ref = "1:0:1:%X:%X:%X:%X:0:0:0:" % k
		try:
			res = cache.lookupEvent(["IBD", (ref, 0, -1, -1)]) or []
		except Exception as e:
			log("XMLTV: nie mozna odczytac EPG %s - pomijam kanal: %s" % (ref, e))
			return
		intervals = merge_intervals((int(r[1]), int(r[1]) + int(r[2] or 0))
			for r in res if r and r[1] is not None and int(r[1]) + int(r[2] or 0) > now)
		hz = intervals[-1][1] if intervals else 0
		if hz and cfg.xmltv_mode.value == "missing":
			return
		out[k] = hz
		if coverage is not None:
			coverage[k] = intervals
		if event_ids is not None:
			event_ids[k] = {int(r[0]) for r in res if r and r[0] is not None}

	files = xmltv_bouquet_files()
	for k in bouquet_keys(files, names):
		add_target(k)
	if cfg.xmltv_copies.value:
		# pozostale kanaly TV z lamedb (kopie tych samych stacji na innych transponderach/platformach)
		for refs in lamedb_groups().values():
			for ref in refs:
				p = ref.split(":")
				if p[2] in ("2", "A"):          # radio
					continue
				try:
					k = (int(p[3], 16), int(p[4], 16), int(p[5], 16), int(p[6], 16))
				except Exception:
					continue
				if k in out:
					continue
				add_target(k)
	return out


def dup_report(refs, top=10):
	"""Lista kanalow z najwieksza liczba nakladajacych sie audycji + przyklad pary (do logu)."""
	cache = eEPGCache.getInstance()
	now = int(time.time())
	rows = []
	for ref in refs:
		try:
			res = cache.lookupEvent(["BDT", (ref, 0, -1, -1)]) or []
		except Exception:
			res = []
		evs = sorted((int(r[0]), int(r[1] or 0), r[2] or "") for r in res if r and r[0] is not None)
		cnt = 0
		ex = None
		prev = None
		for e in evs:
			if prev and e[0] < prev[0] + prev[1] - 60:
				cnt += 1
				if ex is None:
					ex = (prev, e)
			if not prev or e[0] + e[1] > prev[0] + prev[1]:
				prev = e
		if cnt:
			rows.append((cnt, len(evs), ref, ex))
	rows.sort(reverse=True)
	out = []
	for cnt, tot, ref, ex in rows[:top]:
		f = lambda e: "%s %d min '%s'" % (time.strftime("%d.%m %H:%M", time.localtime(e[0])), e[1] // 60, e[2][:40])
		out.append("%s: %d z %d, np. %s  <->  %s" % (service_name(ref), cnt, tot, f(ex[0]), f(ex[1])))
	return out


def log_dups(where):
	try:
		lines = dup_report(all_refs())
		if lines:
			log("Duble %s - kanaly z najwieksza liczba nakladajacych sie audycji:" % where)
			for l in lines:
				log("  " + l)
	except Exception as e:
		log("Raport dubli nieudany: %s" % e)


def export_dups(refs):
	"""Zapisuje wszystkie nakladajace sie pary audycji do duble.txt obok logu. Zwraca (sciezka, liczba par)."""
	cache = eEPGCache.getInstance()
	path = os.path.join(os.path.dirname(log_path()), "duble.txt")
	pairs = 0
	f_ev = lambda e: "%s-%s (%3d min) %s" % (time.strftime("%d.%m %H:%M", time.localtime(e[0])),
		time.strftime("%H:%M", time.localtime(e[0] + e[1])), e[1] // 60, e[2])
	try:
		with open(path, "w") as f:
			f.write("EPG Scan %s - nakladajace sie audycje, %s\n" % (VERSION, time.strftime("%Y-%m-%d %H:%M")))
			f.write("Format: kanal | wczesniejsza audycja | nakladajaca sie audycja | nakladanie [min]\n\n")
			for ref in refs:
				try:
					res = cache.lookupEvent(["BDTI", (ref, 0, -1, -1)]) or []
				except Exception:
					res = []
				evs = sorted((int(r[0]), int(r[1] or 0), (r[2] or "").replace("\n", " "), r[3]) for r in res if r and r[0] is not None)
				prev = None
				name = None
				for e in evs:
					if prev and e[0] < prev[0] + prev[1] - 60:
						if name is None:
							name = service_name(ref)
							f.write("== %s  (%s)\n" % (name, ref))
						ov = (prev[0] + prev[1] - e[0]) // 60
						same = " [TEN SAM TYTUL]" if e[2].strip().lower() == prev[2].strip().lower() else ""
						f.write("  %s\n  %s   nakladanie %d min, id %s/%s%s\n\n" % (f_ev(prev), f_ev(e), ov, prev[3], e[3], same))
						pairs += 1
					if not prev or e[0] + e[1] > prev[0] + prev[1]:
						prev = e
			f.write("Razem par: %d\n" % pairs)
	except Exception as e:
		log("Eksport dubli nieudany: %s" % e)
		return None, 0
	log("Duble zapisane do %s (par: %d)" % (path, pairs))
	return path, pairs


# ---------------------------------------------------------------- doczytywanie brakow

def topup_seconds():
	try:
		return int(cfg.topup_hours.value) * 3600
	except Exception:
		return 6 * 3600


def topup_label():
	return dict(TOPUP_CHOICES).get(str(cfg.topup_hours.value), "%s h" % cfg.topup_hours.value)


def last_midnight(now=None):
	t = time.localtime(now or time.time())
	return int(time.mktime((t.tm_year, t.tm_mon, t.tm_mday, 0, 0, 0, 0, 0, -1)))


TOPUP_INFO = {"recent": 0, "backoff": 0}


def topup_candidates(jobs, force=False):
	"""Transpondery do doczytania: kanal z bukietu, ktory niedawno mial EPG z satelity,
	a jego dane koncza sie w ciagu progu; transponder nie byl skanowany w ciagu ostatnich
	cfg.topup_every godzin (zeby nie skanowac go w kolko)."""
	cache = eEPGCache.getInstance()
	now = int(time.time())
	limit = now + topup_seconds()
	base = cfg.topup_every.value * 3600 - 900      # 15 min zapasu na czas trwania skanu
	stats = load_tp_stats()
	TOPUP_INFO["recent"] = 0
	TOPUP_INFO["backoff"] = 0
	out = []
	for j in jobs:
		if getattr(j, "skip", False):
			continue
		st = stats.get(tp_id(tp_key(j.ref)), {})
		last = st.get("check", 0)
		tgap = st.get("topup_gap", cfg.topup_every.value) * 3600 - 900
		tlast = st.get("topup_last", 0)
		recent = bool(last and now - last < base)
		backoff = not recent and bool(tlast and now - tlast < tgap)
		if force:
			recent = backoff = False
		short = []
		for ref in j.refs:
			try:
				res = cache.lookupEvent(["BD", (ref, 0, now - 86400, -1)]) or []
			except Exception:
				res = []
			ends = [int(r[0]) + int(r[1] or 0) for r in res if r and r[0] is not None]
			if ends and max(ends) < limit:
				short.append(ref)
		if short and recent:
			TOPUP_INFO["recent"] += 1
		elif short and backoff:
			TOPUP_INFO["backoff"] += 1
		elif short:
			j.topup_reason = "kanaly z EPG do %s: %d" % (time.strftime("%d.%m %H:%M", time.localtime(limit)), len(short))
			out.append(j)
	return out


# ---------------------------------------------------------------- ekran statystyk

def bouquet_channels():
	"""[(nazwa bukietu, ref, nazwa kanalu)] kanalow DVB z uwzglednianych bukietow (bez powtorzen)."""
	out = []
	seen = set()
	names = dict(list_bouquets())

	def walk(fname, bq, visited):
		if fname in visited:
			return
		visited.add(fname)
		try:
			with open(os.path.join(E2, fname), encoding="utf-8", errors="ignore") as f:
				lines = f.read().splitlines()
		except Exception:
			return
		for i, line in enumerate(lines):
			if not line.startswith("#SERVICE "):
				continue
			ref = line[9:].strip()
			m = re.search(r'FROM BOUQUET "([^"]+)"', ref)
			if m:
				walk(m.group(1), bq, visited)
				continue
			p = ref.split(":")
			if len(p) < 10 or p[0] != "1" or p[1] == "64" or p[3] == "0" or "%3a" in ref.lower():
				continue
			key = ":".join(p[:10]) + ":"
			if key in seen:
				continue
			seen.add(key)
			nm = ""
			if i + 1 < len(lines) and lines[i + 1].startswith("#DESCRIPTION"):
				nm = lines[i + 1][13:].strip()
			out.append((bq, key, nm or service_name(key)))

	for f in selected_bouquets():
		walk(f, names.get(f, f), set())
	return out


def fmt_span(sec):
	if sec <= 0:
		return _("brak")
	h = sec / 3600.0
	return "%.1f h" % h if h < 24 else _("%.1f dni") % (h / 24.0)


def channel_table():
	"""[(sekundy do konca EPG, liczba przyszlych audycji, bukiet, kanal)] posortowane od najkrotszego."""
	cache = eEPGCache.getInstance()
	now = int(time.time())
	rows = []
	for bq, ref, name in bouquet_channels():
		try:
			res = cache.lookupEvent(["BD", (ref, 0, -1, -1)]) or []
		except Exception:
			res = []
		ends = [int(r[0]) + int(r[1] or 0) for r in res if r and r[0] is not None]
		fut = [e for e in ends if e > now]
		rows.append(((max(fut) - now) if fut else 0, len(fut), bq, name))
	rows.sort(key=lambda r: (r[0], r[1]))
	return rows


def whole_cache_stats():
	"""Cala pamiec EPG: wszystkie kanaly TV/radio z listy kanalow (lamedb)."""
	refs = []
	for lst in lamedb_groups().values():
		refs.extend(lst)
	n, cov = count_events(refs)
	path = epg_path()
	try:
		st = os.stat(path)
		finfo = "%s: %d kB, zapisany %s" % (path, st.st_size // 1024, time.strftime("%d.%m %H:%M", time.localtime(st.st_mtime)))
	except Exception:
		finfo = "%s: brak pliku" % path
	return len(refs), cov, n, finfo
