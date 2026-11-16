package pl.jardol.e2stream

object QuickMenu {
    data class Entry(val id: String, val labelRes: Int)

    val configurable = listOf(
        Entry("record", R.string.player_localrec_menu),
        Entry("record_stop", R.string.player_localrec_stop),
        Entry("event", R.string.menu_event_epg),
        Entry("channel_epg", R.string.menu_channel_epg),
        Entry("multi_epg", R.string.menu_multi_epg),
        Entry("search", R.string.menu_epg_search),
        Entry("audio", R.string.menu_audio_track),
        Entry("zap_timers", R.string.menu_scheduled_switches),
        Entry("decoder_timers", R.string.menu_receiver_timers),
        Entry("buffered", R.string.player_bg_menu),
        Entry("stats", R.string.setting_on_screen_statistics),
        Entry("send_log", R.string.action_send_log),
    )

    /** Pozycje dodane później — widoczne domyślnie także przy starszym zapisie widoczności. */
    val ADDED_LATER = setOf("buffered", "stats", "send_log")

    private val byId = configurable.associateBy { it.id }

    fun allInOrder(order: List<String>): List<Entry> {
        val stored = order.filter { it in byId }.distinct()
        val missing = configurable.map { it.id }.filterNot { it in stored }
        // nowe pozycje nagrywania na górę, pozostałe nowe na koniec
        val ids = missing.filter { it in TOP } + stored + missing.filterNot { it in TOP }
        return ids.mapNotNull(byId::get)
    }

    /** Pozycje dodane w 0.18.45 (nagrania lokalne) — na górze i widoczne także przy zapisanej kolejności. */
    val TOP = setOf("record", "record_stop")

    fun ordered(prefs: Prefs): List<Entry> =
        allInOrder(prefs.quickMenuOrder).filter { it.id in prefs.quickMenuVisible }

    fun saveOrder(prefs: Prefs, entries: List<Entry>) { prefs.quickMenuOrder = entries.map { it.id } }

}
