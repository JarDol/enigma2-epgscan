package pl.jardol.e2stream.desktop

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties
import org.json.JSONObject

/**
 * Poprawki strumienia TS z Enigmy2 przed VLC i buforem timeshiftu (jak TsAnalyzer w E2Stream na Androidzie):
 *  - zapamiętuje PAT i PMT oglądanego programu (do doklejenia na początek odczytu z bufora — [psiHeader]),
 *  - gdy dekoder po PAT nie przysyła PMT, po 0,3 s wstawia zapamiętany PMT kanału (albo zbudowany z PID-ów z lamedb),
 *  - PCR_PID z PMT programu (zegar nagrania),
 *  - nowe połączenie: dane do pierwszego pakietu z PCR programu są pomijane (Enigma2 zaczyna strumień
 *    paczką >100 kB bez PAT/PMT i zegara), po 1 MB wystarcza dowolny PCR, po 8 MB zapis bez czekania.
 */
internal class TsStreamFixer(
    private val sid: Int,
    /** Zapamiętana sekcja PMT kanału (też z Androida przez synchronizację) albo zbudowana z PID-ów. */
    private val cachedPmt: () -> ByteArray?,
    /** Nowa sekcja PMT kanału ze strumienia (do zapamiętania). */
    private val onPmt: (ByteArray) -> Unit = {},
    /** Pomijanie początku strumienia do pierwszego PCR (bufor timeshiftu). */
    private val waitForPcr: Boolean = true,
    private val clock: () -> Long = ::monotonicMs,
    private val log: (String) -> Unit = AppLog::i,
) {
    private val carry = ByteArray(PACKET)
    private var carryLength = 0
    private val out = ByteArrayOutputStream(PACKET * 720)

    @Volatile private var lastPat: ByteArray? = null
    @Volatile private var lastPmt: ByteArray? = null
    @Volatile private var injection: ByteArray? = null

    /** PID PMT programu z PAT; -1 — nieznany. */
    @Volatile var pmtPid = -1
        private set
    /** PID zegara programu (z PMT ze strumienia albo zapamiętanego); -1 — nieznany. */
    @Volatile var pcrPid = -1
        private set
    @Volatile var pmtSeen = false
        private set
    @Volatile var pmtInjected = 0
        private set

    private var patSeenAt = 0L
    private var lastInjectAt = 0L
    private var injectCc = 0
    private val pidSeen = BooleanArray(8192)
    private var cached: ByteArray? = null
    private var cachedLoaded = false

    private val section = ByteArray(4096)
    private var sectionLength = 0
    private var sectionPackets = ByteArrayOutputStream()

    private var waitPcr = waitForPcr
    private var noPcrSince: Long? = null
    private var programPids = emptySet<Int>()
    private var skipped = 0L
    private val skippedPids = HashMap<Int, Int>()

    /** Ostatnie PAT i PMT programu jako pakiety TS — na początek odczytu z bufora (odtwarzacz od razu zna ścieżki). */
    fun psiHeader(): ByteArray? {
        val pat = lastPat ?: return null
        val pmt = lastPmt ?: injection ?: return null
        return pat + pmt
    }

    /** Nowe połączenie z dekoderem: niepełny pakiet porzucony, zapis znów od pierwszego PCR. */
    @Synchronized
    fun newConnection() {
        carryLength = 0
        waitPcr = waitForPcr
        noPcrSince = null
        skipped = 0
        skippedPids.clear()
        patSeenAt = 0
        sectionLength = 0
    }

    /** Przetwarza porcję strumienia; do [sink] trafiają tylko całe pakiety TS (z ewentualnie wstawionym PMT). */
    @Synchronized
    fun feed(bytes: ByteArray, offset: Int, length: Int, sink: (ByteArray, Int, Int) -> Unit) {
        out.reset()
        var position = offset
        val end = offset + length
        if (carryLength > 0) {
            val take = minOf(PACKET - carryLength, end - position)
            System.arraycopy(bytes, position, carry, carryLength, take)
            carryLength += take
            position += take
            if (carryLength == PACKET) {
                carryLength = 0
                if (carry[0] == SYNC) packet(carry, 0)
            }
        }
        while (position < end) {
            if (bytes[position] != SYNC || (position + PACKET < end && bytes[position + PACKET] != SYNC)) {
                position++
                continue
            }
            if (end - position < PACKET) {
                System.arraycopy(bytes, position, carry, 0, end - position)
                carryLength = end - position
                break
            }
            packet(bytes, position)
            position += PACKET
        }
        if (out.size() > 0) sink(out.toByteArray(), 0, out.size())
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xff

    private fun packet(p: ByteArray, o: Int) {
        val b1 = u(p, o + 1)
        val pid = ((b1 and 0x1f) shl 8) or u(p, o + 2)
        val tei = (b1 and 0x80) != 0
        val pusi = (b1 and 0x40) != 0
        if (!tei) {
            pidSeen[pid] = true
            val afc = (u(p, o + 3) shr 4) and 3
            var payload = o + 4
            if ((afc and 2) != 0) payload = o + 5 + u(p, o + 4)
            if ((afc and 1) != 0 && payload < o + PACKET) {
                if (pid == 0 && pusi) pat(p, o, payload)
                else if (pid == pmtPid && pmtPid > 0) pmtPacket(p, o, payload, pusi)
            }
        }
        maybeInject()
        emit(p, o)
    }

    private fun pat(p: ByteArray, o: Int, payload: Int) {
        val start = payload + 1 + u(p, payload)
        if (start + 8 >= o + PACKET || u(p, start) != 0x00) return
        val sectionLength = ((u(p, start + 1) and 0x0f) shl 8) or u(p, start + 2)
        val end = minOf(start + 3 + sectionLength - 4, o + PACKET)
        var i = start + 8
        var found = -1
        while (i + 4 <= end) {
            val program = (u(p, i) shl 8) or u(p, i + 1)
            val pid = ((u(p, i + 2) and 0x1f) shl 8) or u(p, i + 3)
            if (program != 0 && (program == sid || (sid <= 0 && found < 0))) found = pid
            i += 4
        }
        if (found <= 0) return
        lastPat = p.copyOfRange(o, o + PACKET)
        if (pmtPid != found) {
            pmtPid = found
            injection = null
        }
        if (patSeenAt == 0L) patSeenAt = clock()
    }

    private fun pmtPacket(p: ByteArray, o: Int, payload: Int, pusi: Boolean) {
        if (pusi) {
            val start = payload + 1 + u(p, payload)
            if (start >= o + PACKET) return
            sectionPackets = ByteArrayOutputStream()
            sectionLength = o + PACKET - start
            System.arraycopy(p, start, section, 0, sectionLength)
        } else {
            if (sectionLength == 0) return
            val take = minOf(o + PACKET - payload, section.size - sectionLength)
            if (take <= 0) {
                sectionLength = 0
                return
            }
            System.arraycopy(p, payload, section, sectionLength, take)
            sectionLength += take
        }
        sectionPackets.write(p, o, PACKET)
        if (sectionLength < 3) return
        val need = 3 + (((u(section, 1) and 0x0f) shl 8) or u(section, 2))
        if (need > section.size) {
            sectionLength = 0
            return
        }
        if (sectionLength < need) return
        sectionLength = 0
        if (u(section, 0) != 0x02 || need < 16) return
        val program = (u(section, 3) shl 8) or u(section, 4)
        if (sid > 0 && program != sid) return
        pcrPid = ((u(section, 8) and 0x1f) shl 8) or u(section, 9)
        programPids = streamPids(section.copyOf(need))
        lastPmt = sectionPackets.toByteArray()
        if (!pmtSeen) {
            pmtSeen = true
            if (need in 16..1024) {
                val copy = section.copyOf(need)
                if (!copy.contentEquals(cached)) runCatching { onPmt(copy) }
            }
        }
    }

    /** Gdy po PAT przez 300 ms nie ma PMT — zapamiętany PMT kanału, co 300 ms, aż przyjdzie prawdziwy. */
    private fun maybeInject() {
        if (pmtSeen || patSeenAt == 0L || pmtPid <= 0) return
        val now = clock()
        if (now - patSeenAt < INJECT_AFTER_MS || now - lastInjectAt < INJECT_AFTER_MS) return
        if (!cachedLoaded) {
            cachedLoaded = true
            cached = runCatching { cachedPmt() }.getOrNull()
        }
        val sectionBytes = cached ?: return
        if (pcrPid < 0 && sectionBytes.size >= 12 && u(sectionBytes, 0) == 0x02) {
            pcrPid = ((u(sectionBytes, 8) and 0x1f) shl 8) or u(sectionBytes, 9)
            programPids = streamPids(sectionBytes)
        }
        val packets = injection ?: buildInjection(sectionBytes)?.also { injection = it } ?: return
        var i = 0
        while (i < packets.size) {
            packets[i + 3] = ((packets[i + 3].toInt() and 0xf0) or (injectCc and 0x0f)).toByte()
            injectCc = (injectCc + 1) and 0x0f
            i += PACKET
        }
        emit(packets, 0, packets.size)
        lastInjectAt = now
        pmtInjected++
        if (pmtInjected == 1 || pmtInjected % 20 == 0) {
            log("TS: brak PMT od dekodera ${now - patSeenAt} ms po PAT — wstawiam zapamiętany PMT (×$pmtInjected)")
        }
    }

    private fun streamPids(section: ByteArray): Set<Int> {
        if (section.size < 16) return emptySet()
        val end = minOf(3 + (((u(section, 1) and 0x0f) shl 8) or u(section, 2)) - 4, section.size - 4)
        var index = 12 + (((u(section, 10) and 0x0f) shl 8) or u(section, 11))
        val pids = mutableSetOf<Int>()
        while (index + 5 <= end) {
            val length = 5 + (((u(section, index + 3) and 0x0f) shl 8) or u(section, index + 4))
            if (index + length > end) break
            pids += ((u(section, index + 1) and 0x1f) shl 8) or u(section, index + 2)
            index += length
        }
        return pids
    }

    /** Pakiety PMT z zapamiętanej sekcji; zostają tylko ścieżki, które dekoder faktycznie przysyła. */
    private fun buildInjection(cachedSection: ByteArray): ByteArray? {
        val sec = cachedSection
        if (sec.size < 16 || u(sec, 0) != 0x02 || pmtPid <= 0) return null
        val sectionLength = ((u(sec, 1) and 0x0f) shl 8) or u(sec, 2)
        val end = minOf(3 + sectionLength - 4, sec.size - 4).coerceAtLeast(0)
        val infoLength = ((u(sec, 10) and 0x0f) shl 8) or u(sec, 11)
        if (12 + infoLength > end) return null
        val streams = ByteArrayOutputStream()
        var k = 12 + infoLength
        var kept = 0
        val missing = ArrayList<Int>()
        while (k + 5 <= end) {
            val pid = ((u(sec, k + 1) and 0x1f) shl 8) or u(sec, k + 2)
            val len = 5 + (((u(sec, k + 3) and 0x0f) shl 8) or u(sec, k + 4))
            if (k + len > end) break
            if (pidSeen[pid]) {
                streams.write(sec, k, len)
                kept++
            } else {
                missing += pid
            }
            k += len
        }
        val body = sec.copyOfRange(0, 12 + infoLength) + (if (kept > 0) streams.toByteArray() else sec.copyOfRange(12 + infoLength, end))
        val newLength = body.size - 3 + 4
        body[1] = ((u(body, 1) and 0xf0) or ((newLength shr 8) and 0x0f)).toByte()
        body[2] = (newLength and 0xff).toByte()
        val crc = crc32(body)
        val full = body + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
        log("TS: PMT do wstawienia: ścieżek $kept" + if (missing.isNotEmpty()) ", pominięte (brak w strumieniu): ${missing.joinToString(",")}" else "")
        return packetize(full, pmtPid)
    }

    private fun emit(p: ByteArray, o: Int, length: Int = PACKET) {
        var i = o
        while (i < o + length) {
            if (admit(p, i)) out.write(p, i, PACKET)
            i += PACKET
        }
    }

    /** Czy pakiet ma trafić dalej (false — początek strumienia przed pierwszym PCR programu). */
    private fun admit(p: ByteArray, o: Int): Boolean {
        if (!waitPcr) return true
        val b1 = u(p, o + 1)
        val pid = ((b1 and 0x1f) shl 8) or u(p, o + 2)
        val hasPcr = (b1 and 0x80) == 0 && (u(p, o + 3) and 0x20) != 0 && u(p, o + 4) >= 7 && (u(p, o + 5) and 0x10) != 0
        val want = pcrPid
        val noPcrInPmt = want == 0x1fff
        val now = clock()
        if (noPcrInPmt && noPcrSince == null) noPcrSince = now
        if (!noPcrInPmt) noPcrSince = null
        val pcr = hasPcr && when {
            noPcrInPmt -> pid in programPids
            want >= 0 -> pid == want
            else -> skipped >= ANY_PCR_AFTER
        }
        val timedOut = noPcrInPmt && now - (noPcrSince ?: now) >= NO_PCR_WAIT_MS
        if (pcr || timedOut || skipped >= SKIP_MAX) {
            waitPcr = false
            if (skipped > 0) {
                val pids = skippedPids.entries.sortedByDescending { it.value }.take(5).joinToString(", ") { "${it.key}×${it.value}" }
                log(
                    "timeshift: zapis od pierwszego pakietu z zegarem PCR (pid $want) — pominięto ${skipped / 1000} kB na początku strumienia (PID: $pids)" +
                        if (!pcr) " — PCR nie znaleziony, zapis bez czekania" else "",
                )
            }
            skipped = 0
            noPcrSince = null
            skippedPids.clear()
            return true
        }
        skipped += PACKET
        skippedPids[pid] = (skippedPids[pid] ?: 0) + 1
        return false
    }

    companion object {
        const val PACKET = 188
        private const val SYNC = 0x47.toByte()
        const val INJECT_AFTER_MS = 300L
        const val ANY_PCR_AFTER = 1_000_000L
        const val NO_PCR_WAIT_MS = 2_000L
        const val SKIP_MAX = 8_000_000L

        /** CRC32 MPEG-2 (sekcje PSI). */
        fun crc32(b: ByteArray): Int {
            var crc = -1
            for (x in b) {
                crc = crc xor (x.toInt() shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
            }
            return crc
        }

        /** Sekcja PSI w pakiety TS na [pid] (pierwszy z PUSI i pointer_field, reszta wypełniona 0xFF). */
        fun packetize(sectionBytes: ByteArray, pid: Int): ByteArray {
            val result = ByteArrayOutputStream()
            var position = 0
            var first = true
            while (first || position < sectionBytes.size) {
                val packet = ByteArray(PACKET) { 0xff.toByte() }
                packet[0] = SYNC
                packet[1] = ((if (first) 0x40 else 0) or ((pid shr 8) and 0x1f)).toByte()
                packet[2] = (pid and 0xff).toByte()
                packet[3] = 0x10
                var write = 4
                if (first) {
                    packet[4] = 0
                    write = 5
                }
                val count = minOf(PACKET - write, sectionBytes.size - position)
                System.arraycopy(sectionBytes, position, packet, write, count)
                position += count
                first = false
                result.write(packet)
            }
            return result.toByteArray()
        }
    }
}

/**
 * Sekcja PMT z PID-ów, które EPG Scan podaje z lamedb (gdy kanał nie ma jeszcze zapamiętanego PMT) — jak PmtBuilder
 * na Androidzie. CRC liczone przy wstawianiu.
 */
internal object PmtBuilder {
    fun build(sid: Int, json: String?): ByteArray? {
        if (json.isNullOrBlank() || sid <= 0) return null
        return runCatching {
            val o = JSONObject(json)
            val es = ByteArrayOutputStream()
            fun entry(type: Int, pid: Int, descriptor: ByteArray = ByteArray(0)) {
                es.write(type)
                es.write(0xe0 or ((pid shr 8) and 0x1f)); es.write(pid and 0xff)
                es.write(0xf0 or ((descriptor.size shr 8) and 0x0f)); es.write(descriptor.size and 0xff)
                es.write(descriptor)
            }
            val video = o.optInt("video", -1)
            if (video > 0) {
                val type = when (o.optInt("vtype", 0)) {
                    0 -> 0x02; 1 -> 0x1b; 3 -> 0x10; 4 -> 0xea; 6 -> 0x01; 7 -> 0x24; else -> 0x1b
                }
                entry(type, video)
            }
            val audio = o.optJSONArray("audio")
            if (audio != null) for (i in 0 until audio.length()) {
                val a = audio.optJSONObject(i) ?: continue
                val pid = a.optInt("pid", -1)
                if (pid <= 0) continue
                when (a.optString("type")) {
                    "mpeg" -> entry(0x03, pid)
                    "ac3" -> entry(0x06, pid, byteArrayOf(0x6a, 1, 0))
                    "eac3" -> entry(0x06, pid, byteArrayOf(0x7a, 1, 0))
                    "aac" -> entry(0x0f, pid)
                    "latm" -> entry(0x11, pid)
                }
            }
            val streams = es.toByteArray()
            if (streams.isEmpty()) return null
            val pcr = o.optInt("pcr", if (video > 0) video else 0x1fff)
            val length = 9 + streams.size + 4
            val sec = ByteArrayOutputStream()
            sec.write(0x02)
            sec.write(0xb0 or ((length shr 8) and 0x0f)); sec.write(length and 0xff)
            sec.write((sid shr 8) and 0xff); sec.write(sid and 0xff)
            sec.write(0xc1)
            sec.write(0); sec.write(0)
            sec.write(0xe0 or ((pcr shr 8) and 0x1f)); sec.write(pcr and 0xff)
            sec.write(0xf0); sec.write(0)
            sec.write(streams)
            sec.write(byteArrayOf(0, 0, 0, 0))
            sec.toByteArray()
        }.getOrNull()
    }
}

/**
 * Zapamiętane PMT kanałów (sekcje, Base64) i PID-y z lamedb — %APPDATA%\E2Stream\pmts.properties.
 * Te same klucze co na Androidzie („pmts_<SID>”), więc PMT z telewizorów trafiają tu przez synchronizację i odwrotnie.
 */
internal object PmtStore {
    private val lock = Any()
    private var props: Properties? = null
    private var file: Path = DesktopConfig.appDataDir().resolve("pmts.properties")

    internal fun useFile(path: Path) = synchronized(lock) {
        file = path
        props = null
    }

    private fun load(): Properties {
        props?.let { return it }
        val loaded = Properties()
        runCatching { Files.newInputStream(file).use { loaded.load(it) } }
        props = loaded
        return loaded
    }

    private fun save(p: Properties) {
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.newOutputStream(tmp).use { p.store(it, "E2Stream: PMT kanałów") }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** Sekcja PMT kanału; gdy brak — zbudowana z PID-ów lamedb (EPG Scan). */
    fun pmtOrBuilt(sid: Int): ByteArray? = pmt(sid) ?: PmtBuilder.build(sid, pids(sid))

    fun pmt(sid: Int): ByteArray? = synchronized(lock) {
        load().getProperty("pmts_$sid")?.let { runCatching { Base64.getMimeDecoder().decode(it) }.getOrNull() }
    }

    /** Zapis PMT ze strumienia (czas — do synchronizacji „nowszy wygrywa”); false, gdy bez zmian. */
    fun putPmt(sid: Int, section: ByteArray, time: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val p = load()
        val value = Base64.getEncoder().encodeToString(section)
        if (p.getProperty("pmts_$sid") == value) return false
        p.setProperty("pmts_$sid", value)
        p.setProperty("t_pmts_$sid", time.toString())
        p.setProperty("dirty_pmts_$sid", "1")
        save(p)
        true
    }

    fun pids(sid: Int): String? = synchronized(lock) { load().getProperty("pids_$sid") }

    fun putPids(entries: Map<Int, String>) = synchronized(lock) {
        val p = load()
        var changed = false
        for ((sid, json) in entries) {
            if (p.getProperty("pids_$sid") != json) {
                p.setProperty("pids_$sid", json)
                changed = true
            }
        }
        if (changed) save(p)
    }

    /**
     * Wspólne PMT z dekodera (część „common” synchronizacji): nowszy wpis wygrywa. Zwraca liczbę przyjętych.
     */
    fun mergeCommon(data: JSONObject?): Int = synchronized(lock) {
        if (data == null) return 0
        val p = load()
        var taken = 0
        for (key in data.keys()) {
            if (!key.startsWith("pmts_")) continue
            val entry = data.optJSONObject(key) ?: continue
            val value = entry.opt("v") as? String ?: continue
            val time = entry.optLong("t")
            val localTime = p.getProperty("t_$key")?.toLongOrNull() ?: 0L
            if (p.getProperty(key) == value) continue
            if (p.getProperty(key) == null || time > localTime) {
                p.setProperty(key, value)
                p.setProperty("t_$key", time.toString())
                p.remove("dirty_$key")
                taken++
            }
        }
        if (taken > 0) save(p)
        taken
    }

    /** PMT poznane tutaj, jeszcze nie wysłane do części wspólnej na dekoderze. */
    fun pendingCommon(): JSONObject = synchronized(lock) {
        val p = load()
        val result = JSONObject()
        for (name in p.stringPropertyNames()) {
            if (!name.startsWith("dirty_pmts_")) continue
            val key = name.removePrefix("dirty_")
            val value = p.getProperty(key) ?: continue
            result.put(key, JSONObject().put("v", value).put("t", p.getProperty("t_$key")?.toLongOrNull() ?: 0L))
        }
        result
    }

    fun markSent(keys: Collection<String>) = synchronized(lock) {
        val p = load()
        keys.forEach { p.remove("dirty_$it") }
        if (keys.isNotEmpty()) save(p)
    }
}
