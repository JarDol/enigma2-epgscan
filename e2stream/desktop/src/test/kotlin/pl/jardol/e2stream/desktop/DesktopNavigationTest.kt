package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

class DesktopNavigationTest {
    @Test
    fun pipPositionMatchesAndroidLayouts() {
        assertEquals(Alignment.TopEnd to 0, pipPlacement(0, 0))
        assertEquals(Alignment.TopStart to 0, pipPlacement(0, 1))
        assertEquals(Alignment.TopEnd to 1, pipPlacement(0, 2))
        assertEquals(Alignment.CenterEnd to 0, pipPlacement(2, 2))
        assertEquals(Alignment.CenterStart to 0, pipPlacement(3, 2))
        assertEquals(Alignment.TopStart to 2, pipPlacement(4, 2))
        assertEquals(Alignment.TopEnd to 2, pipPlacement(5, 2))
        assertEquals(Alignment.BottomStart to -2, pipPlacement(6, 2))
        assertEquals(Alignment.BottomEnd to -2, pipPlacement(7, 2))
    }

    @Test
    fun pipSizeFitsThreeWindowsAndKeepsSideBySideWindowsSeparate() {
        val one = pipWindowWidth(1280.dp, 720.dp, 40, 5, 1)
        val three = pipWindowWidth(1280.dp, 720.dp, 40, 5, 3)
        val sideBySide = pipWindowWidth(1280.dp, 720.dp, 50, 0, 2)
        assertTrue(one > 240.dp)
        assertTrue(three < one)
        assertTrue((three * (9f / 16f) + 32.dp) * 3 <= 720.dp)
        assertTrue((sideBySide + 32.dp) * 2 <= 1280.dp)
    }

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
        assertEquals(MenuKeyResult(true, DesktopMenuItem.entries.first()), navigation.handleMenuKey(VirtualRemoteKey.OK))
    }

    @Test
    fun configuredMenuKeepsOrderHidesItemsAndEndsWithSettingsAndExit() {
        val items = DesktopMenuItem.visible(
            order = listOf("IMAGE", "UNKNOWN", "CHANNEL_LIST", "IMAGE"),
            hidden = setOf("REMOTE_TEST", "SETTINGS"),
        )
        // zapisana kolejność zachowana, ukryte pominięte, nowe pozycje dopisane, na końcu Ustawienia i Wyłącz
        assertTrue(items.indexOf(DesktopMenuItem.IMAGE) < items.indexOf(DesktopMenuItem.CHANNEL_LIST))
        assertFalse(DesktopMenuItem.REMOTE_TEST in items)
        assertEquals(listOf(DesktopMenuItem.SETTINGS, DesktopMenuItem.EXIT), items.takeLast(2))
        assertEquals(DesktopMenuItem.configurableItems.size - 1 + 2, items.size)
        val navigation = DesktopNavigation()
        navigation.openMenu(items)
        assertEquals(items.size - 2, navigation.menuSelected)
        navigation.handleMenuKey(VirtualRemoteKey.DOWN)
        navigation.handleMenuKey(VirtualRemoteKey.DOWN)
        assertEquals(MenuKeyResult(true, items.first()), navigation.handleMenuKey(VirtualRemoteKey.OK))
    }

    @Test
    fun menuDialogHeightGrowsWithItemsAndCapsBeforeTheWindowScrolls() {
        assertTrue(menuDialogHeightDp(1) < menuDialogHeightDp(5))
        assertEquals(720, menuDialogHeightDp(DesktopMenuItem.entries.size))
        assertEquals(200, menuDialogHeightDp(-1))
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

class MenuOrderTest {
    @Test
    fun newItemsGoToDefaultPlaceNotToTheEnd() {
        // zapis sprzed dodania „Przesunięcie dźwięku” i „Statystyki”: nowe pozycje obok swoich domyślnych sąsiadów
        val saved = DesktopMenuItem.configurableItems.filter { it != DesktopMenuItem.AUDIO_DELAY && it != DesktopMenuItem.STATS }.map { it.name }
        val order = DesktopMenuItem.inOrder(saved)
        assertEquals(DesktopMenuItem.configurableItems, order)
    }
}
