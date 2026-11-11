package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickMenuTest {
    @Test
    fun storedOrderIsAppliedAndUnknownOrDuplicateIdsAreIgnored() {
        val order = QuickMenu.allInOrder(listOf("search", "audio", "unknown", "search"))

        assertEquals(
            listOf("search", "audio", "event", "channel_epg", "multi_epg", "zap_timers", "decoder_timers"),
            order.map { it.id },
        )
    }

    @Test
    fun hiddenMenuItemsAreExcludedWithoutReorderingVisibleItems() {
        val visible = QuickMenu.allInOrder(listOf("search", "audio", "event"))
            .filter { it.id in setOf("audio", "search") }

        assertEquals(listOf("search", "audio"), visible.map { it.id })
    }
}
