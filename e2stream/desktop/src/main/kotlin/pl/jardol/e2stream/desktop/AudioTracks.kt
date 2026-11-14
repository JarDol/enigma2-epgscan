package pl.jardol.e2stream.desktop

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.MissingResourceException
import java.util.Properties

/** Ścieżka dźwięku VLC; w strumieniu TS [id] to PID ścieżki, [language] to kod języka (np. „pl”). */
internal data class AudioTrack(val id: Int, val name: String, val language: String, val codec: String)

internal object AudioTrackLabels {
    private val polish = Locale("pl")

    private val languageAliases by lazy {
        buildMap {
            for (code in Locale.getISOLanguages()) {
                val locale = Locale(code)
                put(code.lowercase(Locale.ROOT), code)
                try {
                    put(locale.isO3Language.lowercase(Locale.ROOT), code)
                } catch (_: MissingResourceException) {
                }
                locale.getDisplayLanguage(Locale.ENGLISH)
                    .takeIf { it.isNotBlank() }
                    ?.lowercase(Locale.ROOT)
                    ?.let { put(it, code) }
            }
            // Nazwy polskie („[Polski]” w opisie ścieżki VLC) — bez nadpisywania kodów.
            for (code in Locale.getISOLanguages()) {
                Locale(code).getDisplayLanguage(polish)
                    .takeIf { it.isNotBlank() }
                    ?.lowercase(polish)
                    ?.let { putIfAbsent(it, code) }
            }
            // ISO 639-2/B (używane w DVB) różni się od 639-2/T dla kilku języków.
            put("ger", "de"); put("fre", "fr"); put("cze", "cs"); put("slo", "sk"); put("dut", "nl")
        }
    }

    /** Kody DVB oznaczające ścieżkę oryginalną. */
    private val original = setOf("ory", "qaa", "org", "original", "oryginalna", "oryginalny", "oryginał")

    fun languageCode(value: String?): String {
        val language = value.orEmpty().trim().lowercase(Locale.ROOT)
            .substringBefore(',').substringBefore('-').substringBefore('_').trim()
        return languageAliases[language] ?: language.takeIf { it.isNotBlank() } ?: "?"
    }

    fun preferredLanguages(value: String): List<String> =
        value.split(',', ';', ' ').map { languageCode(it) }.filter { it != "?" }.distinct()

    /** Nazwa języka po polsku („Polski”, „Angielski”, „Oryginalna”); „?” gdy nieznany. */
    fun displayLanguage(value: String?): String {
        val code = languageCode(value)
        if (code == "?") return "?"
        if (code in original) return "Oryginalna"
        val name = runCatching { Locale(code).getDisplayLanguage(polish) }.getOrNull().orEmpty()
        return if (name.isBlank() || name.equals(code, ignoreCase = true)) code.uppercase(Locale.ROOT)
        else name.replaceFirstChar { it.titlecase(polish) }
    }

    fun codecName(codec: String?): String? {
        val value = codec.orEmpty().lowercase(Locale.ROOT).replace("-", "").trim()
        return when (value) {
            "audio/ac3", "ac3", "a52" -> "AC3"
            "audio/eac3", "audio/eac3joc", "eac3", "ec3" -> "E-AC3"
            "audio/mpeg", "audio/mpegl1", "audio/mpegl2", "mpga", "mp2a", "mp2", "mp3" -> "MPEG"
            "audio/aac", "aac", "mp4a" -> "AAC"
            else -> codec?.trim()?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
        }
    }

    fun label(language: String?, codec: String?, channels: Int): String = buildList {
        add(languageCode(language))
        codecName(codec)?.let(::add)
        if (channels > 0) add("${channels}ch")
    }.joinToString(" ")

    private val bracketLanguage = Regex("""\[([^\[\]]+)]\s*$""")
    private val genericName = Regex("""^(ścieżka|sciezka|track|audio|dźwięk)\s*\d*$""", RegexOption.IGNORE_CASE)

    /** Język z opisu VLC („Ścieżka 1 - [Polski]”). */
    fun languageFromDescription(description: String): String? =
        bracketLanguage.find(description)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    /** Opis VLC bez języka w nawiasie; null, gdy to tylko „Ścieżka N”. */
    fun extraDescription(description: String): String? =
        description.replace(bracketLanguage, "").trim().trimEnd('-', '–', ' ').trim()
            .takeIf { it.isNotBlank() && !genericName.matches(it) }

    /** Etykieta na liście: „Polski • MPEG 2ch • PID 132” (+ opis, np. audiodeskrypcja). */
    fun trackName(id: Int, language: String?, codec: String?, channels: Int, description: String): String {
        val parts = buildList {
            displayLanguage(language).takeIf { it != "?" }?.let(::add)
            extraDescription(description)?.let(::add)
            listOfNotNull(codecName(codec), if (channels > 0) "${channels}ch" else null)
                .takeIf { it.isNotEmpty() }?.joinToString(" ")?.let(::add)
        }
        return (parts.ifEmpty { listOf("Ścieżka") } + "PID $id").joinToString("  •  ")
    }
}

private val mojibakeMarks = Regex("[ĹÄĂÅÃâ][\\u0080-\\u02FF\\u2010-\\u203A]")

/** Tekst UTF-8 odczytany jako cp1250/cp1252 („ĹšcieĹĽka”) — przywraca „Ścieżka”. */
internal fun fixMojibake(text: String): String {
    if (!mojibakeMarks.containsMatchIn(text)) return text
    for (name in listOf("windows-1250", "windows-1252")) {
        val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
        val bytes = text.toByteArray(charset)
        if (String(bytes, charset) != text) continue
        val decoded = String(bytes, Charsets.UTF_8)
        if ('\uFFFD' !in decoded) return decoded
    }
    return text
}

/** Zapis wyboru zgodny z Androidem: „id|język|kodek”. */
internal fun encodeAudioChoice(track: AudioTrack): String = "${track.id}|${track.language}|${track.codec}"

/** PID z zapisu; ExoPlayer (Android) zapisuje identyfikator jako „1/132”. */
internal fun savedAudioId(saved: String): Int? = saved.substringBefore('|').substringAfterLast('/').trim().toIntOrNull()

/** Zapamiętana ścieżka: najpierw po identyfikatorze (PID), potem po języku i kodeku. */
internal fun matchAudioChoice(tracks: List<AudioTrack>, saved: String): AudioTrack? {
    if (saved.isBlank()) return null
    val parts = saved.split("|")
    val savedId = savedAudioId(saved)
    val savedLanguage = parts.getOrElse(1) { "" }
    val savedCodec = parts.getOrElse(2) { "" }
    return tracks.firstOrNull { savedId != null && it.id == savedId }
        ?: tracks.firstOrNull {
            savedLanguage.isNotBlank() && savedLanguage != "?" &&
                AudioTrackLabels.languageCode(it.language) == AudioTrackLabels.languageCode(savedLanguage) &&
                (savedCodec.isBlank() || AudioTrackLabels.codecName(it.codec) == AudioTrackLabels.codecName(savedCodec))
        }
}

/** Pierwsza ścieżka w preferowanym języku (kolejność z ustawień). */
internal fun preferredAudioTrack(tracks: List<AudioTrack>, preferred: List<String>): AudioTrack? =
    preferred.firstNotNullOfOrNull { language ->
        tracks.firstOrNull { AudioTrackLabels.languageCode(it.language) == language }
    }

/** Wpis synchronizacji: wartość i czas zmiany (ms). */
internal data class SyncEntry(val value: String, val time: Long)

/** Co przyjąć z dekodera i co do niego wysłać (klucze to [serviceKey]). */
internal data class AudioSyncPlan(val apply: Map<String, SyncEntry>, val push: Map<String, SyncEntry>)

/**
 * Zasady jak w Androidzie: plik tego odbiornika — nowszy wpis wygrywa; część wspólna — tylko kanały,
 * których brak lokalnie. Wpisy starsze niż [clearedAt] (lokalne „zapomnij”) są pomijane.
 */
internal fun planAudioSync(
    local: Map<String, SyncEntry>,
    device: Map<String, SyncEntry>,
    common: Map<String, SyncEntry>,
    clearedAt: Long = 0,
): AudioSyncPlan {
    val apply = linkedMapOf<String, SyncEntry>()
    for ((key, entry) in device) {
        if (entry.value.isBlank() || entry.time <= clearedAt) continue
        val current = local[key]
        if (current == null || entry.time > current.time) apply[key] = entry
    }
    for ((key, entry) in common) {
        if (entry.value.isBlank() || entry.time <= clearedAt) continue
        if (key !in local && key !in apply) apply[key] = entry
    }
    val merged = local + apply
    val push = merged.filter { (key, entry) -> device[key] != entry }
    return AudioSyncPlan(apply, push)
}

/**
 * Ścieżki dźwięku wybrane dla kanałów (osobno dla każdego dekodera) w %APPDATA%\E2Stream\audio.properties:
 * „host|KLUCZ” = „id|język|kodek”, „host|KLUCZ|t” = czas zmiany (do synchronizacji z dekoderem).
 */
internal class AudioChoices(private val path: Path = DesktopConfig.appDataDir().resolve("audio.properties")) {
    private val values = Properties().apply {
        runCatching { if (Files.exists(path)) Files.newInputStream(path).use(::load) }
        // Klucze „audio_1_0_…” bez dwukropków to stare dane z dekodera, których Android nie używa.
        stringPropertyNames().filter { !it.startsWith("#") && ':' !in it }.forEach(::remove)
    }

    private fun prefix(host: String) = "${host.trim().lowercase(Locale.ROOT)}|"
    private fun key(host: String, ref: String) = prefix(host) + serviceKey(ref)
    private fun clearedKey(host: String) = "#cleared|${host.trim().lowercase(Locale.ROOT)}"

    @Synchronized
    fun get(host: String, ref: String): String = values.getProperty(key(host, ref)).orEmpty()

    @Synchronized
    fun set(host: String, ref: String, track: AudioTrack, now: Long = System.currentTimeMillis()) {
        val key = key(host, ref)
        values.setProperty(key, encodeAudioChoice(track))
        values.setProperty("$key|t", now.toString())
        store()
    }

    /** Wszystkie wybory dla dekodera [host]: klucz kanału → wpis. */
    @Synchronized
    fun entries(host: String): Map<String, SyncEntry> {
        val prefix = prefix(host)
        return values.stringPropertyNames()
            .filter { it.startsWith(prefix) && !it.endsWith("|t") }
            .associate { name ->
                name.removePrefix(prefix) to SyncEntry(
                    values.getProperty(name).orEmpty(),
                    values.getProperty("$name|t")?.toLongOrNull() ?: 0,
                )
            }
    }

    /** Wpisy z dekodera (bez zmiany czasu). */
    @Synchronized
    fun putAll(host: String, entries: Map<String, SyncEntry>) {
        if (entries.isEmpty()) return
        val prefix = prefix(host)
        for ((key, entry) in entries) {
            values.setProperty(prefix + key, entry.value)
            values.setProperty("$prefix$key|t", entry.time.toString())
        }
        store()
    }

    @Synchronized
    fun clearedAt(host: String): Long = values.getProperty(clearedKey(host))?.toLongOrNull() ?: 0

    @Synchronized
    fun count(host: String): Int = entries(host).size

    @Synchronized
    fun clear(host: String, now: Long = System.currentTimeMillis()) {
        val prefix = prefix(host)
        values.stringPropertyNames().filter { it.startsWith(prefix) }.forEach(values::remove)
        // Synchronizacja nie przywróci wyborów zapisanych na dekoderze przed wyczyszczeniem.
        values.setProperty(clearedKey(host), now.toString())
        store()
    }

    private fun store() {
        runCatching {
            Files.createDirectories(path.parent)
            Files.newOutputStream(path).use { values.store(it, "E2Stream audio tracks") }
        }
    }
}

/** Identyfikator tego komputera w synchronizacji (plik device-<id>.json na dekoderze). */
internal fun syncDeviceId(): String =
    "win-" + (System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "pc")
        .replace(Regex("[^A-Za-z0-9_-]"), "_")

private fun audioEntries(data: org.json.JSONObject?): Map<String, SyncEntry> {
    if (data == null) return emptyMap()
    return data.keys().asSequence().filter { it.startsWith("audio_") && ':' in it }.mapNotNull { key ->
        val entry = data.optJSONObject(key) ?: return@mapNotNull null
        val value = entry.opt("v") as? String ?: return@mapNotNull null
        serviceKey(key.removePrefix("audio_")) to SyncEntry(value, entry.optLong("t"))
    }.toMap()
}

/**
 * Ścieżki dźwięku z dekoderem przez wtyczkę EPG Scan — te same klucze „audio_<ref>” co Android,
 * więc wybór z telewizora działa na komputerze i odwrotnie. Zwraca krótki opis wyniku.
 */
internal fun syncAudioChoices(config: DesktopConfig, choices: AudioChoices, appVersion: String): String {
    val client = DecoderClient(config)
    val deviceId = syncDeviceId()
    val state = client.syncState(deviceId)
    val device = audioEntries(state.optJSONObject("device")?.optJSONObject("data"))
    val common = audioEntries(state.optJSONObject("common")?.optJSONObject("data"))
    val plan = planAudioSync(choices.entries(config.host), device, common, choices.clearedAt(config.host))
    choices.putAll(config.host, plan.apply)
    if (plan.push.isNotEmpty() || state.optJSONObject("device") == null) {
        val data = org.json.JSONObject()
        for ((key, entry) in plan.push) {
            data.put("audio_$key", org.json.JSONObject().put("v", entry.value).put("t", entry.time))
        }
        val info = org.json.JSONObject()
            .put("id", deviceId)
            .put("name", System.getenv("COMPUTERNAME") ?: deviceId)
            .put("model", "Windows ${System.getProperty("os.version").orEmpty()}".trim())
            .put("app", "E2Stream Windows $appVersion")
            .put("updated", System.currentTimeMillis())
        client.syncPush(deviceId, org.json.JSONObject().put("device", org.json.JSONObject().put("device", info).put("data", data)))
    }
    return "przyjęto ${plan.apply.size}, wysłano ${plan.push.size}"
}