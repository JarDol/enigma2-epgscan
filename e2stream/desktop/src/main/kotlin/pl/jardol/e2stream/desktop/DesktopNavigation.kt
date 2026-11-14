package pl.jardol.e2stream.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class DesktopMenuItem(val label: String, val configurable: Boolean = true) {
    CHANNEL_LIST("Lista kanałów"),
    RECORDINGS("Nagrania"),
    ZAP_TIMERS("Przełączenia wg harmonogramu"),
    DECODER_TIMERS("Harmonogramy dekodera"),
    REMOTE_TEST("Test pilota"),
    IMAGE("Obraz — skalowanie"),
    WINDOW("Okno / pełny ekran"),
    MULTI_EPG("Multi EPG"),
    EPG_CHANNEL("EPG kanału"),
    EPG_SEARCH("Szukaj w EPG"),
    AUDIO("Ścieżka dźwięku"),
    RECORD("Nagraj z timeshiftu"),
    SETTINGS("Ustawienia", configurable = false),
    EXIT("Wyłącz E2Stream", configurable = false),
    ;

    companion object {
        val configurableItems: List<DesktopMenuItem> get() = entries.filter { it.configurable }

        /** Pozycje konfigurowalne w zapisanej kolejności (nowe dopisane na końcu). */
        fun inOrder(order: List<String>): List<DesktopMenuItem> {
            val stored = order.mapNotNull { name -> configurableItems.firstOrNull { it.name == name } }.distinct()
            return stored + configurableItems.filterNot { it in stored }
        }

        /** Menu: widoczne pozycje w ustalonej kolejności, Ustawienia i Wyłącz zawsze na końcu. */
        fun visible(order: List<String>, hidden: Set<String>): List<DesktopMenuItem> =
            inOrder(order).filterNot { it.name in hidden } + SETTINGS + EXIT
    }
}

internal data class MenuKeyResult(val handled: Boolean, val activated: DesktopMenuItem? = null)

internal class DesktopNavigation {
    var showMenu by mutableStateOf(false)
    var menuSelected by mutableIntStateOf(0)
    var menuItems by mutableStateOf(DesktopMenuItem.entries.toList())
        private set
    var controlsVisible by mutableStateOf(false)

    fun openMenu(items: List<DesktopMenuItem> = DesktopMenuItem.entries) {
        menuItems = items
        menuSelected = items.indexOf(DesktopMenuItem.SETTINGS).coerceAtLeast(0)
        showMenu = true
    }

    fun toggleControls() {
        controlsVisible = !controlsVisible
    }

    fun handleMenuKey(key: VirtualRemoteKey): MenuKeyResult {
        if (!showMenu) return MenuKeyResult(false)
        val items = menuItems
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
