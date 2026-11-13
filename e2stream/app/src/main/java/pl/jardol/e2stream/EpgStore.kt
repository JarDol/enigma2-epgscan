package pl.jardol.e2stream

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Trwały zapis EPG: jeden plik JSON na kanał (pliki/epg/). */
class EpgStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "epg").also { it.mkdirs() }

    private fun file(ref: String) =
        File(dir, OpenWebif.key(ref).replace(Regex("[^A-Z0-9]"), "_").trim('_') + ".json")

    /** (czas pobrania ms, audycje) albo null. */
    fun read(ref: String): Pair<Long, List<EpgEvent>>? {
        val f = file(ref)
        if (!f.exists()) return null
        return try {
            val js = JSONObject(f.readText())
            val arr = js.optJSONArray("e") ?: JSONArray()
            val out = ArrayList<EpgEvent>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    EpgEvent(
                        o.optInt("i"), o.optLong("b"), o.optLong("d"), OpenWebif.unescape(o.optString("t")),
                        OpenWebif.unescape(o.optString("s")), OpenWebif.unescape(o.optString("l")),
                        o.optString("r"), OpenWebif.unescape(o.optString("n")),
                    )
                )
            }
            js.optLong("at") to out
        } catch (_: Exception) {
            null
        }
    }

    fun write(ref: String, events: List<EpgEvent>) {
        val arr = JSONArray()
        for (e in events) {
            arr.put(
                JSONObject().put("i", e.id).put("b", e.begin).put("d", e.duration).put("t", e.title)
                    .put("s", e.shortDesc).put("l", e.longDesc).put("r", e.sref).put("n", e.sname)
            )
        }
        val target = file(ref)
        val tmp = File.createTempFile("epg-", ".tmp", dir)
        try {
            tmp.writeText(JSONObject().put("at", System.currentTimeMillis()).put("e", arr).toString())
            if (!tmp.renameTo(target)) throw java.io.IOException("Nie można zapisać EPG kanału $ref")
        } catch (e: java.io.IOException) {
            tmp.delete()
            AppLog.i("zapis EPG kanału $ref: $e")
            throw e
        }
    }

    fun count() = dir.listFiles()?.size ?: 0
}

/** Pobieranie EPG (i ikon) wszystkich kanałów z widocznych bukietów — ręcznie lub wg harmonogramu. */
object EpgSync {
    @Volatile var running = false
        private set

    /** Najbliższy minął termin z harmonogramu (ms) dla godziny "HH:MM". */
    private fun lastScheduled(hhmm: String): Long {
        val p = hhmm.split(":")
        val h = p.getOrNull(0)?.trim()?.toIntOrNull()?.coerceIn(0, 23) ?: 5
        val m = p.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis > System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, -1)
        return c.timeInMillis
    }

    /** true, gdy od ostatniego terminu harmonogramu EPG nie było pobierane. */
    fun due(prefs: Prefs, startupPending: Boolean): Boolean {
        if (running || prefs.host.isBlank()) return false
        if (prefs.pluginOk) return EpgUpdatePolicy.shouldCheck(
            prefs.epgUpdateMode, startupPending, prefs.packCheckAt, startOfToday())
        return prefs.epgDaily && prefs.lastEpgFetch < lastScheduled(prefs.epgHour)
    }

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * Dane z paczki wtyczki. Zwraca opis wyniku albo null, gdy paczki nie ma (wtedy pobieranie kanał po kanale).
     * Niezmieniona paczka nie jest ponownie importowana automatycznie.
     */
    private fun fromPack(ctx: Context, prefs: Prefs, store: EpgStore, manual: Boolean, progress: (String) -> Unit): String? {
        val api = PluginApi(prefs)
        val info = api.packInfo() ?: return null
        val at = info.optLong("at")
        if (info.optBoolean("building")) throw java.io.IOException(ctx.getString(R.string.epg_pack_building))
        if (at <= 0) return null
        if (info.optLong("epg_size") <= 0) throw java.io.IOException(ctx.getString(R.string.epg_pack_no_epg))
        val sha = info.optString("epg_sha256")
        val identity = EpgUpdatePolicy.packIdentity(prefs.host, prefs.pluginPort, at, info.optLong("epg_size"), sha)
        if (!EpgUpdatePolicy.needsImport(identity, prefs.packIdentity, manual)) {
            prefs.packCheckAt = System.currentTimeMillis()
            return ctx.getString(R.string.epg_pack_unchanged, fmt(at))
        }
        val t0 = SystemClock.elapsedRealtime()
        val tmp = File(ctx.cacheDir, "pack").also { it.mkdirs() }
        // EPG
        progress(ctx.getString(R.string.epg_from_receiver))
        var chans = 0
        var events = 0
        val epgF = File(tmp, "epg.json.gz")
        if (!api.packFile("epg.json.gz", epgF)) throw java.io.IOException(ctx.getString(R.string.epg_pack_no_epg))
        run {
            try {
                if (sha.isNotBlank()) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    epgF.inputStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (!actual.equals(sha, ignoreCase = true)) {
                        throw java.io.IOException(ctx.getString(R.string.epg_pack_corrupt))
                    }
                }
                val nowS = System.currentTimeMillis() / 1000
                val untilS = nowS + prefs.epgDays.coerceIn(1, 14) * 86_400L
                var fileAt = 0L
                var hasChannels = false
                // Strumieniowo: cały plik jako tekst może być zbyt duży dla pamięci telewizora.
                android.util.JsonReader(java.io.InputStreamReader(
                    java.util.zip.GZIPInputStream(epgF.inputStream().buffered(64 * 1024)), Charsets.UTF_8)).use { r ->
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "at" -> { fileAt = r.nextLong(); continue }
                            "ch" -> hasChannels = true
                            else -> { r.skipValue(); continue }
                        }
                        r.beginObject()
                        while (r.hasNext()) {
                            val k = r.nextName()
                            var name = ""
                            val ev = ArrayList<EpgEvent>()
                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "n" -> name = r.nextString()
                                    "e" -> {
                                        r.beginArray()
                                        while (r.hasNext()) {
                                            r.beginArray()
                                            val id = r.nextInt(); val b = r.nextLong(); val d = r.nextLong()
                                            val t = r.nextString(); val sd = r.nextString(); val ld = r.nextString()
                                            while (r.hasNext()) r.skipValue()
                                            r.endArray()
                                            if (b + d > nowS && b < untilS) ev.add(EpgEvent(id, b, d, t, sd, ld, k, ""))
                                        }
                                        r.endArray()
                                    }
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                            store.write(k, if (name.isEmpty()) ev else ev.map { it.copy(sname = name) })
                            chans++
                            events += ev.size
                        }
                        r.endObject()
                    }
                    r.endObject()
                }
                if (!hasChannels || fileAt != at) {
                    throw java.io.IOException(ctx.getString(R.string.epg_pack_mismatch))
                }
            } finally {
                epgF.delete()
            }
        }
        // ikony
        var icons = 0
        if (prefs.picons) {
            progress(ctx.getString(R.string.epg_picons_from_receiver))
            val zf = File(tmp, "picons.zip")
            try {
                if (api.packFile("picons.zip", zf)) icons = Picons.importZip(ctx, zf)
                else if (info.optLong("picons_size") > 0) throw java.io.IOException(ctx.getString(R.string.epg_pack_picons_error))
                else AppLog.i("paczka EPG Scan: brak pliku piconów")
            } finally {
                zf.delete()
            }
        }
        // zamiany referencji (np. kanał przeniesiony na inny transponder)
        val moved = mutableListOf<String>()
        val rf = File(tmp, "refs.json")
        try {
            if (api.packFile("refs.json", rf)) {
                val remap = JSONObject(rf.readText()).optJSONObject("remap") ?: JSONObject()
                fun mapped(ref: String): String? =
                    remap.optJSONObject(OpenWebif.key(ref))?.optString("to")?.takeIf { it.isNotBlank() }?.let { "$it:" }
                mapped(prefs.startChannel)?.let { prefs.startChannel = it; moved.add(ctx.getString(R.string.epg_moved_start)) }
                mapped(prefs.lastChannel)?.let { prefs.lastChannel = it; moved.add(ctx.getString(R.string.epg_moved_last)) }
            } else if (info.optLong("refs_size") > 0) {
                throw java.io.IOException(ctx.getString(R.string.epg_pack_refs_error))
            } else {
                AppLog.i("paczka EPG Scan: brak pliku referencji")
            }
        } finally {
            rf.delete()
        }
        prefs.packAt = at
        prefs.packIdentity = identity
        prefs.packCheckAt = System.currentTimeMillis()
        prefs.lastEpgFetch = System.currentTimeMillis()
        return ctx.getString(R.string.epg_pack_result, fmt(at), chans, events, icons,
            if (moved.isNotEmpty()) ctx.getString(R.string.epg_pack_moved, moved.joinToString(", ")) else "",
            (SystemClock.elapsedRealtime() - t0) / 1000)
    }

    private fun fmt(sec: Long) = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(sec * 1000))

    fun run(ctx: Context, prefs: Prefs, store: EpgStore, progress: (String) -> Unit, done: (String) -> Unit) =
        run(ctx, prefs, store, false, progress, done)

    fun run(ctx: Context, prefs: Prefs, store: EpgStore, manual: Boolean, progress: (String) -> Unit, done: (String) -> Unit) =
        run(ctx, prefs, store, manual, progress, done, {})

    @Synchronized
    fun run(ctx: Context, prefs: Prefs, store: EpgStore, manual: Boolean, progress: (String) -> Unit, done: (String) -> Unit, failed: () -> Unit) {
        val localized = AppLanguage.wrap(ctx)
        if (running) { done(localized.getString(R.string.epg_already_downloading)); return }
        running = true
        Thread {
            val t0 = SystemClock.elapsedRealtime()
            var result: String
            // 1) paczka z wtyczki EPG Scan (EPG, ikony, zamiany referencji — przygotowane po nocnym skanie)
            if (prefs.pluginOk) {
                val r = try { fromPack(localized, prefs, store, manual, progress) } catch (e: Exception) {
                    val message = localized.getString(R.string.epg_pack_error, e.message ?: e.toString())
                    AppLog.i(message)
                    running = false
                    failed()
                    done(message)
                    return@Thread
                }
                if (r != null) {
                    running = false
                    done(r)
                    return@Thread
                }
            }
            try {
                val api = OpenWebif(prefs)
                val hidden = prefs.hiddenBouquets
                val all = api.bouquets()
                val bq = all.filter { it.ref !in hidden }.ifEmpty { all }
                val seen = HashSet<String>()
                val chans = ArrayList<Channel>()
                for (b in bq) {
                    for (c in try { api.channels(b) } catch (_: Exception) { emptyList() }) {
                        if (seen.add(OpenWebif.key(c.ref))) chans.add(c)
                    }
                }
                val until = System.currentTimeMillis() / 1000 + prefs.epgDays.coerceIn(1, 14) * 86_400L
                val now = System.currentTimeMillis() / 1000
                val done1 = AtomicInteger(0)
                val events = AtomicInteger(0)
                val pool = Executors.newFixedThreadPool(2)
                for (c in chans) pool.execute {
                    try {
                        val ev = api.epgService(c.ref).filter { it.end > now && it.begin < until }
                        store.write(c.ref, ev)
                        events.addAndGet(ev.size)
                    } catch (_: Exception) { }
                    val n = done1.incrementAndGet()
                    if (n % 10 == 0) progress(localized.getString(R.string.epg_channel_progress, n, chans.size))
                }
                pool.shutdown()
                pool.awaitTermination(10, TimeUnit.MINUTES)
                var icons = ""
                if (prefs.picons) {
                    val got = Picons.fetch(ctx, prefs, chans) { progress(it) }
                    icons = localized.getString(R.string.epg_picons_count, got)
                }
                prefs.lastEpgFetch = System.currentTimeMillis()
                if (prefs.pluginOk) prefs.packCheckAt = System.currentTimeMillis()
                result = localized.getString(R.string.epg_download_result, chans.size, events.get(), icons, (SystemClock.elapsedRealtime() - t0) / 1000)
            } catch (e: Exception) {
                result = localized.getString(R.string.epg_download_error, e.message ?: e.toString())
                failed()
            }
            AppLog.i(result)
            running = false
            done(result)
        }.start()
    }
}
