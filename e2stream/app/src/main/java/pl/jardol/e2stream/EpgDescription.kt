package pl.jardol.e2stream

internal fun epgDescriptionPreview(shortDesc: String, longDesc: String): String =
    shortDesc.ifBlank { longDesc }.trim().replace(Regex("\\s+"), " ")
