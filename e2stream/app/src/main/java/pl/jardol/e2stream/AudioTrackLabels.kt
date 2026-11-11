package pl.jardol.e2stream

import java.util.Locale
import java.util.MissingResourceException

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
        }
    }

    fun languageCode(value: String?): String {
        val language = value.orEmpty().trim().lowercase(Locale.ROOT)
            .substringBefore(',').substringBefore('-').substringBefore('_')
        return languageAliases[language] ?: language.takeIf { it.isNotBlank() } ?: "?"
    }

    fun preferredLanguages(value: String): List<String> =
        value.split(",").map { languageCode(it) }.filter { it != "?" }.distinct()

    fun preferredVlcLanguages(value: String): List<String> =
        preferredLanguages(value).map { code ->
            try {
                Locale(code).isO3Language.lowercase(Locale.ROOT)
            } catch (_: MissingResourceException) {
                code
            }
        }.distinct()

    fun label(language: String?, codec: String?, channels: Int): String = buildList {
        add(languageCode(language))
        codecName(codec)?.let(::add)
        if (channels > 0) add("${channels}ch")
    }.joinToString(" ")

    fun codecName(codec: String?): String? {
        val value = codec.orEmpty().lowercase(Locale.ROOT).replace("-", "").trim()
        return when (value) {
            "audio/ac3", "ac3" -> "AC3"
            "audio/eac3", "audio/eac3joc", "eac3", "ec3" -> "E-AC3"
            "audio/mpeg", "audio/mpeg-l1", "audio/mpeg-l2", "audio/mpegl1", "audio/mpegl2",
            "mpga", "mp2a", "mp2", "mp3" -> "MPEG"
            "audio/aac", "aac", "mp4a" -> "AAC"
            else -> codec?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
        }
    }
}
