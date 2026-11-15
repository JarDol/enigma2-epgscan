package pl.jardol.e2stream.desktop

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/** Audycja widziana w czasie zapisu bufora (z EPG co minutę albo z EIT ze strumienia); czasy w sekundach epoki. */
internal data class LoggedProgramme(val title: String, val description: String, val begin: Long, val end: Long)

/** Audycja w nagraniu lokalnym: [offsetMs] od początku nagrania. */
internal data class RecProgramme(val title: String, val offsetMs: Long, val description: String = "")

/** Dziennik audycji bufora timeshiftu (jak na Androidzie): kolejne audycje kanału, do spisu w nagraniach. */
internal class ProgrammeLog {
    private val entries = ArrayList<LoggedProgramme>()

    @Synchronized
    fun add(title: String, description: String, begin: Long, end: Long) {
        if (title.isBlank() || end <= begin) return
        val index = entries.indexOfFirst { it.begin == begin && it.title == title }
        val entry = LoggedProgramme(title.trim(), description.trim(), begin, end)
        if (index >= 0) entries[index] = entry
        else {
            // EIT mógł poprawić godziny tej samej audycji — wpis zaczynający się w tym samym miejscu zastępujemy
            entries.removeAll { it.begin == begin }
            entries += entry
            entries.sortBy { it.begin }
        }
        if (entries.size > 200) entries.subList(0, entries.size - 200).clear()
    }

    @Synchronized
    fun snapshot(): List<LoggedProgramme> = entries.toList()
}

/**
 * Spis audycji nagrania od [beginMs] do [endMs] (czas epoki, ms): audycje, które zajmują w nagraniu co najmniej
 * [minOverlapMs] (krótkie skrawki sąsiednich audycji pomijane); przesunięcie od początku nagrania (0 dla pierwszej,
 * gdy zaczęła się przed nagraniem).
 */
internal fun programmeIndex(
    log: List<LoggedProgramme>,
    beginMs: Long,
    endMs: Long,
    minOverlapMs: Long = 90_000,
): List<RecProgramme> = log
    .filter { minOf(it.end * 1000, endMs) - maxOf(it.begin * 1000, beginMs) >= minOverlapMs }
    .map { RecProgramme(it.title, (it.begin * 1000 - beginMs).coerceAtLeast(0), it.description) }
    .distinctBy { it.offsetMs to it.title }

private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** Tytuł nagrania z kilkoma audycjami: „Kanał 08:31–10:05: A, B, C”. */
internal fun indexedRecordingTitle(channel: String, beginMs: Long, endMs: Long, programmes: List<RecProgramme>): String {
    val names = programmes.map { it.title }
    val list = when {
        names.isEmpty() -> ""
        names.size <= 3 -> ": " + names.joinToString(", ")
        else -> ": " + names.take(3).joinToString(", ") + " i ${names.size - 3} więcej"
    }
    return "$channel ${clock.format(Instant.ofEpochMilli(beginMs))}–${clock.format(Instant.ofEpochMilli(endMs))}$list"
}

/** Opis: spis audycji z czasem od początku nagrania, potem ich opisy. */
internal fun indexedRecordingDescription(programmes: List<RecProgramme>): String {
    if (programmes.isEmpty()) return ""
    val index = programmes.joinToString("\n") { "${formatDuration(it.offsetMs)}  ${it.title}" }
    val details = programmes.filter { it.description.isNotBlank() }.joinToString("\n\n") { "${it.title}\n${it.description}" }
    return if (details.isBlank()) index else "$index\n\n$details"
}

internal fun programmesJson(programmes: List<RecProgramme>): JSONArray = JSONArray().apply {
    programmes.forEach { put(JSONObject().put("title", it.title).put("offsetMs", it.offsetMs).put("description", it.description)) }
}

internal fun programmesFrom(info: JSONObject?): List<RecProgramme> {
    val array = info?.optJSONArray("programmes") ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val title = item.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
        RecProgramme(title, item.optLong("offsetMs").coerceAtLeast(0), item.optString("description"))
    }.sortedBy { it.offsetMs }
}

/** Audycja trwająca w miejscu [positionMs] nagrania. */
internal fun programmeAt(programmes: List<RecProgramme>, positionMs: Long): RecProgramme? =
    programmes.lastOrNull { it.offsetMs <= positionMs } ?: programmes.firstOrNull()
