package pl.jardol.e2stream

object QuickMenu {
    data class Entry(val id: String, val labelRes: Int)

    /** Kolejność domyślna (nowa instalacja, przywrócenie domyślnych). */
    val configurable = listOf(
        Entry("record", R.string.player_localrec_menu),
        Entry("record_stop", R.string.player_localrec_stop),
        Entry("pip", R.string.pip_title),
        Entry("buffered", R.string.player_bg_menu),
        Entry("audio", R.string.menu_audio_track),
        Entry("av_shift", R.string.setting_av_shift),
        Entry("event", R.string.menu_event_epg),
        Entry("channel_epg", R.string.menu_channel_epg),
        Entry("multi_epg", R.string.menu_multi_epg),
        Entry("search", R.string.menu_epg_search),
        Entry("zap_timers", R.string.menu_scheduled_switches),
        Entry("decoder_timers", R.string.menu_receiver_timers),
        Entry("engine", R.string.action_engine),
        Entry("tunneling", R.string.action_tunneling),
        Entry("stats", R.string.setting_on_screen_statistics),
        Entry("send_log", R.string.action_send_log),
        Entry("sleep", R.string.sleep_timer_menu),
        Entry("timed_shutdown", R.string.timed_shutdown_title),
    )

    /** Domyślnie ukryte (EPG jest pod klawiszami i na liście kanałów). */
    val HIDDEN_BY_DEFAULT = setOf("event", "channel_epg", "multi_epg", "search", "engine", "tunneling")

    /** Pozycje dodane później — widoczne domyślnie także przy starszym zapisie widoczności. */
    val ADDED_LATER = setOf("buffered", "stats", "send_log", "av_shift", "pip", "sleep", "timed_shutdown")

    private val byId = configurable.associateBy { it.id }

    fun allInOrder(order: List<String>): List<Entry> {
        val ids = order.filter { it in byId }.distinct().toMutableList()
        val defaults = configurable.map { it.id }
        // brakujące (nowe) pozycje wstawiane w miejscu domyślnym: przed najbliższą
        // następną pozycją domyślną obecną w zapisanej kolejności, a gdy jej brak — na koniec
        for ((i, id) in defaults.withIndex()) {
            if (id in ids) continue
            val next = defaults.drop(i + 1).firstOrNull { it in ids }
            if (next == null) ids.add(id) else ids.add(ids.indexOf(next), id)
        }
        return ids.mapNotNull(byId::get)
    }

    /** Pozycje dodane w 0.18.45 (nagrania lokalne) — na górze i widoczne także przy zapisanej kolejności. */
    val TOP = setOf("record", "record_stop")

    fun ordered(prefs: Prefs): List<Entry> =
        allInOrder(prefs.quickMenuOrder).filter { it.id in prefs.quickMenuVisible }

    fun saveOrder(prefs: Prefs, entries: List<Entry>) { prefs.quickMenuOrder = entries.map { it.id } }

}
