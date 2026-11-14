package pl.jardol.e2stream.desktop

import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Aktualizacja aplikacji Windows z wydań GitHub (tagi e2stream-windows-X.Y.Z i e2stream-windows-X.Y.Z-test). */
internal object Updater {
    private const val API = "https://api.github.com/repos/JarDol/enigma2-epgscan/releases?per_page=60"
    private const val PREFIX = "e2stream-windows-"

    data class Release(val version: String, val test: Boolean, val url: String, val name: String, val size: Long, val page: String)

    /** Wydania z instalatorem .exe, od najnowszego; wersje testowe tylko dla kanału „test”. */
    internal fun parse(json: JSONArray, channel: String): List<Release> =
        (0 until json.length()).mapNotNull { index ->
            val release = json.optJSONObject(index) ?: return@mapNotNull null
            val tag = release.optString("tag_name")
            if (release.optBoolean("draft") || !tag.startsWith(PREFIX)) return@mapNotNull null
            val raw = tag.removePrefix(PREFIX)
            val test = raw.endsWith("-test") || release.optBoolean("prerelease")
            if (test && channel != "test") return@mapNotNull null
            val version = raw.removeSuffix("-test")
            if (!Regex("\\d+\\.\\d+\\.\\d+").matches(version)) return@mapNotNull null
            val assets = release.optJSONArray("assets") ?: JSONArray()
            val asset = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".exe", ignoreCase = true) } ?: return@mapNotNull null
            Release(
                version = version,
                test = test,
                url = asset.optString("browser_download_url"),
                name = asset.optString("name"),
                size = asset.optLong("size"),
                page = release.optString("html_url"),
            )
        }.filter { it.url.isNotBlank() }
            .sortedWith { a, b -> compare(b.version, a.version).takeIf { it != 0 } ?: a.test.compareTo(b.test) }

    /** Porównanie X.Y.Z; wersja deweloperska (bez numeru) jest najstarsza. */
    fun compare(a: String, b: String): Int {
        fun parts(value: String) = value.split('.').map { it.toIntOrNull() ?: -1 }
        val left = parts(a)
        val right = parts(b)
        for (index in 0 until maxOf(left.size, right.size)) {
            val difference = (left.getOrNull(index) ?: 0).compareTo(right.getOrNull(index) ?: 0)
            if (difference != 0) return difference
        }
        return 0
    }

    /** Nowsza wersja niż [current] albo null. */
    fun newer(current: String, channel: String): Release? {
        val latest = parse(fetch(), channel).firstOrNull() ?: return null
        return latest.takeIf { compare(it.version, current) > 0 || !Regex("\\d+\\.\\d+\\.\\d+").matches(current) }
    }

    private fun fetch(): JSONArray {
        val connection = URI(API).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "E2Stream-Windows")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("GitHub: HTTP ${connection.responseCode}")
            return JSONArray(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** Pobiera instalator do katalogu tymczasowego; [progress] dostaje procent. */
    fun download(release: Release, progress: (Int) -> Unit): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "E2Stream-update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, release.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        var url = release.url
        repeat(5) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "E2Stream-Windows")
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    url = connection.getHeaderField("Location") ?: throw IOException("GitHub: przekierowanie bez adresu")
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) throw IOException("Pobieranie: HTTP $code")
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
                var done = 0L
                var last = -1
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            done += count
                            val percent = if (total > 0) (done * 100 / total).toInt() else 0
                            if (percent != last) {
                                last = percent
                                progress(percent)
                            }
                        }
                    }
                }
                if (release.size > 0 && target.length() != release.size) throw IOException("Pobrany plik ma inny rozmiar niż wydanie")
                return target
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Za dużo przekierowań")
    }

    /**
     * Uruchamia instalator przez powłokę Windows (z pytaniem UAC, gdy instalacja jest dla wszystkich użytkowników);
     * aplikację trzeba potem zamknąć, żeby instalator mógł podmienić pliki.
     */
    fun launchInstaller(file: File) {
        java.awt.Desktop.getDesktop().open(file)
    }
}
