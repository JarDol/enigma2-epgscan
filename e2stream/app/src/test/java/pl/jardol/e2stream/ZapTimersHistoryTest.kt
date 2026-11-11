package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Test

class ZapTimersHistoryTest {
    private val now = 2_000_000L
    private val timer = ZapTimers.Timer("1:0:1:", "Kanał", "Audycja", 100, 200)

    @Test
    fun retentionKeepsEntriesWithinSelectedDayWindow() {
        val entries = listOf(
            ZapTimers.History(timer, ZapTimers.SWITCHED, now - 7 * 86_400L),
            ZapTimers.History(timer, ZapTimers.SKIPPED, now - 7 * 86_400L - 1),
            ZapTimers.History(timer, ZapTimers.RECORDED, now),
        )

        assertEquals(
            listOf(now, now - 7 * 86_400L),
            ZapTimers.retainHistory(entries, now, 7).map { it.completedAt },
        )
    }

    @Test
    fun zeroRetentionKeepsAllEntries() {
        val entries = listOf(
            ZapTimers.History(timer, ZapTimers.SWITCHED, 1),
            ZapTimers.History(timer, ZapTimers.RECORDED, now),
        )

        assertEquals(2, ZapTimers.retainHistory(entries, now, 0).size)
    }

    @Test
    fun removesOnlyMatchingHistoryEntry() {
        val switched = ZapTimers.History(timer, ZapTimers.SWITCHED, 1000)
        val skipped = ZapTimers.History(timer, ZapTimers.SKIPPED, 1000)
        val laterSwitch = ZapTimers.History(timer, ZapTimers.SWITCHED, 2000)

        assertEquals(
            listOf(skipped, laterSwitch),
            ZapTimers.withoutHistoryEntry(listOf(switched, skipped, laterSwitch), switched),
        )
    }
}
