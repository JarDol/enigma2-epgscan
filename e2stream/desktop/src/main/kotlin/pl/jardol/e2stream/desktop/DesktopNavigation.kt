package pl.jardol.e2stream.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class DesktopMenuItem(val label: String) {
    CHANNEL_LIST("Lista kanałów"),
    SETTINGS("Ustawienia"),
    REMOTE_TEST("Test pilota"),
    WINDOW("Okno / pełny ekran"),
    EXIT("Wyłącz E2Stream"),
}

internal data class MenuKeyResult(val handled: Boolean, val activated: DesktopMenuItem? = null)

internal class DesktopNavigation {
    var showMenu by mutableStateOf(false)
    var menuSelected by mutableIntStateOf(0)
    var controlsVisible by mutableStateOf(false)

    fun openMenu() {
        menuSelected = DesktopMenuItem.SETTINGS.ordinal
        showMenu = true
    }

    fun toggleControls() {
        controlsVisible = !controlsVisible
    }

    fun handleMenuKey(key: VirtualRemoteKey): MenuKeyResult {
        if (!showMenu) return MenuKeyResult(false)
        val items = DesktopMenuItem.entries
        when (key) {
            VirtualRemoteKey.OK -> {
                showMenu = false
                return MenuKeyResult(true, items[menuSelected.coerceIn(items.indices)])
            }
            VirtualRemoteKey.BACK, VirtualRemoteKey.MENU, VirtualRemoteKey.LEFT -> showMenu = false
            VirtualRemoteKey.UP -> menuSelected = (menuSelected - 1).mod(items.size)
            VirtualRemoteKey.DOWN -> menuSelected = (menuSelected + 1).mod(items.size)
            else -> return MenuKeyResult(false)
        }
        return MenuKeyResult(true)
    }
}
