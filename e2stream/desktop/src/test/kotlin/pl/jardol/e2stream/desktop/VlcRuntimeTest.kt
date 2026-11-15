package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VlcRuntimeTest {
    @Test
    fun versionNumberFromLibVlcString() {
        assertEquals("3.0.21", VlcRuntime.number("3.0.21 Vetinari"))
        assertEquals("3.0.17.4", VlcRuntime.number("3.0.17.4 Vetinari"))
        assertNull(VlcRuntime.number("Vetinari"))
    }

    @Test
    fun newerComparesNumerically() {
        assertTrue(VlcRuntime.isNewer("3.0.24", "3.0.21 Vetinari"))
        assertTrue(VlcRuntime.isNewer("3.0.10", "3.0.9"))
        assertTrue(VlcRuntime.isNewer("3.0.17.4", "3.0.17"))
        assertFalse(VlcRuntime.isNewer("3.0.21", "3.0.21 Vetinari"))
        assertFalse(VlcRuntime.isNewer("3.0.20", "3.0.21"))
        assertFalse(VlcRuntime.isNewer(null, "3.0.21"))
    }

    @Test
    fun noBundledVlcWithoutResourcesDir() {
        System.clearProperty("compose.application.resources.dir")
        assertNull(VlcRuntime.bundledDir())
    }
}
