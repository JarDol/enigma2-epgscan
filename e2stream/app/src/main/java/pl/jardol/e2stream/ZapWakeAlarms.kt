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
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/** Systemowe alarmy RTC_WAKEUP dla harmonogramu; aplikacja może zostać uruchomiona przez pełnoekranowe powiadomienie. */
object ZapWakeAlarms {
    private const val STORE = "zapwake"
    private const val CHANNEL = "zap_schedule"
    private const val ACTION_ZAP = "pl.jardol.e2stream.ZAP_ALARM"
    private const val ACTION_TEST = "pl.jardol.e2stream.ZAP_TEST_ALARM"
    private const val EXTRA_KEY = "timer_key"
    private const val EXTRA_SCHEDULED_AT = "scheduled_at"
    private const val MONITORED_KEYS = "monitor_received"

    fun canScheduleExact(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    fun canUseFullScreen(ctx: Context): Boolean = Build.VERSION.SDK_INT < 34 ||
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()

    fun canDrawOverlays(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = app.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet("keys", emptySet())?.toSet() ?: emptySet()
        val enabled = Prefs(app).wakeForZap
        val exactAllowed = canScheduleExact(app)
        val monitorSeconds = Prefs(app).wakeZapMonitorSeconds
        val timers = if (enabled && exactAllowed) ZapTimers.list(app) else emptyList()
        val nextKeys = timers.map { it.key() }.toSet()
        val monitoredKeys = (prefs.getStringSet(MONITORED_KEYS, emptySet())?.toSet() ?: emptySet())
            .intersect(nextKeys)
        if (monitoredKeys != prefs.getStringSet(MONITORED_KEYS, emptySet())) {
            prefs.edit().putStringSet(MONITORED_KEYS, monitoredKeys).apply()
        }

        AppLog.i("wybudzanie: sync enabled=$enabled exactAllowed=$exactAllowed timers=${timers.size}")

        for (key in previous - nextKeys) {
            alarm.cancel(pending(app, key))
            alarm.cancel(pending(app, key, phase = "start"))
            AppLog.i("wybudzanie: anulowano alarm $key")
        }
        for (timer in timers) {
            val at = alarmTriggerAt(timer.begin, monitorSeconds, System.currentTimeMillis())
            val intent = alarmIntent(app, timer).putExtra(EXTRA_SCHEDULED_AT, at)
            val pi = pending(app, timer.key(), intent)
            try {
                val show = PendingIntent.getActivity(app, timer.key().hashCode(),
                    Intent(app, PlayerActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                if (monitorSeconds <= 0 || timer.key() !in monitoredKeys) {
                    alarm.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
                } else {
                    alarm.cancel(pi)
                }
                if (monitorSeconds > 0) {
                    val startAt = alarmTriggerAt(timer.begin, 0, System.currentTimeMillis())
                    val finalIntent = alarmIntent(app, timer)
                        .setData(Uri.parse("e2stream://zap/${Uri.encode(timer.key())}/start"))
                        .putExtra(EXTRA_SCHEDULED_AT, startAt)
                        .putExtra("final_alarm", true)
                    alarm.setAlarmClock(AlarmManager.AlarmClockInfo(startAt, show),
                        pending(app, timer.key(), finalIntent, "start"))
                } else {
                    alarm.cancel(pending(app, timer.key(), phase = "start"))
                }
                Log.i("E2Stream", "Harmonogram alarm: ${timer.key()} ustawiony na $at")
                AppLog.i("wybudzanie: ustawiono AlarmClock ${timer.key()} scheduledTime=$at monitorSeconds=$monitorSeconds monitorDelivered=${timer.key() in monitoredKeys} finalWake=${monitorSeconds > 0}")
            } catch (e: SecurityException) {
                Log.w("E2Stream", "Nie można ustawić dokładnego alarmu ${timer.key()}", e)
                AppLog.i("wybudzanie: błąd ustawienia ${timer.key()}: $e")
            }
        }
        prefs.edit().putStringSet("keys", nextKeys).apply()
    }

    internal fun alarmTriggerAt(beginSeconds: Long, monitorSeconds: Int, nowMillis: Long): Long =
        ((beginSeconds - monitorSeconds.coerceIn(0, 300)) * 1000).coerceAtLeast(nowMillis + 1000)

    internal fun markMonitorReceived(ctx: Context, key: String) {
        val prefs = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val keys = prefs.getStringSet(MONITORED_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()
        keys += key
        prefs.edit().putStringSet(MONITORED_KEYS, keys).apply()
    }

    internal fun monitorWasReceived(ctx: Context, key: String): Boolean =
        ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getStringSet(MONITORED_KEYS, emptySet())?.contains(key) == true

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

    private fun pending(ctx: Context, key: String, intent: Intent? = null, phase: String? = null): PendingIntent {
        val actual = intent ?: Intent(ctx, ZapAlarmReceiver::class.java).apply {
            action = ACTION_ZAP
            data = Uri.parse("e2stream://zap/${Uri.encode(key)}${phase?.let { "/$it" } ?: ""}")
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val requestKey = if (phase == null) key else "$key:$phase"
        return PendingIntent.getBroadcast(ctx, requestKey.hashCode(), actual, flags)
    }

    fun cancelAll(ctx: Context) {
        val prefs = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val alarm = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val keys = prefs.getStringSet("keys", emptySet())?.toSet() ?: emptySet()
        for (key in keys) {
            alarm.cancel(pending(ctx, key))
            alarm.cancel(pending(ctx, key, phase = "start"))
        }
        prefs.edit().putStringSet("keys", emptySet()).putStringSet(MONITORED_KEYS, emptySet()).apply()
    }

    fun notificationAllowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun diagnostics(ctx: Context): String {
        val text = AppLanguage.wrap(ctx)
        val next = ZapTimers.list(ctx).firstOrNull()
        return buildString {
            appendLine(text.getString(R.string.wake_option, text.getString(if (Prefs(ctx).wakeForZap) R.string.wake_enabled else R.string.wake_disabled)))
            appendLine(text.getString(R.string.wake_exact, text.getString(if (canScheduleExact(ctx)) R.string.wake_allowed else R.string.wake_no_permission)))
            appendLine(text.getString(R.string.wake_notifications, text.getString(if (notificationAllowed(ctx)) R.string.wake_allowed else R.string.wake_no_permission)))
            appendLine(text.getString(R.string.wake_fullscreen, text.getString(if (canUseFullScreen(ctx)) R.string.wake_fullscreen_allowed else R.string.wake_fullscreen_blocked)))
            appendLine(text.getString(R.string.wake_overlay, text.getString(if (canDrawOverlays(ctx)) R.string.wake_allowed else R.string.wake_no_permission)))
            appendLine(text.getString(R.string.wake_monitor, monitorDescription(text, Prefs(ctx).wakeZapMonitorSeconds)))
            appendLine(text.getString(R.string.wake_pending, ZapTimers.list(ctx).size))
            if (next != null) append(text.getString(R.string.wake_next, next.channel, next.title, next.begin))
            else append(text.getString(R.string.wake_no_pending))
        }
    }

    private fun monitorDescription(ctx: Context, seconds: Int) = when (seconds) {
        0 -> ctx.getString(R.string.settings_wake_monitor_off)
        60 -> ctx.getString(R.string.settings_wake_monitor_minute, 1)
        120 -> ctx.getString(R.string.settings_wake_monitor_minute, 2)
        300 -> ctx.getString(R.string.settings_wake_monitor_minute, 5)
        else -> ctx.getString(R.string.settings_wake_monitor_seconds, seconds)
    }

    fun scheduleTest(ctx: Context): String {
        val text = AppLanguage.wrap(ctx)
        if (!Prefs(ctx).wakeForZap) return text.getString(R.string.wake_enable_first)
        if (!canScheduleExact(ctx)) return text.getString(R.string.wake_exact_permission)
        if (!notificationAllowed(ctx)) return text.getString(R.string.wake_notification_permission)
        val testId = System.currentTimeMillis().toString()
        val intent = Intent(ctx, ZapAlarmReceiver::class.java).apply {
            action = ACTION_TEST
            data = Uri.parse("e2stream://zap-test/$testId")
            putExtra("test", true)
            putExtra("test_id", testId)
        }
        val at = System.currentTimeMillis() + 60_000
        intent.putExtra(EXTRA_SCHEDULED_AT, at)
        val pi = pending(ctx, "test-$testId", intent)
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                    .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setExact(AlarmManager.RTC_WAKEUP, at, pi)
            }
            Log.i("E2Stream", "Test alarm RTC_WAKEUP ustawiony: $testId na $at")
            AppLog.i("wybudzanie: test RTC_WAKEUP ustawiony id=$testId at=$at")
            return text.getString(R.string.wake_test_scheduled)
        } catch (e: SecurityException) {
            Log.e("E2Stream", "Nie można ustawić testowego alarmu RTC_WAKEUP", e)
            AppLog.i("wybudzanie: błąd ustawienia testu id=$testId: $e")
            return text.getString(R.string.wake_test_error, e.message ?: e.toString())
        }
    }

    internal fun isForeground() = AppState.foreground

    internal fun cancelNotification(ctx: Context, key: String) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(key.hashCode())
    }

    internal fun scheduledPlayerIntent(
        ctx: Context,
        timer: ZapTimers.Timer,
        timerAlreadyConsumed: Boolean = false,
    ) =
        Intent(ctx, PlayerActivity::class.java).apply {
            action = PlayerActivity.ACTION_SCHEDULE_ZAP
            data = Uri.parse("e2stream://zap/${Uri.encode(timer.key())}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!timerAlreadyConsumed) putExtra(PlayerActivity.EXTRA_WAKE_TIMER_KEY, timer.key())
            putExtra(PlayerActivity.EXTRA_SCHEDULE_REF, timer.ref)
        }

    internal fun showNotification(
        ctx: Context,
        intent: Intent,
        key: String,
        channel: String,
        title: String,
        begin: Long,
        testId: String? = null,
        timerOverride: ZapTimers.Timer? = null,
        timerAlreadyConsumed: Boolean = false,
    ) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, AppLanguage.wrap(ctx).getString(R.string.wake_notification_channel), NotificationManager.IMPORTANCE_HIGH))
        }
        val timer = timerOverride ?: if (testId == null) ZapTimers.list(ctx).firstOrNull { it.key() == key } else null
        val launch = if (timer != null) scheduledPlayerIntent(ctx, timer, timerAlreadyConsumed) else Intent(ctx, ZapPromptActivity::class.java).apply {
            action = "pl.jardol.e2stream.OPEN_ZAP_ALARM"
            data = intent.data
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtras(intent)
            putExtra(PlayerActivity.EXTRA_WAKE_TIMER_KEY, key)
            if (testId != null) putExtra(PlayerActivity.EXTRA_WAKE_TEST_ID, testId)
        }
        if (Build.VERSION.SDK_INT <= 28 && testId != null) {
            try {
                ctx.startActivity(launch)
                AppLog.i("wybudzanie: bezpośrednio uruchomiono ZapPromptActivity API ${Build.VERSION.SDK_INT} key=$key")
                return
            } catch (e: Exception) {
                AppLog.i("wybudzanie: bezpośredni start ZapPromptActivity nieudany, zostawiam powiadomienie key=$key: $e")
            }
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
            val importance = if (Build.VERSION.SDK_INT >= 26) manager.getNotificationChannel(CHANNEL)?.importance else null
            AppLog.i("wybudzanie: NotificationManager.notify OK key=$key fullScreenAllowed=${canUseFullScreen(ctx)} notificationsEnabled=${manager.areNotificationsEnabled()} channelImportance=$importance")
        } catch (e: SecurityException) {
            Log.w("E2Stream", "Brak uprawnienia do powiadomienia harmonogramu", e)
            AppLog.i("wybudzanie: notify SecurityException key=$key: $e")
        }
    }

    internal fun launchPlayerOrNotify(
        ctx: Context,
        timer: ZapTimers.Timer,
        source: Intent? = null,
        timerAlreadyConsumed: Boolean = false,
    ): Boolean {
        ZapOverlayMonitor.dismiss(timer.key())
        var started = false
        try {
            ctx.startActivity(scheduledPlayerIntent(ctx, timer, timerAlreadyConsumed))
            started = true
            AppLog.i("wybudzanie: uruchomiono PlayerActivity key=${timer.key()}")
        } catch (e: SecurityException) {
            AppLog.i("wybudzanie: start PlayerActivity odrzucony key=${timer.key()}: $e")
        } catch (e: android.content.ActivityNotFoundException) {
            AppLog.i("wybudzanie: brak PlayerActivity key=${timer.key()}: $e")
        } catch (e: IllegalStateException) {
            AppLog.i("wybudzanie: start PlayerActivity niedozwolony key=${timer.key()}: $e")
        } catch (e: RuntimeException) {
            AppLog.i("wybudzanie: błąd start PlayerActivity key=${timer.key()}: $e")
        }
        if (!started && notificationAllowed(ctx)) {
            val alarm = source ?: alarmIntent(ctx, timer).putExtra(EXTRA_SCHEDULED_AT, System.currentTimeMillis())
            showNotification(ctx, alarm, timer.key(), timer.channel, timer.title, timer.begin,
                timerOverride = timer, timerAlreadyConsumed = timerAlreadyConsumed)
        }
        return started
    }
}

class ZapAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        val scheduled = intent.getLongExtra("scheduled_at", 0L)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        AppLog.i("wybudzanie: alarm scheduledTime=$scheduled actualTriggerTime=$now driftMs=${if (scheduled > 0) now - scheduled else "?"} interactive=${power.isInteractive}")
        if (intent.getBooleanExtra("test", false)) {
            val id = intent.getStringExtra("test_id") ?: "?"
            Log.i("E2Stream", "TEST alarm RTC_WAKEUP dotarł: $id; appForeground=${ZapWakeAlarms.isForeground()}")
            AppLog.i("wybudzanie: TEST RTC_WAKEUP DOSTARCZONY id=$id appForeground=${ZapWakeAlarms.isForeground()}")
            ZapWakeAlarms.showNotification(context, intent, "test-$id", AppLanguage.wrap(context).getString(R.string.wake_test_title),
                AppLanguage.wrap(context).getString(R.string.wake_test_arrived), System.currentTimeMillis() / 1000, id)
            return
        }
        if (!prefs.wakeForZap) {
            Log.i("E2Stream", "Alarm pominięty: wakeForZap=${prefs.wakeForZap}, appForeground=${ZapWakeAlarms.isForeground()}")
            AppLog.i("wybudzanie: alarm pominięty wakeForZap=${prefs.wakeForZap} appForeground=${ZapWakeAlarms.isForeground()}")
            return
        }
        val key = intent.getStringExtra("timer_key") ?: return
        val timer = ZapTimers.list(context).firstOrNull { it.key() == key }
        if (timer == null) {
            Log.w("E2Stream", "Alarm dotarł, ale timer już nie istnieje: $key")
            AppLog.i("wybudzanie: alarm dotarł, timer nie istnieje key=$key")
            return
        }
        if (scheduled > 0 && now + 1000 < scheduled) {
            AppLog.i("wybudzanie: alarm przed zaplanowanym monitorem key=$key — ponawiam planowanie")
            ZapWakeAlarms.sync(context)
            return
        }
        val finalAlarm = intent.getBooleanExtra("final_alarm", false)
        val showPrompt = !finalAlarm && prefs.wakeZapMonitorSeconds > 0 && timer.begin > now / 1000
        if (showPrompt) ZapWakeAlarms.markMonitorReceived(context, key)
        if (ZapWakeAlarms.isForeground() && power.isInteractive) {
            Log.i("E2Stream", "Alarm pominięty: aplikacja jest na wierzchu")
            AppLog.i("wybudzanie: alarm pominięty appForeground=true interactive=true finalAlarm=$finalAlarm")
            return
        }
        if (showPrompt && power.isInteractive && ZapWakeAlarms.canDrawOverlays(context)) {
            if (ZapOverlayMonitor.show(context, timer)) {
                AppLog.i("wybudzanie: nakładka aktywna key=$key; PlayerActivity uruchomi się po wyborze lub końcu odliczania")
                return
            }
            AppLog.i("wybudzanie: nakładka niedostępna key=$key; uruchamiam PlayerActivity")
        } else if (showPrompt) {
            AppLog.i("wybudzanie: monit bez nakładki key=$key interactive=${power.isInteractive} overlayAllowed=${ZapWakeAlarms.canDrawOverlays(context)}")
        }
        Log.i("E2Stream", "Alarm harmonogramu dotarł: $key")
        AppLog.i("wybudzanie: alarm harmonogramu DOSTARCZONY key=$key showPrompt=$showPrompt finalAlarm=$finalAlarm")
        @Suppress("DEPRECATION")
        try {
            val wake = power.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "e2stream:scheduled-wake"
            )
            wake.acquire(10_000L)
            AppLog.i("wybudzanie: wake lock aktywny maks. 10 s")
        } catch (e: SecurityException) {
            AppLog.i("wybudzanie: brak zgody na wake lock: $e")
        } catch (e: IllegalArgumentException) {
            AppLog.i("wybudzanie: wake lock nieobsługiwany: $e")
        }
        ZapWakeAlarms.launchPlayerOrNotify(context, timer, intent)
    }
}

class ZapBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AppLog.i("wybudzanie: odbiornik systemowy ${intent.action}")
                ZapWakeAlarms.sync(context)
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    try {
                        val freed = AppStorage.cleanupInstalledApks(context)
                        AppLog.i("aktualizacja: po instalacji zwolniono ${AppStorage.mb(freed)} z pobranych APK")
                    } catch (e: Exception) {
                        AppLog.i("aktualizacja: nie udało się posprzątać APK po instalacji: $e")
                    }
                }
            }
        }
    }
}