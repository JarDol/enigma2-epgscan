package pl.jardol.e2stream

import java.io.File
import java.io.InputStream

/**
 * Diagnostyka nagrania lokalnego: przegląd zegara programu (PCR) we wszystkich kawałkach po kolei.
 * W ciągłym strumieniu PCR stale rośnie; cofnięcie albo skok > 1 s znaczy, że do bufora trafiły dane
 * z innego strumienia / połączenia (zmiana kanału, wznowienie, drugie połączenie VLC).
 */
object RecScan {

    /** Skok zegara: [part] — numer kawałka (od 1), [offset] — bajt w kawałku, [pos] — bajt od początku nagrania. */
    data class Jump(val part: Int, val offset: Long, val pos: Long, val deltaMs: Long, val pid: Int)

    data class Result(
        val parts: Int,
        val bytes: Long,
        val pcrPid: Int,
        val pcrCount: Long,
        val firstMs: Long,
        val lastMs: Long,
        /** Suma ciągłych przyrostów PCR (bez skoków) — faktyczna długość materiału. */
        val continuousMs: Long,
        val jumps: List<Jump>,
        val jumpCount: Int,
        val syncLoss: Int,
        /** Pierwszy bajt nie jest początkiem pakietu TS. */
        val badStart: Boolean,
        /** PCR_PID z pierwszego PMT (tak jak go bierze ExoPlayer); -1 — brak PMT. */
        val pmtPcrPid: Int = -1,
        /** Jak ExoPlayer: PCR (pid z PMT) w pierwszych / ostatnich [EXO_WINDOW] bajtach; null — nie znalazł. */
        val exoFirstMs: Long? = null,
        val exoLastMs: Long? = null,
        /** Co jest na początku pliku: pierwszy PAT / PCR (bajt) i najczęstsze PID w oknie ExoPlayera. */
        val headInfo: String = "",
    ) {
        /** Tak liczy długość ExoPlayer: ostatni − pierwszy PCR (ujemna = nagranie „nieprzewijalne”). */
        val playerMs get() = wrapMs(lastMs - firstMs)
    }

    private const val SYNC = 0x47
    /** Tyle bajtów z początku i końca czyta ExoPlayer, szukając PCR do wyliczenia długości (600 pakietów). */
    const val EXO_WINDOW = 600 * 188
    private const val MOD_MS = (1L shl 33) / 90
    const val MAX_JUMPS = 20

    private fun wrapMs(d: Long): Long {
        var x = d % MOD_MS
        if (x > MOD_MS / 2) x -= MOD_MS
        if (x < -MOD_MS / 2) x += MOD_MS
        return x
    }

    fun scan(files: List<File>): Result {
        val r = scanStreams(files.map { { it.inputStream() } to it.length() })
        return try { withExoProbe(r, head(files), tail(files)) } catch (_: Exception) { r }
    }

    private fun head(files: List<File>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (f in files) {
            if (out.size() >= EXO_WINDOW) break
            f.inputStream().use { inp ->
                val b = ByteArray(minOf(EXO_WINDOW - out.size(), f.length().toInt().coerceAtLeast(0)))
                var n = 0
                while (n < b.size) { val k = inp.read(b, n, b.size - n); if (k <= 0) break; n += k }
                out.write(b, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun tail(files: List<File>): ByteArray {
        var need = EXO_WINDOW.toLong()
        val chunks = ArrayList<ByteArray>()
        for (f in files.reversed()) {
            if (need <= 0) break
            val take = minOf(need, f.length())
            java.io.RandomAccessFile(f, "r").use { r ->
                val b = ByteArray(take.toInt())
                r.seek(f.length() - take)
                r.readFully(b)
                chunks.add(0, b)
            }
            need -= take
        }
        return chunks.fold(ByteArray(0)) { a, b -> a + b }
    }

    /** Odtworzenie odczytu długości przez ExoPlayer (TsDurationReader): PCR_PID z PMT, PCR w oknie początku i końca. */
    fun withExoProbe(r: Result, head: ByteArray, tail: ByteArray): Result {
        val pmtPid = pmtPcrPid(head)
        val pcrPid = if (pmtPid >= 0) pmtPid else r.pcrPid
        val info = headInfo(head, r.pcrPid)
        // początek: każdy bajt 0x47 (także nie na granicy pakietu), pierwszy pasujący PCR
        var first: Long? = null
        for (i in 0 until head.size - 11) {
            if ((head[i].toInt() and 0xff) != SYNC) continue
            val v = exoPcr(head, i, pcrPid)
            if (v != null) { first = v; break }
        }
        // koniec: od końca, tylko pozycje wyglądające na początek pakietu
        var last: Long? = null
        var i = tail.size - 188
        while (i >= 0) {
            if ((tail[i].toInt() and 0xff) == SYNC && (i - 188 < 0 || (tail[i - 188].toInt() and 0xff) == SYNC) &&
                (i + 188 >= tail.size || (tail[i + 188].toInt() and 0xff) == SYNC)) {
                val v = exoPcr(tail, i, pcrPid)
                if (v != null) { last = v; break }
            }
            i--
        }
        return r.copy(pmtPcrPid = pmtPid, exoFirstMs = first, exoLastMs = last, headInfo = info)
    }

    /** Pierwszy PAT, pierwszy PCR [pcrPid] (bajt od początku) i 4 najczęstsze PID w [b]. */
    private fun headInfo(b: ByteArray, pcrPid: Int): String {
        var o = 0
        while (o + 376 < b.size && !((b[o].toInt() and 0xff) == SYNC && (b[o + 188].toInt() and 0xff) == SYNC)) o++
        var pat = -1
        var pcr = -1
        val hist = HashMap<Int, Int>()
        while (o + 188 <= b.size) {
            if ((b[o].toInt() and 0xff) != SYNC) { o++; continue }
            val pid = ((b[o + 1].toInt() and 0x1f) shl 8) or (b[o + 2].toInt() and 0xff)
            hist[pid] = (hist[pid] ?: 0) + 1
            if (pid == 0 && pat < 0) pat = o
            if (pid == pcrPid && pcr < 0 && exoPcr(b, o, pcrPid) != null) pcr = o
            o += 188
        }
        val top = hist.entries.sortedByDescending { it.value }.take(4).joinToString(", ") { "${it.key}×${it.value}" }
        fun at(x: Int) = if (x < 0) "BRAK" else "${x / 1000} kB"
        return "pierwszy PAT ${at(pat)}, pierwszy PCR ${at(pcr)}, PID: $top"
    }

    /** Jak TsUtil.readPcrFromPacket: bez pakietów z TEI, PID = [pid], pole adaptacji ≥ 7 B z flagą PCR. */
    private fun exoPcr(b: ByteArray, i: Int, pid: Int): Long? {
        if (i + 11 >= b.size) return null
        if ((b[i + 1].toInt() and 0x80) != 0) return null
        val p = ((b[i + 1].toInt() and 0x1f) shl 8) or (b[i + 2].toInt() and 0xff)
        if (p != pid || (b[i + 3].toInt() and 0x20) == 0) return null
        if ((b[i + 4].toInt() and 0xff) < 7 || (b[i + 5].toInt() and 0x10) == 0) return null
        val v = { k: Int -> (b[i + k].toLong() and 0xff) }
        return ((v(6) shl 25) or (v(7) shl 17) or (v(8) shl 9) or (v(9) shl 1) or (v(10) shr 7)) / 90
    }

    /**
     * PCR_PID tak jak w ExoPlayerze (tryb jednego PMT): PID-y PMT wszystkich programów z PAT, PCR_PID z pierwszego
     * PMT, który faktycznie przyjdzie (analizator usuwa PMT innych programów multipleksu). -1 — brak.
     */
    fun pmtPcrPid(b: ByteArray): Int {
        var start = 0
        while (start + 376 < b.size && !((b[start].toInt() and 0xff) == SYNC && (b[start + 188].toInt() and 0xff) == SYNC)) start++
        val pmtPids = HashSet<Int>()
        var o = start
        while (o + 188 <= b.size) {
            if ((b[o].toInt() and 0xff) == SYNC && (b[o + 1].toInt() and 0x40) != 0) {
                val pid = ((b[o + 1].toInt() and 0x1f) shl 8) or (b[o + 2].toInt() and 0xff)
                val afc = (b[o + 3].toInt() shr 4) and 3
                var p = o + 4
                if (afc == 2) { o += 188; continue }
                if (afc == 3) p += 1 + (b[p].toInt() and 0xff)
                if (p < o + 188) {
                    p += 1 + (b[p].toInt() and 0xff)            // pointer_field
                    if (pid == 0 && pmtPids.isEmpty() && p + 8 < o + 188 && (b[p].toInt() and 0xff) == 0) {
                        val len = ((b[p + 1].toInt() and 0x0f) shl 8) or (b[p + 2].toInt() and 0xff)
                        var q = p + 8
                        val end = minOf(p + 3 + len - 4, o + 188)
                        while (q + 4 <= end) {
                            val prog = ((b[q].toInt() and 0xff) shl 8) or (b[q + 1].toInt() and 0xff)
                            val pp = ((b[q + 2].toInt() and 0x1f) shl 8) or (b[q + 3].toInt() and 0xff)
                            if (prog != 0) pmtPids += pp
                            q += 4
                        }
                    } else if (pid in pmtPids && p + 10 < o + 188 && (b[p].toInt() and 0xff) == 2) {
                        return ((b[p + 8].toInt() and 0x1f) shl 8) or (b[p + 9].toInt() and 0xff)
                    }
                }
            }
            o += 188
        }
        return -1
    }

    /** [parts] — (otwarcie strumienia, długość) kolejnych kawałków. */
    fun scanStreams(parts: List<Pair<() -> InputStream, Long>>): Result {
        val count = HashMap<Int, Long>()
        val last = HashMap<Int, Long>()
        val first = HashMap<Int, Long>()
        val cont = HashMap<Int, Long>()
        val jumps = HashMap<Int, ArrayList<Jump>>()
        val jumpN = HashMap<Int, Int>()
        var syncLoss = 0
        var badStart = false
        var base = 0L
        val pkt = ByteArray(188)
        val buf = ByteArray(188 * 2048)
        for ((pi, part) in parts.withIndex()) {
            var carry = 0
            var off = 0L                         // bajt w kawałku odpowiadający pkt[0]
            part.first().use { inp ->
                var firstByte = true
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    var i = 0
                    if (firstByte && pi == 0 && n > 0 && (buf[0].toInt() and 0xff) != SYNC) badStart = true
                    firstByte = false
                    while (i < n) {
                        if (carry == 0) {
                            if ((buf[i].toInt() and 0xff) != SYNC) {
                                syncLoss++
                                while (i < n && (buf[i].toInt() and 0xff) != SYNC) { i++; off++ }
                                continue
                            }
                        }
                        val take = minOf(188 - carry, n - i)
                        System.arraycopy(buf, i, pkt, carry, take)
                        carry += take; i += take
                        if (carry < 188) break
                        carry = 0
                        val pcr = pcrMs(pkt)
                        if (pcr != null) {
                            val (pid, ms) = pcr
                            count[pid] = (count[pid] ?: 0) + 1
                            val prev = last[pid]
                            if (prev == null) first[pid] = ms
                            else {
                                val d = wrapMs(ms - prev)
                                if (d < 0 || d > 1000) {
                                    val k = (jumpN[pid] ?: 0) + 1
                                    jumpN[pid] = k
                                    if (k <= MAX_JUMPS) jumps.getOrPut(pid) { ArrayList() } += Jump(pi + 1, off, base + off, d, pid)
                                } else cont[pid] = (cont[pid] ?: 0) + d
                            }
                            last[pid] = ms
                        }
                        off += 188
                    }
                }
            }
            base += part.second
        }
        val pid = count.maxByOrNull { it.value }?.key ?: -1
        return Result(
            parts = parts.size, bytes = base, pcrPid = pid, pcrCount = count[pid] ?: 0,
            firstMs = first[pid] ?: 0, lastMs = last[pid] ?: 0, continuousMs = cont[pid] ?: 0,
            jumps = jumps[pid] ?: emptyList(), jumpCount = jumpN[pid] ?: 0, syncLoss = syncLoss, badStart = badStart,
        )
    }

    /** PCR z pola adaptacji pakietu: (PID, ms) albo null. */
    fun pcrMs(p: ByteArray): Pair<Int, Long>? {
        val afc = (p[3].toInt() shr 4) and 3
        if (afc != 2 && afc != 3) return null
        val len = p[4].toInt() and 0xff
        if (len < 7 || (p[5].toInt() and 0x10) == 0) return null
        val b = { i: Int -> (p[i].toLong() and 0xff) }
        val ticks = (b(6) shl 25) or (b(7) shl 17) or (b(8) shl 9) or (b(9) shl 1) or (b(10) shr 7)
        val pid = ((p[1].toInt() and 0x1f) shl 8) or (p[2].toInt() and 0xff)
        return pid to ticks / 90
    }

    private fun t(ms: Long): String {
        val s = kotlin.math.abs(ms) / 1000
        val sign = if (ms < 0) "−" else ""
        return if (s >= 3600) String.format(java.util.Locale.US, "%s%d:%02d:%02d", sign, s / 3600, s / 60 % 60, s % 60)
        else String.format(java.util.Locale.US, "%s%d:%02d.%d", sign, s / 60, s % 60, (kotlin.math.abs(ms) % 1000) / 100)
    }

    /** Opis do logu; [lengthSec] — długość wg zegara aplikacji (opis nagrania). */
    fun describe(r: Result, lengthSec: Long): String {
        val sb = StringBuilder()
        sb.append("${r.parts} plików, ${r.bytes / 1_000_000} MB, PCR pid ${r.pcrPid} (${r.pcrCount}×), ")
        sb.append("długość: wg nagrywania ${t(lengthSec * 1000)}, ciągła wg PCR ${t(r.continuousMs)}, ")
        sb.append("ostatni−pierwszy PCR ${t(r.playerMs)}")
        if (r.badStart) sb.append(", początek nie na granicy pakietu")
        sb.append(", ExoPlayer (pierwsze/ostatnie ${EXO_WINDOW / 1000} kB): PMT ${if (r.pmtPcrPid >= 0) "jest" else "BRAK"}, " +
            "PCR na początku ${if (r.exoFirstMs != null) "jest" else "BRAK"}, na końcu ${if (r.exoLastMs != null) "jest" else "BRAK"}")
        val f = r.exoFirstMs; val l = r.exoLastMs
        if (f != null && l != null) sb.append(" → długość ${t(wrapMs(l - f))}") else sb.append(" → NIEPRZEWIJALNE")
        if (r.headInfo.isNotEmpty()) sb.append(" [początek pliku: ${r.headInfo}]")
        if (r.syncLoss > 0) sb.append(", utrata synchronizacji ×${r.syncLoss}")
        if (r.jumpCount == 0) sb.append(", zegar ciągły") else {
            sb.append(", skoki zegara ×${r.jumpCount}: ")
            sb.append(r.jumps.joinToString("; ") { j ->
                val at = if (r.bytes > 0 && lengthSec > 0) " (~${t(j.pos * lengthSec * 1000 / r.bytes)})" else ""
                "kawałek ${j.part} @${j.offset / 1_000_000}.${(j.offset / 100_000) % 10} MB$at: ${if (j.deltaMs > 0) "+" else ""}${t(j.deltaMs)}"
            })
            if (r.jumpCount > r.jumps.size) sb.append("; …")
        }
        return sb.toString()
    }
}
