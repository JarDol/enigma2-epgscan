package pl.jardol.e2stream.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.text.Normalizer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * Ikony kanałów (picony) z dekodera, zapisywane w %APPDATA%\E2Stream\picons — jak w Androidzie:
 * paczka wtyczki EPG Scan, katalog picon z ustawień Enigmy2 (FTP), OpenWebif /picon/, typowe katalogi.
 */
internal object Picons {
    private val ftpDirs = listOf(
        "/usr/share/enigma2/picon", "/media/hdd/picon", "/media/usb/picon",
        "/media/mmc/picon", "/picon", "/data/picon",
    )
    private val cache = ConcurrentHashMap<String, Optional>()

    private class Optional(val image: ImageBitmap?)

    fun dir(): File = DesktopConfig.appDataDir().resolve("picons").toFile().also { it.mkdirs() }

    fun localName(ref: String): String = serviceKey(ref).replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".png"

    /** Nazwy plików picon do wypróbowania: referencja, referencja z typem 1, małe litery, nazwa (SNP). */
    fun candidates(channel: Channel): List<String> {
        val fields = channel.ref.trimEnd(':').split(':')
        if (fields.size < 10) return listOf(snp(channel.name) + ".png")
        val base = fields.take(10).map { it.uppercase() }
        return linkedSetOf(
            base.joinToString("_") + ".png",
            base.toMutableList().also { it[2] = "1" }.joinToString("_") + ".png",
            base.joinToString("_").lowercase() + ".png",
            snp(channel.name) + ".png",
        ).toList()
    }

    fun snp(name: String): String {
        val normalized = Normalizer.normalize(name.lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
        return normalized.replace("&", "and").replace("+", "plus").replace("*", "star").replace(Regex("[^a-z0-9]"), "")
    }

    private fun localFile(channel: Channel): File? {
        val dir = dir()
        File(dir, localName(channel.ref)).takeIf(File::isFile)?.let { return it }
        return File(dir, snp(channel.name) + ".png").takeIf { channel.name.isNotBlank() && it.isFile }
    }

    fun image(channel: Channel): ImageBitmap? {
        val key = localName(channel.ref)
        cache[key]?.let { return it.image }
        val image = localFile(channel)?.let { file ->
            runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
        }
        cache[key] = Optional(image)
        return image
    }

    fun count(): Int = dir().listFiles { file -> file.name.endsWith(".png", true) }?.size ?: 0

    fun clear() {
        dir().listFiles()?.forEach(File::delete)
        cache.clear()
    }

    /** Pobiera brakujące ikony dla [channels]; zwraca liczbę kanałów, które mają ikonę. */
    fun fetch(config: DesktopConfig, channels: List<Channel>, progress: (String) -> Unit): Int {
        val dir = dir()
        fun missing() = channels.filter { !File(dir, localName(it.ref)).exists() }
        var left = missing()
        if (left.isNotEmpty()) {
            pluginPack(config, progress)
            left = missing()
        }
        if (left.isNotEmpty()) {
            val detected = detectDir(config)
            val preferred = listOfNotNull(config.piconDir.trimEnd('/').takeIf(String::isNotBlank), detected).distinct()
            if (preferred.isNotEmpty()) left = ftpStage(config, left, preferred, progress)
            left = httpStage(config, left, progress)
            if (left.isNotEmpty()) left = ftpStage(config, left, ftpDirs, progress)
        }
        cache.clear()
        val none = missing()
        if (none.isNotEmpty()) {
            AppLog.i("ikony: brak dla ${none.size} kanałów: " + none.take(40).joinToString("; ") { it.name })
        }
        return channels.size - none.size
    }

    private fun pluginPack(config: DesktopConfig, progress: (String) -> Unit) {
        try {
            progress("Ikony: paczka wtyczki EPG Scan…")
            val connection = URI("http://${hostForUrl(config.host)}:${config.pluginPort}/api/pack/picons.zip")
                .toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 120_000
            if (config.pluginToken.isNotBlank()) connection.setRequestProperty("X-E2S-Token", config.pluginToken)
            try {
                if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
                var count = 0
                ZipInputStream(connection.inputStream).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = File(entry.name).name
                        if (entry.isDirectory || !name.endsWith(".png") || name.length > 120) continue
                        File(dir(), name).outputStream().use { zip.copyTo(it) }
                        count++
                    }
                }
                AppLog.i("ikony: z paczki EPG Scan: $count")
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            AppLog.i("ikony: paczka EPG Scan niedostępna: ${e.message}")
        }
    }

    /** Katalog picon z /etc/enigma2/settings (np. config.usage.picon_dir=/media/hdd/picon). */
    private fun detectDir(config: DesktopConfig): String? = try {
        FtpSession(config.host, config.ftpUser, config.ftpPass).use { ftp ->
            ftp.retrieve("/etc/enigma2/settings")?.toString(Charsets.UTF_8)?.lines()
                ?.map(String::trim)
                ?.filter { it.contains("picon", true) && it.contains('=') }
                ?.map { it.substringAfter('=').trim().trimEnd('/') }
                ?.firstOrNull { it.startsWith("/") && it.length > 1 }
        }
    } catch (e: Exception) {
        AppLog.i("ikony: nie udało się odczytać ustawień Enigmy2 przez FTP: ${e.message}")
        null
    }

    private fun ftpStage(
        config: DesktopConfig,
        channels: List<Channel>,
        dirs: List<String>,
        progress: (String) -> Unit,
    ): List<Channel> {
        if (channels.isEmpty()) return channels
        val target = dir()
        val left = ArrayList<Channel>()
        try {
            FtpSession(config.host, config.ftpUser, config.ftpPass).use { ftp ->
                val found = dirs.firstNotNullOfOrNull { piconDir ->
                    ftp.list(piconDir)?.toSet()?.takeIf { names -> names.any { it.endsWith(".png", true) } }
                        ?.let { piconDir to it }
                } ?: return channels
                val (piconDir, names) = found
                AppLog.i("ikony: katalog picon na dekoderze: $piconDir (${names.size} plików)")
                val byService = HashMap<String, String>()
                val byName = HashMap<String, String>()
                val upper = HashMap<String, String>()
                for (name in names) {
                    if (!name.endsWith(".png", true)) continue
                    val base = name.dropLast(4)
                    upper.putIfAbsent(name.uppercase(), name)
                    val fields = base.split('_')
                    if (fields.size >= 7) byService.putIfAbsent(fields.subList(3, 7).joinToString("_").uppercase(), name)
                    byName.putIfAbsent(base.lowercase(), name)
                }
                for ((index, channel) in channels.withIndex()) {
                    val refFields = channel.ref.trimEnd(':').split(':')
                    val service = if (refFields.size >= 7) refFields.subList(3, 7).joinToString("_").uppercase() else ""
                    val simple = snp(channel.name)
                    val name = candidates(channel).firstNotNullOfOrNull { upper[it.uppercase()] }
                        ?: byService[service]
                        ?: byName[simple] ?: byName[simple.removeSuffix("hd")] ?: byName[simple + "hd"]
                    val data = name?.let { ftp.retrieve("$piconDir/$it") }
                    if (data != null && data.isNotEmpty()) File(target, localName(channel.ref)).writeBytes(data)
                    else left += channel
                    if (index % 20 == 0) progress("Ikony (FTP): $index/${channels.size}")
                }
            }
        } catch (e: Exception) {
            AppLog.i("ikony: FTP nieudane: ${e.message}")
            return channels.filter { !File(target, localName(it.ref)).exists() }
        }
        return left
    }

    private fun httpStage(config: DesktopConfig, channels: List<Channel>, progress: (String) -> Unit): List<Channel> {
        if (channels.isEmpty()) return channels
        val target = dir()
        val auth = config.username.takeIf(String::isNotBlank)?.let {
            "Basic " + Base64.getEncoder().encodeToString("$it:${config.password}".toByteArray(Charsets.UTF_8))
        }
        val left = ArrayList<Channel>()
        var failures = 0
        for ((index, channel) in channels.withIndex()) {
            // OpenWebif bez ikon — po 5 nieudanych z rzędu nie ma sensu pytać dalej.
            if (failures >= 5 && left.size == index) {
                left += channels.subList(index, channels.size)
                break
            }
            val data = candidates(channel).firstNotNullOfOrNull { name ->
                httpGet("http://${hostForUrl(config.host)}:${config.httpPort}/picon/$name", auth)
            }
            if (data != null) {
                File(target, localName(channel.ref)).writeBytes(data)
                failures = -1000
            } else {
                left += channel
                failures++
            }
            if (index % 20 == 0) progress("Ikony: $index/${channels.size}")
        }
        return left
    }

    private fun httpGet(url: String, auth: String?): ByteArray? = try {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 5000
        auth?.let { connection.setRequestProperty("Authorization", it) }
        try {
            if (connection.responseCode == 200 && connection.contentType.orEmpty().contains("image")) {
                connection.inputStream.use { it.readBytes() }
            } else null
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
