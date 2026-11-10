package pl.jardol.e2stream

import org.junit.Assert.assertFalse
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
}
