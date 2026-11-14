package pl.jardol.e2stream.desktop

import java.net.HttpURLConnection
import java.net.URI

/**
 * Wykrywanie zawieszonego obrazu (jak watchdog VLC w Androidzie): czas odtwarzania VLC stoi dłużej niż
 * [stallMs] + 2 s, a zaraz po starcie strumienia — dłużej niż [startupMs]. Pauza użytkownika nie jest zawieszeniem.
 */
internal class StallWatch(private val stallMs: Long, private val startupMs: Long = 15_000) {
    private var lastTime = Long.MIN_VALUE
    private var lastMove = 0L
    private var started = false

    /** Nowe odtwarzanie (zmiana kanału, wznowienie). */
    fun reset(now: Long) {
        lastTime = Long.MIN_VALUE
        lastMove = now
        started = false
    }

    /** Wywoływane co sekundę; true = zawieszenie (licznik zaczyna się od nowa). */
    fun check(now: Long, time: Long, paused: Boolean): Boolean {
        if (paused) {
            lastMove = now
            return false
        }
        if (time != lastTime) {
            if (lastTime != Long.MIN_VALUE && time > 0) started = true
            lastTime = time
            lastMove = now
            return false
        }
        val limit = if (started) stallMs + 2000 else startupMs
        if (now - lastMove <= limit) return false
        lastMove = now
        return true
    }
}

/** Kolejne próby wznowienia: 0, 1, 2, 5, 10 s; po 30 s płynnego odtwarzania od zera. */
internal class RestartBackoff(private val healthyMs: Long = 30_000) {
    private val delays = longArrayOf(0, 1000, 2000, 5000, 10_000)
    var streak = 0
        private set
    private var healthySince = 0L

    fun nextDelay(): Long = delays[minOf(streak, delays.lastIndex)].also { streak++ }

    fun playing(now: Long) {
        if (healthySince == 0L) healthySince = now
        if (streak > 0 && now - healthySince >= healthyMs) streak = 0
    }

    fun stalled() {
        healthySince = 0L
    }
}

/** Czy dekoder odpowiada (OpenWebif, a gdy nie — port strumienia); dowolna odpowiedź HTTP = dostępny. */
internal fun probeDecoder(config: DesktopConfig, timeoutMs: Int = 1500): Boolean {
    val host = config.host.trim().let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
    if (host.isBlank()) return false
    for (port in listOf(config.httpPort, config.streamPort).distinct()) {
        val connection = runCatching { URI("http://$host:$port/").toURL().openConnection() as HttpURLConnection }.getOrNull() ?: continue
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            if (connection.responseCode > 0) return true
        } catch (_: Exception) {
        } finally {
            connection.disconnect()
        }
    }
    return false
}
