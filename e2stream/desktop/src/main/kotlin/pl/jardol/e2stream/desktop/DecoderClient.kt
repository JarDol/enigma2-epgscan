package pl.jardol.e2stream.desktop

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

data class Bouquet(val name: String, val ref: String)
data class Channel(val number: Int, val name: String, val ref: String)
data class DecoderCatalog(val bouquets: List<Bouquet>, val usesPlugin: Boolean)
data class NowEvent(val title: String, val begin: Long, val end: Long)

fun serviceKey(ref: String): String = ref.trim().trimEnd(':').uppercase()

class DecoderClient(private val config: DesktopConfig) {
    fun loadBouquets(): DecoderCatalog {
        require(config.host.isNotBlank()) { "Podaj adres dekodera." }
        validatePort(config.pluginPort, "EPG Scan")
        validatePort(config.httpPort, "OpenWebif")

        var pluginError: Exception? = null
        val pluginCatalog = try {
            val info = get(config.pluginPort, "/api/info", plugin = true, timeoutMs = 4000)
            if (info.optInt("api") >= 1) {
                val items = get(config.pluginPort, "/api/bouquets", plugin = true)
                    .optJSONArray("bouquets")
                if (items == null) {
                    throw IOException("EPG Scan nie zwrócił listy bukietów.")
                }
                val bouquets = (0 until items.length()).mapNotNull { index ->
                    val item = items.optJSONObject(index) ?: return@mapNotNull null
                    val ref = item.optString("ref")
                    if (ref.isBlank()) null else Bouquet(
                        name = OpenWebifText.unescape(item.optString("name", ref)),
                        ref = ref,
                    )
                }
                DecoderCatalog(bouquets, usesPlugin = true)
            } else {
                throw IOException("Nieobsługiwana wersja API EPG Scan.")
            }
        } catch (e: Exception) {
            pluginError = e
            null
        }
        if (pluginCatalog != null) return pluginCatalog

        return try {
            val items = get(config.httpPort, "/api/getservices").optJSONArray("services")
                ?: throw IOException("OpenWebif nie zwrócił listy bukietów.")
            val bouquets = (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val ref = item.optString("servicereference")
                if (ref.isBlank()) null else Bouquet(
                    name = OpenWebifText.unescape(item.optString("servicename", ref)).trim(),
                    ref = ref,
                )
            }
            DecoderCatalog(bouquets, usesPlugin = false)
        } catch (openWebifError: Exception) {
            pluginError?.let(openWebifError::addSuppressed)
            throw IOException(
                "Nie udało się połączyć z EPG Scan ani OpenWebif: ${openWebifError.message}",
                openWebifError,
            )
        }
    }

    fun loadChannels(bouquet: Bouquet, usesPlugin: Boolean): List<Channel> {
        val port = if (usesPlugin) config.pluginPort else config.httpPort
        validatePort(port, if (usesPlugin) "EPG Scan" else "OpenWebif")
        val path = if (usesPlugin) {
            "/api/services?bRef=${encode(bouquet.ref)}"
        } else {
            "/api/getservices?sRef=${encode(bouquet.ref)}"
        }
        val items = get(port, path, plugin = usesPlugin, timeoutMs = 30_000)
            .optJSONArray(if (usesPlugin) "services" else "services")
            ?: throw IOException("Dekoder nie zwrócił listy kanałów.")

        val channels = mutableListOf<Channel>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val ref: String
            val name: String
            val number: Int
            if (usesPlugin) {
                ref = item.optString("play").ifBlank { item.optString("ref") }
                name = item.optString("name")
                number = item.optInt("num", index + 1)
            } else {
                ref = item.optString("servicereference")
                val flags = ref.split(":").getOrNull(1)?.toIntOrNull() ?: 0
                if ((flags and 64) != 0 || (flags and 1) != 0) continue
                name = item.optString("servicename", ref)
                number = item.optInt("pos", index + 1).takeIf { it > 0 } ?: index + 1
            }
            if (ref.isBlank()) continue
            channels += Channel(number, OpenWebifText.unescape(name).trim(), ref)
        }
        return channels
    }

    fun streamUrl(channel: Channel): String {
        validatePort(config.streamPort, "streaming")
        return "http://${hostForUrl(config.host)}:${config.streamPort}/${channel.ref.replace(" ", "%20")}"
    }

    fun loadNow(bouquet: Bouquet): Map<String, NowEvent> {
        validatePort(config.httpPort, "OpenWebif")
        val events = get(config.httpPort, "/api/epgnow?bRef=${encode(bouquet.ref)}")
            .optJSONArray("events")
            ?: throw IOException("OpenWebif nie zwrócił bieżącego EPG.")
        val result = mutableMapOf<String, NowEvent>()
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            val ref = event.optString("sref")
            val title = event.optString("title")
            if (ref.isBlank() || title.isBlank() || title == "None" || title == "null") continue
            val begin = event.optLong("begin_timestamp")
            val duration = event.optLong("duration_sec")
            result[serviceKey(ref)] = NowEvent(OpenWebifText.unescape(title), begin, begin + duration)
        }
        return result
    }

    private fun validatePort(port: Int, service: String) {
        require(port in 1..65535) { "Nieprawidłowy port $service: $port." }
    }

    private fun get(
        port: Int,
        path: String,
        plugin: Boolean = false,
        timeoutMs: Int = 15_000,
    ): JSONObject {
        val connection = URI("http://${hostForUrl(config.host)}:$port$path")
            .toURL()
            .openConnection() as HttpURLConnection
        connection.connectTimeout = minOf(timeoutMs, 5000)
        connection.readTimeout = timeoutMs
        if (plugin && config.pluginToken.isNotBlank()) {
            connection.setRequestProperty("X-E2S-Token", config.pluginToken)
        } else if (!plugin && config.username.isNotBlank()) {
            val credentials = "${config.username}:${config.password}"
            val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
            connection.setRequestProperty("Authorization", "Basic $encoded")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} z portu $port")
            }
            return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private fun hostForUrl(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
}

private object OpenWebifText {
    private val entities = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val named = mapOf(
        "quot" to "\"", "amp" to "&", "lt" to "<", "gt" to ">", "apos" to "'",
        "nbsp" to " ", "ndash" to "–", "mdash" to "—", "hellip" to "…",
        "bdquo" to "„", "rdquo" to "”", "ldquo" to "“",
    )

    fun unescape(text: String): String {
        var result = text
        repeat(2) {
            result = entities.replace(result) { match ->
                val entity = match.groupValues[1]
                when {
                    entity.startsWith("#x", ignoreCase = true) ->
                        entity.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                    entity.startsWith("#") ->
                        entity.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                    else -> named[entity]
                } ?: match.value
            }
        }
        return result
    }
}
