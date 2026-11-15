package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class RecScanTest {

    private fun pcrPacket(pid: Int, ms: Long): ByteArray {
        val p = ByteArray(188) { 0xff.toByte() }
        p[0] = 0x47; p[1] = (pid shr 8).toByte(); p[2] = pid.toByte(); p[3] = 0x20
        p[4] = 183.toByte(); p[5] = 0x10
        val t = ms * 90
        p[6] = (t shr 25).toByte(); p[7] = (t shr 17).toByte(); p[8] = (t shr 9).toByte(); p[9] = (t shr 1).toByte()
        p[10] = ((t and 1) shl 7).toByte()
        return p
    }

    private fun dataPacket(pid: Int) = ByteArray(188).also { it[0] = 0x47; it[1] = (pid shr 8).toByte(); it[2] = pid.toByte(); it[3] = 0x10 }

    private fun stream(vararg pcr: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (ms in pcr) { out.write(pcrPacket(534, ms)); out.write(dataPacket(534)); out.write(dataPacket(634)) }
        return out.toByteArray()
    }

    private fun part(b: ByteArray) = Pair({ ByteArrayInputStream(b) as java.io.InputStream }, b.size.toLong())

    @Test
    fun continuousClock() {
        val r = RecScan.scanStreams(listOf(part(stream(1000, 1040, 1080)), part(stream(1120, 1160))))
        assertEquals(534, r.pcrPid)
        assertEquals(5L, r.pcrCount)
        assertEquals(0, r.jumpCount)
        assertEquals(160L, r.continuousMs)
        assertEquals(160L, r.playerMs)
        assertTrue(RecScan.describe(r, 0).contains("zegar ciągły"))
    }

    @Test
    fun backwardJumpMakesUnseekable() {
        // na początku kilka pakietów „z przyszłości” (jak w nagraniu TVP): pierwszy PCR późniejszy niż ostatni
        val r = RecScan.scanStreams(listOf(part(stream(60_000, 10_000, 10_040, 10_080)), part(stream(10_120, 50_000))))
        assertEquals(2, r.jumpCount)
        assertEquals(-50_000L, r.jumps[0].deltaMs)
        assertEquals(1, r.jumps[0].part)
        assertEquals(564L, r.jumps[0].offset)
        assertTrue(r.playerMs < 0)
        val d = RecScan.describe(r, 48)
        assertTrue(d, d.contains("NIEPRZEWIJALNE"))
        assertTrue(d, d.contains("kawałek 1"))
    }

    @Test
    fun resyncAfterGarbageAndSplitPackets() {
        val s = stream(1000, 1040)
        val g = byteArrayOf(1, 2, 3) + s
        val r = RecScan.scanStreams(listOf(part(g)))
        assertTrue(r.badStart)
        assertEquals(2L, r.pcrCount)
        assertEquals(0, r.jumpCount)
    }
}
