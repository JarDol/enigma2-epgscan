package pl.jardol.e2stream

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Dla APK Updatera (JarDol/apk-updater):
 *  - content://pl.jardol.e2stream.updateinfo/settings  →  kolumny "auto": 1 = tak, 0 = nie; "channel": stable / beta.
 *  - content://pl.jardol.e2stream.updateinfo/logs      →  lista plików logu: "name", "size", "modified" (ms),
 *    content://pl.jardol.e2stream.updateinfo/logs/<nazwa>  →  plik do odczytu.
 *    Logi tylko dla APK Updatera (sprawdzany pakiet wywołującego) — działa także, gdy główne okno apki się nie otwiera.
 */
class UpdateInfoProvider : ContentProvider() {

    override fun onCreate() = true

    private fun logsAllowed() = callingPackage == UPDATER

    private fun logFiles() = context?.let { ctx ->
        if (AppLog.dir() == null) AppLog.init(ctx)
        AppLog.files()
    } ?: emptyList()

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (uri.pathSegments.firstOrNull() == "logs") {
            val cols = arrayOf("name", "size", "modified")
            val c = MatrixCursor(cols)
            if (!logsAllowed()) return c
            for (f in logFiles()) c.addRow(arrayOf<Any>(f.name, f.length(), f.lastModified()))
            return c
        }
        val cols = arrayOf("auto", "channel")
        val ctx = context ?: return MatrixCursor(cols)
        val p = Prefs(ctx)
        // kanał automatycznej aktualizacji apki — APK Updater instaluje sam tylko wydania z tego kanału
        return MatrixCursor(cols).apply { addRow(arrayOf<Any>(if (p.autoUpdate) 1 else 0, p.updateChannel)) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val seg = uri.pathSegments
        if (seg.size != 2 || seg[0] != "logs" || !logsAllowed()) throw FileNotFoundException(uri.toString())
        val f = logFiles().firstOrNull { it.name == seg[1] } ?: throw FileNotFoundException(seg[1])
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = if (uri.pathSegments.size == 2) "text/plain" else null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object { const val UPDATER = "pl.jardol.apkupdater" }
}
