package pl.jardol.e2stream

import android.content.Context
import android.content.SharedPreferences

internal fun normalizePin(value: String) = value.filter { it in '0'..'9' }.take(6)

/** Funkcje w przygotowaniu (ukryte w wydaniu stabilnym). */
const val HISTORY_MAX = 50

object Features {
    /** PiP: kod obecny; włączane ustawieniem „PiP (eksperymentalne)” (Prefs.pipEnabled), domyślnie wyłączone. */
    const val PIP = true
}

class Prefs(ctx: Context) {
    private val app: Context = ctx.applicationContext ?: ctx
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("e2stream", Context.MODE_PRIVATE)

    init {
        if (sp.contains("backLongExit")) sp.edit().remove("backLongExit").apply()
        // usunięta opcja „liczba klatek dla dekodera” (eksperyment) — bez śladu w ustawieniach i kopii na dekoderze
        if (sp.contains("exoFps")) sp.edit().remove("exoFps").apply()
    }

    private fun s(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun putS(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun putI(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun putB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    /** Adres IP / nazwa dekodera Enigma2. */
    var host: String
        get() = s("host")
        set(v) = putS("host", v)

    var discoverySubnet: String
        get() = s("discoverySubnet")
        set(v) = putS("discoverySubnet", v)

    /** Port OpenWebif (HTTP). */
    var httpPort: Int
        get() = sp.getInt("httpPort", 80)
        set(v) = putI("httpPort", v)

    /** Port streamingu Enigma2 (domyślnie 8001). */
    var streamPort: Int
        get() = sp.getInt("streamPort", 8001)
        set(v) = putI("streamPort", v)

    var user: String
        get() = s("user")
        set(v) = putS("user", v)

    var pass: String
        get() = s("pass")
        set(v) = putS("pass", v)

    var appLanguage: String
        get() = s("appLanguage", AppLanguage.SYSTEM)
        set(v) {
            require(AppLanguage.isValid(v)) { "Nieprawidłowy język aplikacji: $v" }
            putS("appLanguage", v)
        }

    var blockOnDisconnect: Boolean
        get() = sp.getBoolean("blockOnDisconnect", true)
        set(v) = putB("blockOnDisconnect", v)

    /** Tunelowanie wideo (ExoPlayer). */
    var tunneling: Boolean
        get() = sp.getBoolean("tunneling", true)
        set(v) = putB("tunneling", v)

    /** Automatyczne wznawianie zawieszonego strumienia. */
    var watchdog: Boolean
        get() = sp.getBoolean("watchdog", true)
        set(v) = putB("watchdog", v)

    /** Po ilu sekundach bez nowych klatek uznać obraz za zawieszony. */
    var stallSec: Int
        get() = sp.getInt("stallSec", 3)
        set(v) = putI("stallSec", v)

    /** Bufor startowy w ms. */
    var bufferMs: Int
        get() = sp.getInt("bufferMs", 1500)
        set(v) = putI("bufferMs", v)

    /** Odświeżanie ekranu: 0 = bez zmian, 1 = zawsze 50 Hz, 2 = automatycznie wg treści. */
    var afrMode: Int
        get() = sp.getInt("afrMode", if (sp.getBoolean("force50Hz", false)) 1 else 0)
        set(v) = putI("afrMode", v)

    /** Preferowane języki dźwięku; starsze kody ISO 639-2 i nazwy są normalizowane do ISO 639-1. */
    var audioLang: String
        get() = AudioTrackLabels.preferredLanguages(s("audioLang", "pl")).joinToString(",").ifEmpty { "pl" }
        set(v) = putS("audioLang", AudioTrackLabels.preferredLanguages(v).joinToString(",").ifEmpty { "pl" })

    /** Preferuj AC3 / E-AC3 (Dolby) przed MPEG stereo. */
    var preferAc3: Boolean
        get() = sp.getBoolean("preferAc3", false)
        set(v) = putB("preferAc3", v)

    /** Ścieżka dźwięku zapamiętana dla kanału: "id|język|mime". */
    fun audioFor(ref: String): String = s("audio_" + OpenWebif.key(ref))
    fun setAudioFor(ref: String, v: String) { putS("audio_" + OpenWebif.key(ref), v); chanDirty = true }

    /** Wysyłanie logu na dekoder przez FTP. */
    var logFtp: Boolean
        get() = sp.getBoolean("logFtp", true)
        set(v) = putB("logFtp", v)

    var ftpUser: String
        get() = s("ftpUser", "root")
        set(v) = putS("ftpUser", v)

    var ftpPass: String
        get() = s("ftpPass")
        set(v) = putS("ftpPass", v)

    var ftpDir: String
        get() = s("ftpDir", DEFAULT_FTP_DIR)
        set(v) = putS("ftpDir", v)

    /** Timeshift: długość okna w minutach (0 = wyłączony). Ustawienie tego urządzenia. */
    var tsMin: Int
        get() = sp.getInt("tsMin", 0)
        set(v) = putI("tsMin", v)

    /** Timeshift: ile sekund po przełączeniu kanału zaczyna się zapis do bufora (skakanie po kanałach nie zapisuje na dysk). */
    var tsStepSec: Int
        get() = sp.getInt("tsStepSec", 5)
        set(v) = putI("tsStepSec", v)
    var tsStepMin: Int
        get() = sp.getInt("tsStepMin", 1)
        set(v) = putI("tsStepMin", v)
    var tsBackLive: Boolean
        get() = sp.getBoolean("tsBackLive", false)
        set(v) = putB("tsBackLive", v)
    var tsDelay: Int
        get() = sp.getInt("tsDelay", 5)
        set(v) = putI("tsDelay", v)

    /** Timeshift: zgoda na bufor w pamięci urządzenia (bez zgody tylko dysk USB / karta SD). */
    var tsInternalOk: Boolean
        get() = sp.getBoolean("tsInternalOk", false)
        set(v) = sp.edit().putBoolean("tsInternalOk", v).apply()

    /** Nagrania lokalne z bufora timeshiftu: najdłuższe pojedyncze nagranie (h). */
    var recMaxHours: Int
        get() = sp.getInt("recMaxHours", 3).coerceIn(1, 6)
        set(v) = putI("recMaxHours", v)

    /** Nagrania lokalne: najwyżej tyle % pojemności nośnika (reszta dla bufora timeshiftu). */
    var recMaxPct: Int
        get() = sp.getInt("recMaxPct", 70).coerceIn(10, 95)
        set(v) = putI("recMaxPct", v)

    /** Zgoda na nagrania lokalne w pamięci urządzenia (gdy timeshift jest w pamięci wewnętrznej). */
    var recInternalOk: Boolean
        get() = sp.getBoolean("recInternalOk", false)
        set(v) = putB("recInternalOk", v)

    /** Timeshift: katalog nośnika (pusty = pamięć urządzenia). */
    var tsDir: String
        get() = s("tsDir")
        set(v) = putS("tsDir", v)

    /** Katalog zapisu logów i zrzutów TS (pusty = domyślny katalog aplikacji). */
    var storageDir: String
        get() = s("storageDir")
        set(v) = putS("storageDir", v)

    /** Miejsce logów ustawione automatycznie (za dyskiem timeshiftu), a nie ręcznie. */
    var storageDirAuto: Boolean
        get() = sp.getBoolean("storageDirAuto", false)
        set(v) = putB("storageDirAuto", v)

    /** Zapis ostatnich sekund strumienia TS przy zawieszeniu. */
    var saveTs: Boolean
        get() = sp.getBoolean("saveTs", true)
        set(v) = putB("saveTs", v)

    /** Rozmiar bufora zrzutu TS w MB. */
    var tsRingMb: Int
        get() = sp.getInt("tsRingMb", 16)
        set(v) = putI("tsRingMb", v)

    /** Wysyłanie zrzutów TS także przez FTP na dekoder. */
    var tsFtp: Boolean
        get() = sp.getBoolean("tsFtp", false)
        set(v) = putB("tsFtp", v)

    /** Czyszczenie strumienia przed odtwarzaczem (pakiety uszkodzone / kodowane / obce PMT). */
    var cleanTs: Boolean
        get() = sp.getBoolean("cleanTs", true)
        set(v) = putB("cleanTs", v)

    /** Zapamiętany PMT kanału (surowe pakiety TS, Base64). */
    fun pmtFor(sid: Int): ByteArray? = s("pmts_$sid").takeIf { it.isNotEmpty() }?.let {
        try { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) } catch (_: Exception) { null }
    }
    fun setPmtFor(sid: Int, b: ByteArray) {
        val v = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
        if (s("pmts_$sid") != v) { putS("pmts_$sid", v); chanDirty = true }
    }

    /** Dane kanałów do synchronizacji z dekoderem (PMT, ścieżki dźwięku). */
    fun channelData(): Map<String, String> =
        sp.all.filter { isChannelKey(it.key) && it.value is String }.mapValues { it.value as String }

    fun putChannelData(k: String, v: String) { if (isChannelKey(k)) putS(k, v) }

    /** Wtyczka EPG Scan na dekoderze (serwer dla klientów). */
    var pluginPort: Int
        get() = sp.getInt("pluginPort", 8770)
        set(v) = putI("pluginPort", v)

    var pluginToken: String
        get() = s("pluginToken")
        set(v) = putS("pluginToken", v)

    var pluginOk: Boolean
        get() = sp.getBoolean("pluginOk", false)
        set(v) = putB("pluginOk", v)

    var pluginVersion: String
        get() = s("pluginVersion")
        set(v) = putS("pluginVersion", v)

    /** PID-y kanału z lamedb (JSON z wtyczki). */
    fun pidsFor(sid: Int): String? = s("pids_$sid").takeIf { it.isNotEmpty() }
    fun setPidsFor(sid: Int, json: String) { if (s("pids_$sid") != json) putS("pids_$sid", json) }

    /** Nazwa tego odbiornika w synchronizacji (np. „Salon – Philips”). */
    var deviceName: String
        get() = s("deviceName", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        set(v) = putS("deviceName", v)

    /** Krótki znacznik tego urządzenia (4 znaki z ANDROID_ID) — odróżnia te same modele w sieci. */
    val deviceTag: String
        get() {
            val id = try {
                android.provider.Settings.Secure.getString(app.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            } catch (_: Exception) { null } ?: ""
            val hex = id.filter { it.isLetterOrDigit() }.uppercase()
            return if (hex.length >= 4) hex.takeLast(4) else "0000"
        }

    /** Nazwa odbiornika ze znacznikiem, np. „Salon [3F2A]” — w listach odbiorników. */
    val displayName: String
        get() = "$deviceName [$deviceTag]"

    /** Nazwa do plików na dekoderze: nazwa odbiornika + znacznik, np. Salon-3F2A. */
    val fileTag: String
        get() {
            val pl = mapOf('ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n', 'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z',
                'Ą' to 'A', 'Ć' to 'C', 'Ę' to 'E', 'Ł' to 'L', 'Ń' to 'N', 'Ó' to 'O', 'Ś' to 'S', 'Ź' to 'Z', 'Ż' to 'Z')
            val slug = deviceName.map { pl[it] ?: it }.joinToString("")
                .replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(24).ifEmpty { android.os.Build.MODEL }
            return "$slug-$deviceTag"
        }

    /** Synchronizacja ustawień i danych kanałów z dekoderem (FTP). */
    var channelSync: Boolean
        get() = sp.getBoolean("channelSync", true)
        set(v) = putB("channelSync", v)

    /** Są zmiany do wysłania na dekoder. */
    var chanDirty: Boolean
        get() = sp.getBoolean("chanDirty", false)
        set(v) = putB("chanDirty", v)

    /** Czy już zaproponowano pobranie danych kanałów z dekodera (dla danego adresu). */
    var chanAskedHost: String
        get() = s("chanAskedHost")
        set(v) = putS("chanAskedHost", v)

    /** Restart strumienia przy masowej utracie klatek (zacinanie). */
    /** Szybszy start kanału: wznowienie, gdy dane są, a odtwarzacz nie rusza (zamiast czekać na limit buforowania). */
    var fastStart: Boolean
        get() = sp.getBoolean("fastStart", true)
        set(v) = putB("fastStart", v)

    /** Przy przełączaniu kanału ostatnia klatka poprzedniego zostaje na ekranie do pojawienia się nowego obrazu. */
    /** Odtwarzanie przez bufor: jedno połączenie z dekoderem zapisuje do bufora na nośniku, odtwarzacz czyta z bufora. */
    var bufPlay: Boolean
        get() = sp.getBoolean("bufPlay", false)
        set(v) = putB("bufPlay", v)

    /** Bufor poprzednich kanałów: ile kanałów buforowanych w tle (0 — wyłączony, 1–3); dawniej włączony = 2. */
    var bgCount: Int
        get() = if (sp.contains("bgCount")) sp.getInt("bgCount", 0).coerceIn(0, 3) else if (sp.getInt("bgBufMin", 0) != 0) 2 else 0
        set(v) = putI("bgCount", v)

    /** Czas bufora poprzednich kanałów w minutach: -1 — taki sam jak timeshift, >0 — ręczny (najwyżej jak timeshift). */
    var bgBufMin: Int
        get() = sp.getInt("bgBufMin", -1).let { if (it == 0) -1 else it }
        set(v) = putI("bgBufMin", v)

    /** Przesunięcie dźwięku względem obrazu w ms (−1000…+1000, krok 50; ujemne — dźwięk wcześniej). */
    var avShiftMs: Int
        get() = sp.getInt("avShiftMs", 0).coerceIn(AvShift.MIN, AvShift.MAX)
        set(v) = putI("avShiftMs", v.coerceIn(AvShift.MIN, AvShift.MAX))

    /** Komunikat przy przełączaniu, gdy bufory poprzednich kanałów są zajęte przez nagrania. */
    var bgFullMsg: Boolean
        get() = sp.getBoolean("bgFullMsg", true)
        set(v) = putB("bgFullMsg", v)

    /** Krótki stan buforów po każdym przełączeniu kanału („Bufory: 2/3, nagrywa 1”). */
    var bgStatusMsg: Boolean
        get() = sp.getBoolean("bgStatusMsg", false)
        set(v) = putB("bgStatusMsg", v)

    /** Historia oglądanych kanałów (najnowszy pierwszy): ref → nazwa. */
    var history: List<Pair<String, String>>
        get() = try {
            val a = org.json.JSONArray(sp.getString("history", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("ref") to o.optString("name") } }
        } catch (_: Exception) { emptyList() }
        set(v) = putS("history", org.json.JSONArray().apply {
            v.forEach { put(org.json.JSONObject().put("ref", it.first).put("name", it.second)) }
        }.toString())

    fun historyPush(ref: String, name: String) {
        if (ref.isBlank()) return
        val k = OpenWebif.key(ref)
        history = (listOf(ref to name) + history.filter { OpenWebif.key(it.first) != k }).take(HISTORY_MAX)
    }

    /** PiP (eksperymentalne) — domyślnie wyłączone; bezpiecznik wyłącza po awarii / zawieszeniu w trakcie PiP. */
    var pipEnabled: Boolean
        get() = sp.getBoolean("pipEnabled", false)
        set(v) = putB("pipEnabled", v)

    /** Znacznik „okna PiP otwarte” — zapisywany od razu; zostaje po awarii, zabiciu procesu albo zawieszeniu Androida. */
    var pipActive: Boolean
        get() = sp.getBoolean("pipActive", false)
        set(v) = sp.edit().putBoolean("pipActive", v).commit().let { }

    /** PiP: klatka co tyle ms (1 / 2 / 3 / 5 s; dawny obraz płynny i 0,5 s → 1 s). */
    var pipFrameMs: Int
        get() = sp.getInt("pipFrameMs", 1000).let { if (it < 1000) 1000 else minOf(it, 5000) }
        set(v) = putI("pipFrameMs", v)

    /** PiP: położenie okien (SettingsActivity.pipPositions), domyślnie prawy górny róg w dół. */
    var pipPos: Int
        get() = sp.getInt("pipPos", 5)
        set(v) = putI("pipPos", v)

    var keepFrame: Boolean
        get() = sp.getBoolean("keepFrame", true)
        set(v) = putB("keepFrame", v)

    var dropRestart: Boolean
        get() = sp.getBoolean("dropRestart", true)
        set(v) = putB("dropRestart", v)

    /** Mapowanie klawiszy: "kod=AKCJA,...". */
    var keyMap: String
        get() = s("keyMap")
        set(v) = putS("keyMap", v)

    var keyMapNoBanner: String
        get() = s("keyMapNoBanner")
        set(v) = putS("keyMapNoBanner", v)

    var keyMapBanner: String
        get() = s("keyMapBanner")
        set(v) = putS("keyMapBanner", v)

    /** Codzienne pobieranie EPG wg harmonogramu. */
    var epgDaily: Boolean
        get() = sp.getBoolean("epgDaily", true)
        set(v) = putB("epgDaily", v)

    /** EIT „teraz/następne” ze strumienia dekodera (Enigma2: Include EIT in http streams). */
    var eitStream: Boolean
        get() = sp.getBoolean("eitStream", false)
        set(v) = putB("eitStream", v)

    /** Podmiana EPG danymi z EIT — zapis w EPG aplikacji do następnej aktualizacji z dekodera. */
    var eitReplace: Boolean
        get() = sp.getBoolean("eitReplace", true)
        set(v) = putB("eitReplace", v)

    /** Test EIT: EPG aplikacji tylko z EIT strumienia — bez paczki EPG Scan i bez OpenWebif. */
    var eitOnly: Boolean
        get() = sp.getBoolean("eitOnly", false)
        set(v) = putB("eitOnly", v)

    /** Tryb testowy EIT aktywny (wymaga włączonego EIT ze strumienia). */
    val eitOnlyActive: Boolean get() = BuildConfig.BETA && eitStream && eitOnly

    /** Godzina codziennego pobierania EPG "HH:MM". */
    var epgHour: String
        get() = s("epgHour", "05:00")
        set(v) = putS("epgHour", v)

    var epgUpdateMode: String
        get() = s("epgUpdateMode", EpgUpdatePolicy.DAILY)
        set(v) = putS("epgUpdateMode", v)

    /** Ile dni EPG do przodu zapisywać. */
    var epgDays: Int
        get() = sp.getInt("epgDays", 3)
        set(v) = putI("epgDays", v)

    /** Czas (s) paczki z wtyczki zastosowanej ostatnio i ostatnie sprawdzenie (ms). */
    var packAt: Long
        get() = sp.getLong("packAt", 0)
        set(v) = sp.edit().putLong("packAt", v).apply()

    var packCheckAt: Long
        get() = sp.getLong("packCheckAt", 0)
        set(v) = sp.edit().putLong("packCheckAt", v).apply()

    var packIdentity: String
        get() = s("packIdentity")
        set(v) = putS("packIdentity", v)

    var lastEpgFetch: Long
        get() = sp.getLong("lastEpgFetch", 0)
        set(v) = sp.edit().putLong("lastEpgFetch", v).apply()

    /** Ikony kanałów z dekodera. */
    var picons: Boolean
        get() = sp.getBoolean("picons", true)
        set(v) = putB("picons", v)

    /** Logo aplikacji zamiast pustego miejsca, gdy kanał nie ma pobranego piconu. */
    var missingPiconLogo: Boolean
        get() = sp.getBoolean("missingPiconLogo", false)
        set(v) = putB("missingPiconLogo", v)

    /** Katalog picon na dekoderze dla FTP (pusty = szukaj w typowych). */
    var piconDir: String
        get() = s("piconDir")
        set(v) = putS("piconDir", v)

    /** Ostatnia awaria aplikacji (pełny opis) — pokazywana raz przy następnym starcie. */
    var lastCrash: String
        get() = s("lastCrash")
        set(v) = sp.edit().putString("lastCrash", v).commit().let { }

    /** Silnik i wersja, których uruchamianie nie dotarło jeszcze do pierwszego obrazu. */
    var playbackAttempt: String
        get() = s("playbackAttempt")
        set(v) = sp.edit().putString("playbackAttempt", v).commit().let { }

    /**
     * Zapas w trakcie oglądania (ms): do tylu odtwarzacz dobudowuje bufor po szybkim starcie
     * (lekkie zwolnienie) i tyle czeka po niedoborze. Klucz „rebufferMs” zostaje dla synchronizacji.
     */
    var rebufferMs: Int
        get() = sp.getInt("rebufferMs", 4500)
        set(v) = putI("rebufferMs", v)

    /** Wygląd (%): lista kanałów — czcionka i ikony, EPG, pasek kanału. */
    var uiListFont: Int
        get() = sp.getInt("uiListFont", 80)
        set(v) = putI("uiListFont", v)

    var uiListIcon: Int
        get() = sp.getInt("uiListIcon", 80)
        set(v) = putI("uiListIcon", v)

    var uiEpgFont: Int
        get() = sp.getInt("uiEpgFont", 100)
        set(v) = putI("uiEpgFont", v)

    var uiBannerFont: Int
        get() = sp.getInt("uiBannerFont", 100)
        set(v) = putI("uiBannerFont", v)

    var uiBannerIcon: Int
        get() = sp.getInt("uiBannerIcon", 100)
        set(v) = putI("uiBannerIcon", v)

    /** ExoPlayer: podpowiedź liczby klatek dla dekodera (eksperyment): 0 = brak, 25, 50. */

    /** Silnik odtwarzania: exo (ExoPlayer) albo vlc (libVLC). */
    var engine: String
        get() = s("engine", "exo")
        set(v) = putS("engine", v)

    /** VLC: usuwanie przeplotu: default (bez wymuszania, jak VLC), auto, off, yadif, yadif2x, linear, blend, bob, x. */
    var vlcDeint: String
        get() = s("vlcDeint", "default")
        set(v) = putS("vlcDeint", v)

    /** VLC: dekodowanie sprzętowe (MediaCodec) — zostawione dla zgodności; decyduje vlcHwMode. */
    var vlcHw: Boolean
        get() = sp.getBoolean("vlcHw", true)
        set(v) = putB("vlcHw", v)

    /** VLC: akceleracja jak w VLC dla Androida: auto, off, dec (dekodowanie, obraz przez VLC), full. */
    var vlcHwMode: String
        get() = s("vlcHwMode", if (vlcHw) "auto" else "off")
        set(v) = putS("vlcHwMode", v)

    /** VLC: format obrazu (android-display-chroma): RV32, RV16, YV12. */
    var vlcChroma: String
        get() = s("vlcChroma", "RV32")
        set(v) = putS("vlcChroma", v)

    /** Dobudowywanie zapasu przez lekkie zwolnienie odtwarzania (niesłyszalne, wysokość dźwięku bez zmian). */
    var cushionSlow: Boolean
        get() = sp.getBoolean("cushionSlow", true)
        set(v) = putB("cushionSlow", v)

    /** Statystyki widoczne na ekranie. */
    var showStats: Boolean
        get() = sp.getBoolean("showStats", false)
        set(v) = putB("showStats", v)

    /** Ukryte bukiety (referencje). */
    var hiddenBouquets: Set<String>
        get() = HashSet(sp.getStringSet("hiddenBouquets", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("hiddenBouquets", HashSet(v)).apply()

    /** Pozycje i kolejność głównego menu podręcznego. */
    var quickMenuOrder: List<String>
        get() = s("quickMenuOrder").split(',').filter { it.isNotBlank() }
        set(v) = putS("quickMenuOrder", v.joinToString(","))

    var quickMenuVisible: Set<String>
        get() {
            val all = QuickMenu.configurable.map { it.id }.toSet()
            val stored = sp.getStringSet("quickMenuVisible", null) ?: return HashSet(all)
            // pozycje nieznane w chwili zapisu (nowsze wersje) — widoczne
            val known = sp.getStringSet("quickMenuKnown", null) ?: (all - QuickMenu.TOP - QuickMenu.ADDED_LATER)
            return HashSet(stored + (all - known))
        }
        set(v) = sp.edit().putStringSet("quickMenuVisible", HashSet(v))
            .putStringSet("quickMenuKnown", HashSet(QuickMenu.configurable.map { it.id })).apply()

    /** PIN do ustawień (pusty = bez blokady). */
    var pin: String
        get() = normalizePin(s("pin"))
        set(v) = putS("pin", normalizePin(v))

    var invertUpDown: Boolean
        get() = sp.getBoolean("invertUpDown", false)
        set(v) = putB("invertUpDown", v)

    /** Na ile minut PIN odblokowuje ustawienia (0 = do zamknięcia aplikacji). */
    var pinMin: Int
        get() = sp.getInt("pinMin", 1)
        set(v) = putI("pinMin", v)

    /** Czas przechowywania zrealizowanych przełączeń w dniach; 0 = bez limitu. */
    var zapHistoryDays: Int
        get() = sp.getInt("zapHistoryDays", 28).let { if (it in listOf(0, 7, 14, 28)) it else 28 }
        set(v) = putI("zapHistoryDays", if (v in listOf(7, 14, 28)) v else 0)

    var bannerSec: Int
        get() = sp.getInt("bannerSec", 4)
        set(v) = putI("bannerSec", v)

    var messageBar: Boolean
        get() = sp.getBoolean("messageBar", true)
        set(v) = putB("messageBar", v)

    var messageBarSec: Int
        get() = sp.getInt("messageBarSec", 3).coerceIn(1, 10)
        set(v) = putI("messageBarSec", v.coerceIn(1, 10))

    var tsBannerSec: Int
        get() = sp.getInt("tsBannerSec", bannerSec)
        set(v) = putI("tsBannerSec", v)

    var channelListSec: Int
        get() = sp.getInt("channelListSec", 30)
        set(v) = putI("channelListSec", v)

    var actionDialogSec: Int
        get() = sp.getInt("epgEventSec", 30).coerceIn(0, 600)
        set(v) = putI("epgEventSec", v.coerceIn(0, 600))

    var confirmDialogSec: Int
        get() = sp.getInt("confirmDialogSec", 30).coerceIn(0, 600)
        set(v) = putI("confirmDialogSec", v.coerceIn(0, 600))

    var epgChannelSec: Int
        get() = sp.getInt("epgChannelSec", 30)
        set(v) = putI("epgChannelSec", v)

    var epgMultiSec: Int
        get() = sp.getInt("epgMultiSec", 30)
        set(v) = putI("epgMultiSec", v)

    /** Czas wyświetlania komunikatów w sekundach; 0 = bez limitu. */
    var infoDialogSec: Int
        get() = sp.getInt("infoDialogSec", 15).coerceIn(0, 600)
        set(v) = putI("infoDialogSec", v.coerceIn(0, 600))

    var hideInfoDialogs: Boolean
        get() = sp.getBoolean("hideInfoDialogs", false)
        set(v) = putB("hideInfoDialogs", v)

    var resumeLast: Boolean
        get() = sp.getBoolean("resumeLast", true)
        set(v) = putB("resumeLast", v)

    /**
     * Automatyczna aktualizacja: tak = przy starcie (i przez APK Updater w tle) nowa wersja instaluje się sama,
     * nie = tylko informacja i ręczna aktualizacja z ustawień. APK Updater czyta to przez [UpdateInfoProvider].
     */
    var autoUpdate: Boolean
        get() = sp.getBoolean("autoUpdate", true)
        set(v) = putB("autoUpdate", v)

    var updateChannel: String
        get() = s("updateChannel", "stable")
        set(v) = putS("updateChannel", if (v == "beta") "beta" else "stable")

    /** Eksperymentalne alarmy budzące dla lokalnego harmonogramu. */
    var wakeForZap: Boolean
        get() = sp.getBoolean("wakeForZap", false)
        set(v) = putB("wakeForZap", v)

    /** Kanał startowy (gdy nie wracamy do ostatniego). */
    var startBouquet: String
        get() = s("startBouquet")
        set(v) = putS("startBouquet", v)

    var startChannel: String
        get() = s("startChannel")
        set(v) = putS("startChannel", v)

    var startChannelName: String
        get() = s("startChannelName")
        set(v) = putS("startChannelName", v)

    var lastBouquet: String
        get() = s("lastBouquet")
        set(v) = putS("lastBouquet", v)

    var lastChannel: String
        get() = s("lastChannel")
        set(v) = putS("lastChannel", v)

    /** Zwiększane przy każdym zapisie ustawień — odtwarzacz wie, że ma się przeładować. */
    val version: Int
        get() = sp.getInt("version", 0)

    fun bump() = putI("version", version + 1)

    /** Jednorazowe przejście ze starych wartości domyślnych na nowe. */
    fun migrate() {
        val v = sp.getInt("prefsVer", 1)
        if (v < 2) {
            if (sp.getString("ftpDir", null) == "/tmp") putS("ftpDir", DEFAULT_FTP_DIR)
            if (sp.getInt("bufferMs", -1) == 1500) putI("bufferMs", 700)
            putI("prefsVer", 2)
        }
        if (v < 3) {
            // 0.8.2: zapas w trakcie oglądania osobno od bufora startowego (stary domyślny 2000 -> 4000)
            if (sp.getInt("rebufferMs", -1) == 2000) putI("rebufferMs", 4000)
            putI("prefsVer", 3)
        }
    }

    companion object {
        const val DEFAULT_FTP_DIR = "/media/hdd/e2stream"
        fun isChannelKey(k: String) = k.startsWith("pmts_") || k.startsWith("audio_")
    }
}
