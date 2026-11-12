package pl.jardol.e2stream.desktop

import java.awt.Component
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.SwingUtilities

internal class SurfacePlaybackGate(
    private val surface: Component,
    private val onError: (String) -> Unit,
) {
    private var pending: (() -> Unit)? = null
    private var closed = false
    private val listener = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) {
            scheduleIfReady()
        }
    }

    init {
        surface.addHierarchyListener(listener)
    }

    fun request(play: () -> Unit) {
        check(!closed) { "Odtwarzacz został zamknięty." }
        pending = play
        scheduleIfReady()
    }

    private fun scheduleIfReady() {
        if (closed || pending == null || !surface.isDisplayable) return
        SwingUtilities.invokeLater {
            if (!closed && surface.isDisplayable) {
                val play = pending
                pending = null
                if (play != null) {
                    try {
                        play()
                    } catch (e: Exception) {
                        onError("Nie udało się uruchomić VLC: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    fun close() {
        closed = true
        pending = null
        surface.removeHierarchyListener(listener)
    }
}
