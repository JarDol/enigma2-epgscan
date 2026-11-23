package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryOrderTest {
    private val history = listOf("a:" to "A", "b:" to "B", "c:" to "C", "d:" to "D")

    @Test fun sortsByLiveStateWithoutChangingWatchingOrder() {
        val sorted = HistoryOrder.sort(history, setOf("A", "B", "C"), setOf("B"))
        assertEquals(listOf("a:", "c:", "b:", "d:"), sorted.map { it.first })
        assertEquals(listOf("a:", "b:", "c:", "d:"), history.map { it.first })
        val changed = HistoryOrder.sort(history, setOf("A", "B"), setOf("C"))
        assertEquals(listOf("a:", "b:", "c:", "d:"), changed.map { it.first })
    }

    @Test fun appendsOnlyNewPipChannelsAndRespectsHistoryLimit() {
        assertEquals(history, HistoryOrder.append(history, " B ", "renamed", 4))
        assertEquals(listOf("a:", "b:", "c:", "e:"),
            HistoryOrder.append(history, "e:", "E", 4).map { it.first })
        assertEquals(history, HistoryOrder.append(history, "", "empty", 4))
    }

    @Test fun keepsSelectedChannelAfterStateChanges() {
        val sorted = HistoryOrder.sort(history, setOf("D"), setOf("A"))
        assertEquals(2, HistoryOrder.selectedIndex(sorted.map { it.first }, "B"))
        assertEquals(0, HistoryOrder.selectedIndex(sorted.map { it.first }, "missing"))
    }
}
