package pl.jardol.e2stream

object QuickMenu {
    data class Entry(val id: String, val label: String)

    val configurable = listOf(
        Entry("event", "EPG wydarzenia"),
        Entry("channel_epg", "EPG kanału"),
        Entry("multi_epg", "Multi EPG"),
        Entry("search", "Szukaj w EPG"),
        Entry("audio", "Ścieżka dźwięku…"),
        Entry("zap_timers", "Przełączenia wg harmonogramu"),
        Entry("decoder_timers", "Harmonogramy dekodera"),
    )

    private val byId = configurable.associateBy { it.id }

    fun allInOrder(order: List<String>): List<Entry> {
        val stored = order.filter { it in byId }.distinct()
        val ids = stored + configurable.map { it.id }.filterNot { it in stored }
        return ids.mapNotNull(byId::get)
    }

    fun ordered(prefs: Prefs): List<Entry> =
        allInOrder(prefs.quickMenuOrder).filter { it.id in prefs.quickMenuVisible }

    fun saveOrder(prefs: Prefs, entries: List<Entry>) { prefs.quickMenuOrder = entries.map { it.id } }

}
