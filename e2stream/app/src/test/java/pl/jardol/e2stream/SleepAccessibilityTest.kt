package pl.jardol.e2stream

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SleepAccessibilityTest {
    private lateinit var activityController: ActivityController<SettingsActivity>
    private lateinit var activity: SettingsActivity
    private lateinit var serviceController: ServiceController<SleepAccessibilityTestService>
    private lateinit var service: SleepAccessibilityTestService
    private lateinit var shadowService: ShadowAccessibilityService

    @Before fun setUp() {
        org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        AppState.recording = false
        activityController = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        activity = activityController.get()
        serviceController = Robolectric.buildService(SleepAccessibilityTestService::class.java).create()
        service = serviceController.get()
        shadowService = Shadow.extract(service)
    }

    @After fun tearDown() {
        AppState.recording = false
        TvDialog.dismissAll()
        serviceController.destroy()
        activityController.pause().stop().destroy()
    }

    private fun call(name: String) = SettingsActivity::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)

    private fun connect() = SleepAccessibilityTestService::class.java.getDeclaredMethod("onServiceConnected")
        .apply { isAccessible = true }.invoke(service)

    private fun waitSeconds(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS)

    @Test fun menuOpensSettingsAndLockRunsAfterCountdown() {
        assumeTrue(BuildConfig.BETA)
        SettingsActivity::class.java.getDeclaredMethod("openSection", String::class.java)
            .apply { isAccessible = true }.invoke(activity, "Ogólne")
        val list = activity.findViewById<ListView>(R.id.settingsList)
        val index = (0 until list.count).first {
            list.adapter.getView(it, null, list).findViewById<TextView>(R.id.label)?.text ==
                activity.getString(R.string.sleep_accessibility_title)
        }
        list.performItemClick(list.adapter.getView(index, null, list), index, index.toLong())
        TvDialog.top()!!.window!!.decorView.findViewWithTag<Button>("chip_0").performClick()
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        connect()
        call("showSleepAccessibilityTest")
        TvDialog.top()!!.window!!.decorView.findViewWithTag<Button>("chip_1").performClick()
        waitSeconds(9)
        assertTrue(shadowService.globalActionsPerformed.isEmpty())
        waitSeconds(1)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN), shadowService.globalActionsPerformed)
    }

    @Test fun cancelledDisconnectedOrRecordingNeverLocks() {
        connect()
        call("startSleepAccessibilityTest")
        waitSeconds(3)
        TvDialog.top()!!.onBackPressed()
        waitSeconds(10)
        assertTrue(shadowService.globalActionsPerformed.isEmpty())
        call("startSleepAccessibilityTest")
        AppState.recording = true
        waitSeconds(10)
        assertTrue(shadowService.globalActionsPerformed.isEmpty())
        AppState.recording = false
        call("startSleepAccessibilityTest")
        service.onUnbind(Intent())
        waitSeconds(10)
        assertTrue(shadowService.globalActionsPerformed.isEmpty())
    }

    @Test fun leavingSettingsCancelsCountdown() {
        connect()
        call("startSleepAccessibilityTest")
        waitSeconds(3)
        activityController.pause()
        waitSeconds(10)
        assertTrue(shadowService.globalActionsPerformed.isEmpty())
        activityController.resume()
    }
}
