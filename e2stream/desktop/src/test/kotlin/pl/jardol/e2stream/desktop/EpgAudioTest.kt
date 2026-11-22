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
    fun epgConfirmationRunsActionOnlyWhenConfirmed() {
        var actions = 0
        var returns = 0
        val pick = epgConfirmation("Przełączyć?", "Kanał", "Przełącz", 30, 1f, { returns++ }, { actions++ })
        pick.onSelect(1)
        assertEquals(0, actions)
        assertEquals(1, returns)
        pick.onBack?.invoke()
        assertEquals(2, returns)
        pick.onSelect(0)
        assertEquals(1, actions)
    }

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
        // Zapis z ExoPlayera na Androidzie: „1/312”.
        assertEquals(312, matchAudioChoice(tracks, "1/312|pl|audio/mpeg")?.id)
    }

    @Test
    fun buildsReadableTrackNamesFromVlcDescriptions() {
        val text = fixMojibake("ĹšcieĹĽka 1 - [Polski]")
        assertEquals("Ścieżka 1 - [Polski]", text)
        assertEquals("Ścieżka 1 - [Polski]", fixMojibake(text))
        val language = AudioTrackLabels.languageCode(AudioTrackLabels.languageFromDescription(text))
        assertEquals("pl", language)
        assertEquals("Polski  •  MPEG 2ch  •  PID 132", AudioTrackLabels.trackName(132, language, "mpga", 2, text))
        assertEquals("Oryginalna  •  PID 734", AudioTrackLabels.trackName(734, "ORY", "", 0, "Ścieżka 2 - [ORY]"))
        assertEquals(
            "Polski  •  Audio description for the visually impaired  •  PID 1234",
            AudioTrackLabels.trackName(1234, "pl", "", 0, "Audio description for the visually impaired - [Polski]"),
        )
        val tracks = listOf(AudioTrack(734, "", "ory", ""), AudioTrack(132, "", "pl", ""))
        assertEquals(132, preferredAudioTrack(tracks, AudioTrackLabels.preferredLanguages("pol,en"))?.id)
    }

    @Test
    fun plansAudioSyncLikeAndroid() {
        val local = mapOf("A" to SyncEntry("1|pl|", 100), "B" to SyncEntry("2|pl|", 300))
        val device = mapOf("A" to SyncEntry("5|en|", 200), "B" to SyncEntry("3|pl|", 50))
        val common = mapOf("A" to SyncEntry("9||", 999), "C" to SyncEntry("7|pl|", 10), "D" to SyncEntry("8|pl|", 1))

        val plan = planAudioSync(local, device, common, clearedAt = 5)

        // Plik odbiornika: nowszy wygrywa; część wspólna tylko dla brakujących; „zapomnij” odcina starsze.
        assertEquals(mapOf("A" to SyncEntry("5|en|", 200), "C" to SyncEntry("7|pl|", 10)), plan.apply)
        assertEquals(setOf("B", "C"), plan.push.keys)
    }

    @Test
    fun mergesRecordingDescriptionsWithoutRepeats() {
        val short = "Tytuł oryginalny: Aftersun"
        val extended = "Tytuł oryginalny: Aftersun\nDramat, Wielka Brytania 2022\ntytuł oryginalny: aftersun\nDramat"
        assertEquals("Tytuł oryginalny: Aftersun\nDramat, Wielka Brytania 2022\nDramat", mergeDescriptions(short, extended))
    }

    @Test
    fun dropsShortDescriptionLineRepeatingTheEndOfAnother() {
        assertEquals(
            "Tytuł oryginalny: Teleexpress Extra PL, 2026",
            mergeDescriptions("Tytuł oryginalny: Teleexpress Extra PL, 2026", "PL, 2026"),
        )
    }

    @Test
    fun namesCommonLanguagesInPolishWithoutLocaleData() {
        assertEquals("Polski", AudioTrackLabels.displayLanguage("Polski"))
        assertEquals("Angielski", AudioTrackLabels.displayLanguage("eng"))
        assertEquals("Niemiecki", AudioTrackLabels.displayLanguage("ger"))
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
