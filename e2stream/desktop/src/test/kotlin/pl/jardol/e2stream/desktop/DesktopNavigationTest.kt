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
        navigation.handleMenuKey(VirtualRemoteKey.UP)
        navigation.handleMenuKey(VirtualRemoteKey.UP)
        assertEquals(DesktopMenuItem.EXIT.ordinal, navigation.menuSelected)
        navigation.handleMenuKey(VirtualRemoteKey.DOWN)
        assertEquals(MenuKeyResult(true, DesktopMenuItem.CHANNEL_LIST), navigation.handleMenuKey(VirtualRemoteKey.OK))
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
