package pl.jardol.e2stream

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

/** Systemowe alarmy RTC_WAKEUP dla harmonogramu; aplikacja może zostać uruchomiona przez pełnoekranowe powiadomienie. */
object ZapWakeAlarms {
    private const val STORE = "zapwake"
    private const val CHANNEL = "zap_schedule"
    private const val ACTION_ZAP = "pl.jardol.e2stream.ZAP_ALARM"
    private const val EXTRA_KEY = "timer_key"

    fun canScheduleExact(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    fun canUseFullScreen(ctx: Context): Boolean = Build.VERSION.SDK_INT < 34 ||
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()

    fun setAppForeground(ctx: Context, foreground: Boolean) {
        ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putBoolean("foreground", foreground).apply()
    }

    private fun isAppForeground(ctx: Context) =
        ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).getBoolean("foreground", false)

    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = app.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet("keys", emptySet())?.toSet() ?: emptySet()
        val timers = if (Prefs(app).wakeForZap && canScheduleExact(app)) ZapTimers.list(app) else emptyList()
        val nextKeys = timers.map { it.key() }.toSet()

        for (key in previous - nextKeys) alarm.cancel(pending(app, key))
        for (timer in timers) {
            val intent = alarmIntent(app, timer)
            val pi = pending(app, timer.key(), intent)
            val at = (timer.begin * 1000 - 30_000).coerceAtLeast(System.currentTimeMillis() + 1000)
            try {
                if (Build.VERSION.SDK_INT >= 23) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else alarm.setExact(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e: SecurityException) {
                Log.w("E2Stream", "Nie można ustawić dokładnego alarmu ${timer.key()}", e)
            }
        }
        prefs.edit().putStringSet("keys", nextKeys).apply()
    }

    private fun alarmIntent(ctx: Context, timer: ZapTimers.Timer) = Intent(ctx, ZapAlarmReceiver::class.java).apply {
        action = ACTION_ZAP
        data = Uri.parse("e2stream://zap/${Uri.encode(timer.key())}")
        putExtra(EXTRA_KEY, timer.key())
        putExtra("ref", timer.ref)
        putExtra("channel", timer.channel)
        putExtra("title", timer.title)
        putExtra("begin", timer.begin)
        putExtra("end", timer.end)
        putExtra("eventId", timer.eventId)
    }

    private fun pending(ctx: Context, key: String, intent: Intent? = null): PendingIntent {
        val actual = intent ?: Intent(ctx, ZapAlarmReceiver::class.java).apply {
            action = ACTION_ZAP
            data = Uri.parse("e2stream://zap/${Uri.encode(key)}")
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, key.hashCode(), actual, flags)
    }

    fun cancelAll(ctx: Context) {
        val prefs = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val alarm = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val keys = prefs.getStringSet("keys", emptySet())?.toSet() ?: emptySet()
        for (key in keys) alarm.cancel(pending(ctx, key))
        prefs.edit().putStringSet("keys", emptySet()).apply()
    }

    internal fun isForeground(ctx: Context) = isAppForeground(ctx)

    internal fun showNotification(ctx: Context, intent: Intent, key: String, channel: String, title: String, begin: Long) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Harmonogram E2Stream", NotificationManager.IMPORTANCE_HIGH))
        }
        val launch = Intent(ctx, PlayerActivity::class.java).apply {
            action = "pl.jardol.e2stream.OPEN_ZAP_ALARM"
            data = intent.data
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(PlayerActivity.EXTRA_WAKE_TIMER_KEY, key)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val activity = PendingIntent.getActivity(ctx, key.hashCode(), launch, flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)
        val notification = builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(channel)
            .setContentText(title)
            .setWhen(begin * 1000)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_MAX)
            .setContentIntent(activity)
            .setFullScreenIntent(activity, true)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(key.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w("E2Stream", "Brak uprawnienia do powiadomienia harmonogramu", e)
        }
    }
}

class ZapAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return
        val prefs = Prefs(context)
        if (!prefs.wakeForZap || ZapWakeAlarms.isForeground(context)) return
        val key = intent.getStringExtra("timer_key") ?: return
        if (ZapTimers.list(context).none { it.key() == key }) return
        ZapWakeAlarms.showNotification(context, intent, key,
            intent.getStringExtra("channel") ?: "E2Stream",
            intent.getStringExtra("title") ?: "Zaplanowana audycja",
            intent.getLongExtra("begin", System.currentTimeMillis() / 1000))
    }
}

class ZapBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ZapWakeAlarms.sync(context)
        }
    }
}