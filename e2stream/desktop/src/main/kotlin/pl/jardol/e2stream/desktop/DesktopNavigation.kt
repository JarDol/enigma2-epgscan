package pl.jardol.e2stream.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class DesktopMenuItem(val label: String, val configurable: Boolean = true) {
    RECORD("Nagraj z timeshiftu"),
    STOP_RECORDING("Zatrzymaj nagrywanie…"),
    AUDIO("Ścieżka dźwięku"),
    AUDIO_DELAY("Przesunięcie dźwięku"),
    CHANNEL_LIST("Lista kanałów"),
    HISTORY("Historia kanałów"),
    RECORDINGS("Nagrania"),
    LOCAL_RECORDINGS("Nagrania lokalne"),
    EPG_CHANNEL("EPG kanału"),
    MULTI_EPG("Multi EPG"),
    EPG_SEARCH("Szukaj w EPG"),
    ZAP_TIMERS("Przełączenia wg harmonogramu"),
    DECODER_TIMERS("Harmonogramy dekodera"),
    IMAGE("Obraz — skalowanie"),
    WINDOW("Okno / pełny ekran"),
    REMOTE_TEST("Test pilota"),
    ACTIVATE_KEYS("Aktywuj okno klawiszem"),
    STATS("Statystyki na ekranie"),
    SEND_LOG("Wyślij log na dekoder"),
    SETTINGS("Ustawienia", configurable = false),
    EXIT("Wyłącz E2Stream", configurable = false),
    ;

    companion object {
        val configurableItems: List<DesktopMenuItem> get() = entries.filter { it.configurable }

        /**
         * Pozycje konfigurowalne w zapisanej kolejności. Pozycje nowe (spoza zapisanej listy) trafiają w miejsce
         * z kolejności domyślnej — za najbliższą wcześniejszą pozycją domyślną, nie na koniec.
         */
        fun inOrder(order: List<String>): List<DesktopMenuItem> {
            val result = order.mapNotNull { name -> configurableItems.firstOrNull { it.name == name } }.distinct().toMutableList()
            if (result.isEmpty()) return configurableItems
            val defaults = configurableItems
            for ((index, item) in defaults.withIndex()) {
                if (item in result) continue
                val before = defaults.subList(0, index).lastOrNull { it in result }
                val at = if (before == null) 0 else result.indexOf(before) + 1
                result.add(at, item)
            }
            return result
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
