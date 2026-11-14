package pl.jardol.e2stream.desktop

import java.io.IOException
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseListener
import javax.swing.JComponent
import javax.swing.SwingUtilities
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import uk.co.caprica.vlcj.player.base.DeinterlaceMode
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

internal fun vlcMediaOptions(config: DesktopConfig): List<String> = buildList {
    add(":network-caching=${config.bufferMs.coerceIn(250, 10_000)}")
    add(
        ":avcodec-hw=" + when (config.vlcHwMode) {
            "off" -> "none"
            "d3d11va" -> "d3d11va"
            "dxva2" -> "dxva2"
            else -> "any"
        },
    )
    val languages = config.audioLang.split(',', ';', ' ').map(String::trim).filter(String::isNotEmpty)
    if (languages.isNotEmpty()) add(":audio-language=${languages.joinToString(",")}")
    if (config.username.isNotBlank()) {
        add(":http-user=${config.username}")
        add(":http-pwd=${config.password}")
    }
}

/** Liczniki VLC dla statystyk na ekranie. */
internal data class PlaybackStats(
    val inputKbps: Int,
    val width: Int,
    val height: Int,
    val codec: String,
    val displayed: Int,
    val lost: Int,
    val corrupted: Int,
    val discontinuities: Int,
    val audioLost: Int,
)

/** null wyłącza usuwanie przeplotu. */
internal fun vlcDeinterlaceMode(value: String): DeinterlaceMode? = when (value) {
    "off" -> null
    "yadif" -> DeinterlaceMode.YADIF
    "yadif2x" -> DeinterlaceMode.YADIF2X
    "bob" -> DeinterlaceMode.BOB
    "linear" -> DeinterlaceMode.LINEAR
    "blend" -> DeinterlaceMode.BLEND
    "x" -> DeinterlaceMode.X
    else -> DeinterlaceMode.AUTO
}

class VlcSession(private val onError: (String) -> Unit) {
    private val component: EmbeddedMediaPlayerComponent
    private val playbackGate: SurfacePlaybackGate
    private var paused = false
    private var scaleMode = VideoScaleMode.FIT
    private var closed = false

    /** Koniec odtwarzanego materiału (plik nagrania, zamknięty strumień timeshiftu); wątek Swing. */
    var onFinished: (() -> Unit)? = null

    val isPaused: Boolean
        get() = paused

    val view: JComponent
        get() = component

    fun addVideoMouseListener(listener: MouseListener) {
        component.addMouseListener(listener)
        component.videoSurfaceComponent().addMouseListener(listener)
    }

    fun removeVideoMouseListener(listener: MouseListener) {
        component.removeMouseListener(listener)
        component.videoSurfaceComponent().removeMouseListener(listener)
    }

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
            override fun finished(mediaPlayer: MediaPlayer) {
                SwingUtilities.invokeLater { if (!closed) onFinished?.invoke() }
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

    fun play(url: String, config: DesktopConfig, startMs: Long = 0) {
        val options = vlcMediaOptions(config) +
            if (startMs > 0) listOf(":start-time=%.1f".format(java.util.Locale.US, startMs / 1000.0)) else emptyList()
        val deinterlace = vlcDeinterlaceMode(config.vlcDeint)
        playbackGate.request {
            component.mediaPlayer().video().setDeinterlace(deinterlace)
            val started = component.mediaPlayer().media().play(url, *options.toTypedArray())
            if (!started) throw IOException("VLC nie przyjął strumienia.")
            paused = false
        }
    }

    /** Położenie odtwarzania (ms) od początku bieżącego materiału; -1, gdy nieznane. */
    fun time(): Long = if (closed) -1 else component.mediaPlayer().status().time()

    /** Długość materiału (ms); 0 lub -1 dla strumieni na żywo. */
    fun length(): Long = if (closed) -1 else component.mediaPlayer().status().length()

    fun seekTo(ms: Long) {
        if (!closed) component.mediaPlayer().controls().setTime(ms.coerceAtLeast(0))
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

    /** Liczniki VLC do statystyk na ekranie; null, gdy nic nie jest odtwarzane. */
    internal fun statistics(): PlaybackStats? {
        if (closed) return null
        val player = component.mediaPlayer()
        if (!player.media().isValid) return null
        val stats = player.media().info().statistics() ?: return null
        val size = player.video().videoDimension()
        val track = runCatching { player.media().info().videoTracks().firstOrNull() }.getOrNull()
        return PlaybackStats(
            inputKbps = (stats.inputBitrate() * 8000).toInt(),
            width = size?.width ?: 0,
            height = size?.height ?: 0,
            codec = track?.codecDescription()?.takeIf(String::isNotBlank) ?: track?.codecName().orEmpty(),
            displayed = stats.picturesDisplayed(),
            lost = stats.picturesLost(),
            corrupted = stats.demuxCorrupted(),
            discontinuities = stats.demuxDiscontinuity(),
            audioLost = stats.audioBuffersLost(),
        )
    }

    fun close() {
        if (closed) return
        closed = true
        playbackGate.close()
        component.release()
    }
}
