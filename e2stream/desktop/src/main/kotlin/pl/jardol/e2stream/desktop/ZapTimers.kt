package pl.jardol.e2stream.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Przełączenia wg harmonogramu („o początku audycji przełącz na kanał”) — zapisywane tylko na tym
 * komputerze, działają, gdy E2Stream jest uruchomiony. Wpisy dodaje się z opisu audycji w EPG.
 */
internal object ZapOutcome {
    const val SWITCHED = "switched"
    const val RECORDED = "recorded"
    const val RECORD_FAILED = "record_failed"
    const val SKIPPED = "skipped"
    const val REPLACED = "replaced"

    fun label(outcome: String): String = when (outcome) {
        SWITCHED -> "Przełączono"
        RECORDED -> "Nagrano na dekoderze"
        RECORD_FAILED -> "Nagranie nieudane"
        SKIPPED -> "Pominięto"
        REPLACED -> "Zastąpiono innym przełączeniem"
        else -> outcome
    }
}

internal data class ZapTimer(
    val ref: String,
    val channel: String,
    val title: String,
    val begin: Long,
    val end: Long,
    val eventId: Int = 0,
) {
    val key: String get() = "${serviceKey(ref)}@$begin"
}

internal data class ZapHistory(val timer: ZapTimer, val outcome: String, val completedAt: Long)

private val timerDay = java.time.format.DateTimeFormatter.ofPattern("EEE dd.MM  HH:mm", java.util.Locale.forLanguageTag("pl"))
    .withZone(java.time.ZoneId.systemDefault())
private val timerHour = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())

internal fun zapHistoryLabel(days: Int): String = if (days <= 0) "nie czyść" else "$days dniach"

/** „pt 05.06  20:00–21:45” */
internal fun formatTimerRange(begin: Long, end: Long): String {
    val start = java.time.Instant.ofEpochSecond(begin)
    return if (end > begin) "${timerDay.format(start)}–${timerHour.format(java.time.Instant.ofEpochSecond(end))}"
    else timerDay.format(start)
}

internal class ZapTimerStore(
    private val path: Path = DesktopConfig.appDataDir().resolve("zaptimers.json"),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private data class State(val timers: List<ZapTimer>, val history: List<ZapHistory>)

    @Synchronized
    private fun read(): State {
        val json = runCatching { JSONObject(Files.readString(path)) }.getOrNull() ?: return State(emptyList(), emptyList())
        fun timerOf(o: JSONObject) = ZapTimer(
            o.optString("ref"), o.optString("ch"), o.optString("t"), o.optLong("b"), o.optLong("e"), o.optInt("id"),
        )
        val timers = json.optJSONArray("list")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::timerOf) }
        }.orEmpty().filter { it.ref.isNotBlank() }
        val history = json.optJSONArray("history")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                val outcome = o.optString("outcome")
                if (outcome.isBlank()) null else ZapHistory(timerOf(o), outcome, o.optLong("at"))
            }
        }.orEmpty().filter { it.timer.ref.isNotBlank() }
        return State(timers, history)
    }

    @Synchronized
    private fun write(state: State) {
        fun JSONObject.put(timer: ZapTimer) = put("ref", timer.ref).put("ch", timer.channel).put("t", timer.title)
            .put("b", timer.begin).put("e", timer.end).put("id", timer.eventId)
        val json = JSONObject()
            .put("list", JSONArray(state.timers.map { JSONObject().put(it) }))
            .put("history", JSONArray(state.history.map { JSONObject().put(it.timer).put("outcome", it.outcome).put("at", it.completedAt) }))
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, json.toString())
        }.onFailure { AppLog.i("harmonogram: nie udało się zapisać ${path}: ${it.message}") }
    }

    /** Przyszłe i trwające przełączenia, od najbliższego. */
    fun list(): List<ZapTimer> = read().timers.filter { it.end > clock() }.sortedBy { it.begin }

    /** Zrealizowane, od najnowszych; starsze niż [retentionDays] dni są usuwane (0 = bez limitu). */
    fun history(retentionDays: Int): List<ZapHistory> {
        val state = read()
        val retained = retainHistory(state.history, clock(), retentionDays)
        if (retained.size != state.history.size) write(state.copy(history = retained))
        return retained
    }

    fun has(ref: String, begin: Long): Boolean = list().any { it.key == ZapTimer(ref, "", "", begin, 0).key }

    fun add(timer: ZapTimer) {
        val state = read()
        write(state.copy(timers = state.timers.filter { it.key != timer.key } + timer))
        AppLog.i("harmonogram: przełączenie ${timer.channel} — ${timer.title} o ${timer.begin}")
    }

    /** Zastępuje przełączenia o tej samej godzinie (kolizja) nowym wpisem. */
    fun replaceAt(timer: ZapTimer) {
        val state = read()
        val now = clock()
        val replaced = state.timers.filter { it.begin == timer.begin }.map { ZapHistory(it, ZapOutcome.REPLACED, now) }
        write(State(state.timers.filter { it.begin != timer.begin } + timer, replaced + state.history))
    }

    fun remove(timer: ZapTimer) {
        val state = read()
        write(state.copy(timers = state.timers.filter { it.key != timer.key }))
        AppLog.i("harmonogram: usunięto ${timer.channel} — ${timer.title}")
    }

    fun complete(timer: ZapTimer, outcome: String) {
        val state = read()
        write(
            State(
                state.timers.filter { it.key != timer.key },
                listOf(ZapHistory(timer, outcome, clock())) + state.history,
            ),
        )
        AppLog.i("harmonogram: ${timer.channel} — ${timer.title}: $outcome")
    }

    fun removeHistory(entry: ZapHistory) {
        val state = read()
        var removed = false
        write(state.copy(history = state.history.filter { if (!removed && it == entry) { removed = true; false } else true }))
    }

    companion object {
        val HISTORY_DAYS = listOf(7, 14, 28, 0)

        internal fun retainHistory(entries: List<ZapHistory>, now: Long, retentionDays: Int): List<ZapHistory> {
            val retained = if (retentionDays <= 0) entries else entries.filter { it.completedAt >= now - retentionDays * 86_400L }
            return retained.sortedByDescending { it.completedAt }
        }
    }
}
