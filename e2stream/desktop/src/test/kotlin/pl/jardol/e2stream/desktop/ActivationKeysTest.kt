package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ActivationKeysTest {
    @Test
    fun captureChordIncludesKeyboardAndMouseUntilAllReleased() {
        val tracker = ActivationKeyTracker()
        assertNull(tracker.update(true, 0xA2, true, true))
        assertNull(tracker.update(false, 2, true, true))
        assertNull(tracker.update(true, 0xA2, false, true))
        assertEquals(setOf("K:17", "M:2"), tracker.update(false, 2, false, true))
    }

    @Test
    fun repeatedKeysDoNotActivateAgainAndReleaseClearsState() {
        val tracker = ActivationKeyTracker()
        assertEquals(setOf("K:16"), tracker.update(true, 0xA0, true, false))
        assertEquals(setOf("K:16", "K:83"), tracker.update(true, 83, true, false))
        assertNull(tracker.update(true, 83, true, false))
        assertNull(tracker.update(true, 83, false, false))
        assertNull(tracker.update(true, 0xA0, false, false))
        assertEquals(setOf("K:83"), tracker.update(true, 83, true, false))
    }

    @Test
    fun bindingsRoundTrip() {
        val keys = setOf(setOf("K:17", "K:83"), setOf("M:4"))
        assertEquals(keys, ActivationKeys.parse(ActivationKeys.encode(keys)))
    }

    @Test
    fun rejectsInvalidInputInsteadOfSavingUnusableBindings() {
        assertFalse(ActivationKeys.valid(setOf("K:0")))
        assertFalse(ActivationKeys.valid(setOf("M:6")))
        assertFailsWith<IllegalArgumentException> { ActivationKeys.parse("""[["K:0"]]""") }
    }

    @Test
    fun onlyExactConfiguredChordActivatesInBackground() {
        val bindings = setOf(setOf("K:17", "K:83"), setOf("M:4"))
        assertTrue(shouldActivateFromInput(setOf("K:17", "K:83"), bindings, false, false))
        assertTrue(shouldActivateFromInput(setOf("M:4"), bindings, false, false))
        assertFalse(shouldActivateFromInput(setOf("K:83"), bindings, false, false))
        assertFalse(shouldActivateFromInput(setOf("K:17", "K:83", "M:1"), bindings, false, false))
        assertFalse(shouldActivateFromInput(setOf("M:4"), bindings, true, false))
        assertFalse(shouldActivateFromInput(setOf("M:4"), bindings, false, true))
    }
}
