package pl.jardol.e2stream

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

internal enum class VlcFailureAction { RETRY, STARTUP_RECOVERY, PLAYBACK_RECOVERY }

internal class PlaybackRecovery {
    var hasPlayed = false
        private set
    var vlcRestarts = 0
        private set

    fun resetSession() { hasPlayed = false; vlcRestarts = 0 }
    fun markHealthy() { hasPlayed = true }
    fun resetVlcRestarts() { vlcRestarts = 0 }
    fun recordVlcRestart() { vlcRestarts++ }
    fun vlcFailureAction(): VlcFailureAction = when {
        vlcRestarts < 2 -> VlcFailureAction.RETRY
        hasPlayed -> VlcFailureAction.PLAYBACK_RECOVERY
        else -> VlcFailureAction.STARTUP_RECOVERY
    }
}

/**
 * Lokalny pośrednik strumienia dla VLC: pobiera TS z dekodera i przepuszcza go przez ten sam
 * analizator co przy ExoPlayerze (PMT z pamięci, czyszczenie, poprawki znaczników, statystyki, zrzuty),
 * a VLC czyta z http://127.0.0.1:port/.
 */
class TsProxy(private val analyzer: () -> TsAnalyzer?, private val auth: () -> String?) {
    private var server: ServerSocket? = null
    @Volatile private var target: String = ""
    @Volatile private var gen = 0
    val port: Int get() = server?.localPort ?: 0

    fun start() {
        if (server != null) return
        val s = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                val g = gen
                Thread({ serve(c, g) }, "TsProxy-klient").start()
            }
        }, "TsProxy").start()
    }

    /** Nowy kanał: poprzednie połączenia kończą się same (zmiana numeru). */
    fun setTarget(url: String) { target = url; gen++ }

    fun invalidate() { gen++ }

    fun url(): String = "http://127.0.0.1:$port/stream-$gen.ts"

    private fun serve(c: Socket, g: Int) {
        var up: HttpURLConnection? = null
        try {
            c.soTimeout = 15000
            // nagłówki żądania VLC — ignorujemy (zawsze cały strumień od teraz)
            val inp = c.getInputStream()
            val hdr = StringBuilder()
            while (!hdr.endsWith("\r\n\r\n")) {
                val b = inp.read()
                if (b < 0 || hdr.length > 8192) break
                hdr.append(b.toChar())
            }
            val out = c.getOutputStream()
            up = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 6000
                setRequestProperty("User-Agent", "E2Stream")
                auth()?.let { setRequestProperty("Authorization", it) }
            }
            val src: InputStream = up.inputStream
            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
            val buf = ByteArray(188 * 700)
            val sourceAnalyzer = analyzer()
            val analyzerSession = sourceAnalyzer?.sessionId
            while (g == gen) {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val n = src.read(buf)
                if (n < 0 || g != gen) break
                val a = analyzer()
                if (a != null && a === sourceAnalyzer) {
                    a.readTime(android.os.SystemClock.elapsedRealtime() - t0, analyzerSession)
                    if (n > 0) a.feed(buf, 0, n, analyzerSession)
                }
                out.write(buf, 0, n)
            }
        } catch (_: Exception) {
        } finally {
            try { up?.disconnect() } catch (_: Exception) {}
            try { c.close() } catch (_: Exception) {}
        }
    }

    fun stop() {
        gen++
        try { server?.close() } catch (_: Exception) {}
        server = null
    }
}

/** Odtwarzanie przez libVLC (wybór w ustawieniach odbiornika). */
class VlcEngine(
    private val ctx: Context,
    private val prefs: Prefs,
    private val layout: VLCVideoLayout,
    private val onEvent: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    data class AudioTrack(val id: Int, val name: String, val language: String, val codec: String, val channels: Int)

    private val ui = Handler(Looper.getMainLooper())
    private var lib: LibVLC? = null
    private var mp: MediaPlayer? = null
    @Volatile var playing = false
        private set
    @Volatile var buffering = 0f
        private set

    private fun options(): ArrayList<String> {
        // te same opcje biblioteki co VLC dla Androida
        val o = arrayListOf("--audio-time-stretch", "--avcodec-skiploopfilter", "-1", "--avcodec-skip-frame", "0",
            "--avcodec-skip-idct", "0", "--stats", "--network-caching=${prefs.bufferMs.coerceIn(300, 10_000)}",
            "--audio-resampler", "soxr", "--android-display-chroma", prefs.vlcChroma, "-v")
        when (prefs.vlcDeint) {
            "default" -> {}
            "off" -> o += "--deinterlace=0"
            "auto" -> o += "--deinterlace=-1"
            else -> { o += "--deinterlace=1"; o += "--deinterlace-mode=${prefs.vlcDeint}" }
        }
        AudioTrackLabels.preferredVlcLanguages(prefs.audioLang).takeIf { it.isNotEmpty() }
            ?.joinToString(",")?.let { o += "--audio-language=$it" }
        return o
    }

    fun play(url: String) {
        val l = lib ?: LibVLC(ctx, options()).also { lib = it }
        val p = mp ?: MediaPlayer(l).also { m ->
            mp = m
            // z powierzchnią napisów: bez niej VLC odrzuca wyjście android_display (bezpośrednio na warstwę
            // wideo urządzenia, gdzie MiBox sam usuwa przeplot) i przechodzi na OpenGL — wtedy widać przeplot
            m.attachViews(layout, null, true, false)
            m.setEventListener { ev ->
                when (ev.type) {
                    MediaPlayer.Event.Playing -> { playing = true; onEvent("VLC: odtwarzanie") }
                    MediaPlayer.Event.Buffering -> buffering = ev.buffering
                    MediaPlayer.Event.EncounteredError -> { playing = false; ui.post { onFailure("VLC: błąd odtwarzania") } }
                    MediaPlayer.Event.EndReached -> { playing = false; ui.post { onFailure("VLC: koniec strumienia") } }
                    MediaPlayer.Event.Vout -> if (ev.voutCount > 0) onEvent("VLC: utworzone wyjście wideo")
                }
            }
        }
        val m = Media(l, Uri.parse(url))
        when (prefs.vlcHwMode) {
            "off" -> m.setHWDecoderEnabled(false, false)
            "dec" -> { m.setHWDecoderEnabled(true, true); m.addOption(":no-mediacodec-dr"); m.addOption(":no-omxil-dr") }
            "full" -> m.setHWDecoderEnabled(true, true)
            else -> m.setHWDecoderEnabled(true, false)
        }
        m.addOption(":network-caching=${prefs.bufferMs.coerceIn(300, 10_000)}")
        p.media = m
        m.release()
        playing = false
        p.play()
    }

    fun stop() {
        try { mp?.stop() } catch (_: Exception) {}
        playing = false
    }

    /**
     * Widoki odpinane od razu (wątek UI), a zatrzymanie i zwolnienie libVLC w tle: przy wyłączaniu
     * telewizora powierzchnia obrazu znika i stop() potrafi się zablokować — na wątku UI aplikacja
     * zostawała po wybudzeniu z czarnym ekranem i bez reakcji na pilota.
     */
    fun release() {
        val m = mp; val l = lib
        mp = null; lib = null; playing = false
        try { m?.detachViews() } catch (_: Exception) {}
        if (m == null && l == null) return
        Thread({
            val t0 = android.os.SystemClock.elapsedRealtime()
            try { m?.stop() } catch (_: Throwable) {}
            try { m?.release() } catch (_: Throwable) {}
            try { l?.release() } catch (_: Throwable) {}
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            if (ms > 1500) AppLog.i("VLC: zwalnianie trwało $ms ms")
        }, "vlc-release").start()
    }

    /** Wpisy VLC z logcat (dekoder, wyjście obrazu, przeplot) — do logu aplikacji. */
    fun vlcLog(): List<String> = try {
        val pr = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "400", "-v", "brief", "VLC:V", "*:S"))
        val lines = pr.inputStream.bufferedReader().readLines()
        pr.destroy()
        val keys = listOf("mediacodec", "deinterlace", "vout", "decoder", "using", "chroma", "interlace", "gles", "android_display", "avcodec")
        lines.filter { l -> keys.any { l.contains(it, true) } }.takeLast(25)
    } catch (_: Exception) { emptyList() }

    /** Czas odtwarzania (ms) — do wykrywania zawieszenia. */
    fun time(): Long = try { mp?.time ?: -1 } catch (_: Exception) { -1 }

    fun displayedPictures(): Int = mp?.media?.stats?.displayedPictures ?: 0

    /** Bieżące ID ścieżki audio; dla MPEG-TS jest to PID strumienia elementarnego. */
    fun selectedAudioTrackId(): Int? = try {
        mp?.audioTrack?.takeIf { it >= 0 }
    } catch (_: Exception) {
        null
    }

    fun audioTracks(): List<AudioTrack> = try {
        val player = mp ?: return emptyList()
        val metadata = (0 until (player.media?.trackCount ?: 0))
            .mapNotNull { player.media?.getTrack(it) as? IMedia.AudioTrack }
            .associateBy { it.id }
        player.audioTracks.orEmpty()
            .filter { it.id >= 0 }
            .map { track ->
                val meta = metadata[track.id]
                val language = meta?.language
                    ?: track.name?.substringAfterLast('[')?.substringBefore(']')?.takeIf { it.isNotBlank() }
                val name = AudioTrackLabels.label(language, meta?.codec, meta?.channels ?: 0)
                    .takeIf { it != "?" }
                    ?: "Ścieżka ${track.id}"
                AudioTrack(track.id, name, AudioTrackLabels.languageCode(language), meta?.codec.orEmpty(), meta?.channels ?: 0)
            }
    } catch (e: Exception) {
        AppLog.i("VLC: nie można pobrać ścieżek audio: ${e.message}")
        emptyList()
    }

    fun selectAudioTrack(id: Int): Boolean = try {
        mp?.setAudioTrack(id) == true
    } catch (e: Exception) {
        AppLog.i("VLC: nie można wybrać ścieżki audio $id: ${e.message}")
        false
    }

    fun info(): String {
        val p = mp ?: return "-"
        return try {
            val v = p.currentVideoTrack
            "VLC akceleracja: ${prefs.vlcHwMode}, chroma ${prefs.vlcChroma}, przeplot: ${prefs.vlcDeint}" +
                (v?.let { ", obraz ${it.width}x${it.height} %.2f fps".format(if (it.frameRateDen > 0) it.frameRateNum.toFloat() / it.frameRateDen else 0f) } ?: "")
        } catch (_: Exception) { "VLC" }
    }
}
