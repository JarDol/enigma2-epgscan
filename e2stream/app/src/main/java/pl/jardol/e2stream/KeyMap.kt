package pl.jardol.e2stream

import android.view.KeyEvent

/** Akcje, które można przypisać klawiszom pilota. */
enum class Act(val labelRes: Int) {
    NONE(R.string.action_none),
    DEFAULT_OK(R.string.action_default_ok),
    CHANNEL_BANNER(R.string.action_banner),
    CHANNEL_LIST(R.string.action_channel_list),
    EPG_EVENT(R.string.action_event_epg),
    EPG_CHANNEL(R.string.action_channel_epg),
    MULTI_EPG(R.string.action_multi_epg),
    EPG_SEARCH(R.string.action_search),
    AUDIO(R.string.action_audio),
    STATS(R.string.action_stats),
    MENU(R.string.action_menu),
    SETTINGS(R.string.action_settings),
    RECALL(R.string.action_recall),
    CH_UP(R.string.action_channel_up),
    CH_DOWN(R.string.action_channel_down),
    TIMESHIFT(R.string.action_timeshift),
    TS_REWIND(R.string.action_rewind),
    TS_FORWARD(R.string.action_forward),
    TS_LIVE(R.string.action_live),
}

/** Mapowanie klawiszy w trybie oglądania (bez otwartej listy kanałów). */
object KeyMap {
    /** Klawisze, których nie można przemapować. */
    val fixed = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
    ) + (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) + (KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9)

    val defaults: Map<Int, Act> = linkedMapOf(
        KeyEvent.KEYCODE_DPAD_UP to Act.CH_UP,
        KeyEvent.KEYCODE_DPAD_DOWN to Act.CH_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to Act.EPG_CHANNEL,
        KeyEvent.KEYCODE_DPAD_RIGHT to Act.MULTI_EPG,
        KeyEvent.KEYCODE_CHANNEL_UP to Act.CH_UP,
        KeyEvent.KEYCODE_CHANNEL_DOWN to Act.CH_DOWN,
        KeyEvent.KEYCODE_INFO to Act.EPG_EVENT,
        KeyEvent.KEYCODE_GUIDE to Act.MULTI_EPG,
        KeyEvent.KEYCODE_TV_DATA_SERVICE to Act.MULTI_EPG,
        KeyEvent.KEYCODE_SEARCH to Act.EPG_SEARCH,
        KeyEvent.KEYCODE_MENU to Act.MENU,
        KeyEvent.KEYCODE_SETTINGS to Act.SETTINGS,
        KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK to Act.AUDIO,
        KeyEvent.KEYCODE_LAST_CHANNEL to Act.RECALL,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to Act.RECALL,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to Act.TIMESHIFT,
        KeyEvent.KEYCODE_MEDIA_PAUSE to Act.TIMESHIFT,
        KeyEvent.KEYCODE_MEDIA_PLAY to Act.TIMESHIFT,
        KeyEvent.KEYCODE_MEDIA_REWIND to Act.TS_REWIND,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to Act.TS_FORWARD,
        KeyEvent.KEYCODE_MEDIA_STOP to Act.TS_LIVE,
    )

    val navigation = linkedMapOf(
        KeyEvent.KEYCODE_DPAD_CENTER to R.string.key_ok,
        KeyEvent.KEYCODE_DPAD_LEFT to R.string.key_left,
        KeyEvent.KEYCODE_DPAD_RIGHT to R.string.key_right,
        KeyEvent.KEYCODE_DPAD_UP to R.string.key_up,
        KeyEvent.KEYCODE_DPAD_DOWN to R.string.key_down,
    )

    val navigationNoBannerDefaults = mapOf(
        KeyEvent.KEYCODE_DPAD_CENTER to Act.CHANNEL_BANNER,
        KeyEvent.KEYCODE_DPAD_LEFT to Act.EPG_CHANNEL,
        KeyEvent.KEYCODE_DPAD_RIGHT to Act.MULTI_EPG,
        KeyEvent.KEYCODE_DPAD_UP to Act.CHANNEL_LIST,
        KeyEvent.KEYCODE_DPAD_DOWN to Act.CHANNEL_LIST,
    )

    val navigationBannerDefaults = mapOf(
        KeyEvent.KEYCODE_DPAD_CENTER to Act.EPG_EVENT,
        KeyEvent.KEYCODE_DPAD_LEFT to Act.EPG_CHANNEL,
        KeyEvent.KEYCODE_DPAD_RIGHT to Act.MULTI_EPG,
        KeyEvent.KEYCODE_DPAD_UP to Act.CHANNEL_LIST,
        KeyEvent.KEYCODE_DPAD_DOWN to Act.CHANNEL_LIST,
    )

    fun navigationMap(defaults: Map<Int, Act>, overrides: String): MutableMap<Int, Act> {
        val result = LinkedHashMap(defaults)
        parse(overrides).forEach { (code, act) ->
            if (code in navigation && (act != Act.DEFAULT_OK || code == KeyEvent.KEYCODE_DPAD_CENTER)) {
                result[code] = act
            }
        }
        return result
    }

    fun parse(value: String): MutableMap<Int, Act> {
        val m = linkedMapOf<Int, Act>()
        for (part in value.split(",")) {
            val kv = part.split("=")
            if (kv.size != 2) continue
            val code = kv[0].trim().toIntOrNull() ?: continue
            val act = try { Act.valueOf(kv[1].trim()) } catch (_: IllegalArgumentException) { continue }
            m[code] = act
        }
        return m
    }

    fun encode(m: Map<Int, Act>): String =
        m.entries.joinToString(",") { "${it.key}=${it.value.name}" }

    fun load(prefs: Prefs): MutableMap<Int, Act> {
        val m = LinkedHashMap(defaults)
        m.putAll(parse(prefs.keyMap))
        return m
    }

    fun save(prefs: Prefs, m: Map<Int, Act>) {
        prefs.keyMap = encode(m)
    }

    fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
}
