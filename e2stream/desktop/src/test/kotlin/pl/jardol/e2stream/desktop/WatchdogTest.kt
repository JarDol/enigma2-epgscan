package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchdogTest {
    @Test
    fun startupUsesLongLimitThenStallLimit() {
        val watch = StallWatch(stallMs = 3000, startupMs = 15_000)
        watch.reset(0)
        assertFalse(watch.check(1000, -1, false))
        assertFalse(watch.check(10_000, -1, false), "start: 15 s")
        assertTrue(watch.check(16_500, -1, false))
        watch.reset(20_000)
        assertFalse(watch.check(21_000, 0, false))
        assertFalse(watch.check(22_000, 500, false))
        assertFalse(watch.check(26_000, 500, false), "3 s + 2 s zapasu")
        assertTrue(watch.check(27_100, 500, false))
    }

    @Test
    fun pauseIsNotAStall() {
        val watch = StallWatch(stallMs = 3000)
        watch.reset(0)
        watch.check(1000, 100, false)
        watch.check(2000, 200, false)
        assertFalse(watch.check(60_000, 200, true))
        assertFalse(watch.check(61_000, 200, false))
    }

    @Test
    fun backoffGrowsAndResetsAfterHealthyPlayback() {
        val backoff = RestartBackoff(healthyMs = 30_000)
        assertEquals(listOf(0L, 1000L, 2000L, 5000L, 10_000L, 10_000L), List(6) { backoff.nextDelay() })
        backoff.playing(100_000)
        backoff.playing(131_000)
        assertEquals(0, backoff.streak)
    }
}
