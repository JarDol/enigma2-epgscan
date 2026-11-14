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

/** Wirtualny bukiet „Nagrania” — nagrania z dysku dekodera (OpenWebif /api/movielist). */
const val REC_BOUQUET = "e2stream:nagrania"
val RECORDINGS_BOUQUET = Bouquet("Nagrania", REC_BOUQUET)

data class Recording(
    val path: String,
    val title: String,
    val description: String = "",
    val service: String = "",
    val serviceRef: String = "",
    val begin: Long = 0,
    val lengthSec: Long = 0,
    val size: Long = 0,
    val isDir: Boolean = false,
)

/** Długość z OpenWebif: „m:ss” albo „h:mm:ss” → sekundy. */
internal fun parseRecordingLength(value: String): Long {
    val parts = value.split(":").map { it.trim().toLongOrNull() ?: return 0 }
    return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        else -> 0
    }
}

/** Odpowiedź /api/movielist → bieżący katalog oraz podkatalogi i pliki (od najnowszych). */
internal fun parseMovieList(json: JSONObject, requestedDir: String): Pair<String, List<Recording>> {
    fun JSONObject.text(key: String): String =
        if (isNull(key)) "" else optString(key).takeUnless { it == "None" || it == "null" }.orEmpty()
    val directory = json.text("directory").ifBlank { requestedDir }
    val dirs = mutableListOf<Recording>()
    json.optJSONArray("bookmarks")?.let { bookmarks ->
        for (index in 0 until bookmarks.length()) {
            val name = bookmarks.optString(index).trimEnd('/')
            if (name.isBlank() || name.startsWith(".")) continue
            dirs += Recording(directory.trimEnd('/') + "/" + name + "/", name, isDir = true)
        }
    }
    val files = mutableListOf<Recording>()
    json.optJSONArray("movies")?.let { movies ->
        for (index in 0 until movies.length()) {
            val item = movies.optJSONObject(index) ?: continue
            val path = item.text("filename")
            if (path.isBlank()) continue
            files += Recording(
                path = path,
                title = OpenWebifText.unescape(
                    item.text("eventname").ifBlank { path.substringAfterLast('/').substringBeforeLast('.') },
                ),
                description = mergeDescriptions(
                    OpenWebifText.unescape(item.text("description")),
                    OpenWebifText.unescape(item.text("descriptionExtended")),
                ),
                service = OpenWebifText.unescape(item.text("servicename")),
                serviceRef = item.text("serviceref"),
                begin = item.optLong("recordingtime"),
                lengthSec = parseRecordingLength(item.text("length")),
                size = item.optLong("filesize"),
            )
        }
    }
    return directory to (dirs + files.sortedByDescending { it.begin })
}

fun serviceKey(ref: String): String = ref.trim().trimEnd(':').uppercase()

/**
 * Opis krótki + rozszerzony bez powtórzeń: nadawcy często powtarzają w rozszerzonym opisie
 * krótki opis albo te same linie („Tytuł oryginalny: …”).
 */
internal fun mergeDescriptions(vararg parts: String): String {
    val seen = HashSet<String>()
    val lines = parts.flatMap { it.replace("\r\n", "\n").replace('\u008A', '\n').split('\n') }
        .map(String::trim)
        .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
    // Linia zawarta w innej, dłuższej (krótki opis = początek rozszerzonego) jest zbędna; krótkie
    // linie (np. gatunek) odpadają tylko, gdy kończą inną („… PL, 2026”).
    return lines.filterIndexed { index, line ->
        lines.withIndex().none { (other, text) ->
            other != index && text.length > line.length && if (line.length < 15) {
                text.endsWith(line, ignoreCase = true)
            } else {
                text.contains(line, ignoreCase = true)
            }
        }
    }.joinToString("\n")
}

/** Audycja z EPG; czasy w sekundach epoki. */
internal data class EpgEvent(
    val id: Int,
    val begin: Long,
    val duration: Long,
    val title: String,
    val shortDesc: String,
    val longDesc: String,
    val sref: String,
    val sname: String,
) {
    val end: Long get() = begin + duration
}

internal fun parseEpgEvents(json: JSONObject): List<EpgEvent> {
    val events = json.optJSONArray("events") ?: return emptyList()
    fun JSONObject.text(key: String): String =
        optString(key).takeUnless { it == "None" || it == "null" }.orEmpty()
    return (0 until events.length()).mapNotNull { index ->
        val item = events.optJSONObject(index) ?: return@mapNotNull null
        val title = OpenWebifText.unescape(item.text("title")).trim()
        if (title.isBlank()) return@mapNotNull null
        EpgEvent(
            id = item.optInt("id"),
            begin = item.optLong("begin_timestamp"),
            duration = item.optLong("duration_sec"),
            title = title,
            shortDesc = OpenWebifText.unescape(item.text("shortdesc")).trim(),
            longDesc = OpenWebifText.unescape(item.text("longdesc")).trim(),
            sref = item.text("sref"),
            sname = OpenWebifText.unescape(item.text("sname")).trim(),
        )
    }.sortedBy { it.begin }
}

data class DecoderTimer(
    val serviceRef: String,
    val channel: String,
    val title: String,
    val description: String,
    val begin: Long,
    val end: Long,
    val eventId: Int,
    val switchOnly: Boolean,
    val disabled: Boolean,
)

/** Odpowiedź /api/timerlist → timery od najwcześniejszego (bez niepełnych wpisów). */
internal fun parseTimerList(json: JSONObject): List<DecoderTimer> {
    val timers = json.optJSONArray("timers") ?: throw IOException("OpenWebif nie zwrócił listy timerów.")
    fun JSONObject.text(key: String): String =
        if (isNull(key)) "" else optString(key).takeUnless { it == "None" || it == "null" }.orEmpty()
    fun JSONObject.flag(key: String): Boolean = when (val value = opt(key)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value == "1" || value.equals("true", true)
        else -> false
    }
    val out = mutableListOf<DecoderTimer>()
    for (index in 0 until timers.length()) {
        val item = timers.optJSONObject(index) ?: continue
        val ref = item.text("serviceref")
        val begin = item.optLong("begin")
        val end = item.optLong("end")
        if (ref.isBlank() || begin <= 0 || end <= begin) continue
        out += DecoderTimer(
            serviceRef = ref,
            channel = OpenWebifText.unescape(item.text("servicename")),
            title = OpenWebifText.unescape(item.text("name").ifBlank { item.text("description") }),
            description = OpenWebifText.unescape(item.text("description")),
            begin = begin,
            end = end,
            eventId = item.optInt("eit"),
            switchOnly = item.flag("justplay"),
            disabled = item.flag("disabled"),
        )
    }
    return out.sortedBy { it.begin }
}

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

    /** Wersja wtyczki EPG Scan albo wyjątek, gdy wtyczka nie odpowiada. */
    fun pluginVersion(): String {
        require(config.host.isNotBlank()) { "Podaj adres dekodera." }
        validatePort(config.pluginPort, "EPG Scan")
        val info = get(config.pluginPort, "/api/info", plugin = true, timeoutMs = 4000)
        return info.optString("version").ifBlank { "API ${info.optInt("api")}" }
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

    /** Nagrania z katalogu [dir] (puste = katalog domyślny dekodera). */
    fun movies(dir: String): Pair<String, List<Recording>> {
        validatePort(config.httpPort, "OpenWebif")
        val path = "/api/movielist" + if (dir.isNotBlank()) "?dirname=${encode(dir)}" else ""
        return parseMovieList(get(config.httpPort, path, timeoutMs = 30_000), dir)
    }

    /** Plik nagrania przez serwer OpenWebif (z przewijaniem — zakresy HTTP). */
    fun recordingUrl(path: String): String {
        validatePort(config.httpPort, "OpenWebif")
        return "http://${hostForUrl(config.host)}:${config.httpPort}/file?file=${encode(path)}"
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

    /** Timery nagrań i przełączeń zapisane bezpośrednio na dekoderze. */
    fun decoderTimers(): List<DecoderTimer> {
        validatePort(config.httpPort, "OpenWebif")
        return parseTimerList(get(config.httpPort, "/api/timerlist"))
    }

    fun timerDelete(timer: DecoderTimer): Pair<Boolean, String> {
        validatePort(config.httpPort, "OpenWebif")
        val eit = if (timer.eventId > 0) "&eit=${timer.eventId}" else ""
        val json = get(
            config.httpPort,
            "/api/timerdelete?sRef=${encode(timer.serviceRef)}&begin=${timer.begin}&end=${timer.end}$eit",
        )
        val message = json.optString("message").ifBlank { json.optString("error") }.ifBlank { "Brak potwierdzenia z dekodera" }
        return json.optBoolean("result") to message
    }

    /** Nagranie audycji na dekoderze (margines 2 min przed i 5 min po). */
    fun timerAddRecording(ref: String, title: String, begin: Long, end: Long, eventId: Int = 0): Pair<Boolean, String> {
        validatePort(config.httpPort, "OpenWebif")
        if (eventId > 0) {
            val byEvent = runCatching {
                val json = get(config.httpPort, "/api/timeraddbyeventid?sRef=${encode(ref)}&eventid=$eventId")
                json.optBoolean("result") to json.optString("message")
            }.getOrNull()
            if (byEvent?.first == true) return byEvent
        }
        val json = get(
            config.httpPort,
            "/api/timeradd?sRef=${encode(ref)}&begin=${begin - 120}&end=${end + 300}&name=${encode(title)}",
        )
        return json.optBoolean("result") to json.optString("message")
    }

    /** Pełne EPG jednego kanału (OpenWebif). */
    internal fun epgService(ref: String): List<EpgEvent> {
        validatePort(config.httpPort, "OpenWebif")
        return parseEpgEvents(get(config.httpPort, "/api/epgservice?sRef=${encode(ref)}", timeoutMs = 20_000))
    }

    /** Wyszukiwanie w EPG dekodera (tytuły audycji). */
    internal fun epgSearch(query: String): List<EpgEvent> {
        validatePort(config.httpPort, "OpenWebif")
        return parseEpgEvents(get(config.httpPort, "/api/epgsearch?search=${encode(query)}", timeoutMs = 20_000))
    }

    /** Informacje o paczce EPG wtyczki EPG Scan; null, gdy wtyczka jej nie udostępnia. */
    internal fun packInfo(): JSONObject? {
        validatePort(config.pluginPort, "EPG Scan")
        return try {
            get(config.pluginPort, "/api/pack/info", plugin = true, timeoutMs = 5000)
        } catch (e: IOException) {
            if (e.message?.startsWith("HTTP 404") == true) null else throw e
        }
    }

    /** Pobiera plik paczki do [target]; false, gdy go nie ma. */
    internal fun packFile(name: String, target: java.io.File): Boolean {
        val connection = URI("http://${hostForUrl(config.host)}:${config.pluginPort}/api/pack/$name")
            .toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 120_000
        if (config.pluginToken.isNotBlank()) connection.setRequestProperty("X-E2S-Token", config.pluginToken)
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return false
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode} z portu ${config.pluginPort}")
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            return true
        } finally {
            connection.disconnect()
        }
    }

    /** Stan synchronizacji z wtyczki EPG Scan: { common: {data}, device: {data} }. */
    internal fun syncState(deviceId: String): JSONObject {
        validatePort(config.pluginPort, "EPG Scan")
        return get(config.pluginPort, "/api/sync/state?id=${encode(deviceId)}", plugin = true, timeoutMs = 20_000)
    }

    /** Wysyła plik odbiornika; wtyczka scala go (nowszy wpis wygrywa) i uzupełnia część wspólną. */
    internal fun syncPush(deviceId: String, body: JSONObject): JSONObject {
        validatePort(config.pluginPort, "EPG Scan")
        val connection = URI("http://${hostForUrl(config.host)}:${config.pluginPort}/api/sync/push?id=${encode(deviceId)}")
            .toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 20_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (config.pluginToken.isNotBlank()) connection.setRequestProperty("X-E2S-Token", config.pluginToken)
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode} z portu ${config.pluginPort}")
            return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
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
