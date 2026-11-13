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

    private val locale = Locale.forLanguageTag(AppLanguage.selected(a))
    private val hm = SimpleDateFormat("HH:mm", locale)
    private val day = SimpleDateFormat("EEE dd.MM", locale)
    private val eventTime = EpgTimeFormat(locale = locale, until = a.getString(R.string.epg_until))
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

    private fun bigList(title: String, adapter: RowAdapter, hint: String = a.getString(R.string.epg_hint_list), timeoutSec: Int = 0, wrap: Boolean = false, pageKeys: Boolean = false,
                        onLongPick: ((Int) -> Boolean)? = null,
                        onPick: (Int) -> Unit): android.app.Dialog =
        TvDialog.panel(a, title, hint, widthFrac = 0.85f, maxH = 0.9f, timeoutSec = timeoutSec) { ctx, _ ->
            android.widget.ListView(ctx).apply {
                this.adapter = adapter
                selector = ctx.getDrawable(R.drawable.list_selector)
                divider = null
                if (wrap) TvDialog.enableCarousel(this, pageKeys = pageKeys)
                setOnItemClickListener { _, _, w, _ -> onPick(w) }
                if (onLongPick != null) {
                    isLongClickable = true
                    setOnItemLongClickListener { _, _, position, _ -> onLongPick(position) }
                }
            }
        }

    private fun progress(ev: EpgEvent): String {
        val n = now()
        if (ev.begin > n || ev.end <= n || ev.duration <= 0) return ""
        val pct = ((n - ev.begin) * 100 / ev.duration).toInt()
        return a.getString(R.string.epg_progress, pct, (ev.end - n) / 60)
    }

    // ------------------------------------------------------------ audycja

    fun showEvent(ev: EpgEvent, ch: Channel?, scheduledTimer: ZapTimers.Timer? = null, onReturn: () -> Unit = {}) {
        // referencja w pełnej postaci z listy kanałów (z paczki EPG przychodzi skrócona: bez końcowego „:”)
        val ref = (ch?.ref?.takeIf { it.isNotBlank() } ?: ev.sref).let { if (it.count { c -> c == ':' } == 9) "$it:" else it }
        val name = ev.sname.ifBlank { ch?.name ?: "" }
        val running = ev.begin <= now() && ev.end > now()
        val isCurrentChannel = host.current()?.ref?.let { OpenWebif.key(it) == OpenWebif.key(ref) } == true
        val returnToSchedule = if (scheduledTimer != null) onReturn else null
        val btns = mutableListOf<TvDialog.Btn>()
        if (ref.isNotBlank()) {
            if (isCurrentChannel) {
                btns += TvDialog.Btn(a.getString(R.string.epg_watch))
            } else {
                btns += TvDialog.Btn(a.getString(R.string.epg_switch_channel)) {
                    confirmAction(
                        a.getString(R.string.epg_confirm_switch),
                        a.getString(R.string.epg_switch_question, name.ifBlank { ref }),
                        a.getString(R.string.epg_switch),
                        onBack = returnToSchedule,
                    ) {
                        host.zapTo(ref) { ok, result ->
                            showActionResult(
                                a.getString(if (ok) R.string.epg_switched else R.string.epg_switch_failed),
                                result,
                                returnToSchedule ?: {},
                            )
                        }
                    }
                }
            }
            if (ev.end > now()) btns += TvDialog.Btn(a.getString(R.string.epg_record_receiver)) {
                confirmAction(
                    a.getString(R.string.epg_confirm_record),
                    a.getString(R.string.epg_record_question, name, ev.title, t(ev.begin), t(ev.end)),
                    a.getString(R.string.epg_record),
                    onBack = returnToSchedule,
                ) { record(ref, ev, onInfoClose = returnToSchedule ?: {}) }
            }
            if (scheduledTimer != null) {
                btns += TvDialog.Btn(a.getString(R.string.epg_remove_switch)) {
                    confirmAction(
                        a.getString(R.string.epg_confirm_remove),
                        a.getString(R.string.epg_remove_question, scheduledTimer.channel, scheduledTimer.title, t(scheduledTimer.begin)),
                        a.getString(R.string.epg_remove),
                        onBack = onReturn,
                    ) {
                        ZapTimers.remove(a, scheduledTimer)
                        showActionResult(a.getString(R.string.epg_switch_removed),
                            "${scheduledTimer.channel}\n${scheduledTimer.title}", onReturn)
                    }
                }
            // przełączenie o początku audycji (tylko na tym telewizorze, gdy aplikacja działa)
            } else if (!running && ev.begin > now()) {
                if (ZapTimers.has(a, ref, ev.begin)) btns += TvDialog.Btn(a.getString(R.string.epg_remove_switch)) {
                    confirmAction(
                        a.getString(R.string.epg_confirm_remove),
                        a.getString(R.string.epg_remove_question, name, ev.title, t(ev.begin)),
                        a.getString(R.string.epg_remove),
                    ) {
                        ZapTimers.remove(a, ZapTimers.Timer(ref, name, ev.title, ev.begin, ev.end, ev.id))
                        showActionResult(a.getString(R.string.epg_switch_removed),
                            "${name.ifBlank { ref }}\n${ev.title}")
                    }
                } else btns += TvDialog.Btn(a.getString(R.string.epg_switch_at, t(ev.begin))) {
                    confirmAction(
                        a.getString(R.string.epg_confirm_switch),
                        a.getString(R.string.epg_schedule_question, name, ev.title, t(ev.begin)),
                        a.getString(R.string.epg_add),
                    ) {
                        scheduleZap(ZapTimers.Timer(ref, name.ifBlank { ref }, ev.title, ev.begin, ev.end, ev.id))
                    }
                }
            }
        }
        if (btns.isEmpty()) btns += TvDialog.Btn(a.getString(R.string.epg_close))      // WSTECZ też zamyka
        // nagłówek jak wiersz listy kanałów (ikona, kanał, tytuł, czas, postęp), niżej opis, akcje na dole
        TvDialog.panel(a, null, a.getString(R.string.epg_hint_actions), btns, widthFrac = 0.8f,
            onBack = returnToSchedule, focusChip = true, timeoutSec = Prefs(a).actionDialogSec,
            onTimeout = returnToSchedule) { ctx, _ ->
            EventCard.content(ctx, ev, ref, name, EventCard.body(ev).ifEmpty { a.getString(R.string.epg_no_description) })
        }
    }

    fun showScheduledEvent(timer: ZapTimers.Timer, onReturn: () -> Unit) {
        host.message(a.getString(R.string.epg_loading, timer.title))
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
            showActionResult(a.getString(R.string.epg_added_schedule),
                a.getString(R.string.epg_schedule_message, t(timer.begin), timer.channel, timer.title))
            return
        }
        val current = conflicts.joinToString("\n") { "${it.channel} — ${it.title}" }
        TvDialog.confirm(a, a.getString(R.string.epg_schedule_conflict),
            a.getString(R.string.epg_conflict_question, t(timer.begin), current, timer.channel, timer.title),
            listOf(
                TvDialog.Btn(a.getString(R.string.epg_replace)) {
                    ZapTimers.replaceAt(a, timer)
                    showActionResult(a.getString(R.string.epg_replaced),
                        a.getString(R.string.epg_schedule_message, t(timer.begin), timer.channel, timer.title))
                },
                TvDialog.Btn(a.getString(R.string.epg_keep)),
                TvDialog.Btn(a.getString(R.string.epg_record_receiver)) {
                    recordTimer(timer) { ok -> ZapTimers.mark(a, timer, if (ok) ZapTimers.RECORDED else ZapTimers.RECORD_FAILED) }
                },
            ))
    }

    fun recordTimer(
        timer: ZapTimers.Timer,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        record(timer.ref, EpgEvent(timer.eventId, timer.begin, timer.end - timer.begin,
            timer.title, "", "", timer.ref, timer.channel), onResult)
    }

    private fun record(
        ref: String,
        ev: EpgEvent,
        onResult: ((Boolean) -> Unit)? = null,
        onInfoClose: () -> Unit = {},
    ) {
        io.execute {
            val r = try { api.timerAdd(ref, ev) } catch (e: Exception) { false to (e.message ?: e.toString()) }
            AppLog.i("nagranie: ${ev.title} ${d(ev.begin)} ${t(ev.begin)} -> ${r.first} ${r.second}")
            a.runOnUiThread {
                showActionResult(
                    a.getString(if (r.first) R.string.epg_recording_set else R.string.epg_recording_failed),
                    "${ev.title}\n${r.second}".trim(),
                    onInfoClose,
                )
                onResult?.invoke(r.first)
            }
        }
    }

    private fun showActionResult(title: String, message: String, onClose: () -> Unit = {}) {
        TvDialog.info(a, title, message, onClose)
    }

    private fun confirmAction(
        title: String, message: String, confirmLabel: String,
        onBack: (() -> Unit)? = null, action: () -> Unit,
    ) {
        TvDialog.confirm(a, title, message, listOf(
            TvDialog.Btn(confirmLabel, onClick = action),
        ), onBack = onBack, onTimeout = onBack)
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
                a.runOnUiThread { host.message(a.getString(R.string.epg_error, e.message)) }
                return@execute
            }
            val n = now()
            val cur = ev.firstOrNull { it.begin <= n && it.end > n } ?: ev.firstOrNull { it.begin > n }
            a.runOnUiThread {
                if (cur == null) host.message(a.getString(R.string.epg_no_epg, ch.name)) else showEvent(cur, ch)
            }
        }
    }

    // ------------------------------------------------------------ EPG kanału

    fun showChannelEpg(ch: Channel? = host.current()) {
        if (ch == null) return
        host.message(a.getString(R.string.epg_loading, ch.name))
        io.execute {
            val ev = try { load(ch.ref) } catch (e: Exception) {
                a.runOnUiThread { host.message(a.getString(R.string.epg_error, e.message)) }
                return@execute
            }
            a.runOnUiThread {
                val n = now()
                val list = ev.filter { it.end > n }
                if (list.isEmpty()) { host.message(a.getString(R.string.epg_no_epg, ch.name)); return@runOnUiThread }
                val rows = list.map {
                    Row(eventTime.dayAndRange(it.begin, it.end), it.title, epgDescriptionPreview(it.shortDesc, it.longDesc) + progress(it), it, ch)
                }
                val adapter = RowAdapter(rows)
                bigList("EPG: ${ch.num} ${ch.name}",
                    adapter,
                    hint = a.getString(R.string.epg_hint_pages),
                    timeoutSec = Prefs(a).epgChannelSec,
                    wrap = true,
                    pageKeys = true,
                    onLongPick = { showChannelEpgActions(ch); true },
                ) { w -> showEvent(rows[w].ev!!, ch) }
            }
        }
    }

    private fun showChannelEpgActions(current: Channel) {
        TvDialog.list(a, a.getString(R.string.menu_channel_epg), listOf(
            TvDialog.Item(a.getString(R.string.epg_other_channel)),
            TvDialog.Item(a.getString(R.string.epg_search)),
        ), hint = a.getString(R.string.epg_hint_actions), widthFrac = 0.55f) { selected ->
            if (selected == 1) {
                TvDialog.dismissAll()
                showSearch()
            } else {
                showEpgBouquetPicker(current)
            }
        }
    }

    private fun showEpgBouquetPicker(current: Channel) {
        val bouquets = host.bouquetList()
        if (bouquets.isEmpty()) {
            host.message(a.getString(R.string.epg_no_bouquets))
            return
        }
        val currentBouquet = host.currentBouquet()
        TvDialog.list(a, a.getString(R.string.epg_choose_bouquet), bouquets.map { TvDialog.Item(it.name) },
            selected = bouquets.indexOfFirst { it.ref == currentBouquet?.ref }.coerceAtLeast(0),
            hint = a.getString(R.string.epg_hint_actions), widthFrac = 0.6f, wrap = true) { selected ->
            val bouquet = bouquets[selected]
            host.message(a.getString(R.string.epg_loading_channels, bouquet.name))
            io.execute {
                val channels = try {
                    api.channels(bouquet)
                } catch (e: Exception) {
                    a.runOnUiThread { host.message(a.getString(R.string.epg_channels_error, bouquet.name, e.message)) }
                    return@execute
                }
                a.runOnUiThread {
                    if (channels.isEmpty()) {
                        host.message(a.getString(R.string.epg_no_channels, bouquet.name))
                    } else {
                        TvDialog.list(a, a.getString(R.string.epg_choose_channel, bouquet.name),
                            channels.map { TvDialog.Item("${it.num}  ${it.name}") },
                            selected = channels.indexOfFirst { OpenWebif.key(it.ref) == OpenWebif.key(current.ref) }.coerceAtLeast(0),
                            hint = a.getString(R.string.epg_hint_show), widthFrac = 0.7f, wrap = true) { channelIndex ->
                            TvDialog.dismissAll()
                            showChannelEpg(channels[channelIndex])
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ multi-EPG

    private var multiT = 0L
    private var multiDialog: android.app.Dialog? = null

    fun showMultiEpg() {
        val bouquets = host.bouquetList()
        if (bouquets.isEmpty()) {
            host.message(a.getString(R.string.epg_no_bouquets))
            return
        }
        val current = host.currentBouquet()
        val bouquet = current?.let { c -> bouquets.firstOrNull { it.ref == c.ref } } ?: bouquets.first()
        val chans = if (current?.ref == bouquet.ref) host.bouquetChannels() else emptyList()
        loadMulti(bouquet, chans, bouquets, now())
    }

    private fun loadMulti(bouquet: Bouquet, knownChannels: List<Channel>, bouquets: List<Bouquet>, at: Long) {
        host.message(a.getString(R.string.epg_loading_bouquet, bouquet.name))
        io.execute {
            try {
                val chans = knownChannels.ifEmpty { api.channels(bouquet) }
                if (chans.isEmpty()) {
                    a.runOnUiThread { host.message(a.getString(R.string.epg_no_channels, bouquet.name)) }
                    return@execute
                }
                val pool = Executors.newFixedThreadPool(4)
                for (c in chans) pool.execute { try { load(c.ref) } catch (_: Exception) { } }
                pool.shutdown()
                pool.awaitTermination(60, TimeUnit.SECONDS)
                a.runOnUiThread { multiT = at; openMulti(chans, bouquet, bouquets) }
            } catch (e: Exception) {
                a.runOnUiThread { host.message(a.getString(R.string.epg_bouquet_error, bouquet.name, e.message)) }
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
            next?.let { a.getString(R.string.epg_next, eventTime.range(it.begin, it.end), it.title) } ?: "",
            cur ?: next,
            c,
        )
    }

    private fun openMulti(chans: List<Channel>, bouquet: Bouquet, bouquets: List<Bouquet>) {
        val adapter = RowAdapter(multiRows(chans, multiT), multi = true)
        fun title() = "Multi-EPG: ${bouquet.name} — ${d(multiT)} ${t(multiT)}"
        multiDialog?.dismiss()
        val dlg = TvDialog.panel(a, title(),
            a.getString(R.string.epg_hint_multi),
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
                    TvDialog.list(a, "Multi-EPG", listOf(
                        TvDialog.Item(a.getString(R.string.epg_choose_bouquet)),
                        TvDialog.Item(a.getString(R.string.epg_search)),
                    ), hint = a.getString(R.string.epg_hint_actions), widthFrac = 0.55f) { action ->
                        if (action == 1) {
                            multiDialog?.dismiss()
                            multiDialog = null
                            showSearch()
                        } else {
                            TvDialog.list(a, a.getString(R.string.epg_choose_bouquet), bouquets.map { TvDialog.Item(it.name) },
                                selected = bouquets.indexOfFirst { it.ref == bouquet.ref },
                                hint = a.getString(R.string.epg_hint_actions), widthFrac = 0.55f, wrap = true) { picked ->
                                val next = bouquets[picked]
                                if (next.ref != bouquet.ref) {
                                    multiDialog?.dismiss()
                                    multiDialog = null
                                    loadMulti(next, emptyList(), bouquets, multiT)
                                }
                            }
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
        et.hint = a.getString(R.string.epg_search_hint)
        et.textSize = 22f
        et.setSingleLine(true)
        et.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        TvDialog.input(a, a.getString(R.string.epg_search), "", et, a.getString(R.string.epg_search_button)) { search(et.text.toString().trim()) }
    }

    private fun search(q: String) {
        if (q.length < 2) { host.message(a.getString(R.string.epg_search_short)); return }
        host.message(a.getString(R.string.epg_searching, q))
        io.execute {
            val res = try { api.epgSearch(q) } catch (e: Exception) {
                a.runOnUiThread { host.message(a.getString(R.string.epg_search_error, e.message)) }
                return@execute
            }
            a.runOnUiThread {
                val n = now()
                val list = res.filter { it.end > n }
                if (list.isEmpty()) { host.message(a.getString(R.string.epg_no_results, q)); return@runOnUiThread }
                val rows = list.map {
                    Row("${d(it.begin)}  ${t(it.begin)}", it.title, "${it.sname}   ${it.shortDesc}${progress(it)}", it, null)
                }
                bigList(a.getString(R.string.epg_results, q, rows.size), RowAdapter(rows)) { w -> showEvent(rows[w].ev!!, null) }
            }
        }
    }
}
