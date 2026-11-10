package pl.jardol.e2stream

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log

/** Systemowe alarmy RTC_WAKEUP dla harmonogramu; aplikacja może zostać uruchomiona przez pełnoekranowe powiadomienie. */
object ZapWakeAlarms {
    private const val STORE = "zapwake"
    private const val CHANNEL = "zap_schedule"
    private const val ACTION_ZAP = "pl.jardol.e2stream.ZAP_ALARM"
    private const val ACTION_TEST = "pl.jardol.e2stream.ZAP_TEST_ALARM"
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
        val enabled = Prefs(app).wakeForZap
        val exactAllowed = canScheduleExact(app)
        val timers = if (enabled && exactAllowed) ZapTimers.list(app) else emptyList()
        val nextKeys = timers.map { it.key() }.toSet()

        AppLog.i("wybudzanie: sync enabled=$enabled exactAllowed=$exactAllowed timers=${timers.size}")

        for (key in previous - nextKeys) {
            alarm.cancel(pending(app, key))
            AppLog.i("wybudzanie: anulowano alarm $key")
        }
        for (timer in timers) {
            val intent = alarmIntent(app, timer)
            val pi = pending(app, timer.key(), intent)
            val at = (timer.begin * 1000 - 30_000).coerceAtLeast(System.currentTimeMillis() + 1000)
            try {
                if (Build.VERSION.SDK_INT >= 23) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else alarm.setExact(AlarmManager.RTC_WAKEUP, at, pi)
                Log.i("E2Stream", "Harmonogram alarm: ${timer.key()} ustawiony na $at")
                AppLog.i("wybudzanie: ustawiono alarm ${timer.key()} na $at")
            } catch (e: SecurityException) {
                Log.w("E2Stream", "Nie można ustawić dokładnego alarmu ${timer.key()}", e)
                AppLog.i("wybudzanie: błąd ustawienia ${timer.key()}: $e")
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

    fun notificationAllowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun diagnostics(ctx: Context): String {
        val next = ZapTimers.list(ctx).firstOrNull()
        return buildString {
            append("Opcja wybudzania: ${if (Prefs(ctx).wakeForZap) "włączona" else "wyłączona"}\n")
            append("Dokładne alarmy: ${if (canScheduleExact(ctx)) "dozwolone" else "BRAK ZGODY"}\n")
            append("Powiadomienia: ${if (notificationAllowed(ctx)) "dozwolone" else "BRAK ZGODY"}\n")
            append("Pełny ekran: ${if (canUseFullScreen(ctx)) "dozwolony" else "zablokowany"}\n")
            append("Oczekujące wydarzenia: ${ZapTimers.list(ctx).size}\n")
            if (next != null) append("Najbliższy: ${next.channel} — ${next.title} (${next.begin})")
            else append("Brak oczekujących wydarzeń")
        }
    }

    fun scheduleTest(ctx: Context): String {
        if (!Prefs(ctx).wakeForZap) return "Najpierw włącz „Budź TV dla harmonogramu”."
        if (!canScheduleExact(ctx)) return "Brak zgody na dokładne alarmy. Włącz ją w ustawieniach Androida."
        if (!notificationAllowed(ctx)) return "Brak zgody na powiadomienia. Włącz ją w ustawieniach Androida."
        val testId = System.currentTimeMillis().toString()
        val intent = Intent(ctx, ZapAlarmReceiver::class.java).apply {
            action = ACTION_TEST
            data = Uri.parse("e2stream://zap-test/$testId")
            putExtra("test", true)
            putExtra("test_id", testId)
        }
        val pi = pending(ctx, "test-$testId", intent)
        val at = System.currentTimeMillis() + 60_000
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                    .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setExact(AlarmManager.RTC_WAKEUP, at, pi)
            }
            Log.i("E2Stream", "Test alarm RTC_WAKEUP ustawiony: $testId na $at")
            AppLog.i("wybudzanie: test RTC_WAKEUP ustawiony id=$testId at=$at")
            return "Test ustawiony za 60 sekund. Zostaw aplikację i poczekaj bez naciskania pilota."
        } catch (e: SecurityException) {
            Log.e("E2Stream", "Nie można ustawić testowego alarmu RTC_WAKEUP", e)
            AppLog.i("wybudzanie: błąd ustawienia testu id=$testId: $e")
            return "Android odrzucił testowy alarm: ${e.message}"
        }
    }

    internal fun isForeground(ctx: Context) = isAppForeground(ctx)

    internal fun showNotification(ctx: Context, intent: Intent, key: String, channel: String, title: String, begin: Long, testId: String? = null) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Harmonogram E2Stream", NotificationManager.IMPORTANCE_HIGH))
        }
        val launch = Intent(ctx, PlayerActivity::class.java).apply {
            action = "pl.jardol.e2stream.OPEN_ZAP_ALARM"
            data = intent.data
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(PlayerActivity.EXTRA_WAKE_TIMER_KEY, key)
            if (testId != null) putExtra(PlayerActivity.EXTRA_WAKE_TEST_ID, testId)
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
            AppLog.i("wybudzanie: NotificationManager.notify OK key=$key fullScreenAllowed=${canUseFullScreen(ctx)}")
        } catch (e: SecurityException) {
            Log.w("E2Stream", "Brak uprawnienia do powiadomienia harmonogramu", e)
            AppLog.i("wybudzanie: notify SecurityException key=$key: $e")
        }
    }
}

class ZapAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return
        val prefs = Prefs(context)
        if (intent.getBooleanExtra("test", false)) {
            val id = intent.getStringExtra("test_id") ?: "?"
            Log.i("E2Stream", "TEST alarm RTC_WAKEUP dotarł: $id; appForeground=${ZapWakeAlarms.isForeground(context)}")
            AppLog.i("wybudzanie: TEST RTC_WAKEUP DOSTARCZONY id=$id appForeground=${ZapWakeAlarms.isForeground(context)}")
            ZapWakeAlarms.showNotification(context, intent, "test-$id", "Test alarmu E2Stream",
                "Alarm dotarł. Czekaj na okno testowe.", System.currentTimeMillis() / 1000, id)
            return
        }
        if (!prefs.wakeForZap || ZapWakeAlarms.isForeground(context)) {
            Log.i("E2Stream", "Alarm pominięty: wakeForZap=${prefs.wakeForZap}, appForeground=${ZapWakeAlarms.isForeground(context)}")
            AppLog.i("wybudzanie: alarm pominięty wakeForZap=${prefs.wakeForZap} appForeground=${ZapWakeAlarms.isForeground(context)}")
            return
        }
        val key = intent.getStringExtra("timer_key") ?: return
        if (ZapTimers.list(context).none { it.key() == key }) {
            Log.w("E2Stream", "Alarm dotarł, ale timer już nie istnieje: $key")
            AppLog.i("wybudzanie: alarm dotarł, timer nie istnieje key=$key")
            return
        }
        Log.i("E2Stream", "Alarm harmonogramu dotarł: $key")
        AppLog.i("wybudzanie: alarm harmonogramu DOSTARCZONY key=$key")
        ZapWakeAlarms.showNotification(context, intent, key,
            intent.getStringExtra("channel") ?: "E2Stream",
            intent.getStringExtra("title") ?: "Zaplanowana audycja",
            intent.getLongExtra("begin", System.currentTimeMillis() / 1000))
    }
}

class ZapBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AppLog.i("wybudzanie: odbiornik systemowy ${intent.action}")
            ZapWakeAlarms.sync(context)
        }
    }
}