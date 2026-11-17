package pl.jardol.e2stream

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TimeshiftIndexTest {
    private fun pkt(vararg head: Int): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        head.forEachIndexed { i, v -> p[i] = v.toByte() }
        return p
    }

    private val pcr = pkt(0x47, 0x41, 0x00, 0x30, 7, 0x10, 0, 0, 0, 0, 0, 0)
    private val pat = pkt(0x47, 0x40, 0x00, 0x10, 0, 0x00, 0xB0, 0x0D, 0, 1, 0xC1, 0, 0, 0, 1, 0xF0, 0x00)
    private val pmt = pkt(0x47, 0x50, 0x00, 0x10, 0, 0x02, 0xB0, 0x0D, 0, 1, 0xC1, 0, 0, 0xE1, 0x00, 0xF0, 0x00)
    private val key = pkt(0x47, 0x41, 0x00, 0x10, 0, 0, 1, 0xE0, 0, 0, 0x80, 0, 0, 0, 0, 1, 0x65)

    private fun newBuffer(): TimeshiftBuffer {
        val dir = File(System.getProperty("java.io.tmpdir"), "tsidx-" + System.nanoTime()).also { it.mkdirs() }
        return TimeshiftBuffer(dir, 360).also { it.pcrPid = { 0x100 } }
    }

    @Test
    fun keyframeIndexIsNotLimited() {
        val b = newBuffer()
        val all = java.io.ByteArrayOutputStream()
        all.write(pcr)
        repeat(6000) { all.write(key) }
        b.write(all.toByteArray(), 0, all.size())
        assertEquals(6000, b.recentKeyframes(10_000, ramOnly = false).size)
        assertEquals(188L * 6000, b.keyframeAt(Long.MAX_VALUE)!!.first)
    }

    @Test
    fun recordingStartsWithPatAndPmt() {
        val b = newBuffer()
        val all = java.io.ByteArrayOutputStream()
        listOf(pcr, pat, pmt, key, key).forEach { all.write(it) }
        b.write(all.toByteArray(), 0, all.size())
        val dir = File(System.getProperty("java.io.tmpdir"), "tsrec-" + System.nanoTime())
        b.startPin(dir, 0)
        val head = File(dir, "part-00000.ts")
        assertEquals(376L, head.length())
        assertEquals(0x100, RecScan.pmtPcrPid(head.readBytes()))
    }
}
