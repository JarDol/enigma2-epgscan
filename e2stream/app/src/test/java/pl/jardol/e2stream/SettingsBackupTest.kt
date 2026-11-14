package pl.jardol.e2stream

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SettingsBackupTest {
    private lateinit var context: Context
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("e2sync", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        SyncManager.init(context)
    }

    @Test
    fun restoresLanguageAndRepresentativeDeviceSettings() {
        prefs.host = "receiver"
        prefs.appLanguage = AppLanguage.ENGLISH
        prefs.engine = "vlc"
        prefs.vlcHwMode = "dec"
        prefs.quickMenuOrder = listOf("search", "event")
        prefs.quickMenuVisible = setOf("search", "event")
        prefs.pin = "1234"
        prefs.picons = false
        prefs.wakeForZap = true
        val saved = JSONObject()
        for ((key, value) in SettingsBackup.values(prefs)) {
            saved.put(key, if (value is Set<*>) JSONArray(value.toList()) else value)
        }

        prefs.appLanguage = AppLanguage.POLISH
        prefs.engine = "exo"
        prefs.vlcHwMode = "off"
        prefs.quickMenuOrder = emptyList()
        prefs.quickMenuVisible = emptySet()
        prefs.pin = ""
        prefs.picons = true
        prefs.wakeForZap = false

        SyncManager.restore(SyncManager.Profile("device.json", "TV", "TV", 0, JSONObject(), saved))

        assertEquals("receiver", prefs.host)
        assertEquals(AppLanguage.ENGLISH, prefs.appLanguage)
        assertEquals("vlc", prefs.engine)
        assertEquals("dec", prefs.vlcHwMode)
        assertEquals(listOf("search", "event"), prefs.quickMenuOrder)
        assertEquals(setOf("search", "event"), prefs.quickMenuVisible)
        assertEquals("1234", prefs.pin)
        assertEquals(false, prefs.picons)
        assertEquals(true, prefs.wakeForZap)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidLanguageInBackup() {
        val saved = JSONObject().put("appLanguage", "unsupported")
        SyncManager.restore(SyncManager.Profile("device.json", "TV", "TV", 0, JSONObject(), saved))
    }

    @Test
    fun restoredLanguageSelectsTranslatedResources() {
        val saved = JSONObject().put("appLanguage", AppLanguage.ENGLISH)
        SyncManager.restore(SyncManager.Profile("device.json", "TV", "TV", 0, JSONObject(), saved))
        val english = AppLanguage.wrap(context)
        assertEquals("Application language", english.getString(R.string.app_language))
        assertEquals("  •  on now (50%, 10 min remaining)", english.getString(R.string.epg_progress, 50, 10))

        prefs.appLanguage = AppLanguage.POLISH
        val polish = AppLanguage.wrap(context)
        assertEquals("Język aplikacji", polish.getString(R.string.app_language))
        assertEquals("  •  trwa (50%, zostało 10 min)", polish.getString(R.string.epg_progress, 50, 10))
    }

    @Test
    fun snapshotRestoresEverySavedSettingAndKeepsSelectedHost() {
        prefs.host = "original"
        val expected = SettingsBackup.values(prefs)
        val saved = JSONObject()
        val edit = context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit()
        for ((key, value) in expected) {
            saved.put(key, if (value is Set<*>) JSONArray(value.toList()) else value)
            when (value) {
                is Boolean -> edit.putBoolean(key, !value)
                is Int -> edit.putInt(key, value + 1)
                is String -> edit.putString(key, if (key == "appLanguage") AppLanguage.ENGLISH else "changed")
                is Set<*> -> edit.putStringSet(key, setOf("changed"))
            }
        }
        edit.putString("host", "selected").commit()

        SyncManager.restore(SyncManager.Profile("device.json", "TV", "TV", 0, JSONObject(), saved))
        val restored = SettingsBackup.values(prefs)
        for ((key, value) in expected) {
            assertEquals(key, if (key == "host") "selected" else value, restored[key])
        }
    }

    @Test
    fun oldBackupLeavesMissingSettingsAndIgnoresUnknownKeys() {
        prefs.appLanguage = AppLanguage.ENGLISH
        prefs.engine = "vlc"
        val saved = JSONObject().put("pin", "1234").put("futureSetting", "ignored")
        SyncManager.restore(SyncManager.Profile("device.json", "TV", "TV", 0, JSONObject(), saved))
        assertEquals("1234", prefs.pin)
        assertEquals(AppLanguage.ENGLISH, prefs.appLanguage)
        assertEquals("vlc", prefs.engine)
        assertEquals(false, context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).contains("futureSetting"))
    }

    @Test
    fun additionalDiscoverySubnetIsBackedUpAndRestored() {
        prefs.discoverySubnet = "192.168.20.0/24"
        val saved = JSONObject().put("discoverySubnet", SettingsBackup.values(prefs)["discoverySubnet"])
        prefs.discoverySubnet = ""
        SyncManager.restore(SyncManager.Profile("device.json", "TV", "TV", 0, JSONObject(), saved))
        assertEquals("192.168.20.0/24", prefs.discoverySubnet)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidDiscoverySubnetInBackup() {
        val saved = JSONObject().put("discoverySubnet", "192.168.20.0/16")
        SyncManager.restore(SyncManager.Profile("device.json", "TV", "TV", 0, JSONObject(), saved))
    }

    @Test
    fun profileRestoreIgnoresLegacyPmtsButKeepsAudioAndLocalCache() {
        val shared = context.getSharedPreferences("e2stream", Context.MODE_PRIVATE)
        shared.edit().putString("pmts_10", "local-cache").commit()
        val data = JSONObject()
            .put("pmts_10", JSONObject().put("v", "old-profile-pmt").put("t", 1))
            .put("audio_channel", JSONObject().put("v", "42|pol|aac").put("t", 1))
        SyncManager.restore(SyncManager.Profile("device.json", "TV", "TV", 0, data, null))
        assertEquals("local-cache", shared.getString("pmts_10", ""))
        assertEquals("42|pol|aac", shared.getString("audio_channel", ""))
    }

    @Test
    fun oldProfilePmtTimestampDoesNotBlockLaterSharedUpdates() {
        val server = ServerSocket(0)
        try {
            prefs.host = "127.0.0.1"
            prefs.pluginPort = server.localPort
            prefs.pluginOk = true
            val shared = context.getSharedPreferences("e2stream", Context.MODE_PRIVATE)
            shared.edit().putString("pmts_10", "local-cache").commit()
            val backup = JSONObject()
            for ((key, value) in SettingsBackup.values(prefs)) {
                backup.put(key, if (value is Set<*>) JSONArray(value.toList()) else value)
            }
            val state = JSONObject()
                .put("common", JSONObject().put("data", JSONObject().put("pmts_10",
                    JSONObject().put("v", "shared").put("t", 150))))
                .put("device", JSONObject()
                    .put("device", JSONObject().put("backup", backup))
                    .put("data", JSONObject().put("pmts_10", JSONObject().put("v", "legacy").put("t", 200))))
            val responder = thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) { }
                    val body = state.toString().toByteArray()
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body,
                    )
                }
            }
            val meta = context.getSharedPreferences("e2sync", Context.MODE_PRIVATE)
            meta.edit().putBoolean("initialized", true).putBoolean("freshBackupDecision", true)
                .putBoolean("dirty", false).putLong("t_pmts_10", 150).commit()
            val sync = SyncManager::class.java.getDeclaredMethod("sync", Prefs::class.java)
            sync.isAccessible = true
            sync.invoke(SyncManager, prefs)
            responder.join(3000)
            assertEquals(150L, meta.getLong("t_pmts_10", 0))
            assertEquals("local-cache", shared.getString("pmts_10", ""))
        } finally {
            server.close()
        }
    }
}
