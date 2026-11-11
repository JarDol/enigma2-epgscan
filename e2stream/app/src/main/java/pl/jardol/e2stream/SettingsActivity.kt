package pl.jardol.e2stream

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ustawienia jako lista obsługiwana pilotem: ▲▼ wybór, OK zmiana.
 * Zmiany zapisują się od razu; odtwarzacz przeładowuje się po wyjściu z ustawień.
 */
class SettingsActivity : Activity() {

    private lateinit var p: Prefs
    private lateinit var listView: ListView
    private val ui = Handler(Looper.getMainLooper())
    private var asking = false
    private var changed = false
    private var pendingWakeEnable = false
    private var wakePermissionFlow: String? = null
    private var fullScreenPermissionAsked = false
    private val adapter = Adapter()
    private lateinit var items: List<Item>
    private lateinit var store: EpgStore
    private var status = ""

    // ------------------------------------------------------------ model

    private sealed class Item(val label: String) {
        class Header(label: String) : Item(label)
        class Toggle(label: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : Item(label)
        class Text(
            label: String, val get: () -> String, val set: (String) -> Unit,
            val input: Int = InputType.TYPE_CLASS_TEXT, val secret: Boolean = false, val hint: String = "",
        ) : Item(label)
        class Action(label: String, val value: () -> String = { "" }, val run: () -> Unit) : Item(label)
    }

    private fun num(label: String, get: () -> Int, set: (Int) -> Unit, range: IntRange) =
        Item.Text(label, { get().toString() }, { v -> v.trim().toIntOrNull()?.let { set(it.coerceIn(range)) } },
            InputType.TYPE_CLASS_NUMBER, hint = "${range.first}–${range.last}")

    /** Sekcje ustawień (pierwszy ekran) — po wejściu lista pozycji danej sekcji. */
    private var section: String? = null

    private val sections = listOf(
        "Dekoder Enigma2" to "adres, porty, logowanie OpenWebif, wtyczka EPG Scan, FTP, synchronizacja",
        "Odtwarzanie i obraz" to "silnik (ExoPlayer / VLC), przeplot, bufory, watchdog, odświeżanie ekranu, dźwięk",
        "Wygląd" to "wielkość czcionek i ikon: lista kanałów, EPG, pasek kanału",
        "EPG i ikony kanałów" to "pobieranie EPG, ikony z dekodera",
        "Ogólne" to "bukiety, kanał startowy, pilot, PIN",
        "Informacje i aktualizacje" to "wersja i aktualizacja aplikacji, opis aplikacji i wtyczki EPG Scan",
        "Logi i zrzuty" to "diagnostyka: statystyki, log, zrzuty TS, miejsce zapisu",
    )

    private fun buildItems(): List<Item> {
        val sec = section ?: return sections.map { (name, desc) -> Item.Action(name, { desc }) { openSection(name) } }
        return listOf<Item>(Item.Header(sec)) + when (sec) {
            "Dekoder Enigma2" -> listOf(
                Item.Header("Dekoder"),
        Item.Text("Adres IP dekodera", { p.host }, { p.host = it.trim(); checkPluginQuiet() }, hint = "np. 192.168.1.20"),
        Item.Action("Szukaj dekoderów w sieci") { discover() },
        num("Port OpenWebif", { p.httpPort }, { p.httpPort = it }, 1..65535),
        num("Port streamingu", { p.streamPort }, { p.streamPort = it }, 1..65535),
        Item.Text("Użytkownik OpenWebif", { p.user }, { p.user = it.trim() }, hint = "puste = bez logowania"),
        Item.Text("Hasło OpenWebif", { p.pass }, { p.pass = it }, secret = true),
                Item.Header("Wtyczka EPG Scan na dekoderze"),
        Item.Action("Stan wtyczki", { if (p.pluginOk) "jest — ${p.pluginVersion}" else "brak (OpenWebif + FTP)" }) { checkPlugin() },
        num("Port wtyczki", { p.pluginPort }, { p.pluginPort = it }, 1024..65535),
        Item.Text("Token wtyczki", { p.pluginToken }, { p.pluginToken = it.trim() }, secret = true, hint = "puste = bez (sieć lokalna)"),
                Item.Header("FTP (potrzebne tylko, gdy na dekoderze nie ma wtyczki EPG Scan)"),
        Item.Text("FTP — użytkownik", { p.ftpUser }, { p.ftpUser = it.trim().ifEmpty { "root" } }),
        Item.Text("FTP — hasło", { p.ftpPass }, { p.ftpPass = it }, secret = true),
        Item.Text("FTP — katalog na dekoderze", { p.ftpDir }, { p.ftpDir = it.trim().ifEmpty { Prefs.DEFAULT_FTP_DIR } }),
                Item.Header("Synchronizacja z dekoderem"),
        Item.Toggle("Synchronizuj ustawienia i dane kanałów", { p.channelSync }, { p.channelSync = it }),
        Item.Text("Nazwa tego odbiornika (znacznik ${p.deviceTag})", { p.deviceName }, { p.deviceName = it.trim().removeSuffix("[${p.deviceTag}]").trim().ifEmpty { android.os.Build.MODEL } },
            hint = "np. Salon – Philips"),
        Item.Action("Synchronizuj teraz", { syncStatus() }) { syncNow() },
        Item.Action("Przenieś ustawienia z innego odbiornika") { chooseProfile() },
            )
            "Odtwarzanie i obraz" -> listOf(
        Item.Action("Silnik odtwarzania", { if (p.engine == "vlc") "VLC" else "ExoPlayer" }) { chooseEngine() },
    ) + (if (p.engine == "vlc") listOf(
        Item.Action("VLC: usuwanie przeplotu", { deintName(p.vlcDeint) }) { chooseDeint() },
        Item.Action("VLC: akceleracja sprzętowa", { hwName(p.vlcHwMode) }) { chooseHw() },
        Item.Action("VLC: format obrazu (chroma)", { p.vlcChroma }) { chooseChroma() },
    ) else listOf(
        Item.Toggle("Tunelowanie wideo", { p.tunneling }, { p.tunneling = it }),
        Item.Action("ExoPlayer: liczba klatek dla dekodera (eksperyment)", { if (p.exoFps > 0) "${p.exoFps} kl./s" else "nie podawaj" }) {
            val ids = intArrayOf(0, 25, 50)
            TvDialog.single(this, "Liczba klatek dla dekodera (eksperyment)", listOf("nie podawaj", "25 kl./s", "50 kl./s"),
                ids.indexOf(p.exoFps)) { w -> p.exoFps = ids[w]; markChanged("ExoPlayer fps = ${ids[w]}") }
        },
        Item.Toggle("Wznawiaj przy zacinaniu (utrata klatek)", { p.dropRestart }, { p.dropRestart = it }),
        num("Zapas w trakcie oglądania (ms)", { p.rebufferMs }, { p.rebufferMs = it }, 1000..15_000),
        Item.Toggle("Dobuduj zapas lekkim zwolnieniem (5%, tylko bez tunelowania)", { p.cushionSlow }, { p.cushionSlow = it }),
    )) + listOf(
        Item.Toggle("Wznawiaj zawieszony obraz (watchdog)", { p.watchdog }, { p.watchdog = it }),
        num("Zawieszenie po (s bez nowych klatek)", { p.stallSec }, { p.stallSec = it }, 1..30),
        num("Bufor startowy (ms) — szybki obraz", { p.bufferMs }, { p.bufferMs = it }, 250..10_000),
        Item.Action("Odświeżanie ekranu", { afrName(p.afrMode) }) { chooseAfr() },
                Item.Header("Timeshift (pauza i cofanie na żywo)"),
        Item.Action("Timeshift", { tsValue() }) { chooseTs() },
        Item.Action("Miejsce bufora timeshiftu", { tsPlace() }) { chooseTsDir() },
        Item.Action("Automatyczny timeshift", { tsDelayName(p.tsDelay) }) { chooseTsDelay() },
        Item.Action("Przewijanie ◀ ▶ (sekundy)", { "${p.tsStepSec} s" }) {
            val o = listOf(5, 10, 15, 30)
            TvDialog.single(this, "Przewijanie ◀ ▶", o.map { "$it s" }, o.indexOf(p.tsStepSec).coerceAtLeast(0)) { w -> p.tsStepSec = o[w]; markChanged("timeshift: krok ◀ ▶ = ${o[w]} s"); items = buildItems(); refresh() }
        },
        Item.Action("Przewijanie ▲ ▼ (minuty)", { "${p.tsStepMin} min" }) {
            val o = listOf(1, 3, 5, 10, 15)
            TvDialog.single(this, "Przewijanie ▲ ▼", o.map { "$it min" }, o.indexOf(p.tsStepMin).coerceAtLeast(0)) { w -> p.tsStepMin = o[w]; markChanged("timeshift: krok ▲ ▼ = ${o[w]} min"); items = buildItems(); refresh() }
        },
        Item.Toggle("Wstecz na pustym ekranie → na żywo", { p.tsBackLive }, { p.tsBackLive = it }),
        Item.Toggle("Zezwól na bufor w pamięci urządzenia", { p.tsInternalOk }, { v ->
            p.tsInternalOk = v
            if (!v && p.tsDir.isBlank()) p.tsMin = 0        // bez zgody i bez dysku — timeshift wyłączony
        }),
                Item.Header("Dźwięk"),
        Item.Text("Preferowany język", { p.audioLang }, { p.audioLang = it.trim().ifEmpty { "pol" } }, hint = "ISO 639-2, np. pol,eng"),
        Item.Toggle("Preferuj AC3 / E-AC3", { p.preferAc3 }, { p.preferAc3 = it }),
            )
            "Wygląd" -> listOf(
                Item.Header("Lista kanałów"),
                pctItem("Czcionka listy kanałów", { p.uiListFont }, { p.uiListFont = it }),
                pctItem("Ikony na liście kanałów", { p.uiListIcon }, { p.uiListIcon = it }),
                Item.Header("EPG (wydarzenia, EPG kanału, Multi EPG, szukanie)"),
                pctItem("Czcionka okien EPG", { p.uiEpgFont }, { p.uiEpgFont = it }),
                Item.Header("Pasek kanału"),
                pctItem("Czcionka paska kanału", { p.uiBannerFont }, { p.uiBannerFont = it }),
                pctItem("Ikona na pasku kanału", { p.uiBannerIcon }, { p.uiBannerIcon = it }),
            )
            "EPG i ikony kanałów" -> listOf(
        Item.Action("Aktualizacja z EPG Scan", {
            if (p.epgUpdateMode == EpgUpdatePolicy.STARTUP) "przy każdym uruchomieniu" else "raz dziennie"
        }) {
            val modes = listOf(EpgUpdatePolicy.DAILY, EpgUpdatePolicy.STARTUP)
            TvDialog.single(this, "Aktualizacja z EPG Scan",
                listOf("raz dziennie", "przy każdym uruchomieniu"),
                modes.indexOf(p.epgUpdateMode).coerceAtLeast(0)) { w ->
                p.epgUpdateMode = modes[w]
                markChanged("EPG Scan: aktualizacja ${if (w == 0) "raz dziennie" else "przy uruchomieniu"}")
                refresh()
            }
        },
        Item.Toggle("Pobieraj EPG codziennie (bez wtyczki EPG Scan)", { p.epgDaily }, { p.epgDaily = it }),
        Item.Text("Godzina pobierania EPG", { p.epgHour },
            { v -> if (Regex("^\\d{1,2}:\\d{2}$").matches(v.trim())) p.epgHour = v.trim() else toast("Format HH:MM") },
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME, hint = "HH:MM"),
        num("EPG na ile dni do przodu", { p.epgDays }, { p.epgDays = it }, 1..14),
        Item.Toggle("Ikony kanałów z dekodera", { p.picons }, { p.picons = it }),
        Item.Text("Katalog picon na dekoderze", { p.piconDir }, { p.piconDir = it.trim().trimEnd('/') },
            hint = "puste = odczytaj z ustawień Enigmy2"),
        Item.Action("Pobierz EPG i ikony teraz", { status.ifBlank { epgStatus() } }) { sync(false) },
        Item.Action("Pobierz ikony od nowa") { sync(true) },
            )
            "Ogólne" -> listOf(
        Item.Action("Widoczne bukiety", { if (p.hiddenBouquets.isEmpty()) "wszystkie" else "ukryte: ${p.hiddenBouquets.size}" }) { chooseBouquets() },
        Item.Action("Pozycje i kolejność menu") { showQuickMenuSettings() },
        Item.Action("Mapowanie klawiszy pilota") { showKeyMap() },
        Item.Action("Mapowanie OK i strzałek — bez paska") { showNavigationMap(false) },
        Item.Action("Mapowanie OK i strzałek — z paskiem kanału") { showNavigationMap(true) },
        Item.Toggle("Po uruchomieniu wróć do ostatniego kanału", { p.resumeLast }, { p.resumeLast = it }),
        Item.Toggle("Budź TV dla harmonogramu (eksperymentalne)", { p.wakeForZap }, { setWakeForZap(it) }),
        *(if (BuildConfig.WAKE_DIAGNOSTICS) arrayOf<Item>(Item.Action("Diagnostyka wybudzania", { if (p.wakeForZap) "status i test alarmu" else "najpierw włącz wybudzanie" }) {
            TvDialog.confirm(this, "Diagnostyka wybudzania", ZapWakeAlarms.diagnostics(this), listOf(
                TvDialog.Btn("Test 60 s") { toast(ZapWakeAlarms.scheduleTest(this)) },
                TvDialog.Btn("Wyślij log") { Tools.uploadLog(p, true) { toast(it) } },
                TvDialog.Btn("Zamknij"),
            ))
        }) else emptyArray()),
        Item.Action("Po uruchomieniu przełącz na kanał", {
            if (p.resumeLast) "nieaktywne (włączony powrót do ostatniego)" else p.startChannelName.ifBlank { "pierwszy kanał" }
        }) { if (p.resumeLast) toast("Najpierw wyłącz „Po uruchomieniu wróć do ostatniego kanału”") else chooseStartChannel() },
        Item.Toggle("Odwróć ▲ / ▼ przy zmianie kanału", { p.invertUpDown }, { p.invertUpDown = it }),
        num("Czas paska kanału (s)", { p.bannerSec }, { p.bannerSec = it }, 1..30),
        num("Czas paska timeshiftu (s)", { p.tsBannerSec }, { p.tsBannerSec = it }, 1..30),
        num("Czas listy kanałów (s, 0 = bez zamykania)", { p.channelListSec }, { p.channelListSec = it }, 0..600),
        num("Czas EPG wydarzenia (s, 0 = bez zamykania)", { p.epgEventSec }, { p.epgEventSec = it }, 0..600),
        num("Czas EPG kanału (s, 0 = bez zamykania)", { p.epgChannelSec }, { p.epgChannelSec = it }, 0..600),
        num("Czas Multi EPG (s, 0 = bez zamykania)", { p.epgMultiSec }, { p.epgMultiSec = it }, 0..600),
        Item.Text("PIN do ustawień", { p.pin }, { p.pin = it.trim() },
            InputType.TYPE_CLASS_NUMBER, secret = true, hint = "puste = bez blokady"),
        Item.Action("Pytaj o PIN ponownie po", {
            if (p.pin.isEmpty()) "nieaktywne (brak PIN-u)" else PinLock.label(p.pinMin) + " • wyjście z aplikacji zawsze blokuje"
        }) { if (p.pin.isEmpty()) toast("Najpierw ustaw PIN") else choosePinTime() },
            )

            "Informacje i aktualizacje" -> listOf(
                Item.Header("Aplikacja E2Stream"),
        Item.Action("Kanał aktualizacji", { if (p.updateChannel == "beta") "beta (testowy)" else "stabilny" }) {
            TvDialog.single(this, "Kanał aktualizacji", listOf("Stabilny", "Beta (prerelease)"),
                if (p.updateChannel == "beta") 1 else 0) { w ->
                p.updateChannel = if (w == 1) "beta" else "stable"
                markChanged("kanał aktualizacji = ${p.updateChannel}")
            }
        },
        Item.Action("Sprawdź aktualizację", { "wersja ${Tools.appVersion(this)}" }) { Tools.checkUpdate(this, true) { toast(it) } },
        Item.Toggle("Automatyczna aktualizacja", { p.autoUpdate }, { p.autoUpdate = it }),
                Item.Action("Opis aplikacji (README)") { showReadme("E2Stream", "e2stream/README.md") },
                Item.Header("Wtyczka EPG Scan"),
                Item.Action("Wtyczka na dekoderze", { if (p.pluginOk) "wersja ${p.pluginVersion}" else "brak — szczegóły w sekcji Dekoder Enigma2" }) { checkPlugin() },
                Item.Action("Opis wtyczki (README)") { showReadme("EPG Scan", "epgscan/EPGScan/README.md") },
            )
            else -> listOfNotNull(
        Item.Toggle("Statystyki na ekranie", { p.showStats }, { p.showStats = it }),
                if (p.pluginOk) null else Item.Action("Bez wtyczki EPG Scan wysyłanie na dekoder wymaga FTP",
                    { "ustaw w sekcji Dekoder Enigma2" }) { openSection("Dekoder Enigma2") },
        Item.Action("Wyślij log na dekoder", { "${p.ftpDir}/${Tools.logName(p)}" }) { Tools.uploadLog(p, true) { toast(it) } },
        Item.Action("Wyślij zrzuty TS na dekoder") { Tools.uploadDumps(this, p) { toast(it) } },
        Item.Action("Miejsce zapisu logów i zrzutów", { Storage.dir(this, p).absolutePath }) { chooseStorage() },
        Item.Toggle("Czyść strumień (uszkodzone pakiety, błędne znaczniki czasu)", { p.cleanTs }, { p.cleanTs = it }),
        Item.Toggle("Zapisuj zrzut TS przy zawieszeniu", { p.saveTs }, { p.saveTs = it }),
        num("Rozmiar zrzutu TS (MB)", { p.tsRingMb }, { p.tsRingMb = it }, 2..64),
        Item.Toggle("Wysyłaj log na dekoder (co 2 min)", { p.logFtp }, { p.logFtp = it }),
        Item.Toggle("Wysyłaj zrzuty TS na dekoder", { p.tsFtp }, { p.tsFtp = it }),
            )
        }
    }

    /** README z GitHuba (kopia aktualnej wersji opisu) w przewijanym oknie. */
    private fun showReadme(title: String, path: String) {
        toast("Pobieram opis…")
        Thread {
            val text = try {
                val c = java.net.URL("https://raw.githubusercontent.com/JarDol/enigma2-epgscan/main/$path").openConnection()
                    as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000
                c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) { "Nie udało się pobrać opisu: ${e.message}" }
            // prosty tekst z Markdown: bez znaczników nagłówków, pogrubień i kodu
            val plain = text.lines().joinToString("\n") { l ->
                l.replace(Regex("^#{1,6}\\s*"), "").replace("**", "").replace("`", "")
                    .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                TvDialog.text(this, "Opis: $title", plain)
            }
        }.start()
    }

    private fun pctItem(label: String, get: () -> Int, set: (Int) -> Unit) =
        Item.Action(label, { "${get()}%" }) {
            val opts = intArrayOf(50, 60, 70, 80, 90, 100, 110, 120, 140)
            TvDialog.single(this, label, opts.map { "$it%" }, opts.indexOf(get())) { w ->
                set(opts[w]); markChanged("$label = ${opts[w]}%")
            }
        }

    private fun openSection(name: String) {
        section = name
        if (name == "Dekoder Enigma2" || name == "Informacje i aktualizacje" || name == "Logi i zrzuty") checkPluginQuiet()
        items = buildItems()
        refresh()
        listView.setSelection(items.indexOfFirst { it !is Item.Header }.coerceAtLeast(0))
    }

    private fun quickMenuEntriesInOrder(): MutableList<QuickMenu.Entry> {
        return QuickMenu.allInOrder(p.quickMenuOrder).toMutableList()
    }

    private fun showQuickMenuSettings(selected: Int = 0) {
        val entries = quickMenuEntriesInOrder()
        val visible = p.quickMenuVisible.toMutableSet()
        val rows = entries.mapIndexed { index, entry ->
            TvDialog.Item(entry.label, "${index + 1}.  ${if (entry.id in visible) "widoczne" else "ukryte"}")
        }
        TvDialog.list(this, "Pozycje i kolejność menu", rows, selected.coerceIn(rows.indices),
            hint = "OK opcje  •  WSTECZ wróć", widthFrac = 0.6f) { index ->
            val entry = entries[index]
            val options = buildList {
                add(if (entry.id in visible) "Ukryj pozycję" else "Pokaż pozycję")
                if (index > 0) add("Przesuń wyżej")
                if (index < entries.lastIndex) add("Przesuń niżej")
            }
            TvDialog.list(this, entry.label, options.map { TvDialog.Item(it) },
                hint = "OK wybierz  •  WSTECZ wróć", widthFrac = 0.5f) { choice ->
                when (options[choice]) {
                    "Ukryj pozycję" -> visible.remove(entry.id)
                    "Pokaż pozycję" -> visible.add(entry.id)
                    "Przesuń wyżej" -> entries.add(index - 1, entries.removeAt(index))
                    "Przesuń niżej" -> entries.add(index + 1, entries.removeAt(index))
                }
                p.quickMenuVisible = visible
                QuickMenu.saveOrder(p, entries)
                markChanged("menu: ${entry.label} ${options[choice].lowercase()}")
                showQuickMenuSettings(entries.indexOfFirst { it.id == entry.id })
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val s = section
        if (s != null) {
            section = null
            items = buildItems()
            refresh()
            listView.setSelection(sections.indexOfFirst { it.first == s }.coerceAtLeast(0))
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun afrName(m: Int) = when (m) { 1 -> "zawsze 50 Hz"; 2 -> "automatycznie wg treści"; else -> "bez zmian" }

    private fun epgStatus(): String {
        val at = p.lastEpgFetch
        val last = if (at > 0) SimpleDateFormat("dd.MM HH:mm", Locale("pl")).format(Date(at)) else "nigdy"
        return "ostatnio: $last • kanałów: ${store.count()}"
    }

    // ------------------------------------------------------------ cykl życia

    private val relock = object : Runnable {
        override fun run() {
            if (!isFinishing && !asking && !PinLock.isUnlocked(p)) {
                asking = true
                PinLock.ask(this@SettingsActivity, p, cancelable = false,
                    onCancel = { asking = false; finish() }) { asking = false }
            }
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        p = Prefs(this)
        p.migrate()
        AppLog.init(this, Storage.dir(this, p))
        store = EpgStore(this)
        items = buildItems()
        findViewById<TextView>(R.id.version).text = "E2Stream ${Tools.appVersion(this)} — ustawienia"
        listView = findViewById(R.id.settingsList)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ -> onPick(items[pos]) }
        listView.setSelection(0)
        listView.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(relock)
        ui.postDelayed(relock, 2000)
        when (wakePermissionFlow) {
            "exact" -> {
                wakePermissionFlow = null
                if (ZapWakeAlarms.canScheduleExact(this)) continueWakeEnable()
                else { pendingWakeEnable = false; toast("Nie przyznano dostępu do dokładnych alarmów") }
            }
            "fullscreen" -> {
                wakePermissionFlow = null
                fullScreenPermissionAsked = true
                continueWakeEnable()
            }
        }
    }

    private fun setWakeForZap(enabled: Boolean) {
        if (!enabled) {
            pendingWakeEnable = false
            p.wakeForZap = false
            ZapWakeAlarms.sync(this)
            toast("Wybudzanie harmonogramów wyłączone")
            return
        }
        pendingWakeEnable = true
        fullScreenPermissionAsked = false
        continueWakeEnable()
    }

    private fun continueWakeEnable() {
        if (!pendingWakeEnable) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ZapWakeAlarms.canScheduleExact(this)) {
            try {
                wakePermissionFlow = "exact"
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                wakePermissionFlow = null
                pendingWakeEnable = false
                toast("To urządzenie nie udostępnia dokładnych alarmów")
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 34 && !ZapWakeAlarms.canUseFullScreen(this) && !fullScreenPermissionAsked) {
            try {
                wakePermissionFlow = "fullscreen"
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:$packageName")))
                return
            } catch (_: Exception) {
                wakePermissionFlow = null
                fullScreenPermissionAsked = true
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 741)
            return
        }
        pendingWakeEnable = false
        p.wakeForZap = true
        ZapWakeAlarms.sync(this)
        markChanged("Budź TV dla harmonogramu = włączone")
        toast("Wybudzanie harmonogramów włączone")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 741) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) continueWakeEnable()
        else { pendingWakeEnable = false; toast("Bez powiadomień przypomnienie może nie pojawić się na ekranie") }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(relock)
        if (changed) { p.bump(); changed = false }
        SyncManager.syncAsync() // zmiany od razu na dekoder
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun refresh() = adapter.notifyDataSetChanged()

    private fun markChanged(what: String) {
        changed = true
        AppLog.i("ustawienia: $what")
        refresh()
    }

    // ------------------------------------------------------------ edycja

    private fun onPick(it: Item) {
        when (it) {
            is Item.Header -> {}
            is Item.Toggle -> { it.set(!it.get()); markChanged("${it.label} = ${it.get()}") }
            is Item.Action -> it.run()
            is Item.Text -> editText(it)
        }
    }

    private fun editText(it: Item.Text) {
        val et = EditText(this)
        et.inputType = if (it.secret) {
            if ((it.input and InputType.TYPE_CLASS_NUMBER) != 0) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else it.input
        et.setText(it.get())
        et.setSelection(et.text.length)
        et.hint = it.hint
        et.textSize = 22f
        et.imeOptions = EditorInfo.IME_ACTION_DONE
        et.setSingleLine(true)
        TvDialog.input(this, it.label, it.hint, et) {
            it.set(et.text.toString())
            markChanged(it.label + if (it.secret) " (zmieniono)" else " = ${it.get()}")
        }
    }

    // ------------------------------------------------------------ timeshift

    /** Czy wybrany jest dysk USB / karta SD (nie pamięć urządzenia). */
    private fun tsUsb(): Boolean = p.tsDir.isNotBlank() && p.tsDir != TimeshiftBuffer.internalBase(this).absolutePath

    private fun tsFit(): Int = TimeshiftBuffer.dir(this, p)?.let { TimeshiftBuffer.fitMinutes(it) } ?: 0

    private fun tsValue(): String {
        val d = TimeshiftBuffer.dir(this, p)
            ?: return if (p.tsDir.isNotBlank()) "dysk USB niedostępny" else "wybierz miejsce bufora (dysk USB albo zgoda na pamięć urządzenia)"
        val fit = TimeshiftBuffer.fitMinutes(d)
        val rec = TimeshiftBuffer.recommended(fit, tsUsb())
        val cur = if (p.tsMin <= 0) "wyłączony" else "${p.tsMin} min"
        return "$cur  •  miejsce na ~${fmtMin(fit)}" + if (rec > 0) ", zalecane ${fmtMin(rec)}" else " — za mało miejsca"
    }

    private fun tsDelayName(s: Int) = when { s == 0 -> "od razu"; s < 0 -> "wyłączony (bufor rusza dopiero po pauzie)"; else -> "po $s s" }

    private fun chooseTsDelay() {
        val opts = TS_DELAYS
        TvDialog.single(this, "Automatyczny timeshift", opts.map { tsDelayName(it) }, opts.indexOf(p.tsDelay).coerceAtLeast(0)) { w ->
            p.tsDelay = opts[w]
            markChanged("timeshift: start bufora = ${tsDelayName(opts[w])}")
            items = buildItems(); refresh()
        }
    }

    private fun fmtMin(m: Int) = if (m >= 120 && m % 60 == 0) "${m / 60} h" else "$m min"

    private fun tsPlace(): String {
        if (p.tsDir.isBlank()) return if (p.tsInternalOk) "pamięć urządzenia  •  wolne ${TimeshiftBuffer.gb(TimeshiftBuffer.internalBase(this).usableSpace)}" else "nie wybrano"
        if (!TimeshiftBuffer.available(p.tsDir)) return "${storageName(p.tsDir)} — niedostępny"
        return "${storageName(p.tsDir)}  •  wolne ${TimeshiftBuffer.gb(java.io.File(p.tsDir).usableSpace)}"
    }

    private fun storageName(path: String): String =
        Storage.options(this).firstOrNull { it.path == path }?.label?.substringBefore("\n")?.substringBefore(" —") ?: path

    private fun chooseTs() {
        if (TimeshiftBuffer.dir(this, p) == null) {
            toast("Najpierw wybierz miejsce bufora: dysk USB albo zezwól na pamięć urządzenia")
            chooseTsDir(); return
        }
        val usb = tsUsb()
        val fit = tsFit()
        val rec = TimeshiftBuffer.recommended(fit, usb)
        val ch = TimeshiftBuffer.choices(usb)
        val items = ch.map { m ->
            val label = if (m == 0) "Wyłączony" else fmtMin(m)
            val sub = when {
                m == 0 -> ""
                m > fit -> "za mało miejsca"
                m == rec -> "zalecane (najdłuższy, który się mieści)"
                else -> String.format(Locale("pl"), "~%.1f GB", m * TimeshiftBuffer.MB_PER_MIN / 1000.0)
            }
            TvDialog.Item(label, sub, if (m == p.tsMin) 3 else 0)
        }
        TvDialog.list(this, "Timeshift — miejsce na ~${fmtMin(fit)}", items, ch.indexOf(p.tsMin).coerceAtLeast(0), widthFrac = 0.6f) { w ->
            val m = ch[w]
            if (m > fit) { toast("Za mało miejsca na ${fmtMin(m)} — wybierz krótszy czas albo inny nośnik"); return@list }
            p.tsMin = m
            markChanged("timeshift = $m min")
            refresh()
        }
    }

    private fun chooseTsDir() {
        val internal = TimeshiftBuffer.internalBase(this).absolutePath
        val opts = Storage.options(this).filter { it.path != filesDir.absolutePath }
        val items = opts.map { o ->
            val isInt = o.path == internal
            val usable = isInt && !p.tsInternalOk
            val fit = TimeshiftBuffer.fitMinutes(java.io.File(o.path, "timeshift"))
            val rec = TimeshiftBuffer.recommended(fit, !isInt)
            val cur = if (isInt) p.tsDir.isBlank() && p.tsInternalOk else o.path == p.tsDir
            TvDialog.Item(o.label.substringBefore("\n").substringBefore(" —"),
                if (usable) "wymaga zgody (zapis ciągły zużywa pamięć flash)"
                else "wolne ${TimeshiftBuffer.gb(java.io.File(o.path).usableSpace)} → ~${fmtMin(fit)}" + if (rec > 0) " (zalecane ${fmtMin(rec)})" else "",
                if (cur) 3 else 0)
        }
        TvDialog.list(this, "Miejsce bufora timeshiftu", items, -1, widthFrac = 0.6f) { w ->
            val o = opts[w]
            if (o.path == internal) {
                if (!p.tsInternalOk) {
                    TvDialog.confirm(this, "Bufor w pamięci urządzenia",
                        "Timeshift zapisuje strumień bez przerwy (kanał HD to kilka GB na godzinę). Pamięć flash " +
                            "telewizora ma ograniczoną liczbę zapisów — przy codziennym, wielogodzinnym użyciu może się szybciej zużyć.\n\n" +
                            "Bezpieczniej użyć dysku USB. Zezwolić na pamięć urządzenia?",
                        listOf(TvDialog.Btn("Zezwól") { p.tsInternalOk = true; setTsDir("") }, TvDialog.Btn("Nie")))
                } else setTsDir("")
            } else setTsDir(o.path)
        }
    }

    private fun setTsDir(path: String) {
        p.tsDir = path
        val fit = tsFit()
        val rec = TimeshiftBuffer.recommended(fit, tsUsb())
        if (p.tsMin > fit) {
            p.tsMin = rec
            toast(if (rec > 0) "Zmieści się ~${fmtMin(fit)} — ustawiono ${fmtMin(rec)}" else "Za mało miejsca — timeshift wyłączony")
        } else if (p.tsMin == 0 && rec > 0) {
            toast("Zmieści się ~${fmtMin(fit)} — zalecane ${fmtMin(rec)} (włącz w pozycji „Timeshift”)")
        }
        markChanged("timeshift: miejsce = ${path.ifBlank { "pamięć urządzenia" }}")
        items = buildItems(); refresh()
    }

    private fun chooseAfr() {
        val opts = arrayOf("bez zmian", "zawsze 50 Hz", "automatycznie wg treści")
        TvDialog.single(this, "Odświeżanie ekranu", opts.toList(), p.afrMode) { w -> p.afrMode = w; markChanged("odświeżanie = ${opts[w]}") }
    }

    private fun choosePinTime() {
        val mins = intArrayOf(1, 5, 15, 30, 60, 0)
        TvDialog.single(this, "Po odblokowaniu PIN-em pytaj ponownie po", mins.map { PinLock.label(it) }, mins.indexOf(p.pinMin)) { w ->
            p.pinMin = mins[w]
            if (PinLock.isUnlocked(p)) PinLock.unlock(p)      // nowy czas liczony od teraz
            refresh()
        }
    }

    private fun chooseEngine() {
        val ids = arrayOf("exo", "vlc")
        val opts = arrayOf("ExoPlayer (tunelowanie, najszybszy start)", "VLC (inne usuwanie przeplotu i odmierzanie klatek)")
        TvDialog.single(this, "Silnik odtwarzania na tym odbiorniku", opts.toList(), ids.indexOf(p.engine)) { w ->
            p.engine = ids[w]; markChanged("silnik = ${ids[w]}")
            items = buildItems(); refresh()
        }
    }

    private val hwIds = arrayOf("auto", "off", "dec", "full")
    private fun hwName(id: String) = when (id) {
        "off" -> "wyłączona"; "dec" -> "dekodowanie (obraz przez VLC)"; "full" -> "pełna"; else -> "automatyczna (zalecane)"
    }

    private fun chooseHw() {
        TvDialog.single(this, "VLC: akceleracja sprzętowa", hwIds.map { hwName(it) }, hwIds.indexOf(p.vlcHwMode)) { w ->
            p.vlcHwMode = hwIds[w]; markChanged("VLC akceleracja = ${hwIds[w]}")
        }
    }

    private fun chooseChroma() {
        val ids = arrayOf("RV32", "RV16", "YV12")
        val names = arrayOf("RGB 32-bit (zalecane)", "RGB 16-bit", "YUV (YV12)")
        TvDialog.single(this, "VLC: format obrazu", names.toList(), ids.indexOf(p.vlcChroma)) { w ->
            p.vlcChroma = ids[w]; markChanged("VLC chroma = ${ids[w]}")
        }
    }

    private val deintIds = arrayOf("default", "auto", "off", "yadif", "yadif2x", "bob", "linear", "blend", "x")
    private fun deintName(id: String) = when (id) {
        "default" -> "domyślne VLC (zalecane)"; "auto" -> "automatycznie"; "off" -> "wyłączone"; "yadif2x" -> "yadif 2x (50 kl./s)"; "bob" -> "bob (50 kl./s)"
        else -> id
    }

    private fun chooseDeint() {
        TvDialog.single(this, "VLC: usuwanie przeplotu", deintIds.map { deintName(it) }, deintIds.indexOf(p.vlcDeint)) { w ->
            p.vlcDeint = deintIds[w]; markChanged("VLC przeplot = ${deintIds[w]}")
        }
    }

    private fun chooseStorage() {
        val opts = Storage.options(this)
        TvDialog.single(this, "Miejsce zapisu logów i zrzutów TS", opts.map { it.label }, opts.indexOfFirst { it.path == p.storageDir }) { w ->
            p.storageDir = opts[w].path; markChanged("zapis = ${opts[w].path}")
        }
    }

    private fun discover() {
        toast("Szukam dekoderów w sieci…")
        Thread {
            val found = Discovery.scan(p.httpPort) { _, _ -> }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (found.isEmpty()) {
                    toast("Nie znaleziono dekodera (port ${p.httpPort})"); return@runOnUiThread
                }
                TvDialog.list(this, "Znalezione dekodery", found.map { TvDialog.Item(it.ip, it.label) }) { w ->
                    p.host = found[w].ip
                    markChanged("dekoder = ${found[w].ip} (${found[w].label})")
                    checkPluginQuiet()
                }
            }
        }.start()
    }

    private fun chooseBouquets() {
        toast("Pobieram bukiety…")
        Thread {
            val list = try { OpenWebif(p).bouquets() + Bouquet("Nagrania (z dysku dekodera)", REC_BOUQUET) } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (list.isNullOrEmpty()) { toast("Nie udało się pobrać bukietów z dekodera"); return@runOnUiThread }
                val hidden = p.hiddenBouquets
                val checked = BooleanArray(list.size) { list[it].ref !in hidden }
                TvDialog.multi(this, "Widoczne bukiety", list.map { it.name }, checked) { st ->
                    p.hiddenBouquets = list.indices.filter { !st[it] }.map { list[it].ref }.toSet()
                    markChanged("bukiety: ukryte ${p.hiddenBouquets.size}")
                }
            }
        }.start()
    }

    /** Wybór kanału startowego: bukiet → kanał. */
    private fun chooseStartChannel() {
        toast("Pobieram bukiety…")
        Thread {
            val api = OpenWebif(p)
            val bq = try {
                val all = api.bouquets(); val hidden = p.hiddenBouquets
                all.filter { it.ref !in hidden }.ifEmpty { all }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (bq.isNullOrEmpty()) { toast("Nie udało się pobrać bukietów"); return@runOnUiThread }
                TvDialog.single(this, "Kanał startowy — bukiet", bq.map { it.name }, -1) { bi ->
                        Thread {
                            val ch = try { api.channels(bq[bi]) } catch (e: Exception) { emptyList() }
                            runOnUiThread {
                                if (ch.isEmpty()) { toast("Pusty bukiet"); return@runOnUiThread }
                                val cur = ch.indexOfFirst { OpenWebif.key(it.ref) == OpenWebif.key(p.startChannel) }
                                TvDialog.single(this, "Kanał startowy — ${bq[bi].name}", ch.map { "${it.num}  ${it.name}" }, cur) { ci ->
                                    p.startBouquet = bq[bi].ref
                                    p.startChannel = ch[ci].ref
                                    p.startChannelName = "${ch[ci].name} (${bq[bi].name})"
                                    markChanged("kanał startowy = ${p.startChannelName}")
                                }
                            }
                        }.start()
                    }
            }
        }.start()
    }

    /** Sprawdzenie wtyczki w tle (po wyborze dekodera i przy wejściu w sekcje) — stan od razu w ustawieniach. */
    private fun checkPluginQuiet() {
        if (p.host.isBlank()) return
        Thread {
            val was = p.pluginOk
            val ok = PluginApi.check(p)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (ok != was) AppLog.i("ustawienia: wtyczka EPG Scan ${if (ok) p.pluginVersion else "brak"}")
                items = buildItems(); refresh()
            }
        }.start()
    }

    private fun checkPlugin() {
        toast("Sprawdzam wtyczkę EPG Scan…")
        Thread {
            val ok = PluginApi.check(p)
            runOnUiThread {
                toast(if (ok) "EPG Scan ${p.pluginVersion} — kanały, PID-y i synchronizacja przez wtyczkę"
                else "Brak wtyczki EPG Scan (port ${p.pluginPort}) — używam OpenWebif i FTP")
                markChanged("wtyczka: ${if (ok) p.pluginVersion else "brak"}")
            }
        }.start()
    }

    private fun syncStatus(): String {
        val at = SyncManager.lastSyncAt
        val last = if (at > 0) SimpleDateFormat("dd.MM HH:mm", Locale("pl")).format(Date(at)) else "nigdy"
        return if (SyncManager.running) "trwa…" else "ostatnio: $last" + (if (SyncManager.dirty) " • są zmiany" else "")
    }

    private fun syncNow() {
        toast("Synchronizuję…")
        SyncManager.syncAsync { r -> toast("Synchronizacja: $r"); refresh() }
    }

    private fun chooseProfile() {
        toast("Pobieram profile odbiorników…")
        Thread {
            val list = try { SyncManager.profiles(p) } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val others = list?.filter { !it.file.contains(SyncManager.deviceId()) }
                if (others.isNullOrEmpty()) { toast("Brak profili innych odbiorników na dekoderze"); return@runOnUiThread }
                val fmt = SimpleDateFormat("dd.MM HH:mm", Locale("pl"))
                TvDialog.list(this, "Przenieś ustawienia z odbiornika",
                    others.map { TvDialog.Item(it.name.ifBlank { it.model }, "${it.model}, ${fmt.format(Date(it.updated))}") }) { w ->
                    val n = SyncManager.restore(others[w])
                    markChanged("przeniesiono $n ustawień z ${others[w].name}")
                    toast("Przeniesiono $n ustawień z „${others[w].name}”")
                    recreate()
                }
            }
        }.start()
    }

    private fun sync(reloadIcons: Boolean) {
        if (reloadIcons) { Picons.clear(this); p.picons = true }
        status = "pobieranie…"
        refresh()
        EpgSync.run(applicationContext, p, store, true, { msg -> runOnUiThread { status = msg; refresh() } }) { r ->
            runOnUiThread { status = ""; toast(r); markChanged("EPG/ikony pobrane") }
        }
    }

    // ------------------------------------------------------------ mapowanie klawiszy

    private fun showKeyMap() {
        val entries = KeyMap.load(p).entries.filter { it.key !in KeyMap.navigation }
        val rows = listOf(TvDialog.Item("+ Dodaj / zmień klawisz", "naciśnij go na pilocie")) +
            entries.map { TvDialog.Item(KeyMap.keyName(it.key), it.value.label) } +
            listOf(TvDialog.Item("Przywróć domyślne"))
        TvDialog.list(this, "Mapowanie klawiszy (podczas oglądania)", rows) { w ->
            when (w) {
                0 -> captureKey()
                rows.size - 1 -> { p.keyMap = ""; toast("Przywrócono domyślne"); showKeyMap() }
                else -> chooseAct(entries[w - 1].key)
            }
        }
    }

    private fun captureKey() {
        val dlg = TvDialog.panel(this, "Naciśnij klawisz na pilocie…", "WSTECZ anuluj", widthFrac = 0.45f) { ctx, _ ->
            TextView(ctx).apply {
                text = "Klawisz, który ma wywoływać wybraną akcję podczas oglądania."
                textSize = 17f; setTextColor(TvDialog.GREY); isFocusable = true
            }
        }
        dlg.setOnKeyListener { d, code, e ->
            if (e.action != KeyEvent.ACTION_UP) return@setOnKeyListener true
            if (code == KeyEvent.KEYCODE_BACK) { d.dismiss(); return@setOnKeyListener true }
            if (code in KeyMap.navigation) { toast("Użyj mapowania OK i strzałek"); return@setOnKeyListener true }
            if (code in KeyMap.fixed) { toast("Klawisza ${KeyMap.keyName(code)} nie można przypisać"); return@setOnKeyListener true }
            d.dismiss()
            chooseAct(code)
            true
        }
    }

    private fun chooseAct(code: Int) {
        val m = KeyMap.load(p)
        val acts = Act.values().filter { it != Act.DEFAULT_OK }
        val cur = acts.indexOf(m[code] ?: Act.NONE)
        TvDialog.single(this, "Klawisz ${KeyMap.keyName(code)}", acts.map { it.label }, cur, onCancel = { showKeyMap() }) { w ->
            m[code] = acts[w]
            KeyMap.save(p, m)
            AppLog.i("mapowanie: ${KeyMap.keyName(code)} → ${acts[w].name}")
            showKeyMap()
        }
    }

    private fun showNavigationMap(withBanner: Boolean) {
        val stored = if (withBanner) p.keyMapBanner else p.keyMapNoBanner
        val map = KeyMap.navigationMap(KeyMap.load(p), stored)
        val keys = KeyMap.navigation.keys.toList()
        val title = if (withBanner) "OK i strzałki — pasek kanału" else "OK i strzałki — bez paska"
        val rows = keys.map { TvDialog.Item(KeyMap.navigation.getValue(it), map.getValue(it).label) } +
            TvDialog.Item("Przywróć domyślne")
        TvDialog.list(this, title, rows) { selected ->
            if (selected == keys.size) {
                if (withBanner) p.keyMapBanner = "" else p.keyMapNoBanner = ""
                markChanged("mapowanie: $title — przywrócono domyślne")
                showNavigationMap(withBanner)
            } else {
                val code = keys[selected]
                val acts = Act.values().filter { it != Act.DEFAULT_OK || code == KeyEvent.KEYCODE_DPAD_CENTER }
                val current = acts.indexOf(map.getValue(code))
                TvDialog.single(this, "${KeyMap.navigation.getValue(code)} — $title",
                    acts.map { it.label }, current, onCancel = { showNavigationMap(withBanner) }) { index ->
                    val overrides = KeyMap.parse(stored)
                    overrides[code] = acts[index]
                    if (withBanner) p.keyMapBanner = KeyMap.encode(overrides) else p.keyMapNoBanner = KeyMap.encode(overrides)
                    markChanged("mapowanie: $title ${KeyMap.navigation.getValue(code)} → ${acts[index].name}")
                    showNavigationMap(withBanner)
                }
            }
        }
    }

    // ------------------------------------------------------------ widok listy

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 0 else 1
        override fun isEnabled(position: Int) = items[position] !is Item.Header
        override fun areAllItemsEnabled() = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val it = items[position]
            if (it is Item.Header) {
                val v = (convertView ?: layoutInflater.inflate(R.layout.item_setting_header, parent, false)) as TextView
                v.text = it.label
                return v
            }
            val v = convertView ?: layoutInflater.inflate(R.layout.item_setting, parent, false)
            v.findViewById<TextView>(R.id.label).text = it.label
            val value = v.findViewById<TextView>(R.id.value)
            value.text = when (it) {
                is Item.Toggle -> if (it.get()) "✔ włączone" else "✘ wyłączone"
                is Item.Text -> if (it.secret) (if (it.get().isEmpty()) "—" else "••••") else it.get().ifEmpty { "—" }
                is Item.Action -> it.value()
                else -> ""
            }
            value.setTextColor(
                when {
                    it is Item.Toggle && it.get() -> 0xFF81C784.toInt()
                    it is Item.Toggle -> 0xFFE57373.toInt()
                    else -> 0xFFB0BEC5.toInt()
                }
            )
            return v
        }
    }
}

private val TS_DELAYS = listOf(-1, 0, 5, 10, 15, 30)
