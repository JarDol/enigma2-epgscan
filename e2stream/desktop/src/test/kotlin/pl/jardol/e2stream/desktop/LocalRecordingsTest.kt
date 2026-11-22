package pl.jardol.e2stream.desktop

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class LocalRecordingsTest {
    private fun packets(count: Int): ByteArray = ByteArray(count * TimeshiftBuffer.PACKET).also { bytes ->
        for (index in 0 until count) bytes[index * TimeshiftBuffer.PACKET] = 0x47
    }

    @Test
    fun failedOrIncompleteCopyNeverPublishesPartialRecordingSegment() {
        val root = Files.createTempDirectory("e2stream-rec-copy").toFile()
        try {
            val source = File(root, "source.ts").apply { writeBytes(packets(4)) }
            val target = File(root, "00000.ts")
            assertFailsWith<java.io.IOException> {
                copyRecordingSegment(source, target) { _, staged ->
                    staged.writeBytes(byteArrayOf(1, 2))
                    throw java.io.IOException("disk full")
                }
            }
            assertFalse(target.exists())
            assertTrue(source.exists())
            assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
            assertFailsWith<java.io.IOException> {
                copyRecordingSegment(source, target) { _, staged -> staged.writeBytes(byteArrayOf(1)) }
            }
            assertFalse(target.exists())
            copyRecordingSegment(source, target)
            assertTrue(source.readBytes().contentEquals(target.readBytes()))
            assertFailsWith<java.io.IOException> { copyRecordingSegment(source, target) }
            assertTrue(source.readBytes().contentEquals(target.readBytes()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun backgroundRecordingSurvivesCapacityEvictionAndFinishesOnClose() {
        val root = Files.createTempDirectory("e2stream-background-rec").toFile()
        var now = 1_000L
        val first = TimeshiftBuffer(File(root, "timeshift"), 2, clock = { now }, reserveBytes = 0)
        val second = TimeshiftBuffer(File(root, "timeshift"), 2, clock = { now }, reserveBytes = 0)
        try {
            val chunk = packets(100)
            first.write(chunk, 0, chunk.size)
            second.write(chunk, 0, chunk.size)
            val recordingDir = File(File(root, "nagrania"), "rec")
            var finished: TimeshiftBuffer.RecordingResult? = null
            assertTrue(first.startRecording(recordingDir, 0, Long.MAX_VALUE, 1_000_000) { finished = it })
            val background = BackgroundBuffers<TimeshiftBuffer> { it.isRecording }
            assertTrue(background.park("first", first, 1).isEmpty())
            assertEquals(listOf(second), background.park("second", second, 1))
            second.close()
            assertEquals(first, background.take("first"))
            now += 60_000
            first.close()
            assertEquals(1, finished?.files)
            assertEquals(chunk.size.toLong(), LocalRecordings.parts(recordingDir).single().length())
        } finally {
            first.close()
            second.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun simultaneousRecordingDirectoriesDoNotCollide() {
        val config = DesktopConfig()
        val whenStarted = java.time.LocalDateTime.of(2026, 11, 22, 21, 0)
        val first = LocalRecordings.newDir(config, "Kanał", whenStarted)
        val second = LocalRecordings.newDir(config, "Kanał", whenStarted)
        assertTrue(first != second)
    }

    @Test
    fun recordingCannotExceedItsReservedBudget() {
        val root = Files.createTempDirectory("e2stream-rec-budget").toFile()
        var now = 1_000L
        val buffer = TimeshiftBuffer(File(root, "timeshift"), 2, clock = { now }, reserveBytes = 0)
        try {
            val chunk = packets(100)
            buffer.write(chunk, 0, chunk.size)
            val dir = File(root, "recording")
            assertFalse(buffer.startRecording(dir, 0, Long.MAX_VALUE, chunk.size.toLong() - 1) {})
            var finished: TimeshiftBuffer.RecordingResult? = null
            assertTrue(buffer.startRecording(dir, 0, Long.MAX_VALUE, chunk.size.toLong() * 2) { finished = it })
            now += 1_000
            buffer.write(chunk, 0, chunk.size)
            buffer.write(chunk, 0, chunk.size)
            assertFalse(buffer.isRecording)
            assertEquals(2L * chunk.size, finished?.bytes)
            assertEquals(2L * chunk.size, LocalRecordings.parts(dir).sumOf { it.length() })
        } finally {
            buffer.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun recordingReservationsDoNotOvercommitDiskSpace() {
        assertEquals(1_000L, LocalRecordings.recordingBudget(3_000, emptyList(), 3))
        assertEquals(1_000L, LocalRecordings.recordingBudget(3_000, listOf(1_000, 1_000), 3))
        assertEquals(0L, LocalRecordings.recordingBudget(2_000, listOf(1_500, 1_500), 3))
        assertEquals(0L, LocalRecordings.recordingBudget(4_000, listOf(1, 1, 1), 3))
    }

    @Test
    fun lowDiskEndsRecordingWithoutDiscardingBufferedSegments() {
        val root = Files.createTempDirectory("e2stream-rec-low-space").toFile()
        var now = 1_000L
        var free = 2_000L
        val buffer = TimeshiftBuffer(File(root, "timeshift"), 2, clock = { now }, reserveBytes = 1_000, freeBytes = { free })
        try {
            val chunk = packets(100)
            buffer.write(chunk, 0, chunk.size)
            val dir = File(root, "recording")
            var finished: TimeshiftBuffer.RecordingResult? = null
            assertTrue(buffer.startRecording(dir, 0, Long.MAX_VALUE, 100_000) { finished = it })
            now += 1_000
            free = 999
            buffer.write(chunk, 0, chunk.size)
            assertNotNull(buffer.failed)
            assertEquals(chunk.size.toLong(), finished?.bytes)
            assertEquals(chunk.size.toLong(), LocalRecordings.parts(dir).single().length())
        } finally {
            buffer.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun failedRecordingMovePreservesSourceAcrossCleanup() {
        val root = Files.createTempDirectory("e2stream-rec-recovery").toFile()
        var now = 1_000L
        val timeshiftDir = File(root, "timeshift")
        val buffer = TimeshiftBuffer(timeshiftDir, 2, clock = { now }, reserveBytes = 0)
        try {
            val chunk = packets(100)
            buffer.write(chunk, 0, chunk.size)
            val dir = File(root, "recording")
            var result: TimeshiftBuffer.RecordingResult? = null
            assertTrue(buffer.startRecording(dir, 0, Long.MAX_VALUE, 100_000) { result = it })
            val blocked = File(dir, "00000.ts")
            assertTrue(blocked.mkdirs())
            File(blocked, "occupied").writeText("x")
            now += 1_000
            buffer.stopRecording("test")
            val preserved = File(assertNotNull(result).failedPaths.single())
            assertTrue(preserved.exists())
            assertTrue(File(preserved.path + ".keep").exists())
            buffer.close()
            val stale = File(timeshiftDir, "ts-stale.ts").apply { writeBytes(chunk) }
            TimeshiftBuffer.cleanupAsync(timeshiftDir)
            val deadline = System.currentTimeMillis() + 5_000
            while (stale.exists() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertFalse(stale.exists())
            assertTrue(preserved.exists())
        } finally {
            buffer.close()
            root.deleteRecursively()
        }
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
