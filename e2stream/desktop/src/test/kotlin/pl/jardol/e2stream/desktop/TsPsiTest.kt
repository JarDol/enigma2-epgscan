package pl.jardol.e2stream.desktop

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TsPsiTest {
    private val sid = 0x10
    private val pmtPid = 0x100
    private val videoPid = 0x101
    private val audioPid = 0x102

    private fun withCrc(body: ByteArray): ByteArray {
        val crc = TsStreamFixer.crc32(body)
        return body + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    private fun patSection(): ByteArray {
        val body = byteArrayOf(0x00, 0xb0.toByte(), 13, 0, 1, 0xc1.toByte(), 0, 0,
            0, sid.toByte(), (0xe0 or (pmtPid shr 8)).toByte(), (pmtPid and 0xff).toByte())
        return withCrc(body)
    }

    private fun pmtSection(): ByteArray = withCrc(
        byteArrayOf(0x02, 0xb0.toByte(), 23, 0, sid.toByte(), 0xc1.toByte(), 0, 0,
            (0xe0 or (videoPid shr 8)).toByte(), (videoPid and 0xff).toByte(), 0xf0.toByte(), 0,
            0x1b, (0xe0 or (videoPid shr 8)).toByte(), (videoPid and 0xff).toByte(), 0xf0.toByte(), 0,
            0x03, (0xe0 or (audioPid shr 8)).toByte(), (audioPid and 0xff).toByte(), 0xf0.toByte(), 0),
    )

    private fun es(pid: Int, pcr: Boolean = false): ByteArray {
        val p = ByteArray(188) { 0xaa.toByte() }
        p[0] = 0x47
        p[1] = ((pid shr 8) and 0x1f).toByte()
        p[2] = (pid and 0xff).toByte()
        if (pcr) {
            p[3] = 0x30
            p[4] = 7
            p[5] = 0x10
        } else {
            p[3] = 0x10
        }
        return p
    }

    private fun feedAll(fixer: TsStreamFixer, vararg packets: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val input = packets.fold(ByteArray(0)) { a, b -> a + b }
        // w dwóch nierównych kawałkach — sprawdza sklejanie pakietów
        val cut = 100
        fixer.feed(input, 0, cut) { b, o, l -> out.write(b, o, l) }
        fixer.feed(input, cut, input.size - cut) { b, o, l -> out.write(b, o, l) }
        return out.toByteArray()
    }

    private fun pids(stream: ByteArray): List<Int> = (stream.indices step 188).map {
        ((stream[it + 1].toInt() and 0x1f) shl 8) or (stream[it + 2].toInt() and 0xff)
    }

    @Test
    fun learnsPmtAndStartsFromProgramPcr() {
        var learned: ByteArray? = null
        val fixer = TsStreamFixer(sid, { null }, { learned = it }, waitForPcr = true, log = {})
        val pat = TsStreamFixer.packetize(patSection(), 0)
        val pmt = TsStreamFixer.packetize(pmtSection(), pmtPid)
        val out = feedAll(fixer, es(audioPid), pat, pmt, es(0x200, pcr = true), es(videoPid), es(videoPid, pcr = true), es(audioPid))
        assertEquals(videoPid, fixer.pcrPid)
        assertTrue(fixer.pmtSeen)
        assertContentEquals(pmtSection(), learned)
        // zapis od pakietu z PCR programu (PCR innego PID-u się nie liczy)
        assertEquals(listOf(videoPid, audioPid), pids(out))
        val header = assertNotNull(fixer.psiHeader())
        assertEquals(listOf(0, pmtPid), pids(header))
    }

    @Test
    fun injectsCachedPmtWhenDecoderSendsNone() {
        var now = 1_000L
        val logs = mutableListOf<String>()
        val fixer = TsStreamFixer(sid, { pmtSection() }, {}, waitForPcr = false, clock = { now }, log = { logs += it })
        val pat = TsStreamFixer.packetize(patSection(), 0)
        var out = feedAll(fixer, pat, es(videoPid), es(audioPid))
        assertFalse(pmtPid in pids(out))
        now += 350
        out = feedAll(fixer, es(videoPid, pcr = true))
        assertEquals(listOf(pmtPid, videoPid), pids(out))
        assertEquals(1, fixer.pmtInjected)
        assertEquals(videoPid, fixer.pcrPid)
        assertTrue(logs.any { it.contains("wstawiam zapamiętany PMT") })
        assertEquals(listOf(0, pmtPid), pids(assertNotNull(fixer.psiHeader())))
        // kolejne wstawienie dopiero po 300 ms
        now += 100
        out = feedAll(fixer, es(videoPid))
        assertEquals(listOf(videoPid), pids(out))
    }

    @Test
    fun injectedPmtKeepsOnlyStreamsPresent() {
        var now = 1_000L
        val fixer = TsStreamFixer(sid, { pmtSection() }, {}, waitForPcr = false, clock = { now }, log = {})
        feedAll(fixer, TsStreamFixer.packetize(patSection(), 0), es(videoPid))
        now = 1_400
        val out = feedAll(fixer, es(videoPid))
        val pmt = out.copyOfRange(0, 188)
        // sekcja: 12 B nagłówka + jedna ścieżka (5 B) + CRC
        val length = ((pmt[6].toInt() and 0x0f) shl 8) or (pmt[7].toInt() and 0xff)
        assertEquals(9 + 5 + 4, length)
        val section = pmt.copyOfRange(5, 5 + 3 + length)
        assertEquals(0, TsStreamFixer.crc32(section))
    }

    @Test
    fun withoutPmtAnyPcrAfterOneMegabyte() {
        val fixer = TsStreamFixer(sid, { null }, {}, waitForPcr = true, log = {})
        val out = ByteArrayOutputStream()
        val junk = es(audioPid)
        repeat((TsStreamFixer.ANY_PCR_AFTER / 188).toInt() + 2) { fixer.feed(junk, 0, 188) { b, o, l -> out.write(b, o, l) } }
        assertEquals(0, out.size())
        fixer.feed(es(0x300, pcr = true), 0, 188) { b, o, l -> out.write(b, o, l) }
        assertEquals(188, out.size())
    }

    @Test
    fun pmtBuiltFromLamedbPids() {
        val json = JSONObject().put("video", videoPid).put("vtype", 1)
            .put("audio", org.json.JSONArray().put(JSONObject().put("pid", audioPid).put("type", "mpeg"))).toString()
        val section = assertNotNull(PmtBuilder.build(sid, json))
        assertEquals(0x02, section[0].toInt())
        assertEquals(sid, ((section[3].toInt() and 0xff) shl 8) or (section[4].toInt() and 0xff))
        assertNull(PmtBuilder.build(sid, "{}"))
    }

    @Test
    fun storeMergesCommonNewerWinsAndTracksPending() {
        val dir = Files.createTempDirectory("pmts")
        PmtStore.useFile(dir.resolve("pmts.properties"))
        try {
            assertTrue(PmtStore.putPmt(sid, pmtSection(), time = 100))
            assertFalse(PmtStore.putPmt(sid, pmtSection(), time = 200))
            assertEquals(setOf("pmts_$sid"), PmtStore.pendingCommon().keySet())
            PmtStore.markSent(listOf("pmts_$sid"))
            assertEquals(0, PmtStore.pendingCommon().length())
            val other = byteArrayOf(2, 0, 0)
            val common = JSONObject()
                .put("pmts_$sid", JSONObject().put("v", java.util.Base64.getEncoder().encodeToString(other)).put("t", 50))
                .put("pmts_77", JSONObject().put("v", java.util.Base64.getEncoder().encodeToString(other)).put("t", 50))
            assertEquals(1, PmtStore.mergeCommon(common))
            assertContentEquals(pmtSection(), PmtStore.pmt(sid))
            assertContentEquals(other, PmtStore.pmt(77))
        } finally {
            PmtStore.useFile(DesktopConfig.appDataDir().resolve("pmts.properties"))
        }
    }
}
