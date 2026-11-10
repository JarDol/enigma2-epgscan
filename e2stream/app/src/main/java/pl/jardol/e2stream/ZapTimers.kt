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
    class Timer(val ref: String, val channel: String, val title: String, val begin: Long, val end: Long, val eventId: Int = 0) {
        fun key() = "${OpenWebif.key(ref)}@$begin"
    }

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

    fun has(ctx: Context, ref: String, begin: Long) = list(ctx).any { it.key() == Timer(ref, "", "", begin, 0).key() }

    fun add(ctx: Context, t: Timer) {
        val l = list(ctx).filter { it.key() != t.key() } + t
        save(ctx, l)
        AppLog.i("harmonogram: przełączenie ${t.channel} — ${t.title} o ${t.begin}")
    }

    fun replaceAt(ctx: Context, t: Timer) {
        val l = list(ctx).filter { it.begin != t.begin } + t
        save(ctx, l)
        AppLog.i("harmonogram: zastąpiono przełączenie o ${t.begin} przez ${t.channel} — ${t.title}")
    }

    fun remove(ctx: Context, t: Timer) {
        save(ctx, list(ctx).filter { it.key() != t.key() })
        AppLog.i("harmonogram: usunięto ${t.channel} — ${t.title}")
    }
}
