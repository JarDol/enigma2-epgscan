package pl.jardol.e2stream

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Aktualizacja z GitHub Releases (JarDol/enigma2-epgscan, wydania e2stream-X.Y.Z). Metody sieciowe poza wątkiem UI. */
object Updater {

    private const val API = "https://api.github.com/repos/JarDol/enigma2-epgscan/releases?per_page=50"

    data class Info(val version: String, val url: String, val name: String, val prerelease: Boolean)

    /**
     * Najwyższa wersja, której Release ma plik e2stream-<wersja>.apk.
     * Puste / zapasowe Release'y (bez pasującego pliku) są pomijane.
     */
    fun latest(channel: String = "stable"): Info? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "E2Stream")
        try {
            if (c.responseCode != 200) throw IOException("GitHub HTTP ${c.responseCode}")
            val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            var best: Info? = null
            for (r in 0 until arr.length()) {
                val js = arr.optJSONObject(r) ?: continue
                val prerelease = js.optBoolean("prerelease")
                if (js.optBoolean("draft") || (prerelease && channel != "beta")) continue
                val rawTag = js.optString("tag_name")
                if (!rawTag.startsWith("e2stream-")) continue   // w repo są też wydania wtyczki i serwera
                val tag = rawTag.removePrefix("e2stream-").removePrefix("v")
                val assets = js.optJSONArray("assets") ?: continue
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name == "e2stream-$tag.apk") {
                        val cand = Info(tag, a.optString("browser_download_url"), name, prerelease)
                        if (best == null || compareVersions(tag, best.version) > 0) best = cand
                    }
                }
            }
            return best
        } finally {
            c.disconnect()
        }
    }

    /** Porównanie wersji semver, także prerelease w formacie X.Y.Z-beta.N. */
    fun newer(a: String, b: String): Boolean = compareVersions(a, b) > 0

    private fun compareVersions(a: String, b: String): Int {
        fun parts(value: String): Pair<List<Int>, List<String>?> {
            val split = value.removePrefix("v").split('-', limit = 2)
            val numbers = split[0].split('.').map { it.toIntOrNull() ?: 0 }
            val pre = split.getOrNull(1)?.split('.')
            return numbers to pre
        }
        val (aNumbers, aPre) = parts(a)
        val (bNumbers, bPre) = parts(b)
        for (i in 0 until maxOf(aNumbers.size, bNumbers.size)) {
            val diff = aNumbers.getOrElse(i) { 0 }.compareTo(bNumbers.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        if (aPre == null || bPre == null) return when {
            aPre == null && bPre == null -> 0
            aPre == null -> 1
            else -> -1
        }
        for (i in 0 until maxOf(aPre.size, bPre.size)) {
            if (i >= aPre.size) return -1
            if (i >= bPre.size) return 1
            val left = aPre[i]
            val right = bPre[i]
            val leftNum = left.toIntOrNull()
            val rightNum = right.toIntOrNull()
            val diff = when {
                leftNum != null && rightNum != null -> leftNum.compareTo(rightNum)
                leftNum != null -> -1
                rightNum != null -> 1
                else -> left.compareTo(right)
            }
            if (diff != 0) return diff
        }
        return 0
    }

    fun download(ctx: Context, info: Info, progress: (Int) -> Unit): File {
        val dir = ctx.externalCacheDir ?: ctx.cacheDir
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Nie można utworzyć katalogu pobierania")
        val out = File(dir, info.name)
        val temp = File(dir, "${info.name}.part")
        temp.delete()
        var url = URL(info.url)
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "E2Stream")
            val code = c.responseCode
            if (code in 300..399 && hops < 5) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Brak przekierowania")
                c.disconnect()
                url = URL(url, loc)
                hops++
                continue
            }
            if (code != 200) {
                c.disconnect()
                throw IOException("HTTP $code")
            }
            break
        }
        try {
            val total = c.getHeaderField("Content-Length")?.toLongOrNull() ?: c.contentLength.toLong()
            var sum = 0L
            c.inputStream.use { input ->
                temp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        sum += n
                        if (total > 0) {
                            val pct = (sum * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; progress(pct) }
                        }
                    }
                }
            }
            if (sum == 0L) throw IOException("Pobrany plik jest pusty")
            if (total > 0 && sum != total) throw IOException("Niekompletne pobranie APK: $sum z $total bajtów")
            val archive = ctx.packageManager.getPackageArchiveInfo(temp.absolutePath, 0)
                ?: throw IOException("Pobrany plik nie jest poprawnym APK")
            if (archive.packageName != ctx.packageName) throw IOException("Nieprawidłowy pakiet APK: ${archive.packageName}")
            if (archive.versionName != info.version) throw IOException("Nieprawidłowa wersja APK: ${archive.versionName}, oczekiwano ${info.version}")
            if (out.exists() && !out.delete()) throw IOException("Nie można zastąpić poprzedniego pliku APK")
            if (!temp.renameTo(out)) throw IOException("Nie można przygotować pliku APK do instalacji")
            AppLog.i("aktualizacja: APK zweryfikowany package=${archive.packageName} version=${archive.versionName} bytes=$sum")
        } finally {
            c.disconnect()
        }
        return out
    }

    /** Uruchamia instalator. Zwraca false, gdy trzeba najpierw zezwolić na instalację z nieznanych źródeł. */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { }
            return false
        }
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        } else {
            Uri.fromFile(apk)
        }
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }
}
