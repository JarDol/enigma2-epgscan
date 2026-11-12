package pl.jardol.e2stream

/** Media3 IDs for MPEG-TS tracks use "serviceId/PID"; return the elementary-stream PID. */
internal fun mpegTsPid(formatId: String?): Int? =
    formatId?.takeIf { '/' in it }?.substringAfterLast('/')?.toIntOrNull()?.takeIf { it in 0..8191 }

internal fun preferredAudioTrackIndex(trackLanguages: List<String>, preferredLanguages: List<String>): Int? =
    preferredLanguages.firstNotNullOfOrNull { preferred ->
        trackLanguages.indexOfFirst {
            AudioTrackLabels.languageCode(it) == AudioTrackLabels.languageCode(preferred)
        }.takeIf { it >= 0 }
    }
