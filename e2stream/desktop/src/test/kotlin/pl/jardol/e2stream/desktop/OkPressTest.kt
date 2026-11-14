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
}
