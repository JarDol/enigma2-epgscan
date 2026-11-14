package pl.jardol.e2stream.desktop

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLEncoder

/** Minimalny klient FTP (tryb pasywny, binarny) — wysyłanie logów i pobieranie picon z dekodera. */
internal class FtpSession(private val host: String, user: String, pass: String, port: Int = 21) : Closeable {
    private val socket = Socket()
    private val reader: BufferedReader
    private val writer: BufferedWriter
    private val startDir: String?

    init {
        socket.connect(InetSocketAddress(host, port), 5000)
        socket.soTimeout = 15_000
        reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        try {
            expect(read(), "220")
            var response = command("USER ${user.ifBlank { "root" }}")
            if (response.startsWith("331")) response = command("PASS $pass")
            expect(response, "230")
            expect(command("TYPE I"), "200")
            startDir = Regex("\"(.*)\"").find(command("PWD"))?.groupValues?.get(1)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    private fun read(): String {
        var line = reader.readLine() ?: throw IOException("FTP: połączenie zamknięte")
        if (line.length >= 4 && line[3] == '-') {
            val code = line.substring(0, 3)
            while (true) {
                val next = reader.readLine() ?: throw IOException("FTP: połączenie zamknięte")
                if (next.startsWith("$code ")) {
                    line = next
                    break
                }
            }
        }
        return line
    }

    private fun command(text: String): String {
        writer.write(text + "\r\n")
        writer.flush()
        return read()
    }

    private fun expect(response: String, vararg ok: String) {
        if (ok.none { response.startsWith(it) }) throw IOException("FTP: $response")
    }

    private fun cwd(path: String) = command("CWD $path").startsWith("250")

    /** Wchodzi do katalogu, tworząc brakujące (ścieżka bezwzględna, potem względem katalogu startowego). */
    fun enterDir(remoteDir: String, create: Boolean) {
        if (remoteDir.isBlank() || cwd(remoteDir)) return
        val segments = remoteDir.trim('/').split('/').filter(String::isNotEmpty)
        fun enter(target: String): Boolean {
            if (cwd(target)) return true
            if (!create) return false
            command("MKD $target")
            return cwd(target)
        }
        fun absolute(): Boolean {
            var path = ""
            for (segment in segments) {
                path += "/$segment"
                if (!enter(path)) return false
            }
            return true
        }
        // Serwer z „chroot” — ta sama ścieżka względem katalogu startowego.
        fun relative(): Boolean {
            if (startDir != null) cwd(startDir)
            return segments.all(::enter)
        }
        if (!absolute() && !relative()) {
            throw IOException("FTP: nie można wejść do $remoteDir (katalog startowy serwera: ${startDir ?: "?"})")
        }
    }

    private fun <T> transfer(text: String, block: (Socket) -> T): T? {
        val pasv = command("PASV")
        expect(pasv, "227")
        val numbers = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(pasv)
            ?: throw IOException("FTP: zła odpowiedź PASV: $pasv")
        val dataPort = numbers.groupValues[5].toInt() * 256 + numbers.groupValues[6].toInt()
        Socket().use { data ->
            // Adres z PASV bywa nieprawidłowy (NAT) — łączymy się z tym samym hostem.
            data.connect(InetSocketAddress(host, dataPort), 5000)
            data.soTimeout = 15_000
            val response = command(text)
            if (!response.startsWith("150") && !response.startsWith("125")) return null
            val result = block(data)
            data.close()
            expect(read(), "226", "250")
            return result
        }
    }

    fun store(name: String, bytes: ByteArray) {
        transfer("STOR $name") { it.getOutputStream().use { out -> out.write(bytes) } }
            ?: throw IOException("FTP: serwer odrzucił zapis $name")
    }

    fun list(dir: String): List<String>? =
        transfer("NLST $dir") { it.getInputStream().readBytes() }
            ?.toString(Charsets.UTF_8)?.lines()?.map { it.trim().substringAfterLast('/') }?.filter(String::isNotEmpty)

    fun retrieve(path: String): ByteArray? = transfer("RETR $path") { it.getInputStream().readBytes() }

    override fun close() {
        runCatching { command("QUIT") }
        socket.close()
    }
}

/** Wysyłanie logu na dekoder: przez wtyczkę EPG Scan, a gdy jej nie ma — przez FTP (jak w Androidzie). */
internal object DecoderUpload {
    fun logName(): String {
        val host = (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME"))
            ?.replace(Regex("[^A-Za-z0-9_-]"), "")?.takeIf(String::isNotBlank) ?: "windows"
        return "e2stream-$host.log"
    }

    fun send(config: DesktopConfig, name: String, data: ByteArray): String {
        require(config.host.isNotBlank()) { "Najpierw ustaw adres dekodera." }
        val dir = config.ftpDir.ifBlank { DesktopConfig.DEFAULT_FTP_DIR }
        val pluginError = try {
            pluginUpload(config, dir, name, data)
            return "$dir/$name (przez wtyczkę EPG Scan)"
        } catch (e: Exception) {
            e
        }
        try {
            FtpSession(hostForSocket(config.host), config.ftpUser, config.ftpPass).use { ftp ->
                ftp.enterDir(dir, create = true)
                ftp.store(name, data)
            }
        } catch (e: Exception) {
            e.addSuppressed(pluginError)
            throw IOException("${e.message} (wtyczka: ${pluginError.message})", e)
        }
        return "$dir/$name (FTP)"
    }

    private fun pluginUpload(config: DesktopConfig, dir: String, name: String, data: ByteArray) {
        val query = "dir=${URLEncoder.encode(dir, Charsets.UTF_8)}&name=${URLEncoder.encode(name, Charsets.UTF_8)}"
        val connection = URI("http://${hostForUrl(config.host)}:${config.pluginPort}/api/upload?$query")
            .toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 4000
        connection.readTimeout = 60_000
        if (config.pluginToken.isNotBlank()) connection.setRequestProperty("X-E2S-Token", config.pluginToken)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(data.size)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.outputStream.use { it.write(data) }
            if (connection.responseCode != 200) throw IOException("EPG Scan HTTP ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (!json.optBoolean("ok")) throw IOException("EPG Scan: ${json.optString("error")}")
        } finally {
            connection.disconnect()
        }
    }

    private fun hostForSocket(host: String) = host.trim().removePrefix("[").removeSuffix("]")
}

internal fun hostForUrl(host: String): String =
    if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
