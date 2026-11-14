package pl.jardol.e2stream.desktop

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pasek odtwarzania nagrania lub timeshiftu w dolnym panelu odtwarzacza. */
internal data class PlaybackBar(
    val title: String,
    val line: String,
    val start: String,
    val end: String,
    val progress: Float,
    val hint: String,
)

private val wallClock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
private val recordingDate = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

/** Godzina odpowiadająca chwili [wall] zegara monotonicznego. */
private fun clockAt(wall: Long, nowWall: Long): String =
    wallClock.format(Instant.ofEpochMilli(System.currentTimeMillis() - (nowWall - wall)))

internal fun recordingDetails(recording: Recording): String = listOfNotNull(
    recording.service.takeIf(String::isNotBlank),
    recording.begin.takeIf { it > 0 }?.let { recordingDate.format(Instant.ofEpochSecond(it)) },
    recording.lengthSec.takeIf { it > 0 }?.let { formatDuration(it * 1000) },
).joinToString(" • ")

@Suppress("UNUSED_PARAMETER")
internal fun playbackBarFor(
    visible: Boolean,
    tick: Int,
    session: VlcSession?,
    recording: Recording?,
    timeshift: TimeshiftSession?,
    pendingTarget: Long?,
    channelName: String?,
    config: DesktopConfig,
): PlaybackBar? {
    if (!visible || session == null) return null
    val paused = session.isPaused
    if (recording != null) {
        val position = session.time().coerceAtLeast(0)
        val length = session.length().takeIf { it > 0 } ?: (recording.lengthSec * 1000)
        return PlaybackBar(
            title = recording.title,
            line = listOf(if (paused) "Pauza" else "", recordingDetails(recording)).filter(String::isNotBlank).joinToString(" • "),
            start = formatDuration(position),
            end = if (length > 0) formatDuration(length) else "--:--",
            progress = if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f,
            hint = "◀ −10 s   ▶ +30 s   ▲ +5 min   ▼ −5 min   OK pauza   Stop/Wstecz — lista nagrań",
        )
    }
    if (timeshift == null) return null
    val now = monotonicMs()
    val oldest = timeshift.buffer.oldestWall
    val shown = (pendingTarget ?: timeshift.shownWall(session.time())).coerceIn(oldest, now)
    val behind = now - shown
    val span = (now - oldest).coerceAtLeast(1)
    val state = when {
        paused -> "Pauza • −${formatDuration(behind)}"
        pendingTarget != null -> "Przewijanie do ${clockAt(shown, now)} (−${formatDuration(behind)})"
        behind <= LIVE_MARGIN_MS -> "Na żywo"
        else -> "${clockAt(shown, now)} • −${formatDuration(behind)} za transmisją"
    }
    return PlaybackBar(
        title = "Timeshift — ${channelName.orEmpty()}",
        line = "$state • bufor ${formatDuration(span)} z ${config.tsMin} min",
        start = clockAt(oldest, now),
        end = clockAt(now, now),
        progress = ((shown - oldest).toFloat() / span).coerceIn(0f, 1f),
        hint = "◀ ▶ ±${config.tsStepSec} s   ▲ ▼ ±${config.tsStepMin} min   OK pauza   Stop — na żywo   Wstecz — ukryj",
    )
}
