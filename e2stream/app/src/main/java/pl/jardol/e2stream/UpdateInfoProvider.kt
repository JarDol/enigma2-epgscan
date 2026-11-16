package pl.jardol.e2stream

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Ustawienie „Automatyczna aktualizacja” dla APK Updatera (JarDol/apk-updater).
 * content://pl.jardol.e2stream.updateinfo/settings  →  kolumny "auto": 1 = tak, 0 = nie; "channel": stable / beta.
 * Tylko odczyt jednej wartości tak/nie, bez danych prywatnych.
 */
class UpdateInfoProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cols = arrayOf("auto", "channel")
        val ctx = context ?: return MatrixCursor(cols)
        val p = Prefs(ctx)
        // kanał automatycznej aktualizacji apki — APK Updater instaluje sam tylko wydania z tego kanału
        return MatrixCursor(cols).apply { addRow(arrayOf<Any>(if (p.autoUpdate) 1 else 0, p.updateChannel)) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
