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
import kotlinx.coroutines.delay
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
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
        // Obraz rysuje aplikacja (klatki w pamięci RAM): dekoder sprzętowy musi kopiować klatki z karty graficznej.
        // „Automatycznie” = programowo — D3D11VA bez okna VLC potrafi nie oddać żadnej klatki (sam dźwięk).
        ":avcodec-hw=" + when (config.vlcHwMode) {
            "d3d11va" -> "d3d11va"
            "dxva2" -> "dxva2"
            else -> "none"
        },
    )
    // Przesunięcie dźwięku względem obrazu (ms; dodatnie — dźwięk później).
    if (config.audioDelayMs != 0) add(":audio-desync=${config.audioDelayMs}")
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
    /** Liczba odebranych klatek (do wykrycia braku obrazu). */
    val count = java.util.concurrent.atomic.AtomicLong()
    /** Pierwsza klatka po uruchomieniu odtwarzania (pomiar startu kanału); wątek VLC. */
    @Volatile var onFirstFrame: (() -> Unit)? = null
    @Volatile var awaitingFirst = false

    /** Rozmiar ustalony przy pierwszym wywołaniu dla strumienia (VLC potrafi podawać zmienną wysokość). */
    @Volatile private var lockedWidth = 0
    @Volatile private var lockedHeight = 0
    private var formatCalls = 0

    fun resetFormat() = synchronized(this) {
        lockedWidth = 0
        lockedHeight = 0
        formatCalls = 0
    }

    /** Pełna rozdzielczość klatek (true) albo zmniejszenie do rozmiaru ekranu (domyślnie — 4K na ekranie 1080p). */
    @Volatile var fullResolution = false
    @Volatile var frameLimit: Pair<Int, Int>? = null
    /** Chwila ustalenia formatu klatek dla bieżącego odtwarzania (0 — jeszcze nie). */
    @Volatile var formatAtNs = 0L
    /** Pomiar startu: włącz [awaitingFirst] dopiero przy formacie nowego strumienia (nie liczyć klatek poprzedniego). */
    @Volatile var armOnFormat = false

    override fun getBufferFormat(requestedWidth: Int, requestedHeight: Int): BufferFormat {
        synchronized(this) {
            formatCalls++
            formatAtNs = System.nanoTime()
            if (armOnFormat) {
                armOnFormat = false
                awaitingFirst = true
            }
            if (lockedWidth != requestedWidth || lockedHeight <= 0) {
                lockedWidth = requestedWidth
                lockedHeight = requestedHeight
            }
            if (onUhd != null && lockedWidth >= UHD_WIDTH) runCatching { onUhd?.invoke() }
            if (yuvMode) return yuvFormat(requestedWidth, requestedHeight)
            yuvState.value = null
            val limit = frameLimit
            val (outWidth, outHeight) = if (limit != null) {
                fitFrameSize(lockedWidth, lockedHeight, limit.first, limit.second)
            } else frameSize(lockedWidth, lockedHeight, if (fullResolution) null else screenPixels())
            if (outWidth == width && outHeight == height && bitmaps.isNotEmpty() && !lastWasYuv) {
                if (formatCalls == 2 || formatCalls % 500 == 0) AppLog.i("obraz: ponowne ustawienie formatu ($formatCalls×, VLC podał ${requestedWidth}x$requestedHeight)")
                return BufferFormat("RV32", outWidth, outHeight, intArrayOf(outWidth * 4), intArrayOf(outHeight))
            }
            width = outWidth
            height = outHeight
            lastWasYuv = false
            // RV32 = BGRX w pamięci, jak natywny format Skia na Windows (bez przeliczania przy rysowaniu);
            // OPAQUE — czwarty bajt (pusty w RV32) nie jest traktowany jako przezroczystość.
            val info = ImageInfo(outWidth, outHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            // Stare bitmapy mogą być jeszcze rysowane — zwalnia je GC, bez close().
            bitmaps = Array(3) { Bitmap().apply { allocPixels(info) } }
            next = 0
            AppLog.i(
                "obraz: format klatek ${outWidth}x$outHeight BGRA (VLC podał ${requestedWidth}x$requestedHeight" +
                    (if (outWidth != lockedWidth) ", zmniejszone do ekranu" else "") + ")",
            )
            return BufferFormat("RV32", outWidth, outHeight, intArrayOf(outWidth * 4), intArrayOf(outHeight))
        }
    }

    override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit

    override fun display(mediaPlayer: uk.co.caprica.vlcj.player.base.MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
        synchronized(this) {
            if (lastWasYuv) {
                displayYuv(nativeBuffers)
                return
            }
            if (bitmaps.isEmpty()) return
            val bitmap = bitmaps[next]
            next = (next + 1) % bitmaps.size
            val pixmap = bitmap.peekPixels() ?: return
            val size = minOf(pixmap.rowBytes.toLong() * height, nativeBuffers[0].capacity().toLong())
            val source = nativeBuffers[0].duplicate().apply { rewind(); limit(size.toInt()) }
            Pointer(pixmap.addr).getByteBuffer(0, size).put(source)
            bitmap.notifyPixelsChanged()
            frame.value = bitmap.asComposeImageBitmap()
            if (count.incrementAndGet() == 1L) AppLog.i("obraz: pierwsza klatka ${width}x$height")
            if (awaitingFirst) {
                awaitingFirst = false
                runCatching { onFirstFrame?.invoke() }
            }
        }
    }

    fun clear() {
        frame.value = null
        yuvState.value = null
    }

    // ---------- Tryb YUV: VLC oddaje płaszczyzny 4:2:0 bez przeliczania na RGB, kolory i skalowanie liczy karta graficzna ----------

    /** Klatki YUV (I420) zamiast RGB — przeliczanie na kolory ekranu w shaderze przy rysowaniu. */
    @Volatile var yuvMode = false
    /** Bieżąca klatka YUV (wątek rysowania czyta płaszczyzny pod blokadą tego obiektu). */
    val yuvState = mutableStateOf<YuvFrame?>(null)
    /** Wywoływane przy formacie obrazu ≥ 3840 px szerokości (lżejsze dekodowanie 4K). */
    @Volatile var onUhd: (() -> Unit)? = null
    private var lastWasYuv = false
    private var yuvSets: Array<Array<Bitmap>> = emptyArray()
    private var yuvNext = 0
    private var yuvSeq = 0L

    private fun yuvFormat(requestedWidth: Int, requestedHeight: Int): BufferFormat {
        val w = (requestedWidth + 1) and 1.inv()
        val h = (requestedHeight + 1) and 1.inv()
        val cw = w / 2
        val ch = h / 2
        if (!(lastWasYuv && w == width && h == height && yuvSets.isNotEmpty())) {
            width = w
            height = h
            lastWasYuv = true
            frame.value = null
            fun plane(pw: Int, ph: Int) = Bitmap().apply { allocPixels(ImageInfo(pw, ph, ColorType.GRAY_8, ColorAlphaType.OPAQUE)) }
            yuvSets = Array(3) { arrayOf(plane(w, h), plane(cw, ch), plane(cw, ch)) }
            yuvNext = 0
            bitmaps = emptyArray()
            AppLog.i("obraz: format klatek ${w}x$h YUV 4:2:0 — kolory i skalowanie na karcie graficznej (VLC podał ${requestedWidth}x$requestedHeight)")
        } else if (formatCalls == 2 || formatCalls % 500 == 0) {
            AppLog.i("obraz: ponowne ustawienie formatu ($formatCalls×, VLC podał ${requestedWidth}x$requestedHeight)")
        }
        return BufferFormat("I420", w, h, intArrayOf(w, cw, cw), intArrayOf(h, ch, ch))
    }

    private fun displayYuv(nativeBuffers: Array<out ByteBuffer>) {
        if (yuvSets.isEmpty() || nativeBuffers.size < 3) return
        val set = yuvSets[yuvNext]
        yuvNext = (yuvNext + 1) % yuvSets.size
        for (plane in 0 until 3) {
            val pixmap = set[plane].peekPixels() ?: return
            val size = minOf(pixmap.rowBytes.toLong() * set[plane].height, nativeBuffers[plane].capacity().toLong())
            val source = nativeBuffers[plane].duplicate().apply { rewind(); limit(size.toInt()) }
            Pointer(pixmap.addr).getByteBuffer(0, size).put(source)
            set[plane].notifyPixelsChanged()
        }
        yuvState.value = YuvFrame(set, width, height, ++yuvSeq)
        if (count.incrementAndGet() == 1L) AppLog.i("obraz: pierwsza klatka ${width}x$height (YUV)")
        if (awaitingFirst) {
            awaitingFirst = false
            runCatching { onFirstFrame?.invoke() }
        }
    }

    companion object {
        const val UHD_WIDTH = 3000

        /** Rozdzielczość ekranu w pikselach (największy monitor). */
        fun screenPixels(): Pair<Int, Int>? = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                .map { it.displayMode.width to it.displayMode.height }
                .maxByOrNull { it.first * it.second }
        }.getOrNull()

        /**
         * Rozmiar klatek przekazywanych przez VLC: obraz większy niż ekran (np. 4K na 1080p) VLC od razu zmniejsza —
         * 4× mniej danych do przeliczenia, skopiowania i narysowania. Proporcje zachowane, wymiary parzyste, bez powiększania.
         */
        internal fun frameSize(width: Int, height: Int, screen: Pair<Int, Int>?): Pair<Int, Int> {
            if (screen == null || width <= 0 || height <= 0) return width to height
            val maxW = maxOf(screen.first, 1280)
            val maxH = maxOf(screen.second, 720)
            return fitFrameSize(width, height, maxW, maxH)
        }

        internal fun fitFrameSize(width: Int, height: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
            if (width <= 0 || height <= 0 || maxW <= 0 || maxH <= 0) return width to height
            val scale = minOf(1.0, maxW.toDouble() / width, maxH.toDouble() / height)
            if (scale >= 1.0) return width to height
            fun even(v: Double) = (Math.round(v / 2.0) * 2).toInt().coerceAtLeast(2)
            return even(width * scale) to even(height * scale)
        }
    }

    fun close() = synchronized(this) {
        frame.value = null
        yuvState.value = null
        bitmaps = emptyArray()
        yuvSets = emptyArray()
    }
}

class VlcSession(private val onError: (String) -> Unit) {
    private val factory: MediaPlayerFactory
    private val player: EmbeddedMediaPlayer
    private var nativeLog: uk.co.caprica.vlcj.log.NativeLog? = null
    private val vlcMessages = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val vlcMessageTotal = java.util.concurrent.atomic.AtomicInteger()
    internal val frames = VideoFrames()
    private var paused = false
    @Volatile private var closed = false

    @Volatile private var volume = 100
    @Volatile private var muted = false

    /** Start odtwarzania (także po przewinięciu timeshiftu); wątek Swing. */
    var onPlaying: (() -> Unit)? = null

    /** Koniec odtwarzanego materiału (plik nagrania, zamknięty strumień timeshiftu); wątek Swing. */
    var onFinished: (() -> Unit)? = null

    val isPaused: Boolean
        get() = paused

    init {
        // Wyjątek w wywołaniu zwrotnym z VLC (JNA) jest inaczej niewidoczny — do logu.
        com.sun.jna.Native.setCallbackExceptionHandler { _, error ->
            AppLog.i("VLC: błąd w wywołaniu zwrotnym: $error")
        }
        // Jedna instancja libVLC na całe uruchomienie (wtyczki ładowane raz — przy starcie, w tle).
        factory = VlcRuntime.factory()
        player = try {
            factory.mediaPlayers().newEmbeddedMediaPlayer()
        } catch (e: LinkageError) {
            throw IllegalStateException("Nie można załadować bibliotek VLC. Sprawdź instalację VLC 64-bit.", e)
        }
        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(frames, frames, true))
        // Obraz 4K wykryty po formacie (nazwa kanału bez „4K”): jednorazowe wznowienie z lżejszym dekodowaniem.
        frames.onUhd = {
            if (lightMode == "uhd" && !uhdHint && !uhdDetected) {
                uhdDetected = true
                val url = lastUrl
                val generation = playGeneration
                if (url != null && LIGHT_DECODE_OPTION !in lastOptions) {
                    AppLog.i("obraz: kanał 4K — lżejsze dekodowanie (bez filtra wygładzającego), wznawiam strumień")
                    val options = lastOptions + LIGHT_DECODE_OPTION
                    lastOptions = options
                    SwingUtilities.invokeLater {
                        if (!closed && generation == playGeneration) runCatching {
                            frames.resetFormat()
                            player.media().play(url, *options.toTypedArray())
                        }
                    }
                }
            }
        }
        // Ostrzeżenia i błędy VLC do logu aplikacji (każdy komunikat najwyżej 3 razy, razem najwyżej 400).
        nativeLog = runCatching {
            factory.application().newLog().apply {
                setLevel(uk.co.caprica.vlcj.log.LogLevel.WARNING)
                addLogListener { level, module, _, _, _, _, _, message ->
                    // „Failed to set on top” — VLC chce ustawić własne okno na wierzchu, a obraz idzie do okna aplikacji.
                    if (message.startsWith("Failed to set on top")) return@addLogListener
                    val text = "$module: $message"
                    val seen = vlcMessages.merge(text, 1, Int::plus) ?: 1
                    if (seen <= 3 && vlcMessageTotal.incrementAndGet() <= 400) AppLog.i("VLC ${level.name.lowercase()} $text")
                }
            }
        }.getOrNull()
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun error(mediaPlayer: MediaPlayer) {
                SwingUtilities.invokeLater {
                    onError("VLC: nie udało się odtworzyć strumienia. Sprawdź połączenie i dane logowania.")
                }
            }
            override fun playing(mediaPlayer: MediaPlayer) {
                mediaPlayer.submit { applyAudioLevel() }
                checkFrames()
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
    @Synchronized
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

    @Volatile private var lastUrl: String? = null
    @Volatile private var lastOptions: List<String> = emptyList()
    @Volatile private var softwareFallback = false
    @Volatile private var deinterlaceFallback = false
    @Volatile private var lastDeinterlace: DeinterlaceMode? = null
    @Volatile private var playGeneration = 0
    @Volatile private var quickRetryGeneration = -1
    @Volatile private var playStartedNs = 0L

    /** Opis bieżącego startu w logu (np. „kanał TVP 2 HD”). */
    @Volatile var startLabel: String = ""

    /** Nowy kanał: przełączenia awaryjne (dekodowanie programowe, bez usuwania przeplotu) obowiązują tylko na kanale, na którym zadziałały. */
    fun resetFallbacks() {
        uhdDetected = false
        if (softwareFallback || deinterlaceFallback) AppLog.i("obraz: nowy kanał — znów ustawienia dekodowania z konfiguracji")
        softwareFallback = false
        deinterlaceFallback = false
    }

    /**
     * Strumień z obrazem, a brak klatek: przy dekodowaniu sprzętowym — ponowne otwarcie z programowym, przy usuwaniu
     * przeplotu — bez niego (oba do zmiany kanału), w pozostałych przypadkach jedno szybkie wznowienie.
     * Czas liczony od ustalenia formatu klatek (VLC utworzył wyjście obrazu) — kanały 4K startują wolniej; bez formatu
     * czekamy do [NO_FORMAT_LIMIT_MS].
     */
    private fun checkFrames() {
        val generation = playGeneration
        val before = frames.count.get()
        val url = lastUrl ?: return
        val hardware = lastOptions.any { it.startsWith(":avcodec-hw=") && it != ":avcodec-hw=none" }
        val deinterlacing = lastDeinterlace != null
        val canFallback = !((softwareFallback || !hardware) && (deinterlaceFallback || !deinterlacing))
        val startedNs = System.nanoTime()
        kotlin.concurrent.thread(name = "vlc-kontrola-obrazu", isDaemon = true) {
            val limitAfterFormat = if (canFallback) FALLBACK_AFTER_FORMAT_MS else QUICK_RETRY_MS
            while (true) {
                Thread.sleep(250)
                if (closed || generation != playGeneration || frames.count.get() != before) return@thread
                val now = System.nanoTime()
                val formatAt = frames.formatAtNs.takeIf { it >= startedNs }
                val waited = if (formatAt != null) (now - formatAt) / 1_000_000 else (now - startedNs) / 1_000_000
                if (formatAt != null && waited >= limitAfterFormat) break
                if (formatAt == null && waited >= NO_FORMAT_LIMIT_MS) break
            }
            val hasVideo = runCatching { player.video().trackCount() > 0 }.getOrDefault(false)
            if (!hasVideo) return@thread
            if (!canFallback) {
                // Ścieżka wideo jest, dane płyną, a obraz nie ruszył — jedno szybkie wznowienie zamiast czekania na watchdog.
                if (quickRetryGeneration == generation) return@thread
                quickRetryGeneration = generation
                AppLog.i("start: brak obrazu mimo ścieżki wideo — wznawiam strumień")
                SwingUtilities.invokeLater {
                    if (!closed && generation == playGeneration) runCatching {
                        frames.resetFormat()
                        player.media().play(url, *lastOptions.toTypedArray())
                    }
                }
                return@thread
            }
            val message = if (hardware && !softwareFallback) {
                softwareFallback = true
                AppLog.i("obraz: brak klatek przy dekodowaniu sprzętowym — przełączam na programowe (do zmiany kanału)")
                "Brak obrazu przy akceleracji sprzętowej — na tym kanale dekodowanie programowe."
            } else {
                deinterlaceFallback = true
                AppLog.i("obraz: brak klatek z usuwaniem przeplotu VLC — próba bez usuwania przeplotu (do zmiany kanału)")
                "Brak obrazu z usuwaniem przeplotu — na tym kanale bez usuwania przeplotu."
            }
            val options = lastOptions.map { if (softwareFallback && it.startsWith(":avcodec-hw=")) ":avcodec-hw=none" else it }
            SwingUtilities.invokeLater {
                if (!closed && generation == playGeneration) {
                    runCatching {
                        if (deinterlaceFallback) player.video().setDeinterlace(null)
                        frames.resetFormat()
                        player.media().play(url, *options.toTypedArray())
                    }
                    onError(message)
                }
            }
        }
    }

    /** Kanał 4K (nazwa z „4K”/„UHD” albo wykryty po formacie) — lżejsze dekodowanie do zmiany kanału. */
    @Volatile var uhdHint = false
    @Volatile private var uhdDetected = false
    @Volatile private var lightMode = "uhd"

    private fun lightDecoding(): Boolean = lightMode == "always" || (lightMode == "uhd" && (uhdHint || uhdDetected))

    fun play(url: String, config: DesktopConfig, startMs: Long = 0) {
        lightMode = config.lightDecode
        val options = vlcMediaOptions(config).map { if (softwareFallback && it.startsWith(":avcodec-hw=")) ":avcodec-hw=none" else it } +
            (if (lightDecoding()) listOf(LIGHT_DECODE_OPTION) else emptyList()) +
            if (startMs > 0) listOf(":start-time=%.1f".format(java.util.Locale.US, startMs / 1000.0)) else emptyList()
        frames.yuvMode = config.vlcRender != "cpu" && YuvShader.available
        lastUrl = url
        lastOptions = options
        playGeneration++
        playStartedNs = System.nanoTime()
        val label = startLabel
        frames.onFirstFrame = {
            val ms = (System.nanoTime() - playStartedNs) / 1_000_000
            AppLog.i("start: obraz po $ms ms" + if (label.isNotBlank()) " ($label)" else "")
        }
        // Bez „zostaw obraz poprzedniego kanału” — czarny ekran do pierwszej klatki nowego strumienia.
        if (!config.keepLastFrame) frames.clear()
        frames.awaitingFirst = false
        frames.armOnFormat = true
        frames.fullResolution = config.vlcFullRes
        frames.resetFormat()
        val deinterlace = if (deinterlaceFallback) null else vlcDeinterlaceMode(config.vlcDeint)
        lastDeinterlace = deinterlace
        check(!closed) { "Odtwarzacz został zamknięty." }
        player.video().setDeinterlace(deinterlace)
        val started = player.media().play(url, *options.toTypedArray())
        if (!started) throw IOException("VLC nie przyjął strumienia.")
        paused = false
    }

    internal suspend fun capturePipFrame(url: String, config: DesktopConfig, active: () -> Boolean): Boolean {
        val previous = frames.count.get()
        play(url, config)
        val deadline = monotonicMs() + 2_500
        while (active() && frames.count.get() == previous && monotonicMs() < deadline) delay(50)
        if (!active()) return false
        pause()
        return frames.count.get() > previous
    }

    /** Zmiana przesunięcia dźwięku w trakcie odtwarzania (ms) — bez ponownego otwierania strumienia. */
    fun setAudioDelay(ms: Int) {
        if (!closed) runCatching { player.audio().setDelay(ms * 1000L) }
        lastOptions = lastOptions.filterNot { it.startsWith(":audio-desync=") } +
            if (ms != 0) listOf(":audio-desync=$ms") else emptyList()
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

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        runCatching { player.controls().stop() }
        player.release()
        runCatching { nativeLog?.release() }
        // Fabryka (libVLC) zostaje — wspólna dla kolejnych sesji, zwalniana z końcem aplikacji.
        frames.close()
    }

    private companion object {
        /** Bez filtra wygładzającego (deblocking) — HEVC/H.264 4K dekoduje się wyraźnie lżej, różnica w obrazie znikoma. */
        const val LIGHT_DECODE_OPTION = ":avcodec-skiploopfilter=4"

        /** Bez możliwości zmiany dekodowania: po tylu ms od formatu klatek bez obrazu jedno wznowienie strumienia. */
        const val QUICK_RETRY_MS = 6000L
        /** Po tylu ms od ustalenia formatu klatek bez żadnej klatki — przełączenie awaryjne dekodowania. */
        const val FALLBACK_AFTER_FORMAT_MS = 4000L
        /** VLC nie utworzył wyjścia obrazu (brak formatu) — po tylu ms od startu odtwarzania. */
        const val NO_FORMAT_LIMIT_MS = 15_000L
    }
}
