package pl.jardol.e2stream

import android.app.Activity
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/** Źródło bajtów nagrania (pliki lokalne albo plik na dekoderze po HTTP z Range). */
interface ByteSrc {
    /** Rozmiar w bajtach (0 — nieznany). */
    fun size(): Long
    fun read(off: Long, dst: ByteArray, len: Int): Int
    fun close() {}
}

/** Nagranie lokalne: części .ts czytane jak jeden plik. */
class PartsSrc(private val parts: List<File>) : ByteSrc {
    private val lens = parts.map { it.length() }
    override fun size() = lens.sum()
    override fun read(off: Long, dst: ByteArray, len: Int): Int {
        var o = off
        var got = 0
        var start = 0L
        for ((i, f) in parts.withIndex()) {
            val l = lens[i]
            if (o < start + l && got < len) {
                RandomAccessFile(f, "r").use { raf ->
                    raf.seek(o - start)
                    while (got < len) {
                        val n = raf.read(dst, got, len - got)
                        if (n <= 0) break
                        got += n; o += n
                    }
                }
            }
            start += l
            if (got >= len) break
        }
        return got
    }
}

/** Nagranie z dekodera: fragmenty pliku przez HTTP (Range). */
class HttpSrc(private val url: String, private val auth: String?) : ByteSrc {
    @Volatile private var total = -1L

    override fun size(): Long {
        if (total < 0) total = try { val c = open(0, 1); c.disconnect(); maxOf(total, 0L) } catch (_: Exception) { 0L }
        return total
    }

    /** Połączenie od [off] na [len] bajtów; całkowity rozmiar z Content-Range trafia do [total]. */
    private fun open(off: Long, len: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 6000
        auth?.let { c.setRequestProperty("Authorization", it) }
        c.setRequestProperty("Range", "bytes=$off-${off + len - 1}")
        val code = c.responseCode
        if (code != 206) { c.disconnect(); throw java.io.IOException(if (code == 200) "dekoder nie obsługuje Range" else "HTTP $code") }
        c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { total = it }
        return c
    }

    override fun read(off: Long, dst: ByteArray, len: Int): Int {
        val c = open(off, len)
        try {
            var got = 0
            c.inputStream.use { inp ->
                while (got < len) {
                    val n = inp.read(dst, got, len - got)
                    if (n <= 0) break
                    got += n
                }
            }
            return got
        } finally { c.disconnect() }
    }
}

/** Szukanie w danych TS: ścieżka obrazu z PAT/PMT, klatka kluczowa, PTS, wycięcie jednej klatki (PES). */
object TsFrames {
    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /** (PID, „H264” / „HEVC” / „MPEG2-V”) z PAT/PMT w [d] albo null. */
    fun video(d: ByteArray, len: Int): Pair<Int, String>? {
        val pmtPids = HashSet<Int>()
        var o = 0
        while (o + 188 <= len) {
            if (d[o] != 0x47.toByte()) { o++; continue }
            val pid = ((u(d, o + 1) and 0x1F) shl 8) or u(d, o + 2)
            val pusi = (u(d, o + 1) and 0x40) != 0
            val afc = (u(d, o + 3) shr 4) and 3
            var p = o + 4
            if (afc and 2 != 0) p += 1 + u(d, o + 4)
            if (pusi && afc and 1 != 0 && p < o + 188) {
                p += 1 + u(d, p)                         // pointer_field
                if (pid == 0 && p + 8 < o + 188 && u(d, p) == 0) {
                    val sl = ((u(d, p + 1) and 0x0F) shl 8) or u(d, p + 2)
                    var k = p + 8
                    val end = minOf(p + 3 + sl - 4, o + 188)
                    while (k + 4 <= end) {
                        val prog = (u(d, k) shl 8) or u(d, k + 1)
                        if (prog != 0) pmtPids += ((u(d, k + 2) and 0x1F) shl 8) or u(d, k + 3)
                        k += 4
                    }
                } else if (pid in pmtPids && p + 12 < o + 188 && u(d, p) == 2) {
                    val sl = ((u(d, p + 1) and 0x0F) shl 8) or u(d, p + 2)
                    val end = minOf(p + 3 + sl - 4, o + 188)
                    val pil = ((u(d, p + 10) and 0x0F) shl 8) or u(d, p + 11)
                    var k = p + 12 + pil
                    while (k + 5 <= end) {
                        val st = u(d, k)
                        val ep = ((u(d, k + 1) and 0x1F) shl 8) or u(d, k + 2)
                        val name = when (st) { 0x01, 0x02 -> "MPEG2-V"; 0x1B -> "H264"; 0x24 -> "HEVC"; else -> null }
                        if (name != null) return ep to name
                        k += 5 + (((u(d, k + 3) and 0x0F) shl 8) or u(d, k + 4))
                    }
                }
            }
            o += 188
        }
        return null
    }

    /** Pierwszy pakiet [vpid] od [from] zaczynający klatkę kluczową (SPS / IDR / VPS / nagłówek sekwencji); -1 — brak. */
    fun keyframe(d: ByteArray, len: Int, vpid: Int, from: Int = 0): Int {
        var o = from
        while (o + 188 <= len) {
            if (d[o] != 0x47.toByte()) { o++; continue }
            val pid = ((u(d, o + 1) and 0x1F) shl 8) or u(d, o + 2)
            if (pid == vpid && (u(d, o + 1) and 0x40) != 0 && isKeyStart(d, o)) return o
            o += 188
        }
        return -1
    }

    private fun isKeyStart(b: ByteArray, p: Int): Boolean {
        val afc = (u(b, p + 3) shr 4) and 3
        var q = p + 4
        if (afc and 2 != 0) {
            val al = u(b, p + 4)
            if (al > 0 && (u(b, p + 5) and 0x40) != 0) return true          // random_access
            q = p + 5 + al
        }
        if (afc and 1 == 0 || q + 9 > p + 188) return false
        if (b[q] != 0.toByte() || b[q + 1] != 0.toByte() || b[q + 2] != 1.toByte()) return false
        var i = q + 9 + u(b, q + 8)
        val e = p + 188 - 4
        while (i <= e) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 1.toByte()) {
                val x = u(b, i + 3)
                if ((x and 0x80) == 0 && ((x and 0x1F) == 7 || (x and 0x1F) == 5)) return true
                if (x == 0x40 || x == 0x42 || x == 0xB3) return true
                i += 3
            } else i++
        }
        return false
    }

    /** PTS (90 kHz) z nagłówka PES w pakiecie [o] albo -1. */
    fun pts(d: ByteArray, o: Int): Long {
        val afc = (u(d, o + 3) shr 4) and 3
        var p = o + 4
        if (afc and 2 != 0) p += 1 + u(d, o + 4)
        if (p + 14 > o + 188 || d[p] != 0.toByte() || d[p + 1] != 0.toByte() || d[p + 2] != 1.toByte()) return -1
        if ((u(d, p + 7) and 0x80) == 0) return -1
        val q = p + 9
        return ((u(d, q).toLong() shr 1 and 7) shl 30) or (u(d, q + 1).toLong() shl 22) or
            ((u(d, q + 2).toLong() shr 1) shl 15) or (u(d, q + 3).toLong() shl 7) or (u(d, q + 4).toLong() shr 1)
    }

    /** PTS pierwszego PES obrazu w danych albo -1. */
    fun firstPts(d: ByteArray, len: Int, vpid: Int): Long {
        var o = 0
        while (o + 188 <= len) {
            if (d[o] != 0x47.toByte()) { o++; continue }
            val pid = ((u(d, o + 1) and 0x1F) shl 8) or u(d, o + 2)
            if (pid == vpid && (u(d, o + 1) and 0x40) != 0) { val t = pts(d, o); if (t >= 0) return t }
            o += 188
        }
        return -1
    }

    /** Strumień elementarny PES zaczynającego się w pakiecie [from] (do początku następnego PES) → [out]; 0 — niekompletny. */
    fun frame(d: ByteArray, len: Int, vpid: Int, from: Int, out: ByteArray): Int {
        var o = from
        var n = 0
        var started = false
        while (o + 188 <= len) {
            if (d[o] != 0x47.toByte()) { o++; continue }
            val pid = ((u(d, o + 1) and 0x1F) shl 8) or u(d, o + 2)
            if (pid == vpid) {
                val pusi = (u(d, o + 1) and 0x40) != 0
                val afc = (u(d, o + 3) shr 4) and 3
                var p = o + 4
                if (afc and 2 != 0) p += 1 + u(d, o + 4)
                if (afc and 1 != 0 && p < o + 188) {
                    if (pusi) {
                        if (started) return n
                        if (p + 9 > o + 188 || d[p] != 0.toByte() || d[p + 1] != 0.toByte() || d[p + 2] != 1.toByte()) return 0
                        p += 9 + u(d, p + 8)
                        started = true
                    }
                    if (started && p < o + 188) {
                        val c = o + 188 - p
                        if (n + c > out.size) return 0
                        System.arraycopy(d, p, out, n, c); n += c
                    }
                }
            }
            o += 188
        }
        return 0
    }

    fun mime(type: String) = when (type) { "H264" -> "video/avc"; "HEVC" -> "video/hevc"; "MPEG2-V" -> "video/mpeg2"; else -> null }

    private val sw = HashMap<String, String?>()

    /** Programowy dekoder dla typu albo null (sprzętowy nigdy — obraz główny ma swój). */
    fun swDecoder(mime: String): String? = synchronized(sw) {
        sw.getOrPut(mime) {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { i ->
                !i.isEncoder && i.supportedTypes.any { it.equals(mime, true) } && isSw(i)
            }?.name
        }
    }

    private fun isSw(i: MediaCodecInfo) = i.name.startsWith("c2.android.") || i.name.startsWith("OMX.google.") ||
        (Build.VERSION.SDK_INT >= 29 && i.isSoftwareOnly && !i.isAlias && !i.isVendor)
}

/**
 * Podgląd przy przewijaniu (timeshift, nagrania): na całym ekranie klatka kluczowa z miejsca na pasku,
 * dekodowana programowo; obraz główny w tym czasie zatrzymany. Zawsze liczy się tylko najnowsze żądanie.
 */
class SeekPreview(private val act: Activity, private val anchor: View) {
    private val ui = Handler(Looper.getMainLooper())
    private var tv: TextureView? = null
    @Volatile private var surface: Surface? = null
    private val seq = AtomicLong()
    @Volatile private var job: (() -> Unit)? = null
    private var worker: Thread? = null
    private val lock = Object()
    // tylko wątek podglądu
    private var codec: MediaCodec? = null
    private var codecMime = ""
    private var codecSurface: Surface? = null
    private var data = ByteArray(3_000_000)
    private var au = ByteArray(3_000_000)

    /**
     * Nakładka nad obrazem głównym: TextureView zawsze dołączony (powierzchnia istnieje), niewidoczny przez alpha 0;
     * widoczny dopiero po pierwszej klatce podglądu — do tego czasu widać zatrzymany obraz główny.
     */
    private fun ensureView() {
        if (tv != null) return
        val t = TextureView(act)
        t.isOpaque = false
        t.alpha = 0f
        t.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { surface?.release(); surface = null; return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        val parent = anchor.parent as ViewGroup
        parent.addView(t, parent.indexOfChild(anchor) + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tv = t
    }

    /** Początek przewijania (nakładka gotowa; pokazuje się z pierwszą klatką). */
    fun show() {
        ensureView()
        ui.removeCallbacks(hideRun)
    }

    val shown: Boolean get() = (tv?.alpha ?: 0f) > 0f

    private val hideRun = Runnable { tv?.alpha = 0f }

    /** Ukrycie po [delayMs] (obraz główny zdąży już pokazać miejsce po przewinięciu). */
    fun hide(delayMs: Long = 0) {
        seq.incrementAndGet()
        job = null
        ui.removeCallbacks(hideRun)
        if (delayMs <= 0) hideRun.run() else ui.postDelayed(hideRun, delayMs)
    }

    /** Klatka z bufora timeshiftu: klatka kluczowa nie później niż [wall]. */
    fun requestBuffer(buf: TimeshiftBuffer, wall: Long, video: Pair<Int, String>?) {
        if (video == null) return
        submit { s ->
            val (pos, _) = buf.keyframeAt(wall) ?: return@submit
            val len = minOf(buf.total - pos, data.size.toLong()).toInt()
            var got = 0
            while (got < len) { val n = buf.read(pos + got, data, got, len - got); if (n <= 0) break; got += n }
            if (s != seq.get()) return@submit
            decode(s, got, 0, video)
        }
    }

    // nagranie: ścieżka obrazu i PTS początku z pierwszych MB (raz na źródło)
    private var recSrc: ByteSrc? = null
    private var recVideo: Pair<Int, String>? = null
    private var recPts0 = -1L

    /** Klatka z nagrania na pozycji [posMs] z [durMs] (bajt szacowany z proporcji, poprawiany wg PTS). */
    fun requestRecording(src: ByteSrc, posMs: Long, durMs: Long) {
        if (durMs <= 0) return
        submit { s ->
            if (recSrc !== src) {
                recSrc?.close()
                recSrc = src; recVideo = null; recPts0 = -1
                val n = src.read(0, data, 2_000_000)
                recVideo = TsFrames.video(data, n)
                recVideo?.let { recPts0 = TsFrames.firstPts(data, n, it.first) }
                AppLog.i("podgląd przewijania: nagranie — obraz ${recVideo ?: "brak w PAT/PMT"}, rozmiar ${src.size() / 1_000_000} MB")
            }
            val v = recVideo ?: return@submit
            val size = src.size()
            if (size <= 0) return@submit
            var off = (size.toDouble() * posMs / durMs).toLong().coerceIn(0, maxOf(0, size - 1_000_000))
            var tries = 0
            while (true) {
                off -= off % 188
                val got = src.read(off, data, data.size)
                if (s != seq.get()) return@submit
                val k = TsFrames.keyframe(data, got, v.first)
                if (k < 0) return@submit
                // poprawka miejsca wg PTS klatki (zmienna przepływność) — raz
                val pts = TsFrames.pts(data, k)
                if (tries == 0 && pts >= 0 && recPts0 >= 0) {
                    val t = ((pts - recPts0 + (1L shl 33)) % (1L shl 33)) / 90
                    if (t > 1000 && kotlin.math.abs(t - posMs) > 15_000) {
                        tries++
                        off = (off.toDouble() * posMs / t).toLong().coerceIn(0, maxOf(0, size - 1_000_000))
                        continue
                    }
                }
                decode(s, got, k, v)
                return@submit
            }
        }
    }

    private fun submit(task: (Long) -> Unit) {
        val s = seq.incrementAndGet()
        job = { task(s) }
        synchronized(lock) {
            if (worker == null) {
                worker = Thread({ loop() }, "podglad-przewijania").also {
                    it.priority = Thread.NORM_PRIORITY - 1
                    it.start()
                }
            } else lock.notifyAll()
        }
    }

    private fun loop() {
        try {
            while (true) {
                val j = synchronized(lock) {
                    var x = job
                    if (x == null) {
                        lock.wait(15_000)
                        x = job
                        if (x == null) { worker = null; return }   // bez żądań — wątek i dekoder zwalniane
                    }
                    job = null
                    x
                }
                try { j() } catch (e: Exception) {
                    AppLog.i("podgląd przewijania: błąd ($e)")
                    release()
                }
            }
        } catch (_: InterruptedException) {
        } finally { release() }
    }

    private fun decode(s: Long, len: Int, from: Int, video: Pair<Int, String>) {
        val mime = TsFrames.mime(video.second) ?: return
        val name = TsFrames.swDecoder(mime) ?: run { AppLog.i("podgląd przewijania: brak programowego dekodera ${video.second}"); return }
        val k = TsFrames.keyframe(data, len, video.first, from)
        if (k < 0) return
        val n = TsFrames.frame(data, len, video.first, k, au)
        if (n <= 0) return
        var surf = surface
        var wait = 0
        while (surf == null && wait++ < 20) { Thread.sleep(25); surf = surface }
        if (surf == null || !surf.isValid || s != seq.get()) return
        var c = codec
        if (c != null && (codecMime != mime || codecSurface !== surf)) { release(); c = null }
        if (c == null) {
            val fmt = MediaFormat.createVideoFormat(mime, 1920, 1088)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, au.size)
            c = MediaCodec.createByCodecName(name)
            try { c.configure(fmt, surf, null, 0); c.start() } catch (e: Exception) { c.release(); throw e }
            codec = c; codecMime = mime; codecSurface = surf
        }
        val t0 = SystemClock.elapsedRealtime()
        val info = MediaCodec.BufferInfo()
        var queued = false
        var eos = false
        var ok = false
        try {
            while (SystemClock.elapsedRealtime() - t0 < 2000) {
                if (!eos) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        if (!queued) {
                            val ib = c.getInputBuffer(ii)!!
                            ib.clear(); ib.put(au, 0, n)
                            c.queueInputBuffer(ii, 0, n, 0, MediaCodec.BUFFER_FLAG_KEY_FRAME); queued = true
                        } else { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val end = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val img = !end || info.size > 0
                    // nowsze żądanie albo nakładka już schowana — bez pokazywania
                    val show = img && s == seq.get()
                    c.releaseOutputBuffer(oi, show)
                    if (show) ui.post { if (s == seq.get()) tv?.alpha = 1f }
                    if (img || end) { ok = true; break }
                }
            }
        } finally {
            if (!ok) release() else try { c.flush() } catch (_: Exception) { release() }
        }
    }

    private fun release() {
        val c = codec ?: return
        codec = null; codecSurface = null
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
    }
}
