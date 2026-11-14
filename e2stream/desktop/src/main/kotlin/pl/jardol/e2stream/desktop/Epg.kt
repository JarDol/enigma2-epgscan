package pl.jardol.e2stream.desktop

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/** EPG kanałów zapisane lokalnie: jeden plik JSON na kanał w %APPDATA%\E2Stream\epg. */
internal class EpgStore(private val dir: File = DesktopConfig.appDataDir().resolve("epg").toFile()) {
    private fun file(ref: String): File =
        File(dir, serviceKey(ref).replace(Regex("[^A-Z0-9]"), "_") + ".json")

    fun write(ref: String, events: List<EpgEvent>, at: Long = System.currentTimeMillis()) {
        dir.mkdirs()
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("i", event.id).put("b", event.begin).put("d", event.duration)
                    .put("t", event.title).put("s", event.shortDesc).put("l", event.longDesc)
                    .put("r", event.sref).put("n", event.sname),
            )
        }
        val target = file(ref)
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(JSONObject().put("at", at).put("e", array).toString(), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    /** Czas zapisu (ms) i audycje; null, gdy brak pliku. */
    fun read(ref: String): Pair<Long, List<EpgEvent>>? {
        val source = file(ref)
        if (!source.isFile) return null
        return runCatching {
            val json = JSONObject(source.readText(Charsets.UTF_8))
            val array = json.optJSONArray("e") ?: JSONArray()
            json.optLong("at") to (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                EpgEvent(
                    id = item.optInt("i"),
                    begin = item.optLong("b"),
                    duration = item.optLong("d"),
                    title = item.optString("t"),
                    shortDesc = item.optString("s"),
                    longDesc = item.optString("l"),
                    sref = item.optString("r").ifBlank { ref },
                    sname = item.optString("n"),
                )
            }
        }.getOrNull()
    }

    fun count(): Int = dir.listFiles { file -> file.name.endsWith(".json") }?.size ?: 0

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

/** Dostęp do EPG: pamięć (10 min) → plik lokalny (do 26 h) → dekoder. */
internal class EpgRepository(val store: EpgStore = EpgStore()) {
    private val memory = ConcurrentHashMap<String, Pair<Long, List<EpgEvent>>>()
    /** Audycje z EIT strumienia (teraz/następne) — ważniejsze od EPG z dekodera, do zamknięcia aplikacji. */
    private val eitOverlay = ConcurrentHashMap<String, List<EpgEvent>>()

    fun invalidate() = memory.clear()

    private fun withEit(ref: String, events: List<EpgEvent>): List<EpgEvent> {
        val eit = eitOverlay[serviceKey(ref)] ?: return events
        return EitMerge.merge(events, eit, { it.begin }, { it.end }, { it.id })
    }

    /** Bieżąca i następna audycja z danych lokalnych (z EIT), bez sieci. */
    fun nowNext(ref: String, now: Long = System.currentTimeMillis() / 1000): Pair<EpgEvent?, EpgEvent?> {
        val events = cached(ref) ?: eitOverlay[serviceKey(ref)] ?: return null to null
        val current = events.firstOrNull { it.begin <= now && now < it.end }
        val next = events.firstOrNull { it.begin >= (current?.end ?: now) }
        return current to next
    }

    /**
     * EIT teraz/następne kanału: nakładka na EPG, a przy [persist] także zapis w EPG kanału (do następnej
     * aktualizacji z dekodera). Zwraca różnice względem dotychczasowego EPG (stara audycja albo null, nowa).
     */
    fun applyEit(ref: String, name: String, events: List<EitEvent>, persist: Boolean): List<Pair<EpgEvent?, EpgEvent>> {
        if (events.isEmpty()) return emptyList()
        val key = serviceKey(ref)
        val eit = events.map { EpgEvent(it.id, it.begin, it.duration, it.title, it.shortDesc, it.longDesc, ref, name) }
        val base = rawCached(ref) ?: store.read(ref)?.second ?: emptyList()
        val diff = EitMerge.changes(base, eit, { it.begin }, { it.end }, { it.title })
        eitOverlay[key] = eit
        if (persist && diff.isNotEmpty()) {
            runCatching {
                val at = store.read(ref)?.first ?: System.currentTimeMillis()
                val merged = EitMerge.merge(base, eit, { it.begin }, { it.end }, { it.id })
                store.write(ref, merged, at)
                memory[key] = System.currentTimeMillis() to merged
            }.onFailure { AppLog.i("EIT: zapis EPG kanału $name: ${it.message}") }
        }
        return diff
    }

    /** Tylko dane lokalne, bez sieci. */
    fun cached(ref: String): List<EpgEvent>? = rawCached(ref)?.let { withEit(ref, it) }

    private fun rawCached(ref: String): List<EpgEvent>? {
        val key = serviceKey(ref)
        val nowMs = System.currentTimeMillis()
        memory[key]?.takeIf { nowMs - it.first < MEMORY_MS }?.let { return it.second }
        val (at, events) = store.read(ref) ?: return null
        val nowS = nowMs / 1000
        if (nowMs - at > STORE_MS || events.none { it.end > nowS }) return null
        memory[key] = nowMs to events
        return events
    }

    fun load(client: DecoderClient, ref: String, force: Boolean = false): List<EpgEvent> {
        if (!force) cached(ref)?.let { return it }
        val key = serviceKey(ref)
        return try {
            val events = client.epgService(ref)
            if (events.isNotEmpty()) store.write(ref, events)
            memory[key] = System.currentTimeMillis() to events
            withEit(ref, events)
        } catch (e: Exception) {
            // Dekoder niedostępny — lepsze starsze dane niż żadne.
            store.read(ref)?.second?.takeIf { it.isNotEmpty() }?.let { withEit(ref, it) }
                ?: eitOverlay[key]
                ?: throw e
        }
    }

    private companion object {
        const val MEMORY_MS = 10 * 60_000L
        const val STORE_MS = 26 * 3_600_000L
    }
}

internal object EpgSync {
    data class Result(val message: String, val packIdentity: String? = null)

    /** Ostatnia zaplanowana godzina pobierania EPG (ms) nie późniejsza niż [now]. */
    fun lastScheduled(hour: String, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val time = runCatching { LocalTime.parse(hour) }.getOrDefault(LocalTime.of(5, 0))
        val today = now.toLocalDate().atTime(time)
        return if (today.isAfter(now)) today.minusDays(1) else today
    }

    /** Czy należy pobrać EPG w tle: z EPG Scan przy starcie lub raz dziennie, bez wtyczki o ustalonej godzinie. */
    fun isDue(
        config: DesktopConfig,
        usesPlugin: Boolean,
        startupDone: Boolean,
        now: LocalDateTime = LocalDateTime.now(),
    ): Boolean {
        fun millis(time: LocalDateTime) = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return when {
            usesPlugin && config.epgUpdateMode == DesktopConfig.EPG_UPDATE_STARTUP -> !startupDone
            usesPlugin -> config.lastEpgFetch < millis(now.toLocalDate().atStartOfDay())
            else -> config.epgDaily && config.lastEpgFetch < millis(lastScheduled(config.epgHour, now))
        }
    }

    fun run(
        config: DesktopConfig,
        usesPlugin: Boolean,
        store: EpgStore,
        channels: () -> List<Channel>,
        manual: Boolean,
        progress: (String) -> Unit,
    ): Result {
        val client = DecoderClient(config)
        if (usesPlugin) {
            val pack = runCatching { fromPack(config, client, store, manual, progress) }
                .onFailure { AppLog.i("epg: paczka EPG Scan: ${it.message}") }
                .getOrNull()
            if (pack != null) return pack
        }
        return perChannel(client, store, channels(), config.epgDays, progress)
    }

    private fun fromPack(
        config: DesktopConfig,
        client: DecoderClient,
        store: EpgStore,
        manual: Boolean,
        progress: (String) -> Unit,
    ): Result? {
        progress("EPG: sprawdzanie paczki EPG Scan…")
        val info = client.packInfo() ?: return null
        if (info.optBoolean("building")) throw IOException("EPG Scan przygotowuje paczkę")
        val at = info.optLong("at")
        val size = info.optLong("epg_size")
        if (at <= 0 || size <= 0) return null
        val sha = info.optString("epg_sha256")
        val identity = "${config.host.trim().lowercase(Locale.ROOT)}:${config.pluginPort}/$at:$size:$sha"
        if (!manual && identity == config.epgPackIdentity && store.count() > 0) {
            return Result("paczka EPG Scan bez zmian (${formatStamp(at)})", identity)
        }
        progress("EPG: pobieranie paczki EPG Scan…")
        val temp = File.createTempFile("e2stream-epg", ".json.gz")
        try {
            if (!client.packFile("epg.json.gz", temp)) return null
            if (sha.isNotBlank() && !sha256(temp).equals(sha, ignoreCase = true)) {
                throw IOException("Paczka EPG zmieniła się podczas pobierania lub jest uszkodzona")
            }
            progress("EPG: zapisywanie…")
            val json = GZIPInputStream(temp.inputStream().buffered(64 * 1024)).bufferedReader(Charsets.UTF_8).use {
                JSONObject(JSONTokener(it))
            }
            val channelsJson = json.optJSONObject("ch")
            if (channelsJson == null || json.optLong("at") != at) {
                throw IOException("Paczka EPG nie odpowiada informacjom z dekodera")
            }
            val nowS = System.currentTimeMillis() / 1000
            val untilS = nowS + config.epgDays.coerceIn(1, 14) * 86_400L
            var channelCount = 0
            var eventCount = 0
            for (key in channelsJson.keySet()) {
                val channel = channelsJson.optJSONObject(key) ?: continue
                val name = channel.optString("n")
                val rows = channel.optJSONArray("e") ?: JSONArray()
                val events = (0 until rows.length()).mapNotNull { index ->
                    val row = rows.optJSONArray(index) ?: return@mapNotNull null
                    val begin = row.optLong(1)
                    val duration = row.optLong(2)
                    if (begin + duration <= nowS || begin >= untilS) return@mapNotNull null
                    EpgEvent(row.optInt(0), begin, duration, row.optString(3), row.optString(4), row.optString(5), key, name)
                }.sortedBy { it.begin }
                store.write(key, events)
                channelCount++
                eventCount += events.size
            }
            return Result("paczka EPG Scan: $channelCount kanałów, $eventCount audycji", identity)
        } finally {
            temp.delete()
        }
    }

    private fun perChannel(
        client: DecoderClient,
        store: EpgStore,
        channels: List<Channel>,
        days: Int,
        progress: (String) -> Unit,
    ): Result {
        val unique = channels.filter { it.ref.isNotBlank() }.distinctBy { serviceKey(it.ref) }
        if (unique.isEmpty()) return Result("brak kanałów do pobrania EPG")
        val done = AtomicInteger()
        val events = AtomicInteger()
        val failed = AtomicInteger()
        val untilS = System.currentTimeMillis() / 1000 + days.coerceIn(1, 14) * 86_400L
        val pool = Executors.newFixedThreadPool(2)
        try {
            unique.forEach { channel ->
                pool.execute {
                    try {
                        val list = client.epgService(channel.ref).filter { it.begin < untilS }
                        if (list.isNotEmpty()) store.write(channel.ref, list)
                        events.addAndGet(list.size)
                    } catch (_: Exception) {
                        failed.incrementAndGet()
                    }
                    progress("EPG: ${done.incrementAndGet()}/${unique.size} kanałów…")
                }
            }
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.MINUTES)
        } finally {
            pool.shutdownNow()
        }
        val errors = if (failed.get() > 0) ", błędy: ${failed.get()}" else ""
        return Result("OpenWebif: ${unique.size} kanałów, ${events.get()} audycji$errors")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun formatStamp(seconds: Long): String =
        DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds))
}

internal object EpgTime {
    private val zone get() = ZoneId.systemDefault()
    private val day = DateTimeFormatter.ofPattern("EEE dd.MM", Locale.forLanguageTag("pl-PL"))
    private val time = DateTimeFormatter.ofPattern("HH:mm")

    private fun date(seconds: Long): LocalDate = Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate()

    fun day(seconds: Long): String = day.format(Instant.ofEpochSecond(seconds).atZone(zone))

    fun clock(seconds: Long): String = time.format(Instant.ofEpochSecond(seconds).atZone(zone))

    /** „12:00–13:30” (z dniem zakończenia, gdy audycja przechodzi przez północ). */
    fun range(begin: Long, end: Long): String {
        val endDay = if (date(begin) == date(end)) "" else "${day(end)} "
        return "${clock(begin)}–$endDay${clock(end)}"
    }

    fun dayAndRange(begin: Long, end: Long): String = "${day(begin)}  ${range(begin, end)}"

    fun sameDay(a: Long, b: Long): Boolean = date(a) == date(b)

    /** Postęp trwającej audycji: „trwa (40%, zostało 35 min)”; null, gdy nie trwa. */
    fun progress(event: EpgEvent, now: Long): String? {
        if (now < event.begin || now >= event.end || event.duration <= 0) return null
        val percent = ((now - event.begin) * 100 / event.duration).toInt()
        val left = ((event.end - now + 59) / 60).toInt()
        return "trwa ($percent%, zostało $left min)"
    }
}

/** Opis audycji do okna szczegółów: krótki i długi bez powtórzeń. */
internal fun epgDescription(event: EpgEvent): String {
    val short = event.shortDesc.trim()
    val long = event.longDesc.trim()
    return when {
        short.isBlank() -> long
        long.isBlank() || long.startsWith(short) -> long.ifBlank { short }
        short.contains(long) -> short
        else -> "$short\n\n$long"
    }
}

internal fun epgPreview(event: EpgEvent): String =
    event.shortDesc.ifBlank { event.longDesc }.trim().replace(Regex("\\s+"), " ")
