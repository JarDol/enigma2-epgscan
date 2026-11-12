package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class HidInputRouteTest {
    @Test
    fun onlyAssignedPowerIsHandledWhenApplicationIsInBackground() {
        assertEquals(HidInputRoute.POWER, hidInputRoute(isPower = true, isApplicationFocused = false))
        assertEquals(HidInputRoute.IGNORE, hidInputRoute(isPower = false, isApplicationFocused = false))
        assertEquals(HidInputRoute.LOCAL, hidInputRoute(isPower = false, isApplicationFocused = true))
        assertEquals(HidInputRoute.POWER, hidInputRoute(isPower = true, isApplicationFocused = true))
    }
}
