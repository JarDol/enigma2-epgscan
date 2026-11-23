package pl.jardol.e2stream

import android.app.Application
import android.content.res.Configuration
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TrackDescriptionsTest {
    private fun text(locale: Locale): (Int) -> String {
        val context = RuntimeEnvironment.getApplication()
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
        return { localized.getString(it) }
    }

    private fun translate(value: String, locale: Locale = Locale("pl")) =
        TrackDescriptions.localize(value, text(locale))

    @Test fun standardDescriptionsUsePolishAndEnglishResources() {
        val cases = listOf(
            Triple("DVB subtitles", "Napisy DVB", "DVB subtitles"),
            Triple("Teletext subtitles", "Napisy teletekstowe", "Teletext subtitles"),
            Triple("Audio description for the visually impaired", "Audiodeskrypcja", "Audio description"),
            Triple("Visually impaired", "Audiodeskrypcja", "Audio description"),
            Triple("AD", "Audiodeskrypcja", "Audio description"),
            Triple("Hearing impaired", "Dla niesłyszących", "Hearing impaired"),
            Triple("SDH", "Dla niesłyszących", "Hearing impaired"),
            Triple("Closed captions", "Napisy CC", "Closed captions"),
            Triple("Forced subtitles", "Napisy wymuszone", "Forced subtitles"),
            Triple("Audio commentary", "Komentarz", "Commentary"),
            Triple("Original", "Oryginalna", "Original"),
        )
        for ((source, polish, english) in cases) {
            assertEquals(source, polish, translate(source))
            assertEquals(source, english, translate(source, Locale.ENGLISH))
        }
        assertEquals("Audiodeskrypcja", translate("  AUDIO  DESCRIPTION  "))
    }

    @Test fun translatesKnownPartsButPreservesProviderTextAndCodecNames() {
        assertEquals("Napisy DVB (Dla niesłyszących)", translate("DVB subtitles (hearing impaired)"))
        assertEquals("Napisy teletekstowe / Dla niesłyszących", translate("Teletext subtitles / SDH"))
        assertEquals("Stacja X - Napisy wymuszone", translate("Stacja X - Forced subtitles"))
        assertEquals("Napisy DVB (Stacja X)", translate("DVB subtitles (Stacja X)"))
        for (unknown in listOf("Stacja X", "Commentary by Studio X", "DVB subtitles special edition", "AAC", "AC3", "DTS", "audio/eac3")) {
            assertEquals(unknown, translate(unknown))
        }
    }

    @Test fun vlcSubtitleLabelsKeepTrackOrderAndOffPosition() {
        val tracks = listOf(17 to "DVB subtitles - [Polish]", -1 to "Disable",
            31 to "Teletext subtitles - [Polish]", 42 to "Stacja X - [English]")
        val selected = tracks.indexOfFirst { it.first == 31 }
        val labels = SubtitleTracks.vlcLabels(tracks, Locale("pl"), "Wyłączone",
            { translate(it) }) { "Ścieżka $it" }
        assertEquals(listOf("Polski  •  Napisy DVB", "Wyłączone", "Polski  •  Napisy teletekstowe",
            "Angielski  •  Stacja X"), labels)
        assertEquals("Polski  •  Napisy teletekstowe", labels[selected])
        assertEquals("Teletext subtitles - [Polish]", tracks[selected].second)
        assertEquals(listOf("Polish  •  DVB subtitles", "Off", "Polish  •  Teletext subtitles",
            "English  •  Stacja X"),
            SubtitleTracks.vlcLabels(tracks, Locale.ENGLISH, "Off",
                { translate(it, Locale.ENGLISH) }) { "Track $it" })
    }

    @Test fun translatedDuplicateNamesAreNumberedAndPlainLanguageNamesAreLocalized() {
        val tracks = listOf(-1 to "Disable", 3 to "Polish", 8 to "pol", 21 to "English")
        assertEquals(listOf("Wyłączone", "Polski (1)", "Polski (2)", "Angielski"),
            SubtitleTracks.vlcLabels(tracks, Locale("pl"), "Wyłączone",
                { translate(it) }) { "Ścieżka $it" })
    }

    @Test fun audioTranslationPreservesMetadataAndUnknownDescriptions() {
        fun label(language: String?, description: String) = AudioTrackLabels.displayLabel(
            language, "ac3", 6, description, Locale("pl"), "Oryginalna", "Ścieżka", "Audiodeskrypcja",
            descriptionLabel = { translate(it) })
        assertEquals("Polski  •  Audiodeskrypcja  •  AC3 6ch",
            label("pl", "Audio description for the visually impaired - [Polish]"))
        assertEquals("Polski  •  Komentarz  •  AC3 6ch", label(null, "Commentary - [Polish]"))
        assertEquals("Polski  •  Stacja X  •  AC3 6ch", label("pl", "Stacja X"))
        assertEquals("Oryginalna  •  AC3 6ch", label("ory", "Original"))
        assertEquals("Angielski  •  AC3 6ch", label(null, "English"))
    }
}
