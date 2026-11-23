package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

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

    @Test
    fun showsLanguageDescriptionAndCodecWithoutPid() {
        fun label(language: String?, description: String?, role: Boolean = false) =
            AudioTrackLabels.displayLabel(language, "mpga", 2, description, Locale("pl"),
                "Oryginalna", "Ścieżka", "Audiodeskrypcja", role)
        assertEquals("Polski  •  MPEG 2ch", label("pl", "Track 1 - [Polish]"))
        assertEquals("Oryginalna  •  MPEG 2ch", label("ory", null))
        assertEquals("Polski  •  Audio description for the visually impaired  •  MPEG 2ch",
            label("pl", "Audio description for the visually impaired - [Polish]"))
        assertEquals("Polski  •  Audiodeskrypcja  •  MPEG 2ch", label("pl", null, true))
        assertEquals("Polski  •  MPEG 2ch", label(null, "Track 1 - [Polish]"))
    }

    @Test
    fun unknownTrackUsesLocalizedFallbackAndEnglishLanguageNames() {
        assertEquals("Track", AudioTrackLabels.displayLabel(null, null, 0, null,
            Locale.ENGLISH, "Original", "Track", "Audio description"))
        assertEquals("Polish", AudioTrackLabels.displayLabel("pl", null, 0, null,
            Locale.ENGLISH, "Original", "Track", "Audio description"))
    }
}
