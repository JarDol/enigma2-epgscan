package pl.jardol.e2stream.desktop

internal class HidUsageRegistry(
    private val register: (Set<Pair<Short, Short>>) -> Unit,
) {
    private val registered = mutableSetOf<Pair<Short, Short>>()

    fun registerNew(usages: Collection<Pair<Short, Short>>) {
        val added = usages.toSet() - registered
        if (added.isEmpty()) return
        registered.addAll(added)
        try {
            register(added)
        } catch (e: Exception) {
            registered.removeAll(added)
            throw e
        }
    }
}
