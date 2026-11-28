package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCloseDeadlineTest {
    @Test
    fun noRecordingUsesIdleDeadline() {
        assertEquals(10L, AutoCloseDeadline.at(10L, emptyList(), 5L, 100L))
    }

    @Test
    fun programRecordingDefersShutdownUntilItsEnd() {
        val recording = AutoCloseRecording(AutoCloseRecordingKind.PROGRAM, endsWall = 115L)

        assertEquals(20L, AutoCloseDeadline.at(10L, listOf(recording), 5L, 100L))
    }

    @Test
    fun bufferRecordingDoesNotDeferShutdown() {
        val recording = AutoCloseRecording(AutoCloseRecordingKind.BUFFER, endsWall = 115L)

        assertEquals(10L, AutoCloseDeadline.at(10L, listOf(recording), 5L, 100L))
    }

    @Test
    fun programRecordingEndingBeforeIdleDeadlineDoesNotExtendTimer() {
        val recording = AutoCloseRecording(AutoCloseRecordingKind.PROGRAM, endsWall = 102L)

        assertEquals(10L, AutoCloseDeadline.at(10L, listOf(recording), 5L, 100L))
    }

    @Test
    fun timedShutdownCountsIndependentlyAndWaitsForRecording() {
        val timer = TimedShutdown()
        assertEquals((15..180 step 15).toList(), TimedShutdown.intervals)
        timer.schedule(1_000L, 15)
        assertFalse(timer.due(900_999L, false))
        assertFalse(timer.due(901_000L, true))
        assertTrue(timer.due(901_001L, false))
        timer.schedule(2_000L, 180)
        assertFalse(timer.due(901_001L, false))
        timer.cancel()
        assertFalse(timer.due(Long.MAX_VALUE, false))
    }
}
