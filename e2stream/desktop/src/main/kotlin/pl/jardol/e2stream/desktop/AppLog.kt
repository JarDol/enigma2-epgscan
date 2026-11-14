package pl.jardol.e2stream.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Log aplikacji: bufor w pamięci (do wysłania na dekoder) i plik e2stream.log w wybranym katalogu.
 * Plik ma limit rozmiaru — po jego przekroczeniu poprzednia zawartość trafia do e2stream.old.log.
 */
object AppLog {
    const val FILE_NAME = "e2stream.log"
    private const val MAX_MEMORY_CHARS = 512 * 1024
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val buffer = StringBuilder()
    private var directory: Path = defaultDir()
    private var dirty = false

    fun defaultDir(): Path = DesktopConfig.appDataDir().resolve("logs")

    fun dirFor(config: DesktopConfig): Path =
        config.logDir.takeIf { it.isNotBlank() }?.let { runCatching { Paths.get(it) }.getOrNull() } ?: defaultDir()

    @Synchronized
    fun setDirectory(dir: Path) {
        directory = dir
    }

    @Synchronized
    fun directory(): Path = directory

    fun file(): Path = directory().resolve(FILE_NAME)

    @Synchronized
    fun i(message: String) {
        val line = "${LocalDateTime.now().format(timeFormat)} $message\n"
        buffer.append(line)
        if (buffer.length > MAX_MEMORY_CHARS) buffer.delete(0, buffer.length - MAX_MEMORY_CHARS)
        dirty = true
        runCatching {
            Files.createDirectories(directory)
            val target = directory.resolve(FILE_NAME)
            if (Files.exists(target) && Files.size(target) > MAX_FILE_BYTES) {
                Files.move(
                    target,
                    directory.resolve("e2stream.old.log"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
            Files.writeString(target, line, Charsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    @Synchronized
    fun snapshot(): ByteArray = buffer.toString().toByteArray(Charsets.UTF_8)

    /** true, gdy od ostatniego wysłania pojawiły się nowe wpisy (i kasuje znacznik). */
    @Synchronized
    fun takeDirty(): Boolean = dirty.also { dirty = false }

    @Synchronized
    fun markDirty() {
        dirty = true
    }
}
