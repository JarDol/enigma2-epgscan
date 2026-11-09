package pl.jardol.e2stream

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.EditText

/**
 * Blokada ustawień PIN-em: po poprawnym PIN-ie odblokowane na czas z ustawień
 * (lub do zamknięcia aplikacji). Wyjście z aplikacji zawsze blokuje ponownie.
 */
object PinLock {
    private var unlockedUntil = 0L

    fun isUnlocked(prefs: Prefs) = prefs.pin.isEmpty() || SystemClock.elapsedRealtime() < unlockedUntil

    fun label(min: Int) = if (min <= 0) "do wyjścia z aplikacji" else "$min min"

    /** Odblokowuje na czas z ustawień, licząc od teraz. */
    fun unlock(prefs: Prefs) {
        val min = prefs.pinMin
        unlockedUntil = if (min <= 0) Long.MAX_VALUE else SystemClock.elapsedRealtime() + min * 60_000L
    }

    /**
     * Liczy widoczne ekrany aplikacji; gdy żaden nie jest widoczny (ekran główny, inna aplikacja,
     * wyłączony telewizor) — blokada i zamknięcie otwartych ustawień, żeby po powrocie na wierzchu
     * był zawsze odtwarzacz. Zmiany cyklu życia trafiają do logu (diagnostyka wybudzania).
     */
    fun watch(app: android.app.Application) {
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            var started = 0
            val settings = mutableSetOf<Activity>()
            fun name(a: Activity) = a.javaClass.simpleName.removeSuffix("Activity").lowercase()
            override fun onActivityStarted(a: Activity) {
                started++
                AppLog.i("ekran: ${name(a)} widoczny")
            }
            override fun onActivityStopped(a: Activity) {
                started = (started - 1).coerceAtLeast(0)
                AppLog.i("ekran: ${name(a)} ukryty")
                if (started == 0 && !a.isChangingConfigurations) {
                    lock()
                    for (s in settings.toList()) if (!s.isFinishing) s.finish()
                }
            }
            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {
                if (a is SettingsActivity) settings += a
            }
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: Activity) { settings -= a }
        })
    }

    fun remainingMs() = (unlockedUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    fun lock() { unlockedUntil = 0 }

    /**
     * Wywołuje [onOk], gdy ustawienia są odblokowane lub po poprawnym PIN-ie; [onCancel] przy anulowaniu.
     * PIN wpisywany cyframi z pilota albo klawiaturą na ekranie (strzałki + OK) — dla pilotów bez cyfr
     * (Mi Box, małe piloty nowych telewizorów); bez systemowej klawiatury, która zasłaniała okno.
     */
    fun ask(a: Activity, prefs: Prefs, cancelable: Boolean = true, onCancel: () -> Unit = {}, onOk: () -> Unit) {
        if (isUnlocked(prefs)) { onOk(); return }
        val pin = prefs.pin
        val typed = StringBuilder()
        var done = false
        lateinit var dots: android.widget.LinearLayout
        lateinit var info: android.widget.TextView
        lateinit var dlg: android.app.Dialog
        fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
        fun redraw() {
            for (i in 0 until dots.childCount) {
                val d = dots.getChildAt(i)
                (d.background as android.graphics.drawable.GradientDrawable).apply {
                    if (i < typed.length) { setColor(TvDialog.BLUE); setStroke(0, 0) }
                    else { setColor(0); setStroke(dp(2), 0xFF78909C.toInt()) }
                }
            }
        }
        fun press(c: Char) {
            if (done) return
            when (c) {
                '<' -> if (typed.isNotEmpty()) typed.setLength(typed.length - 1)
                '>' -> {}
                else -> if (typed.length < pin.length) typed.append(c)
            }
            redraw()
            if (typed.length >= pin.length || c == '>') {
                if (typed.toString() == pin) {
                    done = true
                    unlock(prefs)
                    dlg.dismiss()
                    onOk()
                } else if (typed.isNotEmpty()) {
                    info.text = "Błędny PIN — spróbuj ponownie"
                    info.setTextColor(0xFFEF9A9A.toInt())
                    typed.setLength(0)
                    dots.postDelayed({ redraw() }, 250)
                }
            }
        }
        dlg = TvDialog.panel(a, "Ustawienia zablokowane", null, widthFrac = 0.42f,
            onBack = { if (!done) { done = true; onCancel() } }) { ctx, _ ->      // WSTECZ zawsze anuluje
            val col = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            dots = android.widget.LinearLayout(ctx).apply { gravity = android.view.Gravity.CENTER }
            repeat(pin.length) {
                dots.addView(View(ctx).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL } },
                    android.widget.LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(9); marginEnd = dp(9) })
            }
            col.addView(dots)
            info = android.widget.TextView(ctx).apply {
                text = "Wpisz PIN cyframi na pilocie albo wybierz poniżej"
                textSize = 14f; setTextColor(TvDialog.GREY); gravity = android.view.Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
            }
            col.addView(info)
            val grid = android.widget.GridLayout(ctx).apply { columnCount = 3 }
            val keys = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '<', '0', '>')
            var firstKey: android.widget.Button? = null
            for (k in keys) {
                val b = android.widget.Button(ctx, null, 0, R.style.TvDialogButton)
                b.text = when (k) { '<' -> "⌫"; '>' -> "OK"; else -> k.toString() }
                b.textSize = 20f
                b.minWidth = dp(76); b.minimumWidth = dp(76)
                b.setOnClickListener { press(k) }
                grid.addView(b, android.widget.GridLayout.LayoutParams().apply {
                    width = dp(76); height = dp(52); setMargins(dp(5), dp(5), dp(5), dp(5))
                })
                if (firstKey == null) firstKey = b
            }
            col.addView(grid)
            redraw()
            col.post { firstKey?.requestFocus() }
            col
        }
        dlg.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { press('0' + (code - KeyEvent.KEYCODE_0)); true }
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { press('0' + (code - KeyEvent.KEYCODE_NUMPAD_0)); true }
                KeyEvent.KEYCODE_DEL -> { press('<'); true }
                else -> false
            }
        }
    }
}

/** Wspólne operacje serwisowe: log i zrzuty na dekoder, aktualizacja aplikacji. */
object Tools {
    private val ui = Handler(Looper.getMainLooper())

    fun logName(prefs: Prefs) = "e2stream-${prefs.fileTag}.log"

    fun uploadLog(prefs: Prefs, manual: Boolean, msg: (String) -> Unit) {
        if (!prefs.logFtp && !manual) return
        val host = prefs.host
        if (host.isBlank()) return
        val user = prefs.ftpUser
        val pass = prefs.ftpPass
        val dir = prefs.ftpDir
        val name = logName(prefs)
        val data = AppLog.snapshot()
        Thread {
            try {
                val where = Upload.send(prefs, name, data)
                if (manual) ui.post { msg("Log wysłany: $where") }
            } catch (e: Exception) {
                AppLog.markDirty()
                if (manual) ui.post { msg("Nie udało się wysłać logu: ${e.message}") }
            }
        }.start()
    }

    /** Wysyła wszystkie zrzuty stall-*.ts przez FTP. */
    fun uploadDumps(ctx: Context, prefs: Prefs, msg: (String) -> Unit) {
        val dirs = listOfNotNull(Storage.dir(ctx, prefs), ctx.getExternalFilesDir(null), ctx.filesDir).distinct()
        val files = dirs.flatMap { d ->
            d.listFiles { f -> f.name.startsWith("stall-") && f.name.endsWith(".ts") }?.toList() ?: emptyList()
        }.distinctBy { it.name }.sortedBy { it.name }
        if (files.isEmpty()) { msg("Brak zrzutów TS"); return }
        val host = prefs.host
        val user = prefs.ftpUser
        val pass = prefs.ftpPass
        val dir = prefs.ftpDir
        msg("Wysyłam ${files.size} zrzut(ów) TS…")
        Thread {
            var ok = 0
            var err = ""
            for (f in files) {
                try {
                    Upload.send(prefs, f.name, f.readBytes())
                    f.delete()      // wysłany — miejsce na urządzeniu wolne
                    ok++
                    AppLog.i("zrzut TS wysłany na dekoder: $dir/${f.name}")
                } catch (e: Exception) {
                    err = e.message ?: e.toString()
                    AppLog.i("wysyłanie ${f.name} nieudane: $e")
                }
            }
            ui.post {
                msg(if (err.isEmpty()) "Wysłano $ok zrzut(ów) do $dir" else "Wysłano $ok/${files.size}. Błąd: $err")
            }
        }.start()
    }

    fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    fun checkUpdate(a: Activity, manual: Boolean, msg: (String) -> Unit) {
        if (manual) msg("Sprawdzam aktualizację…")
        Thread {
            val info = try { Updater.latest() } catch (e: Exception) {
                AppLog.i("aktualizacja: błąd $e"); null
            }
            ui.post {
                if (a.isFinishing) return@post
                val cur = appVersion(a)
                when {
                    info == null -> if (manual) msg("Nie udało się sprawdzić aktualizacji")
                    Updater.newer(info.version, cur) -> when {
                        manual -> askUpdate(a, info, msg)
                        Prefs(a).autoUpdate -> {
                            AppLog.i("aktualizacja automatyczna: ${info.version}")
                            msg("Aktualizacja do wersji ${info.version}…")
                            downloadAndInstall(a, info, msg)
                        }
                        else -> msg("Dostępna wersja ${info.version} — Ustawienia → Sprawdź aktualizację")
                    }
                    else -> if (manual) msg("Masz najnowszą wersję ($cur)")
                }
            }
        }.start()
    }

    private fun downloadAndInstall(a: Activity, info: Updater.Info, msg: (String) -> Unit) {
        AppLog.i("aktualizacja: pobieram ${info.url}")
        Thread {
            try {
                var last = -1
                val apk = Updater.download(a, info) { pct ->
                    if (pct / 10 != last) { last = pct / 10; ui.post { msg("Pobieranie $pct%") } }
                }
                ui.post {
                    if (!Updater.install(a, apk)) {
                        msg("Zezwól E2Stream na instalowanie aplikacji i spróbuj ponownie")
                    }
                }
            } catch (e: Exception) {
                AppLog.i("aktualizacja: błąd pobierania $e")
                ui.post { msg("Błąd pobierania: ${e.message}") }
            }
        }.start()
    }

    private fun askUpdate(a: Activity, info: Updater.Info, msg: (String) -> Unit) {
        TvDialog.confirm(a, "Aktualizacja E2Stream",
            "Dostępna wersja ${info.version} (masz ${appVersion(a)}). Pobrać i zainstalować?",
            listOf(TvDialog.Btn("Zainstaluj") { downloadAndInstall(a, info, msg) }, TvDialog.Btn("Nie teraz")))
    }
}
