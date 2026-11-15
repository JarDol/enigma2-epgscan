package pl.jardol.e2stream

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

data class StorageOption(val path: String, val label: String)

/**
 * Miejsca zapisu dostępne bez dodatkowych uprawnień: katalogi aplikacji na każdym
 * zamontowanym woluminie (pamięć wewnętrzna, karta SD, pendrive USB jako pamięć przenośna).
 * Na pendrive pliki lądują w Android/data/pl.jardol.e2stream/files/.
 */
object Storage {

    fun options(ctx: Context): List<StorageOption> {
        val out = ArrayList<StorageOption>()
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (d in ctx.getExternalFilesDirs(null)) {
            if (d == null) continue
            val desc = if (Build.VERSION.SDK_INT >= 24) {
                try { sm.getStorageVolume(d)?.getDescription(ctx) } catch (_: Exception) { null }
            } else null
            val removable = try { Environment.isExternalStorageRemovable(d) } catch (_: Exception) { false }
            val name = desc ?: ctx.getString(if (removable) R.string.storage_removable else R.string.storage_device)
            val free = d.usableSpace / 1_000_000
            out.add(StorageOption(d.absolutePath, ctx.getString(R.string.storage_free, name, free, d.absolutePath)))
        }
        out.add(StorageOption(ctx.filesDir.absolutePath, ctx.getString(R.string.storage_private, ctx.filesDir.absolutePath)))
        return out
    }

    fun dir(ctx: Context, prefs: Prefs): File {
        val p = prefs.storageDir
        if (p.isNotBlank()) {
            val f = File(p)
            // dysk odłączony (pusty punkt montowania) — zapis w pamięci urządzenia, do ponownego podłączenia
            if (mounted(f) && (f.isDirectory || f.mkdirs()) && f.canWrite()) return f
        }
        return ctx.getExternalFilesDir(null) ?: ctx.filesDir
    }

    private fun mounted(f: File): Boolean = try {
        f.absolutePath.startsWith("/data/") || Environment.getExternalStorageState(f) == Environment.MEDIA_MOUNTED
    } catch (_: Exception) { false }

    /**
     * Logi i zrzuty za dyskiem timeshiftu: po wybraniu dysku na timeshift (i przy starcie dla wcześniejszych
     * ustawień) miejsce logów przechodzi na ten dysk — chyba że użytkownik wybrał je ręcznie. Gdy timeshift
     * wraca do pamięci urządzenia, logi też. Odłączony dysk: [dir] zapisuje w pamięci urządzenia.
     */
    fun followTimeshift(prefs: Prefs): Boolean {
        val manual = prefs.storageDir.isNotBlank() && !prefs.storageDirAuto
        if (manual) return false
        val want = prefs.tsDir
        if (prefs.storageDir == want) return false
        prefs.storageDir = want
        prefs.storageDirAuto = want.isNotBlank()
        AppLog.i("logi i zrzuty: miejsce za dyskiem timeshiftu → ${want.ifBlank { "pamięć urządzenia" }}")
        return true
    }

    /** Usuwa najstarsze pliki o danym prefiksie, zostawiając [keep] najnowszych. */
    fun prune(dir: File, prefix: String, keep: Int) {
        val files = dir.listFiles { f -> f.name.startsWith(prefix) }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keep).forEach { it.delete() }
    }
}
