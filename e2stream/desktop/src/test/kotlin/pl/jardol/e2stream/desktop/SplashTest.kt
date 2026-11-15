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

class FrameSizeTest {
    @Test
    fun uhdIsScaledToScreenKeepingAspect() {
        assertEquals(1920 to 1080, VideoFrames.frameSize(3840, 2160, 1920 to 1080))
        assertEquals(1920 to 1088, VideoFrames.frameSize(1920, 1088, 2560 to 1440))
        // HD na ekranie 1080p — bez zmian, bez powiększania
        assertEquals(1280 to 720, VideoFrames.frameSize(1280, 720, 1920 to 1080))
        // pełna rozdzielczość
        assertEquals(3840 to 2160, VideoFrames.frameSize(3840, 2160, null))
        // mały ekran — nie mniej niż 1280×720
        assertEquals(1280 to 720, VideoFrames.frameSize(3840, 2160, 1024 to 600))
    }
}
