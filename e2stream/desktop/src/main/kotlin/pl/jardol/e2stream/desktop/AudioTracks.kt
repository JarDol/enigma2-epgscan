package pl.jardol.e2stream.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.MissingResourceException
import java.util.Properties

/** Ścieżka dźwięku VLC; w strumieniu TS [id] to PID ścieżki. */
internal data class AudioTrack(val id: Int, val name: String, val language: String, val codec: String)

internal object AudioTrackLabels {
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
            // ISO 639-2/B (używane w DVB) różni się od 639-2/T dla kilku języków.
            put("ger", "de"); put("fre", "fr"); put("cze", "cs"); put("slo", "sk"); put("dut", "nl")
        }
    }

    fun languageCode(value: String?): String {
        val language = value.orEmpty().trim().lowercase(Locale.ROOT)
            .substringBefore(',').substringBefore('-').substringBefore('_')
        return languageAliases[language] ?: language.takeIf { it.isNotBlank() } ?: "?"
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
}

/** Zapis wyboru: „id|język|kodek”. */
internal fun encodeAudioChoice(track: AudioTrack): String = "${track.id}|${track.language}|${track.codec}"

/** Zapamiętana ścieżka: najpierw po identyfikatorze (PID), potem po języku i kodeku. */
internal fun matchAudioChoice(tracks: List<AudioTrack>, saved: String): AudioTrack? {
    if (saved.isBlank()) return null
    val parts = saved.split("|")
    val savedId = parts.firstOrNull()?.toIntOrNull()
    val savedLanguage = parts.getOrElse(1) { "" }
    val savedCodec = parts.getOrElse(2) { "" }
    return tracks.firstOrNull { savedId != null && it.id == savedId }
        ?: tracks.firstOrNull {
            savedLanguage.isNotBlank() &&
                AudioTrackLabels.languageCode(it.language) == AudioTrackLabels.languageCode(savedLanguage) &&
                (savedCodec.isBlank() || AudioTrackLabels.codecName(it.codec) == AudioTrackLabels.codecName(savedCodec))
        }
}

/** Ścieżki dźwięku wybrane dla kanałów (osobno dla każdego dekodera) w %APPDATA%\E2Stream\audio.properties. */
internal class AudioChoices(private val path: Path = DesktopConfig.appDataDir().resolve("audio.properties")) {
    private val values = Properties().apply {
        runCatching { if (Files.exists(path)) Files.newInputStream(path).use(::load) }
    }

    private fun key(host: String, ref: String) = "${host.trim().lowercase(Locale.ROOT)}|${serviceKey(ref)}"

    @Synchronized
    fun get(host: String, ref: String): String = values.getProperty(key(host, ref)).orEmpty()

    @Synchronized
    fun set(host: String, ref: String, track: AudioTrack) {
        values.setProperty(key(host, ref), encodeAudioChoice(track))
        store()
    }

    @Synchronized
    fun count(): Int = values.size

    @Synchronized
    fun clear() {
        values.clear()
        store()
    }

    private fun store() {
        runCatching {
            Files.createDirectories(path.parent)
            Files.newOutputStream(path).use { values.store(it, "E2Stream audio tracks") }
        }
    }
}
