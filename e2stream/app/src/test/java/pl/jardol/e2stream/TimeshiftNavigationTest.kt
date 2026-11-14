package pl.jardol.e2stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeshiftNavigationTest {
    @Test
    fun arrowsSeekOnlyOnVisibleTimeshiftBanner() {
        assertTrue(timeshiftNavigationOnBanner(visible = true, channelBanner = false))
        assertFalse(timeshiftNavigationOnBanner(visible = true, channelBanner = true))
        assertFalse(timeshiftNavigationOnBanner(visible = false, channelBanner = false))
        assertFalse(timeshiftNavigationOnBanner(visible = false, channelBanner = true))
    }

    @Test
    fun repeatedRewindStopsAtPlayableBeginningAndCanMoveForward() {
        val now = 200_000L
        val oldest = 100_000L
        var target = 160_000L
        repeat(10) { target = timeshiftSeekTarget(target, -30_000, oldest, now) }
        assertEquals(110_000L, target)
        assertEquals(140_000L, timeshiftSeekTarget(target, 30_000, oldest, now))
    }

    @Test
    fun startMovesWithBufferAndNeverExceedsCurrentTime() {
        assertEquals(130_000L, timeshiftSeekTarget(110_000, -10_000, 120_000, 200_000))
        assertEquals(205_000L, timeshiftSeekTarget(200_000, -30_000, 200_000, 205_000))
    }

    @Test
    fun progressUsesPlayableWindowInsteadOfPhysicalBuffer() {
        val oldest = 100_000L
        val now = 143_000L
        assertEquals(33_000L, timeshiftAvailableSpan(oldest, now))
        assertEquals(0, timeshiftBufferProgress(110_000, oldest, now))
        assertEquals(500, timeshiftBufferProgress(126_500, oldest, now))
        assertEquals(1000, timeshiftBufferProgress(now, oldest, now))
        assertEquals(0, timeshiftBufferProgress(oldest, oldest, now))
        assertEquals(1000, timeshiftBufferProgress(now + 1000, oldest, now))
    }

    @Test
    fun progressHandlesEmptyShortAndMovingBuffers() {
        assertEquals(0L, timeshiftAvailableSpan(100_000, 105_000))
        assertEquals(1000, timeshiftBufferProgress(100_000, 100_000, 105_000))
        assertEquals(0L, timeshiftAvailableSpan(105_000, 105_000))
        assertEquals(1000, timeshiftBufferProgress(105_000, 105_000, 105_000))
        assertEquals(0, timeshiftBufferProgress(110_000, 120_000, 200_000))
        assertEquals(500, timeshiftBufferProgress(165_000, 120_000, 200_000))
    }
}
