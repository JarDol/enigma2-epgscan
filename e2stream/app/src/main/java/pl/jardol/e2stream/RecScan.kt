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
    ) {
        /** Tak liczy długość ExoPlayer: ostatni − pierwszy PCR (ujemna = nagranie „nieprzewijalne”). */
        val playerMs get() = wrapMs(lastMs - firstMs)
    }

    private const val SYNC = 0x47
    private const val MOD_MS = (1L shl 33) / 90
    const val MAX_JUMPS = 20

    private fun wrapMs(d: Long): Long {
        var x = d % MOD_MS
        if (x > MOD_MS / 2) x -= MOD_MS
        if (x < -MOD_MS / 2) x += MOD_MS
        return x
    }

    fun scan(files: List<File>): Result = scanStreams(files.map { { it.inputStream() } to it.length() })

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
        sb.append("wg odtwarzacza (ostatni−pierwszy PCR) ${t(r.playerMs)}")
        if (r.playerMs <= 0) sb.append(" → NIEPRZEWIJALNE")
        if (r.badStart) sb.append(", początek nie na granicy pakietu")
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
