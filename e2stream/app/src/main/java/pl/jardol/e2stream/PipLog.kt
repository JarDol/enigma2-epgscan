package pl.jardol.e2stream

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Osobny log PiP (obok e2stream.log): każda sesja z otwartym PiP w nowym pliku, trzymane 3 ostatnie
 * (e2stream-pip.log, .1, .2) — nie nadpisuje go zwykły log kolejnych uruchomień. Wpisy trafiają też do logu głównego.
 */
object PipLog {
    private const val KEEP = 3
    private const val MAX = 400_000L
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    private fun file(i: Int): File? = AppLog.dir()?.let { File(it, if (i == 0) "e2stream-pip.log" else "e2stream-pip.$i.log") }

    /** Nowa sesja: starsze pliki przesuwane, najstarszy usuwany. */
    fun newSession(header: String) {
        synchronized(lock) {
            try {
                file(KEEP - 1)?.delete()
                for (i in KEEP - 2 downTo 0) { val f = file(i) ?: return; if (f.exists()) f.renameTo(file(i + 1)!!) }
            } catch (_: Exception) {}
        }
        i("=== sesja PiP | $header")
    }

    fun i(msg: String) {
        AppLog.i("PiP: $msg")
        val f = file(0) ?: return
        synchronized(lock) {
            try {
                if (f.length() < MAX) FileWriter(f, true).use { it.write("${fmt.format(Date())} $msg\n") }
            } catch (_: Exception) {}
        }
    }

    /** Wszystkie zachowane sesje, od najstarszej. */
    fun snapshot(): ByteArray = synchronized(lock) {
        (KEEP - 1 downTo 0).mapNotNull { file(it)?.takeIf { f -> f.exists() }?.readBytes() }.fold(ByteArray(0)) { a, b -> a + b }
    }
}
