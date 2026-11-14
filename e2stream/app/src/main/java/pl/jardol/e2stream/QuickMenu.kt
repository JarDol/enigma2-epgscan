package pl.jardol.e2stream

object QuickMenu {
    data class Entry(val id: String, val labelRes: Int)

    val configurable = listOf(
        Entry("event", R.string.menu_event_epg),
        Entry("channel_epg", R.string.menu_channel_epg),
        Entry("multi_epg", R.string.menu_multi_epg),
        Entry("search", R.string.menu_epg_search),
        Entry("audio", R.string.menu_audio_track),
        Entry("zap_timers", R.string.menu_scheduled_switches),
        Entry("decoder_timers", R.string.menu_receiver_timers),
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
