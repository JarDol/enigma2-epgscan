package pl.jardol.e2stream.desktop

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

internal fun monotonicMs(): Long = System.nanoTime() / 1_000_000

/**
 * Bufor timeshiftu: strumień TS kanału zapisywany w kawałkach po 1 minucie (ts-<sesja>N.ts).
 * Kawałki to kolejne fragmenty jednego ciągłego strumienia z samych pełnych pakietów TS (188 B).
 * Najstarsza minuta wypada, gdy okno przekroczy [maxMin] albo zabraknie miejsca na dysku.
 * Czas to zegar monotoniczny ([clock]) chwili odbioru danych.
 */
internal class TimeshiftBuffer(
    val dir: File,
    @Volatile var maxMin: Int,
    private val clock: () -> Long = ::monotonicMs,
    private val reserveBytes: Long = RESERVE,
) : Closeable {
    private class Segment(val file: File, val start: Long, val wall: Long) {
        @Volatile var length = 0L
    }

    private val segments = ArrayList<Segment>()
    private val lock = Object()
    private var output: FileOutputStream? = null
    private var nextIndex = 0
    private val pending = ByteArray(PACKET)
    private var pendingLength = 0

    @Volatile var total = 0L
        private set
    @Volatile var failed: String? = null
        private set
    /** Najstarsza minuta wypadła już z okna. */
    @Volatile var trimmed = false
        private set

    @Volatile private var session = newSession()

    init {
        dir.mkdirs()
        cleanupAsync(dir, session)
    }

    private fun newSession() = "ts-" + java.lang.Long.toString(System.currentTimeMillis(), 36) + "-"

    /** Nowe połączenie ze strumieniem: niepełny pakiet z poprzedniego jest porzucany. */
    fun resync() = synchronized(lock) { pendingLength = 0 }

    /** Do bufora trafiają tylko całe pakiety TS — odczyt od początku dowolnej minuty trafia w granicę pakietu. */
    fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            if (failed != null) return
            try {
                var position = offset
                val end = offset + length
                if (pendingLength > 0) {
                    val count = minOf(PACKET - pendingLength, end - position)
                    System.arraycopy(bytes, position, pending, pendingLength, count)
                    pendingLength += count
                    position += count
                    if (pendingLength < PACKET) return
                    pendingLength = 0
                    if (pending[0] == SYNC) writeRaw(pending, 0, PACKET)
                }
                if (total == 0L) {
                    while (position < end && !aligned(bytes, position, end)) position++
                }
                var run = position
                while (end - position >= PACKET) {
                    if (bytes[position] != SYNC) {
                        if (position > run) writeRaw(bytes, run, position - run)
                        var next = position + 1
                        while (next < end && bytes[next] != SYNC) next++
                        position = next
                        run = next
                        continue
                    }
                    position += PACKET
                }
                if (position > run) writeRaw(bytes, run, position - run)
                if (position < end && bytes[position] == SYNC) {
                    pendingLength = end - position
                    System.arraycopy(bytes, position, pending, 0, pendingLength)
                }
            } catch (e: IOException) {
                failed = e.message ?: e.toString()
                AppLog.i("timeshift: błąd zapisu — bufor wyłączony ($failed)")
            }
            lock.notifyAll()
        }
    }

    private fun aligned(bytes: ByteArray, position: Int, end: Int): Boolean =
        bytes[position] == SYNC &&
            (position + PACKET >= end || bytes[position + PACKET] == SYNC) &&
            (position + 2 * PACKET >= end || bytes[position + 2 * PACKET] == SYNC)

    private fun writeRaw(bytes: ByteArray, offset: Int, length: Int) {
        val now = clock()
        val current = segments.lastOrNull()
        if (current == null || output == null || now - current.wall >= 60_000) rotate(now)
        output!!.write(bytes, offset, length)
        segments.last().length += length
        total += length
    }

    private fun rotate(now: Long) {
        runCatching { output?.close() }
        val file = File(dir, "$session${nextIndex++}.ts")
        output = FileOutputStream(file)
        segments += Segment(file, total, now)
        while (segments.size > maxOf(2, maxMin + 1)) dropOldest()
        while (segments.size > 2 && dir.usableSpace < reserveBytes) dropOldest()
    }

    private fun dropOldest() {
        val file = segments.removeAt(0).file
        trimmed = true
        thread(name = "timeshift-usun", isDaemon = true) { file.delete() }
    }

    val oldestWall: Long get() = synchronized(lock) { segments.firstOrNull()?.wall ?: clock() }

    /** Długość zapisanego okna (ms). */
    fun spanMs(): Long = if (total == 0L) 0 else clock() - oldestWall

    /** Bajt (początek pakietu TS) odpowiadający chwili [wall]. */
    fun offsetAt(wall: Long): Long = synchronized(lock) {
        if (segments.isEmpty()) return 0
        val now = clock()
        for (index in segments.indices) {
            val segment = segments[index]
            val endWall = segments.getOrNull(index + 1)?.wall ?: now
            if (wall < endWall || index == segments.lastIndex) {
                if (wall <= segment.wall) return segment.start
                val duration = (endWall - segment.wall).coerceAtLeast(1)
                val fraction = ((wall - segment.wall).toDouble() / duration).coerceIn(0.0, 1.0)
                val raw = segment.start + (segment.length * fraction).toLong()
                val packet = raw - (raw - segment.start) % PACKET
                return packet.coerceAtMost(maxOf(segment.start, total - PACKET * 1000L))
            }
        }
        segments.last().start
    }

    /** Chwila odbioru odpowiadająca bajtowi [offset]. */
    fun wallAt(offset: Long): Long = synchronized(lock) {
        if (segments.isEmpty()) return clock()
        val now = clock()
        for (index in segments.indices.reversed()) {
            val segment = segments[index]
            if (offset >= segment.start) {
                val endWall = segments.getOrNull(index + 1)?.wall ?: now
                val fraction = if (segment.length > 0) {
                    ((offset - segment.start).toDouble() / segment.length).coerceIn(0.0, 1.0)
                } else 0.0
                return segment.wall + ((endWall - segment.wall) * fraction).toLong()
            }
        }
        segments.first().wall
    }

    private fun locate(position: Long): Pair<File, Long>? = synchronized(lock) {
        for (index in segments.indices.reversed()) {
            val segment = segments[index]
            if (position >= segment.start) {
                return if (position - segment.start < segment.length || index == segments.lastIndex) {
                    segment.file to (position - segment.start)
                } else null
            }
        }
        null
    }

    /** Czeka na dane za [position] najwyżej [timeoutMs]; false — brak danych. */
    fun await(position: Long, timeoutMs: Long, closed: () -> Boolean): Boolean {
        val until = monotonicMs() + timeoutMs
        synchronized(lock) {
            while (total <= position && !closed()) {
                val left = until - monotonicMs()
                if (left <= 0) return false
                lock.wait(minOf(left, 500))
            }
        }
        return total > position
    }

    /** Odczyt od bajtu [position]; -1 — dane już usunięte z okna. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val (file, inSegment) = locate(position) ?: return -1
        val available = synchronized(lock) { total - position }
        if (available <= 0) return 0
        return try {
            RandomAccessFile(file, "r").use { input ->
                input.seek(inSegment)
                input.read(buffer, offset, minOf(length.toLong(), available).toInt()).coerceAtLeast(0)
            }
        } catch (_: IOException) {
            -1
        }
    }

    /** Koniec bufora: pliki skasowane w tle. */
    override fun close() = synchronized(lock) {
        runCatching { output?.close() }
        output = null
        segments.clear()
        total = 0
        pendingLength = 0
        session = newSession()
        cleanupAsync(dir, session)
        lock.notifyAll()
    }

    companion object {
        const val PACKET = 188
        private const val SYNC = 0x47.toByte()

        /** Zapas wolnego miejsca, którego bufor nie rusza. */
        const val RESERVE = 1_000L * 1_000_000

        /** Ostrożne oszacowanie: kanał HD do ~8 Mb/s = 60 MB na minutę. */
        const val MB_PER_MIN = 60L

        val CHOICES = listOf(0, 15, 30, 60, 90, 120, 180, 240, 300, 360)

        /** %LOCALAPPDATA%\E2Stream\timeshift — dane tymczasowe nie trafiają do profilu mobilnego. */
        fun defaultDir(): File {
            val local = System.getenv("LOCALAPPDATA")
            return if (!local.isNullOrBlank()) Paths.get(local, "E2Stream", "timeshift").toFile()
            else DesktopConfig.appDataDir().resolve("timeshift").toFile()
        }

        fun dirFor(config: DesktopConfig): File =
            config.tsDir.takeIf(String::isNotBlank)?.let { File(it, "timeshift") } ?: defaultDir()

        /** Ile minut timeshiftu zmieści się w katalogu [dir] (z zapasem). */
        fun fitMinutes(dir: File): Int {
            val free = runCatching { dir.mkdirs(); dir.usableSpace }.getOrDefault(0L)
            val usable = free - maxOf(RESERVE, free / 10)
            return if (usable <= 0) 0 else (usable / (MB_PER_MIN * 1_000_000)).toInt()
        }

        /** Kasuje w tle pliki bufora poza bieżącą sesją [keep]. */
        fun cleanupAsync(dir: File, keep: String? = null) {
            thread(name = "timeshift-sprzatanie", isDaemon = true) {
                runCatching {
                    var count = 0
                    dir.listFiles { file ->
                        file.name.startsWith("ts-") && file.name.endsWith(".ts") && (keep == null || !file.name.startsWith(keep))
                    }?.forEach { if (it.delete()) count++ }
                    if (count > 0) AppLog.i("timeshift: usunięto $count plików bufora")
                }
            }
        }
    }
}

/** Pobiera strumień kanału z dekodera do bufora; po zerwaniu łączy się ponownie. */
internal class TimeshiftRecorder(
    private val url: String,
    private val authorization: String?,
    private val buffer: TimeshiftBuffer,
    /** Podgląd danych ze strumienia (np. EIT) — w wątku zapisu. */
    private val tap: ((ByteArray, Int, Int) -> Unit)? = null,
) : Closeable {
    @Volatile private var running = true
    @Volatile private var connection: HttpURLConnection? = null

    init {
        thread(name = "timeshift-zapis", isDaemon = true) { run() }
    }

    private fun run() {
        var failures = 0
        while (running) {
            try {
                val current = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", "E2Stream")
                    authorization?.let { setRequestProperty("Authorization", it) }
                }
                connection = current
                current.inputStream.use { input ->
                    buffer.resync()
                    val bytes = ByteArray(TimeshiftBuffer.PACKET * 700)
                    while (running) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        buffer.write(bytes, 0, count)
                        tap?.let { runCatching { it(bytes, 0, count) } }
                        failures = 0
                    }
                }
            } catch (e: Exception) {
                if (running) AppLog.i("timeshift: zapis strumienia przerwany (${e.message ?: e.javaClass.simpleName})")
            } finally {
                runCatching { connection?.disconnect() }
            }
            if (running) {
                failures++
                runCatching { Thread.sleep(minOf(5000L, 500L * failures)) }
            }
        }
    }

    override fun close() {
        running = false
        runCatching { connection?.disconnect() }
    }
}

/**
 * Lokalny serwer HTTP (127.0.0.1, losowy port), z którego VLC czyta bufor timeshiftu:
 * GET /ts/<n>.ts?off=<bajt> — strumień od podanego bajtu, doganiający zapis na bieżąco.
 */
internal class TimeshiftServer(private val buffer: TimeshiftBuffer) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false

    val port: Int get() = server.localPort

    init {
        thread(name = "timeshift-serwer", isDaemon = true) {
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                clients += socket
                thread(name = "timeshift-klient", isDaemon = true) { serve(socket) }
            }
        }
    }

    fun url(offset: Long, generation: Int): String = "http://127.0.0.1:$port/ts/$generation.ts?off=$offset"

    private fun serve(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = 10_000
                val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val request = reader.readLine().orEmpty().split(' ')
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
                val offset = OFFSET.find(request.getOrNull(1).orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                val method = request.firstOrNull()
                if ((method != "GET" && method != "HEAD") || offset == null) {
                    output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    return
                }
                output.write(
                    "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
                        .toByteArray(),
                )
                output.flush()
                if (method == "HEAD") return
                var position = offset
                val bytes = ByteArray(TimeshiftBuffer.PACKET * 512)
                while (!closed) {
                    if (!buffer.await(position, 12_000) { closed }) break
                    val count = buffer.read(position, bytes, 0, bytes.size)
                    if (count < 0) break
                    if (count > 0) {
                        output.write(bytes, 0, count)
                        output.flush()
                        position += count
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            clients -= socket
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        clients.forEach { runCatching { it.close() } }
    }

    private companion object {
        val OFFSET = Regex("[?&]off=(\\d+)")
    }
}

/**
 * Timeshift bieżącego kanału: zapis strumienia z dekodera do bufora i adresy, z których VLC odtwarza
 * dowolne miejsce bufora (także „na żywo” — koniec bufora).
 */
internal class TimeshiftSession(
    val buffer: TimeshiftBuffer,
    sourceUrl: String,
    authorization: String?,
    tap: ((ByteArray, Int, Int) -> Unit)? = null,
) : Closeable {
    private val recorder = TimeshiftRecorder(sourceUrl, authorization, buffer, tap)
    private val server = TimeshiftServer(buffer)
    private var generation = 0

    /** Chwila odbioru odpowiadająca początkowi bieżącego odtwarzania VLC. */
    var baseWall: Long = monotonicMs()
        private set

    fun urlAt(wall: Long): String {
        val offset = buffer.offsetAt(wall)
        baseWall = if (buffer.total > 0) buffer.wallAt(offset) else monotonicMs()
        return server.url(offset, ++generation)
    }

    fun liveUrl(): String = urlAt(monotonicMs())

    /** Chwila widoczna na ekranie, gdy VLC odtworzył [playedMs] od początku bieżącego adresu. */
    fun shownWall(playedMs: Long): Long = baseWall + playedMs.coerceAtLeast(0)

    override fun close() {
        recorder.close()
        server.close()
        buffer.close()
    }

    companion object {
        fun basicAuth(config: DesktopConfig): String? =
            if (config.username.isBlank()) null
            else "Basic " + Base64.getEncoder().encodeToString("${config.username}:${config.password}".toByteArray(Charsets.UTF_8))
    }
}

/** Gdzie przewinąć: na żywo, na początek bufora albo w wybrane miejsce. */
internal sealed interface TimeshiftTarget {
    data object Live : TimeshiftTarget
    data class At(val wall: Long, val clampedToOldest: Boolean) : TimeshiftTarget
}

internal fun timeshiftTarget(target: Long, now: Long, oldestWall: Long): TimeshiftTarget = when {
    target >= now - LIVE_MARGIN_MS -> TimeshiftTarget.Live
    target < oldestWall -> TimeshiftTarget.At(oldestWall + 10_000, clampedToOldest = true)
    else -> TimeshiftTarget.At(target, clampedToOldest = false)
}

/** Odległość od końca bufora, poniżej której odtwarzanie traktujemy jako „na żywo”. */
internal const val LIVE_MARGIN_MS = 6_000L

internal fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
