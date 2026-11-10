package pl.jardol.e2stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleBackExitTest {
    @Test
    fun requiresTwoPressesWithinInterval() {
        val back = DoubleBackExit()
        assertFalse(back.press(100))
        assertTrue(back.press(2599))
    }

    @Test
    fun expiredOrResetPressDoesNotExit() {
        val back = DoubleBackExit()
        assertFalse(back.press(100))
        assertFalse(back.press(2600))
        back.reset()
        assertFalse(back.press(2700))
        assertTrue(back.press(2800))
    }

    @Test
    fun timeshiftBackRespectsVisibleBannerAndLiveSetting() {
        assertEquals(TimeshiftBackAction.SHOW_CHANNEL_BANNER, timeshiftBackAction(true, false, true, true, true))
        assertEquals(TimeshiftBackAction.HIDE_BANNER, timeshiftBackAction(true, true, true, true, true))
        assertEquals(TimeshiftBackAction.GO_LIVE, timeshiftBackAction(false, false, true, true, true))
        assertEquals(TimeshiftBackAction.EXIT, timeshiftBackAction(false, false, true, true, false))
        assertEquals(TimeshiftBackAction.EXIT, timeshiftBackAction(false, false, true, false, true))
        assertEquals(TimeshiftBackAction.EXIT, timeshiftBackAction(false, false, false, false, true))
    }
}
