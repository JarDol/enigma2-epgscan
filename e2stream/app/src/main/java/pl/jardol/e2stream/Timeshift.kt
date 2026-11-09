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
    @Volatile var shrunk = false        // okno skrócone z braku miejsca
        private set

    init { dir.mkdirs(); clearFiles() }

    private fun clearFiles() {
        dir.listFiles { f -> f.name.startsWith("ts-") && f.name.endsWith(".ts") }?.forEach { it.delete() }
    }

    /** Nowy kanał / koniec: wszystko od zera. */
    fun reset() = synchronized(lock) {
        try { out?.close() } catch (_: Exception) {}
        out = null
        segs.clear()
        total = 0
        failed = null
        shrunk = false
        clearFiles()
        lock.notifyAll()
    }

    fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            if (failed != null) return
            try {
                val now = SystemClock.elapsedRealtime()
                val cur = segs.lastOrNull()
                if (cur == null || out == null || now - cur.wall >= 60_000) rotate(now)
                out!!.write(b, off, len)
                segs.last().len += len
                total += len
            } catch (e: IOException) {
                failed = e.message ?: e.toString()
                AppLog.i("timeshift: błąd zapisu — bufor wyłączony ($failed)")
            }
            lock.notifyAll()
        }
    }

    private fun rotate(now: Long) {
        try { out?.close() } catch (_: Exception) {}
        val f = File(dir, "ts-${nextIdx++}.ts")
        out = FileOutputStream(f)
        segs += Seg(f, total, now)
        // okno: najwyżej maxMin pełnych minut (+ bieżąca); mało miejsca — wypadają najstarsze
        while (segs.size > maxOf(2, maxMin + 1)) dropOldest()
        while (segs.size > 2 && dir.usableSpace < RESERVE) { dropOldest(); shrunk = true }
    }

    private fun dropOldest() {
        val s = segs.removeAt(0)
        s.file.delete()
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

        val CHOICES = intArrayOf(0, 15, 30, 60, 90, 120)

        /** Zalecany czas: największy z wyboru, który się mieści. */
        fun recommended(fit: Int): Int = CHOICES.filter { it in 1..fit }.maxOrNull() ?: 0

        /** Katalog bufora: wybrany nośnik albo pamięć urządzenia, gdy nośnika brak. */
        fun dir(ctx: Context, prefs: Prefs): File {
            val p = prefs.tsDir
            if (p.isNotBlank()) {
                val base = File(p)
                if ((base.isDirectory || base.mkdirs()) && base.canWrite()) return File(base, "timeshift")
            }
            return File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "timeshift")
        }

        fun gb(bytes: Long) = String.format(java.util.Locale("pl"), "%.1f GB", bytes / 1e9)
    }
}

/** ExoPlayer czyta strumień z bufora timeshiftu od bajtu [start], doganiając zapis na bieżąco. */
class TimeshiftDataSource(private val buf: TimeshiftBuffer, private val start: Long, private val onGone: () -> Unit) : DataSource {
    @Volatile private var closed = false
    private var pos = 0L
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        uri = dataSpec.uri
        pos = start + dataSpec.position
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
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
class TimeshiftRecorder(private val url: String, private val auth: String?, private val analyzer: () -> TsAnalyzer?) {
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
