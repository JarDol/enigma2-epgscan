package pl.jardol.e2stream.desktop

import org.json.JSONArray

internal object ActivationKeys {
    fun valid(chord: Set<String>): Boolean =
        chord.isNotEmpty() && chord.size <= 8 && chord.all {
            val value = it.substringAfter(':', "").toIntOrNull()
            when {
                it.startsWith("K:") -> value != null && value in 1..255
                it.startsWith("M:") -> value != null && value in 1..5
                else -> false
            }
        }

    fun parse(encoded: String): Set<Set<String>> {
        if (encoded.isBlank()) return emptySet()
        val array = JSONArray(encoded)
        return (0 until array.length()).map { index ->
            val keys = array.getJSONArray(index)
            (0 until keys.length()).map { keys.getString(it) }.toSet().also { chord ->
                require(valid(chord)) { "Nieprawidłowa kombinacja aktywacji okna." }
            }
        }.toSet()
    }

    fun encode(chords: Set<Set<String>>): String =
        JSONArray(chords.map { JSONArray(it.sorted()) }.sortedBy { it.toString() }).toString()

    fun label(chord: Set<String>): String = chord.sorted().joinToString(" + ") {
        when (it) {
            "M:1" -> "Lewy przycisk myszy"
            "M:2" -> "Prawy przycisk myszy"
            "M:3" -> "Środkowy przycisk myszy"
            "M:4" -> "Przycisk myszy 4"
            "M:5" -> "Przycisk myszy 5"
            "K:91" -> "Lewy Windows"
            "K:92" -> "Prawy Windows"
            else -> UniversalRemoteMap.label(it)
        }
    }
}

internal fun shouldActivateFromInput(
    chord: Set<String>?, bindings: Set<Set<String>>, foreground: Boolean, capturing: Boolean,
): Boolean = chord != null && !foreground && !capturing && chord in bindings

internal class ActivationKeyTracker {
    private val keys = mutableSetOf<Int>()
    private val mouse = mutableSetOf<Int>()
    private val captured = mutableSetOf<String>()

    @Synchronized
    fun resetCapture() = captured.clear()

    @Synchronized
    fun update(keyboard: Boolean, code: Int, down: Boolean, capturing: Boolean): Set<String>? {
        val pressed = if (keyboard) keys else mouse
        val changed = if (down) pressed.add(code) else pressed.remove(code)
        if (!changed) return null
        val chord = keys.map { "K:${normalKey(it)}" }.toSet() + mouse.map { "M:$it" }
        if (!capturing) {
            captured.clear()
            return chord.takeIf { down }
        }
        if (down) captured.addAll(chord)
        if (!down && keys.isEmpty() && mouse.isEmpty() && captured.isNotEmpty()) {
            return captured.toSet().also { captured.clear() }
        }
        return null
    }

    private fun normalKey(key: Int): Int = when (key) {
        0xA0, 0xA1 -> 0x10
        0xA2, 0xA3 -> 0x11
        0xA4, 0xA5 -> 0x12
        else -> key
    }
}
