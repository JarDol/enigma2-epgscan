package pl.jardol.e2stream

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object RecordingExtract {
    private const val MARKER = "extract.json"

    private fun pending(dir: File): JSONObject? {
        val file = File(dir, MARKER)
        if (!file.exists()) return null
        return JSONObject(file.readText())
    }

    fun pendingMeta(dir: File): JSONObject? = pending(dir)?.getJSONObject("meta")

    fun pendingParts(dir: File): Set<String>? {
        val array = pending(dir)?.getJSONArray("keep") ?: return null
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    fun available(dir: File): List<JournalEvent> {
        val meta = LocalRecordings.readMeta(dir) ?: return emptyList()
        if (!meta.optBoolean("done")) return emptyList()
        val segments = LocalRecordings.segments(meta)
        if (segments.isEmpty()) return emptyList()
        val actual = LocalRecordings.parts(dir).filter { it.name != "part-00000.ts" }.map { it.name }
        if (segments.map { it.name } != actual || segments.any { !File(dir, it.name).isFile }) return emptyList()
        val start = segments.first().startMs
        val end = segments.last().endMs
        return LocalRecordings.events(meta).filter { it.end * 1000 > start && it.begin * 1000 < end }
    }

    @Throws(IOException::class)
    fun extract(dir: File, event: JournalEvent) {
        if (!LocalRecordings.activeDirs.add(dir)) throw IOException("nagranie jest używane")
        try {
            extractClaimed(dir, event)
        } finally {
            LocalRecordings.activeDirs -= dir
        }
    }

    @Throws(IOException::class)
    fun extractClaimed(dir: File, event: JournalEvent) = synchronized(LocalRecordings.metadataLock) {
        if (pending(dir) != null) throw IOException("wydzielanie nagrania wymaga dokończenia")
        val meta = LocalRecordings.readMeta(dir) ?: throw IOException("brak opisu nagrania")
        if (event !in available(dir)) throw IOException("audycja nie jest już dostępna")
        val segments = LocalRecordings.segments(meta)
        val selected = segments.filter { it.endMs > event.begin * 1000 && it.startMs < (event.end + 60) * 1000 }
        if (selected.isEmpty()) throw IOException("brak fragmentów audycji")
        val all = LocalRecordings.parts(dir)
        val keep = selected.map { it.name }.toMutableSet()
        if (all.any { it.name == "part-00000.ts" }) keep += "part-00000.ts"
        val start = selected.first().startMs
        val end = selected.last().endMs
        val lengthSec = ((end - start) / 1000).coerceAtLeast(0)
        val updated = JSONObject(meta.toString())
            .put("title", event.title).put("desc", event.desc).put("begin", start / 1000)
            .put("lengthSec", lengthSec).put("size", all.filter { it.name in keep }.sumOf { it.length() })
        updated.remove("scan")
        updated.remove("scanV")
        LocalRecordings.putSegments(updated, selected)
        LocalRecordings.putEvents(updated, listOf(event))
        LocalRecordings.putChapters(updated, listOf(Chapter(0, lengthSec, event.title, event.desc,
            cutStart = start > event.begin * 1000 + 30_000,
            cutEnd = end < event.end * 1000 - 30_000)))
        val marker = JSONObject().put("meta", updated).put("keep", JSONArray().apply {
            all.filter { it.name in keep }.forEach { put(it.name) }
        })
        val tmp = File(dir, "$MARKER.tmp")
        FileOutputStream(tmp).use { output ->
            output.write(marker.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!tmp.renameTo(File(dir, MARKER))) throw IOException("nie można zapisać stanu wydzielania")
        finishPending(dir)
    }

    @Throws(IOException::class)
    private fun finishPending(dir: File) {
        val marker = pending(dir) ?: return
        val meta = marker.getJSONObject("meta")
        val array = marker.getJSONArray("keep")
        val keep = (0 until array.length()).map { array.getString(it) }.toSet()
        val selected = LocalRecordings.segments(meta).map { it.name }
        if (keep.isEmpty() || keep.any {
                File(it).name != it || !it.startsWith("part-") || !it.endsWith(".ts") ||
                    !File(dir, it).isFile || File(dir, it).length() <= 0
            } || selected.isEmpty() || selected.toSet() != keep - "part-00000.ts")
            throw IOException("brakuje fragmentu wydzielanej audycji")
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("part-") && f.name.endsWith(".ts") }
            ?: throw IOException("nie można odczytać katalogu nagrania")
        for (file in files) {
            if (file.name !in keep && !file.delete() && file.exists())
                throw IOException("nie można usunąć ${file.name}")
        }
        if (!LocalRecordings.writeMeta(dir, meta)) throw IOException("nie można zapisać opisu audycji")
        if (!File(dir, MARKER).delete() && File(dir, MARKER).exists())
            throw IOException("nie można zakończyć wydzielania audycji")
    }

    fun recover(dir: File) {
        if (!File(dir, MARKER).exists()) return
        if (!LocalRecordings.activeDirs.add(dir)) return
        try {
            synchronized(LocalRecordings.metadataLock) { finishPending(dir) }
        } finally {
            LocalRecordings.activeDirs -= dir
        }
    }
}
