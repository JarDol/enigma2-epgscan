package pl.jardol.e2stream.desktop

import org.json.JSONArray
import org.json.JSONObject

/** Profil odbiornika zapisany przez wtyczkę EPG Scan (plik device-<id>.json na dekoderze). */
internal data class DeviceProfile(
    val file: String,
    val name: String,
    val model: String,
    val app: String,
    val updated: Long,
    val backup: JSONObject?,
) {
    val isWindows: Boolean get() = file.startsWith("device-win-") || backup?.has(SettingsBackup.WINDOWS) == true
}

internal fun parseDeviceProfiles(devices: JSONArray): List<DeviceProfile> =
    (0 until devices.length()).mapNotNull { index ->
        val json = devices.optJSONObject(index) ?: return@mapNotNull null
        val device = json.optJSONObject("device") ?: JSONObject()
        DeviceProfile(
            file = json.optString("file"),
            name = device.optString("name"),
            model = device.optString("model"),
            app = device.optString("app"),
            updated = device.optLong("updated"),
            backup = device.optJSONObject("backup"),
        )
    }.sortedByDescending { it.updated }

/**
 * Kopia ustawień w profilu odbiornika na dekoderze — zgodna z Androidem: wspólne ustawienia pod nazwami
 * i typami z aplikacji Android (Android przenosi je z kopii Windows, a Windows z kopii Androida), a pełne
 * ustawienia Windows w obiekcie [WINDOWS] (Android go pomija).
 */
internal object SettingsBackup {
    const val WINDOWS = "windows"

    /** Ustawienia zależne od komputera albo stanu — nie trafiają do kopii ani z niej nie wracają. */
    private val excluded = setOf(
        "host", "lastBouquetRef", "lastChannelRef", "tsDir", "piconDir", "logDir", "lastPiconFetchDay",
        "lastEpgFetch", "epgPackIdentity", "epgPackCheckAt", "backupDecision", "recLocalDir", "lastUpdateCheck",
    )

    // nazwa w Androidzie → nazwa w Windows
    private val ints = linkedMapOf(
        "httpPort" to "httpPort", "streamPort" to "streamPort", "pluginPort" to "pluginPort",
        "bufferMs" to "bufferMs", "uiListFont" to "uiListFont", "uiListIcon" to "uiListIcon",
        "uiEpgFont" to "uiEpgFont", "uiBannerFont" to "uiBannerFont", "uiBannerIcon" to "uiBannerIcon",
        "bannerSec" to "bannerSec", "channelListSec" to "channelListSec", "pinMin" to "pinMin",
        "tsMin" to "tsMin", "tsStepSec" to "tsStepSec", "tsStepMin" to "tsStepMin", "tsBannerSec" to "tsBannerSec",
        "epgDays" to "epgDays", "confirmDialogSec" to "confirmDialogSec", "epgChannelSec" to "epgChannelSec",
        "epgMultiSec" to "epgMultiSec", "infoDialogSec" to "infoDialogSec", "zapHistoryDays" to "zapHistoryDays",
        "epgEventSec" to "actionDialogSec", "stallSec" to "stallSec",
    )
    private val strings = linkedMapOf(
        "pluginToken" to "pluginToken", "user" to "username", "pass" to "password",
        "ftpUser" to "ftpUser", "ftpPass" to "ftpPass", "ftpDir" to "ftpDir", "audioLang" to "audioLang",
        "pin" to "pin", "epgHour" to "epgHour", "epgUpdateMode" to "epgUpdateMode", "appLanguage" to "appLanguage",
        "startBouquet" to "startBouquetRef", "startChannel" to "startChannelRef", "startChannelName" to "startChannelName",
    )
    private val bools = linkedMapOf(
        "resumeLast" to "resumeLast", "invertUpDown" to "invertUpDown", "tsBackLive" to "tsBackLive",
        "epgDaily" to "epgDaily", "picons" to "picons", "missingPiconLogo" to "missingPiconLogo",
        "showStats" to "showStats", "logFtp" to "logFtp", "hideInfoDialogs" to "hideInfoDialogs",
        "watchdog" to "watchdog",
    )

    fun backup(config: DesktopConfig): JSONObject {
        val properties = config.toProperties()
        val json = JSONObject().put("host", config.host)
        for ((android, windows) in ints) properties.getProperty(windows)?.toIntOrNull()?.let { json.put(android, it) }
        for ((android, windows) in strings) json.put(android, properties.getProperty(windows).orEmpty())
        for ((android, windows) in bools) properties.getProperty(windows)?.toBooleanStrictOrNull()?.let { json.put(android, it) }
        json.put("hiddenBouquets", JSONArray(config.hiddenBouquets.sorted()))
        val all = JSONObject()
        for (name in properties.stringPropertyNames().sorted()) {
            if (name !in excluded) all.put(name, properties.getProperty(name))
        }
        json.put(WINDOWS, all)
        return json
    }

    data class Restored(val config: DesktopConfig, val count: Int)

    /** Ustawienia z kopii; adres dekodera zostaje bieżący. Wartości są sprawdzane jak przy odczycie pliku ustawień. */
    fun restore(current: DesktopConfig, backup: JSONObject): Restored {
        val properties = current.toProperties()
        var count = 0
        val windows = backup.optJSONObject(WINDOWS)
        if (windows != null) {
            for (name in windows.keys()) {
                if (name in excluded) continue
                val value = windows.opt(name)
                if (value is String) {
                    properties.setProperty(name, value)
                    count++
                }
            }
        } else {
            for ((android, name) in ints) {
                val value = backup.opt(android)
                if (value is Number) { properties.setProperty(name, value.toInt().toString()); count++ }
            }
            for ((android, name) in strings) {
                val value = backup.opt(android)
                if (value is String) { properties.setProperty(name, value); count++ }
            }
            for ((android, name) in bools) {
                val value = backup.opt(android)
                if (value is Boolean) { properties.setProperty(name, value.toString()); count++ }
            }
            backup.optJSONArray("hiddenBouquets")?.let { array ->
                val refs = (0 until array.length()).mapNotNull { array.opt(it) as? String }
                properties.setProperty("hiddenBouquets", JSONArray(refs.sorted()).toString())
                count++
            }
        }
        val restored = DesktopConfig.fromProperties(properties).copy(host = current.host, backupDecision = true)
        return Restored(restored, count)
    }
}
