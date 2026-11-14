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
 * bufora są przenoszone, nie kopiowane). Każde nagranie to katalog z part-00001.ts… i opisem meta.json.
 */
/** Rodzaj nagrania lokalnego. */
const val REC_EVENT = 0          // bieżąca audycja (od jej początku w buforze do końca wg EPG)
const val REC_NOW = 1            // bufor do teraz
const val REC_UNTIL_ZAP = 2      // cały bufor i dalej, do zmiany kanału

object LocalRecordings {
    const val DIR = "nagrania"
    const val META = "meta.json"
    const val PREFIX = "local:"

    /** Katalog nagrań dla katalogu bufora timeshiftu (…/timeshift → …/nagrania). */
    fun base(tsDir: File): File = File(tsDir.parentFile ?: tsDir, DIR)

    fun isLocal(path: String) = path.startsWith(PREFIX)
    fun dirOf(path: String): File = File(path.removePrefix(PREFIX))

    fun parts(dir: File): List<File> =
        dir.listFiles { f -> f.name.startsWith("part-") && f.name.endsWith(".ts") }?.sortedBy { it.name } ?: emptyList()

    fun size(dir: File): Long = parts(dir).sumOf { it.length() }

    /** Miejsce zajęte przez wszystkie nagrania w [base]. */
    fun used(base: File): Long = base.listFiles { f -> f.isDirectory }?.sumOf { size(it) } ?: 0L

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

    fun writeMeta(dir: File, meta: JSONObject) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$META.tmp")
            tmp.writeText(meta.toString())
            if (!tmp.renameTo(File(dir, META))) { File(dir, META).writeText(meta.toString()); tmp.delete() }
        } catch (e: Exception) {
            AppLog.i("nagranie: zapis opisu ${dir.name} nieudany ($e)")
        }
    }

    fun readMeta(dir: File): JSONObject? = try { JSONObject(File(dir, META).readText()) } catch (_: Exception) { null }

    /** Lista nagrań (od najnowszego) w postaci jak nagrania z dekodera. */
    fun list(base: File): List<Recording> {
        val dirs = base.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { d ->
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
    private var raf: RandomAccessFile? = null
    private var rafIdx = -1
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        files = LocalRecordings.parts(dir)
        if (files.isEmpty()) throw IOException("nagranie: brak plików w ${dir.name}")
        starts = LongArray(files.size)
        var t = 0L
        for (i in files.indices) { starts[i] = t; t += files[i].length() }
        total = t
        pos = dataSpec.position
        if (pos > total) throw IOException("nagranie: pozycja poza plikiem")
        val left = total - pos
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, left) else left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (pos >= total) return C.RESULT_END_OF_INPUT
        var idx = starts.indexOfLast { it <= pos }
        while (idx < files.size - 1 && pos >= starts[idx] + files[idx].length()) idx++
        if (idx != rafIdx) {
            try { raf?.close() } catch (_: Exception) {}
            raf = RandomAccessFile(files[idx], "r")
            rafIdx = idx
        }
        val r = raf!!
        r.seek(pos - starts[idx])
        val inFile = files[idx].length() - (pos - starts[idx])
        val n = r.read(buffer, offset, minOf(length.toLong(), inFile).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        pos += n
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { raf?.close() } catch (_: Exception) {}
        raf = null
        rafIdx = -1
    }
}
