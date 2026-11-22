package pl.jardol.e2stream.desktop

/**
 * Least recently parked buffers first. Protected buffers are never evicted by the capacity limit.
 * The owner closes returned buffers outside the UI thread.
 */
internal class BackgroundBuffers<T>(
    private val isProtected: (T) -> Boolean,
) {
    private val entries = LinkedHashMap<String, T>()

    fun snapshot(): List<Pair<String, T>> = entries.map { it.key to it.value }

    fun take(key: String): T? = entries.remove(key)

    fun park(key: String, value: T, limit: Int): List<T> {
        require(key !in entries)
        entries[key] = value
        return trim(limit)
    }

    fun trim(limit: Int): List<T> {
        val removed = mutableListOf<T>()
        while (entries.size > limit.coerceIn(0, 3)) {
            val victim = entries.entries.firstOrNull { !isProtected(it.value) } ?: break
            entries.remove(victim.key)
            removed += victim.value
        }
        return removed
    }

    fun drain(): List<T> = entries.values.toList().also { entries.clear() }
}
