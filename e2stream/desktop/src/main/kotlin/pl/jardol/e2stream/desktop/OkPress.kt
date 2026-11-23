package pl.jardol.e2stream.desktop

/** Czas przytrzymania OK, po którym działa akcja długiego naciśnięcia (jak w Androidzie). */
internal const val OK_LONG_MS = 600L

internal data class OkPressContext(val surface: String, val item: String = "", val selected: Int = -1)

/**
 * Krótkie i długie naciśnięcie OK: krótkie działa po puszczeniu klawisza, długie po [longMs] trzymania
 * (wtedy puszczenie niczego już nie robi). Powtórzenia klawisza w trakcie trzymania są pomijane.
 */
internal class OkPress(private val longMs: Long = OK_LONG_MS, private val maxHoldMs: Long = 10_000) {
    private var downAt = 0L
    private var fired = false
    private var context: OkPressContext? = null

    val isDown: Boolean get() = downAt != 0L

    /** Naciśnięcie; true = nowe naciśnięcie, false = powtórzenie trzymanego klawisza. */
    fun down(now: Long, context: OkPressContext? = null): Boolean {
        if (downAt != 0L && now - downAt < maxHoldMs) return false
        downAt = now
        fired = false
        this.context = context
        return true
    }

    /** Sprawdzenie po [longMs]: true = wykonać akcję długiego naciśnięcia (raz na naciśnięcie). */
    fun longDue(now: Long, context: OkPressContext? = null): Boolean {
        if (downAt == 0L || fired || this.context != context || now - downAt < longMs) return false
        fired = true
        return true
    }

    /** Puszczenie: true = wykonać akcję krótkiego naciśnięcia. */
    fun up(context: OkPressContext? = null): Boolean {
        if (downAt == 0L) return false
        val short = !fired && this.context == context
        downAt = 0L
        fired = false
        this.context = null
        return short
    }

    fun cancel() {
        downAt = 0L
        fired = false
        context = null
    }
}
