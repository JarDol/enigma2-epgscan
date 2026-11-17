package pl.jardol.e2stream.desktop

import java.util.concurrent.TimeUnit

/**
 * Działanie przycisku zasilania Windows (powercfg, bieżący plan zasilania). Klawisz Power pilota (HID „System Power Down”)
 * Windows traktuje jak przycisk zasilania laptopa — żadna aplikacja go nie zatrzyma. „Nic nie rób” zostawia Power pilota
 * tylko dla E2Stream (przytrzymanie przycisku laptopa przez kilka sekund nadal wymusza wyłączenie).
 */
internal object WindowsPowerButton {
    data class State(val ac: Int, val dc: Int)

    private val labels = mapOf(0 to "Nic nie rób", 1 to "Uśpij", 2 to "Hibernuj", 3 to "Zamknij system", 4 to "Wyłącz ekran")

    fun label(value: Int): String = labels[value] ?: "wartość $value"

    fun describe(state: State): String =
        if (state.ac == state.dc) label(state.ac) else "${label(state.ac)} (zasilacz) / ${label(state.dc)} (bateria)"

    /** Odczyt z `powercfg /q` — dwie ostatnie wartości 0x… to AC i DC (niezależnie od języka Windows). */
    internal fun parse(output: String): State? {
        val values = Regex("0x([0-9a-fA-F]{8})").findAll(output).map { it.groupValues[1].toInt(16) }.toList()
        if (values.size < 2) return null
        return State(values[values.size - 2], values[values.size - 1])
    }

    fun query(): State? = runCatching {
        parse(run("powercfg", "/q", "SCHEME_CURRENT", "SUB_BUTTONS", "PBUTTONACTION"))
    }.getOrNull()

    fun set(state: State): Boolean = runCatching {
        run("powercfg", "/setacvalueindex", "SCHEME_CURRENT", "SUB_BUTTONS", "PBUTTONACTION", state.ac.toString())
        run("powercfg", "/setdcvalueindex", "SCHEME_CURRENT", "SUB_BUTTONS", "PBUTTONACTION", state.dc.toString())
        run("powercfg", "/setactive", "SCHEME_CURRENT")
        query() == state
    }.getOrDefault(false)

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        process.waitFor(10, TimeUnit.SECONDS)
        return output
    }
}
