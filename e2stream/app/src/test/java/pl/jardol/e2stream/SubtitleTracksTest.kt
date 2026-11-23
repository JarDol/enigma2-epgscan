package pl.jardol.e2stream

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTracksTest {
    private fun labels(vararg tracks: Pair<String?, String?>, locale: Locale = Locale("pl")) =
        SubtitleTracks.displayLabels(tracks.toList(), locale) { "Subtitle track $it" }

    @Test fun hidesTeletextPagesButPreservesSubtitlesAndOff() {
        assertTrue(SubtitleTracks.isTeletextPage("Teletext - [Polish]"))
        assertTrue(SubtitleTracks.isTeletextPage("TELETEXT"))
        assertFalse(SubtitleTracks.isTeletextPage("Teletext subtitles - [Polish]"))
        assertFalse(SubtitleTracks.isTeletextPage("DVB subtitles - [Polish]"))
        assertFalse(SubtitleTracks.isTeletextPage("Disable"))
        assertFalse(SubtitleTracks.isTeletextPage(null))
    }

    @Test fun localizesLanguageAndHidesTechnicalNamesWithoutGuessingSourceFormat() {
        assertEquals(listOf("Polski"), labels("pl" to null))
        assertEquals(listOf("Polski"), labels("pol" to "application/x-media3-cues"))
        assertEquals(listOf("Polski"), labels("pl" to "PID 6030"))
        assertEquals(listOf("English"), labels("eng" to null, locale = Locale.ENGLISH))
    }

    @Test fun preservesMeaningfulDescriptionsAndNumbersOnlyDuplicateNames() {
        assertEquals(listOf("Polski (1)", "Polski (2)", "Polski  •  Dla niesłyszących"),
            labels("pl" to "Polish", "pol" to "Track 2", "pl" to "Dla niesłyszących"))
        assertEquals(listOf("Polski  •  DVB subtitles"), labels(null to "DVB subtitles - [Polish]"))
        assertEquals(listOf("Polski  •  Teletext subtitles"), labels(null to "Teletext subtitles - [Polish]"))
    }

    @Test fun unknownLanguageHasReadableFallbackAndTrackNumbers() {
        assertEquals(listOf("Subtitle track 1", "Subtitle track 2"),
            labels("und" to null, null to "application/x-media3-cues"))
    }
}
