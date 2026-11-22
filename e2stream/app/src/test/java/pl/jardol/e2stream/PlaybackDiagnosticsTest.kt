package pl.jardol.e2stream

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticsTest {
    @Test fun oldFramesDoNotCountAsFramesFromTheNewStream() {
        assertFalse(hasRenderedEnoughSinceStart(25, 25))
        assertFalse(hasRenderedEnoughSinceStart(24, 0))
        assertTrue(hasRenderedEnoughSinceStart(25, 0))
        assertFalse(hasRenderedEnoughSinceStart(0, 25))
    }

    @Test fun unknownAudioBufferDurationIsReadable() {
        assertEquals("nieznany", audioUnderrunBufferLabel(C.TIME_UNSET))
        assertEquals("0 ms", audioUnderrunBufferLabel(0))
        assertEquals("375 ms", audioUnderrunBufferLabel(375))
    }
}
