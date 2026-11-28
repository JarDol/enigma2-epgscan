package pl.jardol.e2stream

import org.json.JSONException
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

internal class PhilipsPairingServer(
    address: String,
    private val tv: PhilipsSleepTest,
    private val texts: Texts,
    private val onPaired: () -> Unit,
    private val onFinished: (Boolean) -> Unit
) {
    data class Texts(
        val title: String, val start: String, val pinHint: String, val submit: String,
        val retry: String, val error: String, val paired: String
    )

    private val socket = ServerSocket(0, 4, InetAddress.getByName(address)).apply { soTimeout = 1000 }
    private val token = ByteArray(9).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val stopped = AtomicBoolean(false)
    @Volatile private var activeClient: Socket? = null
    private var pinRequested = false
    val url = "http://$address:${socket.localPort}/$token"

    fun start() {
        Thread {
            val expires = System.nanoTime() + 180_000_000_000L
            var completed = false
            try {
                while (!stopped.get() && System.nanoTime() < expires && !completed) {
                    val client = try { socket.accept() } catch (_: SocketTimeoutException) { continue }
                    try {
                        activeClient = client
                        client.use { completed = handle(it) }
                    } catch (e: IOException) {
                        AppLog.i("Philips pairing page connection failed: ${e.javaClass.simpleName}")
                    } catch (e: IllegalArgumentException) {
                        AppLog.i("Philips pairing page request rejected: ${e.javaClass.simpleName}")
                    } finally {
                        activeClient = null
                    }
                }
            } catch (e: SocketException) {
                if (!stopped.get()) AppLog.i("Philips pairing server failed: ${e.javaClass.simpleName}")
            } catch (e: IOException) {
                if (!stopped.get()) AppLog.i("Philips pairing server failed: ${e.javaClass.simpleName}")
            } finally {
                stop()
                if (completed) onPaired()
                onFinished(completed)
            }
        }.start()
    }

    fun stop() {
        if (stopped.compareAndSet(false, true)) {
            try { activeClient?.close() } catch (e: IOException) {
                AppLog.i("Philips pairing page close failed: ${e.javaClass.simpleName}")
            }
            try { socket.close() } catch (e: IOException) {
                AppLog.i("Philips pairing server close failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun handle(client: Socket): Boolean {
        client.soTimeout = 5000
        if (!client.inetAddress.isSiteLocalAddress && !client.inetAddress.isLinkLocalAddress && !client.inetAddress.isLoopbackAddress) {
            reply(client, 403, "Forbidden")
            return false
        }
        val input = BufferedInputStream(client.getInputStream())
        val request = readLine(input, 2048).split(' ')
        if (request.size != 3 || request[2] !in listOf("HTTP/1.0", "HTTP/1.1")) {
            reply(client, 400, "Bad request"); return false
        }
        var length = 0
        var headerBytes = 0
        var host = ""
        var contentType = ""
        while (true) {
            val line = readLine(input, 2048)
            headerBytes += line.length
            if (headerBytes > 8192) throw IOException("Headers too large")
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index <= 0) throw IOException("Invalid header")
            val value = line.substring(index + 1).trim()
            when (line.substring(0, index).lowercase()) {
                "host" -> host = value
                "content-length" -> length = value.toIntOrNull() ?: throw IOException("Invalid length")
                "content-type" -> contentType = value.substringBefore(';').trim().lowercase()
            }
        }
        if (host != "${socket.inetAddress.hostAddress}:${socket.localPort}" || length !in 0..256) {
            reply(client, 400, "Bad request"); return false
        }
        val path = request[1]
        if (path != "/$token" && path != "/$token/start" && path != "/$token/pin") {
            reply(client, 404, "Not found"); return false
        }
        if (request[0] == "GET" && path == "/$token") {
            reply(client, 200, startPage()); return false
        }
        if (request[0] != "POST" || contentType != "application/x-www-form-urlencoded") {
            reply(client, 405, "Method not allowed"); return false
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) throw IOException("Incomplete request")
            offset += count
        }
        if (path == "/$token/start") {
            pinRequested = false
            try {
                tv.requestPin()
                pinRequested = true
                reply(client, 200, pinPage(""))
            } catch (e: IOException) { reply(client, 200, startPage(texts.error))
            } catch (e: JSONException) { reply(client, 200, startPage(texts.error))
            } catch (e: GeneralSecurityException) { reply(client, 200, startPage(texts.error))
            } catch (e: SecurityException) { reply(client, 200, startPage(texts.error)) }
            return false
        }
        if (path == "/$token/pin" && pinRequested) {
            val fields = String(body, StandardCharsets.UTF_8).split('&').mapNotNull {
                val parts = it.split('=', limit = 2)
                if (parts.size == 2) URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8") else null
            }.toMap()
            val pin = fields["pin"] ?: ""
            if (!pin.matches(Regex("[0-9]{4,8}"))) {
                reply(client, 200, pinPage(texts.error)); return false
            }
            try {
                tv.confirmPin(pin)
                reply(client, 200, page(texts.paired))
                return true
            } catch (e: IOException) { reply(client, 200, startPage(texts.error))
            } catch (e: JSONException) { reply(client, 200, startPage(texts.error))
            } catch (e: GeneralSecurityException) { reply(client, 200, startPage(texts.error))
            } catch (e: IllegalArgumentException) { reply(client, 200, startPage(texts.error))
            } catch (e: SecurityException) { reply(client, 200, startPage(texts.error)) }
            pinRequested = false
            return false
        }
        reply(client, 400, "Bad request")
        return false
    }

    private fun startPage(error: String = "") = page(
        "<p>${escape(error)}</p><form method=\"post\" action=\"/$token/start\"><button>${escape(if (error.isEmpty()) texts.start else texts.retry)}</button></form>")

    private fun pinPage(error: String) = page(
        "<p>${escape(texts.pinHint)}</p><p>${escape(error)}</p><form method=\"post\" action=\"/$token/pin\">" +
            "<input name=\"pin\" type=\"password\" inputmode=\"numeric\" pattern=\"[0-9]{4,8}\" autocomplete=\"off\" required>" +
            "<button>${escape(texts.submit)}</button></form>")

    private fun page(content: String) = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<title>${escape(texts.title)}</title><style>body{font:18px sans-serif;max-width:34em;margin:2em auto;padding:0 1em}" +
        "button,input{font:inherit;padding:.6em;margin:.4em}</style></head><body><h1>${escape(texts.title)}</h1>$content</body></html>"

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun reply(client: Socket, status: Int, html: String) {
        val body = html.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Method Not Allowed" }
        val headers = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n" +
            "Content-Security-Policy: default-src 'none'; form-action 'self'; style-src 'unsafe-inline'; base-uri 'none'\r\n" +
            "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n\r\n"
        client.getOutputStream().apply { write(headers.toByteArray(StandardCharsets.US_ASCII)); write(body); flush() }
    }

    private fun readLine(input: BufferedInputStream, limit: Int): String {
        val buffer = ByteArrayOutputStream()
        while (buffer.size() <= limit) {
            val b = input.read()
            if (b < 0) throw IOException("Incomplete request")
            if (b == 10) return buffer.toString("UTF-8").trimEnd('\r')
            buffer.write(b)
        }
        throw IOException("Request line too long")
    }
}
