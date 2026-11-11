package pl.jardol.e2stream

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Przełączenia wg harmonogramu: „o początku audycji przełącz na kanał”. Zapisywane tylko na tym
 * urządzeniu (nie synchronizowane przez dekoder) — dotyczą tego, co dzieje się na danym telewizorze.
 * Działają, gdy aplikacja jest uruchomiona.
 */
object ZapTimers {
    const val SWITCHED = "switched"
    const val RECORDED = "recorded"
    const val RECORD_FAILED = "record_failed"
    const val SKIPPED = "skipped"
    const val REPLACED = "replaced"

    class Timer(val ref: String, val channel: String, val title: String, val begin: Long, val end: Long, val eventId: Int = 0) {
        fun key() = "${OpenWebif.key(ref)}@$begin"
    }

    class History(val timer: Timer, val outcome: String, val completedAt: Long)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("zaptimers", Context.MODE_PRIVATE)

    /** Wszystkie (bez zakończonych audycji), od najbliższego. */
    fun list(ctx: Context): List<Timer> {
        val now = System.currentTimeMillis() / 1000
        val arr = try { JSONArray(sp(ctx).getString("list", "[]")) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<Timer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = Timer(o.optString("ref"), o.optString("ch"), o.optString("t"), o.optLong("b"), o.optLong("e"), o.optInt("id"))
            if (t.ref.isNotBlank() && t.end > now) out += t
        }
        return out.sortedBy { it.begin }
    }

    private fun save(ctx: Context, l: List<Timer>) {
        val arr = JSONArray()
        for (t in l) arr.put(JSONObject().put("ref", t.ref).put("ch", t.channel).put("t", t.title).put("b", t.begin).put("e", t.end).put("id", t.eventId))
        sp(ctx).edit().putString("list", arr.toString()).apply()
    }

    fun history(ctx: Context): List<History> {
        val arr = try { JSONArray(sp(ctx).getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<History>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = Timer(o.optString("ref"), o.optString("ch"), o.optString("t"), o.optLong("b"), o.optLong("e"), o.optInt("id"))
            val outcome = o.optString("outcome")
            if (t.ref.isNotBlank() && outcome.isNotBlank()) out += History(t, outcome, o.optLong("at"))
        }
        val retained = retainHistory(out, System.currentTimeMillis() / 1000, Prefs(ctx).zapHistoryDays)
        if (retained.size != out.size) saveHistory(ctx, retained)
        return retained
    }

    private fun saveHistory(ctx: Context, entries: List<History>) {
        val arr = JSONArray()
        for (h in entries) {
            val t = h.timer
            arr.put(JSONObject().put("ref", t.ref).put("ch", t.channel).put("t", t.title)
                .put("b", t.begin).put("e", t.end).put("id", t.eventId)
                .put("outcome", h.outcome).put("at", h.completedAt))
        }
        sp(ctx).edit().putString("history", arr.toString()).apply()
    }

    internal fun retainHistory(entries: List<History>, now: Long, retentionDays: Int): List<History> {
        val retained = if (retentionDays <= 0) entries else {
            val cutoff = now - retentionDays * 86_400L
            entries.filter { it.completedAt >= cutoff }
        }
        return retained.sortedByDescending { it.completedAt }
    }

    internal fun withoutHistoryEntry(entries: List<History>, target: History): List<History> {
        var removed = false
        return entries.filter { entry ->
            if (!removed && entry.timer.key() == target.timer.key() &&
                entry.outcome == target.outcome && entry.completedAt == target.completedAt
            ) {
                removed = true
                false
            } else true
        }
    }

    fun removeHistory(ctx: Context, target: History) {
        saveHistory(ctx, withoutHistoryEntry(history(ctx), target))
        AppLog.i("harmonogram: usunięto z historii ${target.timer.channel} — ${target.timer.title}")
    }

    fun mark(ctx: Context, t: Timer, outcome: String) {
        val entries = history(ctx).toMutableList()
        entries.add(0, History(t, outcome, System.currentTimeMillis() / 1000))
        saveHistory(ctx, entries)
    }

    fun complete(ctx: Context, t: Timer, outcome: String) {
        save(ctx, list(ctx).filter { it.key() != t.key() })
        ZapWakeAlarms.sync(ctx)
        mark(ctx, t, outcome)
        AppLog.i("harmonogram: ${t.channel} — ${t.title}: $outcome")
    }

    fun has(ctx: Context, ref: String, begin: Long) = list(ctx).any { it.key() == Timer(ref, "", "", begin, 0).key() }

    fun add(ctx: Context, t: Timer) {
        val l = list(ctx).filter { it.key() != t.key() } + t
        save(ctx, l)
        ZapWakeAlarms.sync(ctx)
        AppLog.i("harmonogram: przełączenie ${t.channel} — ${t.title} o ${t.begin}")
    }

    fun replaceAt(ctx: Context, t: Timer) {
        val current = list(ctx)
        current.filter { it.begin == t.begin }.forEach { mark(ctx, it, REPLACED) }
        save(ctx, current.filter { it.begin != t.begin } + t)
        ZapWakeAlarms.sync(ctx)
        AppLog.i("harmonogram: zastąpiono przełączenie o ${t.begin} przez ${t.channel} — ${t.title}")
    }

    fun remove(ctx: Context, t: Timer) {
        save(ctx, list(ctx).filter { it.key() != t.key() })
        ZapWakeAlarms.sync(ctx)
        AppLog.i("harmonogram: usunięto ${t.channel} — ${t.title}")
    }
}
