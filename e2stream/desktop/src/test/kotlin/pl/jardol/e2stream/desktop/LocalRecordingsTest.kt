package pl.jardol.e2stream.desktop

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalRecordingsTest {
    private fun packets(count: Int): ByteArray = ByteArray(count * TimeshiftBuffer.PACKET).also { bytes ->
        for (index in 0 until count) bytes[index * TimeshiftBuffer.PACKET] = 0x47
    }

    @Test
    fun recordingMovesSegmentsAndBufferStartsAgain() {
        val root = Files.createTempDirectory("e2stream-rec-test").toFile()
        var now = 0L
        val buffer = TimeshiftBuffer(File(root, "timeshift"), maxMin = 2, clock = { now }, reserveBytes = 0)
        try {
            val chunk = packets(100)
            buffer.write(chunk, 0, chunk.size)
            now = 60_000
            buffer.write(chunk, 0, chunk.size)
            val dir = File(root, "nagrania/rec1")
            var result: TimeshiftBuffer.RecordingResult? = null
            val done = java.util.concurrent.CountDownLatch(1)
            assertTrue(buffer.startRecording(dir, fromWall = 0, untilWall = Long.MAX_VALUE, maxBytes = Long.MAX_VALUE) {
                result = it
                done.countDown()
            })
            assertTrue(buffer.isRecording)
            // dalsze minuty: najstarsze kawałki wypadają z okna 2 min — trafiają do nagrania
            for (minute in 2..4) {
                now = minute * 60_000L
                buffer.write(chunk, 0, chunk.size)
            }
            assertTrue(LocalRecordings.parts(dir).isNotEmpty(), "kawałki przeniesione przy wypadaniu z okna")
            now = 250_000
            buffer.stopRecording("test")
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(buffer.isRecording)
            val parts = LocalRecordings.parts(dir)
            assertEquals(5, parts.size)
            assertEquals(5L * chunk.size, parts.sumOf { it.length() })
            assertEquals(5, result!!.files)
            // bufor od nowa: pozycje rosną dalej, odczyt na żywo czeka na nowe dane
            val total = buffer.total
            assertEquals(total, buffer.offsetAt(now))
            buffer.write(chunk, 0, chunk.size)
            assertEquals(total + chunk.size, buffer.total)
            assertEquals(total, buffer.oldestOffset())
        } finally {
            buffer.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun listsAndExportsLocalRecordings() {
        val root = Files.createTempDirectory("e2stream-rec-list").toFile()
        val config = DesktopConfig(tsDir = root.path)
        val dir = File(LocalRecordings.root(config), "2026-11-14 21.00.00 TVP 1 HD")
        dir.mkdirs()
        File(dir, "00000.ts").writeBytes(packets(10))
        File(dir, "00001.ts").writeBytes(packets(5))
        LocalRecordings.writeInfo(
            dir,
            JSONObject().put("title", "Mecz").put("channel", "TVP 1 HD").put("begin", 1_000_000L).put("durationMs", 120_000L),
        )
        try {
            val list = LocalRecordings.list(config)
            assertEquals(1, list.size)
            assertEquals("Mecz", list[0].title)
            assertEquals(120L, list[0].lengthSec)
            assertEquals(15L * TimeshiftBuffer.PACKET, list[0].size)
            val file = LocalRecordings.export(config, list[0]) {}
            assertEquals(15L * TimeshiftBuffer.PACKET, file.length())
            assertNotNull(LocalRecordings.dirOf(config, list[0]))
            assertTrue(LocalRecordings.delete(config, list[0]))
            assertTrue(LocalRecordings.list(config).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rangeHeaderIsParsed() {
        assertEquals(123L, LocalRecordingServer.parseRangeStart("bytes=123-"))
        assertEquals(0L, LocalRecordingServer.parseRangeStart("bytes=0-499"))
        assertEquals(null, LocalRecordingServer.parseRangeStart(null))
    }
}
