package pl.jardol.e2stream.desktop

import java.awt.Component
import java.awt.event.HierarchyEvent
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

class SurfacePlaybackGateTest {
    private class TestSurface : Component() {
        var ready = false
        override fun isDisplayable(): Boolean = ready

        fun setDisplayable(value: Boolean) {
            ready = value
            val event = HierarchyEvent(
                this, HierarchyEvent.HIERARCHY_CHANGED, this, null,
                HierarchyEvent.DISPLAYABILITY_CHANGED.toLong(),
            )
            hierarchyListeners.forEach { it.hierarchyChanged(event) }
        }
    }

    @Test
    fun waitsForVideoSurfaceAndStartsOnlyLatestChannel() {
        val surface = TestSurface()
        val errors = mutableListOf<String>()
        val gate = SurfacePlaybackGate(surface, errors::add)
        val played = mutableListOf<String>()

        SwingUtilities.invokeAndWait {
            gate.request { played += "first" }
            gate.request { played += "second" }
        }
        assertEquals(emptyList(), played)

        SwingUtilities.invokeAndWait { surface.setDisplayable(true) }
        SwingUtilities.invokeAndWait {}
        assertEquals(listOf("second"), played)
        assertEquals(emptyList(), errors)

        SwingUtilities.invokeAndWait {
            gate.request { played += "third" }
        }
        SwingUtilities.invokeAndWait {}
        assertEquals(listOf("second", "third"), played)
        SwingUtilities.invokeAndWait { gate.close() }
    }

    @Test
    fun closingBeforeSurfaceIsReadyCancelsPlayback() {
        val surface = TestSurface()
        val played = mutableListOf<String>()
        val gate = SurfacePlaybackGate(surface) { error(it) }

        SwingUtilities.invokeAndWait {
            gate.request { played += "channel" }
            gate.close()
            surface.setDisplayable(true)
        }
        SwingUtilities.invokeAndWait {}
        assertEquals(emptyList(), played)
    }

    @Test
    fun waitsAgainIfSurfaceIsRemovedBeforeScheduledPlayback() {
        val surface = TestSurface()
        val played = mutableListOf<String>()
        val gate = SurfacePlaybackGate(surface) { error(it) }

        SwingUtilities.invokeAndWait {
            surface.setDisplayable(true)
            gate.request { played += "channel" }
            surface.setDisplayable(false)
        }
        SwingUtilities.invokeAndWait {}
        assertEquals(emptyList(), played)

        SwingUtilities.invokeAndWait { surface.setDisplayable(true) }
        SwingUtilities.invokeAndWait {}
        assertEquals(listOf("channel"), played)
        SwingUtilities.invokeAndWait { gate.close() }
    }

    @Test
    fun reportsPlaybackErrors() {
        val surface = TestSurface()
        val errors = mutableListOf<String>()
        val gate = SurfacePlaybackGate(surface, errors::add)

        SwingUtilities.invokeAndWait {
            surface.setDisplayable(true)
            gate.request { throw IllegalStateException("test failure") }
        }
        SwingUtilities.invokeAndWait {}
        assertEquals(listOf("Nie udało się uruchomić VLC: test failure"), errors)
        SwingUtilities.invokeAndWait { gate.close() }
    }
}
