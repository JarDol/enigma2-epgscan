package pl.jardol.e2stream

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

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
            while (g == gen) {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val n = src.read(buf)
                if (n < 0) break
                val a = analyzer()
                if (a != null) {
                    a.readTime(android.os.SystemClock.elapsedRealtime() - t0)
                    if (n > 0) a.feed(buf, 0, n)
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
    private val ui = Handler(Looper.getMainLooper())
    private var lib: LibVLC? = null
    private var mp: MediaPlayer? = null
    @Volatile var playing = false
        private set
    @Volatile var buffering = 0f
        private set

    private fun options(): ArrayList<String> {
        // jak VLC dla Androida (DreamTV): te same opcje biblioteki
        val o = arrayListOf("--audio-time-stretch", "--avcodec-skiploopfilter", "-1", "--avcodec-skip-frame", "0",
            "--avcodec-skip-idct", "0", "--stats", "--network-caching=${prefs.bufferMs.coerceIn(300, 10_000)}",
            "--audio-resampler", "soxr", "--android-display-chroma", prefs.vlcChroma, "-v")
        when (prefs.vlcDeint) {
            "default" -> {}
            "off" -> o += "--deinterlace=0"
            "auto" -> o += "--deinterlace=-1"
            else -> { o += "--deinterlace=1"; o += "--deinterlace-mode=${prefs.vlcDeint}" }
        }
        prefs.audioLang.split(",").firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { o += "--audio-language=$it" }
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
                    MediaPlayer.Event.Vout -> if (ev.voutCount > 0) onEvent("VLC: obraz")
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

    fun release() {
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.detachViews() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        try { lib?.release() } catch (_: Exception) {}
        mp = null
        lib = null
        playing = false
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

    fun info(): String {
        val p = mp ?: return "-"
        return try {
            val v = p.currentVideoTrack
            "VLC akceleracja: ${prefs.vlcHwMode}, chroma ${prefs.vlcChroma}, przeplot: ${prefs.vlcDeint}" +
                (v?.let { ", obraz ${it.width}x${it.height} %.2f fps".format(if (it.frameRateDen > 0) it.frameRateNum.toFloat() / it.frameRateDen else 0f) } ?: "")
        } catch (_: Exception) { "VLC" }
    }
}
