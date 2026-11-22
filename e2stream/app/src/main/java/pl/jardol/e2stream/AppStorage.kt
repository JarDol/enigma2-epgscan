package pl.jardol.e2stream

import android.content.Context
import java.io.File

/**
 * Zajętość pamięci przez aplikację i sprzątanie tego, co da się bezpiecznie usunąć.
 * Ustawienia (shared_prefs) i EPG/picony nie są tu nigdy usuwane — tylko pamięć podręczna
 * (pobrane pliki aktualizacji, tymczasowa paczka EPG).
 */
object AppStorage {
    private const val DAY = 86_400_000L

    fun size(f: File?): Long {
        if (f == null || !f.exists()) return 0
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { size(it) } ?: 0
    }

    private fun cacheDirs(ctx: Context) = listOfNotNull(ctx.cacheDir, ctx.externalCacheDir).distinct()
    private fun isUpdateApk(file: File) = file.isFile && file.name.startsWith("e2stream-") &&
        (file.name.endsWith(".apk") || file.name.endsWith(".apk.part"))

    /** Po udanej aktualizacji usuwa APK-y po instalatorze; nie czeka na ponowne uruchomienie aplikacji. */
    fun cleanupInstalledApks(ctx: Context): Long {
        var freed = 0L
        for (dir in cacheDirs(ctx)) {
            for (file in dir.listFiles()?.filter { isUpdateApk(it) }.orEmpty()) {
                val size = file.length()
                if (file.delete()) freed += size
                else AppLog.i("aktualizacja: nie można usunąć pobranego APK ${file.name}")
            }
        }
        return freed
    }

    /**
     * Przy starcie: usuwa pobrane pliki aktualizacji (e2stream-*.apk ~100 MB każdy — wcześniej zostawały po każdej
     * aktualizacji) i pliki pamięci podręcznej starsze niż dzień. Zwraca zwolnione bajty.
     */
    fun cleanup(ctx: Context, keepApk: File? = null): Long {
        var freed = 0L
        val now = System.currentTimeMillis()
        for (d in cacheDirs(ctx)) {
            for (f in d.walkBottomUp()) {
                if (f == d || f == keepApk) continue
                // pobrany APK: nie ten właśnie instalowany (ostatnie 10 min)
                val apk = isUpdateApk(f) && now - f.lastModified() > 10 * 60_000L
                val old = now - f.lastModified() > DAY
                if (f.isFile && (apk || old)) {
                    val n = f.length()
                    if (f.delete()) freed += n
                } else if (f.isDirectory && old) f.delete()   // tylko puste
            }
        }
        return freed
    }

    /** Ręcznie (ustawienia): cała pamięć podręczna — bezpieczne, ustawienia zostają. */
    fun clearCache(ctx: Context): Long {
        var freed = 0L
        for (d in cacheDirs(ctx)) {
            for (f in d.walkBottomUp()) {
                if (f == d) continue
                val n = if (f.isFile) f.length() else 0
                if (f.delete()) freed += n
            }
        }
        return freed
    }

    data class Part(val label: String, val bytes: Long, val path: String)

    /** Podział zajętości: ustawienia, EPG, picony, pamięć podręczna, logi i zrzuty, timeshift, nagrania lokalne. */
    fun report(ctx: Context, prefs: Prefs): List<Part> {
        val data = ctx.filesDir.parentFile
        val logs = Storage.dir(ctx, prefs)
        val tsDir = TimeshiftBuffer.dir(ctx, prefs)
        val rec = tsDir?.let { LocalRecordings.base(it) }
        val out = ArrayList<Part>()
        out += Part("ustawienia", size(File(data, "shared_prefs")), File(data, "shared_prefs").absolutePath)
        out += Part("EPG", size(File(ctx.filesDir, "epg")), File(ctx.filesDir, "epg").absolutePath)
        out += Part("picony", size(File(ctx.filesDir, "picons")), File(ctx.filesDir, "picons").absolutePath)
        out += Part("pamięć podręczna", cacheDirs(ctx).sumOf { size(it) }, cacheDirs(ctx).joinToString(", ") { it.absolutePath })
        // logi i zrzuty: tylko pliki w katalogu logów (bez podkatalogów timeshift/nagrania, liczonych osobno)
        out += Part("logi i zrzuty", logs.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0, logs.absolutePath)
        if (tsDir != null) out += Part("timeshift", size(tsDir), tsDir.absolutePath)
        if (rec != null) out += Part("nagrania lokalne", size(rec), rec.absolutePath)
        return out
    }

    fun mb(bytes: Long) = String.format(java.util.Locale("pl"), "%.1f MB", bytes / 1e6)

    fun reportLine(parts: List<Part>) = parts.joinToString(", ") { "${it.label} ${mb(it.bytes)}" }
}
