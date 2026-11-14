package pl.jardol.e2stream.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZapTimersTest {
    @Test
    fun completesReplacesAndRetainsHistory() {
        val directory = Files.createTempDirectory("e2stream-zap")
        val path = directory.resolve("zaptimers.json")
        var now = 1_000_000L
        val store = ZapTimerStore(path) { now }
        val first = ZapTimer("1:0:1:A:2:3:0:0:0:0:", "TVP 1", "Wiadomości", now + 600, now + 1800, 11)
        val second = ZapTimer("1:0:1:B:2:3:0:0:0:0:", "TVP 2", "Film", now + 600, now + 3600)
        val later = ZapTimer("1:0:1:C:2:3:0:0:0:0:", "Polsat", "Mecz", now + 7200, now + 9000)
        try {
            store.add(later)
            store.add(first)
            assertEquals(listOf(first, later), store.list())
            assertTrue(store.has(first.ref, first.begin))

            store.replaceAt(second)
            assertEquals(listOf(second, later), store.list())
            assertEquals(listOf(ZapOutcome.REPLACED), store.history(28).map { it.outcome })

            now += 10 * 86_400L
            store.complete(second, ZapOutcome.SWITCHED)
            assertEquals(listOf(ZapOutcome.SWITCHED, ZapOutcome.REPLACED), store.history(0).map { it.outcome })
            assertEquals(listOf(ZapOutcome.SWITCHED), store.history(7).map { it.outcome })
            assertEquals(listOf(ZapOutcome.SWITCHED), ZapTimerStore(path) { now }.history(0).map { it.outcome })

            assertFalse(store.list().contains(later), "zakończone audycje nie są już na liście przyszłych")
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun resolvesApplicationLanguageAndTranslates() {
        assertEquals(AppLanguage.POLISH, AppLanguage.resolve(AppLanguage.SYSTEM, "pl"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(AppLanguage.SYSTEM, "de"))
        assertEquals(AppLanguage.POLISH, AppLanguage.resolve(AppLanguage.POLISH, "en"))
        try {
            AppLanguage.apply(AppLanguage.ENGLISH)
            assertEquals("Recordings", tr("Nagrania"))
            assertEquals("Bez tłumaczenia", tr("Bez tłumaczenia"))
            AppLanguage.apply(AppLanguage.POLISH)
            assertEquals("Nagrania", tr("Nagrania"))
        } finally {
            AppLanguage.apply(AppLanguage.SYSTEM)
        }
    }
}
