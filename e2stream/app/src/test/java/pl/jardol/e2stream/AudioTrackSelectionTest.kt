package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTrackSelectionTest {
    @Test
    fun extractsPidFromMedia3TransportStreamTrackId() {
        assertEquals(790, mpegTsPid("4325/790"))
        assertEquals(0, mpegTsPid("4325/0"))
        assertNull(mpegTsPid(null))
        assertNull(mpegTsPid("4325"))
        assertNull(mpegTsPid("4325/8192"))
        assertNull(mpegTsPid("4325/not-a-pid"))
    }

    @Test
    fun preferredLanguageOrderWinsOverThePlayerDefault() {
        val tracks = listOf(
            AudioCandidate("772", "en", "audio/mpeg-L2", true),
            AudioCandidate("771", "pl", "audio/mpeg-L2", false),
        )
        assertEquals(AudioChoice(1, "preferowana"),
            chooseAudioTrack(tracks, "", listOf("pl", "en"), false, false))
        assertNull(chooseAudioTrack(tracks, "", listOf("fr"), true, false))
    }

    @Test
    fun waitsForSavedTrackAndSelectsItWhenItAppears() {
        val english = AudioCandidate("15207/772", "en", "audio/mpeg-L2", true)
        val polish = AudioCandidate("15207/771", "pl", "audio/mpeg-L2", false)
        val saved = "15207/771|pl|audio/mpeg-L2"
        assertNull(chooseAudioTrack(listOf(english), saved, listOf("pl"), false, false))
        assertEquals(AudioChoice(1, "zapamiętana"),
            chooseAudioTrack(listOf(english, polish), saved, listOf("pl"), false, false))
    }

    @Test
    fun savedTrackOverridesPreferredLanguageAcrossEngines() {
        val tracks = listOf(
            AudioCandidate("771", "pl", "mpga", true),
            AudioCandidate("772", "en", "mpga", false),
        )
        assertEquals(AudioChoice(1, "zapamiętana"),
            chooseAudioTrack(tracks, "15207/772|en|audio/mpeg-L2", listOf("pl"), false, false))
    }

    @Test
    fun fallsBackOnlyAfterWindowWhenSavedTrackIsUnavailable() {
        val tracks = listOf(
            AudioCandidate("1", "pl", "audio/mpeg-L2", false),
            AudioCandidate("2", "en", "audio/mpeg-L2", true),
        )
        val saved = "3|fr|audio/mpeg-L2"
        assertNull(chooseAudioTrack(tracks, saved, listOf("pl"), false, false))
        assertEquals(AudioChoice(0, "preferowana"),
            chooseAudioTrack(tracks, saved, listOf("pl"), true, false))
    }

    @Test
    fun preferredSelectionPreservesSelectedTrackAndDolbyPreference() {
        val tracks = listOf(
            AudioCandidate("1", "pl", "audio/mpeg-L2", false),
            AudioCandidate("2", "pl", "audio/ac3", false),
            AudioCandidate("3", "en", "audio/mpeg-L2", true),
        )
        assertEquals(AudioChoice(1, "preferowana"),
            chooseAudioTrack(tracks, "", listOf("pl"), false, true))
        val selected = tracks.mapIndexed { i, t -> t.copy(selected = i == 0) }
        assertEquals(AudioChoice(0, "preferowana"),
            chooseAudioTrack(selected, "", listOf("pl"), false, true))
    }

    @Test
    fun matchesSavedLanguageAndCodecWhenTrackIdChanges() {
        val tracks = listOf(
            AudioCandidate("20", "pl", "audio/ac3", false),
            AudioCandidate("21", "pol", "audio/mpeg-L2", false),
        )
        assertEquals(AudioChoice(1, "zapamiętana"),
            chooseAudioTrack(tracks, "19|pl|audio/mpeg-L2", listOf("en"), false, false))
    }

    @Test
    fun waitsExactlyTwoSecondsBeforeUsingPreferredFallback() {
        val window = AudioSelectionWindow()
        val tracks = listOf(AudioCandidate("771", "pl", "audio/mpeg-L2", false))
        val saved = "772|en|audio/mpeg-L2"
        assertEquals(null to 2000L, window.check(5000, tracks, saved, listOf("pl"), false))
        assertEquals(null to 1L, window.check(6999, tracks, saved, listOf("pl"), false))
        assertEquals(AudioChoice(0, "preferowana") to 0L,
            window.check(7000, tracks, saved, listOf("pl"), false))
    }

    @Test
    fun waitsForFirstPreferredLanguageBeforeChoosingSecond() {
        val english = listOf(AudioCandidate("772", "en", "audio/mpeg-L2", true))
        assertNull(chooseAudioTrack(english, "", listOf("pl", "en"), false, false))
        assertEquals(AudioChoice(0, "preferowana"),
            chooseAudioTrack(english, "", listOf("pl", "en"), true, false))
    }
}
