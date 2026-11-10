package pl.jardol.e2stream

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bufor timeshiftu: strumień TS bieżącego kanału zapisywany w kawałkach po 1 minucie
 * (ts-N.ts w katalogu bufora). Kawałki to po prostu kolejne fragmenty jednego ciągłego strumienia —
 * odczyt może zacząć się w dowolnym bajcie. Najstarsza minuta wypada, gdy okno przekroczy [maxMin]
 * albo zabraknie miejsca. Czas to zegar monotoniczny (SystemClock.elapsedRealtime) chwili odbioru.
 */
class TimeshiftBuffer(val dir: File, @Volatile var maxMin: Int) {

    private class Seg(val file: File, val start: Long, val wall: Long) {
        @Volatile var len = 0L
    }

    private val segs = ArrayList<Seg>()
    private val lock = Object()
    private var out: FileOutputStream? = null
    private var nextIdx = 0
    @Volatile var total = 0L
        private set
    @Volatile var failed: String? = null
        private set
    @Volatile var trimmed = false       // najstarsza minuta już wypadła z okna
        private set
    @Volatile var shrunk = false        // okno skrócone z braku miejsca
        private set

    /** Prefiks plików bieżącej sesji — sprzątanie w tle nie dotyka plików, które właśnie powstają. */
    @Volatile private var session = newSession()

    init { dir.mkdirs(); cleanupAsync(dir, session) }

    private fun newSession() = "ts-" + java.lang.Long.toString(System.currentTimeMillis(), 36) + "-"

    /** Nowy kanał / koniec: wszystko od zera; stare pliki kasowane w tle (bez czekania). */
    fun reset() = synchronized(lock) {
        try { out?.close() } catch (_: Exception) {}
        out = null
        segs.clear()
        total = 0
        failed = null
        shrunk = false
        trimmed = false
        pendLen = 0
        session = newSession()
        cleanupAsync(dir, session)
        lock.notifyAll()
    }

    private val pend = ByteArray(188)
    private var pendLen = 0

    /** Nowe połączenie ze strumieniem: niepełny pakiet z poprzedniego jest porzucany, żeby bufor został zgodny z siatką 188 B. */
    fun resync() = synchronized(lock) { pendLen = 0 }

    /** Do bufora trafiają tylko całe pakiety TS — odczyt od dowolnego początku minuty zawsze trafia w granicę pakietu. */
    fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            if (failed != null) return
            try {
                var o = off
                val end = off + len
                if (pendLen > 0) {
                    val n = minOf(188 - pendLen, end - o)
                    System.arraycopy(b, o, pend, pendLen, n)
                    pendLen += n; o += n
                    if (pendLen < 188) return
                    pendLen = 0
                    if (pend[0] == SYNC) writeRaw(pend, 0, 188)
                }
                // pierwszy zapis (np. po opóźnieniu): początek porcji bywa w środku pakietu — szukamy pewnej granicy
                if (total == 0L && pendLen == 0) {
                    while (o < end && !aligned(b, o, end)) o++
                }
                var run = o
                while (end - o >= 188) {
                    if (b[o] != SYNC) {
                        if (o > run) writeRaw(b, run, o - run)
                        var j = o + 1
                        while (j < end && b[j] != SYNC) j++
                        o = j; run = j
                        continue
                    }
                    o += 188
                }
                if (o > run) writeRaw(b, run, o - run)
                if (o < end && b[o] == SYNC) {
                    pendLen = end - o
                    System.arraycopy(b, o, pend, 0, pendLen)
                }
            } catch (e: IOException) {
                failed = e.message ?: e.toString()
                AppLog.i("timeshift: błąd zapisu — bufor wyłączony ($failed)")
            }
            lock.notifyAll()
        }
    }

    private fun aligned(b: ByteArray, o: Int, end: Int): Boolean =
        b[o] == SYNC && (o + 188 >= end || b[o + 188] == SYNC) && (o + 376 >= end || b[o + 376] == SYNC)

    private fun writeRaw(b: ByteArray, off: Int, len: Int) {
        val now = SystemClock.elapsedRealtime()
        val cur = segs.lastOrNull()
        if (cur == null || out == null || now - cur.wall >= 60_000) rotate(now)
        out!!.write(b, off, len)
        segs.last().len += len
        total += len
    }

    private fun rotate(now: Long) {
        try { out?.close() } catch (_: Exception) {}
        val f = File(dir, "$session${nextIdx++}.ts")
        out = FileOutputStream(f)
        segs += Seg(f, total, now)
        // okno: najwyżej maxMin pełnych minut (+ bieżąca); mało miejsca — wypadają najstarsze
        while (segs.size > maxOf(2, maxMin + 1)) dropOldest()
        while (segs.size > 2 && dir.usableSpace < RESERVE) { dropOldest(); shrunk = true }
    }

    private fun dropOldest() {
        val s = segs.removeAt(0)
        trimmed = true
        val f = s.file
        Thread({ f.delete() }, "timeshift-usun").start()      // usuwanie dużego pliku nie wstrzymuje zapisu
    }

    val oldestOffset: Long get() = synchronized(lock) { segs.firstOrNull()?.start ?: 0 }
    val oldestWall: Long get() = synchronized(lock) { segs.firstOrNull()?.wall ?: SystemClock.elapsedRealtime() }
    /** Długość zapisanego okna (ms). */
    fun spanMs(): Long = SystemClock.elapsedRealtime() - oldestWall

    /** Bajt odpowiadający chwili [wall] (interpolacja w obrębie minuty). */
    fun offsetAt(wall: Long): Long = synchronized(lock) {
        if (segs.isEmpty()) return 0
        val now = SystemClock.elapsedRealtime()
        for (i in segs.indices) {
            val s = segs[i]
            val endWall = segs.getOrNull(i + 1)?.wall ?: now
            if (wall < endWall || i == segs.size - 1) {
                if (wall <= s.wall) return s.start
                val dur = (endWall - s.wall).coerceAtLeast(1)
                val frac = ((wall - s.wall).toDouble() / dur).coerceIn(0.0, 1.0)
                // początek pakietu TS (188 B) — mniej śmieci na starcie odczytu
                val raw = s.start + (s.len * frac).toLong()
                return (raw - (raw - s.start) % 188).coerceAtMost(maxOf(s.start, total - 188 * 1000))
            }
        }
        segs.last().start
    }

    /** Chwila odpowiadająca bajtowi [offset]. */
    fun wallAt(offset: Long): Long = synchronized(lock) {
        if (segs.isEmpty()) return SystemClock.elapsedRealtime()
        val now = SystemClock.elapsedRealtime()
        for (i in segs.indices.reversed()) {
            val s = segs[i]
            if (offset >= s.start) {
                val endWall = segs.getOrNull(i + 1)?.wall ?: now
                val frac = if (s.len > 0) ((offset - s.start).toDouble() / s.len).coerceIn(0.0, 1.0) else 0.0
                return s.wall + ((endWall - s.wall) * frac).toLong()
            }
        }
        segs.first().wall
    }

    /** Plik i pozycja w nim dla bajtu [pos] (null — dane już usunięte). */
    private fun locate(pos: Long): Pair<File, Long>? = synchronized(lock) {
        for (i in segs.indices.reversed()) {
            val s = segs[i]
            if (pos >= s.start) return if (pos - s.start < s.len || i == segs.size - 1) s.file to (pos - s.start) else null
        }
        null
    }

    /** Czeka na dane za [pos] (najwyżej [timeoutMs]); false — brak danych. */
    fun await(pos: Long, timeoutMs: Long, closed: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        synchronized(lock) {
            while (total <= pos && !closed()) {
                val left = until - SystemClock.elapsedRealtime()
                if (left <= 0) return false
                lock.wait(minOf(left, 500))
            }
        }
        return total > pos
    }

    /** Odczyt od bajtu [pos]; -1 — dane usunięte (okno przesunęło się dalej). */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        val (f, inSeg) = locate(pos) ?: return -1
        val avail = synchronized(lock) { total - pos }
        if (avail <= 0) return 0
        return try {
            RandomAccessFile(f, "r").use { r ->
                r.seek(inSeg)
                val n = r.read(buf, off, minOf(len.toLong(), avail).toInt())
                if (n < 0) 0 else n
            }
        } catch (_: IOException) { -1 }
    }

    companion object {
        /**
         * Kasuje w tle pliki bufora z poprzednich sesji (zmiana kanału, wyjście, przerwanie aplikacji).
         * [keep] — prefiks bieżącej sesji (zostaje).
         */
        fun cleanupAsync(dir: File?, keep: String? = null) {
            if (dir == null) return
            Thread({
                try {
                    var n = 0
                    dir.listFiles { f -> f.name.startsWith("ts-") && f.name.endsWith(".ts") && (keep == null || !f.name.startsWith(keep)) }
                        ?.forEach { if (it.delete()) n++ }
                    if (n > 0) AppLog.i("timeshift: usunięto $n starych plików bufora")
                } catch (_: Exception) {}
            }, "timeshift-sprzatanie").start()
        }

        private const val SYNC = 0x47.toByte()

        /** Zapas wolnego miejsca, którego bufor nie rusza. */
        const val RESERVE = 400L * 1_000_000
        /** Ostrożne oszacowanie: kanał HD do ~8 Mb/s = 60 MB na minutę. */
        const val MB_PER_MIN = 60L

        /** Ile minut timeshiftu zmieści się w katalogu [dir] (z zapasem). */
        fun fitMinutes(dir: File): Int {
            val free = try { dir.mkdirs(); dir.usableSpace } catch (_: Exception) { 0L }
            val usable = free - maxOf(RESERVE, free / 10)
            return if (usable <= 0) 0 else (usable / (MB_PER_MIN * 1_000_000)).toInt()
        }

        /** Pamięć urządzenia: do 2 h; dysk USB / SD: do 6 h (od 2 h co godzinę). */
        val CHOICES_INTERNAL = intArrayOf(0, 15, 30, 60, 90, 120)
        val CHOICES_USB = intArrayOf(0, 15, 30, 60, 90, 120, 180, 240, 300, 360)

        fun choices(usb: Boolean) = if (usb) CHOICES_USB else CHOICES_INTERNAL

        /** Zalecany czas: największy z wyboru, który się mieści. */
        fun recommended(fit: Int, usb: Boolean): Int = choices(usb).filter { it in 1..fit }.maxOrNull() ?: 0

        fun internalBase(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

        /** Czy nośnik [path] jest teraz dostępny do zapisu (dysk USB może zniknąć po restarcie odbiornika). */
        fun available(path: String): Boolean = try {
            val f = File(path)
            val state = android.os.Environment.getExternalStorageState(f)
            (state == android.os.Environment.MEDIA_MOUNTED) && (f.isDirectory || f.mkdirs()) && f.canWrite()
        } catch (_: Exception) { false }

        /**
         * Katalog bufora: wybrany nośnik, jeśli jest dostępny; pamięć urządzenia tylko za zgodą
         * (zapis ciągły zużywa pamięć flash). Null — timeshift niedostępny (np. odłączony dysk USB).
         */
        fun dir(ctx: Context, prefs: Prefs): File? {
            val p = prefs.tsDir
            if (p.isNotBlank() && available(p)) return File(p, "timeshift")
            return if (prefs.tsInternalOk) File(internalBase(ctx), "timeshift") else null
        }

        fun gb(bytes: Long) = String.format(java.util.Locale("pl"), "%.1f GB", bytes / 1e9)
    }
}

/** ExoPlayer czyta strumień z bufora timeshiftu od bajtu [start], doganiając zapis na bieżąco. */
class TimeshiftDataSource(
    private val buf: TimeshiftBuffer, private val start: Long, private val header: ByteArray?, private val onGone: () -> Unit,
) : DataSource {
    @Volatile private var closed = false
    private var pos = 0L
    private var uri: Uri? = null
    private var hdrPos = 0

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        uri = dataSpec.uri
        pos = start + dataSpec.position
        hdrPos = if (dataSpec.position == 0L) 0 else Int.MAX_VALUE
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        // najpierw PAT + PMT — odtwarzacz od razu zna ścieżki, czeka tylko na klatkę kluczową
        val h = header
        if (h != null && hdrPos < h.size) {
            val n = minOf(length, h.size - hdrPos)
            System.arraycopy(h, hdrPos, buffer, offset, n)
            hdrPos += n
            return n
        }
        while (!closed) {
            if (!buf.await(pos, 12_000, { closed })) {
                if (closed) break
                throw IOException("timeshift: brak nowych danych z dekodera")
            }
            val n = buf.read(pos, buffer, offset, length)
            if (n < 0) { onGone(); throw IOException("timeshift: fragment bufora już usunięty") }
            if (n > 0) { pos += n; return n }
        }
        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = uri
    override fun close() { closed = true }
}

/**
 * Pobieranie strumienia na żywo do bufora, gdy odtwarzacz gra z bufora (pauza / cofnięcie):
 * dane idą przez analizator (statystyki, PMT, czyszczenie) i dalej do bufora.
 */
class TimeshiftRecorder(
    private val url: String, private val auth: String?, private val analyzer: () -> TsAnalyzer?, private val onConnect: () -> Unit = {},
) {
    @Volatile private var running = true
    private var conn: HttpURLConnection? = null

    fun start() {
        Thread({
            var fails = 0
            while (running) {
                try {
                    val c = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000; readTimeout = 8000
                        setRequestProperty("User-Agent", "E2Stream")
                        auth?.let { setRequestProperty("Authorization", it) }
                    }
                    conn = c
                    val inp = c.inputStream
                    onConnect()
                    val b = ByteArray(188 * 700)
                    while (running) {
                        val t0 = SystemClock.elapsedRealtime()
                        val n = inp.read(b)
                        if (n < 0) break
                        analyzer()?.let { a ->
                            a.readTime(SystemClock.elapsedRealtime() - t0)
                            if (n > 0) a.feed(b, 0, n)
                        }
                        fails = 0
                    }
                } catch (e: Exception) {
                    if (running) AppLog.i("timeshift: zapis na żywo przerwany ($e)")
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
                if (running) { fails++; Thread.sleep(minOf(5000L, 500L * fails)) }
            }
        }, "timeshift-zapis").start()
    }

    fun stop() {
        running = false
        try { conn?.disconnect() } catch (_: Exception) {}
    }
}
