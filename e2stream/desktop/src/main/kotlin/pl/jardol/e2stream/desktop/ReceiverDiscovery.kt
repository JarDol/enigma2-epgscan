package pl.jardol.e2stream.desktop

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class FoundReceiver(val ip: String, val label: String)

/** Szuka dekoderów Enigma2 w sieciach /24 lokalnych interfejsów (port OpenWebif + /api/about). */
object ReceiverDiscovery {
    fun localPrefixes(): List<String> {
        val prefixes = linkedSetOf<String>()
        try {
            for (networkInterface in NetworkInterface.getNetworkInterfaces()) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (address in networkInterface.interfaceAddresses) {
                    val ip = address.address
                    if (ip is Inet4Address && ip.isSiteLocalAddress) {
                        val parts = ip.hostAddress.split(".")
                        if (parts.size == 4) prefixes += "${parts[0]}.${parts[1]}.${parts[2]}."
                    }
                }
            }
        } catch (_: Exception) {
        }
        return prefixes.toList()
    }

    fun scan(port: Int, pluginPort: Int): List<FoundReceiver> {
        val ips = localPrefixes().flatMap { prefix -> (1..254).map { prefix + it } }
        val found = Collections.synchronizedList(mutableListOf<FoundReceiver>())
        val pool = Executors.newFixedThreadPool(48)
        ips.forEach { ip -> pool.execute { probe(ip, port, pluginPort)?.let(found::add) } }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
        return found.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    private fun probe(ip: String, port: Int, pluginPort: Int): FoundReceiver? {
        try {
            Socket().use { it.connect(InetSocketAddress(ip, port), 350) }
        } catch (_: Exception) {
            return null
        }
        return try {
            val connection = open(ip, port, "/api/about", 1500, 2500)
            try {
                val plugin = pluginVersion(ip, pluginPort)?.let { "  + EPG Scan $it" }.orEmpty()
                when (connection.responseCode) {
                    200 -> {
                        val info = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                            .optJSONObject("info") ?: return null
                        val label = listOf(info.optString("brand"), info.optString("model")).joinToString(" ").trim()
                        val image = "${info.optString("imagedistro")} ${info.optString("imagever")}".trim()
                        FoundReceiver(ip, "$label  ($image)".trim() + plugin)
                    }
                    401 -> FoundReceiver(ip, "OpenWebif (wymaga logowania)$plugin")
                    else -> null
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun pluginVersion(ip: String, port: Int): String? = try {
        val connection = open(ip, port, "/api/info", 1200, 2000)
        try {
            if (connection.responseCode == 200) {
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    .optString("version").ifBlank { null }
            } else null
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun open(ip: String, port: Int, path: String, connectMs: Int, readMs: Int): HttpURLConnection =
        (URI("http://$ip:$port$path").toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
        }
}
