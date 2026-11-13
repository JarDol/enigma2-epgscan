package pl.jardol.e2stream

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
                    TvDialog.dismissAll()
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
     * Wpisywanie bez systemowej klawiatury:
     *  - cyfry z pilota (jeśli ma),
     *  - strzałki: ▲▼ zmienia cyfrę na bieżącej pozycji, ▶ / OK — następna pozycja, ◀ — poprzednia,
     *  - przytrzymane OK pokazuje / chowa klawiaturę na ekranie (piloty bez cyfr, gdy wygodniej).
     */
    fun ask(a: Activity, prefs: Prefs, cancelable: Boolean = true, onCancel: () -> Unit = {}, onOk: () -> Unit) {
        if (isUnlocked(prefs)) { onOk(); return }
        val pin = prefs.pin
        val n = pin.length
        val digits = IntArray(n) { -1 }
        var pos = 0
        var done = false
        var longOk = false
        val boxes = ArrayList<android.widget.TextView>()
        lateinit var info: android.widget.TextView
        lateinit var pad: android.view.View
        lateinit var dlg: android.app.Dialog
        fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
        fun redraw() {
            for (i in 0 until n) {
                val tv = boxes[i]
                tv.text = when {
                    digits[i] < 0 -> ""
                    i == pos && pad.visibility != View.VISIBLE -> digits[i].toString()   // edytowana cyfra widoczna
                    else -> "•"
                }
                (tv.background as android.graphics.drawable.GradientDrawable).setStroke(
                    dp(if (i == pos) 3 else 2), if (i == pos) TvDialog.BLUE else 0xFF607D8B.toInt())
            }
        }
        fun check() {
            if (digits.all { it >= 0 } && digits.joinToString("") == pin) {
                done = true; unlock(prefs); dlg.dismiss(); onOk()
            } else {
                info.text = "Błędny PIN — spróbuj ponownie"
                info.setTextColor(0xFFEF9A9A.toInt())
                for (i in 0 until n) digits[i] = -1
                pos = 0
                redraw()
            }
        }
        fun next() {
            if (digits[pos] < 0) digits[pos] = 0
            if (pos == n - 1) check() else { pos++; redraw() }
        }
        fun typeDigit(d: Int) {
            digits[pos] = d
            if (pos == n - 1) { redraw(); check() } else { pos++; redraw() }
        }
        fun back() {
            if (digits[pos] >= 0 && pos == n - 1) digits[pos] = -1
            else if (pos > 0) { pos--; digits[pos] = -1 }
            redraw()
        }
        val requiredWidthPx = dp(70 * n + 72)
        val widthFrac = (requiredWidthPx.toFloat() / a.resources.displayMetrics.widthPixels)
            .coerceIn(0.42f, 0.9f)
        dlg = TvDialog.panel(a, "Ustawienia zablokowane", null, widthFrac = widthFrac, maxH = 0.9f,
            onBack = { if (!done) { done = true; onCancel() } }) { ctx, _ ->      // WSTECZ zawsze anuluje
            val col = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                isFocusable = true
            }
            val row = android.widget.LinearLayout(ctx).apply { gravity = android.view.Gravity.CENTER }
            repeat(n) {
                val tv = android.widget.TextView(ctx).apply {
                    gravity = android.view.Gravity.CENTER; textSize = 28f; setTextColor(TvDialog.WHITE)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat(); setColor(0x22FFFFFF)
                    }
                }
                boxes += tv
                row.addView(tv, android.widget.LinearLayout.LayoutParams(dp(56), dp(64)).apply { marginStart = dp(7); marginEnd = dp(7) })
            }
            col.addView(row)
            info = android.widget.TextView(ctx).apply {
                text = "Cyfry z pilota albo ▲▼ zmiana cyfry, ▶ / OK dalej, ◀ wstecz\nprzytrzymaj OK — klawiatura na ekranie"
                textSize = 14f; setTextColor(TvDialog.GREY); gravity = android.view.Gravity.CENTER
                setPadding(0, dp(14), 0, dp(6))
            }
            col.addView(info)
            val grid = android.widget.GridLayout(ctx).apply { columnCount = 3; visibility = View.GONE }
            pad = grid
            for (k in listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '<', '0', '>')) {
                val b = android.widget.Button(ctx, null, 0, R.style.TvDialogButton)
                b.text = when (k) { '<' -> "⌫"; '>' -> "OK"; else -> k.toString() }
                b.textSize = 20f
                b.setOnClickListener {
                    when (k) { '<' -> back(); '>' -> check(); else -> typeDigit(k - '0') }
                }
                grid.addView(b, android.widget.GridLayout.LayoutParams().apply {
                    width = dp(76); height = dp(52); setMargins(dp(5), dp(8), dp(5), 0)
                })
            }
            col.addView(grid, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL })
            col
        }
        redraw()
        fun togglePad() {
            if (pad.visibility == View.VISIBLE) {
                pad.visibility = View.GONE
                (pad.parent as View).requestFocus()
            } else {
                pad.visibility = View.VISIBLE
                (pad as android.view.ViewGroup).getChildAt(0)?.requestFocus()
            }
            redraw()
        }
        dlg.setOnKeyListener { _, code, e ->
            val padOn = pad.visibility == View.VISIBLE
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                // przytrzymane OK — klawiatura na ekranie; krótkie OK (przy puszczeniu) — następna pozycja
                if (e.action == KeyEvent.ACTION_DOWN) {
                    if (e.repeatCount > 0) {
                        if (!longOk) { longOk = true; togglePad() }
                        return@setOnKeyListener true
                    }
                    return@setOnKeyListener !padOn
                }
                if (e.action == KeyEvent.ACTION_UP) {
                    if (longOk) { longOk = false; return@setOnKeyListener true }
                    if (!padOn) { next(); return@setOnKeyListener true }
                }
                return@setOnKeyListener false
            }
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { typeDigit(code - KeyEvent.KEYCODE_0); true }
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { typeDigit(code - KeyEvent.KEYCODE_NUMPAD_0); true }
                KeyEvent.KEYCODE_DEL -> { back(); true }
                else -> {
                    if (padOn) return@setOnKeyListener false        // klawiatura na ekranie: strzałki chodzą po klawiszach
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_UP -> { digits[pos] = if (digits[pos] < 0) 0 else (digits[pos] + 1) % 10; redraw(); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { digits[pos] = if (digits[pos] < 0) 9 else (digits[pos] + 9) % 10; redraw(); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { next(); true }
                        KeyEvent.KEYCODE_DPAD_LEFT -> { back(); true }
                        else -> false
                    }
                }
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
            val releases = try { Updater.releases(Prefs(a).updateChannel) } catch (e: Exception) {
                AppLog.i("aktualizacja: błąd $e"); null
            }
            ui.post {
                if (a.isFinishing) return@post
                val cur = appVersion(a)
                when {
                    releases == null -> if (manual) msg("Nie udało się sprawdzić aktualizacji")
                    manual -> chooseRelease(a, releases, cur, msg)
                    releases.firstOrNull()?.let { Updater.newer(it.version, cur) } == true -> {
                        val info = releases.first()
                        when {
                            Prefs(a).autoUpdate -> {
                                AppLog.i("aktualizacja automatyczna: ${info.version}")
                                msg("Aktualizacja do wersji ${info.version}…")
                                downloadAndInstall(a, info, msg)
                            }
                            else -> msg("Dostępna wersja ${info.version} — Ustawienia → Sprawdź aktualizację")
                        }
                    }
                }
            }
        }.start()
    }

    private fun chooseRelease(a: Activity, releases: List<Updater.Info>, current: String, msg: (String) -> Unit) {
        val choices = releases.filter { it.version != current }
        if (choices.isEmpty()) {
            msg(if (releases.isEmpty()) "Nie znaleziono wydań E2Stream" else "Masz najnowszą wersję ($current)")
            return
        }
        val latest = releases.firstOrNull()?.version
        TvDialog.list(
            a,
            "Wybierz wersję E2Stream",
            choices.map { info ->
                val age = when {
                    info.version == latest -> "najnowsza"
                    Updater.newer(info.version, current) -> "nowsza"
                    else -> "starsza"
                }
                TvDialog.Item(info.version, listOf(age, if (info.prerelease) "beta" else "stable").joinToString(" • "))
            },
            selected = 0,
            widthFrac = 0.55f,
        ) { selected -> askUpdate(a, choices[selected], current, msg) }
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
                    Thread {
                        try {
                            val started = Updater.install(a, apk)
                            if (!started) ui.post { if (!a.isFinishing) msg("Zezwól E2Stream na instalowanie aplikacji i spróbuj ponownie") }
                        } catch (e: Exception) {
                            AppLog.i("aktualizacja: błąd instalacji $e")
                            ui.post { if (!a.isFinishing) msg("Błąd instalacji: ${e.message}") }
                        }
                    }.start()
                }
            } catch (e: Exception) {
                AppLog.i("aktualizacja: błąd pobierania $e")
                ui.post { msg("Błąd pobierania: ${e.message}") }
            }
        }.start()
    }

    private fun askUpdate(a: Activity, info: Updater.Info, current: String, msg: (String) -> Unit) {
        val downgrade = Updater.newer(current, info.version)
        val note = if (downgrade) {
            "\n\nAndroid może odmówić instalacji starszej wersji; ta możliwość zależy od systemu urządzenia."
        } else ""
        TvDialog.confirm(a, "Aktualizacja E2Stream",
            "Wybrana wersja ${info.version} (masz $current). Pobrać i zainstalować?$note",
            listOf(TvDialog.Btn("Zainstaluj") { downloadAndInstall(a, info, msg) }, TvDialog.Btn("Nie teraz")))
    }
}
