package pl.jardol.e2stream

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LocalRecordingPlaybackTest {
    @Test fun readerHonorsRangesAcrossPartsAndReopensAtAnEarlierPosition() {
        val dir = File(System.getProperty("java.io.tmpdir"), "rec-reader-${System.nanoTime()}").also { it.mkdirs() }
        val data = ByteArray(90_000) { (it % 239).toByte() }
        File(dir, "part-00000.ts").writeBytes(data.copyOfRange(0, 376))
        File(dir, "part-00001.ts").writeBytes(data.copyOfRange(376, 30_000))
        File(dir, "part-00002.ts").writeBytes(data.copyOfRange(30_000, data.size))
        val source = LocalRecordingDataSource(dir)
        try {
            for ((position, length) in listOf(29_963L to 150L, 80_000L to 10_000L, 0L to 90_000L)) {
                assertEquals(length, source.open(DataSpec.Builder().setUri(Uri.fromFile(dir)).setPosition(position).setLength(length).build()))
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(997)
                while (true) {
                    val n = source.read(chunk, 0, chunk.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    assertTrue(n > 0)
                    output.write(chunk, 0, n)
                }
                assertArrayEquals(data.copyOfRange(position.toInt(), (position + length).toInt()), output.toByteArray())
                source.close()
            }
        } finally {
            source.close()
            dir.deleteRecursively()
        }
    }

    @Test fun onlyActuallyActiveRecordingsAreHiddenIncludingFinalization() {
        val base = File(System.getProperty("java.io.tmpdir"), "rec-list-${System.nanoTime()}").also { it.mkdirs() }
        val active = File(base, "active").also { it.mkdirs() }
        val interrupted = File(base, "interrupted").also { it.mkdirs() }
        for (dir in listOf(active, interrupted)) {
            File(dir, "part-00000.ts").writeBytes(ByteArray(376))
            LocalRecordings.writeMeta(dir, JSONObject().put("done", false))
        }
        try {
            assertEquals(listOf(LocalRecordings.PREFIX + interrupted.absolutePath), LocalRecordings.list(base, setOf(active)).map { it.path })
            assertEquals(2, LocalRecordings.list(base).size)
            assertEquals(2, LocalRecordings.list(base, emptySet()).size)
        } finally { base.deleteRecursively() }
    }

    private fun packet(cc: Int) = ByteArray(188) { 0xff.toByte() }.also {
        it[0] = 0x47; it[1] = 1; it[2] = 0; it[3] = (0x10 or cc).toByte()
    }

    @Test fun seekingDropsPartialPacketsAndContinuityButStillDetectsRealErrors() {
        val analyzer = TsAnalyzer(0) {}.also { it.clean = false; it.reset(1) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        val upstream = ByteArrayDataSource(packet(0) + packet(1) + byteArrayOf(0x12, 0x34) + packet(9) + packet(10))
        val source = AnalyzingDataSource(upstream, seekable = true) { analyzer }
        val bytes = ByteArray(376)
        source.open(DataSpec(Uri.parse("file:///recording")))
        assertEquals(200, source.read(bytes, 0, 200))
        source.close()
        source.open(DataSpec.Builder().setUri("file:///recording").setPosition(376).build())
        val output = ByteArray(378)
        assertEquals(378, source.read(output, 0, output.size))
        assertArrayEquals(byteArrayOf(0x12, 0x34) + packet(9) + packet(10), output)
        assertEquals(0L, analyzer.ccErrors)
        assertEquals(0L, analyzer.syncLoss)
        val bad = packet(14)
        analyzer.feed(bad, 0, bad.size)
        assertTrue(analyzer.ccErrors > 0)
        source.close()
    }
}
