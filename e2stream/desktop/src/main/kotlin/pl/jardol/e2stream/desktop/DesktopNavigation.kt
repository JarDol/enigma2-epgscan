package pl.jardol.e2stream.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class DesktopPage { CONNECTION, PLAYER, REMOTE }

internal class DesktopNavigation {
    var page by mutableStateOf(DesktopPage.PLAYER)
    var showMenu by mutableStateOf(false)
    var controlsVisible by mutableStateOf(false)

    fun openMenu() {
        showMenu = true
    }

    fun toggleControls() {
        controlsVisible = !controlsVisible
    }

    fun handleMenuKey(key: VirtualRemoteKey): Boolean {
        if (!showMenu) return false
        when (key) {
            VirtualRemoteKey.OK -> {
                showMenu = false
                page = DesktopPage.CONNECTION
            }
            VirtualRemoteKey.BACK, VirtualRemoteKey.MENU -> showMenu = false
            VirtualRemoteKey.UP, VirtualRemoteKey.DOWN -> Unit
            else -> return false
        }
        return true
    }
}
