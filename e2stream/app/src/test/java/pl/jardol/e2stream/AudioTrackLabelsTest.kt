package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTrackLabelsTest {
    @Test
    fun normalizesPolishCodeAndNameAliases() {
        assertEquals("pl", AudioTrackLabels.languageCode("pl"))
        assertEquals("pl", AudioTrackLabels.languageCode("pol"))
        assertEquals("pl", AudioTrackLabels.languageCode("Polish"))
    }

    @Test
    fun normalizesPreferredLanguageListWithoutDuplicates() {
        assertEquals(listOf("pl", "en"), AudioTrackLabels.preferredLanguages("pol,pl,Polish,eng,English"))
    }

    @Test
    fun convertsPreferredLanguagesToVlcIso6393Codes() {
        assertEquals(listOf("pol", "eng"), AudioTrackLabels.preferredVlcLanguages("pl,English,eng"))
    }

    @Test
    fun formatsAudioTrackLabelsConsistently() {
        assertEquals("pl MPEG 2ch", AudioTrackLabels.label("Polish", "mpga", 2))
        assertEquals("pl MPEG 2ch", AudioTrackLabels.label("pl", "audio/mpeg-L2", 2))
    }
}
