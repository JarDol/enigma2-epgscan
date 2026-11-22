package pl.jardol.e2stream.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeshiftTest {
    private fun packets(count: Int, first: Int = 0): ByteArray = ByteArray(count * TimeshiftBuffer.PACKET).also { bytes ->
        for (index in 0 until count) {
            bytes[index * TimeshiftBuffer.PACKET] = 0x47
            bytes[index * TimeshiftBuffer.PACKET + 1] = (first + index).toByte()
        }
    }

    @Test
    fun dropsLeadingGarbageAndKeepsWholePacketsAcrossWrites() {
        val dir = Files.createTempDirectory("e2stream-ts-test").toFile()
        var now = 1_000_000L
        val buffer = TimeshiftBuffer(dir, 30, clock = { now }, reserveBytes = 0)
        try {
            val data = byteArrayOf(1, 2, 3) + packets(4)
            buffer.write(data, 0, 100)
            buffer.write(data, 100, data.size - 100)
            assertEquals(4L * TimeshiftBuffer.PACKET, buffer.total)

            val read = ByteArray(TimeshiftBuffer.PACKET)
            assertEquals(TimeshiftBuffer.PACKET, buffer.read(TimeshiftBuffer.PACKET.toLong(), read, 0, read.size))
            assertEquals(0x47.toByte(), read[0])
            assertEquals(1.toByte(), read[1])
        } finally {
            buffer.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun mapsTimeToPacketAlignedOffsetsAcrossMinuteSegments() {
        val dir = Files.createTempDirectory("e2stream-ts-test").toFile()
        var now = 0L
        val buffer = TimeshiftBuffer(dir, 30, clock = { now }, reserveBytes = 0)
        try {
            buffer.write(packets(2000), 0, 2000 * TimeshiftBuffer.PACKET)
            now = 60_000
            buffer.write(packets(2000, 2000), 0, 2000 * TimeshiftBuffer.PACKET)
            now = 120_000
            assertEquals(1_000L, buffer.seekMarginMs())

            assertEquals(0L, buffer.offsetAt(-5_000))
            assertEquals(2000L * TimeshiftBuffer.PACKET, buffer.offsetAt(60_000))
            val middle = buffer.offsetAt(30_000)
            assertEquals(0L, middle % TimeshiftBuffer.PACKET)
            assertEquals(1000L * TimeshiftBuffer.PACKET, middle)
            assertEquals(30_000L, buffer.wallAt(1000L * TimeshiftBuffer.PACKET))
            assertEquals(120_000L, buffer.spanMs())
        } finally {
            buffer.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun dropsOldestMinuteBeyondWindow() {
        val dir = Files.createTempDirectory("e2stream-ts-test").toFile()
        var now = 0L
        val buffer = TimeshiftBuffer(dir, 1, clock = { now }, reserveBytes = 0)
        try {
            repeat(4) { minute ->
                now = minute * 60_000L
                buffer.write(packets(10), 0, 10 * TimeshiftBuffer.PACKET)
            }
            assertTrue(buffer.trimmed)
            assertEquals(10_000L, buffer.seekMarginMs())
            assertEquals(120_000L, buffer.oldestWall)
            assertEquals(-1, buffer.read(0, ByteArray(TimeshiftBuffer.PACKET), 0, TimeshiftBuffer.PACKET))
        } finally {
            buffer.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun resolvesSeekTargets() {
        assertEquals(TimeshiftTarget.Live, timeshiftTarget(target = 98_000, now = 100_000, oldestWall = 0))
        assertEquals(TimeshiftTarget.At(50_000, false), timeshiftTarget(target = 50_000, now = 100_000, oldestWall = 0))
        assertEquals(TimeshiftTarget.At(30_000, true), timeshiftTarget(target = 10_000, now = 100_000, oldestWall = 20_000))
        assertEquals(TimeshiftTarget.At(21_000, true), timeshiftTarget(target = 10_000, now = 100_000, oldestWall = 20_000, marginMs = 1_000))
        assertEquals(TimeshiftTarget.Live, timeshiftTarget(target = 0, now = 25_000, oldestWall = 20_000))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }

    @Test
    fun savesBufferFromChosenMomentToFile() {
        val dir = Files.createTempDirectory("e2stream-ts-test").toFile()
        var now = 0L
        val buffer = TimeshiftBuffer(dir, 30, clock = { now }, reserveBytes = 0)
        try {
            buffer.write(packets(2000), 0, 2000 * TimeshiftBuffer.PACKET)
            now = 60_000
            buffer.write(packets(2000, 2000), 0, 2000 * TimeshiftBuffer.PACKET)
            now = 120_000
            val all = java.io.File(dir, "all.ts")
            assertEquals(4000L * TimeshiftBuffer.PACKET, buffer.saveTo(all, -1))
            assertEquals(4000L * TimeshiftBuffer.PACKET, all.length())
            val part = java.io.File(dir, "part.ts")
            assertEquals(2000L * TimeshiftBuffer.PACKET, buffer.saveTo(part, 60_000))
            assertEquals(0x47.toByte(), part.readBytes()[0])
        } finally {
            buffer.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun recordingNameIsSafeForWindows() {
        val name = TimeshiftBuffer.recordingName("TVP 1 HD", "Piłka nożna: Rumunia / Polska?", java.time.LocalDateTime.of(2026, 11, 14, 21, 5))
        assertEquals("TVP 1 HD – Piłka nożna_ Rumunia _ Polska_ – 2026-11-14 21.05.ts", name)
    }
}
