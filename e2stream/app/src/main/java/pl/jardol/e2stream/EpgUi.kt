package pl.jardol.e2stream

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
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
        fun bouquetName(): String
        fun zapTo(ref: String)
        fun message(t: String)
    }

    private val pl = Locale("pl", "PL")
    private val hm = SimpleDateFormat("HH:mm", pl)
    private val day = SimpleDateFormat("EEE dd.MM", pl)
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

    private inner class RowAdapter(var rows: List<Row>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: a.layoutInflater.inflate(R.layout.item_epg, parent, false)
            UiScale.apply(v, UiScale.pct(Prefs(a).uiEpgFont))
            val r = rows[position]
            v.findViewById<TextView>(R.id.left).text = r.left
            val title = v.findViewById<TextView>(R.id.title)
            title.text = r.title
            val running = r.ev != null && r.ev.begin <= now() && r.ev.end > now()
            title.setTextColor(if (running) 0xFF4FC3F7.toInt() else 0xFFFFFFFF.toInt())
            v.findViewById<TextView>(R.id.sub).text = r.sub
            return v
        }
    }

    private fun bigList(title: String, adapter: RowAdapter, hint: String = "OK opis audycji  •  WSTECZ zamknij",
                        onPick: (Int) -> Unit): android.app.Dialog =
        TvDialog.panel(a, title, hint, widthFrac = 0.85f, maxH = 0.9f) { ctx, _ ->
            android.widget.ListView(ctx).apply {
                this.adapter = adapter
                selector = ctx.getDrawable(R.drawable.list_selector)
                divider = null
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

    fun showEvent(ev: EpgEvent, ch: Channel?) {
        // referencja w pełnej postaci z listy kanałów (z paczki EPG przychodzi skrócona: bez końcowego „:”)
        val ref = (ch?.ref?.takeIf { it.isNotBlank() } ?: ev.sref).let { if (it.count { c -> c == ':' } == 9) "$it:" else it }
        val name = ev.sname.ifBlank { ch?.name ?: "" }
        val running = ev.begin <= now() && ev.end > now()
        val head = buildString {
            append("${d(ev.begin)}  ${t(ev.begin)}–${t(ev.end)}  (${ev.duration / 60} min)")
            append(progress(ev))
            if (name.isNotBlank()) append("\n$name")
        }
        val body = buildString {
            if (ev.shortDesc.isNotBlank()) append(ev.shortDesc)
            if (ev.longDesc.isNotBlank() && ev.longDesc != ev.shortDesc) {
                if (isNotEmpty()) append("\n\n")
                append(ev.longDesc)
            }
            if (isEmpty()) append("Brak opisu")
        }
        val btns = mutableListOf<TvDialog.Btn>()
        if (ref.isNotBlank()) {
            btns += TvDialog.Btn(if (running) "Oglądaj" else "Przełącz na kanał") { host.zapTo(ref) }
            if (ev.end > now()) btns += TvDialog.Btn("Nagraj na dekoderze") { record(ref, ev) }
        }
        btns += TvDialog.Btn("Zamknij")
        val scale = UiScale.pct(Prefs(a).uiEpgFont)
        val n = now()
        val pct = if (running && ev.duration > 0) ((n - ev.begin) * 1000 / ev.duration).toInt() else -1
        val when_ = "${d(ev.begin)}  ${t(ev.begin)} – ${t(ev.end)}  (${ev.duration / 60} min)" +
            if (running) "  •  zostało ${(ev.end - n) / 60} min" else ""
        // nagłówek jak wiersz listy kanałów (ikona, kanał, tytuł, czas, postęp), niżej opis, akcje na dole
        TvDialog.panel(a, null, null, btns, widthFrac = 0.8f, focusChip = true) { ctx, _ ->
            val dp = ctx.resources.displayMetrics.density
            fun px(v: Int) = (v * dp).toInt()
            val col = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
            val head = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val bmp = if (ref.isNotBlank()) try { Picons.bitmap(ctx, ref) } catch (_: Exception) { null } else null
            if (bmp != null) head.addView(android.widget.ImageView(ctx).apply {
                setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }, android.widget.LinearLayout.LayoutParams(px(96), px(56)).apply { marginEnd = px(16) })
            val info = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
            if (name.isNotBlank()) info.addView(TextView(ctx).apply {
                text = name; textSize = 15f * scale; setTextColor(TvDialog.GREY)
            })
            info.addView(TextView(ctx).apply {
                text = ev.title; textSize = 23f * scale; setTextColor(TvDialog.WHITE)
            })
            val tl = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            tl.addView(TextView(ctx).apply { text = when_; textSize = 15f * scale; setTextColor(TvDialog.BLUE) })
            if (pct >= 0) tl.addView(android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progress = pct.coerceIn(0, 1000); progressDrawable = ctx.getDrawable(R.drawable.progress_epg)
            }, android.widget.LinearLayout.LayoutParams(0, px(5), 1f).apply { marginStart = px(16) })
            info.addView(tl)
            head.addView(info, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(head)
            col.addView(android.view.View(ctx).apply { setBackgroundColor(0xFF37474F.toInt()) },
                android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply {
                    topMargin = px(12); bottomMargin = px(12) })
            col.addView(TvDialog.scrollText(ctx, body, 19f * scale),
                android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            col
        }
    }

    private fun record(ref: String, ev: EpgEvent) {
        io.execute {
            val r = try { api.timerAdd(ref, ev) } catch (e: Exception) { false to (e.message ?: e.toString()) }
            AppLog.i("nagranie: ${ev.title} ${d(ev.begin)} ${t(ev.begin)} -> ${r.first} ${r.second}")
            a.runOnUiThread {
                host.message(if (r.first) "Nagranie ustawione: ${ev.title}" else "Nie udało się ustawić nagrania: ${r.second}")
            }
        }
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
                var lastDay = ""
                val rows = list.map {
                    val dd = d(it.begin)
                    val left = if (dd != lastDay) "$dd  ${t(it.begin)}" else t(it.begin)
                    lastDay = dd
                    Row(left, it.title, it.shortDesc + progress(it), it, ch)
                }
                val adapter = RowAdapter(rows)
                bigList("EPG: ${ch.num} ${ch.name}", adapter) { w -> showEvent(rows[w].ev!!, ch) }
            }
        }
    }

    // ------------------------------------------------------------ multi-EPG

    private var multiT = 0L

    fun showMultiEpg() {
        val chans = host.bouquetChannels()
        if (chans.isEmpty()) return
        host.message("Pobieram EPG bukietu (${chans.size} kanałów)…")
        io.execute {
            val pool = Executors.newFixedThreadPool(4)
            for (c in chans) pool.execute { try { load(c.ref) } catch (_: Exception) { } }
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)
            a.runOnUiThread { multiT = now(); openMulti(chans) }
        }
    }

    private fun multiRows(chans: List<Channel>, at: Long): List<Row> = chans.map { c ->
        val ev = cached(c.ref)
        val cur = ev.firstOrNull { it.begin <= at && it.end > at }
        val next = ev.firstOrNull { it.begin >= (cur?.end ?: at) }
        Row(
            "${c.num}  ${c.name}".take(18),
            cur?.let { "${t(it.begin)}  ${it.title}" } ?: "—",
            next?.let { "następnie ${t(it.begin)}  ${it.title}" } ?: "",
            cur ?: next,
            c,
        )
    }

    private fun openMulti(chans: List<Channel>) {
        val adapter = RowAdapter(multiRows(chans, multiT))
        fun title() = "Multi-EPG: ${host.bouquetName()} — ${d(multiT)} ${t(multiT)}"
        val dlg = bigList(title(), adapter, "◀ ▶ zmiana czasu  •  OK opis  •  WSTECZ zamknij") { w ->
            val r = adapter.rows[w]
            if (r.ev != null) showEvent(r.ev, r.ch) else r.ch?.let { host.zapTo(it.ref) }
        }
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
