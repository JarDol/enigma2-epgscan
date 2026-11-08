package pl.jardol.e2stream

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        Item.Text("Adres IP dekodera", { p.host }, { p.host = it.trim() }, hint = "np. 192.168.1.20"),
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
        Item.Action("VLC: usuwanie przeplotu", { deintName(p.vlcDeint) }) { chooseDeint() },
        Item.Action("VLC: akceleracja sprzętowa", { hwName(p.vlcHwMode) }) { chooseHw() },
        Item.Action("VLC: format obrazu (chroma)", { p.vlcChroma }) { chooseChroma() },
        Item.Toggle("Tunelowanie wideo (ExoPlayer)", { p.tunneling }, { p.tunneling = it }),
        Item.Action("ExoPlayer: liczba klatek dla dekodera (eksperyment)", { if (p.exoFps > 0) "${p.exoFps} kl./s" else "nie podawaj" }) {
            val ids = intArrayOf(0, 25, 50)
            AlertDialog.Builder(this).setTitle("Liczba klatek dla dekodera (eksperyment)")
                .setSingleChoiceItems(arrayOf("nie podawaj", "25 kl./s", "50 kl./s"), ids.indexOf(p.exoFps).coerceAtLeast(0)) { d, w ->
                    p.exoFps = ids[w]; d.dismiss(); markChanged("ExoPlayer fps = ${ids[w]}")
                }.show()
        },
        Item.Toggle("Wznawiaj zawieszony obraz (watchdog)", { p.watchdog }, { p.watchdog = it }),
        Item.Toggle("Wznawiaj przy zacinaniu (utrata klatek)", { p.dropRestart }, { p.dropRestart = it }),
        num("Zawieszenie po (s bez nowych klatek)", { p.stallSec }, { p.stallSec = it }, 1..30),
        num("Bufor startowy (ms) — szybki obraz", { p.bufferMs }, { p.bufferMs = it }, 250..10_000),
        num("Zapas w trakcie oglądania (ms)", { p.rebufferMs }, { p.rebufferMs = it }, 1000..15_000),
        Item.Toggle("Dobuduj zapas lekkim zwolnieniem (5%, tylko bez tunelowania)", { p.cushionSlow }, { p.cushionSlow = it }),
        Item.Action("Odświeżanie ekranu", { afrName(p.afrMode) }) { chooseAfr() },
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
        Item.Action("Mapowanie klawiszy pilota") { showKeyMap() },
        Item.Toggle("Po uruchomieniu wróć do ostatniego kanału", { p.resumeLast }, { p.resumeLast = it }),
        Item.Action("Po uruchomieniu przełącz na kanał", {
            if (p.resumeLast) "nieaktywne (włączony powrót do ostatniego)" else p.startChannelName.ifBlank { "pierwszy kanał" }
        }) { if (p.resumeLast) toast("Najpierw wyłącz „Po uruchomieniu wróć do ostatniego kanału”") else chooseStartChannel() },
        Item.Toggle("Odwróć ▲ / ▼ przy zmianie kanału", { p.invertUpDown }, { p.invertUpDown = it }),
        num("Czas paska kanału (s)", { p.bannerSec }, { p.bannerSec = it }, 1..30),
        Item.Text("PIN do ustawień", { p.pin }, { p.pin = it.trim() },
            InputType.TYPE_CLASS_NUMBER, secret = true, hint = "puste = bez blokady; odblokowanie na 1 min"),
            )

            "Informacje i aktualizacje" -> listOf(
                Item.Header("Aplikacja E2Stream"),
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
                val tv = TextView(this).apply {
                    this.text = plain; textSize = 17f; setTextColor(0xFFFFFFFF.toInt())
                    setPadding(40, 20, 40, 20); setTextIsSelectable(false)
                }
                val sv = android.widget.ScrollView(this).apply { addView(tv); isFocusable = true }
                AlertDialog.Builder(this).setTitle("Opis: $title").setView(sv).setPositiveButton("Zamknij", null).show()
                sv.requestFocus()
            }
        }.start()
    }

    private fun pctItem(label: String, get: () -> Int, set: (Int) -> Unit) =
        Item.Action(label, { "${get()}%" }) {
            val opts = intArrayOf(50, 60, 70, 80, 90, 100, 110, 120, 140)
            AlertDialog.Builder(this).setTitle(label)
                .setSingleChoiceItems(opts.map { "$it%" }.toTypedArray(), opts.indexOf(get()).coerceAtLeast(0)) { d, w ->
                    set(opts[w]); d.dismiss(); markChanged("$label = ${opts[w]}%")
                }.show()
        }

    private fun openSection(name: String) {
        section = name
        items = buildItems()
        refresh()
        listView.setSelection(items.indexOfFirst { it !is Item.Header }.coerceAtLeast(0))
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
        lateinit var dlg: AlertDialog
        fun accept() {
            it.set(et.text.toString())
            dlg.dismiss()
            markChanged(it.label + if (it.secret) " (zmieniono)" else " = ${it.get()}")
        }
        dlg = AlertDialog.Builder(this)
            .setTitle(it.label)
            .setMessage(it.hint.ifBlank { null })
            .setView(et)
            .setPositiveButton("Zapisz") { _, _ -> accept() }
            .setNegativeButton("Anuluj", null)
            .create()
        // „Gotowe” na klawiaturze ekranowej zapisuje od razu
        et.setOnEditorActionListener { _, _, _ -> accept(); true }
        dlg.show()
        et.requestFocus()
    }

    private fun chooseAfr() {
        val opts = arrayOf("bez zmian", "zawsze 50 Hz", "automatycznie wg treści")
        AlertDialog.Builder(this).setTitle("Odświeżanie ekranu")
            .setSingleChoiceItems(opts, p.afrMode) { d, w -> p.afrMode = w; d.dismiss(); markChanged("odświeżanie = ${opts[w]}") }
            .show()
    }

    private fun chooseEngine() {
        val ids = arrayOf("exo", "vlc")
        val opts = arrayOf("ExoPlayer (tunelowanie, najszybszy start)", "VLC (inne usuwanie przeplotu i odmierzanie klatek)")
        AlertDialog.Builder(this).setTitle("Silnik odtwarzania na tym odbiorniku")
            .setSingleChoiceItems(opts, ids.indexOf(p.engine).coerceAtLeast(0)) { d, w ->
                p.engine = ids[w]; d.dismiss(); markChanged("silnik = ${ids[w]}")
            }.show()
    }

    private val hwIds = arrayOf("auto", "off", "dec", "full")
    private fun hwName(id: String) = when (id) {
        "off" -> "wyłączona"; "dec" -> "dekodowanie (obraz przez VLC)"; "full" -> "pełna"; else -> "automatyczna (zalecane)"
    }

    private fun chooseHw() {
        AlertDialog.Builder(this).setTitle("VLC: akceleracja sprzętowa")
            .setSingleChoiceItems(hwIds.map { hwName(it) }.toTypedArray(), hwIds.indexOf(p.vlcHwMode).coerceAtLeast(0)) { d, w ->
                p.vlcHwMode = hwIds[w]; d.dismiss(); markChanged("VLC akceleracja = ${hwIds[w]}")
            }.show()
    }

    private fun chooseChroma() {
        val ids = arrayOf("RV32", "RV16", "YV12")
        val names = arrayOf("RGB 32-bit (zalecane)", "RGB 16-bit", "YUV (YV12)")
        AlertDialog.Builder(this).setTitle("VLC: format obrazu")
            .setSingleChoiceItems(names, ids.indexOf(p.vlcChroma).coerceAtLeast(0)) { d, w ->
                p.vlcChroma = ids[w]; d.dismiss(); markChanged("VLC chroma = ${ids[w]}")
            }.show()
    }

    private val deintIds = arrayOf("default", "auto", "off", "yadif", "yadif2x", "bob", "linear", "blend", "x")
    private fun deintName(id: String) = when (id) {
        "default" -> "domyślne VLC (zalecane)"; "auto" -> "automatycznie"; "off" -> "wyłączone"; "yadif2x" -> "yadif 2x (50 kl./s)"; "bob" -> "bob (50 kl./s)"
        else -> id
    }

    private fun chooseDeint() {
        AlertDialog.Builder(this).setTitle("VLC: usuwanie przeplotu")
            .setSingleChoiceItems(deintIds.map { deintName(it) }.toTypedArray(), deintIds.indexOf(p.vlcDeint).coerceAtLeast(0)) { d, w ->
                p.vlcDeint = deintIds[w]; d.dismiss(); markChanged("VLC przeplot = ${deintIds[w]}")
            }.show()
    }

    private fun chooseStorage() {
        val opts = Storage.options(this)
        AlertDialog.Builder(this).setTitle("Miejsce zapisu logów i zrzutów TS")
            .setItems(opts.map { it.label }.toTypedArray()) { _, w -> p.storageDir = opts[w].path; markChanged("zapis = ${opts[w].path}") }
            .show()
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
                AlertDialog.Builder(this).setTitle("Znalezione dekodery")
                    .setItems(found.map { "${it.ip}   ${it.label}" }.toTypedArray()) { _, w ->
                        p.host = found[w].ip
                        markChanged("dekoder = ${found[w].ip} (${found[w].label})")
                    }
                    .show()
            }
        }.start()
    }

    private fun chooseBouquets() {
        toast("Pobieram bukiety…")
        Thread {
            val list = try { OpenWebif(p).bouquets() } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (list.isNullOrEmpty()) { toast("Nie udało się pobrać bukietów z dekodera"); return@runOnUiThread }
                val hidden = p.hiddenBouquets
                val checked = BooleanArray(list.size) { list[it].ref !in hidden }
                AlertDialog.Builder(this).setTitle("Widoczne bukiety")
                    .setMultiChoiceItems(list.map { it.name }.toTypedArray(), checked) { _, w, c -> checked[w] = c }
                    .setPositiveButton("Zapisz") { _, _ ->
                        p.hiddenBouquets = list.indices.filter { !checked[it] }.map { list[it].ref }.toSet()
                        markChanged("bukiety: ukryte ${p.hiddenBouquets.size}")
                    }
                    .setNegativeButton("Anuluj", null)
                    .show()
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
                AlertDialog.Builder(this).setTitle("Kanał startowy — bukiet")
                    .setItems(bq.map { it.name }.toTypedArray()) { _, bi ->
                        Thread {
                            val ch = try { api.channels(bq[bi]) } catch (e: Exception) { emptyList() }
                            runOnUiThread {
                                if (ch.isEmpty()) { toast("Pusty bukiet"); return@runOnUiThread }
                                val cur = ch.indexOfFirst { OpenWebif.key(it.ref) == OpenWebif.key(p.startChannel) }
                                AlertDialog.Builder(this).setTitle("Kanał startowy — ${bq[bi].name}")
                                    .setSingleChoiceItems(ch.map { "${it.num}  ${it.name}" }.toTypedArray(), cur) { d, ci ->
                                        p.startBouquet = bq[bi].ref
                                        p.startChannel = ch[ci].ref
                                        p.startChannelName = "${ch[ci].name} (${bq[bi].name})"
                                        d.dismiss()
                                        markChanged("kanał startowy = ${p.startChannelName}")
                                    }
                                    .show()
                            }
                        }.start()
                    }
                    .show()
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
                AlertDialog.Builder(this).setTitle("Przenieś ustawienia z odbiornika")
                    .setItems(others.map { "${it.name.ifBlank { it.model }}   (${it.model}, ${fmt.format(Date(it.updated))})" }.toTypedArray()) { _, w ->
                        val n = SyncManager.restore(others[w])
                        markChanged("przeniesiono $n ustawień z ${others[w].name}")
                        toast("Przeniesiono $n ustawień z „${others[w].name}”")
                        recreate()
                    }
                    .setNegativeButton("Anuluj", null)
                    .show()
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
        val entries = KeyMap.load(p).entries.toList()
        val labels = (listOf("+ Dodaj / zmień klawisz (naciśnij go na pilocie)") +
            entries.map { "${KeyMap.keyName(it.key)}   →   ${it.value.label}" } +
            listOf("Przywróć domyślne")).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Mapowanie klawiszy (podczas oglądania)")
            .setItems(labels) { _, w ->
                when (w) {
                    0 -> captureKey()
                    labels.size - 1 -> { p.keyMap = ""; toast("Przywrócono domyślne"); showKeyMap() }
                    else -> chooseAct(entries[w - 1].key)
                }
            }
            .setNegativeButton("Zamknij", null)
            .show()
    }

    private fun captureKey() {
        val dlg = AlertDialog.Builder(this)
            .setTitle("Naciśnij klawisz na pilocie…")
            .setMessage("WSTECZ — anuluj")
            .create()
        dlg.setOnKeyListener { d, code, e ->
            if (e.action != KeyEvent.ACTION_UP) return@setOnKeyListener true
            if (code == KeyEvent.KEYCODE_BACK) { d.dismiss(); return@setOnKeyListener true }
            if (code in KeyMap.fixed) { toast("Klawisza ${KeyMap.keyName(code)} nie można przypisać"); return@setOnKeyListener true }
            d.dismiss()
            chooseAct(code)
            true
        }
        dlg.show()
    }

    private fun chooseAct(code: Int) {
        val m = KeyMap.load(p)
        val acts = Act.values()
        val cur = acts.indexOf(m[code] ?: Act.NONE)
        AlertDialog.Builder(this)
            .setTitle("Klawisz ${KeyMap.keyName(code)}")
            .setSingleChoiceItems(acts.map { it.label }.toTypedArray(), cur) { d, w ->
                m[code] = acts[w]
                KeyMap.save(p, m)
                AppLog.i("mapowanie: ${KeyMap.keyName(code)} → ${acts[w].name}")
                d.dismiss()
                showKeyMap()
            }
            .setNegativeButton("Anuluj") { _, _ -> showKeyMap() }
            .show()
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
