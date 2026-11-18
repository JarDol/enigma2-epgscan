package pl.jardol.e2stream

import org.junit.Assert.assertEquals
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
}
