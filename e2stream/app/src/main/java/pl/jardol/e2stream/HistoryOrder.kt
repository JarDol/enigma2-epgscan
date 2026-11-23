package pl.jardol.e2stream

internal object HistoryOrder {
    fun sort(
        history: List<Pair<String, String>>,
        buffers: Set<String>,
        pip: Set<String>,
    ): List<Pair<String, String>> = history.sortedBy { (ref, _) ->
        val key = OpenWebif.key(ref)
        when {
            key in pip -> 1
            key in buffers -> 0
            else -> 2
        }
    }

    fun append(history: List<Pair<String, String>>, ref: String, name: String, max: Int): List<Pair<String, String>> {
        if (ref.isBlank() || history.any { OpenWebif.key(it.first) == OpenWebif.key(ref) }) return history
        return history.take((max - 1).coerceAtLeast(0)) + listOf(ref to name).take(max.coerceAtLeast(0))
    }

    fun selectedIndex(refs: List<String>, selectedRef: String?): Int {
        if (selectedRef == null) return 0
        val key = OpenWebif.key(selectedRef)
        return refs.indexOfFirst { OpenWebif.key(it) == key }.coerceAtLeast(0)
    }
}
