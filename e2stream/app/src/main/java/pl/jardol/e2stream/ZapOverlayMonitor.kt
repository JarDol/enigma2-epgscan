package pl.jardol.e2stream

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

internal object ZapOverlayMonitor {
    private val active = mutableMapOf<String, Overlay>()

    internal fun isShowing(key: String) = active.containsKey(key)

    internal fun dismissAll() {
        active.values.toList().forEach { it.remove() }
    }

    internal fun dismiss(key: String) {
        active[key]?.remove()
    }

    fun show(context: Context, timer: ZapTimers.Timer): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() || !ZapWakeAlarms.canDrawOverlays(context)) return false
        val key = timer.key()
        if (active.containsKey(key)) return true
        var overlay: Overlay? = null
        return try {
            val current = Overlay(context.applicationContext, timer)
            overlay = current
            current.attach()
            active[key] = current
            true
        } catch (e: Exception) {
            overlay?.remove()
            AppLog.i("wybudzanie: błąd tworzenia nakładki key=$key: $e")
            false
        }
    }

    private fun removed(key: String, overlay: Overlay) {
        if (active[key] === overlay) active.remove(key)
    }

    private class Overlay(
        private val context: Context,
        private val timer: ZapTimers.Timer,
    ) {
        private val key = timer.key()
        private val handler = Handler(Looper.getMainLooper())
        private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private var attached = false
        private var finished = false
        private var lastDisplayed: Long = Long.MIN_VALUE
        private lateinit var countdown: TextView
        private lateinit var root: OverlayPanel
        private lateinit var switchButton: Button
        private val tick = object : Runnable {
            override fun run() {
                if (finished) return
                if (ZapTimers.list(context).none { it.key() == key }) {
                    remove()
                    return
                }
                val now = System.currentTimeMillis() / 1000
                if (now >= timer.end) {
                    cancel()
                    return
                }
                val left = (timer.begin - now).coerceAtLeast(0)
                if (left == 0L) {
                    switch()
                    return
                }
                if (left != lastDisplayed) {
                    lastDisplayed = left
                    countdown.text = context.getString(R.string.wake_overlay_countdown, format(left))
                    switchButton.text = context.getString(R.string.wake_overlay_switch_countdown, format(left))
                }
                handler.postDelayed(this, 250)
            }
        }

        fun attach() {
            root = OverlayPanel(context) { cancel() }.apply {
                orientation = LinearLayout.VERTICAL
                isFocusableInTouchMode = true
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                minimumWidth = dp(300)
                background = GradientDrawable().apply {
                    setColor(0xF2161B20.toInt())
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(18), dp(12), dp(18), dp(10))
            }
            root.addView(TextView(context).apply {
                text = timer.channel
                textSize = 17f
                setTextColor(TvDialog.BLUE)
                maxWidth = dp(400)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            root.addView(TextView(context).apply {
                text = timer.title
                textSize = 15f
                setTextColor(TvDialog.WHITE)
                maxWidth = dp(400)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, dp(2))
            })
            countdown = TextView(context).apply {
                textSize = 13f
                setTextColor(TvDialog.GREY)
            }
            root.addView(countdown)

            val buttons = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(7), 0, 0)
            }
            switchButton = Button(context, null, 0, R.style.TvDialogButton).apply {
                text = context.getString(R.string.wake_overlay_switch_now)
                isFocusable = true
                setOnClickListener { switch() }
            }
            buttons.addView(switchButton)
            buttons.addView(Button(context, null, 0, R.style.TvDialogButton).apply {
                text = context.getString(R.string.wake_overlay_cancel)
                isFocusable = true
                setOnClickListener { cancel() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
            root.addView(buttons)

            @Suppress("DEPRECATION")
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = dp(24)
                y = dp(24)
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            }
            attached = true
            windowManager.addView(root, params)
            switchButton.requestFocus()
            handler.post(tick)
            AppLog.i("wybudzanie: dodano nakładkę key=$key typ=$type focus=true")
        }

        fun remove() {
            if (finished) return
            finished = true
            handler.removeCallbacks(tick)
            if (attached) {
                try {
                    windowManager.removeViewImmediate(root)
                    AppLog.i("wybudzanie: usunięto nakładkę key=$key")
                } catch (e: Exception) {
                    AppLog.i("wybudzanie: błąd usuwania nakładki key=$key: $e")
                }
                attached = false
            }
            removed(key, this)
        }

        private fun switch() {
            if (finished) return
            if (!ZapTimers.completeSwitchIfPending(context, key)) {
                remove()
                return
            }
            ZapWakeAlarms.cancelNotification(context, key)
            AppLog.i("wybudzanie: nakładka — przełącz teraz key=$key")
            remove()
            ZapWakeAlarms.launchPlayerOrNotify(context, timer, timerAlreadyConsumed = true)
        }

        private fun cancel() {
            if (finished) return
            if (ZapTimers.completeIfPending(context, key, ZapTimers.SKIPPED)) {
                ZapWakeAlarms.cancelNotification(context, key)
                AppLog.i("wybudzanie: nakładka anulowana key=$key")
            }
            remove()
        }

        private fun format(seconds: Long) =
            "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

        private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    }

    private class OverlayPanel(context: Context, private val onBack: () -> Unit) : LinearLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }
}
