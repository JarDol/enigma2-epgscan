package pl.jardol.e2stream.desktop

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import jdk.net.ExtendedSocketOptions

/** Zdarzenie z WinLIRC / LIRC: „<kod> <powtórzenie> <przycisk> <pilot>”. */
internal data class LircEvent(val remote: String, val button: String, val repeat: Int) {
    val physicalId: String get() = UniversalRemoteMap.lircId(remote, button)
}

internal fun parseLircLine(line: String): LircEvent? {
    val parts = line.trim().split(Regex("\\s+"))
    if (parts.size < 4) return null
    val repeat = parts[1].toIntOrNull(16) ?: return null
    if (parts[2].isBlank() || parts[3].isBlank()) return null
    return LircEvent(remote = parts[3], button = parts[2], repeat = repeat)
}

/**
 * Pilot IR przez WinLIRC (Windows) lub serwer LIRC: klient TCP odbiera nazwy przycisków.
 * Zdarzenia przychodzą niezależnie od okna w pierwszym planie, dlatego Power działa także w tle.
 * Po zerwaniu połączenia klient łączy się ponownie co 5 s.
 */
internal class LircClient(
    private val host: String,
    private val port: Int,
    private val onEvent: (LircEvent) -> Unit,
    private val onState: (String) -> Unit,
) : Closeable {
    private val running = AtomicBoolean(true)
    @Volatile private var socket: Socket? = null
    private val thread = Thread(::run, "E2Stream-LIRC").apply {
        isDaemon = true
        start()
    }

    private fun run() {
        while (running.get()) {
            try {
                onState("łączenie z $host:$port…")
                Socket().use { connection ->
                    socket = connection
                    enableKeepAlive(connection)
                    connection.connect(InetSocketAddress(host, port), 3000)
                    onState("połączono z $host:$port")
                    AppLog.i("pilot IR: połączono z serwerem LIRC $host:$port")
                    val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.UTF_8))
                    while (running.get()) {
                        val line = reader.readLine() ?: break
                        parseLircLine(line)?.let(onEvent)
                    }
                }
                if (running.get()) {
                    AppLog.i("pilot IR: serwer $host:$port zamknął połączenie")
                    onState("serwer zamknął połączenie — ponowna próba za 5 s")
                }
            } catch (e: Exception) {
                if (running.get()) {
                    AppLog.i("pilot IR: brak połączenia z $host:$port (${e.message ?: e.javaClass.simpleName})")
                    onState("brak połączenia z $host:$port (${e.message ?: e.javaClass.simpleName}) — ponowna próba za 5 s")
                }
            } finally {
                socket = null
            }
            if (running.get()) runCatching { Thread.sleep(5000) }
        }
    }

    /** Pilot może milczeć godzinami, więc zerwane połączenie wykrywa TCP keep-alive zamiast limitu czasu odczytu. */
    private fun enableKeepAlive(connection: Socket) {
        connection.keepAlive = true
        val supported = runCatching { connection.supportedOptions() }.getOrDefault(emptySet())
        if (ExtendedSocketOptions.TCP_KEEPIDLE in supported) runCatching { connection.setOption(ExtendedSocketOptions.TCP_KEEPIDLE, 30) }
        if (ExtendedSocketOptions.TCP_KEEPINTERVAL in supported) runCatching { connection.setOption(ExtendedSocketOptions.TCP_KEEPINTERVAL, 10) }
        if (ExtendedSocketOptions.TCP_KEEPCOUNT in supported) runCatching { connection.setOption(ExtendedSocketOptions.TCP_KEEPCOUNT, 3) }
    }

    override fun close() {
        if (running.getAndSet(false)) AppLog.i("pilot IR: rozłączanie z $host:$port")
        runCatching { socket?.close() }
        thread.interrupt()
    }
}
