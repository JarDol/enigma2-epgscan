package pl.jardol.e2stream.desktop

import org.json.JSONArray
import org.json.JSONObject

/** Kanał z historii oglądania (ze swoim bukietem — wybór przełącza na ten bukiet). */
internal data class HistoryEntry(val ref: String, val name: String, val bouquet: String)

internal fun historyProgramme(event: EpgEvent?): String? =
    event?.takeIf { it.title.isNotBlank() }?.let { "${EpgTime.range(it.begin, it.end)}  ·  ${it.title}" }

internal fun updateHistoryProgrammes(pick: QuickPick?, tag: String, programmes: List<String?>): QuickPick? =
    if (pick?.tag == tag) pick.copy(details = programmes) else pick

/** Historia oglądanych kanałów ze wszystkich bukietów: najnowszy pierwszy, bez powtórzeń, do [LIMIT]. */
internal object ChannelHistory {
    const val LIMIT = 50

    fun parse(json: String): List<HistoryEntry> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val ref = item.optString("ref").takeIf(String::isNotBlank) ?: return@mapNotNull null
            HistoryEntry(ref, item.optString("name").ifBlank { ref }, item.optString("bouquet"))
        }
    }.getOrDefault(emptyList())

    fun encode(entries: List<HistoryEntry>): String = JSONArray().apply {
        entries.forEach { put(JSONObject().put("ref", it.ref).put("name", it.name).put("bouquet", it.bouquet)) }
    }.toString()

    /** Kanał na początek historii (ten sam kanał przeniesiony, nie zdublowany). */
    fun push(json: String, entry: HistoryEntry): String {
        val key = serviceKey(entry.ref)
        val rest = parse(json).filterNot { serviceKey(it.ref) == key }
        return encode((listOf(entry) + rest).take(LIMIT))
    }
}
