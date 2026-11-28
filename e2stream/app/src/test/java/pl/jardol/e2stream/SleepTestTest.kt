package pl.jardol.e2stream

import android.app.Application
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDevicePolicyManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SleepTestTest {
    private lateinit var controller: ActivityController<SettingsActivity>
    private lateinit var activity: SettingsActivity
    private lateinit var policy: DevicePolicyManager
    private lateinit var keyguard: KeyguardManager

    @Before fun setUp() {
        AppState.recording = false
        org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        activity = controller.get()
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN, true)
        policy = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        Shadow.extract<ShadowDevicePolicyManager>(policy).setActiveAdmin(ComponentName(activity, SleepTestAdminReceiver::class.java))
    }

    @After fun tearDown() {
        AppState.recording = false
        TvDialog.dismissAll()
        controller.pause().stop().destroy()
    }

    private fun call(name: String) = SettingsActivity::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)

    private fun waitSeconds(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS)

    @Test fun idleSleepMenuRequestsPermissionAndAllowsRevocation() {
        SettingsActivity::class.java.getDeclaredMethod("openSection", String::class.java)
            .apply { isAccessible = true }.invoke(activity, "Ogólne")
        val list = activity.findViewById<ListView>(R.id.settingsList)
        val index = (0 until list.count).first {
            list.adapter.getView(it, null, list).findViewById<TextView>(R.id.label)?.text ==
                activity.getString(R.string.sleep_device_title)
        }
        val prefs = Prefs(activity)
        assertFalse(prefs.sleepDeviceOnIdle)
        policy.removeActiveAdmin(ComponentName(activity, SleepTestAdminReceiver::class.java))
        list.performItemClick(list.adapter.getView(index, null, list), index, index.toLong())
        TvDialog.top()!!.window!!.decorView.findViewWithTag<Button>("chip_0").performClick()
        val request = shadowOf(activity).nextStartedActivity
        assertEquals(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN, request.action)
        assertEquals(ComponentName(activity, SleepTestAdminReceiver::class.java),
            request.getParcelableExtra<ComponentName>(DevicePolicyManager.EXTRA_DEVICE_ADMIN))
        assertFalse(prefs.sleepDeviceOnIdle)
        Shadow.extract<ShadowDevicePolicyManager>(policy).setActiveAdmin(ComponentName(activity, SleepTestAdminReceiver::class.java))
        controller.pause().resume()
        assertTrue(prefs.sleepDeviceOnIdle)
        call("showSleepDeviceSettings")
        TvDialog.top()!!.window!!.decorView.findViewWithTag<Button>("chip_1").performClick()
        assertFalse(policy.isAdminActive(ComponentName(activity, SleepTestAdminReceiver::class.java)))
        assertFalse(prefs.sleepDeviceOnIdle)
    }

    @Test fun idleSleepRespectsOptInPermissionAndRecording() {
        assertEquals(SleepDevice.Result.DISABLED, SleepDevice.attempt(activity, false, false))
        assertEquals(SleepDevice.Result.RECORDING, SleepDevice.attempt(activity, true, true))
        assertFalse(keyguard.isKeyguardLocked)
        policy.removeActiveAdmin(ComponentName(activity, SleepTestAdminReceiver::class.java))
        assertEquals(SleepDevice.Result.PERMISSION_MISSING, SleepDevice.attempt(activity, true, false))
        assertFalse(keyguard.isKeyguardLocked)
        Shadow.extract<ShadowDevicePolicyManager>(policy).setActiveAdmin(ComponentName(activity, SleepTestAdminReceiver::class.java))
        assertEquals(SleepDevice.Result.LOCKED, SleepDevice.attempt(activity, true, false))
        assertTrue(keyguard.isKeyguardLocked)
    }

    @Test fun locksOnlyAfterCountdown() {
        call("startSleepTest")
        waitSeconds(9)
        assertFalse(keyguard.isKeyguardLocked)
        waitSeconds(1)
        assertTrue(keyguard.isKeyguardLocked)
    }

    @Test fun cancellingOrLeavingSettingsStopsCountdown() {
        call("startSleepTest")
        waitSeconds(3)
        TvDialog.top()!!.onBackPressed()
        waitSeconds(10)
        assertFalse(keyguard.isKeyguardLocked)
        call("startSleepTest")
        waitSeconds(3)
        controller.pause()
        waitSeconds(10)
        assertFalse(keyguard.isKeyguardLocked)
        controller.resume()
    }

    @Test fun activeRecordingAndRevokedPermissionBlockLock() {
        AppState.recording = true
        call("startSleepTest")
        waitSeconds(11)
        assertFalse(keyguard.isKeyguardLocked)
        AppState.recording = false
        call("startSleepTest")
        policy.removeActiveAdmin(ComponentName(activity, SleepTestAdminReceiver::class.java))
        waitSeconds(11)
        assertFalse(keyguard.isKeyguardLocked)
        Shadow.extract<ShadowDevicePolicyManager>(policy).setActiveAdmin(ComponentName(activity, SleepTestAdminReceiver::class.java))
        call("startSleepTest")
        AppState.recording = true
        waitSeconds(11)
        assertFalse(keyguard.isKeyguardLocked)
    }
}
