package pl.jardol.e2stream

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Ustawienie „Automatyczna aktualizacja” dla APK Updatera (JarDol/apk-updater).
 * content://pl.jardol.e2stream.updateinfo/settings  →  kolumna "auto": 1 = tak, 0 = nie.
 * Tylko odczyt jednej wartości tak/nie, bez danych prywatnych.
 */
class UpdateInfoProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val ctx = context ?: return MatrixCursor(arrayOf("auto"))
        return MatrixCursor(arrayOf("auto")).apply { addRow(arrayOf<Any>(if (Prefs(ctx).autoUpdate) 1 else 0)) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
