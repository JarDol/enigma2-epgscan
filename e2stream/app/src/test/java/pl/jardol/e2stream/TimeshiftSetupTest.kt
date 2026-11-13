package pl.jardol.e2stream

import android.app.Application
import android.content.Context
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
