package pl.jardol.e2stream

internal class IdleTimeout(
    private val seconds: () -> Int,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    onTimeout: () -> Unit,
) {
    private var active = false
    private val close = Runnable { if (active) onTimeout() }

    fun setActive(value: Boolean) {
        active = value
        reset()
    }

    fun reset() {
        cancel(close)
        val delay = seconds().coerceIn(0, 600)
        if (active && delay > 0) schedule(close, delay * 1000L)
    }
}
