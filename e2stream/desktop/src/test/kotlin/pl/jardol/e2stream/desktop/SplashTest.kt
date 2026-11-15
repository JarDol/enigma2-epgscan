package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class SplashTest {
    @Test
    fun stageTimeFormatting() {
        assertEquals("850 ms", formatStageTime(850))
        assertEquals("2.4 s", formatStageTime(2_430))
    }
}
