package pl.jardol.e2stream.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.awt.event.KeyEvent
import java.nio.charset.StandardCharsets
import java.util.Base64

enum class VirtualRemoteKey(val label: String) {
    POWER("Power"),
    HOME("Home"),
    BACK("Wstecz"),
    MENU("Menu"),
    OK("OK"),
    UP("Góra"),
    DOWN("Dół"),
    LEFT("Lewo"),
    RIGHT("Prawo"),
    CHANNEL_UP("Kanał +"),
    CHANNEL_DOWN("Kanał −"),
    INFO("Info"),
    GUIDE("Guide / EPG"),
    TV_DATA("TV"),
    SEARCH("Szukaj"),
    SETTINGS("Ustawienia"),
    AUDIO("Audio"),
    LAST_CHANNEL("Poprzedni kanał"),
    PLAY_PAUSE("Play / Pause"),
    PLAY("Play"),
    PAUSE("Pause"),
    REWIND("Przewiń wstecz"),
    FAST_FORWARD("Przewiń do przodu"),
    STOP("Stop / na żywo"),
    DIGIT_0("0"),
    DIGIT_1("1"),
    DIGIT_2("2"),
    DIGIT_3("3"),
    DIGIT_4("4"),
    DIGIT_5("5"),
    DIGIT_6("6"),
    DIGIT_7("7"),
    DIGIT_8("8"),
    DIGIT_9("9"),
}

object UniversalRemoteMap {
    val defaults: Map<VirtualRemoteKey, Set<String>> = buildMap {
        put(VirtualRemoteKey.OK, setOf(keyId(KeyEvent.VK_ENTER)))
        put(VirtualRemoteKey.UP, setOf(keyId(KeyEvent.VK_UP)))
        put(VirtualRemoteKey.DOWN, setOf(keyId(KeyEvent.VK_DOWN)))
        put(VirtualRemoteKey.LEFT, setOf(keyId(KeyEvent.VK_LEFT)))
        put(VirtualRemoteKey.RIGHT, setOf(keyId(KeyEvent.VK_RIGHT)))
        put(VirtualRemoteKey.BACK, setOf(keyId(KeyEvent.VK_ESCAPE)))
        put(VirtualRemoteKey.HOME, setOf(keyId(KeyEvent.VK_HOME)))
        put(VirtualRemoteKey.CHANNEL_UP, setOf(keyId(KeyEvent.VK_PAGE_UP)))
        put(VirtualRemoteKey.CHANNEL_DOWN, setOf(keyId(KeyEvent.VK_PAGE_DOWN)))
        put(VirtualRemoteKey.SETTINGS, setOf(keyId(KeyEvent.VK_F1)))
        put(VirtualRemoteKey.DIGIT_0, setOf(keyId(KeyEvent.VK_0), keyId(KeyEvent.VK_NUMPAD0)))
        put(VirtualRemoteKey.DIGIT_1, setOf(keyId(KeyEvent.VK_1), keyId(KeyEvent.VK_NUMPAD1)))
        put(VirtualRemoteKey.DIGIT_2, setOf(keyId(KeyEvent.VK_2), keyId(KeyEvent.VK_NUMPAD2)))
        put(VirtualRemoteKey.DIGIT_3, setOf(keyId(KeyEvent.VK_3), keyId(KeyEvent.VK_NUMPAD3)))
        put(VirtualRemoteKey.DIGIT_4, setOf(keyId(KeyEvent.VK_4), keyId(KeyEvent.VK_NUMPAD4)))
        put(VirtualRemoteKey.DIGIT_5, setOf(keyId(KeyEvent.VK_5), keyId(KeyEvent.VK_NUMPAD5)))
        put(VirtualRemoteKey.DIGIT_6, setOf(keyId(KeyEvent.VK_6), keyId(KeyEvent.VK_NUMPAD6)))
        put(VirtualRemoteKey.DIGIT_7, setOf(keyId(KeyEvent.VK_7), keyId(KeyEvent.VK_NUMPAD7)))
        put(VirtualRemoteKey.DIGIT_8, setOf(keyId(KeyEvent.VK_8), keyId(KeyEvent.VK_NUMPAD8)))
        put(VirtualRemoteKey.DIGIT_9, setOf(keyId(KeyEvent.VK_9), keyId(KeyEvent.VK_NUMPAD9)))
    }

    fun keyId(keyCode: Int): String = "K:$keyCode"

    fun hidId(devicePath: String, report: ByteArray): String {
        val device = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(devicePath.toByteArray(StandardCharsets.UTF_8))
        val bytes = report.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        return "H:$device:$bytes"
    }

    fun label(id: String): String = when {
        id.startsWith("K:") -> id.substringAfter(':').toIntOrNull()?.let(RemoteKeyMap::keyName) ?: id
        id.startsWith("H:") -> "Pilot HID · ${id.substringAfterLast(':').chunked(2).joinToString("-")}"
        else -> id
    }

    fun parse(encoded: String): MutableMap<VirtualRemoteKey, Set<String>> {
        if (encoded.isBlank()) return defaults.toMutableMap()
        return try {
            val json = JSONObject(encoded)
            VirtualRemoteKey.entries.associateWithTo(linkedMapOf()) { key ->
                val items = json.optJSONArray(key.name) ?: return@associateWithTo emptySet()
                buildSet {
                    for (index in 0 until items.length()) {
                        items.optString(index).takeIf(::isValidPhysicalId)?.let(::add)
                    }
                }
            }
        } catch (_: Exception) {
            defaults.toMutableMap()
        }
    }

    fun encode(bindings: Map<VirtualRemoteKey, Set<String>>): String =
        JSONObject().apply {
            VirtualRemoteKey.entries.forEach { key ->
                put(key.name, JSONArray(bindings[key].orEmpty().sorted()))
            }
        }.toString()

    fun assign(
        bindings: Map<VirtualRemoteKey, Set<String>>,
        key: VirtualRemoteKey,
        physicalId: String,
    ): Map<VirtualRemoteKey, Set<String>> {
        require(isValidPhysicalId(physicalId)) { "Nieprawidłowy klawisz fizyczny." }
        return VirtualRemoteKey.entries.associateWith { candidate ->
            val current = bindings[candidate].orEmpty() - physicalId
            if (candidate == key) current + physicalId else current
        }
    }

    fun remove(
        bindings: Map<VirtualRemoteKey, Set<String>>,
        key: VirtualRemoteKey,
        physicalId: String,
    ): Map<VirtualRemoteKey, Set<String>> =
        VirtualRemoteKey.entries.associateWith { candidate ->
            if (candidate == key) bindings[candidate].orEmpty() - physicalId
            else bindings[candidate].orEmpty()
        }

    fun findKey(bindings: Map<VirtualRemoteKey, Set<String>>, physicalId: String): VirtualRemoteKey? =
        VirtualRemoteKey.entries.firstOrNull { physicalId in bindings[it].orEmpty() }

    private fun isValidPhysicalId(id: String): Boolean =
        id.startsWith("K:") && id.substringAfter(':').toIntOrNull() != null ||
            id.startsWith("H:") && id.count { it == ':' } == 2
}
