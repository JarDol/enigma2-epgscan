package pl.jardol.e2stream.desktop

import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpgAudioTest {
    @Test
    fun parsesEpgEventsSkippingEmptyTitlesAndNoneTexts() {
        val json = JSONObject(
            """
            {"events": [
              {"id": 2, "begin_timestamp": 200, "duration_sec": 60, "title": "Druga", "shortdesc": "None",
               "longdesc": "Opis &amp; więcej", "sref": "1:0:1:A:", "sname": "TVP 1"},
              {"id": 1, "begin_timestamp": 100, "duration_sec": 100, "title": "Pierwsza"},
              {"id": 3, "begin_timestamp": 300, "title": ""}
            ]}
            """.trimIndent(),
        )

        val events = parseEpgEvents(json)

        assertEquals(listOf(1, 2), events.map { it.id })
        assertEquals(260, events[1].end)
        assertEquals("", events[1].shortDesc)
        assertEquals("Opis & więcej", events[1].longDesc)
    }

    @Test
    fun matchesSavedAudioByPidThenByLanguageAndCodec() {
        val tracks = listOf(
            AudioTrack(311, "pl", "pol", "mpga"),
            AudioTrack(312, "?", "", "mpga"),
            AudioTrack(313, "en AC3", "eng", "a52"),
        )

        assertEquals(312, matchAudioChoice(tracks, "312|pol|mpga")?.id)
        assertEquals(311, matchAudioChoice(tracks, "999|pl|audio/mpeg")?.id)
        assertEquals(313, matchAudioChoice(tracks, "999|en|")?.id)
        assertNull(matchAudioChoice(tracks, "999|de|"))
        assertEquals("de", AudioTrackLabels.languageCode("ger"))
    }

    @Test
    fun keepsDefaultsForRemoteKeysMissingFromSavedBindings() {
        val bindings = UniversalRemoteMap.parse("""{"OK": ["K:13"]}""")

        assertEquals(setOf("K:13"), bindings[VirtualRemoteKey.OK])
        assertEquals(UniversalRemoteMap.defaults[VirtualRemoteKey.VOLUME_UP].orEmpty(), bindings[VirtualRemoteKey.VOLUME_UP])
        assertTrue(bindings[VirtualRemoteKey.VOLUME_UP].orEmpty().isNotEmpty())
    }

    @Test
    fun decidesWhenEpgSyncIsDue() {
        val now = LocalDateTime.of(2025, 3, 10, 12, 0)
        fun millis(time: LocalDateTime) = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val yesterday = millis(now.minusDays(1))
        val config = DesktopConfig(epgHour = "05:00", lastEpgFetch = yesterday)

        assertEquals(now.withHour(5), EpgSync.lastScheduled("05:00", now))
        assertEquals(now.minusDays(1).withHour(18), EpgSync.lastScheduled("18:00", now))
        assertTrue(EpgSync.isDue(config, usesPlugin = false, startupDone = false, now = now))
        assertFalse(EpgSync.isDue(config.copy(epgDaily = false), usesPlugin = false, startupDone = false, now = now))
        assertFalse(EpgSync.isDue(config.copy(lastEpgFetch = millis(now.withHour(6))), usesPlugin = false, startupDone = false, now = now))
        assertTrue(EpgSync.isDue(config, usesPlugin = true, startupDone = true, now = now))
        assertFalse(EpgSync.isDue(config.copy(lastEpgFetch = millis(now.withHour(1))), usesPlugin = true, startupDone = false, now = now))
        val startup = config.copy(epgUpdateMode = DesktopConfig.EPG_UPDATE_STARTUP, lastEpgFetch = millis(now))
        assertTrue(EpgSync.isDue(startup, usesPlugin = true, startupDone = false, now = now))
        assertFalse(EpgSync.isDue(startup, usesPlugin = true, startupDone = true, now = now))
    }
}
