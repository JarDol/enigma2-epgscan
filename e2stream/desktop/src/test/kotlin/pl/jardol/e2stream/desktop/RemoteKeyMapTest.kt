package pl.jardol.e2stream.desktop

import java.awt.event.KeyEvent
import java.awt.event.InputEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteKeyMapTest {
    @Test
    fun modifiersDoNotTriggerSingleKeyShortcuts() {
        assertTrue(unmodifiedShortcut(0))
        assertFalse(unmodifiedShortcut(InputEvent.SHIFT_DOWN_MASK))
        assertFalse(unmodifiedShortcut(InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK))
        assertFalse(unmodifiedShortcut(InputEvent.ALT_DOWN_MASK))
        assertFalse(unmodifiedShortcut(InputEvent.META_DOWN_MASK))
        assertFalse(unmodifiedShortcut(InputEvent.ALT_GRAPH_DOWN_MASK))
    }

    @Test
    fun encodedBindingsRoundTripAndInvalidEntriesAreIgnored() {
        val encoded = RemoteKeyMap.encode(
            mapOf(
                KeyEvent.VK_F8 to RemoteAction.CHANNEL_UP,
                KeyEvent.VK_ENTER to RemoteAction.CHANNEL_LIST,
            ),
        )

        assertEquals(
            mapOf(
                KeyEvent.VK_F8 to RemoteAction.CHANNEL_UP,
                KeyEvent.VK_ENTER to RemoteAction.CHANNEL_LIST,
            ),
            RemoteKeyMap.parse("$encoded,invalid=MENU,777777=BAD_ACTION"),
        )
    }

    @Test
    fun customBindingOverridesDefaultForSameKey() {
        val bindings = RemoteKeyMap.defaults + (KeyEvent.VK_UP to RemoteAction.MENU)

        assertEquals(RemoteAction.MENU, bindings[KeyEvent.VK_UP])
        assertEquals(RemoteAction.CHANNEL_DOWN, bindings[KeyEvent.VK_DOWN])
    }

    @Test
    fun removedDefaultBindingStaysRemovedAfterReload() {
        val saved = RemoteKeyMap.encode(mapOf(KeyEvent.VK_UP to RemoteAction.NONE))
        val reloaded = RemoteKeyMap.defaults + RemoteKeyMap.parse(saved)

        assertEquals(RemoteAction.NONE, reloaded[KeyEvent.VK_UP])
    }
}
