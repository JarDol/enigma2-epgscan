package pl.jardol.e2stream

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * Prosty log do pliku w pamięci urządzenia: Android/data/pl.jardol.e2stream/files/e2stream.log, rotacja przy 1 MB.
 * Kopia na podłączonym dysku (miejsce „logi i zrzuty”, gdy to nie pamięć urządzenia) dopisywana w osobnym wątku —
 * zawieszony dysk nie blokuje aplikacji, a gdy apki nie da się uruchomić, logi i tak są na dysku.
 */
object AppLog {
    private const val TAG = "E2Stream"
    private const val MAX = 1_000_000L
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null
    @Volatile private var mirror: File? = null
    private val lock = Any()
    private val mirrorQueue = LinkedBlockingQueue<String>(20_000)
    private var mirrorThread: Thread? = null

    @Volatile
    var dirty = false
        private set

    fun init(ctx: Context, dir: File? = null) {
        val d = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        synchronized(lock) {
            file = File(d, "e2stream.log")
            // dawne osobne logi PiP (wpisy PiP są w logu głównym)
            try { d.listFiles { f -> f.name.startsWith("e2stream-pip") }?.forEach { it.delete() } } catch (_: Exception) {}
            mirror = dir?.takeIf { it.absolutePath != d.absolutePath }?.let { File(it, "e2stream.log") }
            if (mirror != null && mirrorThread == null) {
                mirrorThread = Thread({ mirrorLoop() }, "log-kopia-na-dysk").apply { isDaemon = true; start() }
            }
        }
    }

    fun dir(): File? = file?.parentFile

    fun path(): String = file?.absolutePath ?: "-"

    /** Pliki logu w pamięci urządzenia (poprzedni i bieżący). */
    fun files(): List<File> {
        val f = file ?: return emptyList()
        return listOf(File(f.parentFile, "e2stream.1.log"), f).filter { it.exists() }
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        val f = file ?: return
        val line = "${fmt.format(Date())} $msg\n"
        synchronized(lock) {
            try {
                if (f.length() > MAX) {
                    val old = File(f.parentFile, "e2stream.1.log")
                    old.delete()
                    f.renameTo(old)
                }
                FileWriter(f, true).use { it.write(line) }
                dirty = true
            } catch (e: Exception) {
                Log.w(TAG, "log write failed", e)
            }
        }
        if (mirror != null) mirrorQueue.offer(line)     // pełna kolejka (dysk stoi) — linia tylko w pamięci urządzenia
    }

    private fun mirrorLoop() {
        while (true) {
            try {
                val first = mirrorQueue.take()
                val m = mirror ?: continue
                val sb = StringBuilder(first)
                while (true) { val l = mirrorQueue.poll() ?: break; sb.append(l) }
                if (m.length() > MAX) {
                    val old = File(m.parentFile, "e2stream.1.log")
                    old.delete()
                    m.renameTo(old)
                }
                FileWriter(m, true).use { it.write(sb.toString()) }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { return }
            }
        }
    }

    /** Zawartość logu (poprzedni + bieżący plik). */
    fun snapshot(): ByteArray {
        val f = file ?: return ByteArray(0)
        synchronized(lock) {
            dirty = false
            val old = File(f.parentFile, "e2stream.1.log")
            val a = if (old.exists()) old.readBytes() else ByteArray(0)
            val b = if (f.exists()) f.readBytes() else ByteArray(0)
            return a + b
        }
    }

    fun markDirty() {
        dirty = true
    }
}
