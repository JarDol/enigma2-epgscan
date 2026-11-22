package pl.jardol.e2stream.desktop

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsBackupTest {
    @Test
    fun windowsBackupRestoresEverythingExceptHostAndLocalPaths() {
        val source = DesktopConfig(
            host = "192.168.1.20",
            username = "root",
            password = "tajne",
            tsMin = 45,
            tsDir = "D:\\timeshift",
            windowMode = WindowMode.FULLSCREEN,
            universalRemoteBindings = "OK=K:13",
            hiddenBouquets = setOf("b1", "b2"),
            appVolume = 80,
            pipFrameMs = 3000,
            pipPos = 7,
        )
        val backup = SettingsBackup.backup(source)
        val current = DesktopConfig(host = "192.168.1.30", tsDir = "C:\\ts")
        val restored = SettingsBackup.restore(current, backup).config
        assertEquals("192.168.1.30", restored.host)
        assertEquals("C:\\ts", restored.tsDir)
        assertEquals(45, restored.tsMin)
        assertEquals(WindowMode.FULLSCREEN, restored.windowMode)
        assertEquals("OK=K:13", restored.universalRemoteBindings)
        assertEquals(setOf("b1", "b2"), restored.hiddenBouquets)
        assertEquals(80, restored.appVolume)
        assertEquals(3000, restored.pipFrameMs)
        assertEquals(7, restored.pipPos)
        assertEquals("tajne", restored.password)
        assertTrue(restored.backupDecision)
    }

    @Test
    fun backupUsesAndroidNamesAndTypes() {
        val backup = SettingsBackup.backup(DesktopConfig(username = "u", password = "p", actionDialogSec = 12, resumeLast = false))
        assertEquals("u", backup.get("user"))
        assertEquals("p", backup.get("pass"))
        assertEquals(12, backup.get("epgEventSec"))
        assertEquals(false, backup.get("resumeLast"))
        assertTrue(backup.get("hiddenBouquets") is JSONArray)
        assertFalse(backup.has("tsDir"))
        assertFalse(backup.getJSONObject(SettingsBackup.WINDOWS).has("tsDir"))
    }

    @Test
    fun androidBackupMapsSharedSettings() {
        val android = JSONObject()
            .put("host", "10.0.0.5")
            .put("user", "admin")
            .put("epgEventSec", 15)
            .put("bannerSec", 8)
            .put("invertUpDown", true)
            .put("startChannel", "1:0:19:1:1:1:C00000:0:0:0:")
            .put("hiddenBouquets", JSONArray(listOf("x")))
            .put("engine", "vlc")
            .put("tsDir", "/storage/usb")
        val (restored, count) = SettingsBackup.restore(DesktopConfig(host = "192.168.1.30"), android)
        assertEquals("192.168.1.30", restored.host)
        assertEquals("admin", restored.username)
        assertEquals(15, restored.actionDialogSec)
        assertEquals(8, restored.bannerSec)
        assertTrue(restored.invertUpDown)
        assertEquals("1:0:19:1:1:1:C00000:0:0:0:", restored.startChannelRef)
        assertEquals(setOf("x"), restored.hiddenBouquets)
        assertEquals("", restored.tsDir)
        assertEquals(6, count)
    }

    @Test
    fun invalidValuesFallBackToDefaults() {
        val android = JSONObject().put("bannerSec", 999).put("appLanguage", "xx")
        val restored = SettingsBackup.restore(DesktopConfig(), android).config
        assertEquals(30, restored.bannerSec)
        assertEquals(DesktopConfig().appLanguage, restored.appLanguage)
    }

    @Test
    fun profilesAreParsedNewestFirst() {
        val devices = JSONArray()
            .put(JSONObject().put("file", "device-android-MIBOX4-F532.json").put("device", JSONObject().put("name", "TV").put("updated", 1)))
            .put(JSONObject().put("file", "device-win-PC.json").put("device", JSONObject().put("name", "PC").put("updated", 2).put("backup", JSONObject())))
        val profiles = parseDeviceProfiles(devices)
        assertEquals(listOf("PC", "TV"), profiles.map { it.name })
        assertTrue(profiles[0].isWindows)
        assertFalse(profiles[1].isWindows)
    }
}
