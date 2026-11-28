package pl.jardol.e2stream

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.File
import java.io.FileInputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit, temporary read-only sharing of the supplied files; never exposes a directory. */
internal class LocalShareServer(
    address: String,
    private val title: String,
    private val entries: List<Entry>,
    private val onFinished: () -> Unit,
) {
    class Entry(val name: String, val size: Long, val open: () -> InputStream)

    companion object {
        fun recording(name: String, parts: List<File>): Entry {
            require(parts.isNotEmpty() && parts.all { it.isFile }) { "Recording files unavailable" }
            return Entry(name, parts.sumOf { it.length() }) {
                val files = parts.iterator()
                SequenceInputStream(object : java.util.Enumeration<InputStream> {
                    override fun hasMoreElements() = files.hasNext()
                    override fun nextElement(): InputStream = FileInputStream(files.next())
                })
            }
        }
    }

    private val socket = ServerSocket(0, 4, InetAddress.getByName(address)).apply { soTimeout = 1000 }
    private val token = ByteArray(18).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val stopped = AtomicBoolean(false)
    @Volatile private var client: Socket? = null
    val url = "http://$address:${socket.localPort}/$token"

    fun start() {
        Thread({
            val expires = System.nanoTime() + 180_000_000_000L
            try {
                while (!stopped.get() && System.nanoTime() < expires) {
                    val next = try { socket.accept() } catch (_: SocketTimeoutException) { continue }
                    try {
                        client = next
                        next.use { handle(it) }
                    } catch (e: IOException) {
                        if (!stopped.get()) AppLog.i("local share: connection failed (${e.javaClass.simpleName})")
                    } finally {
                        client = null
                    }
                }
            } catch (e: SocketException) {
                if (!stopped.get()) AppLog.i("local share: server failed (${e.javaClass.simpleName})")
            } catch (e: IOException) {
                if (!stopped.get()) AppLog.i("local share: server failed (${e.javaClass.simpleName})")
            } finally {
                stop()
                onFinished()
            }
        }, "local-share").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (stopped.compareAndSet(false, true)) {
            try { client?.close() } catch (e: IOException) { AppLog.i("local share: client close failed (${e.javaClass.simpleName})") }
            try { socket.close() } catch (e: IOException) { AppLog.i("local share: server close failed (${e.javaClass.simpleName})") }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5000
        if (!client.inetAddress.isSiteLocalAddress && !client.inetAddress.isLinkLocalAddress && !client.inetAddress.isLoopbackAddress) {
            respond(client, 403, "Forbidden"); return
        }
        val input = BufferedInputStream(client.getInputStream())
        val request = line(input, 2048).split(' ')
        if (request.size != 3 || request[0] != "GET" || request[2] !in listOf("HTTP/1.0", "HTTP/1.1")) {
            respond(client, 400, "Bad request"); return
        }
        var host = ""
        var total = 0
        while (true) {
            val header = line(input, 2048)
            total += header.length
            if (total > 8192) throw IOException("Headers too large")
            if (header.isEmpty()) break
            val split = header.indexOf(':')
            if (split <= 0) throw IOException("Invalid header")
            if (header.substring(0, split).equals("host", true)) host = header.substring(split + 1).trim()
        }
        if (host != "${socket.inetAddress.hostAddress}:${socket.localPort}") {
            respond(client, 400, "Bad request"); return
        }
        if (request[1] == "/$token") {
            val items = entries.mapIndexed { i, entry ->
                "<li><a href=\"/$token/$i\">${escape(entry.name)}</a></li>"
            }.joinToString("")
            respond(client, 200, "<!doctype html><meta name=\"viewport\" content=\"width=device-width\">" +
                "<title>${escape(title)}</title><h1>${escape(title)}</h1><ul>$items</ul>")
            return
        }
        val index = request[1].removePrefix("/$token/").toIntOrNull()
        if (!request[1].startsWith("/$token/") || index == null || index !in entries.indices) {
            respond(client, 404, "Not found"); return
        }
        val entry = entries[index]
        entry.open().use { data ->
            val filename = URLEncoder.encode(entry.name, "UTF-8").replace("+", "%20")
            val header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                "Content-Length: ${entry.size}\r\nContent-Disposition: attachment; filename=\"download\"; filename*=UTF-8''$filename\r\n" +
                commonHeaders()
            val out = client.getOutputStream()
            out.write(header.toByteArray(Charsets.US_ASCII))
            val buffer = ByteArray(64 * 1024)
            var remaining = entry.size
            while (remaining > 0) {
                val count = data.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw IOException("File shortened during download")
                out.write(buffer, 0, count)
                remaining -= count
            }
            out.flush()
        }
    }

    private fun respond(client: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" + commonHeaders()
        client.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    private fun commonHeaders() = "Connection: close\r\nCache-Control: no-store\r\n" +
        "Content-Security-Policy: default-src 'none'; base-uri 'none'; frame-ancestors 'none'\r\n" +
        "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n\r\n"

    private fun line(input: InputStream, limit: Int): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= limit) {
            val b = input.read()
            if (b < 0) throw IOException("Incomplete request")
            if (b == 10) return bytes.toString("UTF-8").trimEnd('\r')
            bytes.write(b)
        }
        throw IOException("Request too long")
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
