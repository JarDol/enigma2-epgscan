package pl.jardol.e2stream.desktop

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** SID programu z referencji Enigma2 „1:0:19:SID:TSID:ONID:…” (hex); -1, gdy referencja nie wskazuje programu. */
internal fun serviceSid(ref: String): Int {
    val parts = ref.trim().split(':')
    if (parts.size < 7 || parts[0] != "1" || parts[1] != "0") return -1
    return parts[3].toIntOrNull(16)?.takeIf { it in 1..0xffff } ?: -1
}

/**
 * EIT „teraz/następne” (PID 0x12, tabela 0x4E) programu [sid] z surowego strumienia TS w dowolnych kawałkach
 * (jak TsAnalyzer w Androidzie). [onEit] dostaje nową wersję teraz/następne z wątku czytającego strumień.
 */
internal class EitReader(private val sid: Int, private val onEit: (EitEvent?, EitEvent?) -> Unit) {
    private val pending = ByteArray(PACKET)
    private var pendingLength = 0
    private val versions = intArrayOf(-1, -1)
    private var present: EitEvent? = null
    private var following: EitEvent? = null
    private val assembler = SectionAssembler { bytes, length -> section(bytes, length) }

    /** Ile pakietów PID 0x12 przyszło (0 = dekoder nie wysyła EIT w strumieniu). */
    @Volatile var eitPackets = 0L
        private set

    @Synchronized
    fun reset() {
        pendingLength = 0
        assembler.reset()
    }

    @Synchronized
    fun feed(bytes: ByteArray, offset: Int, length: Int) {
        var index = offset
        val end = offset + length
        if (pendingLength > 0) {
            val count = minOf(PACKET - pendingLength, length)
            System.arraycopy(bytes, index, pending, pendingLength, count)
            pendingLength += count
            index += count
            if (pendingLength < PACKET) return
            if (pending[0] == SYNC) packet(pending, 0)
            pendingLength = 0
        }
        while (index < end) {
            if (bytes[index] != SYNC) {
                index++
                continue
            }
            if (end - index < PACKET) {
                System.arraycopy(bytes, index, pending, 0, end - index)
                pendingLength = end - index
                return
            }
            packet(bytes, index)
            index += PACKET
        }
    }

    private fun packet(bytes: ByteArray, offset: Int) {
        val b1 = bytes[offset + 1].toInt() and 0xff
        if ((b1 and 0x80) != 0) return                                   // transport_error_indicator
        val pid = ((b1 and 0x1f) shl 8) or (bytes[offset + 2].toInt() and 0xff)
        if (pid != Eit.PID) return
        val b3 = bytes[offset + 3].toInt() and 0xff
        if ((b3 shr 6) != 0) return                                       // kodowany
        val afc = (b3 shr 4) and 3
        if ((afc and 1) == 0) return                                      // bez danych
        var payload = offset + 4
        if ((afc and 2) != 0) payload = offset + 5 + (bytes[offset + 4].toInt() and 0xff)
        eitPackets++
        if (payload < offset + PACKET) assembler.packet(bytes, offset, payload)
    }

    private fun section(bytes: ByteArray, length: Int) {
        val parsed = Eit.parsePf(bytes, length, sid) ?: return
        if (parsed.number > 1 || versions[parsed.number] == parsed.version) return
        versions[parsed.number] = parsed.version
        if (parsed.number == 0) present = parsed.event else following = parsed.event
        runCatching { onEit(present, following) }
    }

    private companion object {
        const val PACKET = 188
        const val SYNC: Byte = 0x47
    }
}

/**
 * Lokalny tunel strumienia (127.0.0.1, losowy port), gdy timeshift jest wyłączony: VLC czyta strumień przez niego,
 * [tap] widzi te same dane (EIT), a brakujący PMT jest wstawiany ([fixerFactory]). Każde połączenie VLC to osobne połączenie z dekoderem.
 */
internal class StreamTap(
    private val sourceUrl: String,
    private val authorization: String?,
    private val tap: ((ByteArray, Int, Int) -> Unit)?,
    /** Poprawki PAT/PMT dla każdego połączenia VLC (wstawianie zapamiętanego PMT); null — dane bez zmian. */
    private val fixerFactory: (() -> TsStreamFixer)? = null,
) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val upstreams = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    @Volatile private var closed = false

    val url: String get() = "http://127.0.0.1:${server.localPort}/live.ts"

    init {
        thread(name = "strumien-tunel", isDaemon = true) {
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                sockets += socket
                thread(name = "strumien-klient", isDaemon = true) { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        var upstream: HttpURLConnection? = null
        try {
            socket.use {
                socket.soTimeout = 10_000
                val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val method = reader.readLine().orEmpty().substringBefore(' ')
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
                if (method != "GET" && method != "HEAD") {
                    output.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    return
                }
                val connection = (URI(sourceUrl).toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", "E2Stream")
                    authorization?.let { setRequestProperty("Authorization", it) }
                }
                upstream = connection
                upstreams += connection
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    output.write("HTTP/1.1 $code Upstream\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    return
                }
                output.write(
                    "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray(),
                )
                output.flush()
                if (method == "HEAD") return
                val fixer = fixerFactory?.invoke()
                val forward: (ByteArray, Int, Int) -> Unit = { b, o, l -> output.write(b, o, l) }
                connection.inputStream.use { input ->
                    val bytes = ByteArray(188 * 512)
                    while (!closed) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        if (count == 0) continue
                        tap?.let { runCatching { it(bytes, 0, count) } }
                        if (fixer != null) fixer.feed(bytes, 0, count, forward) else output.write(bytes, 0, count)
                        output.flush()
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            upstream?.let {
                upstreams -= it
                runCatching { it.disconnect() }
            }
            sockets -= socket
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        upstreams.forEach { runCatching { it.disconnect() } }
    }
}
