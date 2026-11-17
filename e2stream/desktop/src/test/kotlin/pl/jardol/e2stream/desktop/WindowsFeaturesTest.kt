package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsFeaturesTest {
    @Test
    fun historyMovesChannelToFrontWithoutDuplicates() {
        var json = "[]"
        json = ChannelHistory.push(json, HistoryEntry("1:0:1:A:", "TVP 1", "b1"))
        json = ChannelHistory.push(json, HistoryEntry("1:0:1:B:", "TVP 2", "b1"))
        json = ChannelHistory.push(json, HistoryEntry("1:0:1:a", "TVP 1 HD", "b2"))
        val entries = ChannelHistory.parse(json)
        assertEquals(listOf("TVP 1 HD", "TVP 2"), entries.map { it.name })
        assertEquals("b2", entries.first().bouquet)
        repeat(60) { json = ChannelHistory.push(json, HistoryEntry("1:0:1:$it:", "K$it", "b")) }
        assertEquals(ChannelHistory.LIMIT, ChannelHistory.parse(json).size)
        assertEquals(emptyList(), ChannelHistory.parse("nie json"))
    }

    @Test
    fun powerButtonParsesLastTwoValuesRegardlessOfLanguage() {
        val polish = """
            Identyfikator GUID schematu zasilania: 381b4222-f694-41f0-9685-ff5bb260df2e  (Zrównoważony)
              Identyfikator GUID podgrupy: 4f971e89-eebd-4455-a8de-9e59040e7347  (Przyciski zasilania i pokrywa)
                Indeks możliwego ustawienia: 000
                Przyjazna nazwa możliwego ustawienia: Nic nie rób
                Indeks możliwego ustawienia: 003
            Indeks bieżącego ustawienia zasilania prądem przemiennym: 0x00000003
            Indeks bieżącego ustawienia zasilania prądem stałym: 0x00000001
        """.trimIndent()
        assertEquals(WindowsPowerButton.State(3, 1), WindowsPowerButton.parse(polish))
        assertNull(WindowsPowerButton.parse("brak"))
        assertEquals("Zamknij system (zasilacz) / Uśpij (bateria)", WindowsPowerButton.describe(WindowsPowerButton.State(3, 1)))
    }

    @Test
    fun bufferBannerShowsSpanAndDelay() {
        assertEquals("B 12:34", bufferBannerText(754_000, 500))
        assertEquals("B 12:34 · −1:05", bufferBannerText(754_000, 65_000))
    }
}
