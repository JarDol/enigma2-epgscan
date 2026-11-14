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
}
