package pl.jardol.e2stream

/** Media3 IDs for MPEG-TS tracks use "serviceId/PID"; return the elementary-stream PID. */
internal fun mpegTsPid(formatId: String?): Int? =
    formatId?.takeIf { '/' in it }?.substringAfterLast('/')?.toIntOrNull()?.takeIf { it in 0..8191 }

internal data class AudioCandidate(val id: String, val language: String?, val codec: String?, val selected: Boolean)

internal data class AudioChoice(val index: Int, val reason: String)

internal class AudioSelectionWindow {
    private var deadline: Long? = null

    fun check(
        now: Long,
        tracks: List<AudioCandidate>,
        saved: String,
        preferredLanguages: List<String>,
        preferAc3: Boolean,
    ): Pair<AudioChoice?, Long> {
        require(tracks.isNotEmpty())
        val until = deadline ?: (now + 2000).also { deadline = it }
        val remaining = (until - now).coerceAtLeast(0)
        return chooseAudioTrack(tracks, saved, preferredLanguages, remaining == 0L, preferAc3) to remaining
    }
}

internal fun chooseAudioTrack(
    tracks: List<AudioCandidate>,
    saved: String,
    preferredLanguages: List<String>,
    finalCheck: Boolean,
    preferAc3: Boolean,
): AudioChoice? {
    val parts = saved.split("|")
    val id = parts.getOrElse(0) { "" }
    val language = parts.getOrElse(1) { "" }
    val codec = parts.getOrElse(2) { "" }
    if (saved.isNotEmpty()) {
        val savedPid = mpegTsPid(id) ?: id.toIntOrNull()
        val byId = tracks.indexOfFirst {
            it.id == id && id.isNotEmpty() ||
                savedPid != null && (mpegTsPid(it.id) ?: it.id.toIntOrNull()) == savedPid
        }
        val byFormat = tracks.indexOfFirst {
            language.isNotBlank() &&
                AudioTrackLabels.languageCode(it.language) == AudioTrackLabels.languageCode(language) &&
                (codec.isBlank() || AudioTrackLabels.codecName(it.codec) == AudioTrackLabels.codecName(codec))
        }
        val match = if (byId >= 0) byId else byFormat
        if (match >= 0) return AudioChoice(match, "zapamiętana")
        if (!finalCheck) return null
    }
    for ((rank, preferred) in preferredLanguages.withIndex()) {
        if (rank > 0 && !finalCheck) return null
        val matching = tracks.indices.filter {
            AudioTrackLabels.languageCode(tracks[it].language) == AudioTrackLabels.languageCode(preferred)
        }
        if (matching.isNotEmpty()) {
            val selected = matching.firstOrNull { tracks[it].selected }
            val dolby = if (preferAc3) matching.firstOrNull {
                AudioTrackLabels.codecName(tracks[it].codec) in setOf("E-AC3", "AC3")
            } else null
            return AudioChoice(selected ?: dolby ?: matching.first(), "preferowana")
        }
    }
    return null
}
