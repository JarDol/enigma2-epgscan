package pl.jardol.e2stream

internal object SubtitleTracks {
    private val teletextPage = Regex("""^\s*teletext\s*(?:[-–\[]|$)""", RegexOption.IGNORE_CASE)

    fun isTeletextPage(description: String?): Boolean =
        description != null && teletextPage.containsMatchIn(description)
}
