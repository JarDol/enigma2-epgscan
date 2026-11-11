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
}
