package pl.jardol.e2stream

import android.app.Application
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Looper
import android.os.Environment
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowEnvironment
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TimeshiftSetupTest {
    private lateinit var controller: ActivityController<PlayerActivity>
    private lateinit var activity: PlayerActivity
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        PinLock.lock()
        controller = Robolectric.buildActivity(PlayerActivity::class.java).create()
        activity = controller.get()
        prefs = Prefs(activity)
        activity.findViewById<View>(R.id.panel).visibility = View.GONE
        activity.findViewById<View>(R.id.banner).visibility = View.VISIBLE
    }

    @After
    fun tearDown() {
        ShadowDialog.getLatestDialog()?.dismiss()
        controller.destroy()
        PinLock.lock()
    }

    private fun longOk() {
        assertTrue(activity.onKeyLongPress(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        ))
    }

    private fun dialogMessage(): String =
        ShadowDialog.getLatestDialog().window!!.decorView
            .findViewWithTag<TextView>("dialog-message").text.toString()

    @Test
    fun pcrFallbackIsConnectedForPlaybackBufferAndTimeshiftIndependently() {
        prefs.tsDir = ""
        prefs.tsInternalOk = true
        val analyzer = TsAnalyzer(0) {}
        TsAnalyzer::class.java.getDeclaredField("pcrPid").apply { isAccessible = true }.setInt(analyzer, 0x1fff)
        TsAnalyzer::class.java.getDeclaredField("programPids").apply { isAccessible = true }.set(analyzer, setOf(0x100))
        PlayerActivity::class.java.getDeclaredField("analyzer").apply { isAccessible = true }.set(activity, analyzer)
        val field = PlayerActivity::class.java.getDeclaredField("tsBuf").apply { isAccessible = true }
        val newChannel = PlayerActivity::class.java.getDeclaredMethod("tsNewChannel").apply { isAccessible = true }
        val packet = ByteArray(188) { 0xff.toByte() }.apply {
            this[0] = 0x47; this[1] = 0x01; this[2] = 0
            this[3] = 0x30; this[4] = 7; this[5] = 0x10
        }
        for ((minutes, playback) in listOf(0 to true, 15 to false)) {
            prefs.tsMin = minutes
            prefs.bufPlay = playback
            newChannel.invoke(activity)
            val buffer = field.get(activity) as TimeshiftBuffer
            buffer.write(packet, 0, packet.size)
            assertEquals(188L, buffer.total)
            buffer.close()
            field.set(activity, null)
        }
    }

    @Test
    fun storageGuardNotifiesAfterClosingBackgroundAndWarnsIfCurrentStillStalls() {
        val medium = activity.cacheDir.resolve("recording-medium")
        prefs.setTimeshiftStorage(medium.absolutePath, "Philips USB — Nagrania")
        prefs.messageBar = false
        val buffer = TimeshiftBuffer(medium.resolve("timeshift"), 5)
        val health = TimeshiftBuffer::class.java.getDeclaredField("writeHealth").apply { isAccessible = true }
            .get(buffer) as StorageWriteHealth
        health.begin(SystemClock.elapsedRealtime())
        PlayerActivity::class.java.getDeclaredField("tsBuf").apply { isAccessible = true }.set(activity, buffer)
        val background = TimeshiftBuffer(buffer.root.resolve("kanal-2"), 5, buffer.root)
        val analyzer = TsAnalyzer(0) {}
        val recorder = TimeshiftRecorder("http://unused", null, { analyzer }, {}, onFailure = {})
        val bgClass = PlayerActivity::class.java.declaredClasses.single { it.simpleName == "BgChan" }
        val channel = bgClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance("http://unused", "Kanał w tle", background, analyzer, recorder, ProgramJournal(), null, 0, emptyList<Channel>())
        @Suppress("UNCHECKED_CAST")
        val backgrounds = PlayerActivity::class.java.getDeclaredField("bgChans").apply { isAccessible = true }
            .get(activity) as MutableList<Any>
        backgrounds.add(channel)
        val schedule = PlayerActivity::class.java.getDeclaredMethod("scheduleStorageGuard").apply { isAccessible = true }
        val foreground = AppState.foreground
        try {
            AppState.foreground = true
            schedule.invoke(activity)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            val message = PlayerActivity::class.java.getDeclaredField("message").apply { isAccessible = true }
                .get(activity) as TextView
            assertTrue(backgrounds.isEmpty())
            assertEquals(View.VISIBLE, message.visibility)
            assertEquals(activity.getString(R.string.guard_closed, "Kanał w tle"), message.text.toString())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(View.VISIBLE, message.visibility)
            assertEquals(activity.getString(R.string.guard_check_storage, "Philips USB — Nagrania"), message.text.toString())
        } finally {
            AppState.foreground = foreground
        }
    }

    @Test
    fun noLocationOpensSettingsWithTimeshiftSelectedForBothEngines() {
        prefs.tsMin = 0
        prefs.tsDir = ""
        prefs.tsInternalOk = false
        longOk()
        assertEquals(activity.getString(R.string.player_ts_setup_no_location), dialogMessage())
        ShadowDialog.getLatestDialog().window!!.decorView.findViewWithTag<Button>("chip_0").performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(SettingsActivity::class.java.name, intent.component!!.className)
        assertTrue(intent.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_TIMESHIFT, false))

        for (engine in listOf("exo", "vlc")) {
            prefs.engine = engine
            val settingsController = Robolectric.buildActivity(SettingsActivity::class.java, intent).create()
            val settings = settingsController.get()
            val list = settings.findViewById<ListView>(R.id.settingsList)
            list.measure(
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            list.layout(0, 0, 1280, 720)
            val position = list.selectedItemPosition
            assertTrue(position >= 0)
            val row = list.adapter.getView(position, null, list)
            assertEquals(settings.getString(R.string.setting_timeshift),
                row.findViewById<TextView>(R.id.label).text.toString())
            settingsController.destroy()
        }
    }

    @Test
    fun disabledTimeshiftOffersSettingsEvenWhenLocationIsAllowed() {
        prefs.tsMin = 0
        prefs.tsDir = ""
        prefs.tsInternalOk = true
        longOk()
        assertEquals(activity.getString(R.string.player_ts_setup_disabled), dialogMessage())
    }

    @Test
    fun unavailableDiskReportsDisabledStateAndDoesNotOfferFallbackWithoutPermission() {
        prefs.tsDir = activity.cacheDir.resolve("not-a-directory").apply { writeText("test") }.absolutePath
        prefs.tsInternalOk = false
        prefs.tsMin = 0
        longOk()
        assertTrue(dialogMessage().contains(activity.getString(R.string.player_ts_setup_disk_unavailable)))
        assertTrue(dialogMessage().contains(activity.getString(R.string.player_ts_setup_disabled)))
        val close = ShadowDialog.getLatestDialog().window!!.decorView.findViewWithTag<Button>("chip_1")
        assertEquals(activity.getString(R.string.message_close), close.text.toString())
        ShadowDialog.getLatestDialog().dismiss()
        prefs.tsMin = 15
        longOk()
        assertEquals(activity.getString(R.string.player_ts_setup_disk_unavailable), dialogMessage())
        assertEquals(activity.getString(R.string.message_close),
            ShadowDialog.getLatestDialog().window!!.decorView.findViewWithTag<Button>("chip_1").text.toString())
    }

    @Test
    fun unavailableDiskAllowsExistingDeviceStorageFallback() {
        prefs.tsDir = activity.cacheDir.resolve("not-a-directory").apply { writeText("test") }.absolutePath
        prefs.tsInternalOk = true
        prefs.tsMin = 15
        longOk()
        assertEquals(activity.getString(R.string.player_ts_setup_disk_unavailable), dialogMessage())
        val proceed = ShadowDialog.getLatestDialog().window!!.decorView.findViewWithTag<Button>("chip_1")
        assertEquals(activity.getString(R.string.message_continue), proceed.text.toString())
        proceed.performClick()
        assertEquals(activity.getString(R.string.player_ts_live_empty),
            activity.findViewById<TextView>(R.id.bannerNow).text.toString())
    }

    @Test
    fun missingStorageRetriesThreeTimesThenMountStartsNewSeries() {
        val medium = activity.cacheDir.resolve("retry-medium")
        medium.writeText("not a directory")
        prefs.setTimeshiftStorage(medium.absolutePath, "USB")
        prefs.tsInternalOk = false
        prefs.tsMin = 15
        prefs.bufPlay = false
        prefs.messageBar = false
        ShadowEnvironment.setExternalStorageState(medium, Environment.MEDIA_MOUNTED)
        val fields = PlayerActivity::class.java
        fun field(name: String) = fields.getDeclaredField(name).apply { isAccessible = true }
        field("currentUrl").set(activity, "http://example.invalid/channel")
        fields.getDeclaredMethod("tsNewChannel").apply { isAccessible = true }.invoke(activity)
        val retry = field("tsStorageRetry").get(activity) as Runnable
        val main = shadowOf(Looper.getMainLooper())
        try {
            for (attempt in 1..3) {
                retry.run()
                val deadline = System.nanoTime() + 3_000_000_000L
                while (field("tsStorageRetryAttempts").getInt(activity) < attempt && System.nanoTime() < deadline) {
                    Thread.sleep(10)
                    main.idle()
                }
                assertEquals(attempt, field("tsStorageRetryAttempts").getInt(activity))
            }
            assertFalse(field("tsStorageRetryEnabled").getBoolean(activity))
            val message = field("message").get(activity) as TextView
            assertEquals(View.VISIBLE, message.visibility)
            assertTrue(message.text.toString().contains(activity.getString(R.string.player_ts_usb_unavailable)))
            assertNull(field("tsBuf").get(activity))

            assertTrue(medium.delete())
            assertTrue(medium.mkdir())
            val receiver = field("tsStorageReceiver").get(activity) as BroadcastReceiver
            receiver.onReceive(activity, Intent(Intent.ACTION_MEDIA_MOUNTED))
            assertTrue(field("tsStorageRetryEnabled").getBoolean(activity))
            assertEquals(0, field("tsStorageRetryAttempts").getInt(activity))
            retry.run()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (field("tsBuf").get(activity) == null && System.nanoTime() < deadline) {
                Thread.sleep(10)
                main.idle()
            }
            assertNotNull(field("tsBuf").get(activity))
            assertFalse(field("tsStorageRetryEnabled").getBoolean(activity))
        } finally {
            fields.getDeclaredMethod("tsStopAll", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, false)
        }
    }

    @Test
    fun storageRetryWaitsForRecordingAndRearmsAfterPlaybackStops() {
        val medium = activity.cacheDir.resolve("retry-unavailable")
        medium.writeText("not a directory")
        prefs.setTimeshiftStorage(medium.absolutePath, "USB")
        prefs.tsInternalOk = false
        prefs.tsMin = 15
        val fields = PlayerActivity::class.java
        fun field(name: String) = fields.getDeclaredField(name).apply { isAccessible = true }
        val newChannel = fields.getDeclaredMethod("tsNewChannel").apply { isAccessible = true }
        val stop = fields.getDeclaredMethod("tsStopAll", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        field("currentUrl").set(activity, "http://example.invalid/channel")
        newChannel.invoke(activity)
        try {
            field("recMode").setBoolean(activity, true)
            (field("tsStorageRetry").get(activity) as Runnable).run()
            assertEquals(0, field("tsStorageRetryAttempts").getInt(activity))
            assertNull(field("tsBuf").get(activity))
            field("recMode").setBoolean(activity, false)
            field("tsStorageRetryEnabled").setBoolean(activity, false)
            stop.invoke(activity, false)
            newChannel.invoke(activity)
            assertTrue(field("tsStorageRetryEnabled").getBoolean(activity))
            assertEquals(0, field("tsStorageRetryAttempts").getInt(activity))
        } finally {
            field("recMode").setBoolean(activity, false)
            stop.invoke(activity, false)
        }
    }

    @Test
    @Config(sdk = [34, 35])
    fun storageMountReceiverFollowsActivityLifecycle() {
        val safe = PlayerActivity::class.java.getDeclaredField("safeMode").apply { isAccessible = true }
        val registered = PlayerActivity::class.java.getDeclaredField("tsStorageReceiverRegistered").apply { isAccessible = true }
        safe.setBoolean(activity, true)
        controller.start().resume()
        assertTrue(registered.getBoolean(activity))
        controller.pause().stop()
        assertFalse(registered.getBoolean(activity))
        controller.restart().start().resume()
        assertTrue(registered.getBoolean(activity))
        controller.pause().stop()
        assertFalse(registered.getBoolean(activity))
    }

    @Test
    fun configuredTimeshiftOpensControlsWithoutDialog() {
        prefs.tsDir = ""
        prefs.tsInternalOk = true
        prefs.tsMin = 15
        longOk()
        assertTrue(ShadowDialog.getLatestDialog()?.isShowing != true)
        assertEquals(activity.getString(R.string.player_ts_live_empty),
            activity.findViewById<TextView>(R.id.bannerNow).text.toString())
    }

    @Test
    fun settingsStillRequiresPin() {
        prefs.pin = "1234"
        prefs.tsMin = 0
        prefs.tsDir = ""
        prefs.tsInternalOk = false
        longOk()
        ShadowDialog.getLatestDialog().window!!.decorView.findViewWithTag<Button>("chip_0").performClick()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(activity.getString(R.string.pin_locked),
            ShadowDialog.getLatestDialog().window!!.decorView.findViewWithTag<TextView>("title").text.toString())
        val pinDialog = ShadowDialog.getLatestDialog()
        for (key in listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4)) {
            pinDialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        }
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull(intent)
        assertTrue(intent.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_TIMESHIFT, false))
    }

    @Test
    fun hiddenBannerAndRecordingKeepQuickMenu() {
        activity.findViewById<View>(R.id.banner).visibility = View.GONE
        longOk()
        val menu = ShadowDialog.getLatestDialog()
        assertTrue(menu.isShowing)
        assertNull(menu.window!!.decorView.findViewWithTag<View>("dialog-message"))
        menu.dismiss()

        activity.findViewById<View>(R.id.banner).visibility = View.VISIBLE
        PlayerActivity::class.java.getDeclaredField("recMode").apply {
            isAccessible = true
            setBoolean(activity, true)
        }
        longOk()
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        assertNull(ShadowDialog.getLatestDialog().window!!.decorView.findViewWithTag<View>("dialog-message"))
    }
}
