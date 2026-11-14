package pl.jardol.e2stream.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Miejsce zatrzymania nagrań (ms) w %APPDATA%\E2Stream\recpos.properties. */
internal class RecordingPositions(private val path: Path = DesktopConfig.appDataDir().resolve("recpos.properties")) {
    private val values = Properties().apply {
        runCatching { if (Files.exists(path)) Files.newInputStream(path).use(::load) }
    }

    @Synchronized
    fun get(recordingPath: String): Long = values.getProperty(recordingPath)?.toLongOrNull() ?: 0

    /** Zapis położenia; obejrzane do końca (ostatnia minuta) albo tylko początek — wpis usuwany. */
    @Synchronized
    fun save(recordingPath: String, positionMs: Long, lengthMs: Long) {
        if (positionMs <= 30_000 || (lengthMs > 0 && positionMs > lengthMs - 60_000)) values.remove(recordingPath)
        else values.setProperty(recordingPath, positionMs.toString())
        store()
    }

    @Synchronized
    fun remove(recordingPath: String) {
        if (values.remove(recordingPath) != null) store()
    }

    private fun store() {
        runCatching {
            Files.createDirectories(path.parent)
            Files.newOutputStream(path).use { values.store(it, "E2Stream recording positions") }
        }
    }
}
