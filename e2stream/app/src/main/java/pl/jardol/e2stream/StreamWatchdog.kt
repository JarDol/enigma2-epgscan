package pl.jardol.e2stream

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer

/**
 * Pilnuje, czy odtwarzanie faktycznie idzie do przodu.
 *
 * W trybie tunelowanym zawieszony dekoder sprzętowy nie zgłasza błędu — obraz
 * po prostu staje. Dlatego co 500 ms sprawdzamy:
 *  1. licznik klatek dekodera wideo (rendered + skipped + dropped),
 *  2. zegar odtwarzania (currentPosition),
 *  3. czas buforowania.
 * Jeśli coś stoi dłużej niż limit, wywołujemy onStall(powód).
 *
 * Kontrola klatek uzbraja się dopiero, gdy licznik raz realnie urośnie —
 * niektóre urządzenia w trybie tunelowanym nie raportują klatek i wtedy
 * zostaje sama kontrola zegara (bez fałszywych restartów).
 */
class StreamWatchdog(
    private val player: ExoPlayer,
    private val stallMs: Long,
    private val bufferingMs: Long,
    private val onStall: (String) -> Unit,
    private val onHealthy: () -> Unit,
    private val dropRestart: Boolean = true,
    /** Zapas, przy którym ExoPlayer rusza na starcie / po niedoborze (setBufferDurationsMs). */
    private val startMs: Long = 0,
    private val reserveMs: Long = 0,
) {
    // --- start kanału: od nowego strumienia do chwili, gdy obraz naprawdę idzie
    private var startPhase = false
    private var startAt = 0L
    private var everReady = false
    private var startPos = -1L
    private var startFrames = -1L
    private var startLastPos = -1L
    private var startCountersRef: DecoderCounters? = null
    private var startRenderedBaseline = 0
    private var startProgressAt = 0L
    private var fastSince = 0L
    private val hist = ArrayDeque<LongArray>()

    /** Wstrzymanie (np. kanał nierozkodowany) — bez restartów. */
    var paused = false
        set(v) { field = v; reset() }
    private val h = Handler(Looper.getMainLooper())
    private var running = false

    private var countersRef: DecoderCounters? = null
    private var lastFrames = -1L
    private var lastFrameChange = 0L
    private var framesMoving = false
    private var lastPos = -1L
    private var lastPosChange = 0L
    private var bufferingSince = 0L
    private var wasReady = false
    private var healthySince = 0L
    private var healthyReported = false

    var mode: String = "-"
        private set
    var frames: Long = 0
        private set
    var dropped: Int = 0
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            check()
            h.postDelayed(this, 500)
        }
    }

    fun start() {
        if (running) return
        running = true
        reset()
        h.post(tick)
    }

    /**
     * Nowy strumień na żywo (zmiana kanału, wznowienie): [fast] — ustawienie „Przyspiesz start kanałów”;
     * do chwili, gdy obraz ruszy, działa dodatkowa reguła startu ([checkStart]).
     */
    fun newStream(fast: Boolean) {
        reset()
        val now = SystemClock.elapsedRealtime()
        startPhase = fast
        startAt = now
        everReady = false
        startPos = -1; startFrames = -1; startLastPos = -1
        startCountersRef = player.videoDecoderCounters?.also { it.ensureUpdated() }
        startRenderedBaseline = startCountersRef?.renderedOutputBufferCount ?: 0
        startProgressAt = now
    }

    /** Odtwarzanie z bufora / nagrania — bez reguły startu kanału. */
    fun endStart() { startPhase = false }

    fun stop() {
        running = false
        h.removeCallbacks(tick)
    }

    fun reset() {
        val now = SystemClock.elapsedRealtime()
        countersRef = null
        lastFrames = -1
        lastFrameChange = now
        framesMoving = false
        lastPos = -1
        lastPosChange = now
        bufferingSince = 0
        wasReady = false
        hist.clear()
        healthySince = 0
        healthyReported = false
        fastSince = 0
    }

    /**
     * Start kanału: dane od dekodera są (zapas ≥ próg startu ExoPlayera + 1,5 s), a odtwarzacz od 2 s nie rusza —
     * stoi w buforowaniu albo w gotowości bez nowych klatek i bez ruchu zegara. To zawieszony dekoder obrazu
     * (np. pierwsza klatka i koniec), nie brak danych — wznowienie od razu, bez czekania na limit buforowania.
     * Gdy danych nie ma (dekoder Enigmy2 jeszcze stroi), działają dotychczasowe reguły.
     */
    private fun checkStart(now: Long): Boolean {
        val st = player.playbackState
        if (st == Player.STATE_READY) everReady = true
        val c = player.videoDecoderCounters
        c?.ensureUpdated()
        val rendered = c?.renderedOutputBufferCount?.toLong() ?: 0L
        val f = if (c != null) rendered + c.skippedOutputBufferCount + c.droppedBufferCount else -1L
        if (c !== startCountersRef) {
            startCountersRef = c
            startRenderedBaseline = c?.renderedOutputBufferCount ?: 0
        } else if (c != null && c.renderedOutputBufferCount < startRenderedBaseline) {
            startRenderedBaseline = c.renderedOutputBufferCount
        }
        val pos = player.currentPosition
        if (st == Player.STATE_READY && startPos < 0) startPos = pos
        val framesStarted = hasRenderedEnoughSinceStart(c?.renderedOutputBufferCount ?: 0, startRenderedBaseline)
        val clockAdvanced = startPos >= 0 && pos - startPos >= 3000
        if (framesStarted || clockAdvanced) {
            startPhase = false
            if (framesStarted) AppLog.i("start kanału: obraz ruszył po ${now - startAt} ms")
            else AppLog.i("start kanału: zegar odtwarzania ruszył po ${now - startAt} ms (bez potwierdzenia licznikiem klatek)")
            return false
        }

        if (f != startFrames || pos != startLastPos) {
            startFrames = f; startLastPos = pos; startProgressAt = now
        }
        val buffered = player.totalBufferedDuration
        val need = (if (everReady) reserveMs else startMs) + 1500
        val stuck = buffered >= need &&
            (st == Player.STATE_BUFFERING || (st == Player.STATE_READY && now - startProgressAt > 1500))
        if (!stuck) { fastSince = 0; return false }
        if (fastSince == 0L) { fastSince = now; return false }
        if (now - fastSince < 2000) return false
        startPhase = false
        fire("start kanału: dane są (zapas $buffered ms), odtwarzacz nie rusza od ${(now - startProgressAt) / 1000} s")
        return true
    }

    private fun check() {
        val now = SystemClock.elapsedRealtime()
        if (!player.playWhenReady || paused) {
            reset(); return
        }
        if (startPhase && checkStart(now)) return
        when (player.playbackState) {
            Player.STATE_BUFFERING -> {
                healthySince = 0
                if (bufferingSince == 0L) bufferingSince = now
                else if (now - bufferingSince > bufferingMs) {
                    fire("buforowanie > ${bufferingMs / 1000} s")
                }
                return
            }
            Player.STATE_READY -> {
                bufferingSince = 0
                if (!wasReady) {
                    wasReady = true
                    lastFrameChange = now
                    lastPosChange = now
                }
            }
            else -> {
                bufferingSince = 0
                return
            }
        }

        val c = player.videoDecoderCounters
        if (c !== countersRef) {
            countersRef = c
            hist.clear()
            lastFrames = -1
            framesMoving = false
            lastFrameChange = now
        }
        if (c != null) {
            c.ensureUpdated()
            val f = c.renderedOutputBufferCount.toLong() + c.skippedOutputBufferCount + c.droppedBufferCount
            frames = c.renderedOutputBufferCount.toLong()
            dropped = c.droppedBufferCount
            if (f != lastFrames) {
                if (lastFrames >= 0) framesMoving = true
                lastFrames = f
                lastFrameChange = now
            }
            // masowa utrata klatek (zacinanie): w ciągu ~3 s więcej klatek utraconych niż pokazanych
            if (dropRestart) {
                hist.addLast(longArrayOf(now, c.renderedOutputBufferCount.toLong(), c.droppedBufferCount.toLong()))
                while (hist.size > 1 && now - hist.first()[0] > 3000) hist.removeFirst()
                val first = hist.first()
                val dr = c.droppedBufferCount - first[2]
                val rr = c.renderedOutputBufferCount - first[1]
                if (now - first[0] >= 2500 && dr >= 40 && dr > rr) {
                    fire("zacinanie: utracono $dr z ${dr + rr} klatek w ${(now - first[0]) / 1000} s"); return
                }
            }
        }

        val pos = player.currentPosition
        if (pos != lastPos) {
            lastPos = pos
            lastPosChange = now
        }

        val hasVideo = player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
        val frameCheck = hasVideo && framesMoving
        mode = if (frameCheck) "klatki + zegar" else "zegar"

        if (frameCheck && now - lastFrameChange > stallMs) {
            fire("obraz stoi ${(now - lastFrameChange) / 1000} s"); return
        }
        if (now - lastPosChange > stallMs) {
            fire("zegar stoi ${(now - lastPosChange) / 1000} s"); return
        }

        if (healthySince == 0L) healthySince = now
        if (!healthyReported && now - healthySince > 10_000) {
            healthyReported = true
            onHealthy()
        }
    }

    private fun fire(reason: String) {
        reset()
        onStall(reason)
    }
}

internal fun hasRenderedEnoughSinceStart(rendered: Int, baseline: Int): Boolean =
    rendered >= baseline && rendered - baseline >= 25
