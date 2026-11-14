package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoScaleModeTest {
    @Test
    fun scaleModesApplyTheViewportRatioToCropOrAspect() {
        assertEquals(VideoGeometry(null, null), videoGeometry(VideoScaleMode.FIT, 1920, 1200))
        assertEquals(VideoGeometry(null, "1920:1200"), videoGeometry(VideoScaleMode.CROP, 1920, 1200))
        assertEquals(VideoGeometry("1920:1200", null), videoGeometry(VideoScaleMode.STRETCH, 1920, 1200))
        assertEquals(VideoGeometry(null, null), videoGeometry(VideoScaleMode.CROP, 0, 1200))
    }

    @Test
    fun fitLetterboxesCropCutsAndStretchFills() {
        // 16:9 obraz w oknie 4:3 (1200x900)
        val fit = videoPlacement(VideoScaleMode.FIT, 1200f, 900f, 1920, 1080, 16f / 9f)
        assertEquals(1920, fit.srcW)
        assertEquals(112.5f, fit.dstY, 0.01f)
        assertEquals(675f, fit.dstH, 0.01f)
        assertEquals(1200f, fit.dstW, 0.01f)
        val crop = videoPlacement(VideoScaleMode.CROP, 1200f, 900f, 1920, 1080, 16f / 9f)
        assertEquals(1440, crop.srcW)
        assertEquals(240, crop.srcX)
        assertEquals(1200f, crop.dstW, 0.01f)
        val stretch = videoPlacement(VideoScaleMode.STRETCH, 1200f, 900f, 1920, 1080, 16f / 9f)
        assertEquals(VideoPlacement(0, 0, 1920, 1080, 0f, 0f, 1200f, 900f), stretch)
        // SD 720x576 z pikselami 16:9 w oknie 16:9 — cały widok
        val sd = videoPlacement(VideoScaleMode.FIT, 1600f, 900f, 720, 576, 16f / 9f)
        assertEquals(0f, sd.dstX, 0.01f)
        assertEquals(1600f, sd.dstW, 0.01f)
    }
}
