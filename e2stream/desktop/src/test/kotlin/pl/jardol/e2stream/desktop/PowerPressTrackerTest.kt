package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerPressTrackerTest {
    @Test
    fun repeatedReportsOnlyTriggerOnceUntilRelease() {
        val tracker = PowerPressTracker()
        assertTrue(tracker.update(1L, true))
        assertFalse(tracker.update(1L, true))
        assertTrue(tracker.update(2L, true))
        assertFalse(tracker.update(1L, false))
        assertTrue(tracker.update(1L, true))
        tracker.remove(2L)
        assertTrue(tracker.update(2L, true))
    }
}
