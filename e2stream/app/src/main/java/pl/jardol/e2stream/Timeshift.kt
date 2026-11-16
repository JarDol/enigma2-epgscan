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

internal fun timeshiftEarliestSeekWall(oldestWall: Long, now: Long): Long =
    minOf(oldestWall + 10_000, now)

internal fun timeshiftSeekTarget(base: Long, delta: Long, oldestWall: Long, now: Long): Long =
    maxOf(base + delta, timeshiftEarliestSeekWall(oldestWall, now))

internal fun timeshiftAvailableSpan(oldestWall: Long, now: Long): Long =
    now - timeshiftEarliestSeekWall(oldestWall, now)

internal fun timeshiftBufferProgress(shownWall: Long, oldestWall: Long, now: Long): Int {
    val earliest = timeshiftEarliestSeekWall(oldestWall, now)
    val span = now - earliest
    return if (span == 0L) 1000 else ((shownWall - earliest).coerceIn(0, span) * 1000 / span).toInt()
}

/**
 * Bufor timeshiftu: strumień TS bieżącego kanału zapisywany w kawałkach po 1 minucie
 * (ts-N.ts w katalogu bufora). Kawałki to po prostu kolejne fragmenty jednego ciągłego strumienia —
 * odczyt może zacząć się w dowolnym bajcie. Najstarsza minuta wypada, gdy okno przekroczy [maxMin]
 * albo zabraknie miejsca. Czas to zegar monotoniczny (SystemClock.elapsedRealtime) chwili odbioru.
 */
class TimeshiftBuffer(val dir: File, @Volatile var maxMin: Int, /** katalog „timeshift” nośnika (bufory w tle: podkatalogi) */ val root: File = dir) {

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

    // licznik — dwa bufory założone w tej samej milisekundzie nie dostaną tego samego prefiksu plików
    private fun newSession() = "ts-" + java.lang.Long.toString(System.currentTimeMillis(), 36) + "x" + SEQ.incrementAndGet() + "-"

    // --- diagnostyka źródeł zapisu: do bufora powinien pisać jeden wątek (odtwarzacz / pośrednik VLC / zapis w tle)
    private var writerId = -1L
    private var writerName = ""
    private var lastWriteAt = 0L
    private var switches = 0
    private var switchesLogged = 0
    private var switchesReported = 0
    /** Wątek piszący przed resetem (zmiana kanału) — jego dane przez kilka sekund po resecie są odrzucane. */
    private var staleId = -1L
    private var staleUntil = 0L
    private var staleBytes = 0L
    private var staleLogged = false

    /** Źródło zapisu: zmiany wątku do logu (kilka pierwszych, potem podsumowania co minutę). */
    private fun trackWriter(len: Int): Boolean {
        val t = Thread.currentThread()
        val now = SystemClock.elapsedRealtime()
        if (t.id == staleId && now < staleUntil) {
            staleBytes += len
            if (!staleLogged) { staleLogged = true; AppLog.i("timeshift: odrzucam dane starego źródła ${t.name}#${t.id} po zmianie kanału") }
            return false
        }
        if (t.id != writerId) {
            if (writerId == -1L) AppLog.i("timeshift: źródło zapisu ${t.name}#${t.id}")
            else {
                switches++
                if (switchesLogged < 8) {
                    switchesLogged++
                    AppLog.i("timeshift: ZMIANA źródła zapisu ${t.name}#${t.id} (wcześniej $writerName#$writerId, ostatni zapis ${now - lastWriteAt} ms temu)")
                }
            }
            writerId = t.id
            writerName = t.name
            waitPcr = true                         // nowe połączenie — znów od pierwszego PCR
        }
        lastWriteAt = now
        return true
    }

    private fun reportWriters() {
        if (switches > switchesReported) {
            AppLog.i("timeshift: zmian źródła zapisu: ${switches - switchesReported} (razem $switches)")
            switchesReported = switches
        }
        if (staleBytes > 0) {
            AppLog.i("timeshift: odrzucone dane starego źródła po zmianie kanału: ${staleBytes / 1000} kB")
            staleBytes = 0
        }
    }

    /** Nowy kanał / koniec: wszystko od zera; stare pliki kasowane w tle (bez czekania). */
    fun reset() = synchronized(lock) {
        reportWriters()
        if (writerId != -1L) { staleId = writerId; staleUntil = SystemClock.elapsedRealtime() + 5000; staleLogged = false }
        writerId = -1L
        switches = 0; switchesLogged = 0; switchesReported = 0
        waitPcr = true; skipped = 0; skippedPids.clear()
        if (pin != null) finishPin()          // nagranie trwało — kawałki zachowane, nie skasowane
        try { out?.close() } catch (_: Exception) {}
        out = null
        segs.clear()
        total = 0
        kfCount = 0; kfHead = 0
        ringLen = 0
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

    /**
     * Początek strumienia (nowy kanał, nowe połączenie): pakiety do pierwszego z PCR są pomijane. Enigma2 zaczyna
     * strumień paczką danych bez obrazu i zegara (>100 kB) — nagranie zaczynające się od niej jest dla ExoPlayera
     * nieprzewijalne (szuka PCR tylko w pierwszych 112 kB pliku).
     */
    private var waitPcr = true
    /** PID zegara programu (z PMT, analizator); PCR innych programów multipleksu się nie liczy. */
    @Volatile var pcrPid: () -> Int = { -1 }
    private var skipped = 0L
    private val skippedPids = HashMap<Int, Int>()

    /** Czy pakiet od [o] ma trafić do bufora (false — pominięty, bo przed pierwszym PCR). */
    private fun admit(p: ByteArray, o: Int): Boolean {
        if (!waitPcr) return true
        val tei = (p[o + 1].toInt() and 0x80) != 0
        val want = pcrPid()
        val pid0 = ((p[o + 1].toInt() and 0x1f) shl 8) or (p[o + 2].toInt() and 0xff)
        val hasPcr = !tei && (p[o + 3].toInt() and 0x20) != 0 && (p[o + 4].toInt() and 0xff) >= 7 && (p[o + 5].toInt() and 0x10) != 0
        // PID zegara nieznany (brak PMT) po ~1 MB — dowolny PCR, żeby nie gubić początku strumienia
        val pcr = hasPcr && (if (want >= 0) pid0 == want else skipped >= ANY_PCR_AFTER)
        if (pcr || skipped >= SKIP_MAX) {
            waitPcr = false
            if (skipped > 0) {
                val pids = skippedPids.entries.sortedByDescending { it.value }.take(5).joinToString(", ") { "${it.key}×${it.value}" }
                AppLog.i("timeshift: zapis od pierwszego pakietu z zegarem PCR (pid $want) — pominięto ${skipped / 1000} kB na początku strumienia (PID: $pids)" +
                    if (!pcr) " — PCR nie znaleziony, zapis bez czekania" else "")
            }
            skipped = 0; skippedPids.clear()
            return true
        }
        skipped += 188
        val pid = ((p[o + 1].toInt() and 0x1f) shl 8) or (p[o + 2].toInt() and 0xff)
        skippedPids[pid] = (skippedPids[pid] ?: 0) + 1
        return false
    }

    /** Nowe połączenie ze strumieniem: niepełny pakiet z poprzedniego jest porzucany, żeby bufor został zgodny z siatką 188 B. */
    fun resync() = synchronized(lock) { pendLen = 0 }

    /** Do bufora trafiają tylko całe pakiety TS — odczyt od dowolnego początku minuty zawsze trafia w granicę pakietu. */
    fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            if (failed != null) return
            if (!trackWriter(len)) return
            try {
                var o = off
                val end = off + len
                if (pendLen > 0) {
                    val n = minOf(188 - pendLen, end - o)
                    System.arraycopy(b, o, pend, pendLen, n)
                    pendLen += n; o += n
                    if (pendLen < 188) return
                    pendLen = 0
                    if (pend[0] == SYNC && admit(pend, 0)) writeRaw(pend, 0, 188)
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
                    if (waitPcr && !admit(b, o)) { o += 188; run = o; continue }
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
        markKeyframes(b, off, len, now)
        out!!.write(b, off, len)
        ringPut(b, off, len)
        segs.last().len += len
        total += len
    }

    private fun rotate(now: Long) {
        reportWriters()
        try { out?.close() } catch (_: Exception) {}
        val f = File(dir, "$session${nextIdx++}.ts")
        out = FileOutputStream(f)
        segs += Seg(f, total, now)
        // okno: najwyżej maxMin pełnych minut (+ bieżąca); mało miejsca — wypadają najstarsze
        while (segs.size > maxOf(2, maxMin + 1)) dropOldest()
        while (segs.size > 2 && dir.usableSpace < RESERVE) { dropOldest(); shrunk = true }
    }

    // --- ostatnie RING bajtów w pamięci: odtwarzanie na żywo i tuż za żywym czyta z RAM, nie z dysku
    // (otwieranie pliku na USB przy każdym odczycie było wolniejsze niż napływ strumienia — pauzy co ~30 s)
    private var ring: ByteArray? = null
    private var ringLen = 0

    /** Wywoływane przed zwiększeniem [total] o [len]. */
    private fun ringPut(b: ByteArray, off: Int, len: Int) {
        val r = ring ?: ByteArray(RING).also { ring = it }
        val l = minOf(len, RING)
        var o = off + (len - l)
        var at = ((total + (len - l)) % RING).toInt()
        var left = l
        while (left > 0) {
            val n = minOf(left, RING - at)
            System.arraycopy(b, o, r, at, n)
            o += n; left -= n; at = (at + n) % RING
        }
        ringLen = minOf(RING.toLong(), ringLen.toLong() + len).toInt()
    }

    /** Odczyt z pamięci; -1 — [pos] poza oknem w pamięci (czytaj z dysku), 0 — brak nowych danych. */
    fun readRam(pos: Long, dst: ByteArray, off: Int, len: Int): Int = synchronized(lock) {
        val r = ring ?: return -1
        if (pos >= total) return 0
        if (pos < total - ringLen) return -1
        val all = minOf(len.toLong(), total - pos).toInt()
        var n = all
        var at = (pos % RING).toInt()
        var o = off
        while (n > 0) {
            val c = minOf(n, RING - at)
            System.arraycopy(r, at, dst, o, c)
            o += c; n -= c; at = (at + c) % RING
        }
        all
    }

    /** Plik, przesunięcie w nim dla [pos] i liczba bajtów dostępnych w tym pliku od tego miejsca. */
    fun locateRun(pos: Long): Triple<File, Long, Long>? = synchronized(lock) {
        for (i in segs.indices.reversed()) {
            val s = segs[i]
            if (pos >= s.start) {
                val inSeg = pos - s.start
                return if (inSeg < s.len) Triple(s.file, inSeg, s.len - inSeg) else null
            }
        }
        null
    }

    // --- klatki kluczowe: miejsca (bajt, chwila) początku PES obrazu z SPS / VPS / nagłówkiem sekwencji MPEG-2
    // albo z flagą random_access — start odtwarzania z bufora od pewnej klatki, z zapasem
    private val kfPos = LongArray(KF_MAX)
    private val kfWall = LongArray(KF_MAX)
    private var kfHead = 0
    private var kfCount = 0

    private fun markKeyframes(b: ByteArray, off: Int, len: Int, now: Long) {
        var p = off
        val end = off + len
        while (p + 188 <= end) {
            if (b[p] == SYNC && (b[p + 1].toInt() and 0x40) != 0 && isKeyframeStart(b, p)) {
                val pos = total + (p - off)
                if (kfCount == 0 || kfPos[(kfHead + kfCount - 1) % KF_MAX] < pos) {
                    val i = (kfHead + kfCount) % KF_MAX
                    kfPos[i] = pos; kfWall[i] = now
                    if (kfCount < KF_MAX) kfCount++ else kfHead = (kfHead + 1) % KF_MAX
                }
            }
            p += 188
        }
    }

    private fun isKeyframeStart(b: ByteArray, p: Int): Boolean {
        val afc = (b[p + 3].toInt() shr 4) and 3
        var q = p + 4
        if (afc and 2 != 0) {
            val al = b[p + 4].toInt() and 0xFF
            if (al > 0 && (b[p + 5].toInt() and 0x40) != 0) {
                // flaga random_access — tylko dla PES obrazu (strumień E0–EF)
                val ps = p + 5 + al
                if (ps + 4 <= p + 188 && b[ps] == 0.toByte() && b[ps + 1] == 0.toByte() && b[ps + 2] == 1.toByte() &&
                    (b[ps + 3].toInt() and 0xF0) == 0xE0) return true
            }
            q = p + 5 + al
        }
        if (afc and 1 == 0 || q + 9 > p + 188) return false
        if (b[q] != 0.toByte() || b[q + 1] != 0.toByte() || b[q + 2] != 1.toByte() || (b[q + 3].toInt() and 0xF0) != 0xE0) return false
        var i = q + 9 + (b[q + 8].toInt() and 0xFF)
        val e = p + 188 - 4
        while (i <= e) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 1.toByte()) {
                val x = b[i + 3].toInt() and 0xFF
                // H.264 SPS (typ 7) / IDR (typ 5), HEVC VPS / SPS, MPEG-2 nagłówek sekwencji
                if ((x and 0x80) == 0 && ((x and 0x1F) == 7 || (x and 0x1F) == 5)) return true
                if (x == 0x40 || x == 0x42 || x == 0xB3) return true
                i += 3
            } else i++
        }
        return false
    }

    /**
     * Najpóźniejsza klatka kluczowa zapisana nie później niż [wall] i nie wcześniej niż początek okna;
     * zwraca (bajt, chwila) albo null, gdy brak.
     */
    fun keyframeAt(wall: Long): Pair<Long, Long>? = synchronized(lock) {
        val oldest = segs.firstOrNull()?.start ?: return null
        for (k in kfCount - 1 downTo 0) {
            val i = (kfHead + k) % KF_MAX
            if (kfPos[i] < oldest) break
            if (kfWall[i] <= wall && kfPos[i] < total) return kfPos[i] to kfWall[i]
        }
        null
    }

    /** Pozycje najnowszych klatek kluczowych, które są jeszcze w pamięci (od najnowszej), najwyżej [max]. */
    fun recentKeyframes(max: Int): LongArray = synchronized(lock) {
        val lo = total - ringLen
        val out = ArrayList<Long>(max)
        for (k in kfCount - 1 downTo 0) {
            val p = kfPos[(kfHead + k) % KF_MAX]
            if (p < lo) break
            if (p < total) out += p
            if (out.size >= max) break
        }
        out.toLongArray()
    }

    private fun dropOldest() {
        val s = segs.removeAt(0)
        trimmed = true
        val f = s.file
        val p = pin
        if (p != null && s.start + s.len > p.from) {           // kawałek nagrania: przeniesiony, nie usunięty
            p.take(f, s.len)
            return
        }
        Thread({ f.delete() }, "timeshift-usun").start()      // usuwanie dużego pliku nie wstrzymuje zapisu
    }

    // ------------------------------------------------------------ nagrania lokalne (przypięte kawałki)

    /**
     * Nagranie z bufora: kawałki od bajtu [from] nie są kasowane — przy wypadnięciu z okna trafiają
     * (zmiana nazwy, ten sam nośnik) do katalogu nagrania [dir] jako part-00001.ts, part-00002.ts…
     */
    class Pin(val dir: File, val from: Long, val fromWall: Long) {
        var parts = 0
            private set
        var bytes = 0L
            private set

        fun take(f: File, len: Long) {
            parts++
            val dst = File(dir, String.format(java.util.Locale.US, "part-%05d.ts", parts))
            if (!f.renameTo(dst)) {
                try { f.copyTo(dst, overwrite = true); f.delete() } catch (e: Exception) { AppLog.i("nagranie: przeniesienie ${f.name} nieudane ($e)") }
            }
            bytes += len
        }
    }

    @Volatile var pin: Pin? = null
        private set

    /** Początek nagrania: przypięcie kawałków od bajtu [from] (najstarszy dostępny, gdy wcześniejszy). */
    fun startPin(dir: File, from: Long): Pin? = synchronized(lock) {
        if (pin != null || failed != null) return null
        dir.mkdirs()
        val start = maxOf(from, segs.firstOrNull()?.start ?: total)
        Pin(dir, start, wallAt(start)).also { pin = it }
    }

    /** Bajty nagrania razem z kawałkami jeszcze w buforze. */
    fun pinnedBytes(): Long = synchronized(lock) {
        val p = pin ?: return 0
        p.bytes + segs.filter { it.start + it.len > p.from }.sumOf { it.len }
    }

    /**
     * Koniec nagrania: kawałki z bufora od początku nagrania przeniesione do jego katalogu, bufor zaczyna od nowa
     * (pusty). Zwraca nagranie albo null, gdy żadne nie trwało.
     */
    fun finishPin(): Pin? = synchronized(lock) {
        val p = pin ?: return null
        try { out?.close() } catch (_: Exception) {}
        out = null
        pendLen = 0
        for (s in segs) {
            if (s.start + s.len > p.from && s.len > 0) p.take(s.file, s.len) else s.file.delete()
        }
        segs.clear()
        pin = null
        total = 0
        kfCount = 0; kfHead = 0
        ringLen = 0
        failed = null
        shrunk = false
        trimmed = false
        session = newSession()
        lock.notifyAll()
        p
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
        readRam(pos, buf, off, len).let { if (it >= 0) return it }
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
            // lista plików od razu (w chwili wywołania): pliki nowego bufora, który powstanie za chwilę w tym samym
            // katalogu, nie trafią do kasowania (wcześniej wątek sprzątający potrafił skasować świeży bufor kanału)
            val old = try {
                dir.listFiles { f -> f.name.startsWith("ts-") && f.name.endsWith(".ts") && (keep == null || !f.name.startsWith(keep)) }
            } catch (_: Exception) { null }
            if (old.isNullOrEmpty()) return
            Thread({
                try {
                    var n = 0
                    old.forEach { if (it.delete()) n++ }
                    if (n > 0) AppLog.i("timeshift: usunięto $n starych plików bufora")
                } catch (_: Exception) {}
            }, "timeshift-sprzatanie").start()
        }

        private val SEQ = java.util.concurrent.atomic.AtomicInteger()
        private const val KF_MAX = 4096
        /** Ostatnie dane w pamięci: ~8 s przy 8 Mb/s (na bufor, także kanały w tle). */
        const val RING = 8 * 1024 * 1024
        private const val SYNC = 0x47.toByte()
        /** Najwięcej pomijanych danych przed pierwszym PCR (potem zapis bez czekania). */
        private const val SKIP_MAX = 8L * 1_000_000
        /** Po tylu pominiętych bajtach bez znanego PID-u zegara przyjmujemy PCR z dowolnego PID-u. */
        private const val ANY_PCR_AFTER = 1_000_000L

        /** Zapas wolnego miejsca, którego bufor nie rusza. */
        const val RESERVE = 400L * 1_000_000
        /** Ostrożne oszacowanie: kanał HD do ~8 Mb/s = 60 MB na minutę. */
        const val MB_PER_MIN = 60L
        /** Długość bufora, gdy włączone jest tylko odtwarzanie przez bufor (bez timeshiftu). */
        const val BUF_PLAY_MIN = 5

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

        /** Katalogi buforów: bieżący kanał + kanały buforowane w tle (bez wspólnych plików — każdy sprząta tylko swój). */
        fun slotDirs(root: File): List<File> = listOf(root, File(root, "kanal-2"), File(root, "kanal-3"), File(root, "kanal-4"))

        fun gb(bytes: Long) = String.format(java.util.Locale("pl"), "%.1f GB", bytes / 1e9)
    }
}

/** ExoPlayer czyta strumień z bufora timeshiftu od bajtu [start], doganiając zapis na bieżąco. */
class TimeshiftDataSource(
    private val buf: TimeshiftBuffer, private val start: Long, private var header: ByteArray?, private val onGone: () -> Unit,
    /** Nagłówek PAT + PMT pobierany dopiero przy pierwszych danych (start kanału — analizator zna już PMT). */
    private val lateHeader: (() -> ByteArray?)? = null,
    /** false — bufor przejął już inny kanał: czekamy na zamknięcie (bez błędu, który restartowałby odtwarzanie). */
    private val alive: () -> Boolean = { true },
) : DataSource {
    @Volatile private var closed = false
    private var pos = 0L
    private var uri: Uri? = null
    private var hdrPos = 0
    // odczyt z dysku (timeshift dalej od żywego): plik otwarty raz, porcje po 512 kB
    private var raf: java.io.RandomAccessFile? = null
    private var rafFile: java.io.File? = null
    private val cache = ByteArray(512 * 1024)
    private var cacheAt = -1L
    private var cacheLen = 0

    private fun readDisk(dst: ByteArray, off: Int, len: Int): Int {
        if (cacheAt >= 0 && pos >= cacheAt && pos < cacheAt + cacheLen) {
            val n = minOf(len.toLong(), cacheAt + cacheLen - pos).toInt()
            System.arraycopy(cache, (pos - cacheAt).toInt(), dst, off, n)
            return n
        }
        val (f, inSeg, avail) = buf.locateRun(pos) ?: return -1
        if (rafFile != f) {
            try { raf?.close() } catch (_: Exception) {}
            raf = try { java.io.RandomAccessFile(f, "r") } catch (_: IOException) { null }
            rafFile = f
        }
        val r = raf ?: return -1
        val want = minOf(cache.size.toLong(), avail).toInt()
        val n = try { r.seek(inSeg); r.read(cache, 0, want) } catch (_: IOException) { -1 }
        if (n <= 0) { rafFile = null; return if (n < 0) -1 else 0 }
        cacheAt = pos; cacheLen = n
        val c = minOf(len, n)
        System.arraycopy(cache, 0, dst, off, c)
        return c
    }

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
        if (!alive()) { while (!closed) Thread.sleep(100); return C.RESULT_END_OF_INPUT }
        if (lateHeader != null && header == null && hdrPos == 0) {
            if (!buf.await(pos, 12_000, { closed })) {
                if (closed) return C.RESULT_END_OF_INPUT
                throw IOException("timeshift: brak danych z dekodera")
            }
            header = lateHeader.invoke()
            if (header == null) hdrPos = Int.MAX_VALUE
        }
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
            if (!alive()) { while (!closed) Thread.sleep(100); break }
            var n = buf.readRam(pos, buffer, offset, length)
            if (n < 0) n = readDisk(buffer, offset, length)
            if (n < 0) { onGone(); throw IOException("timeshift: fragment bufora już usunięty") }
            if (n > 0) { pos += n; return n }
        }
        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = uri
    override fun close() {
        closed = true
        try { raf?.close() } catch (_: Exception) {}
        raf = null; rafFile = null; cacheAt = -1; cacheLen = 0
    }
}

/**
 * Pobieranie strumienia na żywo do bufora, gdy odtwarzacz gra z bufora (pauza / cofnięcie):
 * dane idą przez analizator (statystyki, PMT, czyszczenie) i dalej do bufora.
 */
class TimeshiftRecorder(
    private val url: String, private val auth: String?, private val analyzer: () -> TsAnalyzer?, private val onConnect: () -> Unit = {},
    private val onFailure: () -> Unit,
) {
    @Volatile private var running = true
    private var conn: HttpURLConnection? = null

    fun start() {
        Thread({
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
                val sourceAnalyzer = analyzer()
                val analyzerSession = sourceAnalyzer?.sessionId
                while (running) {
                    val t0 = SystemClock.elapsedRealtime()
                    val n = inp.read(b)
                    if (n < 0 || !running) break
                    analyzer()?.let { a ->
                        if (a === sourceAnalyzer) {
                            a.readTime(SystemClock.elapsedRealtime() - t0, analyzerSession)
                            if (n > 0) a.feed(b, 0, n, analyzerSession)
                        }
                    }
                }
                if (running) AppLog.i("timeshift: strumień na żywo zakończył się")
            } catch (e: java.io.IOException) {
                if (running) AppLog.i("timeshift: zapis na żywo przerwany ($e)")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
            if (running) onFailure()
        }, "timeshift-zapis").start()
    }

    fun stop() {
        running = false
        try { conn?.disconnect() } catch (_: Exception) {}
    }
}
