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
import java.util.UUID
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
    private val freeBytes: () -> Long = { dir.usableSpace },
) : Closeable {
    private class Segment(val file: File, val start: Long, val wall: Long) {
        @Volatile var length = 0L
    }

    private val segments = ArrayList<Segment>()
    private val lock = Object()
    private var closed = false
    private var lastSpaceCheck = Long.MIN_VALUE
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

    private val session = newSession()

    /** Nagranie z bufora: kawałki z zakresu są przenoszone do [dir] zamiast kasowania (bez kopiowania). */
    internal class Recording(
        val dir: File,
        val fromWall: Long,
        val untilWall: Long,
        /** Najwięcej bajtów nagrania (limit zajętości nośnika). */
        val maxBytes: Long,
        val onFinished: (RecordingResult) -> Unit,
    ) {
        var files = 0
        var bytes = 0L
        var firstWall = Long.MAX_VALUE
        var lastWall = 0L
        val failedPaths = mutableListOf<String>()
    }

    internal data class RecordingResult(
        val dir: File,
        val files: Int,
        val bytes: Long,
        val firstWall: Long,
        val endWall: Long,
        val reason: String,
        val failedPaths: List<String>,
    )

    @Volatile private var recording: Recording? = null

    val isRecording: Boolean get() = recording != null

    init {
        dir.mkdirs()
        registerSession(dir, session)
        cleanupAsync(dir, session)
    }

    private fun newSession() = "ts-" + UUID.randomUUID().toString() + "-"

    /** Nowe połączenie ze strumieniem: niepełny pakiet z poprzedniego jest porzucany. */
    fun resync() = synchronized(lock) { pendingLength = 0 }

    /** Do bufora trafiają tylko całe pakiety TS — odczyt od początku dowolnej minuty trafia w granicę pakietu. */
    fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            if (closed || failed != null) return
            try {
                val checkAt = clock()
                if (reserveBytes > 0 && (lastSpaceCheck == Long.MIN_VALUE || checkAt - lastSpaceCheck >= 1_000)) {
                    lastSpaceCheck = checkAt
                    if (freeBytes() < reserveBytes) throw IOException("brak wolnego miejsca na dysku")
                }
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
                if (recording != null) finishLocked("błąd zapisu bufora")
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
        recording?.let { rec ->
            val pending = recordingPendingBytes(rec.fromWall, rec.untilWall)
            val remaining = (rec.maxBytes - rec.bytes).coerceAtLeast(0)
            if (pending > remaining || length.toLong() > remaining - pending) {
                finishLocked("osiągnięto limit miejsca na nagrania")
            }
        }
        val now = clock()
        val current = segments.lastOrNull()
        if (current == null || output == null || now - current.wall >= 60_000) rotate(now)
        output!!.write(bytes, offset, length)
        segments.last().length += length
        total += length
    }

    private fun rotate(now: Long) {
        recording?.let { rec ->
            val pending = segments.filter { segmentEnd(it) > rec.fromWall }.sumOf { it.length }
            when {
                now >= rec.untilWall -> finishLocked("koniec zakresu nagrania")
                rec.bytes + pending > rec.maxBytes -> finishLocked("osiągnięto limit miejsca na nagrania")
            }
        }
        runCatching { output?.close() }
        val file = File(dir, "$session${nextIndex++}.ts")
        output = FileOutputStream(file)
        segments += Segment(file, total, now)
        if (recording != null) createRecoveryMarker(file)
        while (segments.size > maxOf(2, maxMin + 1)) dropOldest()
        while (segments.size > 2 && freeBytes() < reserveBytes) dropOldest()
    }

    private fun dropOldest() {
        val end = segmentEnd(segments[0])
        val segment = segments.removeAt(0)
        trimmed = true
        val rec = recording
        if (rec != null && end > rec.fromWall && segment.wall < rec.untilWall) {
            moveToRecording(rec, segment, end)
        } else {
            deleteSegmentAsync(segment.file)
        }
    }

    private fun segmentEnd(segment: Segment): Long {
        val index = segments.indexOf(segment)
        return segments.getOrNull(index + 1)?.wall ?: if (index >= 0) clock() else segment.wall + 60_000
    }

    private fun recordingPendingBytes(fromWall: Long, untilWall: Long): Long {
        val now = clock()
        return segments.indices.sumOf { index ->
            val segment = segments[index]
            val end = segments.getOrNull(index + 1)?.wall ?: now
            if (end >= fromWall && segment.wall < untilWall) segment.length else 0L
        }
    }

    /** Przeniesienie kawałka do katalogu nagrania (zmiana nazwy; przy otwartym odczycie — kilka prób, potem kopia). */
    private fun moveToRecording(rec: Recording, segment: Segment, end: Long) {
        if (segment.length <= 0) {
            segment.file.delete()
            recoveryMarker(segment.file).delete()
            return
        }
        val target = File(rec.dir, "%05d.ts".format(rec.files))
        var moved = false
        for (attempt in 0 until 20) {
            if (segment.file.renameTo(target)) {
                moved = true
                break
            }
            Thread.sleep(25)
        }
        if (!moved) {
            moved = runCatching {
                segment.file.copyTo(target, overwrite = true)
                segment.file.delete()
            }.isSuccess
        }
        if (!moved) {
            rec.failedPaths += segment.file.absolutePath
            AppLog.i("nagranie: nie udało się przenieść ${segment.file.absolutePath}; plik zachowany do odzyskania")
            return
        }
        recoveryMarker(segment.file).delete()
        rec.files++
        rec.bytes += segment.length
        rec.firstWall = minOf(rec.firstWall, segment.wall)
        rec.lastWall = maxOf(rec.lastWall, end)
    }

    /**
     * Początek nagrania od chwili [fromWall] (kawałek, w którym wypada, w całości) do [untilWall]; nowe kawałki
     * dochodzą, dopóki nagranie trwa. False — nagranie już trwa albo bufor jest pusty.
     */
    fun startRecording(dir: File, fromWall: Long, untilWall: Long, maxBytes: Long, onFinished: (RecordingResult) -> Unit): Boolean =
        synchronized(lock) {
            if (closed || recording != null || segments.isEmpty() || failed != null ||
                maxBytes <= 0 || recordingPendingBytes(fromWall, untilWall) > maxBytes
            ) return false
            if (!dir.isDirectory && !dir.mkdirs()) {
                AppLog.i("nagranie: nie można utworzyć katalogu ${dir.absolutePath}")
                return false
            }
            val marked = mutableListOf<File>()
            try {
                for (segment in segments) {
                    createRecoveryMarker(segment.file)
                    marked += recoveryMarker(segment.file)
                }
            } catch (e: IOException) {
                marked.forEach { it.delete() }
                AppLog.i("nagranie: nie można zabezpieczyć bufora (${e.message})")
                return false
            }
            recording = Recording(dir, fromWall, untilWall, maxBytes, onFinished)
            true
        }

    /** Koniec nagrania teraz: wszystkie kawałki z zakresu trafiają do nagrania, bufor zaczyna od nowa. */
    fun stopRecording(reason: String) = synchronized(lock) {
        if (recording != null) finishLocked(reason)
        lock.notifyAll()
    }

    private fun finishLocked(reason: String) {
        val rec = recording ?: return
        runCatching { output?.close() }
        output = null
        pendingLength = 0
        val now = clock()
        val all = segments.toList()
        for ((index, segment) in all.withIndex()) {
            val end = all.getOrNull(index + 1)?.wall ?: now
            if (end > rec.fromWall && segment.wall < rec.untilWall) moveToRecording(rec, segment, end)
            else deleteSegmentAsync(segment.file)
        }
        segments.clear()
        trimmed = false
        recording = null
        val result = RecordingResult(rec.dir, rec.files, rec.bytes, rec.firstWall, rec.lastWall, reason, rec.failedPaths.toList())
        AppLog.i("nagranie: koniec (${result.reason}) — ${result.files} kawałków, ${result.bytes / 1_000_000} MB")
        runCatching { rec.onFinished(result) }.onFailure { AppLog.i("nagranie: błąd zapisu informacji (${it.message})") }
    }


    val oldestWall: Long get() = synchronized(lock) { segments.firstOrNull()?.wall ?: clock() }

    fun seekMarginMs(): Long = synchronized(lock) {
        if (trimmed || segments.size >= maxOf(2, maxMin + 1) ||
            (segments.size >= 2 && dir.usableSpace < reserveBytes + 60_000_000L)) 10_000L else 1_000L
    }

    /** Długość zapisanego okna (ms). */
    fun spanMs(): Long = if (total == 0L) 0 else clock() - oldestWall

    /** Bajt (początek pakietu TS) odpowiadający chwili [wall]. */
    fun offsetAt(wall: Long): Long = synchronized(lock) {
        if (segments.isEmpty()) return total
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

    /** Początek najstarszej zachowanej minuty; -1 — bufor pusty lub zamknięty. */
    fun oldestOffset(): Long = synchronized(lock) { segments.firstOrNull()?.start ?: -1 }

    /**
     * Kopia bufora od chwili [fromWall] do bieżącego końca do pliku [target] (zwykły plik .ts do odtworzenia w VLC).
     * Zwraca liczbę zapisanych bajtów; [progress] dostaje procent.
     */
    fun saveTo(target: File, fromWall: Long, progress: (Int) -> Unit = {}): Long {
        val end = total
        var position = maxOf(offsetAt(fromWall), oldestOffset())
        if (position < 0 || end <= position) return 0
        val start = position
        var written = 0L
        var last = -1
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { output ->
            val bytes = ByteArray(PACKET * 2048)
            while (position < end) {
                val count = read(position, bytes, 0, minOf(bytes.size.toLong(), end - position).toInt())
                if (count < 0) {
                    // Najstarsza minuta wypadła w trakcie kopiowania — dalej od najstarszej zachowanej.
                    val oldest = oldestOffset()
                    if (oldest < 0 || oldest <= position) break
                    position = oldest
                    continue
                }
                if (count == 0) break
                output.write(bytes, 0, count)
                position += count
                written += count
                val percent = ((position - start) * 100 / (end - start)).toInt()
                if (percent != last) {
                    last = percent
                    progress(percent)
                }
            }
        }
        return written
    }

    /** Koniec bufora: pliki skasowane w tle. */
    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        if (recording != null) finishLocked("zmiana kanału albo wyjście")
        runCatching { output?.close() }
        output = null
        segments.clear()
        total = 0
        pendingLength = 0
        unregisterSession(dir, session)
        cleanupAsync(dir)
        lock.notifyAll()
    }

    companion object {
        const val PACKET = 188
        private const val SYNC = 0x47.toByte()

        /** Zapas wolnego miejsca, którego bufor nie rusza. */
        const val RESERVE = 1_000L * 1_000_000

        /** Ostrożne oszacowanie: kanał HD do ~8 Mb/s = 60 MB na minutę. */
        const val MB_PER_MIN = 60L
        private val sessionLock = Any()
        private val activeSessions = HashMap<String, MutableSet<String>>()

        private fun recoveryMarker(file: File): File = File(file.path + ".keep")

        private fun createRecoveryMarker(file: File) {
            val marker = recoveryMarker(file)
            if (!marker.exists() && !marker.createNewFile()) throw IOException("Nie można zabezpieczyć ${file.name}")
        }

        private fun deleteSegmentAsync(file: File) {
            thread(name = "timeshift-usun", isDaemon = true) {
                if (file.delete() || !file.exists()) recoveryMarker(file).delete()
            }
        }

        private fun directoryKey(dir: File): String = dir.canonicalPath.let {
            if (File.separatorChar == '\\') it.lowercase(java.util.Locale.ROOT) else it
        }

        private fun registerSession(dir: File, session: String) = synchronized(sessionLock) {
            activeSessions.getOrPut(directoryKey(dir)) { mutableSetOf() }.add(session)
        }

        private fun unregisterSession(dir: File, session: String) = synchronized(sessionLock) {
            val key = directoryKey(dir)
            activeSessions[key]?.let {
                it.remove(session)
                if (it.isEmpty()) activeSessions.remove(key)
            }
        }

        val CHOICES = listOf(0, 15, 30, 60, 90, 120, 180, 240, 300, 360)

        /** %LOCALAPPDATA%\E2Stream\timeshift — dane tymczasowe nie trafiają do profilu mobilnego. */
        fun defaultDir(): File {
            val local = System.getenv("LOCALAPPDATA")
            return if (!local.isNullOrBlank()) Paths.get(local, "E2Stream", "timeshift").toFile()
            else DesktopConfig.appDataDir().resolve("timeshift").toFile()
        }

        fun dirFor(config: DesktopConfig): File =
            config.tsDir.takeIf(String::isNotBlank)?.let { File(it, "timeshift") } ?: defaultDir()

        /** Katalog nagrań zapisanych z bufora: ustawienie albo Wideo\E2Stream. */
        fun recordingsDir(config: DesktopConfig): File =
            config.recLocalDir.takeIf(String::isNotBlank)?.let(::File)
                ?: Paths.get(System.getProperty("user.home"), "Videos", "E2Stream").toFile()

        /** Nazwa pliku nagrania: „kanał – audycja – rrrr-mm-dd gg.mm.ts” bez znaków niedozwolonych w Windows. */
        fun recordingName(channel: String, title: String?, at: java.time.LocalDateTime): String {
            val stamp = at.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm"))
            val base = listOfNotNull(channel.trim().ifBlank { null }, title?.trim()?.ifBlank { null }, stamp).joinToString(" – ")
            return base.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(150) + ".ts"
        }

        /** Ile minut timeshiftu zmieści się w katalogu [dir] (z zapasem). */
        fun fitMinutes(dir: File): Int {
            val free = runCatching { dir.mkdirs(); dir.usableSpace }.getOrDefault(0L)
            val usable = free - maxOf(RESERVE, free / 10)
            return if (usable <= 0) 0 else (usable / (MB_PER_MIN * 1_000_000)).toInt()
        }

        /** Kasuje w tle pliki nieaktywnych sesji w tym katalogu. */
        fun cleanupAsync(dir: File, keep: String? = null) {
            thread(name = "timeshift-sprzatanie", isDaemon = true) {
                runCatching {
                    var count = 0
                    synchronized(sessionLock) {
                        val active = activeSessions[directoryKey(dir)].orEmpty()
                        dir.listFiles { file ->
                            file.name.startsWith("ts-") && file.name.endsWith(".ts") &&
                                !recoveryMarker(file).exists() &&
                                (keep == null || !file.name.startsWith(keep)) &&
                                active.none { file.name.startsWith(it) }
                        }?.forEach { if (it.delete()) count++ }
                    }
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
    /** PAT/PMT i początek od PCR (jak na Androidzie); null — dane bez zmian. */
    private val fixer: TsStreamFixer? = null,
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
                    fixer?.newConnection()
                    val bytes = ByteArray(TimeshiftBuffer.PACKET * 700)
                    while (running) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        if (fixer != null) fixer.feed(bytes, 0, count, buffer::write) else buffer.write(bytes, 0, count)
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
internal class TimeshiftServer(
    private val buffer: TimeshiftBuffer,
    /** PAT + PMT programu wysyłane przed danymi bufora — VLC od razu zna ścieżki (po przewinięciu też). */
    private val header: () -> ByteArray? = { null },
) : Closeable {
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
                header()?.let {
                    output.write(it)
                    output.flush()
                }
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
    val sourceUrl: String,
    val authorization: String?,
    tap: ((ByteArray, Int, Int) -> Unit)? = null,
    /** Poprawki PAT/PMT/PCR strumienia; null — bez zmian. */
    val fixer: TsStreamFixer? = null,
) : Closeable {
    private val server = try {
        TimeshiftServer(buffer) { fixer?.psiHeader() }
    } catch (e: Exception) {
        buffer.close()
        throw e
    }
    private val recorder = try {
        TimeshiftRecorder(sourceUrl, authorization, buffer, tap, fixer)
    } catch (e: Exception) {
        server.close()
        buffer.close()
        throw e
    }

    /** Audycje widziane w czasie zapisu bufora (spis w nagraniach lokalnych). */
    val programmes = ProgrammeLog()
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

internal fun timeshiftTarget(target: Long, now: Long, oldestWall: Long, marginMs: Long = 10_000): TimeshiftTarget {
    val earliest = minOf(oldestWall + marginMs, now)
    if (target >= now - LIVE_MARGIN_MS) return TimeshiftTarget.Live
    if (target < earliest) {
        return if (earliest >= now - LIVE_MARGIN_MS) TimeshiftTarget.Live
        else TimeshiftTarget.At(earliest, clampedToOldest = true)
    }
    return TimeshiftTarget.At(target, clampedToOldest = false)
}

/** Odległość od końca bufora, poniżej której odtwarzanie traktujemy jako „na żywo”. */
internal const val LIVE_MARGIN_MS = 6_000L

internal fun timeshiftSeekStep(config: DesktopConfig, horizontal: Boolean, held: Boolean): Long =
    if (horizontal) (if (held) config.tsStepSecLong else config.tsStepSec) * 1000L
    else if (held) config.tsStepUdLongMin * 60_000L else config.tsStepUdSec * 1000L

internal fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
