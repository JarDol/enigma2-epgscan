package pl.jardol.e2stream

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Nagrania lokalne z bufora timeshiftu: katalog „nagrania” obok katalogu bufora (ten sam nośnik — kawałki
 * bufora wypadające z okna oraz pozostałe przy kończeniu nagrania są przenoszone, a w razie potrzeby kopiowane).
 * Każde nagranie to katalog z part-00001.ts… i opisem meta.json.
 */
/** Rodzaj nagrania lokalnego. */
const val REC_EVENT = 0          // bieżąca audycja (od jej początku w buforze do końca wg EPG)
const val REC_NOW = 1            // bufor do teraz
const val REC_UNTIL_ZAP = 2      // cały bufor i dalej, do zmiany kanału
const val REC_UNTIL_DROP = 3     // cały bufor i dalej, do usunięcia bufora (także w tle)

data class RecordingSegment(val name: String, val startMs: Long, val endMs: Long)

object LocalRecordings {
    val activeDirs: MutableSet<File> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val metadataLock = Any()
    const val DIR = "nagrania"
    const val META = "meta.json"
    const val PREFIX = "local:"

    /** Katalog nagrań dla katalogu bufora timeshiftu (…/timeshift → …/nagrania). */
    fun base(tsDir: File): File = File(tsDir.parentFile ?: tsDir, DIR)

    fun isLocal(path: String) = path.startsWith(PREFIX)
    fun dirOf(path: String): File = File(path.removePrefix(PREFIX))

    /**
     * Pliki nagrania w kolejności: kawałki part-*.ts; po konwersji — jeden plik .ts o czytelnej nazwie
     * (w trakcie konwersji: pierwszy kawałek z doklejonymi kolejnymi + pozostałe kawałki).
     */
    fun parts(dir: File): List<File> {
        val keep = RecordingExtract.pendingParts(dir)
        val pieces = dir.listFiles { f -> f.name.startsWith("part-") && f.name.endsWith(".ts") &&
            (keep == null || f.name in keep) }?.sortedBy { it.name } ?: emptyList()
        if (pieces.isNotEmpty() || keep != null) return pieces
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".ts") }?.sortedBy { it.name } ?: emptyList()
    }

    /** Czy nagranie jest już jednym plikiem. */
    fun isSingleFile(dir: File) = parts(dir).size == 1

    /**
     * Konwersja na jeden plik w katalogu nagrania (bez drugiej kopii): kolejne kawałki są doklejane do pierwszego
     * i od razu usuwane — dodatkowe miejsce to najwyżej jeden kawałek. Stan po każdym kawałku zapisany w meta.json,
     * więc przerwanie (także wyłączenie zasilania) nie psuje nagrania; ponowne uruchomienie kontynuuje.
     * Na końcu plik dostaje czytelną nazwę (jak eksport). [cancel] — zatrzymanie po bieżącym kawałku.
     */
    fun convert(dir: File, cancel: java.util.concurrent.atomic.AtomicBoolean, onProgress: (Long, Long, Long) -> Unit): Boolean {
        val meta0 = readMeta(dir)
        // przerwanie między zapisem stanu a usunięciem kawałka: ten kawałek jest już doklejony — tylko go usuń
        meta0?.optString("mergedPart")?.takeIf { it.isNotBlank() }?.let { File(dir, it).delete() }
        val all = parts(dir)
        if (all.isEmpty()) throw IOException("brak plików nagrania")
        val first = all[0]
        val meta = meta0 ?: JSONObject()
        // wznowienie po przerwaniu: pierwszy plik przycięty do długości zapisanej po ostatnim całym kawałku
        val savedLen = meta.optLong("mergeLen", -1)
        if (savedLen >= 0 && first.length() > savedLen) RandomAccessFile(first, "rw").use { it.setLength(savedLen) }
        val rest = all.drop(1)
        val total = rest.sumOf { it.length() }
        var done = 0L
        val t0 = android.os.SystemClock.elapsedRealtime()
        val buf = ByteArray(1 shl 20)
        for (p in rest) {
            if (cancel.get()) return false
            val len = p.length()
            java.io.FileOutputStream(first, true).use { out ->
                java.io.FileInputStream(p).use { inp ->
                    var copied = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        val el = android.os.SystemClock.elapsedRealtime() - t0
                        val d = done + copied
                        val eta = if (d > 0 && el > 1500) ((total - d) * el / d) / 1000 else -1
                        if (copied % (8L shl 20) < n) onProgress(d, total, eta)
                    }
                }
                out.fd.sync()
            }
            meta.put("mergeLen", first.length()).put("mergedPart", p.name)
            if (!writeMeta(dir, meta)) throw IOException("nie można zapisać stanu konwersji")
            p.delete()
            done += len
        }
        val target = File(dir, exportFile(dir).name)
        if (first.name != target.name) {
            if (target.exists()) target.delete()
            if (!first.renameTo(target)) throw IOException("nie udało się zmienić nazwy ${first.name}")
        }
        meta.remove("mergeLen")
        meta.remove("mergedPart")
        meta.put("file", target.name)
        if (!writeMeta(dir, meta)) throw IOException("nie można zapisać końcowego stanu konwersji")
        onProgress(total, total, 0)
        return true
    }

    fun size(dir: File): Long = parts(dir).sumOf { it.length() }

    /** Miejsce zajęte przez wszystkie nagrania w [base] (katalogi kawałków i wyeksportowane pliki .ts). */
    fun used(base: File): Long =
        (base.listFiles { f -> f.isDirectory }?.sumOf { size(it) } ?: 0L) +
            (base.listFiles { f -> f.isFile && f.name.endsWith(".ts") }?.sumOf { it.length() } ?: 0L)

    /** Nazwa pliku eksportu: „2026-11-14 20-00 TVN 24 HD - Superwizjer.ts” (w katalogu nagrań). */
    fun exportFile(dir: File): File {
        val m = readMeta(dir) ?: org.json.JSONObject()
        val begin = m.optLong("begin", dir.lastModified() / 1000) * 1000
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.US).format(java.util.Date(begin))
        val name = listOf(m.optString("service"), m.optString("title")).filter { it.isNotBlank() }.distinct().joinToString(" - ")
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(90).ifEmpty { dir.name }
        return File(dir.parentFile, "$stamp $safe.ts")
    }

    /**
     * Sklejenie kawałków nagrania w jeden plik [target] (najpierw .part, na końcu zmiana nazwy).
     * [onProgress] (bajty zapisane, wszystkie, szacowane sekundy do końca albo -1). Przerwane [cancel] — plik usunięty.
     */
    fun export(dir: File, target: File, cancel: java.util.concurrent.atomic.AtomicBoolean,
               onProgress: (Long, Long, Long) -> Unit): Boolean {
        val parts = parts(dir)
        val total = parts.sumOf { it.length() }
        val tmp = File(target.parentFile, target.name + ".part")
        var done = 0L
        val t0 = android.os.SystemClock.elapsedRealtime()
        var lastReport = 0L
        try {
            java.io.FileOutputStream(tmp).use { out ->
                val buf = ByteArray(1 shl 20)
                for (f in parts) {
                    java.io.FileInputStream(f).use { inp ->
                        while (true) {
                            if (cancel.get()) throw java.io.InterruptedIOException("anulowano")
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastReport >= 500) {
                                lastReport = now
                                val el = now - t0
                                val eta = if (done > 0 && el > 1500) ((total - done) * el / done) / 1000 else -1
                                onProgress(done, total, eta)
                            }
                        }
                    }
                }
                out.fd.sync()
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("nie udało się zmienić nazwy ${tmp.name}")
            onProgress(total, total, 0)
            return true
        } catch (e: Exception) {
            tmp.delete()
            if (e !is java.io.InterruptedIOException) throw e
            return false
        }
    }

    /** Pojemność nośnika (bajty) — limit procentowy liczony od niej. */
    fun capacity(base: File): Long = try {
        var d: File? = base
        while (d != null && !d.exists()) d = d.parentFile
        android.os.StatFs((d ?: base).absolutePath).totalBytes
    } catch (_: Exception) { 0L }

    /** Nowy katalog nagrania: rrrrmmdd-ggmmss-<kanał>. */
    fun newDir(base: File, channel: String, epochMs: Long): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(epochMs))
        val safe = channel.replace(Regex("[^A-Za-z0-9ĄĆĘŁŃÓŚŹŻąćęłńóśźż _-]"), "").trim().replace(' ', '_').take(40).ifEmpty { "kanal" }
        var d = File(base, "$stamp-$safe")
        var i = 2
        while (d.exists()) d = File(base, "$stamp-$safe-${i++}")
        return d
    }

    internal fun writeJsonAtomic(target: File, value: JSONObject) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        java.io.FileOutputStream(tmp).use { output ->
            output.write(value.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } else {
            try {
                android.system.Os.rename(tmp.absolutePath, target.absolutePath)
            } catch (e: android.system.ErrnoException) {
                throw IOException("nie można zastąpić ${target.name}", e)
            }
        }
    }

    fun writeMeta(dir: File, meta: JSONObject): Boolean =
        try {
            dir.mkdirs()
            writeJsonAtomic(File(dir, META), meta)
            true
        } catch (e: Exception) {
            AppLog.i("nagranie: zapis opisu ${dir.name} nieudany ($e)")
            false
        }

    fun readMeta(dir: File): JSONObject? = try {
        RecordingExtract.pendingMeta(dir) ?: JSONObject(File(dir, META).readText())
    } catch (e: Exception) {
        AppLog.i("nagranie: odczyt opisu ${dir.name} nieudany ($e)")
        null
    }

    /** Spis audycji nagrania w opisie (meta.json: "chapters"). */
    fun putChapters(meta: JSONObject, ch: List<Chapter>) {
        meta.put("chapters", org.json.JSONArray().apply {
            ch.forEach { c ->
                put(JSONObject().put("s", c.start).put("e", c.end).put("t", c.title).put("d", c.desc)
                    .put("cs", c.cutStart).put("ce", c.cutEnd))
            }
        })
    }

    fun chapters(meta: JSONObject): List<Chapter> {
        val a = meta.optJSONArray("chapters") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Chapter(o.optLong("s"), o.optLong("e"), o.optString("t"), o.optString("d"), o.optBoolean("cs"), o.optBoolean("ce"))
        }
    }

    fun putSegments(meta: JSONObject, segments: List<RecordingSegment>) {
        meta.put("segments", org.json.JSONArray().apply {
            segments.forEach { put(JSONObject().put("name", it.name).put("start", it.startMs).put("end", it.endMs)) }
        })
    }

    fun segments(meta: JSONObject): List<RecordingSegment> {
        val array = meta.optJSONArray("segments") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("name")
            val start = item.optLong("start")
            val end = item.optLong("end")
            if (File(name).name != name || !name.startsWith("part-") || !name.endsWith(".ts") || end <= start)
                null else RecordingSegment(name, start, end)
        }
    }

    fun putEvents(meta: JSONObject, events: List<JournalEvent>) {
        meta.put("events", org.json.JSONArray().apply {
            events.forEach { put(JSONObject().put("begin", it.begin).put("end", it.end).put("title", it.title).put("desc", it.desc)) }
        })
    }

    fun events(meta: JSONObject): List<JournalEvent> {
        val array = meta.optJSONArray("events") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val begin = item.optLong("begin")
            val end = item.optLong("end")
            val title = item.optString("title")
            if (end <= begin || title.isBlank()) null else JournalEvent(begin, end, title, item.optString("desc"))
        }
    }

    /** Lista nagrań (od najnowszego) w postaci jak nagrania z dekodera. */
    fun list(base: File, active: Set<File> = emptySet()): List<Recording> {
        val dirs = base.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { d ->
            if (d in active) return@mapNotNull null
            val parts = parts(d)
            if (parts.isEmpty()) return@mapNotNull null
            val m = readMeta(d) ?: JSONObject()
            val size = parts.sumOf { it.length() }
            Recording(
                PREFIX + d.absolutePath,
                m.optString("title").ifBlank { d.name },
                m.optString("desc"),
                m.optString("service"),
                m.optString("sref"),
                m.optLong("begin", d.lastModified() / 1000),
                m.optLong("lengthSec"),
                size,
                false,
            )
        }.sortedByDescending { it.begin }
    }

    fun delete(dir: File): Boolean {
        dir.listFiles()?.forEach { it.delete() }
        return dir.delete() || !dir.exists()
    }
}

/** Odczyt nagrania lokalnego: kolejne kawałki part-*.ts jako jeden plik (przewijanie, znana długość). */
class LocalRecordingDataSource(private val dir: File) : DataSource {
    private var files: List<File> = emptyList()
    private var starts = LongArray(0)
    private var total = 0L
    private var pos = 0L
    private var lengths = LongArray(0)
    private var input: java.io.BufferedInputStream? = null
    private var rafIdx = -1
    private var remaining = 0L
    private var uri: Uri? = null
    /** Pomijany początek nagrania (dane przed pierwszym PCR — starsze nagrania); -1 = jeszcze nie sprawdzony. */
    private var skip = -1L

    /**
     * Starsze nagrania zaczynają się paczką danych bez zegara PCR (początek strumienia z Enigmy2) — ExoPlayer nie
     * odczytuje wtedy długości i nie przewija. Odczyt zaczyna się od pierwszego pakietu z PCR (najwyżej 8 MB dalej).
     */
    private fun leadingWithoutPcr(f: File): Long = try {
        val head = java.io.RandomAccessFile(f, "r").use { r ->
            val b = ByteArray(minOf(f.length(), 4_000_000L).toInt())
            r.readFully(b)
            b
        }
        // PID zegara z PMT (tak jak ExoPlayer) — PCR innych programów multipleksu się nie liczy
        val pcrPid = RecScan.pmtPcrPid(head)
        var found = -1L
        if (pcrPid >= 0) {
            var o = 0
            while (o + 376 < head.size) {
                if (head[o].toInt() == 0x47 && head[o + 188].toInt() == 0x47) {
                    val pid = ((head[o + 1].toInt() and 0x1f) shl 8) or (head[o + 2].toInt() and 0xff)
                    if (pid == pcrPid && (head[o + 1].toInt() and 0x80) == 0 && (head[o + 3].toInt() and 0x20) != 0 &&
                        (head[o + 4].toInt() and 0xff) >= 7 && (head[o + 5].toInt() and 0x10) != 0) { found = o.toLong(); break }
                    o += 188
                } else o++
            }
        }
        val k = if (found > 0) found else 0L
        if (k > 0) AppLog.i("nagranie: pomijam ${k / 1000} kB początku bez zegara PCR (pid $pcrPid, ${dir.name})")
        else if (pcrPid < 0) AppLog.i("nagranie: brak PAT/PMT w pierwszych 4 MB (${dir.name})")
        k
    } catch (_: Exception) { 0L }

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        close()
        uri = dataSpec.uri
        files = LocalRecordings.parts(dir)
        if (files.isEmpty()) throw IOException("nagranie: brak plików w ${dir.name}")
        starts = LongArray(files.size)
        lengths = LongArray(files.size)
        var t = 0L
        for (i in files.indices) { starts[i] = t; lengths[i] = files[i].length(); t += lengths[i] }
        if (skip < 0) skip = leadingWithoutPcr(files[0])
        total = t - skip
        pos = dataSpec.position
        if (pos < 0 || pos > total) throw IOException("nagranie: pozycja poza plikiem")
        val left = total - pos
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, left) else left
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val apos = pos + skip
        val idx = if (rafIdx >= 0 && apos < starts[rafIdx] + lengths[rafIdx]) rafIdx
            else starts.indexOfLast { it <= apos }
        if (idx != rafIdx) {
            input?.close()
            val file = java.io.FileInputStream(files[idx])
            try {
                file.channel.position(apos - starts[idx])
                input = java.io.BufferedInputStream(file, 256 * 1024)
            } catch (e: IOException) {
                file.close()
                throw e
            }
            rafIdx = idx
        }
        val inFile = lengths[idx] - (apos - starts[idx])
        val n = input!!.read(buffer, offset, minOf(length.toLong(), inFile, remaining).toInt())
        if (n < 0) throw java.io.EOFException("nagranie: niepełny plik ${files[idx].name}")
        pos += n
        remaining -= n
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        input?.close()
        input = null
        rafIdx = -1
        remaining = 0
    }
}

