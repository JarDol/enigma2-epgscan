package pl.jardol.e2stream

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** EPG kanału, multi-EPG bukietu, wyszukiwanie w EPG i nagrywanie na dekoderze. */
class EpgUi(
    private val a: Activity,
    private val api: OpenWebif,
    private val io: ExecutorService,
    private val store: EpgStore,
    private val host: Host,
) {
    interface Host {
        fun current(): Channel?
        fun bouquetChannels(): List<Channel>
        fun bouquetList(): List<Bouquet>
        fun currentBouquet(): Bouquet?
        fun zapTo(ref: String, onResult: ((Boolean, String) -> Unit)? = null)
        fun message(t: String)
    }

    private val pl = Locale("pl", "PL")
    private val hm = SimpleDateFormat("HH:mm", pl)
    private val day = SimpleDateFormat("EEE dd.MM", pl)
    private val eventTime = EpgTimeFormat()
    private val cache = HashMap<String, Pair<Long, List<EpgEvent>>>()

    private fun now() = System.currentTimeMillis() / 1000
    private fun t(sec: Long) = hm.format(Date(sec * 1000))
    private fun d(sec: Long) = day.format(Date(sec * 1000))

    private fun load(ref: String): List<EpgEvent> {
        val k = OpenWebif.key(ref)
        synchronized(cache) {
            cache[k]?.let { if (System.currentTimeMillis() - it.first < 10 * 60_000) return it.second }
        }
        // zapisane EPG (z harmonogramu), jeśli świeże i sięga w przyszłość
        store.read(ref)?.let { (at, ev) ->
            if (System.currentTimeMillis() - at < 26 * 3600_000L && ev.any { it.end > now() }) {
                synchronized(cache) { cache[k] = System.currentTimeMillis() to ev }
                return ev
            }
        }
        val ev = api.epgService(ref)
        synchronized(cache) { cache[k] = System.currentTimeMillis() to ev }
        store.write(ref, ev)
        return ev
    }

    /** Bieżąca audycja tylko z pamięci / zapisanego EPG (bez zapytań do dekodera) — dla listy kanałów. */
    fun localNow(ref: String): EpgEvent? {
        val n = now()
        val ev = synchronized(cache) { cache[OpenWebif.key(ref)]?.second }
            ?: try { store.read(ref)?.second } catch (_: Exception) { null }
            ?: return null
        return ev.firstOrNull { it.begin <= n && it.end > n }
    }

    private fun cached(ref: String): List<EpgEvent> =
        synchronized(cache) { cache[OpenWebif.key(ref)]?.second } ?: emptyList()

    // ------------------------------------------------------------ lista

    private class Row(val left: String, val title: String, val sub: String, val ev: EpgEvent?, val ch: Channel?)

    private inner class RowAdapter(var rows: List<Row>, private val multi: Boolean = false) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: a.layoutInflater.inflate(if (multi) R.layout.item_multi_epg else R.layout.item_epg, parent, false)
            UiScale.apply(v, UiScale.pct(Prefs(a).uiEpgFont))
            val r = rows[position]
            if (multi) v.findViewById<TextView>(R.id.channel_number).text = requireNotNull(r.ch).num.toString()
            v.findViewById<TextView>(R.id.left).apply {
                text = r.left
                maxLines = if (multi) 2 else 3
                ellipsize = if (multi) TextUtils.TruncateAt.END else null
                contentDescription = null
            }
            val title = v.findViewById<TextView>(R.id.title)
            title.text = r.title
            val running = r.ev != null && r.ev.begin <= now() && r.ev.end > now()
            title.setTextColor(if (running) 0xFF4FC3F7.toInt() else 0xFFFFFFFF.toInt())
            v.findViewById<TextView>(R.id.sub).text = r.sub
            return v
        }
    }

    private fun bigList(title: String, adapter: RowAdapter, hint: String = "OK opis audycji  •  WSTECZ zamknij", timeoutSec: Int = 0, wrap: Boolean = false, pageKeys: Boolean = false,
                        onPick: (Int) -> Unit): android.app.Dialog =
        TvDialog.panel(a, title, hint, widthFrac = 0.85f, maxH = 0.9f, timeoutSec = timeoutSec) { ctx, _ ->
            android.widget.ListView(ctx).apply {
                this.adapter = adapter
                selector = ctx.getDrawable(R.drawable.list_selector)
                divider = null
                if (wrap) TvDialog.enableCarousel(this, pageKeys = pageKeys)
                setOnItemClickListener { _, _, w, _ -> onPick(w) }
            }
        }

    private fun progress(ev: EpgEvent): String {
        val n = now()
        if (ev.begin > n || ev.end <= n || ev.duration <= 0) return ""
        val pct = ((n - ev.begin) * 100 / ev.duration).toInt()
        return "  •  trwa ($pct%, zostało ${(ev.end - n) / 60} min)"
    }

    // ------------------------------------------------------------ audycja

    fun showEvent(ev: EpgEvent, ch: Channel?, scheduledTimer: ZapTimers.Timer? = null, onReturn: () -> Unit = {}) {
        // referencja w pełnej postaci z listy kanałów (z paczki EPG przychodzi skrócona: bez końcowego „:”)
        val ref = (ch?.ref?.takeIf { it.isNotBlank() } ?: ev.sref).let { if (it.count { c -> c == ':' } == 9) "$it:" else it }
        val name = ev.sname.ifBlank { ch?.name ?: "" }
        val running = ev.begin <= now() && ev.end > now()
        val btns = mutableListOf<TvDialog.Btn>()
        if (ref.isNotBlank()) {
            btns += TvDialog.Btn(if (running) "Oglądaj" else "Przełącz na kanał") {
                if (running) host.zapTo(ref)
                else host.zapTo(ref) { ok, result ->
                    showActionResult(
                        if (ok) "Przełączono na kanał" else "Nie udało się przełączyć",
                        result,
                    )
                }
            }
            if (ev.end > now()) btns += TvDialog.Btn("Nagraj na dekoderze") { record(ref, ev) }
            if (scheduledTimer != null) {
                btns += TvDialog.Btn("Usuń przełączenie") {
                    ZapTimers.remove(a, scheduledTimer)
                    host.message("Usunięto przełączenie o ${t(scheduledTimer.begin)}")
                    onReturn()
                }
            // przełączenie o początku audycji (tylko na tym telewizorze, gdy aplikacja działa)
            } else if (!running && ev.begin > now()) {
                if (ZapTimers.has(a, ref, ev.begin)) btns += TvDialog.Btn("Usuń przełączenie") {
                    ZapTimers.remove(a, ZapTimers.Timer(ref, name, ev.title, ev.begin, ev.end))
                    host.message("Usunięto przełączenie o ${t(ev.begin)}")
                } else btns += TvDialog.Btn("Przełącz o ${t(ev.begin)}") {
                    scheduleZap(ZapTimers.Timer(ref, name.ifBlank { ref }, ev.title, ev.begin, ev.end, ev.id))
                }
            }
        }
        if (btns.isEmpty()) btns += TvDialog.Btn("Zamknij")      // WSTECZ też zamyka
        // nagłówek jak wiersz listy kanałów (ikona, kanał, tytuł, czas, postęp), niżej opis, akcje na dole
        TvDialog.panel(a, null, "OK wybierz  •  WSTECZ wróć", btns, widthFrac = 0.8f,
            onBack = if (scheduledTimer != null) onReturn else null, focusChip = true, timeoutSec = Prefs(a).epgEventSec) { ctx, _ ->
            EventCard.content(ctx, ev, ref, name, EventCard.body(ev).ifEmpty { "Brak opisu" })
        }
    }

    fun showScheduledEvent(timer: ZapTimers.Timer, onReturn: () -> Unit) {
        host.message("Pobieram EPG: ${timer.title}…")
        io.execute {
            val ev = try {
                val events = load(timer.ref)
                events.firstOrNull { timer.eventId != 0 && it.id == timer.eventId }
                    ?: events.firstOrNull { it.begin == timer.begin }
            } catch (_: Exception) { null }
            val event = ev ?: EpgEvent(timer.eventId, timer.begin, (timer.end - timer.begin).coerceAtLeast(0),
                timer.title, "", "", timer.ref, timer.channel)
            val channel = Channel(0, timer.channel, timer.ref)
            a.runOnUiThread { showEvent(event, channel, timer, onReturn) }
        }
    }

    private fun scheduleZap(timer: ZapTimers.Timer) {
        val conflicts = ZapTimers.list(a).filter { it.begin == timer.begin && it.key() != timer.key() }
        if (conflicts.isEmpty()) {
            ZapTimers.add(a, timer)
            showActionResult("Dodano do harmonogramu",
                "O ${t(timer.begin)} przełączę na ${timer.channel} — ${timer.title}")
            return
        }
        val current = conflicts.joinToString("\n") { "${it.channel} — ${it.title}" }
        TvDialog.confirm(a, "Kolizja harmonogramu",
            "O ${t(timer.begin)} zaplanowano już:\n$current\n\nZamienić na:\n${timer.channel} — ${timer.title}?",
            listOf(
                TvDialog.Btn("Zamień") {
                    ZapTimers.replaceAt(a, timer)
                    showActionResult("Zamieniono w harmonogramie",
                        "O ${t(timer.begin)} przełączę na ${timer.channel} — ${timer.title}")
                },
                TvDialog.Btn("Zostaw obecne"),
                TvDialog.Btn("Nagraj na dekoderze") {
                    recordTimer(timer) { ok -> ZapTimers.mark(a, timer, if (ok) ZapTimers.RECORDED else ZapTimers.RECORD_FAILED) }
                },
            ))
    }

    fun recordTimer(timer: ZapTimers.Timer, onResult: ((Boolean) -> Unit)? = null) {
        record(timer.ref, EpgEvent(timer.eventId, timer.begin, timer.end - timer.begin,
            timer.title, "", "", timer.ref, timer.channel), onResult)
    }

    private fun record(ref: String, ev: EpgEvent, onResult: ((Boolean) -> Unit)? = null) {
        io.execute {
            val r = try { api.timerAdd(ref, ev) } catch (e: Exception) { false to (e.message ?: e.toString()) }
            AppLog.i("nagranie: ${ev.title} ${d(ev.begin)} ${t(ev.begin)} -> ${r.first} ${r.second}")
            a.runOnUiThread {
                showActionResult(
                    if (r.first) "Nagranie ustawione" else "Nie udało się ustawić nagrania",
                    "${ev.title}\n${r.second}".trim(),
                )
                onResult?.invoke(r.first)
            }
        }
    }

    private fun showActionResult(title: String, message: String) {
        TvDialog.confirm(a, title, message, listOf(TvDialog.Btn("OK")))
    }

    fun clearMemory() = synchronized(cache) { cache.clear() }

    /** Bieżąca i następna audycja kanału (z pamięci/zapisu, w razie potrzeby z dekodera); wynik na wątku UI. */
    fun nowNext(ch: Channel, cb: (EpgEvent?, EpgEvent?) -> Unit) {
        io.execute {
            val ev = try { load(ch.ref) } catch (_: Exception) { emptyList() }
            val n = now()
            val cur = ev.firstOrNull { it.begin <= n && it.end > n }
            val next = ev.firstOrNull { it.begin >= (cur?.end ?: n) }
            a.runOnUiThread { cb(cur, next) }
        }
    }

    // ------------------------------------------------------------ EPG wydarzenia

    /** Od razu opis bieżącej audycji kanału. */
    fun showCurrentEvent(ch: Channel? = host.current()) {
        if (ch == null) return
        io.execute {
            val ev = try { load(ch.ref) } catch (e: Exception) {
                a.runOnUiThread { host.message("Błąd EPG: ${e.message}") }
                return@execute
            }
            val n = now()
            val cur = ev.firstOrNull { it.begin <= n && it.end > n } ?: ev.firstOrNull { it.begin > n }
            a.runOnUiThread {
                if (cur == null) host.message("Brak EPG dla ${ch.name}") else showEvent(cur, ch)
            }
        }
    }

    // ------------------------------------------------------------ EPG kanału

    fun showChannelEpg(ch: Channel? = host.current()) {
        if (ch == null) return
        host.message("Pobieram EPG: ${ch.name}…")
        io.execute {
            val ev = try { load(ch.ref) } catch (e: Exception) {
                a.runOnUiThread { host.message("Błąd EPG: ${e.message}") }
                return@execute
            }
            a.runOnUiThread {
                val n = now()
                val list = ev.filter { it.end > n }
                if (list.isEmpty()) { host.message("Brak EPG dla ${ch.name}"); return@runOnUiThread }
                val rows = list.map {
                    Row(eventTime.dayAndRange(it.begin, it.end), it.title, epgDescriptionPreview(it.shortDesc, it.longDesc) + progress(it), it, ch)
                }
                val adapter = RowAdapter(rows)
                bigList("EPG: ${ch.num} ${ch.name}", adapter, hint = "◀ ▶ strony  •  OK opis audycji  •  WSTECZ zamknij",
                    timeoutSec = Prefs(a).epgChannelSec, wrap = true, pageKeys = true) { w -> showEvent(rows[w].ev!!, ch) }
            }
        }
    }

    // ------------------------------------------------------------ multi-EPG

    private var multiT = 0L
    private var multiDialog: android.app.Dialog? = null

    fun showMultiEpg() {
        val bouquets = host.bouquetList()
        if (bouquets.isEmpty()) {
            host.message("Brak widocznych bukietów TV")
            return
        }
        val current = host.currentBouquet()
        val bouquet = current?.let { c -> bouquets.firstOrNull { it.ref == c.ref } } ?: bouquets.first()
        val chans = if (current?.ref == bouquet.ref) host.bouquetChannels() else emptyList()
        loadMulti(bouquet, chans, bouquets, now())
    }

    private fun loadMulti(bouquet: Bouquet, knownChannels: List<Channel>, bouquets: List<Bouquet>, at: Long) {
        host.message("Pobieram EPG bukietu: ${bouquet.name}…")
        io.execute {
            try {
                val chans = knownChannels.ifEmpty { api.channels(bouquet) }
                if (chans.isEmpty()) {
                    a.runOnUiThread { host.message("Brak kanałów w bukiecie ${bouquet.name}") }
                    return@execute
                }
                val pool = Executors.newFixedThreadPool(4)
                for (c in chans) pool.execute { try { load(c.ref) } catch (_: Exception) { } }
                pool.shutdown()
                pool.awaitTermination(60, TimeUnit.SECONDS)
                a.runOnUiThread { multiT = at; openMulti(chans, bouquet, bouquets) }
            } catch (e: Exception) {
                a.runOnUiThread { host.message("Błąd EPG bukietu ${bouquet.name}: ${e.message}") }
            }
        }
    }

    private fun multiRows(chans: List<Channel>, at: Long): List<Row> = chans.map { c ->
        val ev = cached(c.ref)
        val cur = ev.firstOrNull { it.begin <= at && it.end > at }
        val next = ev.firstOrNull { it.begin >= (cur?.end ?: at) }
        Row(
            c.name,
            cur?.let { "${eventTime.range(it.begin, it.end)}  ${it.title}" } ?: "—",
            next?.let { "następnie ${eventTime.range(it.begin, it.end)}  ${it.title}" } ?: "",
            cur ?: next,
            c,
        )
    }

    private fun openMulti(chans: List<Channel>, bouquet: Bouquet, bouquets: List<Bouquet>) {
        val adapter = RowAdapter(multiRows(chans, multiT), multi = true)
        fun title() = "Multi-EPG: ${bouquet.name} — ${d(multiT)} ${t(multiT)}"
        multiDialog?.dismiss()
        val dlg = TvDialog.panel(a, title(),
            "Bukiet: przytrzymaj OK  •  ▲ ▼ kanały  •  ◀ ▶ czas  •  OK opis  •  WSTECZ zamknij",
            widthFrac = 0.85f, maxH = 0.9f, timeoutSec = Prefs(a).epgMultiSec, hintBelowTitle = true) { ctx, _ ->
            ListView(ctx).apply {
                this.adapter = adapter
                selector = ctx.getDrawable(R.drawable.list_selector)
                divider = null
                TvDialog.enableCarousel(this)
                setOnItemClickListener { _, _, w, _ ->
                    val r = adapter.rows[w]
                    if (r.ev != null) showEvent(r.ev, r.ch) else r.ch?.let { host.zapTo(it.ref) }
                }
                isLongClickable = true
                setOnItemLongClickListener { _, _, _, _ ->
                    TvDialog.list(a, "Wybierz bukiet", bouquets.map { TvDialog.Item(it.name) },
                        selected = bouquets.indexOfFirst { it.ref == bouquet.ref },
                        hint = "OK wybierz  •  WSTECZ wróć", widthFrac = 0.55f, wrap = true) { picked ->
                        val next = bouquets[picked]
                        if (next.ref != bouquet.ref) {
                            multiDialog?.dismiss()
                            multiDialog = null
                            loadMulti(next, emptyList(), bouquets, multiT)
                        }
                    }
                    true
                }
            }
        }
        multiDialog = dlg
        dlg.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val step = 30 * 60L
            val shift = when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> step
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> -step
                else -> 0L
            }
            if (shift == 0L) return@setOnKeyListener false
            // pierwsze przesunięcie do pełnego pół-godzinnego znacznika
            val base = if (shift > 0) (multiT / step + 1) * step else ((multiT - 1) / step) * step
            multiT = if (base < now() && shift < 0) now() else base
            adapter.rows = multiRows(chans, multiT)
            adapter.notifyDataSetChanged()
            TvDialog.setTitle(dlg, title())
            true
        }
    }

    // ------------------------------------------------------------ wyszukiwanie

    fun showSearch() {
        val et = EditText(a)
        et.inputType = InputType.TYPE_CLASS_TEXT
        et.hint = "tytuł audycji"
        et.textSize = 22f
        et.setSingleLine(true)
        et.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        TvDialog.input(a, "Szukaj w EPG", "", et, "Szukaj") { search(et.text.toString().trim()) }
    }

    private fun search(q: String) {
        if (q.length < 2) { host.message("Wpisz co najmniej 2 znaki"); return }
        host.message("Szukam „$q”…")
        io.execute {
            val res = try { api.epgSearch(q) } catch (e: Exception) {
                a.runOnUiThread { host.message("Błąd wyszukiwania: ${e.message}") }
                return@execute
            }
            a.runOnUiThread {
                val n = now()
                val list = res.filter { it.end > n }
                if (list.isEmpty()) { host.message("Nic nie znaleziono: „$q”"); return@runOnUiThread }
                val rows = list.map {
                    Row("${d(it.begin)}  ${t(it.begin)}", it.title, "${it.sname}   ${it.shortDesc}${progress(it)}", it, null)
                }
                bigList("Wyniki: „$q” (${rows.size})", RowAdapter(rows)) { w -> showEvent(rows[w].ev!!, null) }
            }
        }
    }
}
