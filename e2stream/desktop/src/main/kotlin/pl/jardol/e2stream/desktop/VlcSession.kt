package pl.jardol.e2stream.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.sun.jna.Pointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.IOException
import java.nio.ByteBuffer
import javax.swing.SwingUtilities
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import uk.co.caprica.vlcj.player.base.DeinterlaceMode
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.media.TrackType

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

/**
 * Klatki z VLC rysowane przez aplikację (zamiast osobnego okna systemowego VLC): dzięki temu paski, menu i okna
 * są warstwami w tym samym oknie co obraz. Format RV32 (BGRA), kopiowany do jednej z trzech bitmap Skia.
 */
internal class VideoFrames : BufferFormatCallback, RenderCallback {
    val frame = mutableStateOf<ImageBitmap?>(null)
    private var bitmaps: Array<Bitmap> = emptyArray()
    private var next = 0
    @Volatile private var width = 0
    @Volatile private var height = 0

    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
        synchronized(this) {
            width = sourceWidth
            height = sourceHeight
            val info = ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            // Stare bitmapy mogą być jeszcze rysowane — zwalnia je GC, bez close().
            bitmaps = Array(3) { Bitmap().apply { allocPixels(info) } }
            next = 0
        }
        return RV32BufferFormat(sourceWidth, sourceHeight)
    }

    override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit

    override fun display(mediaPlayer: uk.co.caprica.vlcj.player.base.MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
        synchronized(this) {
            if (bitmaps.isEmpty()) return
            val bitmap = bitmaps[next]
            next = (next + 1) % bitmaps.size
            val pixmap = bitmap.peekPixels() ?: return
            val size = minOf(pixmap.rowBytes.toLong() * height, nativeBuffers[0].capacity().toLong())
            val source = nativeBuffers[0].duplicate().apply { rewind(); limit(size.toInt()) }
            Pointer(pixmap.addr).getByteBuffer(0, size).put(source)
            bitmap.notifyPixelsChanged()
            frame.value = bitmap.asComposeImageBitmap()
        }
    }

    fun clear() {
        frame.value = null
    }

    fun close() = synchronized(this) {
        frame.value = null
        bitmaps = emptyArray()
    }
}

class VlcSession(private val onError: (String) -> Unit) {
    private val factory: MediaPlayerFactory
    private val player: EmbeddedMediaPlayer
    internal val frames = VideoFrames()
    private var paused = false
    private var closed = false

    @Volatile private var volume = 100
    @Volatile private var muted = false

    /** Start odtwarzania (także po przewinięciu timeshiftu); wątek Swing. */
    var onPlaying: (() -> Unit)? = null

    /** Koniec odtwarzanego materiału (plik nagrania, zamknięty strumień timeshiftu); wątek Swing. */
    var onFinished: (() -> Unit)? = null

    val isPaused: Boolean
        get() = paused

    init {
        if (!NativeDiscovery().discover()) {
            throw IllegalStateException(
                "Nie znaleziono 64-bitowego VLC. Zainstaluj VLC 3.x i uruchom aplikację ponownie.",
            )
        }
        try {
            factory = MediaPlayerFactory()
            player = factory.mediaPlayers().newEmbeddedMediaPlayer()
        } catch (e: LinkageError) {
            throw IllegalStateException("Nie można załadować bibliotek VLC. Sprawdź instalację VLC 64-bit.", e)
        }
        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(frames, frames, true))
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun error(mediaPlayer: MediaPlayer) {
                SwingUtilities.invokeLater {
                    onError("VLC: nie udało się odtworzyć strumienia. Sprawdź połączenie i dane logowania.")
                }
            }
            override fun playing(mediaPlayer: MediaPlayer) {
                mediaPlayer.submit { applyAudioLevel() }
                SwingUtilities.invokeLater { if (!closed) onPlaying?.invoke() }
            }
            override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: TrackType, id: Int) {
                if (type == TrackType.AUDIO) mediaPlayer.submit { applyAudioLevel() }
            }
            override fun finished(mediaPlayer: MediaPlayer) {
                SwingUtilities.invokeLater { if (!closed) onFinished?.invoke() }
            }
        })
    }

    /**
     * Głośność tylko tego programu (0–150 %, w mikserze Windows jako osobna aplikacja) i wyciszenie.
     * VLC przyjmuje je dopiero przy działającym wyjściu dźwięku, więc są ponawiane przy starcie odtwarzania.
     */
    fun setAudioLevel(volume: Int, muted: Boolean) {
        this.volume = volume.coerceIn(0, DesktopConfig.MAX_VOLUME)
        this.muted = muted
        if (!closed) player.submit { applyAudioLevel() }
    }

    private fun applyAudioLevel() {
        if (closed) return
        val audio = player.audio()
        if (audio.volume() != volume) audio.setVolume(volume)
        if (audio.isMute != muted) audio.setMute(muted)
    }

    /** Ścieżki dźwięku bieżącego strumienia (bez pozycji „wyłączone”). */
    internal fun audioTracks(): List<AudioTrack> {
        if (closed) return emptyList()
        val player = player
        val info = runCatching { player.media().info().audioTracks() }.getOrNull().orEmpty().associateBy { it.id() }
        return player.audio().trackDescriptions().orEmpty().filter { it.id() >= 0 }.map { description ->
            val track = info[description.id()]
            val text = fixMojibake(description.description().orEmpty().trim())
            val language = AudioTrackLabels.languageCode(
                track?.language()?.takeIf { it.isNotBlank() } ?: AudioTrackLabels.languageFromDescription(text),
            )
            val codec = track?.codecName().orEmpty().trim()
            AudioTrack(
                id = description.id(),
                name = AudioTrackLabels.trackName(description.id(), language, codec, track?.channels() ?: 0, text),
                language = language,
                codec = codec,
            )
        }
    }

    internal fun selectedAudioTrack(): Int = if (closed) -1 else player.audio().track()

    internal fun selectAudioTrack(id: Int): Boolean = !closed && player.audio().setTrack(id) == 0

    /** Skalowanie rysuje aplikacja (VideoView); tu tylko zapamiętane dla zgodności wywołań. */
    fun setVideoScale(mode: VideoScaleMode) = Unit

    /** Proporcje wyświetlania bieżącego obrazu (z pikselami niekwadratowymi SD); null, gdy nieznane. */
    internal fun displayAspect(): Float? {
        if (closed) return null
        val track = runCatching { player.media().info().videoTracks().firstOrNull() }.getOrNull() ?: return null
        val width = track.width()
        val height = track.height()
        if (width <= 0 || height <= 0) return null
        val num = track.sampleAspectRatio()
        val den = track.sampleAspectRatioBase()
        val sar = if (num > 0 && den > 0) num.toFloat() / den else 1f
        return width * sar / height
    }

    fun play(url: String, config: DesktopConfig, startMs: Long = 0) {
        val options = vlcMediaOptions(config) +
            if (startMs > 0) listOf(":start-time=%.1f".format(java.util.Locale.US, startMs / 1000.0)) else emptyList()
        val deinterlace = vlcDeinterlaceMode(config.vlcDeint)
        check(!closed) { "Odtwarzacz został zamknięty." }
        player.video().setDeinterlace(deinterlace)
        val started = player.media().play(url, *options.toTypedArray())
        if (!started) throw IOException("VLC nie przyjął strumienia.")
        paused = false
    }

    /** Położenie odtwarzania (ms) od początku bieżącego materiału; -1, gdy nieznane. */
    fun time(): Long = if (closed) -1 else player.status().time()

    /** Długość materiału (ms); 0 lub -1 dla strumieni na żywo. */
    fun length(): Long = if (closed) -1 else player.status().length()

    fun seekTo(ms: Long) {
        if (!closed) player.controls().setTime(ms.coerceAtLeast(0))
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
        if (value) player.controls().pause()
        else player.controls().play()
        paused = value
    }

    fun stop() {
        player.controls().stop()
        frames.clear()
        paused = false
    }

    /** Liczniki VLC do statystyk na ekranie; null, gdy nic nie jest odtwarzane. */
    internal fun statistics(): PlaybackStats? {
        if (closed) return null
        val player = player
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
        runCatching { player.controls().stop() }
        player.release()
        factory.release()
        frames.close()
    }
}
