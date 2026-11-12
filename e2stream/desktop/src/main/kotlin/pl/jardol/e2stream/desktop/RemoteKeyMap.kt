package pl.jardol.e2stream.desktop

import java.awt.event.KeyEvent

enum class RemoteAction(val label: String) {
    NONE("Brak akcji"),
    CHANNEL_UP("Kanał +"),
    CHANNEL_DOWN("Kanał −"),
    CHANNEL_LIST("Lista kanałów"),
    CHANNEL_BANNER("Pasek kanału"),
    EPG_EVENT("EPG wydarzenia"),
    EPG_CHANNEL("EPG kanału"),
    MULTI_EPG("Multi-EPG"),
    EPG_SEARCH("Szukaj w EPG"),
    AUDIO("Ścieżka dźwięku"),
    STATS("Statystyki"),
    MENU("Menu"),
    SETTINGS("Ustawienia"),
    RECALL("Poprzedni kanał"),
    TIMESHIFT("Timeshift: pauza / wznowienie"),
    TS_REWIND("Timeshift: wstecz"),
    TS_FORWARD("Timeshift: do przodu"),
    TS_LIVE("Timeshift: na żywo"),
}

object RemoteKeyMap {
    val defaults: Map<Int, RemoteAction> = linkedMapOf(
        KeyEvent.VK_UP to RemoteAction.CHANNEL_UP,
        KeyEvent.VK_DOWN to RemoteAction.CHANNEL_DOWN,
        KeyEvent.VK_LEFT to RemoteAction.EPG_CHANNEL,
        KeyEvent.VK_RIGHT to RemoteAction.MULTI_EPG,
        KeyEvent.VK_ENTER to RemoteAction.CHANNEL_LIST,
        KeyEvent.VK_SPACE to RemoteAction.TIMESHIFT,
        KeyEvent.VK_I to RemoteAction.EPG_EVENT,
        KeyEvent.VK_G to RemoteAction.MULTI_EPG,
        KeyEvent.VK_S to RemoteAction.EPG_SEARCH,
        KeyEvent.VK_M to RemoteAction.MENU,
        KeyEvent.VK_ESCAPE to RemoteAction.NONE,
    )

    fun parse(encoded: String): MutableMap<Int, RemoteAction> {
        val result = linkedMapOf<Int, RemoteAction>()
        encoded.split(",").forEach { item ->
            val parts = item.split("=", limit = 2)
            if (parts.size != 2) return@forEach
            val keyCode = parts[0].trim().toIntOrNull() ?: return@forEach
            val action = runCatching { RemoteAction.valueOf(parts[1].trim()) }.getOrNull()
                ?: return@forEach
            if (keyCode != KeyEvent.VK_UNDEFINED) result[keyCode] = action
        }
        return result
    }

    fun encode(bindings: Map<Int, RemoteAction>): String =
        bindings.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.name}" }

    fun keyName(keyCode: Int): String = KeyEvent.getKeyText(keyCode)
}
