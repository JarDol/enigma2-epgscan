package pl.jardol.e2stream

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal class PhilipsSleepTest(
    val host: String,
    private val transport: Transport = LocalTransport(host)
) {
    internal data class Response(val status: Int, val body: String, val challenge: String? = null)
    internal interface Transport {
        fun post(path: String, body: JSONObject, authorization: String? = null): Response
    }

    private val deviceId = UUID.randomUUID().toString().replace("-", "").take(16)
    private var pairingKey: String? = null
    private var timestamp: Long? = null
    private var paired = false

    private fun device() = JSONObject()
        .put("device_name", "E2Stream")
        .put("device_os", "Android")
        .put("app_name", "E2Stream sleep test")
        .put("type", "native")
        .put("app_id", "app.id")
        .put("id", deviceId)

    fun requestPin() {
        val body = JSONObject().put("scope", org.json.JSONArray().put("read").put("write").put("control"))
            .put("device", device())
        val response = transport.post("/6/pair/request", body)
        requireSuccess(response)
        val json = JSONObject(response.body)
        if (json.optString("error_id") != "SUCCESS") throw IOException("Pairing request rejected")
        pairingKey = json.getString("auth_key").takeIf { it.isNotBlank() }
            ?: throw IOException("Missing pairing key")
        timestamp = json.getLong("timestamp")
    }

    fun confirmPin(pin: String) {
        require(pin.matches(Regex("[0-9]{4,8}"))) { "Invalid PIN" }
        val key = pairingKey ?: throw IOException("Pairing not started")
        val time = timestamp ?: throw IOException("Pairing not started")
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(Base64.decode(PAIRING_SECRET, Base64.DEFAULT), "HmacSHA1"))
        val signature = Base64.encodeToString(hex(mac.doFinal("$time$pin".toByteArray(StandardCharsets.UTF_8)))
            .toByteArray(StandardCharsets.US_ASCII), Base64.NO_WRAP)
        val auth = JSONObject().put("auth_AppId", "1").put("pin", pin)
            .put("auth_timestamp", time).put("auth_signature", signature)
        val response = authenticatedPost("/6/pair/grant", JSONObject().put("auth", auth).put("device", device()), key)
        requireSuccess(response)
        val result = JSONObject(response.body)
        if (result.optString("error_id") != "SUCCESS") throw IOException("PIN rejected")
        paired = true
    }

    fun standby() {
        if (!paired) throw IOException("Not paired")
        val response = authenticatedPost("/6/input/key", JSONObject().put("key", "Standby"),
            pairingKey ?: throw IOException("Not paired"))
        requireSuccess(response)
        if (response.body.isNotBlank()) {
            val result = JSONObject(response.body)
            if (result.has("error_id") && result.optString("error_id") != "SUCCESS")
                throw IOException("Standby rejected")
        }
    }

    private fun authenticatedPost(path: String, body: JSONObject, password: String): Response {
        val challenge = transport.post(path, body)
        if (challenge.status != HttpURLConnection.HTTP_UNAUTHORIZED) return challenge
        val authorization = digestAuthorization(challenge.challenge ?: throw IOException("Missing authentication challenge"),
            "POST", path, deviceId, password)
        return transport.post(path, body, authorization)
    }

    companion object {
        private const val PAIRING_SECRET = "JCqdN5AcnAHgJYseUn7ER5k3qgtemfUvMRghQpTfTZq7Cvv8EPQPqfz6dDxPQPSu4gKFPWkJGw32zyASgJkHwCjU"

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun md5(value: String) = hex(MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8)))

        internal fun digestAuthorization(challenge: String, method: String, path: String, user: String, password: String): String {
            if (!challenge.startsWith("Digest ", ignoreCase = true)) throw IOException("Unsupported authentication")
            val fields = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""").findAll(challenge.substring(7))
                .associate { it.groupValues[1].lowercase() to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
            val realm = fields["realm"] ?: throw IOException("Missing authentication realm")
            val nonce = fields["nonce"] ?: throw IOException("Missing authentication nonce")
            if (fields["algorithm"]?.equals("MD5", ignoreCase = true) == false) throw IOException("Unsupported digest algorithm")
            val qop = fields["qop"]
            if (qop != null && qop.split(',').none { it.trim() == "auth" }) throw IOException("Unsupported digest quality")
            val cnonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val cnonce = hex(cnonceBytes)
            val ha1 = md5("$user:$realm:$password")
            val ha2 = md5("$method:$path")
            val digest = if (qop == null) md5("$ha1:$nonce:$ha2")
                else md5("$ha1:$nonce:00000001:$cnonce:auth:$ha2")
            fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            val parts = mutableListOf("username=${quote(user)}", "realm=${quote(realm)}", "nonce=${quote(nonce)}",
                "uri=${quote(path)}", "response=${quote(digest)}", "algorithm=MD5")
            fields["opaque"]?.let { parts += "opaque=${quote(it)}" }
            if (qop != null) parts += listOf("qop=auth", "nc=00000001", "cnonce=${quote(cnonce)}")
            return "Digest " + parts.joinToString(", ")
        }

        private fun requireSuccess(response: Response) {
            if (response.status !in 200..299) throw IOException("TV returned HTTP ${response.status}")
        }

        fun isLocalAddress(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> !c.isDigit() } }) return false
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            return octets[0] == 127 || octets[0] == 10 ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 169 && octets[1] == 254)
        }

        fun deviceAddress(): String = Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull(InetAddress::getHostAddress)
            .firstOrNull { isLocalAddress(it) } ?: ""
    }

    private class LocalTransport(private val host: String) : Transport {
        private var pinnedCertificate: ByteArray? = null
        private val socketFactory = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<X509TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.isEmpty()) throw java.security.cert.CertificateException("No TV certificate")
                    val fingerprint = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    val pinned = pinnedCertificate
                    if (pinned == null) pinnedCertificate = fingerprint
                    else if (!MessageDigest.isEqual(pinned, fingerprint))
                        throw java.security.cert.CertificateException("TV certificate changed")
                }
            }), SecureRandom())
        }.socketFactory

        init { require(isLocalAddress(host)) { "Invalid local address" } }

        override fun post(path: String, body: JSONObject, authorization: String?): Response {
            val connection = URL("https://$host:1926$path").openConnection() as HttpsURLConnection
            connection.sslSocketFactory = socketFactory
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (authorization != null) connection.setRequestProperty("Authorization", authorization)
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                return Response(status, text, connection.getHeaderField("WWW-Authenticate"))
            } finally {
                connection.disconnect()
            }
        }
    }
}
