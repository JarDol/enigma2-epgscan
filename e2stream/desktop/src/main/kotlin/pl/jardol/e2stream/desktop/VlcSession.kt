package pl.jardol.e2stream.desktop

import java.io.IOException
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

class VlcSession(private val onError: (String) -> Unit) {
    private val component: EmbeddedMediaPlayerComponent
    private val playbackGate: SurfacePlaybackGate
    private var paused = false
    private var scaleMode = VideoScaleMode.FIT
    private var closed = false

    val view: JComponent
        get() = component

    init {
        if (!NativeDiscovery().discover()) {
            throw IllegalStateException(
                "Nie znaleziono 64-bitowego VLC. Zainstaluj VLC 3.x i uruchom aplikację ponownie.",
            )
        }
        component = try {
            EmbeddedMediaPlayerComponent()
        } catch (e: LinkageError) {
            throw IllegalStateException("Nie można załadować bibliotek VLC. Sprawdź instalację VLC 64-bit.", e)
        }
        playbackGate = SurfacePlaybackGate(component.videoSurfaceComponent(), onError)
        component.videoSurfaceComponent().addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = applyVideoScale()
        })
        component.mediaPlayer().events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) {
                if (newCount > 0) SwingUtilities.invokeLater { applyVideoScale() }
            }
            override fun error(mediaPlayer: MediaPlayer) {
                SwingUtilities.invokeLater {
                    onError("VLC: nie udało się odtworzyć strumienia. Sprawdź połączenie i dane logowania.")
                }
            }
        })
    }

    fun setVideoScale(mode: VideoScaleMode) {
        scaleMode = mode
        applyVideoScale()
    }

    private fun applyVideoScale() {
        if (closed) return
        val surface = component.videoSurfaceComponent()
        val geometry = videoGeometry(scaleMode, surface.width, surface.height)
        component.mediaPlayer().video().apply {
            setAspectRatio(geometry.aspect)
            setCropGeometry(geometry.crop)
            setScale(0f)
        }
    }

    fun play(url: String, config: DesktopConfig) {
        val options = mutableListOf(":network-caching=1000")
        if (config.username.isNotBlank()) {
            options += ":http-user=${config.username}"
            options += ":http-pwd=${config.password}"
        }
        playbackGate.request {
            val started = component.mediaPlayer().media().play(url, *options.toTypedArray())
            if (!started) throw IOException("VLC nie przyjął strumienia.")
            paused = false
        }
    }

    fun togglePause() {
        setPaused(!paused)
    }

    fun pause() {
        setPaused(true)
    }

    fun resume() {
        setPaused(false)
    }

    private fun setPaused(value: Boolean) {
        if (paused == value) return
        if (value) component.mediaPlayer().controls().pause()
        else component.mediaPlayer().controls().play()
        paused = value
    }

    fun stop() {
        component.mediaPlayer().controls().stop()
        paused = false
    }

    fun close() {
        if (closed) return
        closed = true
        playbackGate.close()
        component.release()
    }
}
