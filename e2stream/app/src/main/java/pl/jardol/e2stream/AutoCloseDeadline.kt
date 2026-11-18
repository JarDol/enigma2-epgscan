package pl.jardol.e2stream

internal enum class AutoCloseRecordingKind {
    PROGRAM,
    BUFFER
}

internal data class AutoCloseRecording(
    val kind: AutoCloseRecordingKind,
    val endsWall: Long
)

internal object AutoCloseDeadline {
    const val LOG_INTERVAL_MS = 15 * 60 * 1000L

    fun at(
        idleDeadlineElapsed: Long,
        recordings: List<AutoCloseRecording>,
        nowElapsed: Long,
        nowWall: Long
    ): Long {
        val latestProgramEndElapsed = recordings
            .asSequence()
            .filter { it.kind == AutoCloseRecordingKind.PROGRAM }
            .maxOfOrNull { recording ->
                nowElapsed + (recording.endsWall - nowWall).coerceAtLeast(0)
            } ?: idleDeadlineElapsed
        return maxOf(idleDeadlineElapsed, latestProgramEndElapsed)
    }
}
