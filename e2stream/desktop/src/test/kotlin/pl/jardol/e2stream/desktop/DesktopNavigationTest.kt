package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopNavigationTest {
    @Test
    fun menuOpensOnSettingsAndActivatesSelectedItem() {
        val navigation = DesktopNavigation()
        navigation.openMenu()
        assertEquals(MenuKeyResult(true, DesktopMenuItem.SETTINGS), navigation.handleMenuKey(VirtualRemoteKey.OK))
        assertFalse(navigation.showMenu)
        assertFalse(navigation.controlsVisible)
    }

    @Test
    fun arrowsMoveMenuSelectionWithWrapping() {
        val navigation = DesktopNavigation()
        navigation.openMenu()
        navigation.handleMenuKey(VirtualRemoteKey.DOWN)
        assertEquals(DesktopMenuItem.EXIT.ordinal, navigation.menuSelected)
        navigation.handleMenuKey(VirtualRemoteKey.DOWN)
        assertEquals(MenuKeyResult(true, DesktopMenuItem.CHANNEL_LIST), navigation.handleMenuKey(VirtualRemoteKey.OK))
    }

    @Test
    fun configuredMenuKeepsOrderHidesItemsAndEndsWithSettingsAndExit() {
        val items = DesktopMenuItem.visible(
            order = listOf("IMAGE", "UNKNOWN", "CHANNEL_LIST", "IMAGE"),
            hidden = setOf("REMOTE_TEST", "SETTINGS"),
        )
        assertEquals(
            listOf(
                DesktopMenuItem.IMAGE, DesktopMenuItem.CHANNEL_LIST, DesktopMenuItem.RECORDINGS,
                DesktopMenuItem.ZAP_TIMERS, DesktopMenuItem.DECODER_TIMERS, DesktopMenuItem.WINDOW,
                DesktopMenuItem.MULTI_EPG, DesktopMenuItem.EPG_CHANNEL, DesktopMenuItem.EPG_SEARCH, DesktopMenuItem.AUDIO,
                DesktopMenuItem.RECORD, DesktopMenuItem.ACTIVATE_KEYS,
                DesktopMenuItem.SETTINGS, DesktopMenuItem.EXIT,
            ),
            items,
        )
        val navigation = DesktopNavigation()
        navigation.openMenu(items)
        assertEquals(12, navigation.menuSelected)
        navigation.handleMenuKey(VirtualRemoteKey.DOWN)
        navigation.handleMenuKey(VirtualRemoteKey.DOWN)
        assertEquals(MenuKeyResult(true, DesktopMenuItem.IMAGE), navigation.handleMenuKey(VirtualRemoteKey.OK))
    }

    @Test
    fun backClosesMenuWithoutActivatingAnything() {
        val navigation = DesktopNavigation()
        navigation.openMenu()
        val result = navigation.handleMenuKey(VirtualRemoteKey.BACK)
        assertTrue(result.handled)
        assertNull(result.activated)
        assertFalse(navigation.showMenu)
        assertFalse(navigation.handleMenuKey(VirtualRemoteKey.OK).handled)
    }

    @Test
    fun mouseControlsToggleWithoutOpeningMenu() {
        val navigation = DesktopNavigation()
        navigation.toggleControls()
        assertTrue(navigation.controlsVisible)
        assertFalse(navigation.showMenu)
        navigation.toggleControls()
        assertFalse(navigation.controlsVisible)
    }
}
