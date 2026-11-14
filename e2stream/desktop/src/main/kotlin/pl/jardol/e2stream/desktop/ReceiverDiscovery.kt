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

data class FoundReceiver(
    val ip: String,
    val label: String,
    val pluginPort: Int? = null,
    val httpPort: Int? = null,
    val streamPort: Int? = null,
    val viaMdns: Boolean = false,
)

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

    /** mDNS (wtyczka EPG Scan, także poza siecią /24) i przeszukanie sieci /24 lokalnych interfejsów. */
    /** Adresy dodatkowej podsieci „a.b.c.d/24…30” (bez adresu sieci i rozgłoszeniowego); null — błędny zapis. */
    fun subnetHosts(value: String): List<String>? {
        val parts = value.trim().split('/')
        if (parts.size != 2) return null
        val octets = parts[0].split('.')
        if (octets.size != 4 || octets.any { !it.matches(Regex("[0-9]{1,3}")) }) return null
        val numbers = octets.map { it.toInt() }
        if (numbers.any { it !in 0..255 } || numbers[0] == 0 || numbers[0] == 127 || numbers[0] >= 224) return null
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix !in 24..30) return null
        val address = numbers.fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val network = address and mask
        val broadcast = network or (0xffffffffL xor mask)
        return (network + 1 until broadcast).map { ip -> listOf(24, 16, 8, 0).joinToString(".") { ((ip shr it) and 255).toString() } }
    }

    fun scan(port: Int, pluginPort: Int, extraSubnet: String = ""): List<FoundReceiver> {
        val ips = (localPrefixes().flatMap { prefix -> (1..254).map { prefix + it } } + subnetHosts(extraSubnet).orEmpty()).distinct()
        val found = Collections.synchronizedList(mutableListOf<FoundReceiver>())
        val pool = Executors.newFixedThreadPool(48)
        val mdns = pool.submit<List<MdnsDiscovery.Service>> { runCatching { MdnsDiscovery.find() }.getOrDefault(emptyList()) }
        ips.forEach { ip -> pool.execute { probe(ip, port, pluginPort)?.let(found::add) } }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
        val announced = runCatching { mdns.get(1, TimeUnit.SECONDS) }.getOrDefault(emptyList())
        if (announced.isNotEmpty()) AppLog.i("mDNS: ${announced.joinToString { "${it.ip}:${it.port}" }}")
        val merged = linkedMapOf<String, FoundReceiver>()
        found.forEach { merged[it.ip] = it }
        for (service in announced) {
            val scanned = merged[service.ip]
            val plugin = pluginVersion(service.ip, service.port)?.let { "EPG Scan $it" } ?: "EPG Scan"
            merged[service.ip] = FoundReceiver(
                ip = service.ip,
                label = (scanned?.label ?: plugin) + "  · mDNS",
                pluginPort = service.port,
                httpPort = service.webifPort,
                streamPort = service.streamPort,
                viaMdns = true,
            )
        }
        return merged.values.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
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
