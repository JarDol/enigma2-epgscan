package pl.jardol.e2stream

import java.util.Locale

internal object SubtitleTracks {
    private val teletextPage = Regex("""^\s*teletext\s*(?:[-–\[]|$)""", RegexOption.IGNORE_CASE)
    private val bracketLanguage = Regex("""\[([^\[\]]+)]\s*$""")
    private val genericName = Regex("""^(track|subtitle(?:s)?|napisy|ścieżka(?: napisów)?)\s*\d*$""", RegexOption.IGNORE_CASE)
    private val technicalName = Regex("""^(?:application/|text/|audio/|video/|PID\b).*""", RegexOption.IGNORE_CASE)

    fun isTeletextPage(description: String?): Boolean =
        description != null && teletextPage.containsMatchIn(description)

    fun displayLabels(
        tracks: List<Pair<String?, String?>>, locale: Locale,
        descriptionLabel: (String) -> String = { it }, unknownLabel: (Int) -> String,
    ): List<String> {
        val labels = tracks.mapIndexed { index, (language, description) ->
            val code = AudioTrackLabels.displayLanguageCode(
                language?.takeUnless { it.isBlank() || it == "und" }, description.orEmpty())
            val name = code.takeUnless { it in setOf("?", "und", "zxx", "mul") }
                ?.let { Locale(it).getDisplayLanguage(locale) }
                ?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.titlecase(locale) }
            val extra = description.orEmpty().replace(bracketLanguage, "").trim().trimEnd('-', '–', ' ').trim()
                .takeIf { it.isNotBlank() && !genericName.matches(it) && !technicalName.matches(it) &&
                    AudioTrackLabels.languageCode(it) != code }
                ?.let(descriptionLabel)
            listOfNotNull(name, extra).joinToString("  •  ").ifBlank { unknownLabel(index + 1) }
        }
        val counts = labels.groupingBy { it }.eachCount()
        val seen = mutableMapOf<String, Int>()
        return labels.map { label ->
            if (counts.getValue(label) == 1) label else {
                val number = (seen[label] ?: 0) + 1
                seen[label] = number
                "$label ($number)"
            }
        }
    }

    fun vlcLabels(
        tracks: List<Pair<Int, String>>, locale: Locale, offLabel: String,
        descriptionLabel: (String) -> String = { it }, unknownLabel: (Int) -> String,
    ): List<String> {
        val labels = displayLabels(tracks.filter { it.first >= 0 }.map { null to it.second },
            locale, descriptionLabel, unknownLabel).iterator()
        return tracks.map { if (it.first < 0) offLabel else labels.next() }
    }
}
