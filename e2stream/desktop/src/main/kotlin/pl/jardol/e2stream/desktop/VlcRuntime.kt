package pl.jardol.e2stream.desktop

import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Wybór bibliotek VLC: wewnętrzny (dołączony do instalatora, katalog `vlc` w zasobach aplikacji)
 * albo zewnętrzny (zainstalowany w systemie VLC 3.x 64-bit). Biblioteki ładują się raz na uruchomienie,
 * więc zmiana źródła działa po ponownym uruchomieniu aplikacji.
 */
object VlcRuntime {
    enum class Source(val label: String) { INTERNAL("wewnętrzny"), EXTERNAL("zewnętrzny") }

    data class Loaded(val source: Source, val dir: String?, val fallback: Boolean)

    /** Ustawienie użytkownika — czytane przy pierwszym utworzeniu odtwarzacza. */
    @Volatile var preferInternal: Boolean = true

    @Volatile var loaded: Loaded? = null
        private set

    /** Wersja libVLC z działającego odtwarzacza, np. „3.0.24 Vetinari”. */
    @Volatile var version: String? = null

    /** Katalog VLC dołączonego do instalatora; null w wersji deweloperskiej albo bez paczki VLC. */
    fun bundledDir(): File? {
        val resources = System.getProperty("compose.application.resources.dir") ?: return null
        val dir = File(resources, "vlc")
        return dir.takeIf { File(it, "libvlc.dll").isFile && File(it, "libvlccore.dll").isFile }
    }

    /** Wersja VLC dołączonego do instalatora (plik VERSION z budowania). */
    fun bundledVersion(): String? =
        bundledDir()?.let { File(it, "VERSION") }?.takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }

    @Synchronized
    fun ensureLoaded(): Loaded {
        loaded?.let { return it }
        val bundled = bundledDir()
        if (preferInternal && bundled != null) {
            val ok = runCatching {
                NativeLibrary.addSearchPath("libvlccore", bundled.absolutePath)
                NativeLibrary.addSearchPath("libvlc", bundled.absolutePath)
                // libvlccore najpierw — libvlc.dll od niej zależy, a katalog aplikacji nie jest w ścieżce DLL.
                NativeLibrary.getInstance("libvlccore")
                NativeLibrary.getInstance("libvlc")
            }
            ok.onFailure { AppLog.i("VLC wewnętrzny: nie udało się załadować z ${bundled.absolutePath}: $it") }
            if (ok.isSuccess) return Loaded(Source.INTERNAL, bundled.absolutePath, fallback = false).also(::remember)
        }
        val discovery = NativeDiscovery()
        if (!discovery.discover()) {
            throw IllegalStateException(
                if (bundled == null) "Nie znaleziono 64-bitowego VLC. Zainstaluj VLC 3.x i uruchom aplikację ponownie."
                else "Nie udało się załadować VLC wewnętrznego ani zewnętrznego. Zainstaluj ponownie E2Stream.",
            )
        }
        val fallback = preferInternal
        return Loaded(Source.EXTERNAL, discovery.discoveredPath(), fallback).also(::remember)
    }

    private fun remember(value: Loaded) {
        loaded = value
        AppLog.i(
            "VLC: ${value.source.label}" + (value.dir?.let { " ($it)" } ?: "") +
                (if (value.fallback) " — wewnętrzny niedostępny" else ""),
        )
    }

    /** Najnowsza wersja VLC dla Windows 64-bit według serwera aktualizacji VideoLAN. */
    fun latestVersion(): String? {
        val connection = URI("https://update.videolan.org/vlc/status-win-x64").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("User-Agent", "E2Stream")
        return try {
            if (connection.responseCode != 200) null
            else connection.inputStream.bufferedReader().readLine()?.trim()?.takeIf { it.matches(Regex("\\d+(\\.\\d+)+")) }
        } finally {
            connection.disconnect()
        }
    }

    /** Numer wersji z napisu libVLC („3.0.24 Vetinari” → „3.0.24”). */
    fun number(version: String?): String? = version?.let { Regex("\\d+(\\.\\d+)+").find(it)?.value }

    /** true, gdy [latest] jest nowsza niż [current] (porównanie liczbowe kolejnych członów). */
    fun isNewer(latest: String?, current: String?): Boolean {
        val a = number(latest)?.split('.')?.map { it.toInt() } ?: return false
        val b = number(current)?.split('.')?.map { it.toInt() } ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/** Ponowne uruchomienie aplikacji: nowy proces startuje po kilku sekundach, gdy ten zwolni blokadę jednej instancji. */
internal fun relaunchApplication(): Boolean {
    val command = ProcessHandle.current().info().command().orElse(null) ?: return false
    if (!command.endsWith(".exe", ignoreCase = true) || File(command).name.equals("java.exe", ignoreCase = true)) return false
    val path = command.replace("'", "''")
    return runCatching {
        ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
            "-Command", "Start-Sleep -Seconds 3; Start-Process -FilePath '$path'",
        ).start()
    }.isSuccess
}
