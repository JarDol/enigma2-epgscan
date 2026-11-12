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
}
