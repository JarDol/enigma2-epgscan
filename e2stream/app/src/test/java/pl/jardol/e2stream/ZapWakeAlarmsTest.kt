package pl.jardol.e2stream

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ZapWakeAlarmsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("zaptimers", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("zapwake", Context.MODE_PRIVATE).edit().clear().commit()
        AppState.foreground = false
    }

    private fun timer(offset: Long = 120): ZapTimers.Timer {
        val now = System.currentTimeMillis() / 1000
        return ZapTimers.Timer("1:0:1:123:456:", "Kanał", "Audycja", now + offset, now + offset + 600)
    }

    @Test
    fun wakeAlarmLeadTimeMatchesMonitorSetting() {
        Prefs(context).wakeForZap = true
        Prefs(context).wakeZapMonitorSeconds = 15
        val t = timer()
        ZapTimers.add(context, t)
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarm = shadowOf(manager).nextScheduledAlarm!!
        assertEquals((t.begin - 15) * 1000, alarm.alarmClockInfo!!.triggerTime)
    }

    @Test
    fun monitorLeadIsClampedAndZeroMeansNoLead() {
        val begin = 1000L
        assertEquals(1_000_000L, ZapWakeAlarms.alarmTriggerAt(begin, 0, 0))
        assertEquals(985_000L, ZapWakeAlarms.alarmTriggerAt(begin, 15, 0))
        assertEquals(700_000L, ZapWakeAlarms.alarmTriggerAt(begin, 999, 0))
        assertEquals(10_000L, ZapWakeAlarms.alarmTriggerAt(1, 30, 9_000))
    }

    @Test
    fun switchCanBeConsumedOnlyOnceAndExpiredTimersCannotBeConsumed() {
        val t = timer()
        ZapTimers.add(context, t)
        assertTrue(ZapTimers.completeSwitchIfPending(context, t.key()))
        assertFalse(ZapTimers.completeSwitchIfPending(context, t.key()))
        assertEquals(1, ZapTimers.history(context).count { it.timer.key() == t.key() })
        val expired = timer(-700)
        ZapTimers.add(context, expired)
        assertFalse(ZapTimers.completeSwitchIfPending(context, expired.key()))
    }

    @Test
    @Config(sdk = [34])
    fun backgroundAlarmLaunchesScheduledPlayerEvenAfterPreviousProcessDied() {
        Prefs(context).wakeForZap = true
        val t = timer(0)
        ZapTimers.add(context, t)
        context.getSharedPreferences("zapwake", Context.MODE_PRIVATE).edit().putBoolean("foreground", true).commit()
        ZapAlarmReceiver().onReceive(context, Intent().apply {
            putExtra("timer_key", t.key())
            putExtra("scheduled_at", t.begin * 1000)
            putExtra("channel", t.channel)
            putExtra("title", t.title)
            putExtra("begin", t.begin)
        })
        val launch = shadowOf(context).nextStartedActivity!!
        assertEquals(PlayerActivity.ACTION_SCHEDULE_ZAP, launch.action)
        assertEquals(t.key(), launch.getStringExtra(PlayerActivity.EXTRA_WAKE_TIMER_KEY))
        assertEquals(t.ref, launch.getStringExtra(PlayerActivity.EXTRA_SCHEDULE_REF))
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val fallback = shadowOf(notifications).getNotification(t.key().hashCode())
        assertNotNull(fallback)
        val fallbackIntent = shadowOf(fallback.fullScreenIntent).savedIntent
        assertEquals(t.key(), fallbackIntent.getStringExtra(PlayerActivity.EXTRA_WAKE_TIMER_KEY))
        assertEquals(t.ref, fallbackIntent.getStringExtra(PlayerActivity.EXTRA_SCHEDULE_REF))
        assertTrue(ZapTimers.list(context).any { it.key() == t.key() })
    }

    @Test
    fun alarmAtMonitorTimeLaunchesPlayerBeforeProgrammeStart() {
        Prefs(context).wakeForZap = true
        Prefs(context).wakeZapMonitorSeconds = 30
        val t = timer(120)
        ZapTimers.add(context, t)
        ZapAlarmReceiver().onReceive(context, Intent().apply {
            putExtra("timer_key", t.key())
            putExtra("scheduled_at", System.currentTimeMillis())
            putExtra("channel", t.channel)
            putExtra("title", t.title)
            putExtra("begin", t.begin)
        })
        val launch = shadowOf(context).nextStartedActivity!!
        assertEquals(PlayerActivity.ACTION_SCHEDULE_ZAP, launch.action)
        assertEquals(t.key(), launch.getStringExtra(PlayerActivity.EXTRA_WAKE_TIMER_KEY))
        assertTrue(ZapWakeAlarms.monitorWasReceived(context, t.key()))
    }

    @Test
    fun receivedMonitorIsNotRearmedButFinalWakeRemainsScheduled() {
        Prefs(context).wakeForZap = true
        Prefs(context).wakeZapMonitorSeconds = 30
        val t = timer(120)
        ZapTimers.add(context, t)
        ZapWakeAlarms.markMonitorReceived(context, t.key())
        ZapWakeAlarms.sync(context)
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertEquals(t.begin * 1000, shadowOf(manager).nextScheduledAlarm!!.alarmClockInfo!!.triggerTime)
    }

    @Test
    fun duplicateAndConsumedWakeIntentsDoNotQueueAnotherSwitch() {
        val prefs = Prefs(context)
        prefs.playbackAttempt = "unfinished / VLC"
        prefs.channelSync = false
        prefs.logFtp = false
        SyncManager.init(context)
        val t = timer(0)
        ZapTimers.add(context, t)
        val intent = ZapWakeAlarms.scheduledPlayerIntent(context, t)
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, intent).setup()
        val activity = controller.get()
        ShadowDialog.getLatestDialog().dismiss()
        val field = PlayerActivity::class.java.getDeclaredField("scheduledChannelKey").apply { isAccessible = true }
        assertEquals(t.key(), field.get(activity))
        controller.newIntent(intent)
        assertEquals(t.key(), field.get(activity))
        assertTrue(ZapTimers.completeSwitchIfPending(context, t.key()))
        field.set(activity, null)
        PlayerActivity::class.java.getDeclaredField("scheduledChannelRef").apply { isAccessible = true }.set(activity, null)
        controller.newIntent(intent)
        assertNull(field.get(activity))
        assertEquals(1, ZapTimers.history(context).count { it.timer.key() == t.key() })
        controller.pause().stop().destroy()
    }

    @Test
    fun playerAcceptsMonitorIntentBeforeProgrammeStart() {
        val prefs = Prefs(context)
        prefs.playbackAttempt = "unfinished / VLC"
        prefs.channelSync = false
        prefs.logFtp = false
        SyncManager.init(context)
        val controller = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        val activity = controller.get()
        ShadowDialog.getLatestDialog()?.dismiss()
        val t = timer(180)
        ZapTimers.add(context, t)
        val accepted = PlayerActivity::class.java.getDeclaredMethod("receiveScheduledIntent", Intent::class.java)
            .apply { isAccessible = true }
            .invoke(activity, ZapWakeAlarms.scheduledPlayerIntent(context, t)) as Boolean
        assertTrue(accepted)
        assertEquals(t.key(), PlayerActivity::class.java.getDeclaredField("scheduledChannelKey")
            .apply { isAccessible = true }.get(activity))
        controller.pause().stop().destroy()
    }
}
