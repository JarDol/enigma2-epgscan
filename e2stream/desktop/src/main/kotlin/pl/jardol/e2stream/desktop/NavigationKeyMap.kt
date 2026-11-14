package pl.jardol.e2stream.desktop

/** Akcje przypisywane OK i strzałkom podczas oglądania (odpowiednik Act z Androida). */
enum class NavAction(val label: String) {
    NONE("— brak —"),
    CHANNEL_BANNER("Pokaż pasek kanału"),
    CHANNEL_LIST("Lista kanałów"),
    EPG_EVENT("EPG wydarzenia (opis bieżącej audycji)"),
    EPG_CHANNEL("EPG kanału"),
    MULTI_EPG("Multi-EPG bukietu"),
    EPG_SEARCH("Szukaj w EPG"),
    AUDIO("Ścieżka dźwięku"),
    MENU("Menu"),
    SETTINGS("Ustawienia"),
    RECALL("Poprzedni kanał"),
    CH_UP("Kanał +"),
    CH_DOWN("Kanał −"),
    PLAY_PAUSE("Pauza / wznowienie"),
}

object NavigationKeyMap {
    val keys = listOf(
        VirtualRemoteKey.OK,
        VirtualRemoteKey.LEFT,
        VirtualRemoteKey.RIGHT,
        VirtualRemoteKey.UP,
        VirtualRemoteKey.DOWN,
    )

    val noBannerDefaults: Map<VirtualRemoteKey, NavAction> = mapOf(
        VirtualRemoteKey.OK to NavAction.CHANNEL_BANNER,
        VirtualRemoteKey.LEFT to NavAction.EPG_CHANNEL,
        VirtualRemoteKey.RIGHT to NavAction.MULTI_EPG,
        VirtualRemoteKey.UP to NavAction.CHANNEL_LIST,
        VirtualRemoteKey.DOWN to NavAction.CHANNEL_LIST,
    )

    val bannerDefaults: Map<VirtualRemoteKey, NavAction> = mapOf(
        VirtualRemoteKey.OK to NavAction.EPG_EVENT,
        VirtualRemoteKey.LEFT to NavAction.EPG_CHANNEL,
        VirtualRemoteKey.RIGHT to NavAction.MULTI_EPG,
        VirtualRemoteKey.UP to NavAction.CHANNEL_LIST,
        VirtualRemoteKey.DOWN to NavAction.CHANNEL_LIST,
    )

    fun resolve(defaults: Map<VirtualRemoteKey, NavAction>, overrides: String): Map<VirtualRemoteKey, NavAction> =
        defaults + parse(overrides)

    fun parse(value: String): Map<VirtualRemoteKey, NavAction> = buildMap {
        for (part in value.split(",")) {
            val pair = part.split("=")
            if (pair.size != 2) continue
            val key = VirtualRemoteKey.entries.firstOrNull { it.name == pair[0].trim() } ?: continue
            val action = NavAction.entries.firstOrNull { it.name == pair[1].trim() } ?: continue
            if (key in this@NavigationKeyMap.keys) put(key, action)
        }
    }

    fun encode(map: Map<VirtualRemoteKey, NavAction>): String =
        keys.mapNotNull { key -> map[key]?.let { "${key.name}=${it.name}" } }.joinToString(",")
}
