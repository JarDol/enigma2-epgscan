package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowsDisplayAwakeTest {
    @Test
    fun requestsDisplayAwakeDuringPlaybackAndReleasesItAfterward() {
        val requests = mutableListOf<Int>()
        val display = WindowsDisplayAwake(isWindows = true) { flags ->
            requests += flags
            1
        }

        display.setAwake(true)
        display.setAwake(true)
        display.setAwake(false)
        display.setAwake(false)

        assertEquals(
            listOf(
                WindowsDisplayAwake.ES_CONTINUOUS or WindowsDisplayAwake.ES_DISPLAY_REQUIRED,
                WindowsDisplayAwake.ES_CONTINUOUS,
            ),
            requests,
        )
    }

    @Test
    fun doesNotRequestPowerStateOnOtherPlatformsAndSurfacesFailure() {
        val calls = mutableListOf<Int>()
        WindowsDisplayAwake(isWindows = false) { flags -> calls += flags; 1 }.setAwake(true)
        assertEquals(emptyList(), calls)

        val display = WindowsDisplayAwake(isWindows = true) { 0 }
        assertFailsWith<java.io.IOException> { display.setAwake(true) }
    }
}
