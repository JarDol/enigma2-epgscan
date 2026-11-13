package pl.jardol.e2stream

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/** Lekkie okno alarmu: nie tworzy odtwarzacza, dopóki użytkownik nie wybierze „Oglądaj”. */
class ZapPromptActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private var timer: ZapTimers.Timer? = null
    private var actionTaken = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var countdown: Runnable? = null

    private companion object { const val COUNTDOWN_S = 30 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareWindow()
        showIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        actionTaken = false
        prepareWindow()
        showIntent(intent)
    }

    private fun prepareWindow() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(View(this))
    }

    private fun showIntent(intent: android.content.Intent) {
        if (intent.getBooleanExtra("test", false)) {
            val id = intent.getStringExtra("test_id") ?: "?"
            AppLog.i("wybudzanie: ZapPromptActivity pokazuje wynik testu id=$id")
            TvDialog.confirm(this, getString(R.string.message_wake_up_test_received),
                getString(R.string.prompt_test_body, id),
                listOf(TvDialog.Btn(getString(R.string.epg_close)) { finish() }),
                onBack = { finish() })
            return
        }

        val ref = intent.getStringExtra("ref") ?: run { finish(); return }
        val event = ZapTimers.Timer(
            ref,
            intent.getStringExtra("channel") ?: getString(R.string.prompt_channel),
            intent.getStringExtra("title") ?: getString(R.string.prompt_programme),
            intent.getLongExtra("begin", 0),
            intent.getLongExtra("end", 0),
            intent.getIntExtra("eventId", 0),
        )
        if (ZapTimers.list(this).none { it.key() == event.key() }) {
            AppLog.i("wybudzanie: prompt pominięty, timer już nie istnieje ${event.key()}")
            finish()
            return
        }
        timer = event
        AppLog.i("wybudzanie: ZapPromptActivity pokazuje ${event.channel} — ${event.title}")
        val card = EventCard.eventOf(event, this)
        val body = EventCard.cachedBody(this, event)
        val chips = listOf(
            TvDialog.Btn(getString(R.string.prompt_switch_countdown, countdownLabel(COUNTDOWN_S))) { watch(event) },
            TvDialog.Btn(getString(R.string.epg_record_receiver)) { record(event) },
        )
        stopCountdown()
        val dlg = TvDialog.panel(this, null, null, chips, widthFrac = 0.8f,
            onBack = { skip(event) }, focusChip = true) { ctx, _ ->
            EventCard.content(ctx, card, event.ref, event.channel, body)
        }
        startCountdown(dlg, event)
    }

    private fun countdownLabel(s: Int) = "0:${s.coerceAtLeast(0).toString().padStart(2, '0')}"

    /**
     * Odliczanie biegnie tylko przy włączonym ekranie i aktywnym oknie. Gdy przystawka/TV śpi,
     * licznik stoi na pełnych 30 s i startuje dopiero po włączeniu. Po końcu audycji popup się zamyka.
     */
    private fun startCountdown(dlg: android.app.Dialog, event: ZapTimers.Timer) {
        var left = COUNTDOWN_S
        val power = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val tick = object : Runnable {
            override fun run() {
                if (actionTaken || isFinishing) return
                if (System.currentTimeMillis() / 1000 >= event.end) {
                    AppLog.i("wybudzanie: audycja się skończyła, pomijam ${event.channel} — ${event.title}")
                    try { dlg.dismiss() } catch (_: Exception) {}
                    skip(event)
                    return
                }
                val watching = power.isInteractive && (hasWindowFocus() || dlg.window?.decorView?.hasWindowFocus() == true)
                if (!watching) {
                    if (left != COUNTDOWN_S) {
                        left = COUNTDOWN_S
                        TvDialog.setButtonLabel(dlg, 0, getString(R.string.prompt_switch_countdown, countdownLabel(left)))
                    }
                    handler.postDelayed(this, 1000)
                    return
                }
                left--
                if (left <= 0) {
                    AppLog.i("wybudzanie: minął czas, przełączam ${event.channel} — ${event.title}")
                    try { dlg.dismiss() } catch (_: Exception) {}
                    watch(event)
                    return
                }
                TvDialog.setButtonLabel(dlg, 0, getString(R.string.prompt_switch_countdown, countdownLabel(left)))
                handler.postDelayed(this, 1000)
            }
        }
        countdown = tick
        handler.postDelayed(tick, 1000)
    }

    private fun stopCountdown() {
        countdown?.let { handler.removeCallbacks(it) }
        countdown = null
    }

    override fun onDestroy() {
        stopCountdown()
        super.onDestroy()
    }

    private fun watch(event: ZapTimers.Timer) {
        if (actionTaken) return
        actionTaken = true
        stopCountdown()
        ZapTimers.complete(this, event, ZapTimers.SWITCHED)
        AppLog.i("wybudzanie: wybrano Oglądaj ${event.channel} — ${event.title}")
        startActivity(android.content.Intent(this, PlayerActivity::class.java).apply {
            action = PlayerActivity.ACTION_SCHEDULE_ZAP
            putExtra(PlayerActivity.EXTRA_SCHEDULE_REF, event.ref)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun record(event: ZapTimers.Timer) {
        if (actionTaken) return
        actionTaken = true
        stopCountdown()
        setContentView(TextView(this).apply {
            text = getString(R.string.prompt_sending_recording)
            textSize = 20f; setTextColor(TvDialog.WHITE)
            setBackgroundColor(0xE6161B20.toInt())
            gravity = android.view.Gravity.CENTER
        })
        AppLog.i("wybudzanie: wybrano nagranie ${event.channel} — ${event.title}")
        Thread {
            val result = try {
                OpenWebif(Prefs(applicationContext)).timerAdd(event.ref,
                    EpgEvent(event.eventId, event.begin, (event.end - event.begin).coerceAtLeast(0), event.title, "", "", event.ref, event.channel))
            } catch (e: Exception) { false to (e.message ?: e.toString()) }
            val outcome = if (result.first) ZapTimers.RECORDED else ZapTimers.RECORD_FAILED
            ZapTimers.complete(applicationContext, event, outcome)
            AppLog.i("wybudzanie: wynik nagrania ${event.channel} — ${event.title}: ${result.first} ${result.second}")
            runOnUiThread {
                Toast.makeText(this,
                    if (result.first) getString(R.string.prompt_recording_set) else getString(R.string.prompt_recording_failed, result.second),
                    Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    private fun skip(event: ZapTimers.Timer) {
        if (actionTaken) return
        actionTaken = true
        stopCountdown()
        ZapTimers.complete(this, event, ZapTimers.SKIPPED)
        AppLog.i("wybudzanie: pominięto z ZapPromptActivity ${event.channel} — ${event.title}")
        finish()
    }
}