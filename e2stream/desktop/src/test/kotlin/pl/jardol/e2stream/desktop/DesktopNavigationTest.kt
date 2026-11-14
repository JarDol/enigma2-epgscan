package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopNavigationTest {
    @Test
    fun opensSettingsFromMenuWithoutShowingControls() {
        val navigation = DesktopNavigation()
        assertEquals(DesktopPage.PLAYER, navigation.page)
        assertFalse(navigation.controlsVisible)
        navigation.openMenu()
        assertTrue(navigation.handleMenuKey(VirtualRemoteKey.OK))
        assertFalse(navigation.showMenu)
        assertEquals(DesktopPage.CONNECTION, navigation.page)
        assertFalse(navigation.controlsVisible)
    }

    @Test
    fun backClosesMenuWithoutLeavingPlayer() {
        val navigation = DesktopNavigation()
        navigation.openMenu()
        assertTrue(navigation.handleMenuKey(VirtualRemoteKey.BACK))
        assertFalse(navigation.showMenu)
        assertEquals(DesktopPage.PLAYER, navigation.page)
        assertFalse(navigation.handleMenuKey(VirtualRemoteKey.OK))
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
