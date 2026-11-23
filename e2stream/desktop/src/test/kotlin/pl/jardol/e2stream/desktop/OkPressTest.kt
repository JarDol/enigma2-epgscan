package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OkPressTest {
    @Test
    fun shortPressActsOnRelease() {
        val ok = OkPress(longMs = 600)
        assertTrue(ok.down(1000))
        assertFalse(ok.longDue(1300))
        assertTrue(ok.up())
        assertFalse(ok.isDown)
    }

    @Test
    fun longPressFiresOnceAndSuppressesRelease() {
        val ok = OkPress(longMs = 600)
        assertTrue(ok.down(1000))
        assertFalse(ok.down(1100), "powtórzenie klawisza")
        assertTrue(ok.longDue(1600))
        assertFalse(ok.longDue(1700))
        assertFalse(ok.up())
    }

    @Test
    fun releaseWithoutPressDoesNothing() {
        assertFalse(OkPress().up())
    }

    @Test
    fun stuckKeyIsResetAfterMaxHold() {
        val ok = OkPress(longMs = 600, maxHoldMs = 5000)
        assertTrue(ok.down(1000))
        assertTrue(ok.longDue(1700))
        assertTrue(ok.down(7000), "brak puszczenia - nowe naciśnięcie po czasie")
        assertTrue(ok.up())
    }

    @Test
    fun changingSelectionDuringHoldDoesNotActivateAnotherItem() {
        val ok = OkPress(longMs = 600)
        val first = OkPressContext("history", "window", 0)
        val second = first.copy(selected = 1)
        assertTrue(ok.down(1000, first))
        assertFalse(ok.longDue(1600, second))
        assertFalse(ok.up(second))
        assertTrue(ok.down(2000, second))
        assertTrue(ok.longDue(2600, second))
        assertFalse(ok.up(second))
    }

    @Test
    fun closingListDuringHoldDoesNotTriggerPlaybackAction() {
        val ok = OkPress()
        val list = OkPressContext("channel", "bouquet:ref", 0)
        assertTrue(ok.down(1000, list))
        assertFalse(ok.longDue(1600, OkPressContext("playback")))
        assertFalse(ok.up(OkPressContext("playback")))
    }

    @Test
    fun shortPressOnListActivatesSelectedItemOnce() {
        val ok = OkPress()
        val selected = OkPressContext("quick-pick", "history", 2)
        assertTrue(ok.down(1000, selected))
        assertTrue(ok.up(selected))
        assertFalse(ok.up(selected))
    }
}
