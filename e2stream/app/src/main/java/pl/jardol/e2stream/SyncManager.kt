package pl.jardol.e2stream

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cicha synchronizacja ustawień i danych kanałów z dekoderem (bez „mastera” — dekoder jest wspólnym miejscem).
 *
 * Na dekoderze (<katalog FTP>/sync/):
 *  - common.json          — wartości domyślne dla nowych odbiorników + wspólne PMT kanałów,
 *  - device-<id>.json     — pełne ustawienia każdego odbiornika (nazwa, model, wartości ze znacznikami czasu).
 *
 * Zasady:
 *  - po nowej instalacji import profilu wymaga decyzji użytkownika przed rozpoczęciem synchronizacji,
 *  - każda zmiana trafia do pliku odbiornika; jeśli w common.json nie ma takiego klucza — dopisuje go jako domyślny,
 *  - PMT kanałów zawsze wspólne (wygrywa nowszy wpis),
 *  - ścieżki dźwięku: brakujące lokalnie są przejmowane z części wspólnej (nowe kanały wybrane na innym TV),
 *    a wybór na tym odbiorniku zapisuje się w jego pliku.
 */
object SyncManager {
    private const val SUBDIR = "sync"
    private const val COMMON = "common.json"
    private const val LEGACY = "e2stream-channels.json"

    /** Ustawienia synchronizowane (bez adresu dekodera, ścieżek lokalnych i stanu). */
    private val SETTINGS = setOf(
        "httpPort", "streamPort", "user", "pass", "blockOnDisconnect", "appLanguage",
        "tunneling", "engine", "vlcDeint", "vlcHw", "vlcHwMode", "vlcChroma", "exoFps", "uiListFont", "uiListIcon", "uiEpgFont", "uiBannerFont", "uiBannerIcon", "watchdog", "stallSec", "bufferMs", "rebufferMs", "cushionSlow", "afrMode", "dropRestart",
        "audioLang", "preferAc3",
        "logFtp", "ftpUser", "ftpPass", "ftpDir", "saveTs", "tsRingMb", "tsFtp", "cleanTs",
        "hiddenBouquets", "pin", "pinMin", "invertUpDown", "bannerSec", "tsBannerSec",
        "channelListSec", "epgEventSec", "confirmDialogSec", "epgChannelSec", "epgMultiSec", "infoDialogSec", "hideInfoDialogs", "resumeLast",
        "startBouquet", "startChannel", "startChannelName", "autoUpdate", "keyMap", "keyMapNoBanner", "keyMapBanner",
        "epgDaily", "epgHour", "epgDays", "epgUpdateMode", "picons", "missingPiconLogo", "piconDir", "showStats",
    )

    /**
     * Ustawienia zależne od urządzenia (silnik, obraz, bufory, wygląd): tylko w pliku odbiornika,
     * nigdy jako wartości domyślne dla innych — nowy telewizor dostaje domyślne aplikacji.
     */
    private val DEVICE_ONLY = setOf(
        "engine", "tunneling", "vlcDeint", "vlcHw", "vlcHwMode", "vlcChroma", "exoFps",
        "uiListFont", "uiListIcon", "uiEpgFont", "uiBannerFont", "uiBannerIcon",
        "bufferMs", "rebufferMs", "cushionSlow", "afrMode", "stallSec", "dropRestart", "watchdog",
        "showStats", "saveTs", "tsRingMb", "tsFtp", "preferAc3", "blockOnDisconnect", "appLanguage",
    )

    /** Przy pierwszym uruchomieniu nie nadpisujemy tego, co użytkownik właśnie wpisał. */
    private val KEEP_ON_FIRST = setOf("ftpUser", "ftpPass", "ftpDir")

    fun isSetting(k: String) = k in SETTINGS
    fun isAudio(k: String) = k.startsWith("audio_")
    fun isPmt(k: String) = k.startsWith("pmts_")
    private fun isDeviceKey(k: String) = isSetting(k) || isAudio(k)

    private lateinit var app: Context
    private lateinit var sp: SharedPreferences
    private lateinit var meta: SharedPreferences
    private lateinit var backupKeys: Set<String>
    private val ui = Handler(Looper.getMainLooper())
    private val changeLock = Any()

    @Volatile private var applying = false
    @Volatile var running = false
        private set
    @Volatile var lastResult = ""
        private set

    /** Wywoływane (na wątku UI), gdy synchronizacja zmieniła ustawienia odtwarzania. */
    var onSettingsApplied: (() -> Unit)? = null

    private val soon = Runnable { syncAsync() }

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || applying) return@OnSharedPreferenceChangeListener
        if (isDeviceKey(key) || isPmt(key) || key in backupKeys) {
            synchronized(changeLock) {
                val timestamp = maxOf(System.currentTimeMillis(), meta.getLong("t_$key", 0) + 1)
                meta.edit().putLong("t_$key", timestamp).putBoolean("dirty", true)
                    .putLong("changeVersion", meta.getLong("changeVersion", 0) + 1).apply()
            }
            ui.removeCallbacks(soon)
            ui.postDelayed(soon, 5000)
        }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        sp = app.getSharedPreferences("e2stream", Context.MODE_PRIVATE)
        meta = app.getSharedPreferences("e2sync", Context.MODE_PRIVATE)
        backupKeys = SettingsBackup.values(Prefs(app)).keys
        if (!meta.contains("backupFlowEnrolled")) {
            val edit = meta.edit().putBoolean("backupFlowEnrolled", true)
            if (!meta.contains("backupDecision") &&
                (meta.getBoolean("initialized", false) || !sp.getString("host", "").isNullOrBlank())) {
                edit.putBoolean("backupDecision", true)
            }
            check(edit.commit()) { "Nie udało się zapisać stanu konfiguracji kopii" }
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    @SuppressLint("HardwareIds")
    fun deviceId(): String =
        (Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: Build.MODEL)
            .replace(Regex("[^A-Za-z0-9_-]"), "_")

    val dirty: Boolean get() = meta.getBoolean("dirty", false)
    val lastSyncAt: Long get() = meta.getLong("lastSync", 0)
    val needsBackupDecision: Boolean get() = !meta.getBoolean("backupDecision", false)

    fun finishBackupDecision(skipCommonAudio: Boolean = false) {
        meta.edit().putBoolean("backupDecision", true).putBoolean("freshBackupDecision", true)
            .putBoolean("skipCommonAudio", skipCommonAudio)
            .putBoolean("dirty", true).commit()
    }

    fun syncAsync(done: ((String) -> Unit)? = null) {
        if (!::app.isInitialized || running || needsBackupDecision) return
        val prefs = Prefs(app)
        if (!prefs.channelSync || prefs.host.isBlank()) return
        running = true
        Thread {
            val result = runCatching { sync(prefs) }
            val r = result.getOrElse { AppLanguage.wrap(app).getString(R.string.receiver_sync_error, it.message ?: it.toString()) }
            lastResult = r
            AppLog.i("synchronizacja: $r")
            running = false
            if (result.isSuccess && dirty) ui.postDelayed(soon, 5000)
            done?.let { cb -> ui.post { cb(r) } }
        }.start()
    }

    // ------------------------------------------------------------ wartości

    private fun toJson(v: Any?): Any? = when (v) {
        is Set<*> -> JSONArray(v.map { it.toString() })
        is Boolean, is Int, is Long, is String -> v
        else -> null
    }

    private fun put(e: SharedPreferences.Editor, k: String, v: Any?) {
        if (k == "appLanguage") {
            require(v is String && AppLanguage.isValid(v)) { "Nieprawidłowy język aplikacji w kopii: $v" }
        }
        when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putInt(k, v.toInt())
            is Double -> e.putInt(k, v.toInt())
            is String -> e.putString(k, v)
            is JSONArray -> e.putStringSet(k, (0 until v.length()).map { v.optString(it) }.toSet())
        }
    }

    private fun entry(v: Any?, t: Long) = JSONObject().put("v", toJson(v)).put("t", t)

    private fun dirPath(prefs: Prefs) = prefs.ftpDir.trimEnd('/') + "/" + SUBDIR

    private fun read(ftp: FtpSession, path: String): JSONObject? {
        val b = ftp.retr(path) ?: ftp.retr(path.trimStart('/')) ?: return null
        return try { JSONObject(b.toString(Charsets.UTF_8)) } catch (_: Exception) { null }
    }

    private fun upload(prefs: Prefs, name: String, js: JSONObject) {
        FtpUpload.upload(prefs.host, 21, prefs.ftpUser, prefs.ftpPass, dirPath(prefs), name,
            js.toString(1).toByteArray(Charsets.UTF_8))
    }

    // ------------------------------------------------------------ synchronizacja

    @Synchronized
    private fun sync(prefs: Prefs): String {
        val dir = dirPath(prefs)
        val devName = "device-${deviceId()}.json"
        var common: JSONObject? = null
        var dev: JSONObject? = null
        var legacy: JSONObject? = null
        // wtyczka EPG Scan na dekoderze — przez jej API (bez FTP); inaczej pliki przez FTP
        if (!prefs.pluginOk) PluginApi.check(prefs)
        val plugin = if (prefs.pluginOk) PluginApi(prefs) else null
        if (plugin != null) {
            val st = plugin.syncState(deviceId())
            common = st.optJSONObject("common")
            dev = st.optJSONObject("device")
        } else {
            FtpSession(prefs.host, 21, prefs.ftpUser, prefs.ftpPass).use { ftp ->
                common = read(ftp, "$dir/$COMMON")
                dev = read(ftp, "$dir/$devName")
                if (common == null) legacy = read(ftp, prefs.ftpDir.trimEnd('/') + "/" + LEGACY)
            }
        }
        val now = System.currentTimeMillis()
        val c = common ?: JSONObject()
        val cData = c.optJSONObject("data") ?: JSONObject().also { c.put("data", it) }
        // dane z 0.6.3 (wspólny plik kanałów) — przejmij do części wspólnej
        legacy?.optJSONObject("data")?.let { ld ->
            for (k in ld.keys()) if ((isPmt(k) || isAudio(k)) && !cData.has(k)) cData.put(k, entry(ld.optString(k), now))
        }
        val dData = dev?.optJSONObject("data")

        val first = !meta.getBoolean("initialized", false)
        val freshDecision = meta.getBoolean("freshBackupDecision", false)
        val local = sp.all
        var applied = 0
        var settingsApplied = false
        val metaEd = meta.edit()
        applying = true
        try {
            val ed = sp.edit()
            if (freshDecision) {
                dData?.let { data ->
                    for (k in data.keys()) {
                        val t = data.optJSONObject(k)?.optLong("t") ?: continue
                        metaEd.putLong("t_$k", maxOf(t, meta.getLong("t_$k", 0)))
                    }
                }
            } else if (first) {
                val configured = local.keys.count { isSetting(it) && it !in KEEP_ON_FIRST } >= 3
                if (configured && dData == null) {
                    // istniejąca instalacja sprzed synchronizacji: lokalne ustawienia są ważne
                    for (k in local.keys) if (isDeviceKey(k) || isPmt(k)) metaEd.putLong("t_$k", now)
                } else {
                    // nowy odbiornik: własny plik (reinstalacja) albo domyślne z części wspólnej
                    val src = dData ?: cData
                    for (k in src.keys()) {
                        if (!isDeviceKey(k) || k in KEEP_ON_FIRST) continue
                        if (dData == null && k in DEVICE_ONLY) continue   // z części wspólnej tylko ustawienia ogólne
                        val e = src.optJSONObject(k) ?: continue
                        put(ed, k, e.opt("v")); metaEd.putLong("t_$k", e.optLong("t")); applied++
                        if (isSetting(k)) settingsApplied = true
                    }
                }
            } else if (dData != null) {
                // własny plik na dekoderze nowszy (np. zmieniony z innej instalacji tego odbiornika)
                for (k in dData.keys()) {
                    if (!isDeviceKey(k)) continue
                    val e = dData.optJSONObject(k) ?: continue
                    if (e.optLong("t") > meta.getLong("t_$k", 0)) {
                        put(ed, k, e.opt("v")); metaEd.putLong("t_$k", e.optLong("t")); applied++
                        if (isSetting(k)) settingsApplied = true
                    }
                }
            }
            // wspólne: PMT (nowszy wygrywa) i ścieżki dźwięku brakujące lokalnie
            for (k in cData.keys()) {
                val e = cData.optJSONObject(k) ?: continue
                val lt = meta.getLong("t_$k", 0)
                if ((isPmt(k) && (k !in local || e.optLong("t") > lt)) ||
                    (isAudio(k) && k !in local && !freshDecision && !meta.getBoolean("skipCommonAudio", false))) {
                    put(ed, k, e.opt("v")); metaEd.putLong("t_$k", e.optLong("t")); applied++
                }
            }
            ed.commit()
        } finally {
            applying = false
        }
        metaEd.putBoolean("initialized", true).commit()

        // plik odbiornika: wszystkie jego ustawienia i ścieżki
        val changeVersion = synchronized(changeLock) { meta.getLong("changeVersion", 0) }
        val all = sp.all
        val myData = JSONObject()
        for ((k, v) in all) if (isDeviceKey(k)) {
            val j = toJson(v) ?: continue
            myData.put(k, JSONObject().put("v", j).put("t", meta.getLong("t_$k", 0)))
        }
        val backup = JSONObject()
        for ((k, v) in SettingsBackup.values(prefs)) backup.put(k, toJson(v))
        val device = JSONObject().put("id", deviceId()).put("name", prefs.displayName)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}").put("app", Tools.appVersion(app))
            .put("updated", now).put("backup", backup)
        val oldDevice = dev?.optJSONObject("device")
        val oldBackup = oldDevice?.optJSONObject("backup")
        val backupMissing = oldBackup == null || backup.keys().asSequence().any { !oldBackup.has(it) }
        val preserved = if (freshDecision) {
            val saved = meta.getString("backupBeforeFirstPush", null)
            val savedRecord = saved?.let { JSONObject(it) }
            if (savedRecord?.optString("host") == prefs.host) savedRecord else JSONObject().also { record ->
                record.put("host", prefs.host)
                oldDevice?.optJSONObject("backup")?.let { record.put("backup", it) }
                dData?.let { record.put("data", it) }
                check(meta.edit().putString("backupBeforeFirstPush", record.toString()).commit()) {
                    "Nie udało się zabezpieczyć poprzedniej kopii"
                }
            }
        } else null
        val previousBackup = if (freshDecision) preserved?.optJSONObject("backup")
            else oldDevice?.optJSONObject("previousBackup")
        val previousData = if (freshDecision) preserved?.optJSONObject("data")
            else oldDevice?.optJSONObject("previousData")
        if (previousBackup != null) device.put("previousBackup", previousBackup)
        if (previousData != null) device.put("previousData", previousData)
        val myFile = JSONObject()
            .put("device", device)
            .put("data", myData)

        // część wspólna: PMT (nowszy wygrywa), pozostałe tylko gdy brak klucza (domyślne dla nowych)
        var commonChanged = common == null || legacy != null
        for ((k, v) in all) {
            val t = meta.getLong("t_$k", 0)
            val j = toJson(v) ?: continue
            val has = cData.optJSONObject(k)
            val write = when {
                isPmt(k) -> has == null || t > has.optLong("t")
                isDeviceKey(k) -> has == null && k !in DEVICE_ONLY
                else -> false
            }
            if (write) { cData.put(k, entry(v, t)); commonChanged = true }
        }

        if (plugin != null) {
            // wtyczka scala po swojej stronie: plik odbiornika + PMT do części wspólnej
            if (first || dirty || dev == null || backupMissing || applied > 0 || commonChanged) {
                val pmt = JSONObject()
                for ((k, v) in all) if (isPmt(k)) toJson(v)?.let { pmt.put(k, JSONObject().put("v", it).put("t", meta.getLong("t_$k", 0))) }
                plugin.syncPush(deviceId(), JSONObject().put("device", myFile).put("common_add", pmt))
                val saved = plugin.syncState(deviceId()).optJSONObject("device")
                    ?.optJSONObject("device")?.optJSONObject("backup")
                    ?: throw java.io.IOException("Nie udało się potwierdzić zapisu kopii na dekoderze")
                for (k in backup.keys()) {
                    val expected = backup.get(k)
                    val actual = saved.opt(k)
                    val equal = if (expected is JSONArray && actual is JSONArray) {
                        (0 until expected.length()).map { expected.getString(it) }.toSet() ==
                            (0 until actual.length()).map { actual.getString(it) }.toSet()
                    } else expected == actual
                    if (!equal) throw java.io.IOException("Nie udało się potwierdzić ustawienia $k na dekoderze")
                }
            }
        } else {
            if (first || dirty || dev == null || backupMissing || applied > 0) upload(prefs, devName, myFile)
            if (commonChanged) {
                c.put("updated", now)
                upload(prefs, COMMON, c)
            }
        }
        synchronized(changeLock) {
            val edit = meta.edit().putBoolean("freshBackupDecision", false)
                .remove("backupBeforeFirstPush").putLong("lastSync", now)
            if (meta.getLong("changeVersion", 0) == changeVersion) edit.putBoolean("dirty", false)
            edit.apply()
        }

        if (settingsApplied) ui.post { prefs.bump(); onSettingsApplied?.invoke() }
        val localized = AppLanguage.wrap(app)
        return localized.getString(R.string.receiver_sync_counts, applied, cData.length()) +
            if (first) localized.getString(R.string.receiver_sync_first) else ""
    }

    // ------------------------------------------------------------ profile innych odbiorników

    data class Profile(
        val file: String, val name: String, val model: String, val updated: Long,
        val data: JSONObject, val backup: JSONObject?,
    )

    fun profiles(prefs: Prefs): List<Profile> {
        val out = ArrayList<Profile>()
        if (prefs.pluginOk) {
            val arr = PluginApi(prefs).devices()
            for (i in 0 until arr.length()) {
                val js = arr.getJSONObject(i)
                val d = js.optJSONObject("device") ?: JSONObject()
                out.add(Profile(js.optString("file"), d.optString("name"), d.optString("model"), d.optLong("updated"),
                    js.optJSONObject("data") ?: JSONObject(), d.optJSONObject("backup")))
                if (d.has("previousBackup") || d.has("previousData")) {
                    out.add(Profile(js.optString("file") + "#previous", d.optString("name") + " (poprzednia kopia)",
                        d.optString("model"), d.optLong("updated"), d.optJSONObject("previousData") ?: JSONObject(),
                        d.optJSONObject("previousBackup")))
                }
            }
            return out.sortedByDescending { it.updated }
        }
        FtpSession(prefs.host, 21, prefs.ftpUser, prefs.ftpPass).use { ftp ->
            val dir = dirPath(prefs)
            val names = ftp.list(dir) ?: ftp.list(dir.trimStart('/')) ?: return out
            for (n in names.filter { it.startsWith("device-") && it.endsWith(".json") }) {
                val js = read(ftp, "$dir/$n") ?: continue
                val d = js.optJSONObject("device") ?: JSONObject()
                out.add(Profile(n, d.optString("name"), d.optString("model"), d.optLong("updated"),
                    js.optJSONObject("data") ?: JSONObject(), d.optJSONObject("backup")))
                if (d.has("previousBackup") || d.has("previousData")) {
                    out.add(Profile(n + "#previous", d.optString("name") + " (poprzednia kopia)",
                        d.optString("model"), d.optLong("updated"), d.optJSONObject("previousData") ?: JSONObject(),
                        d.optJSONObject("previousBackup")))
                }
            }
        }
        return out.sortedByDescending { it.updated }
    }

    /** Przenosi ustawienia i ścieżki z profilu innego odbiornika na ten (jako nowe zmiany). */
    fun restore(p: Profile): Int {
        val ed = sp.edit()
        var n = 0
        val before = Prefs(app)
        val defaults = SettingsBackup.values(Prefs(app))
        p.backup?.let { backup ->
            for (k in backup.keys()) {
                if (k == "host") continue
                val expected = defaults[k] ?: continue
                val value = backup.opt(k)
                val valid = when (expected) {
                    is Boolean -> value is Boolean
                    is Int -> value is Int
                    is String -> value is String
                    is Set<*> -> value is JSONArray && (0 until value.length()).all { value.opt(it) is String }
                    else -> false
                }
                require(valid) { "Nieprawidłowa wartość ustawienia $k w kopii" }
                put(ed, k, value)
                n++
            }
        }
        for (k in p.data.keys()) {
            if (!isDeviceKey(k)) continue
            if (p.backup?.has(k) == true) continue
            put(ed, k, p.data.optJSONObject(k)?.opt("v")); n++
        }
        if (p.backup?.has("pluginPort") == true && p.backup.opt("pluginPort") != before.pluginPort ||
            p.backup?.has("pluginToken") == true && p.backup.opt("pluginToken") != before.pluginToken) {
            ed.putBoolean("pluginOk", false)
        }
        check(ed.commit()) { "Nie udało się zapisać ustawień" }
        return n
    }
}
