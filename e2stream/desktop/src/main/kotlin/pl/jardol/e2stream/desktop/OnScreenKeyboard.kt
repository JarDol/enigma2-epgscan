package pl.jardol.e2stream.desktop

internal enum class OskAction { CHAR, SPACE, BACKSPACE, CLEAR, SHIFT, VOICE, DONE }

/** Klawisz klawiatury ekranowej; [weight] = szerokość względem zwykłego klawisza. */
internal data class OskKey(val label: String, val action: OskAction, val text: String = "", val weight: Float = 1f)

/**
 * Klawiatura ekranowa okna edycji — do wpisywania pilotem (strzałki + OK). Litery z polskimi znakami,
 * cyfry, Spacja, usuwanie, Wyczyść, wielkie litery, dyktowanie Windows i zatwierdzenie; dla pól liczbowych
 * sama klawiatura numeryczna.
 */
internal object OnScreenKeyboard {
    private val textRows = listOf("1234567890", "qwertyuiop", "asdfghjklł", "zxcvbnm.-@", "ąćęńóśźż:/")

    fun layout(digitsOnly: Boolean, shift: Boolean): List<List<OskKey>> {
        if (digitsOnly) {
            return listOf("123", "456", "789").map { row -> row.map { OskKey(it.toString(), OskAction.CHAR, it.toString()) } } +
                listOf(
                    listOf(
                        OskKey("⌫", OskAction.BACKSPACE),
                        OskKey("0", OskAction.CHAR, "0"),
                        OskKey("OK", OskAction.DONE),
                    ),
                )
        }
        val letters = textRows.map { row ->
            row.map { char ->
                val text = if (shift) char.uppercaseChar().toString() else char.toString()
                OskKey(text, OskAction.CHAR, text)
            }
        }
        return letters + listOf(
            listOf(
                OskKey(if (shift) "abc" else "ABC", OskAction.SHIFT, weight = 1.3f),
                OskKey("Spacja", OskAction.SPACE, weight = 2.7f),
                OskKey("⌫ Usuń", OskAction.BACKSPACE, weight = 1.6f),
                OskKey("Wyczyść", OskAction.CLEAR, weight = 1.6f),
                OskKey("🎤 Dyktuj", OskAction.VOICE, weight = 1.6f),
                OskKey("OK", OskAction.DONE, weight = 1.2f),
            ),
        )
    }

    /** Pozycja startowa: klawisz OK (Enter z klawiatury komputera nadal od razu zatwierdza). */
    fun start(layout: List<List<OskKey>>): Pair<Int, Int> {
        val row = layout.indexOfLast { keys -> keys.any { it.action == OskAction.DONE } }.coerceAtLeast(0)
        return row to layout[row].indexOfFirst { it.action == OskAction.DONE }.coerceAtLeast(0)
    }

    /** Ruch zaznaczenia; w pionie zachowuje położenie w poziomie (wiersze mają różne szerokości klawiszy). */
    fun move(layout: List<List<OskKey>>, row: Int, col: Int, dRow: Int, dCol: Int): Pair<Int, Int> {
        if (layout.isEmpty()) return 0 to 0
        val r = row.coerceIn(0, layout.lastIndex)
        val c = col.coerceIn(0, layout[r].lastIndex)
        if (dCol != 0) return r to (c + dCol).mod(layout[r].size)
        if (dRow == 0) return r to c
        val target = (r + dRow).mod(layout.size)
        val center = position(layout[r], c)
        val keys = layout[target]
        val total = keys.sumOf { it.weight.toDouble() }
        var acc = 0.0
        for ((index, key) in keys.withIndex()) {
            acc += key.weight
            if (center * total <= acc) return target to index
        }
        return target to keys.lastIndex
    }

    /** Środek klawisza jako ułamek szerokości wiersza. */
    private fun position(keys: List<OskKey>, col: Int): Double {
        val total = keys.sumOf { it.weight.toDouble() }
        val before = keys.take(col).sumOf { it.weight.toDouble() }
        return (before + keys[col].weight / 2.0) / total
    }
}
