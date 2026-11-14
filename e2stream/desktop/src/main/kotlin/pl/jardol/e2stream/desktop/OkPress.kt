package pl.jardol.e2stream.desktop

/** Czas przytrzymania OK, po którym działa akcja długiego naciśnięcia (jak w Androidzie). */
internal const val OK_LONG_MS = 600L

/**
 * Krótkie i długie naciśnięcie OK: krótkie działa po puszczeniu klawisza, długie po [longMs] trzymania
 * (wtedy puszczenie niczego już nie robi). Powtórzenia klawisza w trakcie trzymania są pomijane.
 */
internal class OkPress(private val longMs: Long = OK_LONG_MS, private val maxHoldMs: Long = 10_000) {
    private var downAt = 0L
    private var fired = false

    val isDown: Boolean get() = downAt != 0L

    /** Naciśnięcie; true = nowe naciśnięcie, false = powtórzenie trzymanego klawisza. */
    fun down(now: Long): Boolean {
        if (downAt != 0L && now - downAt < maxHoldMs) return false
        downAt = now
        fired = false
        return true
    }

    /** Sprawdzenie po [longMs]: true = wykonać akcję długiego naciśnięcia (raz na naciśnięcie). */
    fun longDue(now: Long): Boolean {
        if (downAt == 0L || fired || now - downAt < longMs) return false
        fired = true
        return true
    }

    /** Puszczenie: true = wykonać akcję krótkiego naciśnięcia. */
    fun up(): Boolean {
        if (downAt == 0L) return false
        val short = !fired
        downAt = 0L
        fired = false
        return short
    }

    fun cancel() {
        downAt = 0L
        fired = false
    }
}
