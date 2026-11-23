package pl.jardol.e2stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTracksTest {
    @Test fun hidesTeletextPagesButPreservesSubtitlesAndOff() {
        assertTrue(SubtitleTracks.isTeletextPage("Teletext - [Polish]"))
        assertTrue(SubtitleTracks.isTeletextPage("TELETEXT"))
        assertFalse(SubtitleTracks.isTeletextPage("Teletext subtitles - [Polish]"))
        assertFalse(SubtitleTracks.isTeletextPage("DVB subtitles - [Polish]"))
        assertFalse(SubtitleTracks.isTeletextPage("Disable"))
        assertFalse(SubtitleTracks.isTeletextPage(null))
    }
}
