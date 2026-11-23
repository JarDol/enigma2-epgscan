package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.io.path.createTempDirectory

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
    fun historyPrioritizesBuffersThenPipAndRetainsViewingOrder() {
        val entries = listOf(
            HistoryEntry("1:0:1:A:", "Oglądany", "b1"),
            HistoryEntry("1:0:1:C:", "Stary", "b2"),
            HistoryEntry("1:0:1:B:", "Buforowany", "b1"),
            HistoryEntry("1:0:1:D:", "PiP wcześniej", "b3"),
        )
        val result = ChannelHistory.addPip(
            ChannelHistory.encode(entries),
            HistoryEntry("1:0:1:d", "PiP", "b4"),
        )
        val history = ChannelHistory.parse(result)
        assertEquals(listOf("Oglądany", "Stary", "Buforowany", "PiP"), history.map { it.name })
        assertEquals("b4", history.last().bouquet)
        val pip = setOf(serviceKey(history.last().ref))
        val buffered = setOf(serviceKey(entries[0].ref), serviceKey(entries[2].ref))
        assertEquals(listOf("Oglądany", "Buforowany", "PiP", "Stary"),
            ChannelHistory.ordered(history, buffered, pip).map { it.name })
        assertEquals(listOf("Oglądany", "PiP", "Stary", "Buforowany"),
            ChannelHistory.ordered(history, setOf(serviceKey(entries[0].ref)), pip).map { it.name })
        assertEquals(listOf("Oglądany", "Stary", "Buforowany", "PiP"),
            ChannelHistory.ordered(history, emptySet(), emptySet()).map { it.name })
        val added = ChannelHistory.parse(ChannelHistory.addPip(result, HistoryEntry("1:0:1:E:", "Nowy PiP", "b1")))
        assertEquals("Nowy PiP", added.last().name)
    }

    @Test
    fun historyShowsOnlyCurrentLocallyCachedProgramme() {
        val dir = createTempDirectory("history-epg").toFile()
        try {
            val now = System.currentTimeMillis() / 1000
            val event = EpgEvent(1, now - 60, 120, "Audycja", "", "", "1:0:1:A:", "")
            val repo = EpgRepository(EpgStore(dir))
            repo.store.write(event.sref, listOf(event))

            assertEquals(
                "${EpgTime.range(event.begin, event.end)}  ·  Audycja",
                historyProgramme(repo.nowNext("1:0:1:a", now).first),
            )
            assertNull(historyProgramme(repo.nowNext(event.sref, event.end).first))
            assertNull(historyProgramme(repo.nowNext("1:0:1:B:", now).first))
            assertNull(historyProgramme(event.copy(title = " ")))
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    @Test
    fun longOkEventPrefersCurrentAndFallsBackToNext() {
        val past = EpgEvent(1, 100, 50, "Past", "", "", "ref", "")
        val current = past.copy(id = 2, begin = 150, duration = 50, title = "Current")
        val next = past.copy(id = 3, begin = 200, duration = 50, title = "Next")
        val events = listOf(past, current, next)
        assertEquals(current, currentOrNextEpgEvent(events, 175))
        assertEquals(next, currentOrNextEpgEvent(listOf(past, next), 175))
        assertNull(currentOrNextEpgEvent(events, 250))
    }

    @Test
    fun historyProgrammeUpdatePreservesSelectionAndIgnoresOldWindows() {
        val pick = QuickPick(
            title = "Historia kanałów",
            options = listOf("TVP 1", "TVP 2"),
            selected = 1,
            subtitles = listOf("oglądany · Bukiet", "Bukiet"),
            tag = "history-new",
            translucentBackground = true,
            iconChannels = listOf(Channel(0, "TVP 1", "1:0:1:A:"), Channel(0, "TVP 2", "1:0:1:B:")),
            iconStyle = PiconStyle.PICONS,
        ) {}

        assertSame(pick, updateHistoryProgrammes(pick, "history-old", listOf("Stara audycja")))
        assertNull(updateHistoryProgrammes(null, "history-new", listOf("Audycja")))
        val updated = updateHistoryProgrammes(pick, "history-new", listOf("Audycja", null))!!
        assertEquals(1, updated.selected)
        assertEquals(pick.subtitles, updated.subtitles)
        assertEquals(pick.iconChannels, updated.iconChannels)
        assertEquals(pick.iconStyle, updated.iconStyle)
        assertEquals(pick.translucentBackground, updated.translucentBackground)
        assertEquals(listOf("Audycja", null), updated.details)
        assertSame(pick.onSelect, updated.onSelect)
    }

    @Test
    fun piconPackIdentityDetectsChangedHashesAndReceivers() {
        val first = Picons.packIdentity(" receiver ", 8088, 1, 100, "ABC")
        assertEquals(first, Picons.packIdentity("receiver", 8088, 2, 200, "abc"))
        kotlin.test.assertNotEquals(first, Picons.packIdentity("receiver", 8088, 1, 100, "def"))
        kotlin.test.assertNotEquals(first, Picons.packIdentity("other", 8088, 1, 100, "abc"))
        kotlin.test.assertNotEquals(
            Picons.packIdentity("receiver", 8088, 1, 100, ""),
            Picons.packIdentity("receiver", 8088, 2, 100, ""),
        )
        assertNull(Picons.packIdentity("receiver", 8088, 1, 0, "abc"))
    }

    @Test
    fun piconPackUpdatesExistingIconsOnlyAfterHashVerification() {
        val dir = createTempDirectory("picon-pack").toFile()
        val pack = dir.resolve("pack.zip")
        val icon = dir.resolve("channel.png").apply { writeText("old") }
        try {
            java.util.zip.ZipOutputStream(pack.outputStream()).use {
                it.putNextEntry(java.util.zip.ZipEntry(icon.name))
                it.write("new".toByteArray())
                it.closeEntry()
            }
            kotlin.test.assertFailsWith<java.io.IOException> { Picons.importPack(pack, dir, "bad-hash") }
            assertEquals("old", icon.readText())
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(pack.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(1, Picons.importPack(pack, dir, sha))
            assertEquals("new", icon.readText())
        } finally {
            icon.delete()
            pack.delete()
            dir.delete()
        }
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
        assertEquals("B 12:34 · tło 2/3", bufferBannerText(754_000, 500, 2, 3))
        assertEquals("B 12:34 · tło 2/3 (nagr. 1)", bufferBannerText(754_000, 500, 2, 3, 1))
        assertEquals("tło 2/3 (nagr. 2)", bufferBannerText(null, 0, 2, 3, 2))
        assertNull(bufferBannerText(null, 0))
    }

    @Test
    fun channelPlaybackBadgesPrioritizePictureInPictureAndMarkRecording() {
        assertEquals("B", channelPlaybackBadge(buffered = true, pipNumber = null)?.text)
        assertEquals("PiP 2", channelPlaybackBadge(buffered = true, pipNumber = 2)?.text)
        assertEquals("● PiP 2", channelPlaybackBadge(buffered = true, pipNumber = 2, recording = true)?.text)
        assertEquals(null, channelPlaybackBadge(buffered = false, pipNumber = null))
    }

    @Test
    fun channelPipActionFollowsCurrentState() {
        assertEquals(ChannelPipAction.ADD, channelPipAction(enabled = true, current = false, inPip = false))
        assertEquals(ChannelPipAction.CLOSE, channelPipAction(enabled = true, current = false, inPip = true))
        assertEquals(ChannelPipAction.CLOSE, channelPipAction(enabled = false, current = false, inPip = true))
        assertNull(channelPipAction(enabled = true, current = true, inPip = false))
        assertNull(channelPipAction(enabled = false, current = false, inPip = false))
    }
}
