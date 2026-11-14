package pl.jardol.e2stream

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.net.URL
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

data class FoundReceiver(
    val ip: String,
    val httpPort: Int,
    val pluginPort: Int?,
    val streamPort: Int?,
    val description: String,
    val verified: Boolean,
    val viaMdns: Boolean,
)

object Discovery {
    data class Result(val receivers: List<FoundReceiver>, val warnings: Boolean)

    /** Dodatkowy zakres ma najwyżej 254 adresy; adres sieci i broadcast nie są skanowane. */
    internal fun subnetHosts(value: String): List<String>? {
        val parts = value.trim().split('/')
        if (parts.size != 2) return null
        val octets = parts[0].split('.')
        if (octets.size != 4 || octets.any { !it.matches(Regex("[0-9]{1,3}")) }) return null
        val numbers = octets.map { it.toInt() }
        if (numbers.any { it !in 0..255 } || numbers[0] == 0 || numbers[0] == 127 || numbers[0] >= 224) return null
        if (!parts[1].matches(Regex("[0-9]{1,2}"))) return null
        val prefix = parts[1].toInt()
        if (prefix !in 24..30) return null
        val address = numbers.fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val network = address and mask
        val broadcast = network or (0xffffffffL xor mask)
        return (network + 1 until broadcast).map { ip ->
            listOf(24, 16, 8, 0).joinToString(".") { shift -> ((ip shr shift) and 255).toString() }
        }
    }

    private fun localPrefixes(): List<String> {
        val out = LinkedHashSet<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        for (ni in interfaces) {
            if (!ni.isUp || ni.isLoopback) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address
                if (a is Inet4Address && a.isSiteLocalAddress) {
                    val parts = a.hostAddress?.split(".") ?: continue
                    if (parts.size == 4) out.add("${parts[0]}.${parts[1]}.${parts[2]}.")
                }
            }
        }
        return out.toList()
    }

    /** Wywoływać poza wątkiem UI. Żaden token ani hasło nie są wysyłane podczas skanowania. */
    fun scan(context: Context, httpPort: Int, pluginPort: Int, subnet: String, onProgress: (Int, Int) -> Unit): Result {
        require(httpPort in 1..65535 && pluginPort in 1..65535) { "Nieprawidłowy port wyszukiwania" }
        val extra = if (subnet.isBlank()) emptyList() else requireNotNull(subnetHosts(subnet)) {
            "Nieprawidłowa dodatkowa podsieć IPv4"
        }
        var warnings = false
        val prefixes = try {
            localPrefixes()
        } catch (e: SocketException) {
            AppLog.i("wyszukiwanie: nie można odczytać interfejsów: ${e.message}")
            warnings = true
            emptyList()
        } catch (e: SecurityException) {
            AppLog.i("wyszukiwanie: brak dostępu do interfejsów: ${e.message}")
            warnings = true
            emptyList()
        }
        val ips = (prefixes.flatMap { prefix -> (1..254).map { prefix + it } } + extra).distinct()
        val found = Collections.synchronizedList(ArrayList<FoundReceiver>())
        val workers = Executors.newFixedThreadPool(48)
        val mdnsWorker = Executors.newSingleThreadExecutor()
        val mdnsFuture = mdnsWorker.submit<MdnsDiscovery.Result> { MdnsDiscovery.find(context) }
        val done = AtomicInteger(0)
        try {
            for (ip in ips) {
                workers.execute {
                    try {
                        probe(ip, httpPort, pluginPort)?.let { found.add(it) }
                    } finally {
                        onProgress(done.incrementAndGet(), ips.size)
                    }
                }
            }
            val mdns = try {
                mdnsFuture.get(20, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                mdnsFuture.cancel(true)
                AppLog.i("wyszukiwanie mDNS: przekroczono czas")
                MdnsDiscovery.Result(emptyList(), true)
            } catch (e: ExecutionException) {
                AppLog.i("wyszukiwanie mDNS: ${e.cause ?: e}")
                MdnsDiscovery.Result(emptyList(), true)
            }
            warnings = warnings || mdns.warning
            for (service in mdns.services) {
                workers.execute {
                    probe(
                        service.ip, service.webifPort ?: httpPort, service.port,
                        service.streamPort, advertised = true,
                    )?.let { found.add(it) }
                }
            }
            workers.shutdown()
            if (!workers.awaitTermination(60, TimeUnit.SECONDS)) {
                AppLog.i("wyszukiwanie: przekroczono czas skanowania")
                warnings = true
                workers.shutdownNow()
                workers.awaitTermination(5, TimeUnit.SECONDS)
            }
            val snapshot = synchronized(found) { found.toList() }
            return Result(merge(snapshot), warnings)
        } finally {
            workers.shutdownNow()
            mdnsFuture.cancel(true)
            mdnsWorker.shutdownNow()
        }
    }

    internal fun merge(receivers: List<FoundReceiver>): List<FoundReceiver> =
        receivers.groupBy { it.ip to it.httpPort }.values.map { group ->
            val preferred = group.firstOrNull { it.verified } ?: group.first()
            val mdns = group.firstOrNull { it.viaMdns }
            preferred.copy(
                pluginPort = mdns?.pluginPort ?: preferred.pluginPort,
                streamPort = mdns?.streamPort ?: preferred.streamPort,
                viaMdns = mdns != null,
            )
        }.sortedWith(compareBy<FoundReceiver> { !it.verified }.thenBy { it.ip }.thenBy { it.httpPort })

    private data class Response(val code: Int, val body: String?)

    private fun get(ip: String, port: Int, path: String): Response? {
        return try {
            val connection = URL("http://$ip:$port$path").openConnection() as HttpURLConnection
            connection.connectTimeout = 400
            connection.readTimeout = 1800
            connection.instanceFollowRedirects = false
            try {
                val code = connection.responseCode
                val body = if (code == 200) connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(65537)
                    var count = 0
                    while (count < buffer.size) {
                        val read = reader.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    if (count > 65536) null else String(buffer, 0, count)
                } else null
                Response(code, body)
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            null // Brak odpowiedzi na skanowanym adresie jest normalny.
        } catch (e: SecurityException) {
            AppLog.i("wyszukiwanie: brak dostępu do sieci: ${e.message}")
            null
        }
    }

    private fun json(body: String?): JSONObject? {
        if (body == null) return null
        return try { JSONObject(body) } catch (_: JSONException) { null }
    }

    internal fun probe(
        ip: String, httpPort: Int, pluginPort: Int, streamPort: Int? = null, advertised: Boolean = false,
    ): FoundReceiver? {
        val web = get(ip, httpPort, "/api/about")
        val info = if (web?.code == 200) json(web.body)?.optJSONObject("info") else null
        val webVerified = info != null && info.optString("webifver").isNotBlank() &&
            (info.optString("enigmaver").isNotBlank() || info.has("tuners"))
        val webAuth = web?.code == 401
        if (!webVerified && !webAuth && !advertised) return null

        val plugin = get(ip, pluginPort, "/api/info")
        val pluginInfo = if (plugin?.code == 200) json(plugin.body) else null
        val pluginVerified = pluginInfo?.optString("name") == "EPG Scan" && pluginInfo.optInt("api") >= 1
        if (!webVerified && !webAuth && !pluginVerified && !(advertised && plugin?.code == 401)) return null

        val model = if (webVerified) listOf(info!!.optString("brand"), info.optString("model"))
            .filter { it.isNotBlank() }.joinToString(" ")
        else if (pluginVerified) pluginInfo!!.optString("model") else ""
        val image = if (webVerified) listOf(info!!.optString("imagedistro"), info.optString("imagever"))
            .filter { it.isNotBlank() }.joinToString(" ") else ""
        val version = if (pluginVerified) pluginInfo!!.optString("version") else ""
        val description = listOf(model, image, if (version.isNotBlank()) "EPG Scan $version" else "")
            .filter { it.isNotBlank() }.joinToString("  ·  ")
        return FoundReceiver(
            ip, httpPort, if (advertised || pluginVerified) pluginPort else null, streamPort,
            description, webVerified || pluginVerified, advertised,
        )
    }
}
