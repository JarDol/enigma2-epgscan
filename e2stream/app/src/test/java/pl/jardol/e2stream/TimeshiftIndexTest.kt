package pl.jardol.e2stream

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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

    @Test
    fun shrinkingBufferMovesExpiredSegmentsIntoActiveRecording() {
        val b = newBuffer()
        val segsField = TimeshiftBuffer::class.java.getDeclaredField("segs").apply { isAccessible = true }
        repeat(5) {
            b.write(pcr, 0, pcr.size)
            val seg = (segsField.get(b) as List<*>).last()!!
            seg.javaClass.getDeclaredField("wall").apply {
                isAccessible = true
                setLong(seg, android.os.SystemClock.elapsedRealtime() - 60_001)
            }
        }
        val recordingDir = File(System.getProperty("java.io.tmpdir"), "tsrec-resize-" + System.nanoTime())
        val pin = b.startPin(recordingDir, 0)!!
        b.maxMin = 1
        b.write(pcr, 0, pcr.size)

        assertEquals(4, pin.parts)
        assertEquals(2, b.dir.listFiles()!!.count { it.extension == "ts" })
        assertEquals(4, recordingDir.listFiles()!!.count { it.name.startsWith("part-") && it.extension == "ts" })
        b.close()
    }

    @Test
    fun writeBatchFlushesAfterBoundedWaitAndOnClose() {
        val b = newBuffer()
        b.writeBatchBytes = 256 * 1024
        b.write(pcr, 0, pcr.size)
        assertEquals(0L, b.total)

        Thread.sleep(TimeshiftBuffer.WRITE_BATCH_MAX_WAIT_MS + 250)
        assertEquals(188L, b.total)
        assertEquals(188L, File(b.dir, b.dir.listFiles()!!.single { it.extension == "ts" }.name).length())

        b.write(pcr, 0, pcr.size)
        assertEquals(188L, b.total)
        b.close()
        assertEquals(376L, b.total)
        assertEquals(376L, b.dir.listFiles()!!.single { it.extension == "ts" }.length())
    }

    @Test
    fun fullWriteBatchFlushesImmediatelyAndDisabledBatchWritesImmediately() {
        val batched = newBuffer().also { it.writeBatchBytes = 256 * 1024 }
        val packetCount = 256 * 1024 / 188
        val data = ByteArray(packetCount * 188)
        repeat(packetCount) { System.arraycopy(pcr, 0, data, it * 188, 188) }
        batched.write(data, 0, data.size)
        assertEquals(data.size.toLong(), batched.total)
        assertEquals(data.size.toLong(), batched.dir.listFiles()!!.single { it.extension == "ts" }.length())
        batched.close()

        val direct = newBuffer()
        direct.write(pcr, 0, pcr.size)
        assertEquals(188L, direct.total)
        direct.close()
    }

    @Test
    fun rotationFlushesPendingBatchBeforeOpeningNextSegment() {
        val b = newBuffer().also { it.writeBatchBytes = 256 * 1024 }
        b.write(pcr, 0, pcr.size)
        val segsField = TimeshiftBuffer::class.java.getDeclaredField("segs").apply { isAccessible = true }
        val seg = (segsField.get(b) as List<*>).last()!!
        seg.javaClass.getDeclaredField("wall").apply {
            isAccessible = true
            setLong(seg, android.os.SystemClock.elapsedRealtime() - 60_001)
        }

        b.write(pcr, 0, pcr.size)
        assertEquals(188L, b.total)
        assertEquals(2, b.dir.listFiles()!!.count { it.extension == "ts" })
        b.close()
        assertEquals(376L, b.total)
    }

    @Test
    fun pinnedRecordingIncludesPartialBatchesAtStartAndFinish() {
        val b = newBuffer().also { it.writeBatchBytes = 256 * 1024 }
        b.write(pcr, 0, pcr.size)
        val recordingDir = File(System.getProperty("java.io.tmpdir"), "tsrec-batch-" + System.nanoTime())
        val pin = b.startPin(recordingDir, 0)!!
        assertEquals(188L, b.total)

        b.write(pcr, 0, pcr.size)
        assertEquals(376L, b.pinnedBytes())
        b.write(pcr, 0, pcr.size)
        val finished = b.finishPin()!!
        assertSame(pin, finished)
        assertEquals(564L, pin.bytes)
        assertEquals(564L, File(recordingDir, "part-00001.ts").length())
        b.close()
    }

    @Test
    fun failedBatchWriteDisablesBufferWithoutPublishingUnwrittenBytes() {
        val b = newBuffer().also { it.writeBatchBytes = 256 * 1024 }
        b.write(pcr, 0, pcr.size)
        val outField = TimeshiftBuffer::class.java.getDeclaredField("out").apply { isAccessible = true }
        (outField.get(b) as FileOutputStream).close()
        outField.set(b, object : FileOutputStream(File(b.dir, "failing.ts")) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("injected write failure")
            }
        })

        Thread.sleep(TimeshiftBuffer.WRITE_BATCH_MAX_WAIT_MS + 250)
        assertTrue(b.failed!!.contains("injected write failure"))
        assertEquals(0L, b.total)
        b.close()
    }
}
