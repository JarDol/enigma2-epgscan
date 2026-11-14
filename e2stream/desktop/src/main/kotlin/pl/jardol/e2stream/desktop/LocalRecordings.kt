package pl.jardol.e2stream.desktop

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Wirtualny bukiet „Nagrania lokalne” — nagrania z bufora timeshiftu na tym komputerze. */
const val LOCAL_REC_BOUQUET = "e2stream:nagrania-lokalne"
val LOCAL_RECORDINGS_BOUQUET = Bouquet("Nagrania lokalne", LOCAL_REC_BOUQUET)

/** Ścieżka nagrania lokalnego w [Recording.path]: „local:<katalog>”. */
internal const val LOCAL_PREFIX = "local:"

internal fun isRecordingsBouquet(ref: String?): Boolean = ref == REC_BOUQUET || ref == LOCAL_REC_BOUQUET

/**
 * Nagrania z bufora timeshiftu: każde to katalog w `nagrania` obok katalogu bufora (ten sam nośnik — kawałki
 * są przenoszone, nie kopiowane) z kawałkami 00000.ts, 00001.ts… i opisem info.json.
 */
internal object LocalRecordings {
    const val INFO = "info.json"
    /** Nagrania mogą zająć najwyżej tyle pojemności nośnika. */
    const val MAX_SHARE = 0.70

    fun root(config: DesktopConfig): File = TimeshiftBuffer.dirFor(config).absoluteFile.parentFile.resolve("nagrania")

    fun dirOf(config: DesktopConfig, recording: Recording): File? =
        recording.path.takeIf { it.startsWith(LOCAL_PREFIX) }?.let { File(root(config), it.removePrefix(LOCAL_PREFIX)) }

    /** Nowy katalog nagrania: „rrrr-mm-dd gg.mm.ss kanał”. */
    fun newDir(config: DesktopConfig, channel: String, at: java.time.LocalDateTime): File {
        val stamp = at.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss"))
        val name = "$stamp ${channel.trim()}".replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120).trim()
        return File(root(config), name)
    }

    fun parts(dir: File): List<File> =
        dir.listFiles { file -> file.isFile && file.name.matches(Regex("\\d{5}\\.ts")) }?.sortedBy { it.name }.orEmpty()

    fun writeInfo(dir: File, info: JSONObject) {
        dir.mkdirs()
        val temp = File(dir, "$INFO.tmp")
        temp.writeText(info.toString(1), Charsets.UTF_8)
        val target = File(dir, INFO)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    fun readInfo(dir: File): JSONObject? = runCatching { JSONObject(File(dir, INFO).readText(Charsets.UTF_8)) }.getOrNull()

    /** Nagrania od najnowszego (jako [Recording], jak nagrania z dekodera). */
    fun list(config: DesktopConfig): List<Recording> {
        val dirs = root(config).listFiles { file -> file.isDirectory }.orEmpty()
        return dirs.mapNotNull { dir ->
            val info = readInfo(dir) ?: return@mapNotNull null
            val size = parts(dir).sumOf { it.length() }
            val recording = info.optBoolean("recording")
            Recording(
                path = LOCAL_PREFIX + dir.name,
                title = info.optString("title").ifBlank { info.optString("channel") } + if (recording) "  ● nagrywa się" else "",
                description = info.optString("description"),
                service = info.optString("channel"),
                serviceRef = info.optString("ref"),
                begin = info.optLong("begin") / 1000,
                lengthSec = info.optLong("durationMs") / 1000,
                size = size,
            )
        }.sortedByDescending { it.begin }
    }

    /** Zajętość: (rozmiar nagrań, pojemność nośnika). */
    fun usage(config: DesktopConfig): Pair<Long, Long> {
        val root = root(config).apply { mkdirs() }
        val used = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return used to root.totalSpace
    }

    /** Ile bajtów może jeszcze zająć nowe nagranie (limit [MAX_SHARE] pojemności, z zapasem wolnego miejsca). */
    fun allowedBytes(config: DesktopConfig): Long {
        val (used, total) = usage(config)
        val byShare = (total * MAX_SHARE).toLong() - used
        val byFree = root(config).usableSpace - TimeshiftBuffer.RESERVE
        return minOf(byShare, byFree)
    }

    fun delete(config: DesktopConfig, recording: Recording): Boolean {
        val dir = dirOf(config, recording) ?: return false
        return dir.deleteRecursively()
    }

    /** Sklejenie kawałków w jeden plik .ts obok katalogów nagrań; [progress] dostaje procent. */
    fun export(config: DesktopConfig, recording: Recording, progress: (Int) -> Unit): File {
        val dir = dirOf(config, recording) ?: throw IOException("To nie jest nagranie lokalne")
        val parts = parts(dir)
        if (parts.isEmpty()) throw IOException("Nagranie nie ma danych")
        val total = parts.sumOf { it.length() }.coerceAtLeast(1)
        val target = File(root(config), dir.name + ".ts")
        val temp = File(root(config), dir.name + ".ts.tmp")
        var done = 0L
        var last = -1
        BufferedOutputStream(temp.outputStream(), 1 shl 20).use { output ->
            val buffer = ByteArray(1 shl 20)
            for (part in parts) {
                part.inputStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        val percent = (done * 100 / total).toInt()
                        if (percent != last) {
                            last = percent
                            progress(percent)
                        }
                    }
                }
            }
        }
        target.delete()
        if (!temp.renameTo(target)) throw IOException("Nie udało się zapisać ${target.name}")
        return target
    }
}

/**
 * Lokalny serwer HTTP (127.0.0.1) z nagraniami lokalnymi: kawałki nagrania jako jeden plik .ts, z obsługą
 * zakresów (Range) — VLC może przewijać i wznawiać od zapamiętanego miejsca.
 */
internal class LocalRecordingServer(private val root: () -> File) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false

    init {
        thread(name = "nagrania-serwer", isDaemon = true) {
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                clients += socket
                thread(name = "nagrania-klient", isDaemon = true) { serve(socket) }
            }
        }
    }

    fun url(recording: Recording): String =
        "http://127.0.0.1:${server.localPort}/rec/" +
            URLEncoder.encode(recording.path.removePrefix(LOCAL_PREFIX), Charsets.UTF_8).replace("+", "%20") + ".ts"

    private fun serve(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = 15_000
                val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val request = reader.readLine().orEmpty().split(' ')
                var range: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                }
                val output = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)
                val method = request.firstOrNull()
                val path = request.getOrNull(1).orEmpty()
                val name = path.removePrefix("/rec/").removeSuffix(".ts").let { URLDecoder.decode(it, Charsets.UTF_8) }
                val dir = File(root(), name)
                val parts = if (path.startsWith("/rec/") && !name.contains("..") && dir.isDirectory) LocalRecordings.parts(dir) else emptyList()
                if ((method != "GET" && method != "HEAD") || parts.isEmpty()) {
                    output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    return
                }
                val sizes = parts.map { it.length() }
                val total = sizes.sum()
                val start = parseRangeStart(range)?.coerceIn(0, total) ?: 0L
                val header = if (range != null) {
                    "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-${total - 1}/$total\r\n"
                } else "HTTP/1.1 200 OK\r\n"
                output.write(
                    (header + "Content-Type: video/mp2t\r\nAccept-Ranges: bytes\r\nContent-Length: ${total - start}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(),
                )
                output.flush()
                if (method == "HEAD") return
                var skip = start
                val buffer = ByteArray(188 * 1024)
                for ((index, part) in parts.withIndex()) {
                    if (closed) break
                    if (skip >= sizes[index]) {
                        skip -= sizes[index]
                        continue
                    }
                    RandomAccessFile(part, "r").use { file ->
                        file.seek(skip)
                        skip = 0
                        while (!closed) {
                            val count = file.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                output.flush()
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

    companion object {
        /** „bytes=123-” albo „bytes=123-456” → 123. */
        fun parseRangeStart(value: String?): Long? =
            value?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() }
    }
}
