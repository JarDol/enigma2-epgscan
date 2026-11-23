package pl.jardol.e2stream

import java.util.Locale

internal object TrackDescriptions {
    private val labels = mapOf(
        "dvb subtitles" to R.string.track_subtitles_dvb,
        "dvb subtitle" to R.string.track_subtitles_dvb,
        "teletext subtitles" to R.string.track_subtitles_teletext,
        "teletext subtitle" to R.string.track_subtitles_teletext,
        "subtitles" to R.string.epg_subtitles,
        "subtitle" to R.string.epg_subtitles,
        "closed captions" to R.string.track_subtitles_cc,
        "closed caption" to R.string.track_subtitles_cc,
        "cc" to R.string.track_subtitles_cc,
        "audio description for the visually impaired" to R.string.audio_description,
        "audio description for visually impaired" to R.string.audio_description,
        "audio description" to R.string.audio_description,
        "for the visually impaired" to R.string.audio_description,
        "visually impaired" to R.string.audio_description,
        "ad" to R.string.audio_description,
        "for the hearing impaired" to R.string.track_hearing_impaired,
        "hearing impaired" to R.string.track_hearing_impaired,
        "hearing-impaired" to R.string.track_hearing_impaired,
        "sdh" to R.string.track_hearing_impaired,
        "forced subtitles" to R.string.track_forced_subtitles,
        "forced subtitle" to R.string.track_forced_subtitles,
        "forced only" to R.string.track_forced_subtitles,
        "forced" to R.string.track_forced_subtitles,
        "audio commentary" to R.string.track_commentary,
        "commentary" to R.string.track_commentary,
        "original" to R.string.audio_original,
    )
    private val whitespace = Regex("""\s+""")
    private val separator = Regex("""\s*[\u2022;/]\s*|\s+[-\u2013]\s+""")
    private val qualifier = Regex("""^(.+?)\s*\(([^()]*)\)$""")

    fun localize(description: String, text: (Int) -> String): String {
        fun known(value: String): String? =
            labels[value.trim().replace(whitespace, " ").lowercase(Locale.ROOT)]?.let(text)

        fun part(value: String): String {
            known(value)?.let { return it }
            val match = qualifier.matchEntire(value.trim()) ?: return value
            val base = match.groupValues[1].trim()
            val detail = match.groupValues[2].trim()
            val translatedBase = known(base)
            val translatedDetail = known(detail)
            return if (translatedBase != null || translatedDetail != null) {
                "${translatedBase ?: base} (${translatedDetail ?: detail})"
            } else value
        }

        return buildString {
            var start = 0
            for (match in separator.findAll(description)) {
                append(part(description.substring(start, match.range.first)))
                append(match.value)
                start = match.range.last + 1
            }
            append(part(description.substring(start)))
        }
    }
}
