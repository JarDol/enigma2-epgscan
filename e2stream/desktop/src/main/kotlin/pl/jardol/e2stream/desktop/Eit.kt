package pl.jardol.e2stream.desktop

import java.nio.charset.Charset
import java.text.Normalizer

/** Audycja z EIT „teraz/następne” (tabela 0x4E) strumienia z dekodera. Czas w sekundach UTC (epoch). */
data class EitEvent(
    val id: Int,
    val begin: Long,
    val duration: Long,
    val title: String,
    val shortDesc: String,
    val longDesc: String,
) {
    val end: Long get() = begin + duration
}

/**
 * Składa sekcje PSI z pakietów PID 0x12 (EIT). Pakiet może zawierać koniec jednej i początek kolejnych sekcji,
 * a sekcja może się ciągnąć przez wiele pakietów. Gotowa sekcja trafia do [onSection] (bufor, długość).
 */
class SectionAssembler(private val onSection: (ByteArray, Int) -> Unit) {
    private val buf = ByteArray(4096 + 188)
    private var len = 0
    private var active = false
    private var lastCc = -1

    fun reset() { len = 0; active = false; lastCc = -1 }

    /** [p] pakiet TS od [o]; [payload] = początek danych (po polu adaptacji). */
    fun packet(p: ByteArray, o: Int, payload: Int) {
        val end = o + 188
        if (payload >= end) return
        val pusi = (p[o + 1].toInt() and 0x40) != 0
        val cc = p[o + 3].toInt() and 0x0f
        if (lastCc >= 0 && cc != ((lastCc + 1) and 0x0f) && cc != lastCc) active = false   // zgubione pakiety
        if (cc == lastCc && lastCc >= 0) return                                             // powtórzony pakiet
        lastCc = cc
        var i = payload
        if (pusi) {
            val pointer = p[i].toInt() and 0xff
            i++
            if (active) feed(p, i, minOf(end, i + pointer))   // dokończenie poprzedniej sekcji
            i += pointer
            if (i >= end) { active = false; return }
            active = true
            len = 0
        } else if (!active) {
            return
        }
        feed(p, i, end)
    }

    private fun feed(p: ByteArray, from: Int, to: Int) {
        var i = from
        while (active && i < to) {
            val n = minOf(to - i, buf.size - len)
            if (n <= 0) { active = false; len = 0; return }
            System.arraycopy(p, i, buf, len, n)
            len += n
            i += n
            while (active) {
                if (len >= 1 && buf[0] == 0xff.toByte()) { active = false; len = 0; return }   // wypełnienie do końca pakietu
                if (len < 3) break
                val need = 3 + (((buf[1].toInt() and 0x0f) shl 8) or (buf[2].toInt() and 0xff))
                if (need > 4096 + 3 || need < 8) { active = false; len = 0; return }
                if (len < need) break
                onSection(buf, need)
                System.arraycopy(buf, need, buf, 0, len - need)
                len -= need
                if (len == 0) break
            }
        }
    }
}

object Eit {
    const val PID = 0x12
    const val TABLE_PF_ACTUAL = 0x4E

    fun crc32(b: ByteArray, off: Int, len: Int): Int {
        var crc = -1
        for (k in off until off + len) {
            crc = crc xor ((b[k].toInt() and 0xff) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
        }
        return crc
    }

    /** Sekcja EIT p/f dla programu [sid]: (numer sekcji 0 = teraz / 1 = następne, wersja, audycja albo null). */
    data class PfSection(val number: Int, val version: Int, val event: EitEvent?)

    fun parsePf(b: ByteArray, len: Int, sid: Int): PfSection? {
        if (len < 18) return null
        if ((b[0].toInt() and 0xff) != TABLE_PF_ACTUAL) return null
        val service = ((b[3].toInt() and 0xff) shl 8) or (b[4].toInt() and 0xff)
        if (sid >= 0 && service != sid) return null
        if ((b[5].toInt() and 1) == 0) return null                 // current_next_indicator
        if (crc32(b, 0, len) != 0) return null                     // CRC całej sekcji (z CRC) = 0
        val version = (b[5].toInt() shr 1) and 0x1f
        val number = b[6].toInt() and 0xff
        var k = 14
        val last = len - 4
        var event: EitEvent? = null
        if (k + 12 <= last) {
            val id = ((b[k].toInt() and 0xff) shl 8) or (b[k + 1].toInt() and 0xff)
            val begin = mjdUtc(b, k + 2)
            val dur = bcd(b[k + 7]) * 3600L + bcd(b[k + 8]) * 60L + bcd(b[k + 9])
            val dlen = ((b[k + 10].toInt() and 0x0f) shl 8) or (b[k + 11].toInt() and 0xff)
            k += 12
            val dend = minOf(last, k + dlen)
            var title = ""
            var short = ""
            val ext = StringBuilder()
            while (k + 2 <= dend) {
                val tag = b[k].toInt() and 0xff
                val l = b[k + 1].toInt() and 0xff
                val d = k + 2
                if (d + l > dend) break
                when (tag) {
                    0x4D -> if (l >= 5) {                          // short_event_descriptor
                        val nl = b[d + 3].toInt() and 0xff
                        if (d + 4 + nl < d + l + 1) {
                            title = DvbText.decode(b, d + 4, minOf(nl, l - 4))
                            val tp = d + 4 + nl
                            if (tp < d + l) {
                                val tl = b[tp].toInt() and 0xff
                                short = DvbText.decode(b, tp + 1, minOf(tl, d + l - tp - 1))
                            }
                        }
                    }
                    0x4E -> if (l >= 6) {                          // extended_event_descriptor
                        val il = b[d + 4].toInt() and 0xff
                        val tp = d + 5 + il
                        if (tp < d + l) {
                            val tl = b[tp].toInt() and 0xff
                            ext.append(DvbText.decode(b, tp + 1, minOf(tl, d + l - tp - 1)))
                        }
                    }
                }
                k = d + l
            }
            if (begin > 0 && dur > 0 && title.isNotBlank()) event = EitEvent(id, begin, dur, title.trim(), short.trim(), ext.toString().trim())
        }
        return PfSection(number, version, event)
    }

    private fun bcd(x: Byte): Int { val v = x.toInt() and 0xff; return (v shr 4) * 10 + (v and 0x0f) }

    /** start_time: MJD (16 bit) + godzina BCD (24 bit), UTC -> epoch s; 0 = brak (same 0xFF). */
    fun mjdUtc(b: ByteArray, i: Int): Long {
        val mjd = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
        if (mjd == 0xffff || mjd == 0) return 0
        return (mjd - 40587L) * 86400L + bcd(b[i + 2]) * 3600L + bcd(b[i + 3]) * 60L + bcd(b[i + 4])
    }
}

/** Tekst DVB (EN 300 468, załącznik A): pierwszy bajt wybiera kodowanie, domyślnie ISO 6937. */
object DvbText {
    fun decode(b: ByteArray, off: Int, len: Int): String {
        if (len <= 0 || off < 0 || off + len > b.size) return ""
        var start = off
        val first = b[off].toInt() and 0xff
        val charset: String? = when {
            first >= 0x20 -> null
            first in 0x01..0x0B -> { start = off + 1; "ISO-8859-${first + 4}" }
            first == 0x10 && len >= 3 -> { start = off + 3; "ISO-8859-${b[off + 2].toInt() and 0xff}" }
            first == 0x11 -> { start = off + 1; "UTF-16BE" }
            first == 0x15 -> { start = off + 1; "UTF-8" }
            else -> { start = off + 1; null }
        }
        val n = off + len - start
        if (n <= 0) return ""
        val raw = if (charset == null) iso6937(b, start, n) else try {
            String(b, start, n, Charset.forName(charset))
        } catch (_: Exception) {
            String(b, start, n, Charsets.ISO_8859_1)
        }
        return clean(raw)
    }

    /** Kody sterujące DVB: 0x8A = nowa linia, 0x86/0x87 (wyróżnienie) i pozostałe 0x80–0x9F usuwane. */
    private fun clean(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) {
            val v = c.code
            when {
                v == 0x8A || v == 0xE08A -> out.append('\n')
                v in 0x80..0x9F || v in 0xE080..0xE09F -> {}
                v < 0x20 && c != '\n' -> {}
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    // ISO 6937: 0xC1–0xCF to znaki diakrytyczne stojące PRZED literą
    private val combining = mapOf(
        0xC1 to '̀', 0xC2 to '́', 0xC3 to '̂', 0xC4 to '̃', 0xC5 to '̄',
        0xC6 to '̆', 0xC7 to '̇', 0xC8 to '̈', 0xCA to '̊', 0xCB to '̧',
        0xCD to '̋', 0xCE to '̨', 0xCF to '̌',
    )
    private val single = mapOf(
        0xA1 to '¡', 0xA2 to '¢', 0xA3 to '£', 0xA5 to '¥', 0xA7 to '§', 0xA8 to '¤', 0xAB to '«',
        0xB0 to '°', 0xB1 to '±', 0xB2 to '²', 0xB3 to '³', 0xB4 to '×', 0xB5 to 'µ', 0xB6 to '¶', 0xB7 to '·',
        0xB8 to '÷', 0xBB to '»', 0xBC to '¼', 0xBD to '½', 0xBE to '¾', 0xBF to '¿',
        0xD0 to '―', 0xD1 to '¹', 0xD2 to '®', 0xD3 to '©', 0xD4 to '™', 0xD5 to '♪', 0xD6 to '¬', 0xD7 to '¦',
        0xDC to '⅛', 0xDD to '⅜', 0xDE to '⅝', 0xDF to '⅞',
        0xE0 to 'Ω', 0xE1 to 'Æ', 0xE2 to 'Đ', 0xE3 to 'ª', 0xE4 to 'Ħ', 0xE6 to 'Ĳ', 0xE7 to 'Ŀ', 0xE8 to 'Ł',
        0xE9 to 'Ø', 0xEA to 'Œ', 0xEB to 'º', 0xEC to 'Þ', 0xED to 'Ŧ', 0xEE to 'Ŋ', 0xEF to 'ŉ',
        0xF0 to 'ĸ', 0xF1 to 'æ', 0xF2 to 'đ', 0xF3 to 'ð', 0xF4 to 'ħ', 0xF5 to 'ı', 0xF6 to 'ĳ', 0xF7 to 'ŀ',
        0xF8 to 'ł', 0xF9 to 'ø', 0xFA to 'œ', 0xFB to 'ß', 0xFC to 'þ', 0xFD to 'ŧ', 0xFE to 'ŋ', 0xA0 to ' ',
    )

    fun iso6937(b: ByteArray, off: Int, len: Int): String {
        val out = StringBuilder(len)
        var i = off
        val end = off + len
        while (i < end) {
            val v = b[i].toInt() and 0xff
            val mark = combining[v]
            if (mark != null) {
                if (i + 1 < end) {
                    val base = (b[i + 1].toInt() and 0xff).toChar()
                    out.append(Normalizer.normalize("$base$mark", Normalizer.Form.NFC))
                    i += 2
                    continue
                }
                i++
                continue
            }
            out.append(
                when {
                    v < 0x80 -> v.toChar()
                    v in 0x80..0x9F -> v.toChar()   // kody sterujące - usuwane w clean()
                    else -> single[v] ?: ' '
                }
            )
            i++
        }
        return out.toString()
    }
}

/** Łączenie EPG z danymi EIT „teraz/następne” (ogólne — testowalne bez Androida). */
object EitMerge {
    /** Audycje [base] nachodzące na którąś z [eit] (albo o tym samym id) są zastępowane audycjami z EIT. */
    fun <T> merge(base: List<T>, eit: List<T>, begin: (T) -> Long, end: (T) -> Long, id: (T) -> Int): List<T> {
        if (eit.isEmpty()) return base
        val ids = eit.map(id).filter { it != 0 }.toSet()
        val kept = base.filter { b ->
            id(b) !in ids && eit.none { e -> begin(b) < end(e) && end(b) > begin(e) }
        }
        return (kept + eit).sortedBy(begin)
    }

    /**
     * Różnice EIT względem EPG: (dotychczasowa audycja w tym czasie albo null, audycja z EIT) — tylko gdy tytuł
     * jest inny, początek przesunięty o ponad minutę albo koniec o ponad 2 minuty.
     */
    fun <T> changes(base: List<T>, eit: List<T>, begin: (T) -> Long, end: (T) -> Long, title: (T) -> String): List<Pair<T?, T>> =
        eit.mapNotNull { e ->
            val old = base.filter { b -> begin(b) < end(e) && end(b) > begin(e) }
                .maxByOrNull { b -> minOf(end(b), end(e)) - maxOf(begin(b), begin(e)) }
            val same = old != null && title(old).trim().equals(title(e).trim(), ignoreCase = true) &&
                kotlin.math.abs(begin(old) - begin(e)) <= 60 && kotlin.math.abs(end(old) - end(e)) <= 120
            if (same) null else old to e
        }
}
