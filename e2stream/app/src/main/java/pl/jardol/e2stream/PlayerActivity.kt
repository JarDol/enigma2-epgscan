package pl.jardol.e2stream

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class PlayerActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }


    companion object {
        const val EXTRA_WAKE_TIMER_KEY = "zap_alarm_timer_key"
        const val EXTRA_WAKE_TEST_ID = "zap_alarm_test_id"
        const val EXTRA_SCHEDULE_REF = "schedule_channel_ref"
        const val ACTION_SCHEDULE_ZAP = "pl.jardol.e2stream.SCHEDULE_ZAP"
    }

    private var decTimerSel = 0
    private var zapTimerSel = 0

    private fun showDecoderTimers() {
        showMessage(getString(R.string.message_loading_timers_from_receiver))
        io.execute {
            val result = try { api.decoderTimers() to null } catch (e: Exception) { null to (e.message ?: e.toString()) }
            runOnUiThread {
                val timers = result.first
                if (timers == null) {
                    showMessage(getString(R.string.player_timers_failed, result.second))
                    return@runOnUiThread
                }
                if (timers.isEmpty()) {
                    showMessage(getString(R.string.message_no_timers_on_the_receiver))
                    return@runOnUiThread
                }
                val now = System.currentTimeMillis() / 1000
                val future = timers.filter { it.end > now }
                val completed = timers.filter { it.end <= now }
                val labels = ArrayList<TvDialog.Item>()
                val actions = ArrayList<DecoderTimer?>()
                fun addSection(title: String, section: List<DecoderTimer>) {
                    if (section.isEmpty()) return
                    labels += TvDialog.Item(title, header = true)
                    actions += null
                    for (timer in section) {
                        labels += TvDialog.Item(timer.title.ifBlank { timer.channel },
                            "${if (timer.disabled) getString(R.string.player_disabled_prefix) else ""}${timer.channel}  •  ${zapDf.format(Date(timer.begin * 1000))}  •  ${getString(if (timer.switchOnly) R.string.player_switch_type else R.string.player_record_type)}")
                        actions += timer
                    }
                }
                addSection(getString(R.string.player_future), future)
                addSection(getString(R.string.player_completed), completed)
                // po szczegółach / usunięciu lista wraca na tę samą pozycję (wyjście WSTECZ)
                val first = labels.indexOfFirst { !it.header }
                val at = decTimerSel.takeIf { it in labels.indices && !labels[it].header }
                    ?: labels.indices.lastOrNull { it <= decTimerSel && !labels[it].header } ?: first
                TvDialog.list(this, getString(R.string.message_receiver_timers), labels, at,
                    hint = getString(R.string.player_details_hint), widthFrac = 0.75f, onCancel = { decTimerSel = 0 }) { selected ->
                    decTimerSel = selected
                    actions[selected]?.let { showDecoderTimer(it) }
                }
            }
        }
    }

    private fun showDecoderTimer(timer: DecoderTimer) {
        val description = listOf(
            timer.channel,
            "${zapDf.format(Date(timer.begin * 1000))} – ${SimpleDateFormat("HH:mm", Locale("pl", "PL")).format(Date(timer.end * 1000))}",
            getString(if (timer.switchOnly) R.string.player_switch_type_title else R.string.player_record_type_title),
            if (timer.disabled) getString(R.string.player_disabled) else "",
            timer.description,
        ).filter { it.isNotBlank() }.joinToString("\n")
        TvDialog.confirm(this, timer.title.ifBlank { timer.channel }, description,
            listOf(TvDialog.Btn(getString(R.string.message_delete_from_receiver)) { confirmDeleteDecoderTimer(timer) }),
            onBack = { showDecoderTimers() }, timeoutSec = prefs.actionDialogSec,
            onTimeout = { showDecoderTimers() })
    }

    private fun confirmDeleteDecoderTimer(timer: DecoderTimer) {
        TvDialog.confirm(this, getString(R.string.message_delete_timer_from_receiver), "${timer.channel}\n${timer.title}\n${zapDf.format(Date(timer.begin * 1000))}",
            listOf(
                TvDialog.Btn(getString(R.string.message_delete)) {
                    io.execute {
                        val result = try { api.timerDelete(timer) } catch (e: Exception) { false to (e.message ?: e.toString()) }
                        runOnUiThread {
                            val (title, message) = if (result.first) {
                                getString(R.string.player_timer_deleted) to "${timer.channel}\n${timer.title}"
                            } else {
                                getString(R.string.player_timer_delete_failed) to "${timer.title}\n${result.second}"
                            }
                            TvDialog.info(this@PlayerActivity, title, message) { showDecoderTimers() }
                        }
                    }
                }),
            onBack = { showDecoderTimers() }, onTimeout = { showDecoderTimers() })
    }

    private lateinit var prefs: Prefs
    private lateinit var api: OpenWebif
    private lateinit var epg: EpgUi
    private lateinit var epgStore: EpgStore
    private var keyMap: MutableMap<Int, Act> = LinkedHashMap(KeyMap.defaults)
    private lateinit var bannerIcon: android.widget.ImageView
    private lateinit var bannerClock: TextView
    private lateinit var bannerTime: TextView
    private lateinit var bannerStart: TextView
    private lateinit var bannerEnd: TextView
    private lateinit var bannerNext: TextView
    private lateinit var bannerProgress: android.widget.ProgressBar
    private lateinit var infoDesc: TextView
    private lateinit var infoTitle: TextView
    private lateinit var infoBox: View
    private val hm = SimpleDateFormat("HH:mm", Locale("pl", "PL"))

    private lateinit var playerView: PlayerView
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var list: ListView
    private lateinit var banner: LinearLayout
    private lateinit var bannerName: TextView
    private lateinit var bannerNow: TextView
    private lateinit var numEntry: TextView
    private lateinit var stats: TextView
    private lateinit var message: TextView

    private var player: ExoPlayer? = null
    private var watchdog: StreamWatchdog? = null
    private val ui = Handler(Looper.getMainLooper())
    // zadania zgłoszone po zamknięciu okna (np. opóźnione EIT, odpowiedzi sieci) są pomijane zamiast awarii
    private val io: java.util.concurrent.ExecutorService = quietSingleThread()
    private val probeIo: java.util.concurrent.ExecutorService = quietSingleThread()

    private var bouquets: List<Bouquet> = emptyList()
    private var playBouquet = 0
    private var playList: List<Channel> = emptyList()
    private var playIndex = -1
    private var prevBouquet = -1
    private var prevRef: String? = null

    private var panelBouquet = 0
    private var panelList: List<Channel> = emptyList()
    private val adapter = ChannelAdapter()

    private var currentUrl: String? = null
    private var restarts = 0
    private var lastRestartReason = "-"
    private var failStreak = 0
    private var restartPending = false
    private var pendingReason = ""
    private var settingsVersion = -1
    private var settingsShown = false
    private var safeMode = false
    private val playbackRecovery = PlaybackRecovery()
    private var vlcFailureDialog: Dialog? = null
    private var numberBuffer = ""
    private var suppressOkUp = false
    private var okAction = Act.DEFAULT_OK
    private val backExit = DoubleBackExit()
    @Volatile private var streamGen = 0
    private var analyzer: TsAnalyzer? = null
    private var analyzerRing = -1
    private var lastDumpAt = 0L
    private var updateChecked = false
    private var epgStartupPending = false
    private var epgReturnFromSettings = false
    private var streamStartAt = 0L
    private var decoderAvailableAt = 0L
    private var firstStreamByteLogged = false
    private var firstStreamFrameLogged = false
    private var readyLogged = false
    private var lastStartMs = -1L
    private var audioAppliedGen = -1
    private var audioCheckGen = -1
    private var audioCheckStarted = 0L
    private var audioWindow = AudioSelectionWindow()
    private var audioCheckLastTarget: String? = null
    private var audioCheckDescription = ""
    private var wakeTimerKey: String? = null
    private var wakeTestId: String? = null
    private var scheduledChannelRef: String? = null
    private var scheduledAppliedRef: String? = null      // kanał z harmonogramu dostrojony przed końcem startu
    private var scheduledAppliedAt = 0L
    private var connectionDialog: Dialog? = null
    private var connectionProbePending = false
    private var connectionProbeId = 0
    private var decoderUnavailable = false
    private var disconnectedRecordingPosition = 0L
    private var lastProbeAt = 0L
    @Volatile private var startupProbeGeneration = 0
    private var backupCheckRunning = false
    private var backupDialogOpen = false
    private var backupCheckGeneration = 0

    // ---------------------------------------------------------------- cykl życia

    /** Ręczna aktualizacja: koniec odtwarzania, buforów (także w tle) i nagrań lokalnych (zapisane jak przy wyjściu). */
    private val stopForUpdate: () -> Unit = {
        ui.post {
            if (!isDestroyed && (player != null || vlc != null || tsRec != null || bgChans.isNotEmpty())) releasePlayer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tools.beforeManualUpdate = stopForUpdate
        wakeTimerKey = intent.getStringExtra(EXTRA_WAKE_TIMER_KEY)
        wakeTestId = intent.getStringExtra(EXTRA_WAKE_TEST_ID)
        scheduledChannelRef = intent.getStringExtra(EXTRA_SCHEDULE_REF)
        if ((wakeTimerKey != null || wakeTestId != null) && Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)
        api = OpenWebif(prefs)
        prefs.migrate()
        AppLog.init(this, Storage.dir(this, prefs))
        if (Storage.followTimeshift(prefs)) AppLog.init(this, Storage.dir(this, prefs))
        tsCleanupAtStart()
        val appCtx = applicationContext
        val storePrefs = prefs
        Thread({
            try {
                val freed = AppStorage.cleanup(appCtx)
                val parts = AppStorage.report(appCtx, storePrefs)
                AppLog.i("pamięć aplikacji: ${AppStorage.reportLine(parts)}" +
                    if (freed > 0) " (usunięto z pamięci podręcznej ${AppStorage.mb(freed)}: pobrane aktualizacje i stare pliki)" else "")
            } catch (e: Exception) { AppLog.i("pamięć aplikacji: $e") }
        }, "pamiec-aplikacji").start()
        epgStore = EpgStore(this)
        epg = EpgUi(this, api, io, epgStore, object : EpgUi.Host {
            override fun current(): Channel? = playList.getOrNull(playIndex)
            override fun bouquetChannels(): List<Channel> = playList
            override fun bouquetList(): List<Bouquet> = this@PlayerActivity.bouquets.filter { !isVirtual(it.ref) }
            override fun currentBouquet(): Bouquet? = bouquets.getOrNull(playBouquet)?.takeIf { !isVirtual(it.ref) }
            override fun zapTo(ref: String, onResult: ((Boolean, String) -> Unit)?) =
                this@PlayerActivity.zapTo(ref, onResult = onResult)
            override fun message(t: String) = showMessage(t)
        })
        AppLog.i("=== Start E2Stream ${appVersion()} | ${Build.MANUFACTURER} ${Build.MODEL} | ${prefs.displayName} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ekran: ${displayInfo()}")
        if (wakeTimerKey != null || wakeTestId != null) {
            AppLog.i("wybudzanie: PlayerActivity uruchomiona z alarmu key=$wakeTimerKey testId=$wakeTestId")
        }

        playerView = findViewById(R.id.playerView)
        vlcLayout = findViewById(R.id.vlcLayout)
        zapFrame = findViewById(R.id.zapFrame)
        panel = findViewById(R.id.panel)
        panelTitle = findViewById(R.id.panelTitle)
        list = findViewById(R.id.list)
        banner = findViewById(R.id.banner)
        bannerName = findViewById(R.id.bannerName)
        bannerNow = findViewById(R.id.bannerNow)
        bannerIcon = findViewById(R.id.bannerIcon)
        bannerClock = findViewById(R.id.bannerClock)
        bannerTime = findViewById(R.id.bannerTime)
        bannerStart = findViewById(R.id.bannerStart)
        bannerEnd = findViewById(R.id.bannerEnd)
        bannerNext = findViewById(R.id.bannerNext)
        bannerProgress = findViewById(R.id.bannerProgress)
        infoDesc = findViewById(R.id.infoDesc)
        infoTitle = findViewById(R.id.infoTitle)
        infoBox = findViewById(R.id.infoBox)
        list.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                panelList.getOrNull(pos)?.let { showInfo(it) }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        numEntry = findViewById(R.id.numEntry)
        stats = findViewById(R.id.stats)
        message = findViewById(R.id.message)

        list.adapter = adapter
        // dotyk (tablet/telefon): stuknięcie = lista kanałów, przytrzymanie = menu
        playerView.setOnClickListener { if (panel.visibility == View.VISIBLE) hidePanel() else showPanel() }
        playerView.setOnLongClickListener { showQuickMenu(); true }
        list.setOnItemClickListener { _, _, pos, _ -> onPanelPick(pos) }
        // karuzela: z ostatniego kanału w dół na pierwszy, z pierwszego w górę na ostatni
        list.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN || panelList.isEmpty()) return@setOnKeyListener false
            val pos = list.selectedItemPosition
            when {
                code == KeyEvent.KEYCODE_DPAD_DOWN && pos >= panelList.size - 1 -> { list.setSelection(0); true }
                code == KeyEvent.KEYCODE_DPAD_UP && pos == 0 -> { list.setSelection(panelList.size - 1); true }
                else -> false
            }
        }
        // długie OK na liście: opis bieżącej audycji zaznaczonego kanału
        list.isLongClickable = true
        list.setOnItemLongClickListener { _, _, pos, _ ->
            panelList.getOrNull(pos)?.let { c ->
                val r = c.rec
                val bg = if (r == null) bgOf(c) else null
                if (bg != null) epg.showCurrentEvent(c) { ev -> bgRecButtons(bg, c, ev) }
                else if (r == null && isMainBuf(c)) epg.showCurrentEvent(c) { ev -> mainRecButtons(c, ev) }
                else if (r == null) epg.showCurrentEvent(c)
                else if (!r.isDir) showRecordingActions(r)
            }
            true
        }

        // bezpiecznik PiP: poprzednia sesja skończyła się z otwartymi oknami (awaria, zabicie, zawieszenie Androida)
        if (prefs.pipActive) {
            prefs.pipActive = false
            PipLog.i("bezpiecznik: poprzednia sesja zakończyła się z otwartym PiP; ustawienie PiP=${prefs.pipEnabled}")
            if (prefs.pipEnabled) {
                ui.postDelayed({ if (!isFinishing) showPipTripped() }, 2500)
            }
        }
        val crash = prefs.lastCrash
        var attempt = prefs.playbackAttempt
        // próba przerwana aktualizacją aplikacji (instalacja zamknęła proces w trakcie startu kanału) — to nie awaria
        val attemptAt = attempt.substringAfterLast(" @", "").toLongOrNull()
        if (attempt.isNotEmpty() && crash.isEmpty()) {
            val updated = try { packageManager.getPackageInfo(packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }
            if (attemptAt != null && updated >= attemptAt - 60_000) {   // instalacja tuż przed próbą albo po niej
                AppLog.i("start: poprzednia próba odtwarzania przerwana aktualizacją aplikacji — bez trybu bezpiecznego")
                prefs.playbackAttempt = ""
                attempt = ""
            }
        }
        attempt = attempt.substringBeforeLast(" @")
        if (crash.isNotEmpty() || attempt.isNotEmpty()) {
            prefs.lastCrash = ""
            val details = buildList {
                if (crash.isNotEmpty()) add(getString(R.string.player_crash_details, crash.lines().take(14).joinToString("\n")))
                if (attempt.isNotEmpty()) add(getString(R.string.player_attempt_details, attempt))
            }.joinToString("\n\n")
            AppLog.i("start w trybie bezpiecznym; poprzednia awaria lub nieukończony start")
            showRecoveryDialog(details)
        }
    }

    override fun onStart() {
        super.onStart()
        if (recreateForLanguage()) return
        if (!epgReturnFromSettings) epgStartupPending = true
        epgReturnFromSettings = false
        AppState.foreground = true
        TvDialog.onInput = { sleepInput() }
        sleepAt = 0
        val now = SystemClock.elapsedRealtime()
        if (sleepLogStartedAt == 0L) sleepLogStartedAt = now
        sleepStatusLoggedAt = now
        AppLog.i("auto-zamknięcie: limit bezczynności ${prefs.sleepHours} h")
        ui.removeCallbacks(sleepTick)
        ui.post(sleepTick)
        ZapWakeAlarms.setAppForeground(this, true)
        ZapWakeAlarms.sync(this)
        if (safeMode) return
        startUp()
        showWakeTestResult()
        ui.removeCallbacks(netTick); ui.postDelayed(netTick, 30_000)
    }

    private fun showPipTripped() {
        PipLog.i("pokazuję komunikat o problemie z PiP; ustawienie PiP pozostaje ${prefs.pipEnabled}")
        TvDialog.confirm(this, getString(R.string.pip_tripped_title), getString(R.string.pip_tripped_msg), listOf(
            TvDialog.Btn(getString(R.string.action_send_log)) { uploadLog(true) },
            TvDialog.Btn(getString(R.string.message_close))))
    }

    private fun showRecoveryDialog(detail: String, stopPlayback: Boolean = false) {
        safeMode = true
        startupProbeGeneration++
        if (stopPlayback) {
            ui.removeCallbacks(restartRunnable)
            ui.removeCallbacks(connectionGuardTick)
            ui.removeCallbacks(epgTick)
            ui.removeCallbacks(zapTick)
            connectionProbePending = false
            dismissConnectionDialog()
            releasePlayer()
        }
        TvDialog.confirm(
            this,
            getString(R.string.message_starting_in_safe_mode),
            getString(R.string.player_safe_details, detail),
            listOf(
                TvDialog.Btn(getString(R.string.message_open_safe_menu)) {
                    AppLog.i("tryb bezpieczny: otwieram menu bez dekodera")
                    showQuickMenu()
                },
                TvDialog.Btn(getString(R.string.message_go_to_settings)) { openSettings() },
                TvDialog.Btn(getString(R.string.message_close)) { finish() },
            ),
            cancelable = false,
        )
    }

    private fun markPlaybackHealthy() {
        ui.removeCallbacks(restartRunnable)
        restartPending = false
        playbackRecovery.markHealthy()
        if (prefs.playbackAttempt.isNotEmpty()) {
            prefs.playbackAttempt = ""
            AppLog.i("start: odtwarzanie uznane za działające")
        }
        failStreak = 0
    }

    private fun retryNormalStartup() {
        if (!safeMode || isFinishing) return
        safeMode = false
        settingsVersion = -1
        playbackRecovery.resetSession()
        AppLog.i("tryb bezpieczny: użytkownik jawnie ponawia normalny start")
        startUp()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        wakeTimerKey = intent.getStringExtra(EXTRA_WAKE_TIMER_KEY)
        wakeTestId = intent.getStringExtra(EXTRA_WAKE_TEST_ID)
        scheduledChannelRef = intent.getStringExtra(EXTRA_SCHEDULE_REF) ?: scheduledChannelRef
        AppLog.i("wybudzanie: onNewIntent key=$wakeTimerKey testId=$wakeTestId")
        if ((wakeTimerKey != null || wakeTestId != null) && Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (safeMode) return
        zapPrompted = null
        ui.removeCallbacks(zapTick)
        ui.post(zapTick)
        showWakeTestResult()
        applyScheduledChannel()
    }

    private fun showWakeTestResult() {
        val id = wakeTestId ?: return
        wakeTestId = null
        AppLog.i("wybudzanie: pokazuję popup testu id=$id")
        ui.post {
            if (!isFinishing) TvDialog.confirm(this, getString(R.string.message_wake_up_test_received),
                getString(R.string.player_test_details, id),
                listOf(TvDialog.Btn(getString(R.string.message_close))))
        }
    }

    private fun startUp() {
        if (safeMode) {
            AppLog.i("tryb bezpieczny: pomijam połączenie z dekoderem i odtwarzanie")
            return
        }
        AppLog.i("start: host=${prefs.host} ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}")
        if (prefs.host.isBlank()) {
            if (settingsShown) { finish(); return }
            settingsShown = true
            openSettings()
            return
        }
        if (SyncManager.needsBackupDecision) {
            checkInitialBackup()
            return
        }
        AppLog.init(this, Storage.dir(this, prefs))
        epg.eitOnly = prefs.eitOnlyActive
        if (epg.eitOnly) AppLog.i("EPG: tryb testowy EIT — EPG tylko ze strumienia, bez pobierania z dekodera")
        stats.visibility = if (prefs.showStats) View.VISIBLE else View.GONE
        keyMap = KeyMap.load(prefs)
        AvShift.ms = prefs.avShiftMs; vlc?.applyAudioDelay()
        UiScale.apply(banner, UiScale.pct(prefs.uiBannerFont), UiScale.pct(prefs.uiBannerIcon))
        UiScale.apply(message, UiScale.pct(prefs.uiBannerFont))
        ui.removeCallbacks(epgTick)
        ui.postDelayed(epgTick, 15_000)
        ui.removeCallbacks(zapTick)
        ui.postDelayed(zapTick, if (wakeTimerKey != null) 0 else 5000)
        ensureAnalyzer()
        buildPlayer()
        ui.removeCallbacks(connectionGuardTick)
        ui.post(connectionGuardTick)
        applyDisplayMode()
        if (!updateChecked) {
            updateChecked = true
            ui.postDelayed({ checkUpdate(false) }, 8000)
        }
        ui.removeCallbacks(logTick)
        ui.postDelayed(logTick, 120_000)
        SyncManager.onSettingsApplied = {
            if (!isFinishing) {
                AppLog.i("synchronizacja: zastosowano ustawienia z dekodera — przeładowanie")
                if (!recreateForLanguage()) {
                    releasePlayer()
                    settingsVersion = -1
                    startUp()
                }
            }
        }
        SyncManager.syncAsync()
        if (backgrounded) {
            // Android trzyma aplikację w pamięci; po powrocie z ekranu głównego zachowujemy się jak przy
            // uruchomieniu: bez otwartej listy, kanał startowy albo ostatni (wg ustawień)
            backgrounded = false
            if (bgChans.isNotEmpty()) {
                AppLog.i("start: pozostałe bufory w tle po powrocie (${bgChans.joinToString { it.name }}) — zamykam")
                bgDropAll("powrót do aplikacji")
            }
            if (panel.visibility == View.VISIBLE) hidePanel()
            settingsVersion = -1
        }
        if (settingsVersion != prefs.version || bouquets.isEmpty()) {
            settingsVersion = prefs.version
            loadBouquets()
        } else {
            currentUrl?.let { startStream(it) }
            applyScheduledChannel()
        }
    }

    private fun checkInitialBackup() {
        if (backupCheckRunning || backupDialogOpen) return
        backupCheckRunning = true
        val generation = ++backupCheckGeneration
        val host = prefs.host
        showMessage(getString(R.string.message_checking_settings_backups_on_receiver))
        io.execute {
            val result = runCatching {
                if (!PluginApi.check(prefs)) null
                else SyncManager.profiles(prefs).filter { it.backup != null || it.data.length() > 0 }
            }
            runOnUiThread {
                if (generation != backupCheckGeneration) return@runOnUiThread
                backupCheckRunning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (prefs.host != host) { startUp(); return@runOnUiThread }
                result.onFailure { error ->
                    AppLog.i("kopia ustawień: błąd pobierania: $error")
                    showBackupCheckError(getString(R.string.player_backup_check_error, error.message ?: error))
                }.onSuccess { profiles ->
                    if (profiles == null) {
                        showBackupCheckError(getString(R.string.player_backup_plugin_missing))
                    } else if (profiles.isEmpty()) {
                        AppLog.i("kopia ustawień: brak profili na dekoderze")
                        SyncManager.finishBackupDecision()
                        startUp()
                    } else {
                        AppLog.i("kopia ustawień: znaleziono ${profiles.size} profili")
                        showInitialBackupChoice(profiles)
                    }
                }
            }
        }
    }

    private fun showBackupCheckError(detail: String) {
        backupDialogOpen = true
        TvDialog.confirm(this, getString(R.string.message_settings_backup), getString(R.string.player_backup_retry, detail),
            listOf(
                TvDialog.Btn(getString(R.string.message_retry)) { backupDialogOpen = false; checkInitialBackup() },
                TvDialog.Btn(getString(R.string.message_continue)) { backupDialogOpen = false; SyncManager.finishBackupDecision(skipCommonAudio = true); startUp() },
                TvDialog.Btn(getString(R.string.message_settings)) { backupDialogOpen = false; openSettings() },
                TvDialog.Btn(getString(R.string.message_exit)) { finish() },
            ), cancelable = false, timeoutSec = 0)
    }

    private fun showInitialBackupChoice(profiles: List<SyncManager.Profile>) {
        backupDialogOpen = true
        TvDialog.confirm(this, getString(R.string.message_settings_backups_found),
            getString(R.string.player_backup_found),
            listOf(
                TvDialog.Btn(getString(R.string.message_choose_backup)) { backupDialogOpen = false; selectInitialBackup(profiles) },
                TvDialog.Btn(getString(R.string.message_do_not_import)) { backupDialogOpen = false; SyncManager.finishBackupDecision(skipCommonAudio = true); startUp() },
                TvDialog.Btn(getString(R.string.message_exit)) { finish() },
            ), cancelable = false, timeoutSec = 0)
    }

    private fun selectInitialBackup(profiles: List<SyncManager.Profile>) {
        backupDialogOpen = true
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag(AppLanguage.selected(this)))
        TvDialog.list(this, getString(R.string.message_choose_a_settings_backup),
            profiles.map { profile ->
                TvDialog.Item(profile.name.ifBlank { profile.model },
                    "${profile.model} • ${fmt.format(Date(profile.updated))}" +
                        if (profile.backup == null) getString(R.string.player_backup_legacy) else "")
            }, onCancel = { backupDialogOpen = false; showInitialBackupChoice(profiles) }) { index ->
            backupDialogOpen = false
            try {
                val count = SyncManager.restore(profiles[index])
                SyncManager.finishBackupDecision()
                AppLog.i("kopia ustawień: przywrócono $count ustawień")
                ZapWakeAlarms.sync(this)
                if (!recreateForLanguage()) startUp()
            } catch (e: Exception) {
                AppLog.i("kopia ustawień: błąd przywracania: $e")
                TvDialog.confirm(this, getString(R.string.message_could_not_restore_backup),
                    e.message ?: e.toString(), listOf(TvDialog.Btn(getString(R.string.message_back)) { showInitialBackupChoice(profiles) }),
                    cancelable = false, timeoutSec = 0)
                backupDialogOpen = true
            }
        }
    }

    /** Co minutę: czy przypada pobranie EPG z harmonogramu. */
    private val epgTick = object : Runnable {
        override fun run() {
            if (safeMode) return
            if (!prefs.eitOnlyActive && EpgSync.due(prefs, epgStartupPending)) {
                epgStartupPending = false
                if (!prefs.pluginOk) AppLog.i("EPG: pobieranie wg harmonogramu (${prefs.epgHour})")
                EpgSync.run(applicationContext, prefs, epgStore, false, { },
                    { r -> runOnUiThread { epg.clearMemory(); adapter.notifyDataSetChanged(); AppLog.i(r) } },
                    { runOnUiThread { epgStartupPending = true } })
            }
            ui.postDelayed(this, 60_000)
        }
    }

    override fun onStop() {
        super.onStop()
        backupCheckGeneration++
        backupCheckRunning = false
        backupDialogOpen = false
        startupProbeGeneration++
        epgReturnFromSettings = settingsOpen
        if (settingsOpen && !recMode) {
            val channel = playList.getOrNull(playIndex)
            val bouquet = bouquets.getOrNull(playBouquet)
            if (channel != null && bouquet != null && !isRecRef(bouquet.ref)) {
                settingsReturnChannel = bouquet.ref to channel.ref
            }
        }
        panelTimer.setActive(false)
        AppState.foreground = false
        AppState.recording = activeRecs() > 0
        ui.removeCallbacks(sleepTick)
        sleepDlg?.dismiss(); sleepDlg = null
        TvDialog.onInput = null
        ZapWakeAlarms.setAppForeground(this, false)
        settingsOpen = false      // po powrocie i tak pełny start (onStart)
        backgrounded = true       // wyjście do ekranu głównego — po powrocie start jak przy uruchomieniu
        ui.removeCallbacks(epgTick)
        ui.removeCallbacks(zapTick)
        ui.removeCallbacks(connectionGuardTick)
        ui.removeCallbacks(netTick)
        connectionProbeId++
        connectionProbePending = false
        dismissConnectionDialog()
        dismissVlcFailureDialog()
        if (!safeMode && prefs.playbackAttempt.isNotEmpty()) prefs.playbackAttempt = ""
        releasePlayer()
        for (c in bgChans.filter { it.pip >= 0 }) c.pip = -1
        pipRefresh()
        if (prefs.pipActive) prefs.pipActive = false
        ui.removeCallbacks(logTick)
        if (!safeMode) {
            if (AppLog.dirty) uploadLog(false)
            syncChannels()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Tools.beforeManualUpdate === stopForUpdate) Tools.beforeManualUpdate = null
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
        probeIo.shutdownNow()
    }

    // ---------------------------------------------------------------- odtwarzacz

    private fun buildPlayer() {
        if (player != null) return
        playbackRecovery.resetSession()

        val trackSelector = DefaultTrackSelector(this)
        val langs = AudioTrackLabels.preferredLanguages(prefs.audioLang)
        val params = trackSelector.buildUponParameters()
            .setTunnelingEnabled(prefs.tunneling)
            .setPreferredAudioLanguages(*langs.toTypedArray())
            // napisy DVB nie są wyświetlane — wyłączona ścieżka nie trzyma danych w pamięci odtwarzacza
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        if (prefs.preferAc3) {
            params.setPreferredAudioMimeTypes(MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3)
        }
        trackSelector.setParameters(params.build())

        // Bez AudioTrack PlaybackParams: przy tunelowaniu zmiana prędkości przez AudioTrack psuła płynność
        // obrazu (MiBox), więc zwolnienie działa tylko bez tunelowania (procesor dźwięku Sonic).
        val renderers = E2RenderersFactory(this).setEnableDecoderFallback(true)

        // Dwa bufory: krótki na start (szybki obraz) i zapas w trakcie oglądania. Strumień na żywo
        // przychodzi w tempie odtwarzania, więc zapas rośnie tylko przy lekko zwolnionym odtwarzaniu
        // (cushionTick) albo po niedoborze — wtedy odtwarzacz czeka na pełny zapas.
        val start = prefs.bufferMs.coerceIn(250, 10_000)
        val reserve = maxOf(start, prefs.rebufferMs.coerceIn(1000, 15_000))
        // ExoPlayer wymaga: minBuffer ≥ bufor startowy i ≥ bufor po niedoborze
        val minBuf = maxOf(start * 2, reserve + 5000, 10_000)
        val alloc = androidx.media3.exoplayer.upstream.DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        allocator = alloc
        val load = DefaultLoadControl.Builder()
            .setAllocator(alloc)
            // Enigma2 wysyła dane paczkami (~0,5 MB) z przerwami do ~1,5 s (SD: nawet 3–4 s)
            .setBufferDurationsMs(minBuf, maxOf(minBuf, 20_000), start, reserve)
            .build()
        cushionTarget = reserve.toLong()

        val p = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(load)
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                AppLog.i("BŁĄD ${error.errorCodeName}: ${error.message} / ${error.cause}")
                scheduleRestart("błąd: ${error.errorCodeName}")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val n = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    else -> "ENDED"
                }
                AppLog.i("stan: $n (bufor ${p.totalBufferedDuration} ms" +
                    (if (playbackState == Player.STATE_BUFFERING) ", wczytuje: ${if (p.isLoading) "tak" else "NIE"}, pamięć ${memMb()} MB" else "") + ")")
                if (playbackState == Player.STATE_READY && !readyLogged) {
                    readyLogged = true
                    AppLog.i("start: READY po ${SystemClock.elapsedRealtime() - streamStartAt} ms")
                    if (recMode) recItem?.let { r ->
                        val local = LocalRecordings.isLocal(r.path)
                        val parts = if (local) LocalRecordings.parts(LocalRecordings.dirOf(r.path)).size else 0
                        AppLog.i("nagranie: długość wg odtwarzacza ${if (p.duration > 0) fmtMs(p.duration) else "nieznana"}, " +
                            "wg opisu ${fmtMs(r.lengthSec * 1000)}, przewijanie: ${if (p.isCurrentMediaItemSeekable) "tak" else "NIE"}" +
                            (if (local) ", plików: $parts" else ""))
                    }
                }
                if (playbackState == Player.STATE_ENDED) {
                    if (recMode) recEnded() else if (tsMode) tsGoLive("koniec bufora") else scheduleRestart("koniec strumienia")
                }
            }

            override fun onRenderedFirstFrame() {
                if (!tsMode) logFirstStreamFrame("ExoPlayer: pierwsza klatka")
                markPlaybackHealthy()
            }

            override fun onTracksChanged(tracks: Tracks) {
                // kanał bez obrazu (radio) — stary obraz nie czeka na pierwszą klatkę, której nie będzie
                if (!tracks.isEmpty && tracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }) hideZapFrame()
                val aud = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (aud.isNotEmpty() && aud.none { it.isSelected }) {
                    val available = audioTracks().firstOrNull()
                    if (available != null) {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .setOverrideForType(TrackSelectionOverride(available.group.mediaTrackGroup, available.index))
                            .build()
                        AppLog.i("dźwięk: brak automatycznego wyboru, wymuszam ${audioLabel(available.format)} (id=${available.format.id})")
                    } else {
                        AppLog.i("dźwięk: żadna ścieżka nie jest odtwarzana (" + aud.joinToString { g ->
                            val f = g.getTrackFormat(0)
                            "${f.sampleMimeType} ${if (g.isTrackSupported(0)) "obsługiwana" else "brak dekodera"}"
                        } + ")")
                    }
                }
                if (tsMode) applyTimeshiftAudio()
                else if (recMode) applySavedAudio()
                else if (prefs.engine != "vlc" && aud.isNotEmpty() &&
                    p.currentMediaItem?.localConfiguration?.uri?.toString() == currentUrl) {
                    startAudioCheck()
                }
            }
        })

        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                AppLog.i("utracone klatki: $droppedFrames w $elapsedMs ms")
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                AppLog.i("wideo: ${format.width}x${format.height} ${format.sampleMimeType} fps=${format.frameRate} id=${format.id}")
                if (prefs.afrMode == 2) ui.post { applyDisplayMode() }
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                AppLog.i("audio: ${format.sampleMimeType} ${format.channelCount}ch ${format.sampleRate} Hz lang=${format.language} id=${format.id}")
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                AppLog.i("dekoder wideo: $decoderName (tunelowanie ${if (prefs.tunneling) "wł." else "wył."})")
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                AppLog.i("dekoder audio: $decoderName")
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                AppLog.i("audio underrun (bufor $bufferSizeMs ms, od ostatnich danych $elapsedSinceLastFeedMs ms)")
            }
        })

        playerView.player = p
        player = p

        if (prefs.watchdog) {
            val stall = prefs.stallSec.coerceIn(1, 30) * 1000L
            watchdog = StreamWatchdog(
                player = p,
                stallMs = stall,
                bufferingMs = maxOf(stall * 2, cushionTarget + 6000L, 8000L),
                onStall = { reason -> scheduleRestart(reason) },
                onHealthy = { failStreak = 0 },
                dropRestart = prefs.dropRestart,
                startMs = start.toLong(),
                reserveMs = reserve.toLong(),
            ).also { it.start() }
        }
        ui.removeCallbacks(statsTick)
        ui.post(statsTick)
        ui.removeCallbacks(tsTick)
        ui.postDelayed(tsTick, 10_000)
        ui.removeCallbacks(cushionTick)
        ui.postDelayed(cushionTick, 1000)
        ui.removeCallbacks(loadGuard)
        loadStuckSince = 0
        ui.postDelayed(loadGuard, 3000)
        ui.removeCallbacks(pmtCheck)
        ui.postDelayed(pmtCheck, 500)
    }

    private fun ensureAnalyzer() {
        val ring = if (prefs.saveTs) prefs.tsRingMb.coerceIn(2, 64) * 1_000_000 else 0
        if (analyzer == null || ring != analyzerRing) {
            analyzer = TsAnalyzer(ring) { msg -> AppLog.i(msg) }.also { a ->
                // zapamiętany PMT kanału, a gdy go nie ma — PMT zbudowany z PID-ów z dekodera (EPG Scan)
                a.pmtCacheGet = { sid -> prefs.pmtFor(sid) ?: PmtBuilder.build(sid, prefs.pidsFor(sid)) }
                a.pmtCachePut = { sid, b -> prefs.setPmtFor(sid, b) }
                mainAnalyzerHooks(a)
            }
            analyzerRing = ring
        }
        analyzer?.clean = prefs.cleanTs
        // kopia strumienia na żywo do bufora timeshiftu (nie przy nagraniach z dekodera)
        analyzer?.tee = { b, o, l -> if (!recMode && SystemClock.elapsedRealtime() >= tsWriteFrom) tsBuf?.write(b, o, l) }
    }

    /** Analizator bieżącego kanału: EIT do EPG i dziennika, kopia do bufora bieżącego kanału. */
    private fun mainAnalyzerHooks(a: TsAnalyzer) {
        a.onEit = { session, _, now, next -> ui.post { onStreamEit(session, now, next) } }
        a.tee = { b, o, l -> if (!recMode && SystemClock.elapsedRealtime() >= tsWriteFrom) tsBuf?.write(b, o, l) }
    }

    // --- EIT ze strumienia
    private var eitChannel: Channel? = null
    private var eitSession = -1L
    private var eitPending: Pair<EitEvent?, EitEvent?>? = null
    private var eitMissingLogged = false

    /** Nowa wersja EIT teraz/następne z analizatora; krótka zwłoka, bo „teraz” i „następne” przychodzą osobno. */
    private fun onStreamEit(session: Long, now: EitEvent?, next: EitEvent?) {
        if (session != eitSession || eitChannel == null) return
        eitPending = now to next
        ui.removeCallbacks(eitApply)
        ui.postDelayed(eitApply, 800)
    }

    private val eitApply = Runnable {
        val ch = eitChannel ?: return@Runnable
        val (now, next) = eitPending ?: return@Runnable
        eitPending = null
        if (playList.getOrNull(playIndex) !== ch) return@Runnable
        now?.let { journalAdd(it.begin, it.end, it.title, it.shortDesc, it.longDesc) }
        epg.applyEit(ch, listOfNotNull(now, next), prefs.eitReplace) { changed ->
            if (!changed || playList.getOrNull(playIndex) !== ch) return@applyEit
            now?.let { ch.now = it.title; ch.nowBegin = it.begin; ch.nowEnd = it.end }
            adapter.notifyDataSetChanged()
            if (banner.visibility == View.VISIBLE && tsChBar) showBanner(ch)
        }
    }

    /** Opcja włączona, a z dekodera nie przychodzi PID 0x12 - podpowiedź w logu (raz na uruchomienie aplikacji). */
    private val eitMissingCheck = Runnable {
        val a = analyzer ?: return@Runnable
        if (!a.eitEnabled || eitMissingLogged || a.eitPackets > 0 || a.sessionId != eitSession) return@Runnable
        eitMissingLogged = true
        AppLog.i("EIT: dekoder nie wysyła EIT w strumieniu (Enigma2: Include EIT in http streams wyłączone?)")
    }

    private var encrypted = false
    private var scrHits = 0
    private var scrTick = 0

    /** Kanał, którego dekoder nie rozkodowuje: > 80% pakietów danych kodowanych przez ~4 s. */
    private fun checkScrambled() {
        val a = analyzer ?: return
        if (++scrTick < 4) return
        scrTick = 0
        val (pk, scr) = a.takeScramble()
        if (pk < 200) return
        val ratio = scr.toDouble() / pk
        if (!encrypted && ratio > 0.8) {
            if (++scrHits >= 2) {
                encrypted = true
                watchdog?.paused = true
                ui.removeCallbacks(restartRunnable); restartPending = false
                AppLog.i("kanał kodowany: dekoder nie rozkodowuje (${(ratio * 100).toInt()}% pakietów kodowanych) — bez restartów")
                showMessage(getString(R.string.message_encrypted_channel_receiver_is_not_decrypting_it_card_so))
            }
        } else if (ratio < 0.2) {
            scrHits = 0
            if (encrypted) {
                encrypted = false
                watchdog?.paused = false
                AppLog.i("kanał rozkodowany — wznawiam odtwarzanie")
                currentUrl?.let { startStream(it) }
            }
        }
    }

    /** Szybki restart, gdy dekoder nie przysłał PMT, a odtwarzacz wciąż buforuje. */
    private val pmtCheck = object : Runnable {
        override fun run() {
            checkScrambled()
            val a = analyzer
            val p = player
            if (!recMode && !pvActive && !encrypted && a != null && p != null && p.playbackState == Player.STATE_BUFFERING &&
                p.videoFormat == null && a.pmtMissingMs() > 3000
            ) {
                scheduleRestart("brak PMT w strumieniu ${a.pmtMissingMs()} ms")
            }
            ui.postDelayed(this, 500)
        }
    }

    private var lastRendered = -1L
    private var lastDropped = -1L

    // ---------------------------------------------------------------- zapas w trakcie oglądania

    private var cushionTarget = 4000L
    private var cushionSpeed = 1f
    private var cushionSlowSince = 0L
    private var cushionSlowFrom = 0L
    private var cushionDisabled = false     // zwolnienie nie działa na tym urządzeniu (sesja)
    private var cushionReached = false
    private var cushionLowStreak = 0
    private val cushionHist = LongArray(4)
    private var cushionN = 0

    private fun cushionReset() {
        cushionSlowSince = 0
        cushionReached = false
        cushionLowStreak = 0
        cushionN = 0
        cushionSetSpeed(1f)
    }

    private fun cushionSetSpeed(v: Float) {
        if (v == cushionSpeed) return
        cushionSpeed = v
        try { player?.playbackParameters = PlaybackParameters(v, 1f) } catch (_: Exception) {}
    }

    private var allocator: androidx.media3.exoplayer.upstream.DefaultAllocator? = null
    private var loadStuckSince = 0L

    private fun memMb() = (allocator?.totalBytesAllocated ?: 0) / 1_000_000

    /**
     * Co 250 ms: ExoPlayer przestał wczytywać dane, choć zapas prawie się skończył (np. zapchana pamięć
     * bufora). Sam ExoPlayer czeka wtedy 4 s i zgłasza „Playback stuck buffering” — obraz stoi ~7 s.
     * Tu strumień jest przeładowany od razu, zwykle jeszcze zanim obraz stanie.
     */
    private val loadGuard = object : Runnable {
        override fun run() {
            ui.postDelayed(this, 250)
            val p = player ?: return
            if (restartPending || !p.playWhenReady) { loadStuckSince = 0; return }
            // nagranie: koniec pliku — wszystko wczytane, to nie zawieszenie (bez wznawiania przed ENDED)
            if (recMode && p.duration > 0 && p.bufferedPosition >= p.duration - 1500) { loadStuckSince = 0; return }
            val st = p.playbackState
            val buf = p.totalBufferedDuration
            val stuck = !p.isLoading && (st == Player.STATE_BUFFERING || (st == Player.STATE_READY && buf < 1000))
            if (!stuck) { loadStuckSince = 0; return }
            val now = SystemClock.elapsedRealtime()
            if (loadStuckSince == 0L) {
                loadStuckSince = now
                AppLog.i("bufor: ExoPlayer nie wczytuje danych (stan ${if (st == Player.STATE_READY) "READY" else "BUFFERING"}, zapas $buf ms, pamięć ${memMb()} MB)")
                return
            }
            if (now - loadStuckSince >= 700) {
                loadStuckSince = 0
                scheduleRestart("ExoPlayer przestał wczytywać (zapas $buf ms, pamięć ${memMb()} MB)")
            }
        }
    }

    /** Raz na sekundę: gdy zapas (minimum z ostatnich 4 s — paczki z dekodera) mniejszy od docelowego, odtwarzanie 0,95×. */
    private val cushionTick = object : Runnable {
        override fun run() {
            ui.postDelayed(this, 1000)
            val p = player ?: return
            if (p.playbackState != Player.STATE_READY || !p.playWhenReady) {
                if (p.playbackState == Player.STATE_BUFFERING) cushionSlowSince = 0
                return
            }
            cushionHist[cushionN % cushionHist.size] = p.totalBufferedDuration
            cushionN++
            if (cushionN < cushionHist.size) return
            val low = cushionHist.minOrNull() ?: return
            val now = SystemClock.elapsedRealtime()
            if (!prefs.cushionSlow || prefs.tunneling || cushionDisabled) { cushionSetSpeed(1f); return }
            if (low >= cushionTarget) {
                if (cushionSpeed != 1f) {
                    cushionSetSpeed(1f)
                    if (!cushionReached) {
                        cushionReached = true
                        AppLog.i("zapas: ${low} ms po ${(now - streamStartAt) / 1000} s od startu — normalna prędkość")
                    }
                }
                cushionSlowSince = 0
                return
            }
            // poniżej celu (z małą histerezą, żeby nie przełączać przy każdej paczce)
            if (cushionSpeed == 1f && (!cushionReached || low < cushionTarget - 1500)) {
                cushionSetSpeed(0.95f)
                cushionSlowSince = now
                cushionSlowFrom = low
            }
            // kontrola: po 40 s zwolnienia zapas powinien urosnąć o ~2 s; jeśli nie — urządzenie nie zwalnia
            if (cushionSpeed != 1f && cushionSlowSince > 0 && now - cushionSlowSince > 40_000) {
                if (low - cushionSlowFrom < 600) {
                    cushionDisabled = true
                    cushionSetSpeed(1f)
                    AppLog.i("zapas: zwolnienie nie działa na tym urządzeniu" +
                        (if (prefs.tunneling) " (tunelowanie)" else "") + " — zapas tylko po niedoborze (${cushionTarget} ms)")
                } else {
                    cushionSlowSince = now
                    cushionSlowFrom = low
                }
            }
        }
    }

    private val tsTick = object : Runnable {
        override fun run() {
            val c = player?.videoDecoderCounters
            if (c != null) {
                c.ensureUpdated()
                val r = c.renderedOutputBufferCount.toLong()
                val d = c.droppedBufferCount.toLong()
                if (lastRendered in 0..r && lastDropped in 0..d) {
                    val dd = d - lastDropped
                    if (dd > 0) {
                        AppLog.i("dekoder 10 s: pokazane +${r - lastRendered}, utracone +$dd")
                        analyzer?.forceNext = true
                    }
                }
                lastRendered = r
                lastDropped = d
            } else {
                lastRendered = -1; lastDropped = -1
            }
            analyzer?.interval()?.let { AppLog.i(it) }
            ui.postDelayed(this, 10_000)
        }
    }

    private fun releasePlayer() {
        ui.removeCallbacks(audioCheck)
        audioCheckGen = -1
        tsStopAll()
        ui.removeCallbacks(vlcTick)
        proxy?.stop(); proxy = null          // najpierw źródło danych — VLC kończy odczyt
        vlc?.release(); vlc = null
        watchdog?.stop()
        watchdog = null
        ui.removeCallbacks(restartRunnable)
        ui.removeCallbacks(statsTick)
        ui.removeCallbacks(tsTick)
        ui.removeCallbacks(cushionTick)
        ui.removeCallbacks(loadGuard)
        ui.removeCallbacks(pmtCheck)
        restartPending = false
        playerView.player = null
        player?.release()
        player = null
    }

    private fun buildSource(url: String): MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("E2Stream")
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(6000)
            .setAllowCrossProtocolRedirects(true)
        api.authHeader()?.let { http.setDefaultRequestProperties(mapOf("Authorization" to it)) }

        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val generation = streamGen
        val local = if (LocalRecordings.isLocal(url)) LocalRecordings.dirOf(url) else null
        val analyzing = DataSource.Factory {
            AnalyzingDataSource(if (local != null) LocalRecordingDataSource(local) else http.createDataSource()) {
                if (generation == streamGen) analyzer else null
            }
        }
        return ProgressiveMediaSource.Factory(analyzing, AvShift.wrap(extractors))
            .createMediaSource(MediaItem.fromUri(url))
    }

    private fun startStream(url: String, startMs: Long = 0, availableAt: Long = 0) {
        if (safeMode) return
        val p = player ?: return
        currentUrl = url
        connectionProbeId++
        connectionProbePending = false
        streamGen++
        ui.removeCallbacks(audioCheck)
        audioCheckGen = -1
        streamStartAt = SystemClock.elapsedRealtime()
        decoderAvailableAt = availableAt
        firstStreamByteLogged = false
        firstStreamFrameLogged = false
        readyLogged = false
        cushionReset()
        lastStartMs = -1
        // SID serwisu = 4. pole referencji (hex), np. 1:0:19:3DD2:640:13E:820000:0:0:0:
        val sref = if (recMode) recItem?.sref.orEmpty().ifBlank { url.substringAfterLast('/') } else url.substringAfterLast('/')
        val sid = sref.split(":").getOrNull(3)?.toIntOrNull(16) ?: -1
        proxy?.invalidate()
        // odtwarzanie przez bufor: połączenie zapisu do bufora działa dalej (ten sam kanał) — analizator bez zmian
        val bufReuse = !recMode && bufLive && tsRec != null && tsRecAn != null && tsRecAn === analyzer && tsLiveUrl == url && bufReady()
        if (!bufReuse) analyzer?.reset(sid)
        // EIT „teraz/następne” ze strumienia (opcja; nie przy nagraniach z dysku dekodera)
        eitChannel = if (!recMode) playList.getOrNull(playIndex) else null
        analyzer?.eitEnabled = prefs.eitStream && eitChannel != null && sid >= 0
        eitSession = analyzer?.sessionId ?: -1
        eitPending = null
        ui.removeCallbacks(eitApply)
        ui.removeCallbacks(eitMissingCheck)
        if (analyzer?.eitEnabled == true) ui.postDelayed(eitMissingCheck, 20_000)
        // nagranie z pliku: bez poprawiania strumienia — po przewinięciu znaczniki czasu skaczą względem
        // ostatniego PCR i „poprawka” przesuwała dźwięk (obraz wyprzedzał dźwięk)
        analyzer?.clean = if (recMode) false else prefs.cleanTs
        if (decoderUnavailable && !recMode) {
            p.stop()
            proxy?.stop(); proxy = null
            ui.removeCallbacks(vlcTick)
            vlc?.release(); vlc = null
            return
        }
        if (!playbackRecovery.hasPlayed) prefs.playbackAttempt = "${appVersion()} / ${prefs.engine.uppercase()} @${System.currentTimeMillis()}"
        if (!recMode && prefs.bufPlay) {
            if (bufReady()) { startBufLive(url, bufReuse); return }
            if (bufLive) bufStop("nośnik niedostępny — odtwarzanie bezpośrednie")
        } else if (bufLive) bufStop(if (recMode) "nagranie" else "opcja wyłączona")
        if (!bgAllowed()) bgDropAll("opcja wyłączona")
        else if (recMode) { }                 // odtwarzanie nagrania — kanały w tle (zapis, ostatni kanał) zostają
        else if (prefs.bgCount == 0) for (c in bgChans.filter { it.pip < 0 && it.lrec == null }) bgDrop(c, "opcja wyłączona")
        else while (bgChans.count { it.pip < 0 } > prefs.bgCount)
            bgChans.filter { it.pip < 0 && !bgProtected(it) }.minByOrNull { it.leftAt }?.let { bgDrop(it, "limit ${prefs.bgCount} kanałów w tle") } ?: break
        pipRefresh()
        if (prefs.engine == "vlc" && !recMode) {
            startVlc(url)
            return
        }
        if (recMode) {        // nagrania zawsze przez ExoPlayer (przewijanie w pliku)
            ui.removeCallbacks(vlcTick)
            proxy?.stop(); proxy = null
            vlc?.release(); vlc = null
        }
        vlcLayout.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        if (startMs > 0) p.setMediaSource(buildSource(url), startMs) else p.setMediaSource(buildSource(url))
        p.prepare()
        p.playWhenReady = true
        // tylko na żywo: nagranie czyta z dysku (inny przebieg startu)
        watchdog?.newStream(prefs.fastStart && !recMode)
    }

    private fun logFirstStreamByte() {
        val first = analyzer?.firstFeedAt ?: 0L
        if (firstStreamByteLogged || first <= 0 || first < streamStartAt) return
        firstStreamByteLogged = true
        val fromProbe = if (decoderAvailableAt > 0) ", ${first - decoderAvailableAt} ms od testu dekodera" else ""
        AppLog.i("start: pierwszy bajt po ${first - streamStartAt} ms$fromProbe")
    }

    private fun logFirstStreamFrame(source: String) {
        hideZapFrame()
        if (firstStreamFrameLogged || streamStartAt <= 0) return
        logFirstStreamByte()
        val now = SystemClock.elapsedRealtime()
        lastStartMs = now - streamStartAt
        val first = analyzer?.firstFeedAt ?: 0L
        val fromByte = if (first >= streamStartAt && first > 0) ", ${now - first} ms od pierwszych bajtów" else ""
        val fromProbe = if (decoderAvailableAt > 0) ", ${now - decoderAvailableAt} ms od testu dekodera" else ""
        AppLog.i("start: $source po $lastStartMs ms$fromByte$fromProbe")
        firstStreamFrameLogged = true
    }

    // ---------------------------------------------------------------- VLC

    private lateinit var vlcLayout: org.videolan.libvlc.util.VLCVideoLayout
    private var vlc: VlcEngine? = null
    private var proxy: TsProxy? = null
    private var vlcLastTime = -1L
    private var vlcLastMove = 0L
    private var vlcProgressAt = 0L
    private var vlcProgressPosition = 0L

    private fun startVlc(url: String, fromBuf: TimeshiftBuffer? = null, bufStart: Long = 0) {
        player?.stop()
        playerView.visibility = View.GONE
        vlcLayout.visibility = View.VISIBLE
        val px = proxy ?: TsProxy({ analyzer }, { api.authHeader() }).also { it.start(); proxy = it }
        px.setTarget(url)
        if (fromBuf != null) px.setBuffer(fromBuf, bufStart) { analyzer?.psiHeader() }
        val v = vlc ?: run {
            lateinit var created: VlcEngine
            created = VlcEngine(this, prefs, vlcLayout,
                onEvent = { m ->
                    val generation = streamGen
                    ui.post {
                        if (vlc !== created || generation != streamGen) return@post
                        if (m == "VLC: odtwarzanie") startAudioCheck()
                        if (!readyLogged) {
                            readyLogged = true
                            AppLog.i("start: $m po ${SystemClock.elapsedRealtime() - streamStartAt} ms")
                        }
                    }
                },
                onFailure = { r -> if (vlc === created) scheduleRestart(r) })
            vlc = created
            created
        }
        AppLog.i("VLC: ${v.info()}")
        vlcLastTime = -1
        vlcLastMove = SystemClock.elapsedRealtime()
        vlcProgressAt = 0
        vlcProgressPosition = 0
        v.play(px.url())
        ui.postDelayed({
            Thread { vlc?.vlcLog()?.forEach { AppLog.i("VLC log: $it") } }.start()
        }, 6000)
        ui.removeCallbacks(vlcTick)
        ui.postDelayed(vlcTick, 250)
    }

    /** Zawieszenie przy VLC: czas odtwarzania stoi dłużej niż próg (po starcie: 15 s). */
    private val vlcTick = object : Runnable {
        override fun run() {
            val v = vlc ?: return
            if (prefs.engine != "vlc") return
            val now = SystemClock.elapsedRealtime()
            logFirstStreamByte()
            if (!firstStreamFrameLogged && v.displayedPictures() > 0) {
                val sampleMs = if (now - streamStartAt >= 15_000) 1000 else 250
                logFirstStreamFrame("VLC: pierwsza klatka wg licznika (próbkowanie do $sampleMs ms)")
                markPlaybackHealthy()
            }
            ui.postDelayed(this, if (firstStreamFrameLogged || now - streamStartAt >= 15_000) 1000 else 250)
            val t = v.time()
            if (t != vlcLastTime) {
                vlcLastTime = t
                vlcLastMove = now
                if (v.playing && t >= 0) {
                    if (vlcProgressAt == 0L || t < vlcProgressPosition) {
                        vlcProgressAt = now
                        vlcProgressPosition = t
                    } else if (now - vlcProgressAt >= 10_000 && t - vlcProgressPosition >= 2000) {
                        if (!playbackRecovery.hasPlayed) {
                            AppLog.i("VLC: czas odtwarzania postępuje, licznik pierwszej klatki niedostępny")
                            markPlaybackHealthy()
                        }
                        playbackRecovery.resetVlcRestarts()
                        vlcProgressAt = now
                        vlcProgressPosition = t
                    }
                }
                return
            }
            val limit = if (v.playing) prefs.stallSec.coerceIn(1, 30) * 1000L + 2000 else 15_000L
            if (prefs.watchdog && now - vlcLastMove > limit) {
                vlcLastMove = now
                scheduleRestart("VLC: obraz stoi ${limit / 1000} s")
            }
        }
    }

    private val restartRunnable = Runnable { doRestart() }

    private val connectionGuardTick = object : Runnable {
        override fun run() {
            if (isFinishing || backgrounded) return
            ui.postDelayed(this, 1000)
            if (currentUrl == null || (recMode && !decoderUnavailable)) return
            logFirstStreamByte()
            val now = SystemClock.elapsedRealtime()
            val lastFeed = analyzer?.lastFeedAt ?: 0L
            if (decoderUnavailable) {
                if (!connectionProbePending && now - lastProbeAt >= 3000) probeStalledStream()
                return
            }
            val lastData = lastFeed.takeIf { it > 0 } ?: streamStartAt
            if (lastData <= 0 || now - lastData < 8000) return
            if (!connectionProbePending && now - lastProbeAt >= 3000) probeStalledStream()
        }
    }

    private fun probeStalledStream(onReachableWithData: (() -> Unit)? = null) {
        if (connectionProbePending) return
        val url = currentUrl ?: return
        val generation = streamGen
        val probeId = ++connectionProbeId
        connectionProbePending = true
        lastProbeAt = SystemClock.elapsedRealtime()
        probeIo.execute {
            val result = try { api.probe() } catch (e: java.io.IOException) {
                DecoderProbe.Result(false, "${e.javaClass.simpleName}: ${e.message}")
            } catch (e: IllegalArgumentException) {
                DecoderProbe.Result(false, "${e.javaClass.simpleName}: ${e.message}")
            }
            runOnUiThread {
                if (probeId != connectionProbeId) return@runOnUiThread
                connectionProbePending = false
                if (isFinishing || backgrounded || generation != streamGen || currentUrl != url) return@runOnUiThread
                if (!result.reachable) {
                    if (!decoderUnavailable && recMode) disconnectedRecordingPosition = player?.currentPosition ?: 0
                    if (!decoderUnavailable) AppLog.i("dekoder: brak komunikacji (${result.detail})")
                    decoderUnavailable = true
                    playbackRecovery.resetVlcRestarts()
                    if (prefs.playbackAttempt.isNotEmpty()) prefs.playbackAttempt = ""
                    ui.removeCallbacks(restartRunnable)
                    restartPending = false
                    dismissVlcFailureDialog()
                    if (tsMode || bufLive) {
                        tsRec?.stop(); tsRec = null
                        if (!tsMode) { player?.stop(); proxy?.stop(); proxy = null; vlc?.release(); vlc = null }
                    } else {
                        ui.removeCallbacks(vlcTick)
                        proxy?.stop(); proxy = null
                        vlc?.release(); vlc = null
                        player?.stop()
                    }
                    if (prefs.blockOnDisconnect) showConnectionDialog(false)
                    else if (!connectionDialogShown()) showMessage(getString(R.string.player_unreachable_retry, prefs.host))
                    return@runOnUiThread
                }
                val wasUnavailable = decoderUnavailable
                decoderUnavailable = false
                dismissConnectionDialog()
                if (wasUnavailable) AppLog.i("dekoder: komunikacja przywrócona")
                val availableAt = SystemClock.elapsedRealtime()
                val lastData = analyzer?.lastFeedAt?.takeIf { it > 0 } ?: streamStartAt
                if (wasUnavailable || availableAt - lastData >= 8000) {
                    if (vlcFailureDialog?.isShowing == true && !wasUnavailable) return@runOnUiThread
                    if (!wasUnavailable) showMessage(getString(R.string.message_receiver_responds_but_no_stream_is_arriving_reconnectin))
                    if (tsMode) restartTimeshiftRecorder(url, generation) else {
                        if (bufLive) { tsRec?.stop(); tsRec = null }          // nowe połączenie zapisu
                        failStreak = 0
                        restartPending = false
                        ui.removeCallbacks(restartRunnable)
                        val position = if (recMode) {
                            if (wasUnavailable) disconnectedRecordingPosition else (player?.currentPosition ?: 0)
                        } else 0
                        startStream(url, position, availableAt)
                    }
                } else onReachableWithData?.invoke()
            }
        }
    }

    private fun connectionDialogShown() = connectionDialog?.isShowing == true

    private fun showConnectionDialog(startup: Boolean) {
        if (connectionDialogShown()) {
            TvDialog.setMessage(connectionDialog, getString(R.string.player_cannot_connect, prefs.host))
            return
        }
        val buttons = mutableListOf(
            TvDialog.Btn(getString(R.string.message_wait), closeOnClick = false) {
                TvDialog.setMessage(connectionDialog, getString(R.string.player_wait_receiver, prefs.host))
            },
            TvDialog.Btn(getString(if (startup) R.string.message_exit else R.string.message_close)) { finish() },
        )
        if (startup) buttons += TvDialog.Btn(getString(R.string.message_settings)) {
            startupProbeGeneration++
            settingsVersion = -1
            openSettings(onCancel = { if (!isFinishing && !backgrounded) loadBouquets() })
        }
        connectionDialog = TvDialog.confirm(
            this,
            getString(R.string.message_cannot_communicate_with_receiver),
            getString(R.string.player_cannot_connect, prefs.host),
            buttons,
            cancelable = false,
            timeoutSec = 0,
        )
    }

    private fun dismissConnectionDialog() {
        connectionDialog?.let { if (it.isShowing) it.dismiss() }
        connectionDialog = null
    }

    private fun dismissVlcFailureDialog() {
        vlcFailureDialog?.let { if (it.isShowing) it.dismiss() }
        vlcFailureDialog = null
    }

    private fun showVlcPlaybackFailure(reason: String) {
        if (vlcFailureDialog?.isShowing == true) return
        AppLog.i("VLC: przerwano automatyczne restarty po ${playbackRecovery.vlcRestarts} próbach: $reason")
        ui.removeCallbacks(vlcTick)
        vlcFailureDialog = TvDialog.confirm(
            this,
            getString(R.string.player_vlc_failure_title),
            getString(R.string.player_vlc_failure_message),
            listOf(
                TvDialog.Btn(getString(R.string.message_retry)) {
                    vlcFailureDialog = null
                    playbackRecovery.resetVlcRestarts()
                    failStreak = 0
                    currentUrl?.let { startStream(it) }
                },
                TvDialog.Btn(getString(R.string.message_settings)) {
                    vlcFailureDialog = null
                    openSettings()
                },
                TvDialog.Btn(getString(R.string.message_close)) { finish() },
            ),
            cancelable = false,
            timeoutSec = 0,
        )
    }

    private fun scheduleRestart(reason: String) {
        if (safeMode || decoderUnavailable || currentUrl == null || restartPending || encrypted ||
            vlcFailureDialog?.isShowing == true) return
        restartPending = true
        pendingReason = reason
        val delays = longArrayOf(0, 1000, 2000, 5000, 10_000)
        val d = delays[minOf(failStreak, delays.size - 1)]
        failStreak++
        dumpStall(reason)
        AppLog.i("WZNOWIENIE za $d ms: $reason")
        pipHoldUntil = SystemClock.elapsedRealtime() + 8000
        ui.postDelayed(restartRunnable, d)
    }

    private fun doRestart() {
        restartPending = false
        val url = currentUrl ?: return
        if (safeMode) return
        if (decoderUnavailable || vlcFailureDialog?.isShowing == true) return
        if (connectionProbePending) {
            restartPending = true
            ui.postDelayed(restartRunnable, 500)
            return
        }
        val lastFeed = analyzer?.lastFeedAt ?: 0L
        val lastData = lastFeed.takeIf { it > 0 } ?: streamStartAt
        val silence = SystemClock.elapsedRealtime() - lastData
        if (silence >= 8000) {
            probeStalledStream()
            return
        }
        if (lastFeed == 0L) return
        val isVlcLive = prefs.engine == "vlc" && !tsMode && !recMode
        if (isVlcLive && playbackRecovery.vlcFailureAction() != VlcFailureAction.RETRY) {
            probeStalledStream {
                if (vlc?.playing == true && (vlc?.time() ?: 0) > 0 &&
                    SystemClock.elapsedRealtime() - vlcLastMove < 1500) {
                    AppLog.i("VLC: odtwarzanie wznowiło się przed otwarciem okna błędu")
                    return@probeStalledStream
                }
                when (playbackRecovery.vlcFailureAction()) {
                    VlcFailureAction.STARTUP_RECOVERY -> showRecoveryDialog(
                        getString(R.string.player_vlc_retries, playbackRecovery.vlcRestarts, pendingReason),
                        stopPlayback = true,
                    )
                    VlcFailureAction.PLAYBACK_RECOVERY -> showVlcPlaybackFailure(pendingReason)
                    VlcFailureAction.RETRY -> Unit
                }
            }
            return
        }
        restarts++
        lastRestartReason = pendingReason
        if (tsMode) { tsGoLive(pendingReason); return }
        if (isVlcLive) playbackRecovery.recordVlcRestart()
        showMessage(getString(R.string.player_resuming, pendingReason))
        // nagranie: od miejsca, w którym przerwało, nie od początku
        startStream(url, if (recMode) (player?.currentPosition ?: 0) else 0)
    }

    // ---------------------------------------------------------------- kanały

    /** Czy telewizor ma połączenie z siecią lokalną (Wi-Fi / Ethernet). */
    @Suppress("DEPRECATION")
    private fun networkUp(): Boolean = try {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.activeNetworkInfo?.isConnected == true
    } catch (_: Exception) { true }

    private fun loadBouquets() {
        if (safeMode) return
        val generation = ++startupProbeGeneration
        showMessage(getString(R.string.player_connecting, prefs.host))
        io.execute {
            try {
                while (generation == startupProbeGeneration && !isFinishing) {
                    val result = if (networkUp()) api.probe()
                    else DecoderProbe.Result(false, getString(R.string.player_no_network))
                    if (result.reachable) break
                    AppLog.i("dekoder: brak komunikacji przy starcie (${result.detail})")
                    runOnUiThread {
                        if (generation == startupProbeGeneration && !isFinishing) {
                            showConnectionDialog(true)
                        }
                    }
                    Thread.sleep(3000)
                }
                if (generation != startupProbeGeneration || isFinishing) return@execute
                runOnUiThread {
                    if (generation == startupProbeGeneration) {
                        decoderUnavailable = false
                        dismissConnectionDialog()
                    }
                }
                PluginApi.check(prefs)   // wtyczka EPG Scan na dekoderze?
                val all = api.bouquets()
                val hidden = prefs.hiddenBouquets
                val bq = all.filter { it.ref !in hidden }.ifEmpty { all }
                AppLog.i("bukiety: ${bq.size} z ${all.size} widocznych")
                // start: ostatni kanał albo wybrany kanał startowy
                val returnChannel = settingsReturnChannel
                val wantB = returnChannel?.first ?: if (prefs.resumeLast) prefs.lastBouquet else prefs.startBouquet
                val wantC = returnChannel?.second ?: if (prefs.resumeLast) prefs.lastChannel else prefs.startChannel
                val hist = wantB == HIST_BOUQUET && HIST_BOUQUET !in hidden && prefs.history.isNotEmpty()
                val bi = bq.indexOfFirst { it.ref == wantB }.let { if (it < 0) 0 else it }
                val ch = if (bq.isEmpty()) emptyList() else if (hist) historyChannels() else api.channels(bq[bi])
                runOnUiThread {
                    bouquets = withRec(bq)
                    val bi = if (hist) bouquets.indexOfFirst { it.ref == HIST_BOUQUET }.coerceAtLeast(0) else bi
                    if (bq.isEmpty()) {
                        showMessage(getString(R.string.message_no_bouquets_on_receiver))
                        return@runOnUiThread
                    }
                    message.visibility = View.GONE
                    val ci = ch.indexOfFirst { OpenWebif.key(it.ref) == OpenWebif.key(wantC) }.let { if (it < 0) 0 else it }
                    // kanał z harmonogramu wybrany w trakcie tego startu ma pierwszeństwo przed startowym
                    val forced = scheduledAppliedRef?.takeIf { android.os.SystemClock.elapsedRealtime() - scheduledAppliedAt < 30_000 }
                    scheduledAppliedRef = null
                    if (ch.isNotEmpty() && (forced == null || playList.isEmpty())) {
                        playIndex = -1
                        playIn(bi, ch, ci)
                        if (returnChannel != null) {
                            AppLog.i("ustawienia: powrót do oglądanego kanału ${ch[ci].name}")
                        }
                    }
                    if (returnChannel != null) settingsReturnChannel = null
                    if (forced != null) {
                        AppLog.i("harmonogram: start nie nadpisuje kanału z harmonogramu ${OpenWebif.key(forced)}")
                        zapTo(forced)
                    }
                    applyScheduledChannel()
                }
            } catch (e: Exception) {
                if (generation != startupProbeGeneration || isFinishing) return@execute
                AppLog.i("błąd połączenia z OpenWebif: $e")
                runOnUiThread {
                    showMessage(getString(R.string.player_load_failed, e.message))
                }
            }
        }
    }

    private fun applyScheduledChannel() {
        if (safeMode) return
        val ref = scheduledChannelRef ?: return
        if (bouquets.isEmpty()) return
        scheduledChannelRef = null
        scheduledAppliedRef = ref
        scheduledAppliedAt = android.os.SystemClock.elapsedRealtime()
        AppLog.i("harmonogram: Oglądaj — dostrajam kanał ${OpenWebif.key(ref)}")
        ui.post { if (!isFinishing) zapTo(ref, forceTune = true) }
    }

    /** Nowa lista bukietów (zmiana widocznych) bez przerywania odtwarzanego kanału. */
    private fun reloadBouquetsKeepStream() {
        val curRef = bouquets.getOrNull(playBouquet)?.ref
        io.execute {
            try {
                val all = api.bouquets()
                val hidden = prefs.hiddenBouquets
                val bq = all.filter { it.ref !in hidden }.ifEmpty { all }
                runOnUiThread {
                    bouquets = withRec(bq)
                    val i = bouquets.indexOfFirst { it.ref == curRef }
                    playBouquet = if (i >= 0) i else 0
                    AppLog.i("bukiety: ${bq.size} z ${all.size} widocznych")
                }
            } catch (e: Exception) {
                AppLog.i("bukiety: odświeżenie nieudane $e")
            }
        }
    }

    private fun playIn(bIdx: Int, chList: List<Channel>, index: Int) {
        if (safeMode || chList.isEmpty()) return
        if (recMode) leaveRec()
        panelOnRec = false
        tsCtl = false; tsChBar = false
        val i = ((index % chList.size) + chList.size) % chList.size
        chList[i].let { bgSwitch(api.streamUrl(it), it.name) }
        tsNewChannel()
        if (prefs.bgStatusMsg && prefs.bufPlay && prefs.bgCount > 0)
            showMessage(getString(R.string.player_bg_status, bgChans.size, prefs.bgCount, activeRecs()))
        val cur = playList.getOrNull(playIndex)
        if (cur != null && !(bIdx == playBouquet && i == playIndex)) {
            prevBouquet = playBouquet
            prevRef = cur.ref
        }
        playBouquet = bIdx
        playList = chList
        playIndex = i
        val ch = chList[i]
        prefs.lastBouquet = bouquets.getOrNull(bIdx)?.ref ?: ""
        chList[i].let { prefs.historyPush(it.ref, it.name) }
        prefs.lastChannel = ch.ref

        failStreak = 0
        playbackRecovery.resetVlcRestarts()
        ui.removeCallbacks(restartRunnable)
        restartPending = false
        encrypted = false; scrHits = 0; scrTick = 0
        watchdog?.paused = false
        analyzer?.takeScramble()
        AppLog.i("kanał: ${ch.num} ${ch.name} [${bouquets.getOrNull(bIdx)?.name}] ${api.streamUrl(ch)}")
        val gen = ++zapGen
        keepFrameThen { if (gen == zapGen) startStream(api.streamUrl(ch)) }
        showBanner(ch)
        fetchNow(ch)
    }

    private fun play(index: Int) = playIn(playBouquet, playList, index)

    // --- obraz poprzedniego kanału na czas przełączania (jak w wersji Windows): zamiast czarnego ekranu
    private lateinit var zapFrame: android.widget.ImageView
    private var zapGen = 0
    private var zapFrameBitmap: android.graphics.Bitmap? = null
    private var keepFrameLoggedFail = false
    private val zapFrameTimeout = Runnable { hideZapFrame() }

    private fun findSurface(v: View): android.view.SurfaceView? = when (v) {
        is android.view.SurfaceView -> v.takeIf { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findSurface(v.getChildAt(it)) }
        else -> null
    }

    /** Czy kopia klatki jest czarna / pusta (obraz w tunelu albo na nakładce sprzętowej nie daje się skopiować). */
    private fun isBlank(b: android.graphics.Bitmap): Boolean {
        for (y in 1..7) for (x in 1..7) {
            val c = b.getPixel(b.width * x / 8, b.height * y / 8)
            if ((c shr 16 and 0xff) > 24 || (c shr 8 and 0xff) > 24 || (c and 0xff) > 24) return false
        }
        return true
    }

    /**
     * Przed zmianą kanału: kopia ostatniej klatki (PixelCopy z powierzchni obrazu) pokazana do pierwszej klatki
     * nowego kanału; potem [go]. Najwyżej ~150 ms opóźnienia; gdy kopia się nie uda (np. tunelowanie) — od razu.
     */
    private fun keepFrameThen(go: () -> Unit) {
        if (!prefs.keepFrame || recMode || Build.VERSION.SDK_INT < 24) { hideZapFrame(); go(); return }
        if (zapFrame.visibility == View.VISIBLE) { armZapFrameTimeout(); go(); return }   // szybkie przełączanie — stary obraz zostaje
        val sv = (if (vlcLayout.visibility == View.VISIBLE) findSurface(vlcLayout) else playerView.videoSurfaceView as? android.view.SurfaceView)
            ?.takeIf { it.holder.surface.isValid && it.width > 0 && it.height > 0 }
        if (sv == null || !firstStreamFrameLogged) { go(); return }
        val w = 960
        val h = (w.toLong() * sv.height / sv.width).toInt().coerceIn(1, 1080)
        val bmp = try { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888) } catch (_: Throwable) { go(); return }
        var done = false
        val t0 = SystemClock.elapsedRealtime()
        val finish = finish@{ ok: Boolean ->
            if (done) return@finish
            done = true
            if (ok && !isBlank(bmp)) {
                zapFrameBitmap = bmp
                zapFrame.setImageBitmap(bmp)
                zapFrame.visibility = View.VISIBLE
                armZapFrameTimeout()
                AppLog.i("przełączanie: obraz poprzedniego kanału zachowany (${SystemClock.elapsedRealtime() - t0} ms)")
            } else if (!keepFrameLoggedFail) {
                keepFrameLoggedFail = true
                AppLog.i("przełączanie: obrazu nie da się skopiować" + (if (prefs.tunneling && vlcLayout.visibility != View.VISIBLE) " (tunelowanie)" else "") +
                    " — czarny ekran do pierwszej klatki")
            }
            go()
        }
        try {
            android.view.PixelCopy.request(sv, bmp, { r -> finish(r == android.view.PixelCopy.SUCCESS) }, ui)
        } catch (_: Exception) {
            finish(false); return
        }
        ui.postDelayed({ finish(false) }, 150)
    }

    private fun armZapFrameTimeout() {
        ui.removeCallbacks(zapFrameTimeout)
        ui.postDelayed(zapFrameTimeout, 12_000)       // brak obrazu nowego kanału (radio, błąd) — nie wisieć ze starym
    }

    private fun hideZapFrame() {
        if (!::zapFrame.isInitialized || zapFrame.visibility != View.VISIBLE) return
        ui.removeCallbacks(zapFrameTimeout)
        zapFrame.visibility = View.GONE
        zapFrame.setImageDrawable(null)
        zapFrameBitmap = null
    }

    private fun recall() {
        val ref = prevRef ?: return
        val b = prevBouquet
        if (b == playBouquet) {
            val i = playList.indexOfFirst { it.ref == ref }
            if (i >= 0) play(i)
            return
        }
        val bq = bouquets.getOrNull(b) ?: return
        io.execute {
            val ch = try { channelsOf(bq) } catch (e: Exception) { return@execute }
            runOnUiThread {
                val i = ch.indexOfFirst { it.ref == ref }
                if (i >= 0) playIn(b, ch, i)
            }
        }
    }

    private fun fetchNow(ch: Channel) {
        io.execute {
            val t = try { api.serviceNow(ch.ref) } catch (e: Exception) { null }
            if (t != null) runOnUiThread {
                ch.now = t
                if (playList.getOrNull(playIndex) === ch && tsChBar && banner.visibility == View.VISIBLE) {
                    bannerNow.text = t
                }
            }
        }
    }

    // ---------------------------------------------------------------- panel listy kanałów

    /** Lista „Historia” jako osobne okno (klawisz w dół): ostatnio oglądane kanały ze wszystkich bukietów. */
    private var panelHist = false
    /** Lista kanałów w trybie wyboru kanału do okna PiP. */
    private var panelPip = false

    private fun showHistory() {
        if (bouquets.isEmpty() || recMode) { showPanel(); return }
        val h = historyChannels()
        if (h.isEmpty()) { showPanel(); return }
        panelHist = true
        panelBouquet = -1
        panelList = h
        val cur = playList.getOrNull(playIndex)?.ref?.let { OpenWebif.key(it) }
        refreshPanel(h.indexOfFirst { OpenWebif.key(it.ref) == cur }.coerceAtLeast(0))
        panel.visibility = View.VISIBLE
        resetPanelTimer()
        banner.visibility = View.GONE
        list.requestFocus()
        loadPanelEpg()
    }

    private fun showPanel() {
        panelHist = false
        panelPip = false
        if (bouquets.isEmpty()) return
        // w trakcie nagrania albo po powrocie z niego — lista otwiera się na nagraniach
        if ((recMode || panelOnRec) && bouquets.any { it.ref == recBqRef }) { showRecordings(); return }
        panelBouquet = playBouquet
        panelList = playList
        refreshPanel(playIndex.coerceAtLeast(0))
        panel.visibility = View.VISIBLE
        resetPanelTimer()
        banner.visibility = View.GONE
        list.requestFocus()
        loadPanelEpg()
        playList.getOrNull(playIndex)?.let { showInfo(it) }
    }

    private fun hidePanel() {
        panelPip = false
        panelTimer.setActive(false)
        panel.visibility = View.GONE
    }

    private var sleepAt = 0L
    private var sleepDlg: Dialog? = null
    private var lastPilotInputAt = 0L
    private var lastPilotInputWall = 0L
    private var sleepLogStartedAt = 0L
    private var sleepStatusLoggedAt = 0L

    private fun sleepLimitMs() = prefs.sleepHours * 3_600_000L

    private fun notePilotInput() {
        lastPilotInputAt = SystemClock.elapsedRealtime()
        lastPilotInputWall = System.currentTimeMillis()
    }

    private fun sleepInput() {
        if (!::prefs.isInitialized || sleepDlg?.isShowing == true) return
        sleepAt = SystemClock.elapsedRealtime() + sleepLimitMs()
    }

    private fun activeRecordings(): List<LocalRec> = buildList {
        localRec?.let(::add)
        bgChans.mapNotNull { it.lrec }.forEach(::add)
    }

    private fun sleepCloseAt(idleDeadline: Long, recordings: List<LocalRec>, now: Long) =
        AutoCloseDeadline.at(
            idleDeadline,
            recordings.map {
                AutoCloseRecording(
                    if (it.mode == REC_EVENT) AutoCloseRecordingKind.PROGRAM else AutoCloseRecordingKind.BUFFER,
                    it.untilWall
                )
            },
            now,
            System.currentTimeMillis()
        )

    private fun logSleepStatus(now: Long, limit: Long, deadline: Long, recordings: List<LocalRec>) {
        if (now - sleepStatusLoggedAt < AutoCloseDeadline.LOG_INTERVAL_MS) return
        val inputAt = lastPilotInputAt.takeIf { it > 0 } ?: sleepLogStartedAt
        val inputWall = lastPilotInputWall.takeIf { it > 0 }
        val noPilotFor = (now - inputAt).coerceAtLeast(0)
        val closesAt = if (limit > 0) {
            val closeWall = System.currentTimeMillis() + (deadline - now)
            hm.format(Date(closeWall))
        } else "wyłączone"
        val recInfo = recordings.maxByOrNull { it.untilWall }?.let {
            "nagranie audycji „${it.meta.optString("title").replace(Regex("\\s+"), " ").trim()}” do ${hm.format(Date(it.untilWall))}"
        } ?: "brak nagrania audycji"
        val lastInput = inputWall?.let { hm.format(Date(it)) } ?: "od uruchomienia"
        AppLog.i("auto-zamknięcie: brak sygnału pilota przez ${fmtMs(noPilotFor)} (ostatni: $lastInput); " +
            "przewidywane zamknięcie aplikacji: $closesAt; $recInfo")
        sleepStatusLoggedAt = now
    }

    private val sleepTick = object : Runnable {
        override fun run() {
            ui.postDelayed(this, 1000)
            AppState.recording = activeRecs() > 0
            val limit = sleepLimitMs()
            val now = SystemClock.elapsedRealtime()
            val activeRecordings = activeRecordings()
            val programRecs = activeRecordings.filter { it.mode == REC_EVENT }
            if (limit > 0 && sleepAt == 0L) sleepAt = now + limit
            val closeAt = if (limit > 0) sleepCloseAt(sleepAt, activeRecordings, now) else 0L
            logSleepStatus(now, limit, closeAt, programRecs)
            if (limit <= 0) { sleepDlg?.dismiss(); sleepDlg = null; sleepAt = 0; return }
            if (settingsOpen) return
            val dlg = sleepDlg?.takeIf { it.isShowing }
            if (programRecs.isNotEmpty() && sleepAt <= now) {
                if (dlg != null) { dlg.dismiss(); sleepDlg = null }
                return
            }
            val left = closeAt - now
            when {
                left <= 0 -> { dlg?.dismiss(); sleepDlg = null; sleepShutdown() }
                dlg != null -> TvDialog.setMessage(dlg, getString(R.string.sleep_timer_message, ((left + 999) / 1000).toInt()))
                left <= 60_000 && !settingsOpen -> showSleepDialog(left)
            }
        }
    }

    private fun showSleepDialog(leftMs: Long) {
        sleepDlg = TvDialog.confirm(this, getString(R.string.sleep_timer_title),
            getString(R.string.sleep_timer_message, ((leftMs + 999) / 1000).toInt()),
            listOf(
                TvDialog.Btn(getString(R.string.sleep_timer_postpone)) {
                    sleepDlg = null
                    sleepAt = SystemClock.elapsedRealtime() + 15 * 60_000L + 60_000L
                },
                TvDialog.Btn(getString(R.string.sleep_timer_now)) { sleepDlg = null; sleepShutdown() },
            ),
            onBack = { sleepDlg = null; sleepAt = SystemClock.elapsedRealtime() + sleepLimitMs() },
            timeoutSec = 0)
    }

    private fun sleepShutdown() {
        AppLog.i("wyłączenie po bezczynności (${prefs.sleepHours} h) — zamykam aplikację")
        finishAndRemoveTask()
    }

    private fun chooseSleep() {
        val o = listOf(0) + Prefs.SLEEP_HOURS
        val labels = o.map { if (it <= 0) getString(R.string.sleep_timer_off) else getString(R.string.sleep_timer_hours, it) }
        TvDialog.single(this, getString(R.string.sleep_timer_menu), labels, o.indexOf(prefs.sleepHours).coerceAtLeast(0)) { w ->
            prefs.sleepHours = o[w]
            sleepAt = 0
        }
    }

    private val panelTimer = IdleTimeout(
        { if (::prefs.isInitialized) prefs.channelListSec else 0 },
        { task, delay -> ui.postDelayed(task, delay) }, { ui.removeCallbacks(it) },
    ) { hidePanel() }

    private fun resetPanelTimer() {
        panelTimer.setActive(::panel.isInitialized && panel.visibility == View.VISIBLE && hasWindowFocus())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) resetPanelTimer() else {
            panelTimer.setActive(false)
            backExit.reset()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) { notePilotInput(); resetPanelTimer(); sleepInput() }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) { resetPanelTimer(); sleepInput() }
        return super.dispatchTouchEvent(event)
    }

    private fun refreshPanel(sel: Int) {
        val bn = bouquets.getOrNull(panelBouquet)
        val sub = if (bn?.ref == REC_REF && recStack.isNotEmpty()) "  ›  " + recDir.trimEnd('/').substringAfterLast('/') else ""
        panelTitle.text = if (panelHist) getString(R.string.player_history)
            else (if (panelPip) getString(R.string.pip_pick_title) + ":   " else "") + "◀  ${bn?.name ?: ""}$sub  ▶"
        adapter.notifyDataSetChanged()
        if (panelList.isNotEmpty()) list.setSelection(sel.coerceIn(0, panelList.size - 1))
    }

    private fun panelBouquetStep(d: Int) {
        if (bouquets.isEmpty() || panelHist) return
        var b = ((panelBouquet + d) % bouquets.size + bouquets.size) % bouquets.size
        // wybór kanału do PiP — bukiety nagrań pomijane
        if (panelPip) {
            var n = 0
            while (isRecRef(bouquets[b].ref) && n++ < bouquets.size) b = ((b + d) % bouquets.size + bouquets.size) % bouquets.size
        }
        panelBouquet = b
        if (b == playBouquet) {
            panelList = playList
            refreshPanel(playIndex.coerceAtLeast(0))
            loadPanelEpg()
            return
        }
        panelList = emptyList()
        refreshPanel(0)
        val bq = bouquets[b]
        io.execute {
            val result = try { channelsOf(bq) to null } catch (e: Exception) { null to e }
            runOnUiThread {
                if (panelBouquet == b) {
                    val ch = result.first
                    val error = result.second
                    if (error != null) {
                        AppLog.i("bukiet: nie udało się pobrać kanałów ${bq.name}: $error")
                        showMessage(
                            if (isRecRef(bq.ref)) getString(R.string.player_recording_error, error.message ?: error.toString())
                            else getString(R.string.player_bouquet_failed, bq.name, error.message))
                        return@runOnUiThread
                    }
                    panelList = ch.orEmpty()
                    if (panelList.isEmpty() && !isRecRef(bq.ref)) showMessage(getString(R.string.player_bouquet_empty, bq.name))
                    // nagrania: zaznaczone ostatnio oglądane
                    val sel = if (isRecRef(bq.ref)) panelList.indexOfFirst { it.rec?.path == recLastPath }.coerceAtLeast(0) else 0
                    refreshPanel(sel)
                    loadPanelEpg()
                }
            }
        }
    }

    private fun loadPanelEpg() {
        val b = bouquets.getOrNull(panelBouquet)
        if (b == null && !panelHist) return
        if (b != null && isRecRef(b.ref)) return
        val target = panelList
        io.execute {
            val map = if (b == null || epg.eitOnly || b.ref == HIST_BOUQUET) emptyMap() else try { api.epgNow(b) } catch (e: Exception) { emptyMap() }
            // kanały, dla których dekoder nie podał bieżącej audycji (np. EPG tylko z paczki EPG Scan
            // pod inną referencją) — z zapisanego EPG, tak jak opis na dole listy
            val n = System.currentTimeMillis() / 1000
            val local = HashMap<String, EpgEvent>()
            for (c in target) {
                val k = OpenWebif.key(c.ref)
                val m = map[k]
                if (m == null || m.first.isBlank() || m.third <= n) epg.localNow(c.ref)?.let { local[k] = it }
            }
            runOnUiThread {
                target.forEach { c ->
                    val k = OpenWebif.key(c.ref)
                    val l = local[k]
                    if (l != null) { c.now = l.title; c.nowBegin = l.begin; c.nowEnd = l.end }
                    else map[k]?.let { c.now = it.first; c.nowBegin = it.second; c.nowEnd = it.third }
                }
                if (panelList === target) adapter.notifyDataSetChanged()
            }
        }
    }

    private fun onPanelPick(pos: Int) {
        val l = panelList
        if (pos !in l.indices) return
        l[pos].rec?.let {
            if (it.isDir) pickRecording(it) else showRecordingDetails(it)
            return
        }
        if (panelPip) {
            val b = panelBouquet
            hidePanel()
            pipAdd(l[pos], b, l)
            pipMenuAgain()
            return
        }
        panelOnRec = false          // wybrany kanał TV — lista znów otwiera się na bukiecie kanału
        hidePanel()
        // okno historii: kanał z jego bukietu (bieżący albo wyszukany w widocznych bukietach)
        if (panelHist) { zapTo(l[pos].ref); return }
        // ten sam, już odtwarzany kanał — nie uruchamiaj strumienia od nowa
        if (panelBouquet == playBouquet && pos == playIndex && currentUrl != null) {
            playList.getOrNull(playIndex)?.let { showBanner(it) }
            return
        }
        playIn(panelBouquet, l, pos)
    }

    private inner class ChannelAdapter : BaseAdapter() {
        override fun getCount() = panelList.size
        override fun getItem(position: Int): Any = panelList[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_channel, parent, false)
            UiScale.apply(v, UiScale.pct(prefs.uiListFont), UiScale.pct(prefs.uiListIcon))
            val c = panelList[position]
            c.rec?.let { return recRow(v, c, it, position) }
            v.findViewById<TextView>(R.id.num).text = c.num.toString()
            val iv = v.findViewById<android.widget.ImageView>(R.id.icon)
            Picons.show(iv, this@PlayerActivity, c.ref, prefs.picons, prefs.missingPiconLogo)
            val name = v.findViewById<TextView>(R.id.name)
            val playing = if (panelHist) playList.getOrNull(playIndex)?.ref?.let { OpenWebif.key(it) == OpenWebif.key(c.ref) } == true
                else panelBouquet == playBouquet && position == playIndex
            val selected = position == list.selectedItemPosition && list.hasFocus()
            val label = if (playing) "▶  ${c.name}" else c.name
            // „B” — kanał buforowany w tle (● — w trakcie zapisu z tego bufora)
            val bg = bgOf(c)
            // oglądany kanał też, gdy gra przez bufor
            val mainBuf = bg == null && isMainBuf(c)
            val rec = if (bg != null) bg.lrec != null else mainBuf && localRec != null
            name.text = if (bg == null && !mainBuf) label else android.text.SpannableStringBuilder(label).apply {
                append("  ")
                val st = length
                append(when { bg != null && bg.pip >= 0 -> if (rec) " ● PiP ${bg.pip + 1} " else " PiP ${bg.pip + 1} "; rec -> " ● B "; else -> " B " })
                setSpan(android.text.style.BackgroundColorSpan(if (rec) 0xFFC62828.toInt() else 0xFF1565C0.toInt()), st, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(0xFFFFFFFF.toInt()), st, length, 0)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, length, 0)
            }
            // na podświetleniu wszystko białe; odtwarzany kanał oznaczony ▶ i kolorem tylko poza podświetleniem
            name.setTextColor(if (selected) 0xFFFFFFFF.toInt() else if (playing) 0xFF4FC3F7.toInt() else 0xFFFFFFFF.toInt())
            val now = v.findViewById<TextView>(R.id.now)
            now.text = c.now
            now.setTextColor(if (selected) 0xFFE3F2FD.toInt() else 0xFFB0BEC5.toInt())
            val time = v.findViewById<TextView>(R.id.time)
            val bar = v.findViewById<android.widget.ProgressBar>(R.id.bar)
            if (c.nowBegin > 0 && c.nowEnd > c.nowBegin) {
                time.text = "${t(c.nowBegin)} – ${t(c.nowEnd)}"
                time.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFB0BEC5.toInt())
                val n = System.currentTimeMillis() / 1000
                bar.progress = (((n - c.nowBegin) * 1000) / (c.nowEnd - c.nowBegin)).toInt().coerceIn(0, 1000)
                time.visibility = View.VISIBLE
                bar.visibility = View.VISIBLE
            } else {
                time.visibility = View.INVISIBLE
                bar.visibility = View.INVISIBLE
            }
            return v
        }
    }

    // ---------------------------------------------------------------- przełączenia wg harmonogramu

    private val zapDf by lazy { SimpleDateFormat("EEE dd.MM  HH:mm", Locale.forLanguageTag(AppLanguage.selected(this))) }
    private var zapPrompted: String? = null          // klucz przełączenia, dla którego jest odliczanie
    private var zapDialog: android.app.Dialog? = null

    /** 30 s przed audycją pokazuje okno; odliczanie aktualizuje się co sekundę. */
    private val zapTick = object : Runnable {
        override fun run() {
            if (safeMode) return
            val timers = ZapTimers.list(this@PlayerActivity)
            if (zapPrompted != null && timers.none { it.key() == zapPrompted }) {
                try { zapDialog?.dismiss() } catch (_: Exception) {}
                zapDialog = null
                zapPrompted = null
            }
            if (backgrounded) {
                ui.postDelayed(this, if (zapPrompted == null) 5000 else 1000)
                return
            }
            val now = System.currentTimeMillis() / 1000
            val t = timers.firstOrNull { it.begin - 30 <= now }
            if (t == null) {
                ui.postDelayed(this, 5000)
                return
            }
            if (now >= t.begin && wakeTimerKey != t.key() && zapPrompted != t.key()) {
                zapFire(t)
                ui.postDelayed(this, 5000)
                return
            }
            if (zapPrompted == t.key()) {
                if (now >= t.begin) {
                    zapFire(t)
                    ui.postDelayed(this, 5000)
                    return
                }
                TvDialog.setButtonLabel(zapDialog, 0, getString(R.string.prompt_switch_countdown, zapCountdown(t.begin - now)))
                ui.postDelayed(this, 1000)
                return
            }
            zapPrompted = t.key()
            wakeTimerKey = null
            AppLog.i("harmonogram: za ${t.begin - now} s przełączenie na ${t.channel}")
            val card = EventCard.eventOf(t, this@PlayerActivity)
            val body = EventCard.cachedBody(this@PlayerActivity, t)
            zapDialog = TvDialog.panel(this@PlayerActivity, null, null,
                listOf(
                    TvDialog.Btn(getString(R.string.prompt_switch_countdown, zapCountdown(t.begin - now))) { zapFire(t) },
                    TvDialog.Btn(getString(R.string.message_record_on_receiver)) {
                        ZapTimers.remove(this@PlayerActivity, t)
                        zapPrompted = null
                        zapDialog = null
                        epg.recordTimer(t) { ok ->
                            ZapTimers.mark(this@PlayerActivity, t, if (ok) ZapTimers.RECORDED else ZapTimers.RECORD_FAILED)
                        }
                    },
                ), widthFrac = 0.8f, onBack = { zapSkip(t) }, focusChip = true) { ctx, _ ->
                EventCard.content(ctx, card, t.ref, t.channel, body)
            }
            ui.postDelayed(this, 1000)
        }
    }

    private fun zapCountdown(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return "${(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun zapFire(t: ZapTimers.Timer) {
        ZapTimers.complete(this, t, ZapTimers.SWITCHED)
        zapPrompted = null
        try { zapDialog?.dismiss() } catch (_: Exception) {}
        zapDialog = null
        AppLog.i("harmonogram: przełączam na ${t.channel} — ${t.title}")
        val forceTune = recMode || tsMode
        if (recMode) {
            leaveRec()
            stopPlayback()
        }
        hidePanel()
        zapTo(t.ref, forceTune)
        showMessage(getString(R.string.player_schedule_message, t.channel, t.title))
    }

    private fun zapSkip(t: ZapTimers.Timer) {
        ZapTimers.complete(this, t, ZapTimers.SKIPPED)
        zapPrompted = null
        zapDialog = null
        AppLog.i("harmonogram: pominięto po wyjściu z okna ${t.channel} — ${t.title}")
    }

    /** Lista przyszłych i zrealizowanych przełączeń. */
    private fun showZapTimers() {
        val future = ZapTimers.list(this)
        val history = ZapTimers.history(this)
        if (future.isEmpty() && history.isEmpty()) {
            showMessage(getString(R.string.message_no_scheduled_switches_add_one_from_programme_details_lo))
            return
        }
        val items = ArrayList<TvDialog.Item>()
        val actions = ArrayList<(() -> Unit)?>()
        val historyEntries = HashMap<Int, ZapTimers.History>()
        if (future.isNotEmpty()) {
            items += TvDialog.Item(getString(R.string.player_future_events), header = true)
            actions += null
            for (timer in future) {
                items += TvDialog.Item(timer.title, "${timer.channel}  •  ${zapDf.format(Date(timer.begin * 1000))}")
                actions += { epg.showScheduledEvent(timer) { showZapTimers() } }
            }
        }
        if (history.isNotEmpty()) {
            items += TvDialog.Item(getString(R.string.player_completed), header = true)
            actions += null
            for (entry in history) {
                items += TvDialog.Item(entry.timer.title,
                    "${zapOutcomeLabel(entry.outcome)}  •  ${entry.timer.channel}  •  ${zapDf.format(Date(entry.completedAt * 1000))}")
                historyEntries[items.lastIndex] = entry
                actions += {
                    if (entry.outcome == ZapTimers.RECORDED) playZapRecording(entry)
                    else showZapHistoryEntry(entry)
                }
            }
        }
        // po szczegółach / usunięciu lista wraca na tę samą pozycję (wyjście WSTECZ)
        val at = zapTimerSel.takeIf { it in items.indices && !items[it].header }
            ?: items.indices.lastOrNull { it <= zapTimerSel && !items[it].header } ?: items.indexOfFirst { !it.header }
        TvDialog.list(this, getString(R.string.message_scheduled_switches), items, at,
            hint = getString(R.string.player_details_remove_hint), widthFrac = 0.72f, onCancel = { zapTimerSel = 0 },
            onLongPick = { position ->
                historyEntries[position]?.let {
                    zapTimerSel = position
                    ui.post { showZapHistoryEntry(it) }
                    true
                } ?: false
            }) { w -> zapTimerSel = w; actions[w]?.invoke() }
    }

    private fun zapOutcomeLabel(outcome: String) = when (outcome) {
            ZapTimers.SWITCHED -> getString(R.string.player_history_switched)
            ZapTimers.RECORDED -> getString(R.string.player_history_recorded)
            ZapTimers.RECORD_FAILED -> getString(R.string.player_history_failed)
            ZapTimers.SKIPPED -> getString(R.string.player_history_skipped)
            ZapTimers.REPLACED -> getString(R.string.player_history_replaced)
            else -> outcome
        }

    private fun showZapHistoryEntry(entry: ZapTimers.History, note: String? = null) {
        val buttons = listOf(
            TvDialog.Btn(getString(R.string.message_remove_from_history)) {
                TvDialog.confirm(this, getString(R.string.message_confirm_deletion),
                    getString(R.string.player_history_remove_question, entry.timer.channel, entry.timer.title),
                    listOf(
                        TvDialog.Btn(getString(R.string.message_delete)) {
                            ZapTimers.removeHistory(this, entry)
                            showZapTimers()
                            TvDialog.info(this, getString(R.string.player_history_removed),
                                "${entry.timer.channel}\n${entry.timer.title}")
                        },
                    ), onBack = { showZapTimers() }, onTimeout = { showZapTimers() })
            },
        )
        TvDialog.confirm(this, zapOutcomeLabel(entry.outcome),
            listOfNotNull(note, entry.timer.channel, entry.timer.title,
                zapDf.format(Date(entry.completedAt * 1000))).joinToString("\n"),
            buttons, onBack = { showZapTimers() }, timeoutSec = prefs.actionDialogSec,
            onTimeout = { showZapTimers() })
    }

    private fun playZapRecording(entry: ZapTimers.History) {
        val timer = entry.timer
        showMessage(getString(R.string.player_recording_search, timer.title))
        io.execute {
            val (recording, error) = try {
                val movies = api.movies("").second.filterNot { it.isDir }
                val wantedTitle = timer.title.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
                val wantedRef = OpenWebif.key(timer.ref)
                val exactTitle = movies.filter {
                    it.title.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ") == wantedTitle
                }
                val found = exactTitle.maxWithOrNull(
                    compareBy<Recording> { OpenWebif.key(it.sref) == wantedRef }
                        .thenBy { -abs(it.begin - timer.begin) }
                ) ?: movies.filter {
                    OpenWebif.key(it.sref) == wantedRef && abs(it.begin - timer.begin) <= 600
                }.minByOrNull { abs(it.begin - timer.begin) }
                found to null
            } catch (e: Exception) {
                AppLog.i("harmonogram: nie udało się wyszukać nagrania ${timer.title}: $e")
                null to (e.message ?: e.toString())
            }
            runOnUiThread {
                when {
                    recording != null -> showRecordingDetails(recording) { showZapTimers() }
                    error != null -> TvDialog.info(this@PlayerActivity, getString(R.string.player_recording_search_failed),
                        error) { showZapTimers() }
                    else -> showZapHistoryEntry(entry, getString(R.string.player_recording_file_missing))
                }
            }
        }
    }

    // ---------------------------------------------------------------- timeshift

    private var tsBuf: TimeshiftBuffer? = null
    private var tsRec: TimeshiftRecorder? = null
    private var tsMode = false                 // odtwarzanie z bufora (strumień z dekodera zapisuje osobne połączenie)
    private var tsAudioPid: Int? = null
    private var tsAudioSourceGen = 0
    private var tsAudioAppliedGen = -1
    private var tsChBar = false                // w timeshifcie pokazany zwykły pasek kanału (zamiast paska timeshiftu)
    private var tsCtl = false                  // wybrany tryb obsługi timeshiftu, także po ukryciu paska

    /** Za transmisją: watchdog wyłączony — krótkie przerwy po wznowieniu/przewinięciu to nie zawieszenie. */
    private fun tsSetShifted(v: Boolean) {
        tsShifted = v
        watchdog?.paused = v
    }

    private var tsShifted = false              // za transmisją (pauza / cofnięte)
    private var tsCleanedAtStart = false
    private var tsBaseWall = 0L                // chwila odbioru odpowiadająca pozycji 0 odtwarzacza
    private var tsPending = 0L                 // przewijanie zbierane z kilku naciśnięć
    private var tsLiveUrl: String? = null

    /** Ile kanałów w tle (ustawienie 1–3; PiP wymaga 2). */
    private val BG_MAX get() = minOf(BG_SLOTS, prefs.bgCount + bgChans.count { it.pip >= 0 })
    /** Miejsc na bufory w tle (katalogi kanal-2…4): bufory poprzednich kanałów i okna PiP razem. */
    private val BG_SLOTS = 3
    /** PiP: najwięcej danych od klatki kluczowej czytanych z pamięci bufora. */
    private val PIP_READ_MAX = 3_000_000L

    // --- odtwarzanie przez bufor: jedno połączenie z dekoderem (TimeshiftRecorder) zapisuje do bufora,
    // odtwarzacz (ExoPlayer / VLC przez lokalny serwer) czyta koniec bufora; pauza i cofnięcie bez nowego połączenia
    private var bufLive = false
    @Volatile private var bufGen = 0           // zmiana — źródło odtwarzacza z poprzedniego kanału przestaje czytać

    /** Bufor gotowy do odtwarzania: włączona opcja, nośnik dostępny, bez błędu zapisu. */
    private fun bufReady(): Boolean {
        if (!prefs.bufPlay) return false
        val dir = TimeshiftBuffer.dir(this, prefs) ?: return false
        if (tsBuf == null) tsNewChannel()          // nośnik mógł wrócić po starcie
        val b = tsBuf ?: return false
        return b.failed == null && b.root == dir
    }

    private fun bufStop(reason: String) {
        if (!bufLive) return
        bufLive = false
        bufGen++
        if (!tsMode) { tsRec?.stop(); tsRec = null }
        AppLog.i("bufor: koniec odtwarzania przez bufor ($reason)")
    }

    private var bufFallbackWarned = false

    private fun startBufRecorder(url: String) {
        tsRec?.stop()
        // analizator i bufor przypięte do połączenia — po przełączeniu kanału zapis w tle pisze dalej do swojego bufora
        val a = analyzer
        val buf = tsBuf
        lateinit var recorder: TimeshiftRecorder
        recorder = TimeshiftRecorder(
            url, api.authHeader(), { a },
            { a?.dropCarry(); buf?.resync() },
            onFailure = {
                runOnUiThread {
                    bgChans.firstOrNull { it.rec === recorder }?.let { bgDrop(it, "brak strumienia z dekodera"); return@runOnUiThread }
                    if (bufLive && tsLiveUrl == url && tsRec === recorder) {
                        tsRec = null
                        AppLog.i("bufor: brak strumienia z dekodera — sprawdzam dekoder")
                        probeStalledStream()
                    }
                }
            },
        )
        tsRec = recorder
        tsRecAn = a
        recorder.start()
    }
    private var tsRecAn: TsAnalyzer? = null

    // --- bufory poprzednich kanałów i PiP: po przełączeniu poprzedni kanał zapisuje się dalej w tle (własne połączenie,
    // analizator, bufor w podkatalogu); powrót — odtwarzanie od razu z jego bufora. Kanały w oknach PiP to też bufory w tle.
    private class BgChan(
        val url: String, val name: String, val buf: TimeshiftBuffer, val an: TsAnalyzer,
        val rec: TimeshiftRecorder, val journal: ProgramJournal,
        val ch: Channel?, val bIdx: Int, val list: List<Channel>,
    ) {
        var pip = -1                               // numer okna PiP albo -1
        val addedAt = SystemClock.elapsedRealtime() // kolejność otwarcia (pytanie przy lukach w odbiorze)
        var parked = false                         // bufor ostatniego kanału na czas odtwarzania nagrań
        var leftAt = SystemClock.elapsedRealtime()
        var lrec: LocalRec? = null                 // zapis z tego bufora na dysk (nagranie lokalne)
    }

    /** Bufor z nagraniem audycji — chroniony do jej końca (nie wypada przy przełączaniu). */
    private fun bgProtected(c: BgChan) = c.lrec?.mode == REC_EVENT

    /** Trwające nagrania z buforów (bieżący kanał + kanały w tle). */
    private fun activeRecs() = (if (localRec != null) 1 else 0) + bgChans.count { it.lrec != null }

    /** Ile nagrań z buforów naraz: tyle, ile kanałów w tle (bufor bieżącego kanału zawsze zostaje), co najmniej 1. */
    private fun recLimit() = maxOf(1, if (bgAllowed()) prefs.bgCount else 0)

    /** Kanał z listy buforowany w tle (dowolny bukiet, także historia). */
    private fun bgOf(c: Channel): BgChan? {
        if (bgChans.isEmpty() || c.ref.isBlank()) return null
        val k = OpenWebif.key(c.ref)
        val m = bgChans.firstOrNull { it.ch?.ref?.let { r -> OpenWebif.key(r) == k } == true || it.url == api.streamUrl(c) }
        if (m != null && bgBadgeLogged.add(m.name)) AppLog.i("lista: znacznik B dla ${c.name} — bufor w tle ${m.name} (PiP ${m.pip + 1}, zapis ${m.lrec != null}, odłożony ${m.parked})")
        return m
    }
    private val bgChans = ArrayList<BgChan>()
    private val bgBadgeLogged = HashSet<String>()
    private var bgPromoted = false

    private fun bgAllowed() = prefs.bufPlay && TimeshiftBuffer.dir(this, prefs) != null
    private fun bgEnabled() = bgAllowed() && prefs.bgCount > 0
    private fun mainBufMin() = if (prefs.tsMin > 0) prefs.tsMin else TimeshiftBuffer.BUF_PLAY_MIN
    /** Długość bufora poprzedniego kanału: jak bieżący albo ręczna (najwyżej jak bieżący). */
    private fun bgBufLen() = if (prefs.bgBufMin < 0) mainBufMin() else minOf(maxOf(prefs.bgBufMin, 1), mainBufMin())
    private fun pipBufLen() = if (prefs.bgCount == 0) mainBufMin() else bgBufLen()

    private fun bgEit(j: ProgramJournal): (Long, Int, EitEvent?, EitEvent?) -> Unit = { _, _, now, _ ->
        if (now != null) ui.post {
            j.add(JournalEvent(now.begin, now.end, now.title, listOf(now.shortDesc, now.longDesc).filter { it.isNotBlank() }.distinct().joinToString("\n")))
        }
    }

    private fun bgDrop(c: BgChan, reason: String) {
        if (!bgChans.remove(c)) return
        c.lrec?.let { c.lrec = null; finishRec(it, "bufor zamknięty", restartLive = false, main = false) }
        c.rec.stop()
        if (c.pip >= 0) { c.pip = -1; pipRefresh() }
        c.an.tee = null; c.an.onEit = null
        // w osobnym wątku: przy zawieszonym nośniku zamknięcie pliku może czekać — nie blokuje obrazu ani pilota
        closeBufferAsync(c.buf)
        bgBadgeLogged.remove(c.name)
        AppLog.i("bufor w tle: ${c.name} zamknięty ($reason)")
        pipRefresh()
        refreshPanelRows()
    }

    private fun bgDropAll(reason: String) { for (c in bgChans.toList()) bgDrop(c, reason) }

    private fun closeBufferAsync(buffer: TimeshiftBuffer) {
        Thread({
            try {
                buffer.close()
                TimeshiftBuffer.cleanupAsync(buffer.dir)
            } catch (e: Exception) {
                AppLog.i("timeshift: zamknięcie bufora nieudane (${buffer.dir.name}, $e)")
            }
        }, "bufor-zamykanie").start()
    }

    private val storageGuard = StorageGuard()
    private var guardScheduled = false
    private val storageGuardTick = object : Runnable {
        override fun run() {
            guardScheduled = false
            if (isFinishing || isDestroyed) return
            guardTick()
            scheduleStorageGuard()
        }
    }

    private fun scheduleStorageGuard() {
        if (guardScheduled || (tsBuf == null && bgChans.isEmpty())) return
        guardScheduled = true
        ui.postDelayed(storageGuardTick, StorageGuard.CHECK_MS)
    }

    private fun guardTick() {
        val now = SystemClock.elapsedRealtime()
        val buffers = bgChans.map { it.buf } + listOfNotNull(tsBuf)
        val (buf, reason) = buffers.firstNotNullOfOrNull { b ->
            b.storageTrouble(now, storageGuard.lastDropAt)?.let { b to it }
        } ?: return
        val victim = storageGuard.chooseDrop(now, bgChans.filter { it.buf.root == buf.root }.map { c ->
            StorageGuard.Background(c, c.lrec != null, c.buf.failed != null, if (c.pip >= 0) c.addedAt else c.leftAt)
        })
        if (victim != null) {
            // Select and remove on the UI thread, before the channel can start recording or become current.
            val hadPip = victim.pip >= 0
            val wall = System.currentTimeMillis() / 1000
            val title = victim.journal.snapshot().lastOrNull { it.begin <= wall && wall < it.end }?.title
            val what = victim.name + (title?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: "")
            bgDrop(victim, "strażnik zapisu: $reason")
            refreshPanelRows()
            if (AppState.foreground) {
                showMessage(getString(if (hadPip) R.string.guard_closed_pip else R.string.guard_closed, what), force = true)
            }
        } else if (AppState.foreground && storageGuard.shouldWarn(now)) {
            val name = Storage.recordingName(this, prefs, buf.root.parentFile)
            AppLog.i("strażnik zapisu: $reason — sprawdź nośnik: $name")
            showMessage(getString(R.string.guard_check_storage, name), force = true)
        }
    }

    /**
     * Odtwarzanie nagrania (z dekodera albo lokalnego): bufor oglądanego kanału przechodzi do tła (także przy wyłączonym
     * buforze poprzednich kanałów), zostają też kanały w tle z trwającym zapisem; pozostałe bufory są usuwane.
     * Po wyjściu z nagrania powrót na ostatni kanał — od razu z jego bufora.
     */
    private fun bgPark() {
        if (!bgAllowed()) return
        // bufor ostatniego kanału odłożony przy poprzednim nagraniu zostaje (przechodzenie między nagraniami)
        for (c in bgChans.filter { it.lrec == null && it.pip < 0 && !it.parked }) bgDrop(c, "odtwarzanie nagrania")
        val curBuf = tsBuf ?: return
        val curRec = tsRec ?: return
        val curAn = tsRecAn ?: return
        if (!bufLive || curAn !== analyzer || curBuf.failed != null) return
        // nagranie audycji / „do usunięcia bufora” trwa dalej w tle; „do zmiany kanału” kończy się
        val keep = localRec?.takeIf { it.mode == REC_EVENT || it.mode == REC_UNTIL_DROP }
        if (keep != null) { localRec = null; ui.removeCallbacks(localRecTick) }
        else if (localRec != null) finishLocalRec("odtwarzanie nagrania", restartLive = false)
        if (tsMode) tsLeave()
        val cur = playList.getOrNull(playIndex)
        curAn.tee = { b, o, l -> curBuf.write(b, o, l) }
        curAn.onEit = bgEit(journal)
        curBuf.pcrPid = { curAn.pcrPid }
        val c = BgChan(tsLiveUrl ?: "", cur?.name ?: "?", curBuf, curAn, curRec, journal, cur, playBouquet, playList)
        // odłożony jest tylko ostatni kanał — wcześniej odłożony (inny) przestaje być chroniony
        for (o in bgChans) o.parked = false
        c.parked = true
        bgChans += c
        refreshPanelRows()
        if (keep != null) {
            c.lrec = keep
            ui.removeCallbacks(bgRecTick); ui.postDelayed(bgRecTick, 5000)
        }
        AppLog.i("bufor w tle: ${c.name} — zapis w tle na czas odtwarzania nagrania (bufor ${curBuf.maxMin} min)" +
            (if (keep != null) ", nagranie „${keep.meta.optString("title")}” trwa" else ""))
        tsRec = null; tsRecAn = null; tsBuf = null
        bufLive = false
        journal = ProgramJournal()
        analyzer = null
        ensureAnalyzer()
        scheduleStorageGuard()
    }

    /**
     * Przełączenie na [url]: bieżący kanał (odtwarzany przez bufor) przechodzi do zapisu w tle, a gdy [url] jest już
     * buforowany w tle — jego bufor, połączenie i dziennik stają się bieżącymi (tsNewChannel ich nie resetuje).
     * Kanał z okna PiP zamienia się miejscem z bieżącym (bieżący trafia do tego okna).
     */
    // --- łącze: luki w odbiorze (skoki zegara PCR = utracone sekundy strumienia) przy równoległych strumieniach;
    // bez blokowania z prędkości (Android 9 podaje tylko prędkość wysyłania WiFi) — statystyki, log i pytanie
    private val rateSamples = java.util.WeakHashMap<TimeshiftBuffer, LongArray>()

    /** Przepływność zapisu bufora (Mb/s, pomiar co ≥ 10 s); nieznana — 8. */
    private fun bufMbps(b: TimeshiftBuffer): Double {
        val now = SystemClock.elapsedRealtime()
        val s = rateSamples.getOrPut(b) { longArrayOf(b.total, now, -1) }
        if (now - s[1] >= 10_000) { s[2] = (b.total - s[0]) * 8 / (now - s[1]); s[0] = b.total; s[1] = now }
        return if (s[2] > 0) s[2] / 1000.0 else 8.0
    }

    /** Strumienie z dekodera teraz: (liczba, razem Mb/s). */
    private fun streamsMbps(): Pair<Int, Double> {
        var n = 0
        var sum = 0.0
        if (currentUrl != null && !recMode) {
            n++
            sum += tsBuf?.let { bufMbps(it) } ?: analyzer?.bitrateKbps?.takeIf { it > 0 }?.let { it / 1000.0 } ?: 8.0
        }
        for (c in bgChans) { n++; sum += bufMbps(c.buf) }
        return n to sum
    }

    private var netLink: NetInfo.Link? = null
    private val gapSeen = java.util.WeakHashMap<TsAnalyzer, Long>()
    /** Luki z ostatnich 10 min: (chwila, ile strumieni było otwartych). */
    private val gapLog = ArrayDeque<Pair<Long, Int>>()
    private var netLogN = 0
    private var netAskSnoozeUntil = 0L
    private var netAskOff = false
    private var netMainMsgAt = -1_000_000L

    private fun gaps10min(): Int {
        val lim = SystemClock.elapsedRealtime() - 600_000
        while (gapLog.isNotEmpty() && gapLog.first().first < lim) gapLog.removeFirst()
        return gapLog.size
    }

    /** Linia statystyk: łącze, liczba strumieni, luki z 10 min. */
    private fun netStatsLine(): String {
        val l = NetInfo.read(this) ?: netLink
        if (l != null) netLink = l
        return getString(R.string.diag_net, l?.toString() ?: "?", streamsMbps().first, gaps10min())
    }

    /**
     * Co 30 s: luki w strumieniach. Przy otwartych równoległych strumieniach (bufory w tle / PiP) i lukach w ≥ 2
     * z nich — pytanie o zmniejszenie liczby buforów (albo zamknięcie okna PiP); przy samym oglądanym kanale —
     * komunikat o łączu (raz na 30 min). Co minutę podsumowanie w logu.
     */
    private val netTick = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            if (isFinishing) return
            ui.postDelayed(this, 30_000)
            // nagranie z dekodera (strumień HTTP) z oknami PiP — też liczone; nagranie lokalne nie obciąża łącza
            val recNet = recMode && recItem?.let { !LocalRecordings.isLocal(it.path) } == true && bgChans.any { it.pip >= 0 }
            if ((recMode && !recNet) || (!recMode && currentUrl == null)) return
            var (n, sum) = streamsMbps()
            if (recNet) { n++; sum += analyzer?.bitrateKbps?.takeIf { it > 0 }?.let { it / 1000.0 } ?: 8.0 }
            val l = NetInfo.read(this@PlayerActivity)
            netLink = l
            var gaps = 0
            var mainGap = false
            for (a in listOfNotNull(analyzer) + bgChans.map { it.an }) {
                val prev = gapSeen[a]
                if (prev != null && a.pcrJumps > prev) { gaps++; if (a === analyzer) mainGap = true }
                gapSeen[a] = a.pcrJumps
            }
            val now = SystemClock.elapsedRealtime()
            repeat(gaps) { gapLog.addLast(now to n) }
            if (++netLogN % 2 == 0 || gaps > 0)
                AppLog.i("łącze: ${l ?: "?"}; strumienie $n × razem ${"%.1f".format(sum)} Mb/s; luki teraz $gaps, w 10 min ${gaps10min()}")
            // ostatnie ~2 min (4 sprawdzenia co 30 s): czy luki się utrzymują
            gapTicks.addLast(gaps > 0)
            while (gapTicks.size > 4) gapTicks.removeFirst()
            val lasting = gapTicks.size >= 4 && gapTicks.count { it } >= 3
            when {
                bgChans.isNotEmpty() && gaps >= 2 -> netAsk(n, l)
                recNet && gaps >= 1 && gaps10min() >= 2 -> netAsk(n, l)
            }
            // podpowiedź o łączu (raz na 30 min): sam oglądany kanał z luką, albo luki trwają mimo pytania / gdy nie ma czego zamknąć
            val hint = (n <= 1 && mainGap) || (n > 1 && lasting)
            if (hint && now - netMainMsgAt > 1_800_000 && TvDialog.top() == null) {
                netMainMsgAt = now
                val link = l?.toString() ?: "?"
                val msg = if (n <= 1) getString(R.string.net_hint_one, link)
                    else getString(R.string.net_hint_many, link, n)
                AppLog.i("łącze: podpowiedź — $msg")
                showMessage(msg)
            }
        }
    }

    /** Pytanie przy lukach w równoległych strumieniach: mniej buforów poprzednich kanałów albo zamknięcie okna PiP. */
    /** Luki w kolejnych sprawdzeniach łącza (co 30 s) — do podpowiedzi przy trwałych przerwach. */
    private val gapTicks = ArrayDeque<Boolean>()

    private fun netAsk(streams: Int, l: NetInfo.Link?) {
        if (netAskOff || SystemClock.elapsedRealtime() < netAskSnoozeUntil || TvDialog.top() != null) return
        netAskSnoozeUntil = SystemClock.elapsedRealtime() + 120_000      // bez ponawiania w trakcie decyzji
        val prev = bgChans.filter { it.pip < 0 && it.lrec == null }
        val pip = bgChans.filter { it.pip >= 0 && it.lrec == null }.maxByOrNull { it.addedAt }
        val link = l?.toString() ?: "?"
        val (question, act) = when {
            // przy nagraniu z dekodera: zamykane okno PiP (bufor ostatniego kanału zostaje)
            recMode -> if (pip != null) getString(R.string.net_ask_pip, streams, link, pip.name) to { pipClose(pip) } else return
            prefs.bgCount > 0 && prev.isNotEmpty() -> getString(R.string.net_ask_bg, streams, link, prefs.bgCount, prefs.bgCount - 1) to {
                prefs.bgCount = prefs.bgCount - 1
                AppLog.i("łącze: bufor poprzednich kanałów zmniejszony do ${prefs.bgCount} (luki przy $streams strumieniach, $link)")
                while (bgChans.count { it.pip < 0 } > prefs.bgCount) {
                    val v = bgChans.filter { it.pip < 0 && !bgProtected(it) && it.lrec == null }.minByOrNull { it.leftAt } ?: break
                    bgDrop(v, "mniej buforów (luki w odbiorze)")
                }
                refreshPanelRows()
            }
            pip != null -> getString(R.string.net_ask_pip, streams, link, pip.name) to { pipClose(pip) }
            else -> return
        }
        AppLog.i("łącze: luki przy $streams strumieniach ($link) — pytanie: $question")
        TvDialog.confirm(this, getString(R.string.net_ask_title), question, listOf(
            TvDialog.Btn(getString(R.string.net_ask_reduce)) { netAskSnoozeUntil = 0; act() },
            TvDialog.Btn(getString(R.string.net_ask_keep)) { netAskSnoozeUntil = SystemClock.elapsedRealtime() + 1_800_000 },
            TvDialog.Btn(getString(R.string.net_ask_never)) { netAskOff = true },
        ))
    }

    private fun bgSwitch(url: String, name: String) {
        if (recMode || url == tsLiveUrl && bufLive) return
        // powrót na żywo: koniec ochrony bufora odłożonego na czas nagrań
        for (c in bgChans) c.parked = false
        if (!bgAllowed()) { bgDropAll("opcja wyłączona"); return }
        if (prefs.bgCount == 0) for (c in bgChans.filter { it.pip < 0 && it.lrec == null && it.url != url }) bgDrop(c, "opcja wyłączona")
        val target = bgChans.firstOrNull { it.url == url }
        val pipSlot = target?.pip ?: -1
        val curBuf = tsBuf
        val curRec = tsRec
        val curAn = tsRecAn
        val canDemote = bufLive && curBuf != null && curRec != null && curAn != null && curAn === analyzer && curBuf.failed == null
        if (canDemote && (pipSlot >= 0 || prefs.bgCount > 0)) {
            // miejsce: najdawniej opuszczony poprzedni kanał wypada (okna PiP i nagrania audycji zostają)
            while (bgChans.count { it !== target } >= BG_MAX) {
                val v = bgChans.filter { it !== target && it.pip < 0 && !bgProtected(it) }.minByOrNull { it.leftAt } ?: break
                bgDrop(v, "limit $BG_MAX kanałów w tle")
            }
            if (bgChans.count { it !== target } >= BG_MAX) {
                AppLog.i("bufor w tle: ${playList.getOrNull(playIndex)?.name} bez bufora — miejsca zajęte przez nagrania audycji")
                if (prefs.bgFullMsg) showMessage(getString(R.string.player_bg_full))
            }
            if (bgChans.count { it !== target } < BG_MAX) {
                // nagranie audycji / „do usunięcia bufora” trwa dalej w tle; „do zmiany kanału” — kończy się
                val keep = localRec?.takeIf { it.mode == REC_EVENT || it.mode == REC_UNTIL_DROP }
                if (keep != null) { localRec = null; ui.removeCallbacks(localRecTick) }
                else if (localRec != null) finishLocalRec("zmiana kanału", restartLive = false)
                if (tsMode) tsLeave()
                val cur = playList.getOrNull(playIndex)
                // bieżący → w tle: analizator, bufor i dziennik należą odtąd do niego
                curAn!!.tee = { b, o, l -> curBuf!!.write(b, o, l) }
                curAn.onEit = bgEit(journal)
                curBuf!!.pcrPid = { curAn.pcrPid }
                curBuf.maxMin = if (pipSlot >= 0) pipBufLen() else bgBufLen()
                val c = BgChan(tsLiveUrl ?: "", cur?.name ?: "?", curBuf, curAn, curRec!!, journal, cur, playBouquet, playList)
                c.pip = pipSlot
                bgChans += c
                if (keep != null) {
                    c.lrec = keep
                    ui.removeCallbacks(bgRecTick); ui.postDelayed(bgRecTick, 5000)
                    AppLog.i("nagranie lokalne: „${keep.meta.optString("title")}” trwa dalej w tle")
                }
                AppLog.i("bufor w tle: ${c.name} — zapis w tle, bufor ${curBuf.maxMin} min" + if (pipSlot >= 0) " (okno PiP ${pipSlot + 1})" else "")
                tsRec = null; tsRecAn = null; tsBuf = null
                bufLive = false
                journal = ProgramJournal()
                analyzer = null
                if (target == null) ensureAnalyzer()
                scheduleStorageGuard()
            }
        }
        if (target != null) {
            bgChans.remove(target)
            target.pip = -1
            if (tsMode) tsLeave()
            bufStop("powrót na kanał buforowany w tle")
            if (localRec != null) finishLocalRec("zmiana kanału", restartLive = false)
            tsBuf?.let { closeBufferAsync(it) }
            tsBuf = null
            // przejęcie: ten sam analizator (bez resetu sesji), bufor, połączenie i dziennik
            analyzer = target.an
            mainAnalyzerHooks(target.an)
            target.an.clean = prefs.cleanTs
            target.buf.pcrPid = { analyzer?.pcrPid ?: -1 }
            target.buf.maxMin = mainBufMin()
            tsBuf = target.buf
            tsRec = target.rec; tsRecAn = target.an
            journal = target.journal
            tsLiveUrl = url
            bufLive = true
            bgPromoted = true
            // zapis z tego bufora trwa dalej jako nagranie bieżącego kanału
            target.lrec?.let { r ->
                target.lrec = null
                localRec = r
                ui.removeCallbacks(localRecTick); ui.postDelayed(localRecTick, 5000)
                AppLog.i("nagranie lokalne: „${r.meta.optString("title")}” trwa dalej na bieżącym kanale")
            }
            AppLog.i("bufor w tle: powrót na $name — odtwarzanie od razu z bufora (${target.buf.spanMs() / 1000} s zapisu)")
        }
        pipRefresh()
        refreshPanelRows()
    }

    /** Nowy kanał buforowany w tle (dla okna PiP): własne połączenie, analizator i bufor w wolnym podkatalogu. */
    private fun bgNew(ch: Channel, bIdx: Int = playBouquet, list: List<Channel> = playList): BgChan? {
        val root = TimeshiftBuffer.dir(this, prefs) ?: return null
        val used = bgChans.map { it.buf.dir }.toSet() + listOfNotNull(tsBuf?.dir)
        val dir = TimeshiftBuffer.slotDirs(root).firstOrNull { it !in used } ?: return null
        val url = api.streamUrl(ch)
        val buf = TimeshiftBuffer(dir, pipBufLen(), root, prefs.tsWriteBatchBytes)
        val an = TsAnalyzer(0) { msg -> AppLog.i("PiP ${ch.name}: $msg") }
        an.pmtCacheGet = { sid -> prefs.pmtFor(sid) ?: PmtBuilder.build(sid, prefs.pidsFor(sid)) }
        an.pmtCachePut = { sid, b -> prefs.setPmtFor(sid, b) }
        an.clean = prefs.cleanTs
        an.reset(url.substringAfterLast('/').split(":").getOrNull(3)?.toIntOrNull(16) ?: -1)
        buf.pcrPid = { an.pcrPid }
        an.tee = { b, o, l -> buf.write(b, o, l) }
        val j = ProgramJournal()
        an.onEit = bgEit(j)
        lateinit var rec: TimeshiftRecorder
        rec = TimeshiftRecorder(url, api.authHeader(), { an }, { an.dropCarry(); buf.resync() }, onFailure = {
            runOnUiThread { bgChans.firstOrNull { it.rec === rec }?.let { bgDrop(it, "brak strumienia z dekodera") } }
        })
        val c = BgChan(url, ch.name, buf, an, rec, j, ch, bIdx, list)
        bgChans += c
        rec.start()
        scheduleStorageGuard()
        AppLog.i("bufor w tle: ${ch.name} — nowe połączenie dla PiP, bufor ${buf.maxMin} min w ${dir.absolutePath}")
        refreshPanelRows()
        return c
    }

    // --- PiP: najwyżej 3 okna z kanałami buforowanymi w tle (bez dźwięku): klatka z pamięci bufora co
    // prefs.pipFrameMs, okna odświeżane na zmianę przez jeden wątek; dekoder programowy do pamięci (bez zajmowania
    // sprzętowego dekodera obrazu głównego), jeden półobraz zmniejszony do okna (ImageView)
    private val PIP_MAX = 3

    private inner class PipWin(val idx: Int) {
        val frame = android.widget.FrameLayout(this@PlayerActivity)
        val label = TextView(this@PlayerActivity)
        val note = TextView(this@PlayerActivity)              // „brak dekodera …” na środku okna
        val iv = android.widget.ImageView(this@PlayerActivity)
        @Volatile var chan: BgChan? = null
        @Volatile var gen = 0                                // zmiana kanału w oknie — dekoder od nowa
        @Volatile var pw = 0                                 // rozmiar obrazu w oknie (px)
        @Volatile var ph = 0
        // tylko wątek PiP
        var codec: PipCodec? = null
        var codecGen = -1
        var noDecoder: String? = null
        var failLogged = false
        var firstLogged = false
        var misses = 0
        var gaps = 0
        var statN = 0
        var statMs = 0L
        var statAt = 0L
        var statInfo = ""
        var px = IntArray(0)
        val bmps = arrayOfNulls<android.graphics.Bitmap>(2)
        var bmpTurn = 0
    }
    private val pipWins = arrayOfNulls<PipWin>(PIP_MAX)
    @Volatile private var pipWorker: Thread? = null
    private var pipSessionStarted = false
    /** PiP wstrzymane do tej chwili (zacięcie / wznowienie obrazu głównego — procesor dla obrazu głównego). */
    @Volatile private var pipHoldUntil = 0L

    private fun pipView(i: Int): PipWin = pipWins[i] ?: PipWin(i).also { w ->
        val m = (16 * resources.displayMetrics.density).toInt()
        w.frame.setBackgroundColor(0xFF000000.toInt())
        w.frame.setPadding(3, 3, 3, 3)
        w.frame.foreground = android.graphics.drawable.GradientDrawable().apply { setStroke(3, 0xCCFFFFFF.toInt()) }
        w.label.setTextColor(0xFFFFFFFF.toInt()); w.label.textSize = 13f
        w.label.setBackgroundColor(0x99000000.toInt()); w.label.setPadding(m / 3, 2, m / 3, 2)
        w.note.setTextColor(0xFFB0BEC5.toInt()); w.note.textSize = 13f
        w.note.visibility = View.GONE
        w.iv.scaleType = android.widget.ImageView.ScaleType.FIT_XY
        w.frame.addView(w.iv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.frame.addView(w.note, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        w.frame.addView(w.label, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.START))
        w.frame.isFocusable = false
        w.frame.tag = "pip"
        // tuż nad obrazem — pasek kanału, listy, komunikaty i podgląd przewijania zostają nad oknami PiP
        val parent = zapFrame.parent as ViewGroup
        parent.addView(w.frame, parent.indexOfChild(zapFrame) + 1, pipLayout(i))
        w.frame.visibility = View.GONE
        pipWins[i] = w
    }

    /**
     * Położenie okna [i] (0 — pierwsze): 0 na górze w rogach, 1 na dole w rogach, 2 prawa strona w rogach,
     * 3 lewa strona w rogach, 4 lewy górny róg w dół, 5 prawy górny róg w dół, 6 lewy dolny róg w górę, 7 prawy dolny róg w górę.
     * Trzecie okno w trybach „w rogach”: pod/nad pierwszym (0, 1) albo na środku boku (2, 3).
     */
    private fun pipLayout(i: Int): android.widget.FrameLayout.LayoutParams {
        val dm = resources.displayMetrics
        val wpx = (dm.widthPixels * 0.26f).toInt()
        val hpx = wpx * 9 / 16
        val m = (16 * dm.density).toInt() * 2
        val step = hpx + m / 2
        val G = android.view.Gravity.TOP; val B = android.view.Gravity.BOTTOM
        val L = android.view.Gravity.START; val R = android.view.Gravity.END
        val C = android.view.Gravity.CENTER_VERTICAL
        val (g, off) = when (prefs.pipPos) {
            0 -> when (i) { 0 -> (G or R) to 0; 1 -> (G or L) to 0; else -> (G or R) to step }
            1 -> when (i) { 0 -> (B or R) to 0; 1 -> (B or L) to 0; else -> (B or R) to step }
            2 -> when (i) { 0 -> (R or G) to 0; 1 -> (R or B) to 0; else -> (R or C) to 0 }
            3 -> when (i) { 0 -> (L or G) to 0; 1 -> (L or B) to 0; else -> (L or C) to 0 }
            4 -> (G or L) to i * step
            6 -> (B or L) to i * step
            7 -> (B or R) to i * step
            else -> (G or R) to i * step
        }
        return android.widget.FrameLayout.LayoutParams(wpx, hpx, g).apply {
            leftMargin = m; rightMargin = m
            topMargin = m + if ((g and android.view.Gravity.VERTICAL_GRAVITY_MASK) == G) off else 0
            bottomMargin = m + if ((g and android.view.Gravity.VERTICAL_GRAVITY_MASK) == B) off else 0
        }
    }

    /** Okna zgodnie z kanałami w tle oznaczonymi jako PiP (po przełączeniu, zamknięciu bufora, zmianie ustawień). */
    private fun pipRefresh() {
        // numeracja bez dziur: po zamknięciu pierwszego okna kolejne przesuwają się
        bgChans.filter { it.pip >= 0 }.sortedBy { it.pip }.forEachIndexed { n, c -> c.pip = n }
        for (i in 0 until PIP_MAX) {
            val c = bgChans.firstOrNull { it.pip == i }
            val w = pipWins[i]
            if (c == null) {
                if (w != null) { w.chan = null; w.gen++; w.frame.visibility = View.GONE; w.iv.setImageDrawable(null) }
                continue
            }
            val v = pipView(i)
            val lp = pipLayout(i)
            v.frame.layoutParams = lp
            v.pw = maxOf(16, lp.width - 6); v.ph = maxOf(16, lp.height - 6)
            if (v.chan !== c) {
                v.chan = c; v.gen++
                v.note.visibility = View.GONE
                v.iv.setImageDrawable(null)
                v.label.text = "${i + 1}  ${c.name}"
                if (!pipSessionStarted) {
                    pipSessionStarted = true
                    PipLog.newSession("E2Stream ${appVersion()} | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                }
                PipLog.i("okno ${i + 1}: ${c.name} — klatka co ${prefs.pipFrameMs} ms")
            }
            v.frame.visibility = View.VISIBLE
        }
        val open = pipWins.any { it?.chan != null }
        // bezpiecznik: znacznik zostaje, gdy aplikacja padnie albo Android się zawiesi z otwartym PiP
        if (prefs.pipActive != open) prefs.pipActive = open
        if (open && pipWorker == null) {
            pipWorker = Thread({ pipLoop() }, "pip-klatki").also { it.start() }
        }
    }

    /**
     * Jeden wątek dla wszystkich okien: na zmianę, każde okno co prefs.pipFrameMs; dekodery tylko w tym wątku.
     * Limit obciążenia: po każdej klatce przerwa co najmniej 3× czas jej przygotowania (PiP ≤ ~25% czasu procesora),
     * więc krótkie odstępy (0,25 / 0,5 s) są tylko górną granicą. Przy zacięciu obrazu głównego PiP czeka.
     */
    private fun pipLoop() {
        var turn = 0
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST)
        try {
            while (true) {
                // okna zamknięte albo z innym kanałem — dekoder zwalniany
                for (w in pipWins) if (w != null && w.codec != null && (w.chan == null || w.codecGen != w.gen)) pipRelease(w)
                val open = pipWins.filterNotNull().filter { it.chan != null }
                if (open.isEmpty()) break
                val hold = pipHoldUntil - SystemClock.elapsedRealtime()
                if (hold > 0) { Thread.sleep(minOf(hold, 1000L)); continue }
                val w = open[turn++ % open.size]
                if (w.codecGen != w.gen) {
                    w.codecGen = w.gen; w.noDecoder = null; w.failLogged = false; w.firstLogged = false; w.misses = 0; w.gaps = 0
                    w.statN = 0; w.statMs = 0; w.statAt = 0
                }
                val t0 = SystemClock.elapsedRealtime()
                val c = w.chan
                if (c != null && w.noDecoder == null) {
                    try {
                        val r = pipGrab(w, c)
                        when {
                            r.ms >= 0 -> {
                                if (!w.firstLogged) { w.firstLogged = true; PipLog.i("okno ${w.idx + 1}: ${c.name} — pierwsza klatka po ${r.ms} ms (${r.info})") }
                                // co minutę: ile odświeżeń, średni czas przygotowania klatki
                                val now = SystemClock.elapsedRealtime()
                                if (w.statAt == 0L) w.statAt = now
                                w.statN++; w.statMs += r.ms; w.statInfo = r.info
                                if (now - w.statAt >= 60_000) {
                                    PipLog.i("okno ${w.idx + 1}: ${c.name} — ${w.statN} klatek w ${(now - w.statAt) / 1000} s, śr. ${w.statMs / w.statN} ms (${w.statInfo})")
                                    w.statN = 0; w.statMs = 0; w.statAt = now
                                }
                            }
                            r.ms == -2L -> {
                                w.noDecoder = r.info
                                PipLog.i("okno ${w.idx + 1}: ${c.name} — ${r.info}")
                                val g = w.gen
                                ui.post { if (w.gen == g) { w.note.text = getString(R.string.pip_no_decoder, r.info.substringAfterLast(' ')); w.note.visibility = View.VISIBLE } }
                            }
                            r.ms == -3L -> {
                                // luka w danych — zostaje poprzednia klatka
                                w.gaps++
                                if (w.gaps == 1 || w.gaps % 20 == 0) PipLog.i("okno ${w.idx + 1} (${c.name}): luka w danych obrazu ×${w.gaps} — klatka pominięta")
                            }
                            else -> {
                                // brak klatki — powód do logu (raz na kanał w oknie, potem co 20 nieudanych)
                                w.misses++
                                if (!w.firstLogged && (w.misses == 2 || w.misses % 20 == 0)) PipLog.i("okno ${w.idx + 1} (${c.name}): brak klatki ×${w.misses} — ${r.info}")
                            }
                        }
                    } catch (e: Exception) {
                        pipRelease(w)
                        if (!w.failLogged) {
                            w.failLogged = true
                            val trace = android.util.Log.getStackTraceString(e).replace('\n', ' ').trim()
                            PipLog.i("okno ${w.idx + 1} (${c.name}): klatka nieudana; stos: $trace")
                        }
                    }
                }
                val spent = SystemClock.elapsedRealtime() - t0
                val step = prefs.pipFrameMs.coerceAtLeast(250).toLong() / open.size
                Thread.sleep(maxOf(30L, step - spent, 3 * spent))
            }
        } catch (_: InterruptedException) {
        } finally {
            for (w in pipWins) if (w != null) pipRelease(w)
            pipWorker = null
        }
        // okno otwarte w chwili kończenia wątku — nowy wątek
        ui.post { if (pipWins.any { it?.chan != null } && pipWorker == null) pipRefresh() }
    }

    /** Dekoder okna PiP: programowy, do pamięci, tworzony raz na kanał w oknie; między klatkami flush. */
    private class PipCodec(val codec: android.media.MediaCodec, val mime: String, val maxIn: Int)

    private fun pipRelease(w: PipWin) {
        val pc = w.codec ?: return
        w.codec = null
        try { pc.codec.stop() } catch (_: Exception) {}
        try { pc.codec.release() } catch (_: Exception) {}
    }

    private val swLogged = HashSet<String>()

    /** Programowy dekoder dla typu; null — brak. Sprzętowego PiP nie używa nigdy. */
    private fun swDecoderName(mime: String): String? {
        val n = TsFrames.swDecoder(mime)
        if (synchronized(swLogged) { swLogged.add(mime) }) {
            val all = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            PipLog.i("dekoder dla $mime: ${n ?: "brak programowego"} (dostępne: ${all.joinToString { it.name }.ifEmpty { "żaden" }})")
        }
        return n
    }

    private class Grab(val ms: Long, val info: String)
    private var pipData = ByteArray(0)         // tylko wątek PiP — bez nowych buforów przy każdej klatce
    private var pipAu = ByteArray(0)

    /**
     * Klatka z pamięci bufora: klatka kluczowa z indeksu → własny demultiplekser TS (bity kodowania po odszyfrowaniu
     * bez znaczenia) → jednostki dostępu (pełny obraz: 3; odświeżanie pasami: do następnej klatki kluczowej) →
     * programowy dekoder → ostatnia klatka, jeden półobraz zmniejszony do okna.
     * Wynik: ms ≥ 0 — klatka pokazana; -1 — chwilowo brak; -2 — brak dekodera; -3 — luka w danych (bez zmiany obrazu).
     */
    private fun pipGrab(w: PipWin, c: BgChan): Grab {
        val t0 = SystemClock.elapsedRealtime()
        val (vpid, type) = c.an.videoStream ?: return Grab(-1, "brak PMT / ścieżki obrazu")
        val mime = TsFrames.mime(type) ?: return Grab(-2, "nieobsługiwany obraz $type")
        val swName = swDecoderName(mime) ?: return Grab(-2, "brak programowego dekodera $type")
        val b = c.buf
        // klatki kluczowe od najnowszej (starsze z dysku, gdy po najnowszej jest jeszcze za mało obrazu)
        val kfs = b.recentKeyframes(4, ramOnly = false)
        if (kfs.isEmpty()) return Grab(-1, "brak klatki kluczowej w buforze (${b.total / 1000} kB)")
        var ends = IntArray(0)
        var full = false
        var gap = false
        var desc = ""
        var fallbackKf = -1L
        fun load(kf: Long): TsFrames.Pic? {
            val len = minOf(b.total - kf, PIP_READ_MAX).toInt()
            if (len < 188 * 4) return null
            if (pipData.size < len) pipData = ByteArray(len)
            if (pipAu.size < len) pipAu = ByteArray(len)
            var got = 0
            while (got < len) { val n = b.read(kf + got, pipData, got, len - got); if (n <= 0) break; got += n }
            if (got < 188 * 4) return null
            return TsFrames.picture(pipData, got, vpid, type, 0, pipAu)
        }
        fun use(pic: TsFrames.Pic) {
            ends = pic.ends; full = pic.full
            desc = if (pic.full) "pełna klatka" else "pasami ${pic.spanMs} ms" + (if (pic.needMs >= 0) ", pełny po ${pic.needMs} ms" else "")
        }
        for (kf in kfs) {
            val pic = load(kf) ?: continue
            if (pic.gap && pic.ends.isEmpty()) gap = true
            if (pic.ends.isEmpty()) continue
            // pełna klatka kluczowa albo odświeżanie pasami z wystarczającym zapasem obrazu — od razu
            if (pic.full || pic.toNextKey) { use(pic); break }
            if (fallbackKf < 0) fallbackKf = kf
        }
        // tryb szybki: krótszy odcinek z najnowszej klatki (obraz może być niepełny, ale widać, co leci)
        if (ends.isEmpty() && fallbackKf >= 0) load(fallbackKf)?.takeIf { it.ends.isNotEmpty() }?.let { use(it) }
        if (ends.isEmpty()) return if (gap) Grab(-3, "luka") else Grab(-1, "brak obrazu (PID $vpid $type)")
        val au = ends.last()
        var maxUnit = ends[0]
        for (i in 1 until ends.size) maxUnit = maxOf(maxUnit, ends[i] - ends[i - 1])
        var pc = w.codec
        if (pc != null && (pc.mime != mime || pc.maxIn < maxUnit)) { pipRelease(w); pc = null }
        if (pc == null) {
            val maxIn = maxOf(maxUnit + 65536, 1 shl 20)
            val codec = TsFrames.openDecoder(swName, mime, maxIn)
            pc = PipCodec(codec, mime, maxIn)
            w.codec = pc
            PipLog.i("okno ${w.idx + 1}: ${c.name} — dekoder ${codec.name} ($type)")
        }
        val codec = pc.codec
        val pw = w.pw; val ph = w.ph
        if (w.px.size != pw * ph) w.px = IntArray(pw * ph)
        val bmp = w.bmps[w.bmpTurn]?.takeIf { it.width == pw && it.height == ph }
            ?: android.graphics.Bitmap.createBitmap(pw, ph, android.graphics.Bitmap.Config.ARGB_8888).also { w.bmps[w.bmpTurn] = it }
        var shown = false
        var ok = false
        try {
            shown = TsFrames.decodeImage(codec, pipAu, ends, 4000, first = full) { img ->
                TsFrames.fieldArgb(img, pw, ph, w.px)
                bmp.setPixels(w.px, 0, pw, 0, 0, pw, ph)
            }
            ok = true
        } finally {
            // gotowy na następną klatkę; dekoder, który nie odpowiada albo zgłosił błąd — zwalniany
            if (!ok || !shown) pipRelease(w) else try { codec.flush() } catch (_: Exception) { pipRelease(w) }
        }
        if (!shown) return Grab(-1, "$type ${au / 1000} kB, ${codec.name}: dekoder nie oddał obrazu")
        w.bmpTurn = 1 - w.bmpTurn
        val g = w.gen
        ui.post { if (w.gen == g) w.iv.setImageBitmap(bmp) }
        return Grab(SystemClock.elapsedRealtime() - t0, "$type ${ends.size} jedn. ${au / 1000} kB, $desc, ${codec.name}")
    }

    /** Długie OK → PiP: okna (zamiana z głównym, zamknięcie), dodanie kanału; menu zostaje otwarte do WSTECZ. */
    private fun showPipMenu(sel: Int = 0) {
        if (!pipPossible()) { showMessage(getString(R.string.pip_needs)); return }
        val acts = ArrayList<Pair<String, () -> Unit>>()
        val open = bgChans.filter { it.pip >= 0 }.sortedBy { it.pip }
        val pick = getString(R.string.pip_add_other)
        val change = getString(R.string.pip_change)
        for (c in open) {
            // przy nagraniu obraz główny to nagranie — zamiana z głównym niedostępna
            if (!recMode) acts += (getString(R.string.pip_window, c.pip + 1, c.name) + " — " + getString(R.string.pip_swap)) to { pipSwap(c) }
            acts += (getString(R.string.pip_window, c.pip + 1, c.name) + " — " + change) to { pipReplace = c; pipPickChannel() }
            acts += (getString(R.string.pip_window, c.pip + 1, c.name) + " — " + getString(R.string.pip_close)) to { pipClose(c) }
        }
        if (open.size < PIP_MAX) {
            for (c in bgChans.filter { it.pip < 0 }) acts += getString(R.string.pip_add_buffered, c.name) to { pipOpen(c) }
        }
        if (open.size < PIP_MAX) acts += pick to { pipReplace = null; pipPickChannel() }
        if (open.isNotEmpty()) acts += getString(R.string.pip_close_all) to { for (c in open) pipClose(c) }
        TvDialog.list(this, getString(R.string.pip_title), acts.map { TvDialog.Item(it.first) }, sel.coerceIn(0, acts.size - 1), widthFrac = 0.5f) { i ->
            val (label, run) = acts[i]
            run()
            // po każdej zmianie menu wraca (wybór z listy kanałów — po dodaniu okna); wyjście tylko WSTECZ
            if (label != pick && !label.endsWith(change)) pipMenuAgain(i)
        }
    }

    /** PiP możliwe: na żywo przez bufor albo przy odtwarzaniu nagrania (z dekodera / lokalnego). */
    private fun pipPossible() = bgAllowed() && (recMode || bufLive)

    /** Okno, któremu „Zmień kanał” podmienia kanał (wybór z listy). */
    private var pipReplace: BgChan? = null

    /** Menu PiP ponownie po zmianie (po zamianie z głównym — gdy nowy kanał już gra przez bufor). */
    private fun pipMenuAgain(sel: Int = 0, tries: Int = 10) {
        ui.postDelayed({
            when {
                isFinishing || panel.visibility == View.VISIBLE -> {}
                pipPossible() -> showPipMenu(sel)
                tries > 0 -> pipMenuAgain(sel, tries - 1)
            }
        }, 400)
    }

    private fun pipOpen(c: BgChan, want: Int = -1) {
        val free = (if (want in 0 until PIP_MAX && bgChans.none { it.pip == want }) want else null)
            ?: (0 until PIP_MAX).firstOrNull { i -> bgChans.none { it.pip == i } } ?: run { showMessage(getString(R.string.pip_full)); return }
        // bufory w tle zostają — kanał z bufora przechodzi do okna
        c.pip = free
        c.buf.maxMin = pipBufLen()
        pipRefresh()
        refreshPanelRows()
    }

    private fun pipClose(c: BgChan) {
        c.pip = -1
        c.leftAt = SystemClock.elapsedRealtime()
        // kanał wraca do zasad bufora poprzednich kanałów
        // bufor ostatnio oglądanego kanału (odłożony na czas nagrań) zostaje
        if (prefs.bgCount == 0 && !c.parked) bgDrop(c, "zamknięte okno PiP") else c.buf.maxMin = bgBufLen()
        pipRefresh()
        refreshPanelRows()
    }

    private fun pipSwap(c: BgChan) {
        val ch = c.ch ?: return
        val i = c.list.indexOf(ch)
        if (i < 0) return
        playIn(c.bIdx, c.list, i)
    }

    /** Akcja klawisza „PiP okno N”: zamiana okna [n] (0..2) z głównym obrazem. */
    private fun pipKey(n: Int) {
        val c = bgChans.firstOrNull { it.pip == n }
        if (c == null) { showMessage(getString(R.string.pip_no_window, n + 1)); return }
        if (recMode) { showMessage(getString(R.string.pip_rec_no_swap)); return }
        pipSwap(c)
    }

    /** Wybór kanału do PiP na zwykłej liście kanałów (◀ ▶ bukiety, OK dodaje okno bez przełączania, WSTECZ anuluje). */
    private fun pipPickChannel() {
        if (recMode) {
            // przy nagraniu lista otwiera się na bukiecie ostatnio oglądanego kanału (nie na nagraniach)
            if (bouquets.isEmpty()) return
            panelHist = false
            panelBouquet = playBouquet
            panelList = playList
            panel.visibility = View.VISIBLE
            resetPanelTimer()
            banner.visibility = View.GONE
            list.requestFocus()
            loadPanelEpg()
        } else showPanel()
        if (panel.visibility != View.VISIBLE) return
        panelPip = true
        refreshPanel(if (recMode) playIndex.coerceAtLeast(0) else list.selectedItemPosition.coerceAtLeast(0))
    }

    private fun pipAdd(ch: Channel, bIdx: Int, l: List<Channel>) {
        val rep = pipReplace
        pipReplace = null
        if (!pipPossible()) { showMessage(getString(R.string.pip_needs)); return }
        val url = api.streamUrl(ch)
        val cur = playList.getOrNull(playIndex)
        // przy nagraniu ostatnio oglądany kanał nie jest obrazem głównym — może trafić do okna
        if (!recMode && (url == tsLiveUrl || (cur != null && OpenWebif.key(cur.ref) == OpenWebif.key(ch.ref)))) { showMessage(getString(R.string.pip_is_main)); return }
        val have = bgOf(ch)
        if (have != null && have.pip >= 0) { showMessage(getString(R.string.pip_already)); return }
        // „Zmień kanał”: okno zamykane, nowy kanał wchodzi na jego miejsce
        val want = rep?.takeIf { it.pip >= 0 && it in bgChans }?.let { val w = it.pip; pipClose(it); w } ?: -1
        if (have != null) { pipOpen(have, want); return }
        if (bgChans.count { it.pip >= 0 } >= PIP_MAX) { showMessage(getString(R.string.pip_full)); return }
        // brak miejsca: wypada najdawniej opuszczony bufor w tle (bez nagrywania); same nagrywane — komunikat
        if (bgChans.size >= BG_SLOTS) {
            val v = bgChans.filter { it.pip < 0 && it.lrec == null }.minByOrNull { it.leftAt }
                ?: run { showMessage(getString(R.string.pip_recording_busy)); return }
            bgDrop(v, "miejsce dla okna PiP")
        }
        val c = bgNew(ch, bIdx, l) ?: run { showMessage(getString(R.string.pip_no_slot)); return }
        pipOpen(c, want)
    }

    /** Start (albo wznowienie) odtwarzania z bufora; [reuse] — połączenie zapisu z dekoderem już działa. */
    private fun startBufLive(url: String, reuse: Boolean) {
        val b = tsBuf ?: return
        val p = player ?: return
        bufFallbackWarned = false
        val now = SystemClock.elapsedRealtime()
        var leadMs = -1L
        val start = if (reuse) {
            // od klatki kluczowej z zapasem ≥ bufor startowy + 1 s: obraz od razu, bez przycięcia na starcie
            val k = b.keyframeAt(now - (prefs.bufferMs + 1000))
            if (k != null) { leadMs = now - k.second; k.first }
            else if (b.total > 0) { leadMs = -2; b.offsetAt(now - (prefs.bufferMs + 1500)) } else 0L
        } else {
            tsLiveUrl = url
            bufLive = true
            tsWriteFrom = 0
            val from = b.total                                       // nowe dane (0 — nowy kanał)
            startBufRecorder(url)
            from
        }
        AppLog.i("bufor: ${if (reuse) "wznowienie" else "start"} odtwarzania przez bufor (${prefs.engine.uppercase()}, od bajtu $start" +
            (if (leadMs >= 0) ", klatka kluczowa ${leadMs} ms przed końcem" else if (leadMs == -2L) ", bez znanej klatki kluczowej" else "") +
            (if (prefs.tsMin <= 0) ", bufor ${TimeshiftBuffer.BUF_PLAY_MIN} min" else "") + ")")
        if (prefs.engine == "vlc") {
            startVlc(url, b, start)
            return
        }
        ui.removeCallbacks(vlcTick)
        proxy?.stop(); proxy = null
        vlc?.release(); vlc = null
        val gen = streamGen
        val myBuf = bufGen
        val src = ProgressiveMediaSource.Factory(
            DataSource.Factory { TimeshiftDataSource(b, start, null, {}, lateHeader = { analyzer?.psiHeader() }, alive = { bufGen == myBuf }) },
            AvShift.wrap(DefaultExtractorsFactory().setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)),
        ).createMediaSource(MediaItem.fromUri("bufor://na-zywo/$gen/$start"))
        vlcLayout.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
        p.setMediaSource(src)
        p.prepare()
        p.playWhenReady = true
        // dane już na nośniku — bez fazy szybkiego startu strażnika (zbędny restart w pierwszych sekundach)
        watchdog?.newStream(prefs.fastStart && !reuse)
    }

    /** Nowy kanał (albo zmiana ustawień): bufor od zera, odtwarzanie na żywo. */
    private fun tsNewChannel() {
        if (bgPromoted) {
            // powrót na kanał buforowany w tle: bufor, zapis i dziennik przejęte — bez resetu
            bgPromoted = false
            ui.removeCallbacks(journalTick)
            ui.postDelayed(journalTick, 3000)
            tsWriteFrom = 0L
            return
        }
        if (localRec != null) finishLocalRec("zmiana kanału", restartLive = false)
        if (tsMode) tsLeave()
        bufStop("zmiana kanału")
        journal.clear()
        ui.removeCallbacks(journalTick)
        ui.postDelayed(journalTick, 3000)
        // przy odtwarzaniu przez bufor zapis od razu (odtwarzacz czyta z bufora)
        tsWriteFrom = if (prefs.bufPlay) 0L else if (prefs.tsDelay < 0) Long.MAX_VALUE else SystemClock.elapsedRealtime() + prefs.tsDelay * 1000L
        val min = if (prefs.tsMin > 0) prefs.tsMin else if (prefs.bufPlay) TimeshiftBuffer.BUF_PLAY_MIN else 0
        if (min <= 0) {
            tsBuf?.let { closeBufferAsync(it) }; tsBuf = null
            return
        }
        val dir = TimeshiftBuffer.dir(this, prefs)
        if (dir == null) {
            // wybrany dysk USB niedostępny, a pamięci urządzenia nie wolno użyć — timeshift wyłączony do czasu podłączenia
            if (tsBuf != null || !tsWarned) {
                tsWarned = true
                AppLog.i("timeshift: nośnik ${prefs.tsDir.ifBlank { "pamięć urządzenia (brak zgody)" }} niedostępny — timeshift wyłączony")
                if (prefs.tsMin <= 0) showMessage(getString(R.string.player_bufplay_fallback)) else showMessage(getString(R.string.player_ts_disabled, getString(if (prefs.tsDir.isBlank()) R.string.player_ts_disk_not_selected else R.string.player_ts_usb_unavailable)))
            }
            tsBuf?.let { closeBufferAsync(it) }; tsBuf = null
            return
        }
        tsWarned = false
        val b = tsBuf
        if (b == null || b.root != dir) {
            b?.let { closeBufferAsync(it) }
            if (b != null) bgDropAll("zmiana nośnika")
            // katalog nie zajęty przez kanały buforowane w tle
            val used = bgChans.map { it.buf.dir }.toSet()
            val slot = TimeshiftBuffer.slotDirs(dir).first { it !in used }
            tsBuf = TimeshiftBuffer(slot, min, dir, prefs.tsWriteBatchBytes).also { it.pcrPid = { analyzer?.pcrPid ?: -1 } }
            AppLog.i("timeshift: $min min w ${slot.absolutePath} (wolne ${TimeshiftBuffer.gb(dir.usableSpace)})")
        } else {
            b.maxMin = min
            b.writeBatchBytes = prefs.tsWriteBatchBytes
            b.reset()
        }
        bgChans.forEach { it.buf.writeBatchBytes = prefs.tsWriteBatchBytes }
        scheduleStorageGuard()
    }

    private var tsWarned = false
    @Volatile private var tsWriteFrom = 0L        // zapis do bufora rusza dopiero po tej chwili (opóźnienie po zmianie kanału)

    /** Przy starcie: pliki bufora z poprzedniego uruchomienia (np. aplikacja przerwana) — kasowane w tle. */
    private fun tsCleanupAtStart() {
        if (tsCleanedAtStart) return
        tsCleanedAtStart = true
        val roots = listOfNotNull(java.io.File(TimeshiftBuffer.internalBase(this), "timeshift"),
            prefs.tsDir.takeIf { it.isNotBlank() && TimeshiftBuffer.available(it) }?.let { java.io.File(it, "timeshift") })
        for (r in roots) for (d in TimeshiftBuffer.slotDirs(r)) TimeshiftBuffer.cleanupAsync(d)
    }

    /** Wyjście z aplikacji / nagranie: koniec timeshiftu, bufor skasowany (w tle). */
    /** [keepBg] — kanały w tle zostają (odtwarzanie nagrania: zapis w tle i bufor ostatniego kanału). */
    private fun tsStopAll(keepBg: Boolean = false) {
        if (localRec != null) finishLocalRec("koniec odtwarzania", restartLive = false)
        if (tsMode) tsLeave()
        bufStop("koniec odtwarzania na żywo")
        if (!keepBg) bgDropAll("koniec odtwarzania na żywo")
        tsCtl = false; tsChBar = false
        tsBuf?.let { closeBufferAsync(it) }
        // po powrocie wszystko od nowa: nowy bufor w katalogu głównym, bez przejętych połączeń i analizatorów kanałów w tle
        tsBuf = null
        tsRec?.stop(); tsRec = null; tsRecAn = null
        bgPromoted = false
        journal.clear()
        ui.removeCallbacks(journalTick)
    }

    /** Koniec odtwarzania z bufora (bez uruchamiania strumienia na żywo). */
    private fun tsLeave() {
        pvCancel()
        tsMode = false
        tsAudioPid = null
        tsSetShifted(false)
        tsCtl = false
        if (!bufLive) { tsRec?.stop(); tsRec = null }      // odtwarzanie przez bufor: zapis działa dalej
        ui.removeCallbacks(shiftTick)
        ui.removeCallbacks(tsApply)
        tsPending = 0
    }

    private fun restartTimeshiftRecorder(url: String, generation: Int) {
        if (!tsMode || tsLiveUrl != url || streamGen != generation) return
        if (bufLive) { startBufRecorder(url); return }
        tsRec?.stop()
        lateinit var recorder: TimeshiftRecorder
        recorder = TimeshiftRecorder(
            url, api.authHeader(), { analyzer },
            { analyzer?.dropCarry(); tsBuf?.resync() },
            onFailure = {
                runOnUiThread {
                    if (tsMode && tsLiveUrl == url && streamGen == generation && tsRec === recorder) {
                        tsRec = null
                        AppLog.i("timeshift: brak strumienia na żywo — sprawdzam dekoder")
                        probeStalledStream()
                    }
                }
            },
        )
        tsRec = recorder
        recorder.start()
    }

    /** Chwila (odbioru), którą teraz widać na ekranie. */
    private fun tsShownWall(): Long = tsBaseWall + (player?.currentPosition ?: 0)

    /**
     * Pauza albo cofnięcie strumienia na żywo: odtwarzacz przechodzi na bufor, a strumień z dekodera
     * dalej zapisuje się do bufora (osobne połączenie).
     */
    /** Czy timeshift jest skonfigurowany; jeśli nie — komunikat. */
    private fun tsConfigured(): Boolean {
        if (prefs.tsMin > 0) return true
        showMessage(getString(R.string.message_timeshift_is_not_configured_settings_playback_and_video))
        return false
    }

    private fun tsEnter(pause: Boolean, backMs: Long) {
        val url = currentUrl
        if (!tsConfigured()) return
        if (recMode) { showMessage(getString(R.string.message_timeshift_is_unavailable_when_playing_a_recording)); return }
        if (url == null) { showMessage(getString(R.string.message_timeshift_no_channel_playing)); return }
        if (tsBuf == null) tsNewChannel()          // bufor mógł nie powstać (np. dysk podłączony po starcie)
        val b = tsBuf
        if (b == null) { showMessage(getString(R.string.message_timeshift_unavailable_usb_drive_unavailable_or_device_s)); return }
        b.failed?.let { showMessage(getString(R.string.player_ts_error, it)); return }
        // bufor jeszcze nie zapisywany (opóźnienie / tryb ręczny) lub prawie pusty: pauza rusza od teraz, cofnięcie niemożliwe
        val fresh = b.total < 2_000_000
        if (fresh && !pause) { showMessage(getString(R.string.message_timeshift_buffer_is_still_empty_nothing_to_rewind)); return }
        tsWriteFrom = 0
        val p = player ?: return
        // obraz na ekranie jest za danymi o zapas odtwarzacza
        val behind = if (vlc != null) prefs.bufferMs.toLong() else p.totalBufferedDuration
        tsAudioPid = vlc?.selectedAudioTrackId() ?: selectedAudioPid()
        val now = SystemClock.elapsedRealtime()
        val target = if (fresh) now else
            timeshiftSeekTarget(now - behind - 300, -backMs, b.oldestWall, now)
        AppLog.i("timeshift: zachowuję ścieżkę audio PID=${tsAudioPid ?: "auto"}")
        AppLog.i("timeshift: ${if (pause) "pauza" else "cofnięcie ${backMs / 1000} s"} (bufor ${b.spanMs() / 60000} min)")
        // strumień na żywo odtwarzacza zatrzymany — zapis przejmuje osobne połączenie
        ui.removeCallbacks(vlcTick)
        proxy?.stop(); proxy = null
        vlc?.release(); vlc = null
        p.stop()
        tsLiveUrl = url
        tsMode = true
        if (!bufLive || tsRec == null) restartTimeshiftRecorder(url, streamGen)
        tsSetShifted(true)
        tsCtl = true
        tsPlayFrom(target, play = !pause)
        ui.removeCallbacks(shiftTick)
        ui.postDelayed(shiftTick, 1000)
    }

    private fun tsPlayFrom(wall: Long, play: Boolean) {
        val b = tsBuf ?: return
        val p = player ?: return
        tsAudioSourceGen++
        val off = b.offsetAt(wall)
        tsBaseWall = b.wallAt(off)
        val hdr = analyzer?.psiHeader()
        val gen = streamGen
        val src = ProgressiveMediaSource.Factory(
            DataSource.Factory { TimeshiftDataSource(b, off, hdr, onGone = { }) },
            AvShift.wrap(DefaultExtractorsFactory().setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)),
        ).createMediaSource(MediaItem.fromUri("timeshift://bufor/$gen/$off"))
        vlcLayout.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        failStreak = 0
        restartPending = false
        ui.removeCallbacks(restartRunnable)
        p.setMediaSource(src)
        p.prepare()
        p.playWhenReady = play
        watchdog?.reset()
        watchdog?.endStart()
        watchdog?.paused = tsShifted
        // przeładowanie po przewinięciu nie może zastąpić paska kanału paskiem timeshiftu
        if (tsShifted && !(tsChBar && banner.visibility == View.VISIBLE)) tsShowBanner(sticky = !play)
    }

    /**
     * Powrót na żywo. ExoPlayer: skok na koniec bufora (kilka sekund za transmisją) — połączenie zapisu
     * działa dalej, więc w buforze nie ma dziury. VLC: zwykły strumień z dekodera (VLC nie czyta z bufora).
     */
    private fun tsGoLive(reason: String = "") {
        val url = tsLiveUrl ?: currentUrl ?: return
        val b = tsBuf
        val controlsActive = tsCtl
        val showControls = controlsActive && !tsChBar
        if (prefs.engine != "vlc" && tsRec != null && b != null && b.failed == null && b.total > 0) {
            AppLog.i("timeshift: na żywo — koniec bufora${if (reason.isNotBlank()) " ($reason)" else ""}")
            tsSetShifted(false)
            ui.removeCallbacks(tsApply)
            tsPending = 0
            showMessage(getString(R.string.message_live))
            // ~6 s za końcem bufora: odtwarzacz ma od razu zapas na start (dane z dekodera przychodzą paczkami)
            tsPlayFrom(SystemClock.elapsedRealtime() - 6000, play = true)
            if (showControls) tsCtlOpen() else playList.getOrNull(playIndex)?.let { showBanner(it) }
            return
        }
        tsLeave()
        tsCtl = controlsActive
        AppLog.i("timeshift: na żywo${if (reason.isNotBlank()) " ($reason)" else ""}")
        showMessage(getString(R.string.message_live))
        failStreak = 0
        startStream(url)
        if (showControls) tsCtlOpen() else playList.getOrNull(playIndex)?.let { showBanner(it) }
    }

    /** Menu przewijania na żywo (OK na pasku kanału): pasek ze skrótem klawiszy, czeka na przewinięcie lub pauzę. */
    private fun tsCtlOpen() {
        val b = tsBuf
        if (b == null && prefs.tsMin > 0) tsNewChannel()
        if (!tsConfigured()) return
        tsCtl = true
        tsChBar = false
        val ch = playList.getOrNull(playIndex)
        bannerName.text = ch?.let { "${it.num}   ${it.name}" } ?: ""
        Picons.show(bannerIcon, this, ch?.ref.orEmpty(), prefs.picons, prefs.missingPiconLogo)
        val buffered = tsBuf?.takeIf { it.total > 0 }
        val span = buffered?.let { timeshiftAvailableSpan(it.oldestWall, SystemClock.elapsedRealtime()) } ?: 0L
        refreshBannerTop()
        bannerNow.text = if (buffered != null) getString(R.string.player_ts_live_buffer, fmtMs(span)) else getString(R.string.player_ts_live_empty)
        bannerStart.text = ""
        bannerEnd.text = getString(R.string.player_live_lower)
        bannerProgress.progress = 1000
        bannerNext.text = getString(R.string.player_ts_controls, "${prefs.tsStepSec}/${prefs.tsStepSecLong} s", "${Prefs.stepMinLabel(prefs.tsStepUdSec)}/${prefs.tsStepUdLongMin} min")
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, prefs.tsBannerSec.coerceIn(1, 30) * 1000L)
    }

    private fun tsTogglePause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        if (!p.playWhenReady) tsSetShifted(true)
        tsShowBanner(sticky = !p.playWhenReady)
    }

    /** Przewinięcie o [delta] ms; kilka naciśnięć z rzędu sumuje się (jedno przeładowanie). */
    private fun tsSeek(delta: Long) {
        if (!tsMode) return
        val b = tsBuf ?: return
        val base = if (tsPending != 0L) tsPending else tsShownWall()
        tsPending = timeshiftSeekTarget(base, delta, b.oldestWall, SystemClock.elapsedRealtime())
        // podgląd: obraz zatrzymany, na ekranie klatka z miejsca na pasku
        if (pvStart()) { pvLastKey = SystemClock.elapsedRealtime(); seekPreview.requestBuffer(b, tsPending, (tsRecAn ?: analyzer)?.videoStream) }
        tsShowBanner(sticky = !pvPlaying())
        ui.removeCallbacks(tsApply)
        ui.postDelayed(tsApply, 700)
    }

    private val tsApply: Runnable = Runnable {
        if (pvWaitFrame { ui.postDelayed(tsApply, 150) }) return@Runnable
        val b = tsBuf
        val requested = tsPending
        tsPending = 0
        if (b == null || !tsMode || requested == 0L) return@Runnable
        val now = SystemClock.elapsedRealtime()
        val earliest = timeshiftEarliestSeekWall(b.oldestWall, now)
        val target = maxOf(requested, earliest)
        val play = pvPlaying()
        when {
            target >= now - 6000 -> tsGoLive("przewinięte do końca")
            target == earliest -> {
                showMessage(getString(R.string.player_ts_start, fmtMs(now - earliest)))
                tsPlayFrom(earliest, play = play)
            }
            else -> tsPlayFrom(target, play = play)
        }
        pvFinish()
    }

    // --- podgląd klatek przy przewijaniu (timeshift, nagrania): obraz główny zatrzymany, klatka kluczowa z miejsca
    // na pasku na całym ekranie (dekoder programowy); po puszczeniu klawiszy odtwarzanie od tego miejsca
    private val seekPreview by lazy { SeekPreview(this, zapFrame) }
    private var pvActive = false
    private var pvLastKey = 0L

    /**
     * Odtwarzanie od miejsca czeka na klatkę podglądu (dekodowanie / odczyt z dysku bywa dłuższe niż 0,7 s),
     * najwyżej ~2,5 s od ostatniego klawisza. true — odłożone ([again] ponawia).
     */
    private fun pvWaitFrame(again: () -> Unit): Boolean {
        if (pvActive && !seekPreview.frameReady && SystemClock.elapsedRealtime() - pvLastKey < 2500) { again(); return true }
        return false
    }
    private var pvWasPlaying = true

    /** Początek przewijania z podglądem (tylko ExoPlayer); false — bez podglądu. */
    private fun pvStart(): Boolean {
        val p = player ?: return false
        if (!pvActive) {
            pvActive = true
            pvWasPlaying = p.playWhenReady
            p.playWhenReady = false
            seekPreview.show()
        }
        ui.removeCallbacks(pvHideCheck)
        return true
    }

    /** Czy odtwarzanie ma iść (stan sprzed przewijania, gdy podgląd zatrzymał obraz). */
    private fun pvPlaying() = if (pvActive) pvWasPlaying else player?.playWhenReady != false

    /** Koniec przewijania: nakładka znika, gdy obraz główny jest gotowy w nowym miejscu (najpóźniej po 4 s). */
    private fun pvFinish() {
        if (!pvActive) return
        pvActive = false
        pvHideAt = SystemClock.elapsedRealtime() + 4000
        ui.removeCallbacks(pvHideCheck)
        ui.postDelayed(pvHideCheck, 250)
    }

    private fun pvCancel() {
        pvActive = false
        ui.removeCallbacks(pvHideCheck)
        if (seekPreview.shown) seekPreview.hide()
    }

    private var pvHideAt = 0L
    private val pvHideCheck = object : Runnable {
        override fun run() {
            val ready = player?.playbackState == Player.STATE_READY
            if (ready || SystemClock.elapsedRealtime() > pvHideAt || player == null) seekPreview.hide(if (ready) 300 else 0)
            else ui.postDelayed(this, 100)
        }
    }

    /** Krok przewijania strzałkami: ◀ ▶ — krok w sekundach, ▲ ▼ — w minutach; przytrzymanie: 2× / 3× więcej. */
    private fun seekStepMs(code: Int, repeat: Int): Long {
        val held = repeat > 0
        return if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)
            (if (held) prefs.tsStepSecLong else prefs.tsStepSec) * 1000L
        else if (held) prefs.tsStepUdLongMin * 60_000L else prefs.tsStepUdSec * 1000L
    }

    /** Klawisze w trybie timeshiftu. */
    private fun tsKey(code: Int, repeat: Int = 0): Boolean {
        if (code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) AppLog.i("timeshift: strzałka $code, pasek kanału=$tsChBar, widoczny=${banner.visibility == View.VISIBLE}")
        // Strzałki przewijają wyłącznie na widocznym pasku timeshiftu.
        if (code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT &&
            !timeshiftNavigationOnBanner(banner.visibility == View.VISIBLE, tsChBar)) return false
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val back = seekStepMs(code, repeat)
                if (tsMode) { tsSetShifted(true); tsSeek(-back) } else tsEnter(pause = false, backMs = back)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> {
                val forward = seekStepMs(code, repeat)
                if (tsMode && tsShifted) tsSeek(forward) else { showMessage(getString(R.string.message_already_live)); tsCtlOpen() }
            }
            // Wstecz: najpierw zamyka pasek (także przy pauzie), dopiero kolejne naciśnięcie wraca na żywo
            KeyEvent.KEYCODE_BACK -> {
                when (timeshiftBackAction(banner.visibility == View.VISIBLE, tsChBar, tsMode, tsShifted, prefs.tsBackLive)) {
                    TimeshiftBackAction.SHOW_CHANNEL_BANNER -> playList.getOrNull(playIndex)?.let { showBanner(it) }
                    TimeshiftBackAction.HIDE_BANNER -> { banner.visibility = View.GONE; tsChBar = false; ui.removeCallbacks(hideBanner) }
                    TimeshiftBackAction.GO_LIVE -> { tsCtl = false; tsGoLive() }
                    TimeshiftBackAction.EXIT -> { if (!tsMode || !tsShifted) tsCtl = false; return false }
                }
            }
            else -> return false
        }
        return true
    }

    private fun tsShowBanner(sticky: Boolean = false) {
        tsCtl = true
        val ch = playList.getOrNull(playIndex)
        bannerName.text = ch?.let { "${it.num}   ${it.name}" } ?: ""
        Picons.show(bannerIcon, this, ch?.ref.orEmpty(), prefs.picons, prefs.missingPiconLogo)
        tsChBar = false
        tsUpdateBanner()
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        if (!sticky) ui.postDelayed(hideBanner, prefs.tsBannerSec.coerceIn(1, 30) * 1000L)
    }

    private fun tsUpdateBanner() {
        val b = tsBuf ?: return
        val now = SystemClock.elapsedRealtime()
        if (tsPending != 0L) tsPending = maxOf(tsPending, timeshiftEarliestSeekWall(b.oldestWall, now))
        val shown = if (tsPending != 0L) tsPending else tsShownWall()
        val behind = (now - shown).coerceAtLeast(0)
        val oldest = b.oldestWall
        val span = timeshiftAvailableSpan(oldest, now)
        val paused = !pvPlaying()
        refreshBannerTop()
        bannerNow.text = getString(R.string.player_ts_shifted_buffer,
            if (paused) "⏸" else "▶", fmtMs(behind), fmtMs(span))
        bannerStart.text = ""
        bannerEnd.text = getString(R.string.player_live_lower)
        bannerProgress.progress = timeshiftBufferProgress(shown, oldest, now)
        bannerNext.text = getString(R.string.player_ts_controls, "${prefs.tsStepSec}/${prefs.tsStepSecLong} s", "${Prefs.stepMinLabel(prefs.tsStepUdSec)}/${prefs.tsStepUdLongMin} min")
    }

    /** Co sekundę: pasek, ukrywanie, pilnowanie okna przy długiej pauzie. */
    private val shiftTick = object : Runnable {
        override fun run() {
            if (!tsMode) return
            ui.postDelayed(this, 1000)
            val b = tsBuf ?: return
            if (player == null) return
            // pauza dłuższa niż okno: miejsce pauzy zaraz wypadnie z bufora — wznowienie od najstarszego
            if (tsPending == 0L && b.trimmed && tsShownWall() < b.oldestWall + 5000) {
                showMessage(getString(R.string.message_timeshift_buffer_full_resuming_from_the_oldest_recorded))
                AppLog.i("timeshift: pauza dłuższa niż okno — wznowienie od początku bufora")
                tsPlayFrom(b.oldestWall + 10_000, play = true)
                return
            }
            if (tsShifted && !tsChBar && banner.visibility == View.VISIBLE) {
                tsUpdateBanner()
            }
        }
    }

    // ---------------------------------------------------------------- nagrania lokalne (z bufora timeshiftu)

    /** Audycje zauważone podczas zapisu bufora — spis i skoki w nagraniach z bufora. */
    private var journal = ProgramJournal()

    private fun journalAdd(begin: Long, end: Long, title: String, shortDesc: String, longDesc: String) {
        journal.add(JournalEvent(begin, end, title, listOf(shortDesc, longDesc).filter { it.isNotBlank() }.distinct().joinToString("\n")))
    }

    /** Co minutę, póki działa bufor: bieżąca audycja kanału (EPG z pamięci, z EIT, gdy włączone) do dziennika. */
    private val journalTick = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            val b = tsBuf ?: return
            if (recMode) return
            val ch = playList.getOrNull(playIndex) ?: return
            epg.nowNext(ch) { now, _ ->
                if (now != null && playList.getOrNull(playIndex) === ch && tsBuf === b) {
                    journalAdd(now.begin, now.end, now.title, now.shortDesc, now.longDesc)
                    val oldestEpoch = (System.currentTimeMillis() - b.spanMs()) / 1000
                    if (localRec == null) journal.trimBefore(oldestEpoch)
                }
            }
            ui.postDelayed(this, 60_000)
        }
    }

    /** Trwające nagranie z bufora: przypięte kawałki, opis, koniec (zegar monotoniczny; MAX = do zmiany kanału). */
    private class LocalRec(
        val pin: TimeshiftBuffer.Pin, val mode: Int, val meta: org.json.JSONObject, val untilWall: Long,
        val ch: Channel, val usedBefore: Long, val capacity: Long,
        /** bufor i dziennik audycji, z których powstaje nagranie (bieżący kanał albo kanał w tle) */
        val buf: TimeshiftBuffer, val journal: ProgramJournal,
    )
    private var localRec: LocalRec? = null

    private fun localBase(): File? = (tsBuf?.root ?: TimeshiftBuffer.dir(this, prefs))?.let { LocalRecordings.base(it) }

    private fun isRecRef(ref: String?) = ref == REC_REF || ref == REC_LOCAL_BOUQUET
    /** Bukiety aplikacji (nagrania, historia) — nie ma ich na dekoderze. */
    private fun isVirtual(ref: String?) = isRecRef(ref) || ref == HIST_BOUQUET

    /** Długie OK → „Nagraj…”: audycja / bufor do teraz / cały bufor do zmiany kanału; w trakcie — zatrzymanie. */
    private fun showRecordMenu() {
        val cur = localRec
        if (cur != null) {
            TvDialog.confirm(this, getString(R.string.player_localrec_running_title), cur.meta.optString("title"), listOf(
                TvDialog.Btn(getString(R.string.player_localrec_stop)) { finishLocalRec("zatrzymane", restartLive = true) },
                TvDialog.Btn(getString(R.string.message_close)),
            ))
            return
        }
        if (recMode) { showMessage(getString(R.string.message_timeshift_is_unavailable_when_playing_a_recording)); return }
        val ch = playList.getOrNull(playIndex)
        if (currentUrl == null || ch == null) { showMessage(getString(R.string.message_timeshift_no_channel_playing)); return }
        if (!tsConfigured()) return
        if (tsBuf == null) tsNewChannel()
        val b = tsBuf
        if (b == null) { showMessage(getString(R.string.message_timeshift_unavailable_usb_drive_unavailable_or_device_s)); return }
        b.failed?.let { showMessage(getString(R.string.player_ts_error, it)); return }
        val base = LocalRecordings.base(b.root)
        val internal = b.root.absolutePath.startsWith(TimeshiftBuffer.internalBase(this).absolutePath)
        if (internal && !prefs.recInternalOk) {
            TvDialog.confirm(this, getString(R.string.player_localrec_title), getString(R.string.player_localrec_internal_consent), listOf(
                TvDialog.Btn(getString(R.string.player_localrec_allow)) { prefs.recInternalOk = true; showRecordMenu() },
                TvDialog.Btn(getString(R.string.dialog_cancel)),
            ))
            return
        }
        io.execute {
            val used = LocalRecordings.used(base)
            val cap = LocalRecordings.capacity(base)
            runOnUiThread {
                if (cap > 0 && used >= cap * prefs.recMaxPct / 100) {
                    TvDialog.confirm(this, getString(R.string.player_localrec_title),
                        getString(R.string.player_localrec_limit_reached, prefs.recMaxPct, TimeshiftBuffer.gb(used), TimeshiftBuffer.gb(cap)),
                        listOf(TvDialog.Btn(getString(R.string.player_local_recordings)) { showRecordings(REC_LOCAL_BOUQUET) },
                            TvDialog.Btn(getString(R.string.message_close))))
                    return@runOnUiThread
                }
                epg.nowNext(ch) { now, _ ->
                    if (playList.getOrNull(playIndex) !== ch || localRec != null) return@nowNext
                    val span = b.spanMs()
                    val opts = ArrayList<Pair<String, () -> Unit>>()
                    if (now != null) opts += getString(R.string.player_localrec_event, now.title, hm.format(Date(now.begin * 1000)), hm.format(Date(now.end * 1000))) to
                        { startLocalRec(REC_EVENT, ch, now, used, cap) }
                    opts += getString(R.string.player_localrec_buffer_now, fmtMs(span)) to { startLocalRec(REC_NOW, ch, now, used, cap) }
                    opts += getString(R.string.player_localrec_buffer_until_zap) to { startLocalRec(REC_UNTIL_ZAP, ch, now, used, cap) }
                    TvDialog.list(this, getString(R.string.player_localrec_title), opts.map { TvDialog.Item(it.first) }, 0, widthFrac = 0.55f) { w ->
                        opts[w].second()
                    }
                }
            }
        }
    }

    private fun startLocalRec(mode: Int, ch: Channel, ev: EpgEvent?, usedBefore: Long, capacity: Long, bg: BgChan? = null) {
        val b = (if (bg != null) bg.buf else tsBuf) ?: return
        if (mode != REC_NOW && activeRecs() >= recLimit()) {
            AppLog.i("nagranie lokalne: limit ${recLimit()} nagrań z buforów osiągnięty")
            showMessage(getString(R.string.player_rec_limit))
            return
        }
        val jr = bg?.journal ?: journal
        if (bg != null) {
            if (bg.lrec != null || bg !in bgChans) return
            ev?.let { jr.add(JournalEvent(it.begin, it.end, it.title, listOf(it.shortDesc, it.longDesc).filter { d -> d.isNotBlank() }.distinct().joinToString("\n"))) }
        } else {
            if (localRec != null || playList.getOrNull(playIndex) !== ch) return
            ev?.let { journalAdd(it.begin, it.end, it.title, it.shortDesc, it.longDesc) }
            tsWriteFrom = 0                               // bufor zapisuje od teraz (także przy starcie ręcznym)
        }
        val nowEpoch = System.currentTimeMillis()
        val nowWall = SystemClock.elapsedRealtime()
        fun wallOf(epochSec: Long) = nowWall - (nowEpoch - epochSec * 1000)
        // „audycja”: od jej początku wg EPG (wcześniejsze audycje z bufora pomijane), pozostałe — cały bufor
        val from = if (mode == REC_EVENT && ev != null) b.offsetAt(wallOf(ev.begin)) else b.oldestOffset
        val dir = LocalRecordings.newDir(LocalRecordings.base(b.root), ch.name, nowEpoch)
        val pin = b.startPin(dir, from)
        if (pin == null) { showMessage(getString(R.string.player_localrec_failed, b.failed ?: "?")); return }
        val beginEpoch = nowEpoch - (nowWall - pin.fromWall)
        val title = if (mode == REC_EVENT && ev != null) ev.title else "${ch.name} ${hm.format(Date(beginEpoch))}"
        val meta = org.json.JSONObject()
            .put("title", title).put("service", ch.name).put("sref", ch.ref)
            .put("desc", if (mode == REC_EVENT && ev != null) listOf(ev.shortDesc, ev.longDesc).filter { it.isNotBlank() }.joinToString("\n") else "")
            .put("begin", beginEpoch / 1000).put("lengthSec", 0).put("mode", mode).put("done", false)
        LocalRecordings.writeMeta(dir, meta)
        val until = if (mode == REC_EVENT && ev != null) wallOf(ev.end) + 60_000 else Long.MAX_VALUE
        val r = LocalRec(pin, mode, meta, until, ch, usedBefore, capacity, b, jr)
        AppLog.i("nagranie lokalne: start ${listOf("audycja", "bufor do teraz", "do zmiany kanału", "do usunięcia bufora")[mode]} „$title” od ${fmtMs(nowWall - pin.fromWall)} wstecz" +
            (if (bg != null) " (kanał w tle ${bg.name})" else "") + ", ${dir.absolutePath}")
        if (bg != null) {
            if (mode == REC_NOW) { finishRec(r, "bufor do teraz", restartLive = false, main = false); return }
            bg.lrec = r
            showMessage(getString(R.string.player_localrec_started, title))
            ui.removeCallbacks(bgRecTick); ui.postDelayed(bgRecTick, 5000)
            refreshPanelRows()
            return
        }
        localRec = r
        if (mode == REC_NOW) { finishLocalRec("bufor do teraz", restartLive = true); return }
        showMessage(getString(R.string.player_localrec_started, title))
        ui.removeCallbacks(localRecTick)
        ui.postDelayed(localRecTick, 5000)
        refreshPanelRows()
        playList.getOrNull(playIndex)?.let { if (banner.visibility == View.VISIBLE && !tsCtl) showBanner(it) }
    }

    /**
     * Koniec nagrania: kawałki z bufora przeniesione do katalogu nagrania, opis uzupełniony, timeshift od nowa.
     * Odtwarzanie z bufora (przesunięte) wraca na żywo, gdy [restartLive].
     */
    private fun finishLocalRec(reason: String, restartLive: Boolean) {
        val r = localRec ?: return
        localRec = null
        ui.removeCallbacks(localRecTick)
        finishRec(r, reason, restartLive, main = true)
    }

    /** [main] — nagranie bieżącego kanału (timeshift / powrót na żywo); inaczej z bufora kanału w tle. */
    private fun finishRec(r: LocalRec, reason: String, restartLive: Boolean, main: Boolean) {
        val wasShifted = main && tsMode
        val url = tsLiveUrl ?: currentUrl
        if (wasShifted) tsLeave()
        val endWall = SystemClock.elapsedRealtime()
        val pin = r.buf.takeIf { it.pin === r.pin }?.finishPin() ?: r.pin
        val lengthSec = ((endWall - pin.fromWall) / 1000).coerceAtLeast(0)
        r.meta.put("lengthSec", lengthSec).put("size", pin.bytes).put("done", true).put("end", reason)
        // spis audycji z dziennika bufora; „do teraz” / „do zmiany kanału” — także tytuł i opis
        val from = r.meta.optLong("begin")
        val chapters = ProgramJournal.chapters(r.journal.snapshot(), from, from + lengthSec,
            minEdgeSec = if (r.mode == REC_EVENT) 90 else ProgramJournal.MIN_CHAPTER_SEC)
        if (chapters.isNotEmpty()) LocalRecordings.putChapters(r.meta, chapters)
        val desc = { ProgramJournal.describe(chapters, getString(R.string.player_localrec_from_middle), getString(R.string.player_localrec_no_end)) }
        if (r.mode != REC_EVENT && chapters.isNotEmpty()) {
            val prefix = "${r.ch.name} ${hm.format(Date(from * 1000))}–${hm.format(Date((from + lengthSec) * 1000))}"
            r.meta.put("title", ProgramJournal.title(prefix, chapters))
            r.meta.put("desc", desc())
        } else if (r.mode == REC_EVENT && chapters.size > 1) {
            r.meta.put("desc", desc())                 // tytuł zostaje — nagrywana audycja (np. EPG zmienione w trakcie)
        }
        if (chapters.size > 1) AppLog.i("nagranie lokalne: audycje: " + chapters.joinToString(" | ") { "${ProgramJournal.hms(it.start)} ${it.title}" })
        LocalRecordings.writeMeta(pin.dir, r.meta)
        if (pin.parts > 0) scanRecording(pin.dir, r.meta.optString("title"))
        val title = r.meta.optString("title")
        AppLog.i("nagranie lokalne: koniec ($reason) „$title” ${fmtMs(lengthSec * 1000)}, ${pin.parts} plików, ${TimeshiftBuffer.gb(pin.bytes)}")
        if (pin.parts == 0) {
            LocalRecordings.delete(pin.dir)
            showMessage(getString(R.string.player_localrec_empty))
        } else showMessage(getString(R.string.player_localrec_saved, title, fmtMs(lengthSec * 1000), reasonText(reason)))
        refreshPanelRows()
        if (restartLive && wasShifted && url != null && !recMode) {
            failStreak = 0
            startStream(url)
            playList.getOrNull(playIndex)?.let { showBanner(it) }
        }
    }

    private fun reasonText(reason: String) = when (reason) {
        "koniec audycji" -> getString(R.string.player_localrec_reason_event_end)
        "zmiana kanału" -> getString(R.string.player_localrec_reason_zap)
        "limit czasu" -> getString(R.string.player_localrec_reason_time, prefs.recMaxHours)
        "limit miejsca" -> getString(R.string.player_localrec_reason_space, prefs.recMaxPct)
        "brak miejsca" -> getString(R.string.player_localrec_reason_disk_full)
        "bufor zamknięty" -> getString(R.string.player_localrec_reason_buffer_closed)
        else -> ""
    }

    /** Pilnowanie zapisów z buforów kanałów w tle (jak localRecTick). */
    private val bgRecTick = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            val now = SystemClock.elapsedRealtime()
            for (c in bgChans.toList()) {
                val r = c.lrec ?: continue
                val b = c.buf
                val reason = when {
                    b.pin !== r.pin -> "bufor zamknięty"
                    b.failed != null -> "błąd bufora"
                    now >= r.untilWall -> "koniec audycji"
                    now - r.pin.fromWall >= prefs.recMaxHours * 3_600_000L -> "limit czasu"
                    r.capacity > 0 && r.usedBefore + b.pinnedBytes() >= r.capacity * prefs.recMaxPct / 100 -> "limit miejsca"
                    b.shrunk -> "brak miejsca"
                    else -> null
                } ?: continue
                c.lrec = null
                finishRec(r, reason, restartLive = false, main = false)
            }
            if (bgChans.any { it.lrec != null }) ui.postDelayed(this, 5000)
        }
    }

    /** Długie OK na liście przy kanale z buforem w tle: „Zakończ bufor” i „Nagraj bufor…” (albo „Zakończ zapis”). */
    private fun bgRecButtons(bg: BgChan, c: Channel, ev: EpgEvent?): List<TvDialog.Btn> = listOf(
        TvDialog.Btn(getString(R.string.player_bg_close)) { bgCloseAsk(bg) },
        bg.lrec?.let { r ->
            TvDialog.Btn(getString(R.string.player_bgrec_stop)) {
                if (bg.lrec === r) { bg.lrec = null; finishRec(r, "zatrzymane", restartLive = false, main = false); refreshPanelRows() }
            }
        } ?: TvDialog.Btn(getString(R.string.player_bgrec_menu)) { bufRecMenu(bg, bg.ch ?: c, ev) },
    )

    /** Oglądany kanał odtwarzany przez bufor (znacznik „B” na liście). */
    private fun isMainBuf(c: Channel): Boolean = bufLive && tsBuf != null &&
        playList.getOrNull(playIndex)?.ref?.let { OpenWebif.key(it) == OpenWebif.key(c.ref) } == true

    /** Przyciski w opisie audycji oglądanego kanału: zapis bufora albo zatrzymanie trwającego zapisu. */
    private fun mainRecButtons(c: Channel, ev: EpgEvent?): List<TvDialog.Btn> = listOf(
        localRec?.let { r ->
            TvDialog.Btn(getString(R.string.player_bgrec_stop)) {
                if (localRec === r) { finishLocalRec("zatrzymane", restartLive = true); refreshPanelRows() }
            }
        } ?: TvDialog.Btn(getString(R.string.player_bgrec_menu)) { bufRecMenu(null, c, ev) },
    )

    /** Lista bieżących nagrań (oglądany kanał i bufory w tle): stacja + tytuł; OK zatrzymuje od razu (bez pytania). */
    private fun showStopRecs(sel: Int = 0) {
        val recs = ArrayList<Triple<String, String, () -> Unit>>()
        localRec?.let { r ->
            recs += Triple(r.meta.optString("service").ifBlank { r.ch.name }, r.meta.optString("title"),
                { if (localRec === r) finishLocalRec("zatrzymane", restartLive = true) })
        }
        for (bg in bgChans.toList()) bg.lrec?.let { r ->
            recs += Triple(bg.name, r.meta.optString("title"), {
                if (bg.lrec === r) { bg.lrec = null; finishRec(r, "zatrzymane", restartLive = false, main = false); refreshPanelRows() }
            })
        }
        if (recs.isEmpty()) return
        val items = recs.map { TvDialog.Item(it.first, it.second) } +
            (if (recs.size > 1) listOf(TvDialog.Item(getString(R.string.player_rec_stop_all))) else emptyList())
        TvDialog.list(this, getString(R.string.player_rec_stop_title), items, sel.coerceIn(0, items.size - 1), widthFrac = 0.55f) { i ->
            if (i < recs.size) recs[i].third() else recs.asReversed().forEach { it.third() }
            // lista zostaje z pozostałymi nagraniami; pusta — zamyka się sama
            whenNoDialog { if (activeRecs() > 0) showStopRecs(minOf(i, activeRecs() - 1)) }
        }
    }

    /** Zakończenie bufora w tle; przy nagraniu audycji — pytanie (bufor chroniony do końca audycji). */
    private fun bgCloseAsk(bg: BgChan) {
        val r = bg.lrec
        if (r != null && bgProtected(bg)) {
            TvDialog.confirm(this, bg.name, getString(R.string.player_bg_close_rec, r.meta.optString("title")), listOf(
                TvDialog.Btn(getString(R.string.player_bg_close)) { bgDrop(bg, "zakończony ręcznie (z nagraniem)"); refreshPanelRows() },
                TvDialog.Btn(getString(R.string.dialog_cancel))))
            return
        }
        bgDrop(bg, "zakończony ręcznie"); refreshPanelRows()
    }

    /**
     * Zapis bufora na dysk: „Audycja: tytuł” / „Bufor do teraz” / „Do usunięcia bufora”.
     * [bg] null — bufor oglądanego kanału. [ev] — bieżąca audycja (null — z EPG / dziennika kanału).
     */
    private fun bufRecMenu(bg: BgChan?, ch: Channel, ev: EpgEvent?, fetched: Boolean = false) {
        if (bg != null && bg !in bgChans) return
        if (ev == null && !fetched) {
            io.execute {
                val e = currentEventOf(bg, ch)
                runOnUiThread { bufRecMenu(bg, ch, e, fetched = true) }
            }
            return
        }
        val span = (bg?.buf ?: tsBuf)?.spanMs() ?: 0
        val acts = ArrayList<Pair<String, Int>>()
        if (ev != null) acts += getString(R.string.player_bgrec_event, ev.title) to REC_EVENT
        acts += getString(R.string.player_bgrec_now, fmtMs(span)) to REC_NOW
        acts += getString(R.string.player_bgrec_until) to REC_UNTIL_DROP
        TvDialog.list(this, getString(R.string.player_bgrec_title, bg?.name ?: ch.name), acts.map { TvDialog.Item(it.first) }, 0, widthFrac = 0.5f) { i ->
            bgRecStart(bg, acts[i].second, ch, ev)
        }
    }

    /** Bieżąca audycja: zapisane EPG, a gdy go brak — dziennik z EIT strumienia (kanału w tle albo oglądanego). */
    private fun currentEventOf(bg: BgChan?, ch: Channel): EpgEvent? {
        epg.localNow(ch.ref)?.let { return it }
        val n = System.currentTimeMillis() / 1000
        return (bg?.journal ?: journal).snapshot().lastOrNull { it.begin <= n && it.end > n }?.let {
            EpgEvent(0, it.begin, it.end - it.begin, it.title, it.desc, "", ch.ref, ch.name)
        }
    }

    private fun refreshPanelRows() {
        if (panel.visibility != View.VISIBLE) return
        if (panelHist) {
            val selectedRef = panelList.getOrNull(list.selectedItemPosition)?.ref
            panelList = historyChannels()
            val selected = selectedRef?.let { ref ->
                panelList.indexOfFirst { OpenWebif.key(it.ref) == OpenWebif.key(ref) }
            } ?: -1
            refreshPanel(selected.coerceAtLeast(0))
        } else {
            adapter.notifyDataSetChanged()
        }
    }

    /** Menu → Buforowane kanały: oglądany kanał (gdy gra przez bufor) i kanały w tle; zakończenie bufora i zapis na dysk. */
    /** Menu po zmianie wraca, gdy nie ma już innego okna (np. potwierdzenia); wyjście tylko WSTECZ. */
    private fun whenNoDialog(tries: Int = 40, block: () -> Unit) {
        ui.postDelayed({
            when {
                isFinishing -> {}
                TvDialog.top() == null -> block()
                tries > 0 -> whenNoDialog(tries - 1, block)
            }
        }, 300)
    }

    private fun showBufferedMenu(sel: Int = 0) {
        val cur = playList.getOrNull(playIndex)
        val main = if (bufLive && tsBuf != null && cur != null && !recMode) cur else null
        if (main == null && bgChans.isEmpty()) { showMessage(getString(R.string.action_no_buffered)); return }
        val chans: List<BgChan?> = (if (main != null) listOf<BgChan?>(null) else emptyList()) + bgChans.toList()
        TvDialog.list(this, getString(R.string.player_bg_menu), chans.map { c ->
            if (c == null) TvDialog.Item((if (localRec != null) "● " else "") + "▶  " + main!!.name,
                getString(R.string.player_bg_span_main, fmtMs(tsBuf?.spanMs() ?: 0)))
            else TvDialog.Item((if (c.lrec != null) "● " else "") + c.name, getString(R.string.player_bg_span, fmtMs(c.buf.spanMs())))
        }, sel.coerceIn(0, chans.size - 1), widthFrac = 0.45f) { i ->
            val bg = chans[i]
            if (bg != null && bg !in bgChans) return@list
            val ch = if (bg == null) main!! else bg.ch ?: Channel(0, bg.name, "")
            io.execute {
                val ev = currentEventOf(bg, ch)
                runOnUiThread { bufferedActions(bg, ch, ev, i) }
            }
        }
    }

    private fun bufferedActions(bg: BgChan?, ch: Channel, ev: EpgEvent?, listSel: Int = 0, sel: Int = 0) {
        if (bg != null && bg !in bgChans) { showBufferedMenu(listSel); return }
        val acts = ArrayList<Pair<String, () -> Unit>>()
        if (bg != null) acts += getString(R.string.player_bg_close) to { bgCloseAsk(bg) }
        val r = if (bg == null) localRec else bg.lrec
        if (r != null) acts += getString(R.string.player_bgrec_stop) to {
            if (bg == null) { if (localRec === r) finishLocalRec("zatrzymane", restartLive = true) }
            else if (bg.lrec === r) { bg.lrec = null; finishRec(r, "zatrzymane", restartLive = false, main = false); refreshPanelRows() }
        } else {
            if (ev != null) acts += getString(R.string.player_bgrec_event, ev.title) to { bgRecStart(bg, REC_EVENT, ch, ev) }
            acts += getString(R.string.player_bgrec_now, fmtMs((bg?.buf ?: tsBuf)?.spanMs() ?: 0)) to { bgRecStart(bg, REC_NOW, ch, ev) }
            acts += getString(R.string.player_bgrec_until) to { bgRecStart(bg, REC_UNTIL_DROP, ch, ev) }
        }
        // po akcji okno wraca (kanał zamknięty — lista buforowanych); WSTECZ — do listy, drugi WSTECZ zamyka
        TvDialog.list(this, bg?.name ?: ch.name, acts.map { TvDialog.Item(it.first) }, sel.coerceIn(0, acts.size - 1), widthFrac = 0.5f,
            onCancel = { whenNoDialog { showBufferedMenu(listSel) } }) { j ->
            acts[j].second()
            whenNoDialog {
                if (bg != null && bg !in bgChans) {
                    if (bgChans.isNotEmpty() || (bufLive && tsBuf != null && !recMode)) showBufferedMenu(listSel)
                } else io.execute {
                    val e = currentEventOf(bg, ch)
                    runOnUiThread { bufferedActions(bg, ch, e, listSel, j) }
                }
            }
        }
    }

    /** Zgoda na pamięć urządzenia, limit miejsca — jak przy nagraniu bieżącego kanału — i start zapisu z bufora w tle. */
    private fun bgRecStart(bg: BgChan?, mode: Int, ch: Channel, ev: EpgEvent?) {
        if (bg != null && bg !in bgChans) return
        val b = bg?.buf ?: tsBuf ?: return
        b.failed?.let { showMessage(getString(R.string.player_ts_error, it)); return }
        val base = LocalRecordings.base(b.root)
        val internal = b.root.absolutePath.startsWith(TimeshiftBuffer.internalBase(this).absolutePath)
        if (internal && !prefs.recInternalOk) {
            TvDialog.confirm(this, getString(R.string.player_localrec_title), getString(R.string.player_localrec_internal_consent), listOf(
                TvDialog.Btn(getString(R.string.player_localrec_allow)) { prefs.recInternalOk = true; bgRecStart(bg, mode, ch, ev) },
                TvDialog.Btn(getString(R.string.dialog_cancel)),
            ))
            return
        }
        io.execute {
            val used = LocalRecordings.used(base)
            val cap = LocalRecordings.capacity(base)
            runOnUiThread {
                if (cap > 0 && used >= cap * prefs.recMaxPct / 100) {
                    TvDialog.confirm(this, getString(R.string.player_localrec_title),
                        getString(R.string.player_localrec_limit_reached, prefs.recMaxPct, TimeshiftBuffer.gb(used), TimeshiftBuffer.gb(cap)),
                        listOf(TvDialog.Btn(getString(R.string.player_local_recordings)) { showRecordings(REC_LOCAL_BOUQUET) },
                            TvDialog.Btn(getString(R.string.message_close))))
                    return@runOnUiThread
                }
                // bufor oglądanego kanału: nagranie bieżącego kanału (kanał musi być tym z listy odtwarzania)
                if (bg == null) playList.getOrNull(playIndex)?.let { startLocalRec(mode, it, ev, used, cap) }
                else startLocalRec(mode, ch, ev, used, cap, bg)
            }
        }
    }

    /** Pilnowanie trwającego nagrania: koniec audycji, limit czasu, limit miejsca, błąd bufora. */
    private val localRecTick = object : Runnable {
        override fun run() {
            val r = localRec ?: return
            val b = tsBuf
            val now = SystemClock.elapsedRealtime()
            when {
                b == null || b.pin !== r.pin -> finishLocalRec("bufor zamknięty", restartLive = false)
                b.failed != null -> finishLocalRec("błąd bufora", restartLive = true)
                now >= r.untilWall -> finishLocalRec("koniec audycji", restartLive = true)
                now - r.pin.fromWall >= prefs.recMaxHours * 3_600_000L -> finishLocalRec("limit czasu", restartLive = true)
                r.capacity > 0 && r.usedBefore + b.pinnedBytes() >= r.capacity * prefs.recMaxPct / 100 -> finishLocalRec("limit miejsca", restartLive = true)
                b.shrunk -> finishLocalRec("brak miejsca", restartLive = true)
                else -> ui.postDelayed(this, 5000)
            }
        }
    }

    /**
     * Diagnostyka: przegląd zegara PCR całego nagrania w tle (niski priorytet), wynik do logu i do opisu nagrania
     * (pole "scan" — przy następnym odtwarzaniu bez ponownego czytania plików).
     */
    private val SCAN_V = 4

    private fun scanRecording(dir: File, title: String) {
        Thread({
            try {
                val parts = LocalRecordings.parts(dir)
                if (parts.isEmpty()) return@Thread
                val t0 = SystemClock.elapsedRealtime()
                val res = RecScan.scan(parts)
                val meta = LocalRecordings.readMeta(dir) ?: org.json.JSONObject()
                val d = RecScan.describe(res, meta.optLong("lengthSec"))
                AppLog.i("nagranie: skan zegara „$title” (${SystemClock.elapsedRealtime() - t0} ms): $d")
                meta.put("scan", d).put("scanV", SCAN_V)
                LocalRecordings.writeMeta(dir, meta)
            } catch (e: Exception) {
                AppLog.i("nagranie: skan zegara nieudany ($e)")
            }
        }, "skan-nagrania").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** Znacznik nagrywania przy zegarze paska. */
    private fun recMark(clock: String) = if (localRec != null) "● REC   $clock" else clock

    // ---------------------------------------------------------------- nagrania z dekodera

    private val REC_REF = REC_BOUQUET
    private var recDir = ""                       // bieżący katalog (puste = domyślny dekodera)
    private val recStack = ArrayList<String>()    // katalogi nadrzędne (powrót „‹ wstecz”)
    private var recMode = false
    private var recItem: Recording? = null
    /** Audycje odtwarzanego nagrania lokalnego (długie OK — lista skoków). */
    private var recChapters: List<Chapter> = emptyList()
    private var recBannerUntil = 0L
    private var panelOnRec = false                // po powrocie z nagrania lista otwiera się na nagraniach
    private var recLastPath = ""
    private val recPos by lazy { getSharedPreferences("recpos", MODE_PRIVATE) }
    private val dateFmt by lazy { SimpleDateFormat("EEE dd.MM  HH:mm", Locale.forLanguageTag(AppLanguage.selected(this))) }

    /** Bukiet „Nagrania” zawsze na końcu listy bukietów. */
    private fun withRec(bq: List<Bouquet>) = bq.filter { !isVirtual(it.ref) } +
        (if (REC_REF in prefs.hiddenBouquets) emptyList() else listOf(Bouquet(getString(R.string.player_recordings), REC_REF))) +
        (if (REC_LOCAL_BOUQUET in prefs.hiddenBouquets || prefs.tsMin <= 0) emptyList()
            else listOf(Bouquet(getString(R.string.player_local_recordings), REC_LOCAL_BOUQUET))) +
        // historia na końcu — ◀ z pierwszego bukietu przechodzi od razu na nią
        (if (HIST_BOUQUET in prefs.hiddenBouquets) emptyList() else listOf(Bouquet(getString(R.string.player_history), HIST_BOUQUET)))

    private fun historyChannels(): List<Channel> = prefs.history.mapIndexed { i, (ref, name) -> Channel(i + 1, name, ref) }

    /** Kanały bukietu albo (dla „Nagrania”) zawartość bieżącego katalogu nagrań. Poza wątkiem UI. */
    private fun channelsOf(bq: Bouquet): List<Channel> {
        if (bq.ref == HIST_BOUQUET) return historyChannels()
        if (bq.ref == REC_LOCAL_BOUQUET) {
            val base = localBase() ?: return emptyList()
            val recording = localRec?.pin?.dir?.absolutePath
            return LocalRecordings.list(base).mapIndexed { i, r ->
                val live = recording != null && r.path == LocalRecordings.PREFIX + recording
                Channel(i + 1, (if (live) "● " else "") + r.title, r.path).also { it.rec = r }
            }
        }
        if (bq.ref != REC_REF) return api.channels(bq)
        val (cur, items) = api.movies(recDir)
        if (recDir.isBlank()) recDir = cur
        val out = ArrayList<Channel>()
        if (recStack.isNotEmpty()) out += Channel(0, "‹ wstecz", "").also {
            it.rec = Recording("..", "‹ wstecz", "", "", "", 0, 0, 0, true)
        }
        // katalogi ukryte (.Trash itp.) pomijane
        items.filter { !(it.isDir && it.title.startsWith(".")) }
            .forEachIndexed { i, r -> out += Channel(if (r.isDir) 0 else i + 1, r.title, r.sref.ifBlank { r.path }).also { it.rec = r } }
        return out
    }

    /** Ostatnio używany bukiet nagrań (z dekodera albo lokalne). */
    private var recBqRef = REC_REF

    /** Lista kanałów otwarta na bukiecie „Nagrania” (albo „Nagrania lokalne”). */
    private fun showRecordings(ref: String = recBqRef) {
        recBqRef = ref
        panelHist = false
        val i = bouquets.indexOfFirst { it.ref == ref }
        if (i < 0) { panelOnRec = false; showPanel(); return }      // bukiet „Nagrania” ukryty
        panel.visibility = View.VISIBLE
        resetPanelTimer()
        banner.visibility = View.GONE
        list.requestFocus()
        panelBouquet = i - 1
        panelBouquetStep(1)
    }

    private fun recPosKey(r: Recording) = r.path

    private fun savedPos(r: Recording): Long = recPos.getLong(recPosKey(r), 0)

    private fun saveRecPos() {
        val r = recItem ?: return
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (dur > 0 && pos > dur - 60_000) recPos.edit().remove(recPosKey(r)).apply()      // obejrzane do końca
        else if (pos > 30_000) recPos.edit().putLong(recPosKey(r), pos).apply()
    }

    private fun pickRecording(r: Recording, onCancel: (() -> Unit)? = null) {
        if (r.isDir) {
            if (r.path == "..") {
                recDir = if (recStack.isNotEmpty()) recStack.removeAt(recStack.size - 1) else ""
            } else {
                recStack += recDir
                recDir = r.path
            }
            panelBouquet -= 1
            panelBouquetStep(1)
            return
        }
        val saved = savedPos(r)
        if (saved > 60_000) {
            TvDialog.list(this, r.title, listOf(
                TvDialog.Item(getString(R.string.player_resume_recording, fmtMs(saved))),
                TvDialog.Item(getString(R.string.player_from_beginning)),
            ), 0, widthFrac = 0.45f, onCancel = { onCancel?.invoke() }) { w ->
                playRecording(r, if (w == 0) saved else 0)
            }
        } else playRecording(r, 0)
    }

    private fun recordingDescription(r: Recording) =
        listOf(r.service, if (r.begin > 0) dateFmt.format(Date(r.begin * 1000)) else "", if (r.lengthSec > 0) "${r.lengthSec / 60} min" else "")
            .filter { it.isNotBlank() }.joinToString("  •  ") + "\n\n" + r.desc.ifBlank { getString(R.string.epg_no_description) }

    private fun showRecordingDetails(r: Recording, onBack: (() -> Unit)? = null) {
        TvDialog.confirm(this, r.title, recordingDescription(r),
            listOf(TvDialog.Btn(getString(R.string.message_play)) {
                pickRecording(r, onCancel = onBack?.let { returnToSchedule ->
                    { showRecordingDetails(r, returnToSchedule) }
                })
            }),
            onBack = onBack, timeoutSec = prefs.actionDialogSec, onTimeout = onBack)
    }

    private fun showRecordingActions(r: Recording) {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts += getString(R.string.message_play) to { pickRecording(r) }
        if (LocalRecordings.isLocal(r.path)) {
            if (!LocalRecordings.isSingleFile(LocalRecordings.dirOf(r.path)))
                acts += getString(R.string.player_localrec_convert) to { exportLocalRecording(r, convert = true) }
            acts += getString(R.string.player_localrec_export) to { exportLocalRecording(r, convert = false) }
        }
        acts += getString(R.string.message_delete) to { confirmDeleteRecording(r) }
        TvDialog.list(this, r.title, acts.map { TvDialog.Item(it.first) },
            hint = getString(R.string.epg_hint_actions), widthFrac = 0.45f) { selected -> acts[selected].second() }
    }

    // --- eksport nagrania lokalnego do jednego pliku .ts (na żądanie, w tle)
    private var exportCancel: java.util.concurrent.atomic.AtomicBoolean? = null
    private var exportDialog: android.app.Dialog? = null
    private var exportTitle = ""
    private var exportConvert = false

    /** Eksport kopii do jednego pliku (obok nagrań) albo konwersja na jeden plik w miejscu ([convert], bez drugiej kopii). */
    private fun exportLocalRecording(r: Recording, convert: Boolean) {
        val dir = LocalRecordings.dirOf(r.path)
        if (exportCancel != null) {             // trwa inny eksport — pokaż jego postęp
            showExportDialog(getString(R.string.player_localrec_export_busy, exportTitle)); return
        }
        if (localRec?.pin?.dir == dir) { showMessage(getString(R.string.player_localrec_export_running)); return }
        if (convert && recMode && recItem?.path == r.path) { showMessage(getString(R.string.player_localrec_convert_playing)); return }
        val target = if (convert) File(dir, LocalRecordings.exportFile(dir).name) else LocalRecordings.exportFile(dir)
        fun start() {
            // konwersja: zapas na jeden kawałek; eksport: cała kopia
            val size = if (convert) LocalRecordings.parts(dir).maxOfOrNull { it.length() } ?: 0L else LocalRecordings.size(dir)
            val free = dir.usableSpace
            if (size + TimeshiftBuffer.RESERVE > free) {
                TvDialog.confirm(this, getString(R.string.player_localrec_export), getString(R.string.player_localrec_export_no_space,
                    TimeshiftBuffer.gb(size), TimeshiftBuffer.gb(free)), listOf(TvDialog.Btn("OK")))
                return
            }
            val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
            exportCancel = cancel
            exportTitle = r.title
            exportConvert = convert
            AppLog.i("nagranie lokalne: ${if (convert) "konwersja" else "eksport"} „${r.title}” → ${target.absolutePath} (${TimeshiftBuffer.gb(LocalRecordings.size(dir))})")
            showExportDialog(getString(R.string.player_localrec_export_start, target.name))
            Thread({
                val progress: (Long, Long, Long) -> Unit = { done, total, eta -> runOnUiThread { exportProgress(done, total, eta) } }
                val result = try {
                    val ok = if (convert) LocalRecordings.convert(dir, cancel, progress) else LocalRecordings.export(dir, target, cancel, progress)
                    if (ok) "ok" else "cancel"
                } catch (e: Exception) { e.message ?: e.toString() }
                runOnUiThread {
                    exportCancel = null
                    val d = exportDialog
                    exportDialog = null
                    d?.dismiss()
                    when (result) {
                        "ok" -> {
                            AppLog.i("nagranie lokalne: ${if (convert) "konwersja" else "eksport"} gotowy ${target.absolutePath}")
                            TvDialog.confirm(this, getString(R.string.player_localrec_export_done_title),
                                getString(if (convert) R.string.player_localrec_convert_done else R.string.player_localrec_export_done, target.absolutePath),
                                listOf(TvDialog.Btn("OK")), timeoutSec = 0)
                            if (convert) reloadRecordings()
                        }
                        "cancel" -> {
                            AppLog.i("nagranie lokalne: ${if (convert) "konwersja przerwana" else "eksport anulowany"}")
                            showMessage(getString(if (convert) R.string.player_localrec_convert_cancelled else R.string.player_localrec_export_cancelled))
                            if (convert) reloadRecordings()
                        }
                        else -> {
                            AppLog.i("nagranie lokalne: eksport nieudany ($result)")
                            TvDialog.confirm(this, getString(R.string.player_localrec_export), getString(R.string.player_localrec_export_failed, result),
                                listOf(TvDialog.Btn("OK")), timeoutSec = 0)
                        }
                    }
                }
            }, "eksport-nagrania").start()
        }
        if (target.exists()) {
            TvDialog.confirm(this, getString(R.string.player_localrec_export), getString(R.string.player_localrec_export_exists, target.name), listOf(
                TvDialog.Btn(getString(R.string.player_localrec_export_overwrite)) { start() },
                TvDialog.Btn(getString(R.string.dialog_cancel)),
            ))
        } else start()
    }

    private fun showExportDialog(message: String) {
        exportDialog?.dismiss()
        exportDialog = TvDialog.progress(this,
            getString(if (exportConvert) R.string.player_localrec_convert_progress_title else R.string.player_localrec_export_progress_title, exportTitle), message,
            getString(R.string.player_localrec_export_hide), getString(R.string.dialog_cancel),
            onHide = { exportDialog?.dismiss(); exportDialog = null; showMessage(getString(R.string.player_localrec_export_background)) },
            onCancel = { exportCancel?.set(true) })
    }

    private fun exportProgress(done: Long, total: Long, eta: Long) {
        val d = exportDialog ?: return
        val pct = if (total > 0) (done * 1000 / total).toInt() else 0
        TvDialog.setProgress(d, pct)
        val left = if (eta >= 0) getString(R.string.player_localrec_export_eta, fmtMs(eta * 1000)) else ""
        TvDialog.setMessage(d, getString(R.string.player_localrec_export_status, pct / 10, TimeshiftBuffer.gb(done), TimeshiftBuffer.gb(total), left))
    }

    private fun confirmDeleteRecording(r: Recording) {
        TvDialog.confirm(this, getString(R.string.message_delete_recording), "${r.title}\n${r.path}",
            listOf(TvDialog.Btn(getString(R.string.message_delete)) { deleteRecording(r) }))
    }

    private fun deleteRecording(r: Recording) {
        if (LocalRecordings.isLocal(r.path) && localRec?.pin?.dir == LocalRecordings.dirOf(r.path)) {
            showMessage(getString(R.string.player_localrec_cannot_delete_running)); return
        }
        io.execute {
            val result = if (LocalRecordings.isLocal(r.path)) {
                if (LocalRecordings.delete(LocalRecordings.dirOf(r.path))) true to "" else false to getString(R.string.player_localrec_delete_failed)
            } else try { api.movieDelete(r.sref) } catch (e: Exception) { false to (e.message ?: e.toString()) }
            runOnUiThread {
                if (result.first) {
                    recPos.edit().remove(recPosKey(r)).apply()
                    TvDialog.info(this@PlayerActivity, getString(R.string.player_recording_removed), r.title) {
                        reloadRecordings()
                    }
                } else TvDialog.info(this@PlayerActivity, getString(R.string.player_recording_remove_failed), result.second)
            }
        }
    }

    private fun reloadRecordings() {
        val bqIndex = panelBouquet
        if (!isRecRef(bouquets.getOrNull(bqIndex)?.ref)) return
        io.execute {
            val channels = try { channelsOf(bouquets[bqIndex]) } catch (e: Exception) {
                runOnUiThread { showMessage(getString(R.string.player_recording_error, e.message ?: e.toString())) }
                return@execute
            }
            runOnUiThread {
                if (panelBouquet == bqIndex && panel.visibility == View.VISIBLE) {
                    panelList = channels
                    val selected = channels.indexOfFirst { it.rec?.path == recLastPath }.coerceAtLeast(0)
                    refreshPanel(selected)
                }
            }
        }
    }

    private fun playRecording(r: Recording, startMs: Long) {
        if (recMode) saveRecPos()
        else { bgPark(); tsStopAll(keepBg = true) }
        hidePanel()
        recMode = true
        recItem = r
        panelOnRec = true
        recLastPath = r.path
        failStreak = 0
        ui.removeCallbacks(restartRunnable)
        restartPending = false
        recBqRef = if (LocalRecordings.isLocal(r.path)) REC_LOCAL_BOUQUET else REC_REF
        recSeekWarned = false
        if (LocalRecordings.isLocal(r.path)) {
            val dir = LocalRecordings.dirOf(r.path)
            val m = LocalRecordings.readMeta(dir)
            val done = m?.optString("scan").orEmpty()
            if (done.isEmpty() || m?.optInt("scanV") != SCAN_V) scanRecording(dir, r.title) else AppLog.i("nagranie: skan zegara (zapisany): $done")
        }
        recChapters = if (LocalRecordings.isLocal(r.path))
            LocalRecordings.readMeta(LocalRecordings.dirOf(r.path))?.let { LocalRecordings.chapters(it) } ?: emptyList()
        else emptyList()
        val url = if (LocalRecordings.isLocal(r.path)) r.path else api.fileUrl(r.path)
        AppLog.i("nagranie: ${r.title} (${r.service}) $url od ${fmtMs(startMs)}")
        startStream(url, startMs)
        showRecBanner()
        ui.removeCallbacks(recTick)
        ui.postDelayed(recTick, 500)
    }

    /** Koniec nagrania albo wyjście: zapamiętanie miejsca. */
    private fun leaveRec() {
        if (!recMode) return
        ui.removeCallbacks(recApply); recPending = -1; pvCancel()
        saveRecPos()
        recMode = false
        recItem = null
        ui.removeCallbacks(recTick)
        player?.playWhenReady = true
    }

    /**
     * Wyjście z nagrania: zatrzymanie (czarny ekran, bez wznawiania kanału TV) i lista nagrań.
     * Obraz wraca po wyborze innego nagrania albo kanału z listy.
     */
    private fun backToLive() {
        leaveRec()
        stopPlayback()
        showRecordings()
    }

    /** Zatrzymanie odtwarzania bez zwalniania odtwarzacza (czarny ekran do czasu wyboru). */
    private fun stopPlayback() {
        currentUrl = null
        ui.removeCallbacks(restartRunnable)
        restartPending = false
        watchdog?.paused = true
        ui.removeCallbacks(vlcTick)
        proxy?.stop(); proxy = null
        vlc?.release(); vlc = null
        player?.stop()
        player?.clearMediaItems()
        banner.visibility = View.GONE
    }

    private fun recEnded() {
        ui.removeCallbacks(recApply); recPending = -1; pvCancel()
        recItem?.let { recPos.edit().remove(recPosKey(it)).apply() }
        AppLog.i("nagranie: koniec")
        recMode = false
        recItem = null
        showMessage(getString(R.string.message_end_of_recording))
        stopPlayback()
        showRecordings()
    }

    private var recSeekWarned = false

    private fun recSeek(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        if (readyLogged && !p.isCurrentMediaItemSeekable && !recSeekWarned) {
            recSeekWarned = true
            AppLog.i("nagranie: odtwarzacz nie pozwala przewijać (długość ${if (p.duration > 0) fmtMs(p.duration) else "nieznana"}, pozycja ${fmtMs(p.currentPosition)})")
        }
        val src = recByteSrc()
        val known = recItem?.let { r -> p.duration.takeIf { it > 0 } ?: (r.lengthSec * 1000).takeIf { it > 0 } } ?: 0L
        if (src == null || known <= 0 || !pvStart()) {
            val base = if (recPending >= 0) recPending else p.currentPosition
            val target = (base + deltaMs).coerceIn(0, maxOf(0, (if (dur < Long.MAX_VALUE) dur else known.takeIf { it > 0 } ?: dur) - 1000))
            if (!p.isCurrentMediaItemSeekable && p.playbackState == Player.STATE_BUFFERING &&
                SystemClock.elapsedRealtime() - streamStartAt < 10_000) {
                // ExoPlayer jeszcze mierzy długość — seek teraz przerwałby pomiar; wykonujemy go po jego zakończeniu
                recPending = target
                showRecBanner()
                ui.removeCallbacks(recApply)
                ui.postDelayed(recApply, 200)
                return
            }
            p.seekTo(target)
            showRecBanner()
            return
        }
        // przewijanie zbierane z kilku naciśnięć; podgląd klatki, odtwarzanie od miejsca po 0,7 s bez klawiszy
        val base = if (recPending >= 0) recPending else p.currentPosition
        recPending = (base + deltaMs).coerceIn(0, maxOf(0, known - 1000))
        pvLastKey = SystemClock.elapsedRealtime()
        seekPreview.requestRecording(src, recPending, known)
        showRecBanner(sticky = !pvWasPlaying)
        ui.removeCallbacks(recApply)
        ui.postDelayed(recApply, 700)
    }

    private var recPending = -1L
    private val recApply: Runnable = Runnable {
        if (pvWaitFrame { ui.postDelayed(recApply, 150) }) return@Runnable
        val p = player
        if (p != null && recMode && !p.isCurrentMediaItemSeekable && p.playbackState == Player.STATE_BUFFERING &&
            SystemClock.elapsedRealtime() - streamStartAt < 10_000) {
            ui.postDelayed(recApply, 150)
            return@Runnable
        }
        val t = recPending
        recPending = -1
        if (p != null && recMode && t >= 0) {
            p.seekTo(t)
            p.playWhenReady = pvPlaying()
            pvFinish()
            showRecBanner(sticky = !p.playWhenReady)
        } else pvCancel()
    }

    // źródło bajtów odtwarzanego nagrania (podgląd przewijania): pliki lokalne albo plik na dekoderze (HTTP Range)
    private var recSrc: ByteSrc? = null
    private var recSrcPath = ""

    private fun recByteSrc(): ByteSrc? {
        val r = recItem ?: return null
        if (recSrcPath != r.path || recSrc == null) {
            recSrcPath = r.path
            recSrc = if (LocalRecordings.isLocal(r.path)) LocalRecordings.parts(LocalRecordings.dirOf(r.path)).takeIf { it.isNotEmpty() }?.let { PartsSrc(it) }
                else HttpSrc(api.fileUrl(r.path), api.authHeader())
        }
        return recSrc
    }

    private fun recTogglePause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        if (!p.playWhenReady) saveRecPos()
        showRecBanner(sticky = !p.playWhenReady)
    }

    private fun chapterAt(posMs: Long): Int {
        val sec = posMs / 1000
        val i = recChapters.indexOfLast { it.start <= sec }
        return i.coerceAtLeast(0)
    }

    /** Długie OK w nagraniu z kilkoma audycjami: lista skoków do początku audycji (+ zwykłe menu na końcu). */
    /** Długie OK podczas odtwarzania nagrania lokalnego: od początku, audycje, konwersja, eksport, usunięcie. */
    private fun showRecMenu(): Boolean {
        val r = recItem ?: return false
        if (!LocalRecordings.isLocal(r.path)) return showChapters()
        val dir = LocalRecordings.dirOf(r.path)
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts += getString(R.string.player_from_beginning) to {
            player?.let { pl -> pl.seekTo(0); AppLog.i("nagranie: od początku"); showRecBanner(sticky = !pl.playWhenReady) }
            Unit
        }
        if (recChapters.size >= 2) acts += getString(R.string.player_rec_chapters_title) to { showChapters(); Unit }
        // konwersja i usunięcie dotyczą odtwarzanego pliku — najpierw wracamy do TV
        if (!LocalRecordings.isSingleFile(dir))
            acts += getString(R.string.player_localrec_convert) to { backToLive(); exportLocalRecording(r, convert = true) }
        acts += getString(R.string.player_localrec_export) to { exportLocalRecording(r, convert = false) }
        acts += getString(R.string.message_delete) to {
            TvDialog.confirm(this, getString(R.string.message_delete_recording), "${r.title}\n${r.path}",
                listOf(TvDialog.Btn(getString(R.string.message_delete)) { backToLive(); deleteRecording(r) }))
            Unit
        }
        TvDialog.list(this, r.title, acts.map { TvDialog.Item(it.first) }, 0, widthFrac = 0.45f) { i -> acts[i].second() }
        return true
    }

    private fun showChapters(): Boolean {
        if (recChapters.size < 2) return false
        val p = player ?: return false
        val cur = chapterAt(p.currentPosition)
        val items = recChapters.mapIndexed { i, c ->
            val sub = c.desc.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
            TvDialog.Item("${ProgramJournal.hms(c.start)}   ${c.title}", sub, mark = if (i == cur) 3 else 0)
        }
        TvDialog.list(this, getString(R.string.player_rec_chapters_title), items, cur, widthFrac = 0.6f) { i ->
            val pl = player ?: return@list
            val dur = pl.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            pl.seekTo((recChapters[i].start * 1000).coerceIn(0, maxOf(0, dur - 1000)))
            AppLog.i("nagranie: skok do audycji „${recChapters[i].title}” ${ProgramJournal.hms(recChapters[i].start)}")
            showRecBanner(sticky = !pl.playWhenReady)
        }
        return true
    }

    /** Klawisze podczas odtwarzania nagrania. */
    private fun recKey(code: Int, e: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN -> recSeek(-seekStepMs(code, e.repeatCount))
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> recSeek(seekStepMs(code, e.repeatCount))
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> recSeek(60_000)
            KeyEvent.KEYCODE_MEDIA_REWIND -> recSeek(-60_000)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> recTogglePause()
            KeyEvent.KEYCODE_MEDIA_STOP -> backToLive()
            KeyEvent.KEYCODE_BACK -> {
                if (banner.visibility == View.VISIBLE && player?.playWhenReady == true) { banner.visibility = View.GONE; return true }
                backToLive()
            }
            else -> return false
        }
        return true
    }

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /** Pasek nagrania: tytuł, kanał, położenie / długość, pasek postępu, podpowiedzi klawiszy. */
    private fun showRecBanner(sticky: Boolean = false) {
        val r = recItem ?: return
        bannerName.text = r.title
        refreshBannerTop()
        bannerIcon.setImageDrawable(null)
        bannerIcon.visibility = View.GONE
        updateRecBanner()
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        recBannerUntil = if (sticky) Long.MAX_VALUE else SystemClock.elapsedRealtime() + prefs.bannerSec.coerceIn(3, 30) * 1000L
    }

    private fun updateRecBanner() {
        val r = recItem ?: return
        val p = player ?: return
        val pos = if (recPending >= 0) recPending else p.currentPosition
        val dur = p.duration.takeIf { it > 0 } ?: r.lengthSec * 1000
        val paused = !pvPlaying()
        val chapter = if (recChapters.size > 1) recChapters[chapterAt(pos)].title else ""
        bannerNow.text = (if (paused) "⏸  " else "▶  ") + listOf(chapter, r.service, if (r.begin > 0) dateFmt.format(Date(r.begin * 1000)) else "")
            .filter { it.isNotBlank() }.joinToString("  •  ")
        bannerStart.text = fmtMs(pos)
        bannerEnd.text = if (dur > 0) "${fmtMs(dur)}   (−${fmtMs(maxOf(0, dur - pos))})" else ""
        bannerProgress.progress = if (dur > 0) (pos * 1000 / dur).toInt().coerceIn(0, 1000) else 0
        bannerNext.text = getString(if (recChapters.size > 1) R.string.player_recording_controls_chapters else R.string.player_recording_controls,
            "${prefs.tsStepSec}/${prefs.tsStepSecLong} s", "${Prefs.stepMinLabel(prefs.tsStepUdSec)}/${prefs.tsStepUdLongMin} min")
    }

    /** Co 0,5 s w trybie nagrania: odświeżenie paska, ukrycie po czasie, zapis miejsca co 15 s. */
    private var recSaveN = 0
    private val recTick = object : Runnable {
        override fun run() {
            if (!recMode) return
            ui.postDelayed(this, 500)
            if (banner.visibility == View.VISIBLE) {
                updateRecBanner()
                if (SystemClock.elapsedRealtime() > recBannerUntil && player?.playWhenReady == true) banner.visibility = View.GONE
            }
            if (++recSaveN % 30 == 0) saveRecPos()
        }
    }

    /** Wiersz nagrania (albo katalogu) na liście. */
    private fun recRow(v: View, c: Channel, r: Recording, position: Int): View {
        val selected = position == list.selectedItemPosition && list.hasFocus()
        v.findViewById<TextView>(R.id.num).text = if (r.isDir) "" else c.num.toString()
        val iv = v.findViewById<android.widget.ImageView>(R.id.icon)
        iv.setImageDrawable(null)
        iv.visibility = View.GONE
        val name = v.findViewById<TextView>(R.id.name)
        val playing = recMode && recItem?.path == r.path
        name.text = when {
            r.isDir && r.path == ".." -> r.title
            r.isDir -> "▸  ${r.title}"
            playing -> "▶  ${r.title}"
            else -> r.title
        }
        name.setTextColor(if (!selected && (playing || r.isDir)) 0xFF4FC3F7.toInt() else 0xFFFFFFFF.toInt())
        val now = v.findViewById<TextView>(R.id.now)
        now.text = if (r.isDir) "" else listOf(r.service, if (r.lengthSec > 0) "${r.lengthSec / 60} min" else "")
            .filter { it.isNotBlank() }.joinToString("  •  ")
        now.setTextColor(if (selected) 0xFFE3F2FD.toInt() else 0xFFB0BEC5.toInt())
        val time = v.findViewById<TextView>(R.id.time)
        val bar = v.findViewById<android.widget.ProgressBar>(R.id.bar)
        if (!r.isDir && r.begin > 0) {
            time.text = dateFmt.format(Date(r.begin * 1000))
            time.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFB0BEC5.toInt())
            time.visibility = View.VISIBLE
        } else time.visibility = View.INVISIBLE
        val saved = savedPos(r)
        if (!r.isDir && saved > 0 && r.lengthSec > 0) {
            bar.progress = (saved * 1000 / (r.lengthSec * 1000)).toInt().coerceIn(0, 1000)      // obejrzana część
            bar.visibility = View.VISIBLE
        } else bar.visibility = View.INVISIBLE
        return v
    }

    // ---------------------------------------------------------------- nakładki

    private val hideBanner = Runnable { banner.visibility = View.GONE; tsChBar = false }

    private val hms = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * Górna linia paska: stan buforów (wariant B: „B 12:34 · tło 2/3 (nagr. 1)”, czerwone ● przy nagrywaniu
     * oglądanego kanału); pod logo bieżąca godzina hh:mm:ss. Odświeżane co sekundę, póki pasek widoczny.
     */
    private fun refreshBannerTop() {
        bannerTime.text = hms.format(Date())
        // wielkość tytułu audycji, a gdy się nie mieści pod logo — wielkość nazwy kanału
        val col = 150 * resources.displayMetrics.density
        bannerTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, bannerNow.textSize)
        if (bannerTime.paint.measureText("88:88:88") > col) bannerTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, bannerName.textSize)
        bannerClock.text = if (recMode) "" else bufStatus()
        ui.removeCallbacks(bannerTick)
        ui.postDelayed(bannerTick, 1000L - System.currentTimeMillis() % 1000L)
    }

    private val bannerTick = object : Runnable {
        override fun run() {
            if (banner.visibility != View.VISIBLE) return
            bannerTime.text = hms.format(Date())
            if (!recMode) bannerClock.text = bufStatus()
            ui.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    private fun spanText(ms: Long): String {
        val t = ms / 1000
        return if (t >= 3600) String.format(Locale.US, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
        else String.format(Locale.US, "%d:%02d", t / 60, t % 60)
    }

    private fun bufStatus(): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        val b = tsBuf?.takeIf { it.total > 0 }
        if (b != null) {
            if (localRec != null) {
                sb.append("● ")
                sb.setSpan(android.text.style.ForegroundColorSpan(0xFFE53935.toInt()), 0, 1, 0)
            }
            sb.append("B ").append(spanText(b.spanMs()))
        }
        if (prefs.bufPlay && prefs.bgCount > 0 && (bgChans.isNotEmpty() || b != null)) {
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(getString(R.string.player_bg_line, bgChans.size, prefs.bgCount))
            val rec = bgChans.count { it.lrec != null }
            if (rec > 0) sb.append(" ").append(getString(R.string.player_bg_line_rec, rec))
        }
        return sb
    }
    private val hideMessage = Runnable {
        message.visibility = View.GONE
        try { msgPopup?.dismiss() } catch (_: Exception) {}
        msgPopup = null
    }

    /** Komunikat nad otwartym menu (okno menu przykrywa pasek komunikatu odtwarzacza) — ten sam wygląd i miejsce. */
    private var msgPopup: android.widget.PopupWindow? = null

    private fun messageOverDialog(text: CharSequence) {
        try { msgPopup?.dismiss() } catch (_: Exception) {}
        msgPopup = null
        val d = TvDialog.top() ?: return
        val anchor = d.window?.decorView ?: return
        val tv = TextView(this).apply {
            background = message.background?.constantState?.newDrawable()
            setPadding(message.paddingLeft, message.paddingTop, message.paddingRight, message.paddingBottom)
            setTextColor(message.currentTextColor)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, message.textSize)
            gravity = android.view.Gravity.CENTER
            maxWidth = message.maxWidth
            this.text = text
        }
        val pw = android.widget.PopupWindow(tv, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        pw.isTouchable = false
        try {
            pw.showAtLocation(anchor, android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, 0,
                (message.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0)
            msgPopup = pw
        } catch (_: Exception) {}
    }

    private fun t(sec: Long) = hm.format(Date(sec * 1000))

    private fun progressOf(e: EpgEvent): Int {
        val n = System.currentTimeMillis() / 1000
        if (e.duration <= 0) return 0
        return (((n - e.begin) * 1000) / e.duration).toInt().coerceIn(0, 1000)
    }

    /** Dolna część listy kanałów: bieżąca audycja zaznaczonego kanału. */
    private fun showInfo(ch: Channel) {
        adapter.notifyDataSetChanged() // kolory podświetlonego wiersza
        infoDesc.text = ""
        infoTitle.text = ""
        UiScale.apply(infoBox, UiScale.pct(prefs.uiListFont))
        ch.rec?.let { r ->
            infoTitle.visibility = View.VISIBLE
            infoTitle.text = if (r.isDir) r.title else "${r.title}${if (r.service.isNotBlank()) "  •  " + r.service else ""}"
            infoDesc.text = if (r.isDir) getString(R.string.player_recordings_folder) else r.desc.ifBlank { getString(R.string.epg_no_description) }
            return
        }
        epg.nowNext(ch) { cur, _ ->
            if (panelList.getOrNull(list.selectedItemPosition) !== ch) return@nowNext
            infoTitle.text = cur?.let { "${t(it.begin)}–${t(it.end)}  ${it.title}" } ?: ""
            infoTitle.visibility = if (cur != null) View.VISIBLE else View.GONE
            infoDesc.text = when {
                cur == null -> getString(R.string.player_no_epg_description)
                cur.shortDesc.isNotBlank() && cur.longDesc.isNotBlank() && cur.longDesc != cur.shortDesc ->
                    cur.shortDesc + "\n" + cur.longDesc
                else -> cur.shortDesc.ifBlank { cur.longDesc }.ifBlank { getString(R.string.player_no_epg_description) }
            }
        }
    }

    private fun showBanner(ch: Channel) {
        tsChBar = true
        bannerName.text = "${ch.num}   ${ch.name}"
        refreshBannerTop()
        Picons.show(bannerIcon, this, ch.ref, prefs.picons, prefs.missingPiconLogo)
        bannerNow.text = ch.now
        bannerStart.text = ""
        bannerEnd.text = ""
        bannerNext.text = ""
        bannerProgress.progress = 0
        epg.nowNext(ch) { cur, next ->
            if (playList.getOrNull(playIndex) !== ch || !tsChBar || banner.visibility != View.VISIBLE) return@nowNext
            if (cur != null) {
                bannerNow.text = cur.title
                bannerStart.text = t(cur.begin)
                bannerEnd.text = "${t(cur.end)}   (−${(cur.end - System.currentTimeMillis() / 1000) / 60} min)"
                bannerProgress.progress = progressOf(cur)
            }
            bannerNext.text = next?.let { getString(R.string.player_next_event, t(it.begin), it.title) } ?: ""
        }
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, prefs.bannerSec.coerceIn(1, 30) * 1000L)
    }

    private fun showMessage(t: String) = showMessage(t, force = false)

    private fun showMessage(t: String, force: Boolean) {
        ui.removeCallbacks(hideMessage)
        if (!force && !prefs.messageBar) {
            message.visibility = View.GONE
            return
        }
        message.maxWidth = (resources.displayMetrics.widthPixels * 0.7f).toInt()
        message.text = prettyMessage(t)
        message.visibility = View.VISIBLE
        messageOverDialog(message.text)
        ui.postDelayed(hideMessage, prefs.messageBarSec * 1000L)
    }

    /**
     * Komunikat schludniej: zdarzenie w pierwszej linii (✓ / ✗), szczegóły mniejsze i przygaszone w drugiej —
     * zamiast pełnej ścieżki tylko nazwa pliku, nawiasy jako „ · ”. Pełne ścieżki zostają w logu.
     */
    private fun prettyMessage(t: String): CharSequence {
        val m = Regex("""(?<![\w.:/])/[A-Za-z][^\s()]*/[^\s()]+""").find(t) ?: return t
        val before = t.substring(0, m.range.first).trim().trimEnd(':').trim()
        val after = t.substring(m.range.last + 1)
        val path = m.value.trimEnd('/')
        val name = path.substringAfterLast('/')
        val extras = Regex("""\(([^)]*)\)""").findAll(after).map {
            it.groupValues[1].replace("przez wtyczkę EPG Scan", "przez EPG Scan").replace("via EPG Scan plugin", "via EPG Scan")
        }.filter { it.isNotBlank() }.toList()
        val rest = after.replace(Regex("""\([^)]*\)"""), "").trim().trim(',', '.', ':').trim()
        val title = when {
            before.isEmpty() -> name
            Regex("(?i)nie udało|błąd|error|failed").containsMatchIn(before) -> "✗ $before"
            Regex("(?i)wysłan|wysłano|zapisan|sent|saved").containsMatchIn(before) -> "✓ $before"
            else -> before
        }
        val detail = (listOf(if (before.isEmpty()) "" else name, rest) + extras).filter { it.isNotBlank() }.joinToString("  ·  ")
        if (detail.isEmpty()) return title
        return android.text.SpannableStringBuilder(title).apply {
            append("\n")
            val st = length
            append(detail)
            setSpan(android.text.style.RelativeSizeSpan(0.72f), st, length, 0)
            setSpan(android.text.style.ForegroundColorSpan(0xFFB0BEC5.toInt()), st, length, 0)
        }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (stats.visibility == View.VISIBLE) stats.text = statsText()
            ui.postDelayed(this, 1000)
        }
    }

    private fun toggleStats() {
        prefs.showStats = stats.visibility != View.VISIBLE
        if (stats.visibility == View.VISIBLE) {
            stats.visibility = View.GONE
        } else {
            stats.text = statsText()
            stats.visibility = View.VISIBLE
        }
    }

    private fun statsText(): String {
        val p = player ?: return ""
        val v = p.videoFormat
        val a = p.audioFormat
        val wd = watchdog
        val ch = playList.getOrNull(playIndex)
        return buildString {
            appendLine("${ch?.num ?: ""}  ${ch?.name ?: ""}")
            appendLine(getString(R.string.diag_video, v?.let {
                    "${it.width}x${it.height} ${it.sampleMimeType ?: "?"}" +
                        (if (it.frameRate > 0) " %.2f fps".format(it.frameRate) else "")
                } ?: "-"))
            appendLine(getString(R.string.diag_audio, a?.let {
                    "${it.sampleMimeType ?: "?"} ${it.channelCount}ch ${it.language ?: ""}"
                } ?: "-"))
            if (prefs.engine == "vlc") appendLine(getString(R.string.diag_engine, vlc?.info() ?: "VLC"))
            else appendLine(getString(R.string.diag_tunnel, getString(if (prefs.tunneling) R.string.diag_enabled else R.string.diag_disabled)))
            val bufferState = if (cushionSpeed != 1f) getString(R.string.diag_building_buffer, "%.2f".format(cushionSpeed))
                else if (cushionDisabled || prefs.tunneling || !prefs.cushionSlow) getString(R.string.diag_no_slowdown) else ""
            appendLine(getString(R.string.diag_buffer, p.totalBufferedDuration, cushionTarget, bufferState,
                if (lastStartMs >= 0) "$lastStartMs ms" else "-"))
            appendLine(netStatsLine())
            analyzer?.let { a ->
                appendLine(getString(R.string.diag_ts_rate, a.bitrateKbps / 1000.0, a.maxGapMs))
                appendLine(getString(R.string.diag_ts_bursts, a.burstAvgMs, a.cleaned, a.tsFixed))
                appendLine(getString(R.string.diag_ts_errors, a.ccErrors, a.teiErrors, a.scrambled, a.syncLoss, a.pcrJumps, a.pmtChanges))
                if (prefs.eitStream) {
                    fun e(x: EitEvent?) = x?.let { "${it.title} ${hm.format(Date(it.begin * 1000))}–${hm.format(Date(it.end * 1000))}" } ?: "-"
                    appendLine(getString(R.string.diag_eit, a.eitPackets, e(a.eitPresent), e(a.eitFollowing)))
                }
            }
            appendLine(getString(R.string.diag_screen, displayInfo()))
            appendLine(getString(R.string.diag_refresh, afrName(prefs.afrMode)))
            if (wd != null) {
                appendLine(getString(R.string.diag_frames, wd.frames, wd.dropped, if (prefs.tunneling) getString(R.string.diag_tunnel_hidden) else ""))
                appendLine(getString(R.string.diag_watchdog, wd.mode))
            } else {
                appendLine(getString(R.string.diag_watchdog_off))
            }
            appendLine(getString(R.string.diag_restarts, restarts))
            append(getString(R.string.diag_last_reason, lastRestartReason))
        }
    }

    // ---------------------------------------------------------------- numer kanału

    private val commitNumberRunnable = Runnable { commitNumber() }

    private fun digit(d: Int) {
        if (numberBuffer.length >= 4) numberBuffer = ""
        numberBuffer += d.toString()
        numEntry.text = numberBuffer
        numEntry.visibility = View.VISIBLE
        ui.removeCallbacks(commitNumberRunnable)
        ui.postDelayed(commitNumberRunnable, 1800)
    }

    private fun commitNumber() {
        ui.removeCallbacks(commitNumberRunnable)
        val n = numberBuffer.toIntOrNull()
        numberBuffer = ""
        numEntry.visibility = View.GONE
        if (n == null) return
        val i = playList.indexOfFirst { it.num == n }
        if (i >= 0) { panelOnRec = false; play(i) } else showMessage(getString(R.string.player_missing_number, n))
    }

    // ---------------------------------------------------------------- pilot

    private fun isOk(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount > 0) return true
        if (keyCode != KeyEvent.KEYCODE_BACK) backExit.reset()
        if (safeMode) {
            when {
                keyCode == KeyEvent.KEYCODE_BACK -> {
                    if (backExit.press(SystemClock.elapsedRealtime())) finish()
                    else showMessage(getString(R.string.message_press_back_again_to_exit))
                    return true
                }
                keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO || isOk(keyCode) -> {
                    showQuickMenu()
                    return true
                }
                keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                    keyCode == KeyEvent.KEYCODE_VOLUME_MUTE -> return super.onKeyDown(keyCode, event)
                else -> return true
            }
        }
        if (panel.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { panelBouquetStep(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { panelBouquetStep(1); return true }
                KeyEvent.KEYCODE_BACK -> { backExit.reset(); hidePanel(); return true }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> {
                    panelList.getOrNull(list.selectedItemPosition)?.let { epg.showChannelEpg(it) }
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (panelList.isNotEmpty()) list.setSelection(maxOf(0, list.selectedItemPosition - 10))
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (panelList.isNotEmpty()) list.setSelection(minOf(panelList.size - 1, list.selectedItemPosition + 10))
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        if (recMode && recKey(keyCode, event)) { if (keyCode == KeyEvent.KEYCODE_BACK) backExit.reset(); return true }
        if ((tsCtl || (keyCode == KeyEvent.KEYCODE_BACK && tsMode && tsShifted)) && tsKey(keyCode, event.repeatCount)) {
            if (keyCode == KeyEvent.KEYCODE_BACK) backExit.reset()
            return true
        }

        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            digit(keyCode - KeyEvent.KEYCODE_0); return true
        }
        if (keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
            digit(keyCode - KeyEvent.KEYCODE_NUMPAD_0); return true
        }

        if (isOk(keyCode)) {
            if (event.repeatCount == 0) {
                suppressOkUp = false
                if (numberBuffer.isNotEmpty()) {
                    commitNumber()
                    suppressOkUp = true
                } else {
                    okAction = navigationAction(KeyEvent.KEYCODE_DPAD_CENTER)
                    event.startTracking()
                }
            }
            return true
        }

        if (keyCode in KeyMap.navigation) {
            doAct(navigationAction(keyCode), keyCode)
            return true
        }
        keyMap[keyCode]?.let { act ->
            if (act != Act.NONE) { doAct(act, keyCode); return true }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (stats.visibility == View.VISIBLE) { backExit.reset(); stats.visibility = View.GONE; prefs.showStats = false; return true }
                if (banner.visibility == View.VISIBLE) { backExit.reset(); banner.visibility = View.GONE; return true }
                if (backExit.press(SystemClock.elapsedRealtime())) {
                    finish()
                } else {
                    showMessage(getString(R.string.message_press_back_again_to_exit))
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (panel.visibility != View.VISIBLE && isOk(keyCode)) {
            suppressOkUp = true
            // nagranie lokalne z kilkoma audycjami: lista skoków
            if (recMode && showRecMenu()) return true
            // pasek timeshiftu (nie pasek kanału): menu z „Nagraj…”
            if (!recMode && banner.visibility == View.VISIBLE && tsCtl && !tsChBar) { showRecordMenu(); return true }
            if (!recMode && banner.visibility == View.VISIBLE) {
                val diskUnavailable = prefs.tsDir.isNotBlank() && !TimeshiftBuffer.available(prefs.tsDir)
                val reason = when {
                    diskUnavailable -> R.string.player_ts_setup_disk_unavailable
                    prefs.tsDir.isBlank() && !prefs.tsInternalOk -> R.string.player_ts_setup_no_location
                    prefs.tsMin <= 0 -> R.string.player_ts_setup_disabled
                    else -> null
                }
                if (reason != null) {
                    val message = getString(reason) +
                        if (diskUnavailable && prefs.tsMin <= 0) "\n\n${getString(R.string.player_ts_setup_disabled)}" else ""
                    val buttons = mutableListOf(
                        TvDialog.Btn(getString(R.string.message_settings)) { openSettings(focusTimeshift = true) },
                    )
                    if (diskUnavailable && prefs.tsMin > 0 && prefs.tsInternalOk) {
                        buttons += TvDialog.Btn(getString(R.string.message_continue)) { openTimeshiftControls() }
                    } else buttons += TvDialog.Btn(getString(R.string.message_close))
                    TvDialog.confirm(this, getString(R.string.setting_timeshift), message, buttons)
                } else openTimeshiftControls()
            } else showQuickMenu()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun openTimeshiftControls() {
        if (tsShifted) tsShowBanner(sticky = player?.playWhenReady == false) else tsCtlOpen()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (panel.visibility != View.VISIBLE && isOk(keyCode)) {
            if (!suppressOkUp && !event.isCanceled) {
                when {
                    recMode -> recTogglePause()
                    panelOnRec -> showRecordings()
                    tsCtl && !tsChBar && banner.visibility == View.VISIBLE ->
                        if (tsMode) tsTogglePause() else tsEnter(pause = true, backMs = 0)
                    okAction != Act.DEFAULT_OK -> doAct(okAction, KeyEvent.KEYCODE_DPAD_CENTER)
                    tsCtl && tsChBar && banner.visibility == View.VISIBLE -> showPanel()
                    tsCtl && banner.visibility != View.VISIBLE -> playList.getOrNull(playIndex)?.let { showBanner(it) }
                    prefs.tsMin > 0 && playList.isNotEmpty() && banner.visibility != View.VISIBLE ->
                        playList.getOrNull(playIndex)?.let { showBanner(it) }
                    else -> showPanel()
                }
            }
            suppressOkUp = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun navigationAction(code: Int): Act {
        val withBanner = banner.visibility == View.VISIBLE && tsChBar
        val overrides = if (withBanner) prefs.keyMapBanner else prefs.keyMapNoBanner
        val defaults = if (withBanner) KeyMap.navigationBannerDefaults else KeyMap.navigationNoBannerDefaults
        return KeyMap.navigationMap(defaults, overrides).getValue(code)
    }

    private fun doAct(act: Act, keyCode: Int) {
        val inv = prefs.invertUpDown && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        when (act) {
            Act.NONE, Act.DEFAULT_OK -> {}
            Act.CHANNEL_BANNER -> playList.getOrNull(playIndex)?.let { showBanner(it) }
            Act.CHANNEL_LIST -> showPanel()
            Act.HISTORY_LIST -> showHistory()
            Act.EPG_EVENT -> epg.showCurrentEvent()
            Act.EPG_CHANNEL -> epg.showChannelEpg()
            Act.MULTI_EPG -> epg.showMultiEpg()
            Act.EPG_SEARCH -> epg.showSearch()
            Act.AUDIO -> showAudioDialog()
            Act.STATS -> toggleStats()
            Act.MENU -> showQuickMenu()
            Act.SETTINGS -> openSettings()
            Act.RECALL -> recall()
            Act.CH_UP -> { panelOnRec = false; play(playIndex + if (inv) -1 else 1) }
            Act.CH_DOWN -> { panelOnRec = false; play(playIndex + if (inv) 1 else -1) }
            Act.TS_REWIND -> when {
                !tsConfigured() -> {}
                tsMode -> { tsSetShifted(true); tsSeek(-prefs.tsStepSec * 1000L) }
                else -> tsEnter(pause = false, backMs = prefs.tsStepSec * 1000L)
            }
            Act.TS_FORWARD -> when {
                tsShifted -> tsSeek(prefs.tsStepSec * 1000L)
                tsMode -> showMessage(getString(R.string.message_already_live))
                prefs.tsMin > 0 -> showMessage(getString(R.string.message_already_live))
                else -> tsConfigured()
            }
            Act.TS_LIVE -> if (tsMode) tsGoLive() else if (prefs.tsMin <= 0) tsConfigured()
            Act.TIMESHIFT -> when {
                tsShifted -> tsTogglePause()
                tsMode -> { tsSetShifted(true); player?.playWhenReady = false; tsShowBanner(sticky = true) }   // już z bufora
                else -> tsEnter(pause = true, backMs = 0)
            }
            Act.RECORD -> when {
                localRec != null -> localRec?.let { r ->
                    TvDialog.confirm(this, getString(R.string.player_localrec_title), getString(R.string.player_localrec_stop_menu, r.meta.optString("title")),
                        listOf(TvDialog.Btn(getString(R.string.player_localrec_stop)) { if (localRec === r) finishLocalRec("zatrzymane", restartLive = true) },
                            TvDialog.Btn(getString(R.string.dialog_cancel))))
                }
                recMode || currentUrl == null -> {}
                prefs.tsMin <= 0 -> tsConfigured()
                else -> showRecordMenu()
            }
            Act.RECORDINGS -> if (bouquets.any { it.ref == REC_REF }) showRecordings(REC_REF) else showMessage(getString(R.string.action_hidden_bouquet))
            Act.LOCAL_RECORDINGS -> if (bouquets.any { it.ref == REC_LOCAL_BOUQUET }) showRecordings(REC_LOCAL_BOUQUET) else showMessage(getString(R.string.action_hidden_bouquet))
            Act.BUFFERED -> showBufferedMenu()
            Act.ZAP_TIMERS -> showZapTimers()
            Act.DECODER_TIMERS -> showDecoderTimers()
            Act.RESTART_CHANNEL -> if (!recMode) currentUrl?.let { url ->
                AppLog.i("kanał: ręczne uruchomienie od nowa")
                if (tsMode) tsLeave()
                if (bufLive) { tsRec?.stop(); tsRec = null }        // nowe połączenie z dekoderem
                showMessage(getString(R.string.action_restarting))
                startStream(url)
            }
            Act.BOUQUET_NEXT -> bouquetFromKey(1)
            Act.BOUQUET_PREV -> bouquetFromKey(-1)
            Act.ENGINE -> pinThen {
                val o = listOf("exo" to "ExoPlayer", "vlc" to "VLC")
                TvDialog.single(this, getString(R.string.setting_playback_engine), o.map { it.second }, o.indexOfFirst { it.first == prefs.engine }.coerceAtLeast(0)) { w ->
                    if (prefs.engine != o[w].first) {
                        prefs.engine = o[w].first
                        applyStreamSetting("silnik odtwarzania = ${o[w].second}", getString(R.string.action_engine_set, o[w].second))
                    }
                }
            }
            Act.TUNNELING -> pinThen {
                prefs.tunneling = !prefs.tunneling
                applyStreamSetting("tunelowanie obrazu = ${prefs.tunneling}",
                    getString(if (prefs.tunneling) R.string.action_tunneling_on else R.string.action_tunneling_off))
            }
            Act.BUFFER_PLAY -> pinThen {
                if (!prefs.bufPlay && TimeshiftBuffer.dir(this, prefs) == null) { showMessage(getString(R.string.storage_requires_medium)); return@pinThen }
                prefs.bufPlay = !prefs.bufPlay
                AppLog.i("klawisz: odtwarzanie przez bufor = ${prefs.bufPlay}")
                showMessage(getString(if (prefs.bufPlay) R.string.action_buffer_play_on else R.string.action_buffer_play_off))
                if (!recMode) currentUrl?.let { startStream(it) }
            }
            Act.BG_BUFFER -> pinThen {
                if (!prefs.bufPlay) { showMessage(getString(R.string.setting_bg_buffer_needs)); return@pinThen }
                val o = listOf(0, 1, 2, 3)
                val names = o.map { if (it == 0) getString(R.string.settings_disabled) else if (it == 1) getString(R.string.setting_bg_buffer_1) else getString(R.string.setting_bg_buffer_n, it) }
                TvDialog.single(this, getString(R.string.setting_bg_buffer), names, prefs.bgCount.coerceIn(0, 3)) { w ->
                    prefs.bgCount = o[w]
                    AppLog.i("klawisz: bufor poprzednich kanałów = ${o[w]}")
                    showMessage(getString(R.string.setting_bg_buffer) + ": " + names[w])
                    if (!recMode) currentUrl?.let { startStream(it) }
                }
            }
            Act.AV_SHIFT -> chooseAvShift()
            Act.PIP_1 -> pipKey(0)
            Act.PIP_2 -> pipKey(1)
            Act.PIP_3 -> pipKey(2)
            Act.SEND_LOG -> {
                showMessage(getString(R.string.action_sending_log))
                Tools.uploadLog(this, prefs, true) { showMessage(it) }
            }
        }
    }

    /** Przesunięcie dźwięku: lista −1000…+1000 ms, bieżąca wartość zaznaczona; działa od razu. */
    private fun chooseAvShift() {
        val o = AvShift.values
        // okno zostaje otwarte (ustawianie i sprawdzanie na żywo), wyjście WSTECZ
        TvDialog.singleLive(this, getString(R.string.setting_av_shift), o.map { avShiftLabel(this, it) }, o.indexOf(prefs.avShiftMs).coerceAtLeast(0)) { w ->
            prefs.avShiftMs = o[w]
            AvShift.ms = o[w]
            vlc?.applyAudioDelay()
            AppLog.i("dźwięk: przesunięcie ${o[w]} ms")
            w
        }
    }

    /** Akcje zmieniające ustawienia — z PIN-em, jak wejście do ustawień. */
    private fun pinThen(go: () -> Unit) = PinLock.ask(this, prefs, onCancel = {}) { go() }

    /** Zmiana ustawienia strumienia z klawisza: odtwarzacz od nowa (jak po powrocie z ustawień). */
    private fun applyStreamSetting(log: String, msg: String) {
        AppLog.i("klawisz: $log — odtwarzacz od nowa")
        val url = currentUrl
        releasePlayer()
        buildPlayer()
        if (url != null && !recMode) startStream(url)
        settingsSig = streamSig()
        showMessage(msg)
    }

    /** Następny / poprzedni bukiet z klawisza podczas oglądania: lista kanałów na sąsiednim bukiecie (bez przełączania). */
    private fun bouquetFromKey(d: Int) {
        if (bouquets.isEmpty() || recMode) return
        if (panel.visibility != View.VISIBLE || panelHist) {
            panelOnRec = false
            showPanel()
        }
        panelBouquetStep(d)
    }

    private fun openSettings(onCancel: () -> Unit = {}, focusTimeshift: Boolean = false) {
        PinLock.ask(this, prefs, onCancel = onCancel) {
            settingsOpen = true
            settingsSig = streamSig()
            settingsBq = prefs.hiddenBouquets.toString()
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                if (focusTimeshift) putExtra(SettingsActivity.EXTRA_FOCUS_TIMESHIFT, true)
            })
        }
    }

    private var settingsOpen = false
    private var backgrounded = false
    private var settingsSig = ""
    private var settingsBq = ""
    private var settingsReturnChannel: Pair<String, String>? = null

    /** Ustawienia, których zmiana wymaga ponownego uruchomienia strumienia. */
    private fun streamSig(): String = listOf(
        prefs.host, prefs.streamPort, prefs.httpPort, prefs.user, prefs.pass, prefs.tunneling, prefs.watchdog,
        prefs.stallSec, prefs.bufferMs, prefs.rebufferMs, prefs.cushionSlow, prefs.dropRestart, prefs.audioLang,
        prefs.preferAc3, prefs.saveTs, prefs.tsRingMb, prefs.pluginPort, prefs.pluginToken,
        prefs.engine, prefs.vlcDeint, prefs.vlcHwMode, prefs.vlcChroma, prefs.tsMin, prefs.tsDir, prefs.tsDelay, prefs.bufPlay, prefs.bgCount, prefs.pipEnabled,
    ).joinToString("|")

    override fun onResume() {
        super.onResume()
        if (recreateForLanguage()) return
        // pierwsza konfiguracja: wybrano dekoder w ustawieniach, a decyzja o kopii ustawień jeszcze nie zapadła —
        // pytanie od razu, niezależnie od tego, czy po powrocie z ustawień był pełny start (onStart)
        if (!safeMode && prefs.host.isNotBlank() && SyncManager.needsBackupDecision && !backupCheckRunning && !backupDialogOpen) {
            settingsOpen = false
            AppLog.i("kopia ustawień: pytanie po powrocie z ustawień")
            checkInitialBackup()
            return
        }
        if (!settingsOpen) return
        settingsOpen = false
        if (player == null && vlc == null) {
            // odtwarzacz nie był jeszcze uruchomiony (np. pierwsza konfiguracja nowego telewizora:
            // ustawienia otwarte od razu, bez adresu dekodera) — po ustawieniu adresu start od razu
            if (!safeMode && prefs.host.isNotBlank()) {
                AppLog.i("ustawienia: pierwsza konfiguracja — start odtwarzania")
                settingsVersion = -1
                startUp()
            }
            return
        }
        if (streamSig() != settingsSig) {
            AppLog.i("ustawienia: zmiany dotyczące strumienia — ponowne uruchomienie")
            releasePlayer()
            settingsVersion = -1
            startUp()
            return
        }
        // pozostałe zmiany bez zatrzymywania strumienia
        stats.visibility = if (prefs.showStats) View.VISIBLE else View.GONE
        keyMap = KeyMap.load(prefs)
        AvShift.ms = prefs.avShiftMs; vlc?.applyAudioDelay()
        UiScale.apply(banner, UiScale.pct(prefs.uiBannerFont), UiScale.pct(prefs.uiBannerIcon))
        UiScale.apply(message, UiScale.pct(prefs.uiBannerFont))
        if (!prefs.messageBar) {
            ui.removeCallbacks(hideMessage)
            message.visibility = View.GONE
        }
        applyDisplayMode()
        epg.eitOnly = prefs.eitOnlyActive
        epg.clearMemory()
        list.adapter = adapter      // wiersze od nowa (np. nowa wielkość czcionki)
        if (prefs.hiddenBouquets.toString() != settingsBq) {
            AppLog.i("ustawienia: zmiana bukietów — lista kanałów od nowa, strumień bez przerwy")
            reloadBouquetsKeepStream()
        }
        settingsVersion = prefs.version
    }

    private var recreatingLanguage = false

    private fun recreateForLanguage(): Boolean {
        if (recreatingLanguage) return true
        if (!AppLanguage.needsUpdate(this) || isFinishing) return false
        if (!safeMode && (player != null || vlc != null) && prefs.playbackAttempt.isNotEmpty()) {
            prefs.playbackAttempt = ""
        }
        recreatingLanguage = true
        recreate()
        return true
    }

    // ---------------------------------------------------------------- zrzut przy zawieszeniu

    private fun decoderInfo(): String {
        val c = player?.videoDecoderCounters ?: return "dekoder: -"
        c.ensureUpdated()
        return "dekoder: wyrenderowane ${c.renderedOutputBufferCount}, utracone ${c.droppedBufferCount}, pominięte ${c.skippedOutputBufferCount}, maks. kolejno utracone ${c.maxConsecutiveDroppedBufferCount}"
    }

    private fun dumpStall(reason: String) {
        val a = analyzer ?: return
        // Problemy „startowe” (brak PMT, brak danych) to nie awaria strumienia — tylko wpis w logu,
        // bez zrzutu TS. Zrzut ma sens, gdy strumień był kompletny i odtwarzany.
        val startIssue = !a.pmtSeen || a.bytes < 2_000_000
        if (startIssue) {
            val why = when {
                a.bytes == 0L -> "dekoder nie wysyła danych"
                !a.pmtSeen && a.pmtInjected == 0 -> "brak PMT, jeszcze nie zapamiętany dla kanału"
                !a.pmtSeen -> "brak PMT mimo wstawienia zapamiętanego"
                else -> "za mało danych"
            }
            AppLog.i("start: restart ($reason) — $why; ${a.summary()}")
            return
        }
        AppLog.i("ZAWIESZENIE ($reason) | ${a.summary()} | ${decoderInfo()}")
        pipHoldUntil = SystemClock.elapsedRealtime() + 8000
        if (!prefs.saveTs) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDumpAt < 60_000) {
            AppLog.i("zrzut TS pominięty (poprzedni < 60 s temu)"); return
        }
        lastDumpAt = now
        val data = a.ringSnapshot()
        if (data.isEmpty()) return
        val ch = playList.getOrNull(playIndex)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = (ch?.name ?: "kanal").replace(Regex("[^A-Za-z0-9_-]"), "_").take(30)
        val name = "stall-$stamp-${prefs.fileTag}-$safe.ts"
        val dir = Storage.dir(this, prefs)
        val ftp = prefs.tsFtp
        val host = prefs.host
        val user = prefs.ftpUser
        val pass = prefs.ftpPass
        val ftpDir = prefs.ftpDir
        Thread {
            try {
                File(dir, name).writeBytes(data)
                Storage.prune(dir, "stall-", 3)
                AppLog.i("zrzut TS: ${dir.absolutePath}/$name (${data.size / 1024} kB)")
            } catch (e: Exception) {
                AppLog.i("zrzut TS nieudany: $e")
            }
            if (ftp) {
                try {
                    val where = Upload.send(prefs, name, data)
                    File(dir, name).delete()   // jest na dekoderze — na urządzeniu nie trzymamy kopii
                    AppLog.i("zrzut TS wysłany na dekoder: $where (kopia lokalna usunięta)")
                } catch (e: Exception) {
                    AppLog.i("wysyłanie zrzutu TS nieudane: $e")
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- aktualizacja

    private fun checkUpdate(manual: Boolean) = Tools.checkUpdate(this, manual) { showMessage(it) }



    // ---------------------------------------------------------------- menu podręczne

    private fun showQuickMenu() {
        if (safeMode) {
            val items = listOf(
                getString(R.string.player_normal_start) to { retryNormalStartup() },
                getString(R.string.message_settings) to { openSettings() },
                getString(R.string.message_close) to { finish() },
            )
            TvDialog.list(this, getString(R.string.message_e2stream_safe_mode), items.map { TvDialog.Item(it.first) }, 0, widthFrac = 0.5f) { which ->
                items[which].second()
            }
            return
        }
        val actions = mapOf<String, () -> Unit>(
            "event" to { epg.showCurrentEvent() },
            "channel_epg" to { epg.showChannelEpg() },
            "multi_epg" to { epg.showMultiEpg() },
            "search" to { epg.showSearch() },
            "audio" to { showAudioDialog() },
            "zap_timers" to { showZapTimers() },
            "decoder_timers" to { showDecoderTimers() },
            "stats" to { toggleStats() },
            "av_shift" to { chooseAvShift() },
            "engine" to { doAct(Act.ENGINE, 0) },
            "tunneling" to { doAct(Act.TUNNELING, 0) },
            "send_log" to { showMessage(getString(R.string.action_sending_log)); Tools.uploadLog(this, prefs, true) { showMessage(it) } },
            "sleep" to { chooseSleep() },
        )
        val canRecord = !recMode && prefs.tsMin > 0 && currentUrl != null && localRec == null
        val items = QuickMenu.ordered(prefs).mapNotNull { e ->
            when (e.id) {
                "record" -> if (canRecord) getString(R.string.player_localrec_menu) to { showRecordMenu() } else null
                "record_stop" -> if (activeRecs() > 0) getString(R.string.player_rec_stop_menu) to { showStopRecs() } else null
                "buffered" -> if (!recMode && (bgChans.isNotEmpty() || (bufLive && tsBuf != null)))
                    getString(R.string.player_bg_menu) to { showBufferedMenu() } else null
                // PiP — tylko przy włączonym ustawieniu „PiP (eksperymentalne)”
                "pip" -> if (Features.PIP && prefs.pipEnabled && bgAllowed() && (recMode || currentUrl != null))
                    getString(R.string.pip_menu) to { showPipMenu() } else null
                else -> getString(e.labelRes) to actions.getValue(e.id)
            }
        } + listOf(getString(R.string.message_settings) to { openSettings() })
        TvDialog.list(this, "E2Stream ${appVersion()}", items.map { TvDialog.Item(it.first) }, 0, widthFrac = 0.4f) { which ->
            items[which].second()
        }
    }

    /** Przełącza na kanał o danej referencji (z bieżącego lub innego widocznego bukietu). */
    private fun zapTo(
        ref: String,
        forceTune: Boolean = false,
        onResult: ((Boolean, String) -> Unit)? = null,
    ) {
        if (safeMode) return
        val k = OpenWebif.key(ref)
        val i = playList.indexOfFirst { OpenWebif.key(it.ref) == k }
        if (!forceTune && i >= 0 && i == playIndex && currentUrl != null && !recMode) {
            // ten kanał już gra — tylko zamknij listę, bez ponownego uruchamiania strumienia
            hidePanel(); showBanner(playList[i])
            onResult?.invoke(true, getString(R.string.player_already_playing, playList[i].name))
            return
        }
        if (i >= 0) {
            hidePanel()
            play(i)
            onResult?.invoke(true, getString(R.string.player_switch_started, playList[i].name))
            return
        }
        val bq = bouquets
        io.execute {
            var lookupError: Exception? = null
            for ((bi, b) in bq.withIndex()) {
                if (isVirtual(b.ref)) continue
                val ch = try {
                    api.channels(b)
                } catch (e: Exception) {
                    lookupError = e
                    continue
                }
                val j = ch.indexOfFirst { OpenWebif.key(it.ref) == k }
                if (j >= 0) {
                    runOnUiThread {
                        hidePanel()
                        playIn(bi, ch, j)
                        onResult?.invoke(true, getString(R.string.player_switch_started, ch[j].name))
                    }
                    return@execute
                }
            }
            runOnUiThread {
                val message = if (lookupError == null) {
                    getString(R.string.player_channel_not_found)
                } else {
                    getString(R.string.player_channel_check_failed, lookupError?.message ?: lookupError.toString())
                }
                showMessage(message)
                onResult?.invoke(false, message)
            }
        }
    }

    // ---------------------------------------------------------------- dźwięk

    private class AudioTrack(val group: Tracks.Group, val index: Int, val format: Format)

    private fun audioTracks(): List<AudioTrack> {
        val p = player ?: return emptyList()
        val out = ArrayList<AudioTrack>()
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) {
                if (g.isTrackSupported(i)) out.add(AudioTrack(g, i, g.getTrackFormat(i)))
            }
        }
        return out
    }

    private fun audioLabel(f: Format): String =
        AudioTrackLabels.label(f.language, f.sampleMimeType, f.channelCount)

    private fun showAudioDialog(vlcAttempt: Int = 0) {
        if (prefs.engine == "vlc" && !tsMode && !recMode) {
            val engine = vlc
            val tracks = engine?.audioTracks().orEmpty()
            if (tracks.isEmpty()) {
                if (vlcAttempt < 12) {
                    ui.postDelayed({ showAudioDialog(vlcAttempt + 1) }, 250)
                } else {
                    showMessage(getString(R.string.message_no_audio_tracks))
                }
                return
            }
            val labels = tracks.map { it.name }
            val selected = tracks.indexOfFirst { it.id == engine?.selectedAudioTrackId() }
            TvDialog.singleLive(this, getString(R.string.message_audio_track_remembered_for_this_channel), labels, selected) { which ->
                val track = tracks.getOrNull(which)
                val previous = engine?.selectedAudioTrackId()
                ui.removeCallbacks(audioCheck)
                audioCheckGen = streamGen
                if (track != null && (previous == track.id || engine?.selectAudioTrack(track.id) == true)) {
                    playList.getOrNull(playIndex)?.let { ch ->
                        prefs.setAudioFor(ch.ref, "${track.id}|${track.language}|${track.codec}")
                        AppLog.i("wybrano audio dla ${ch.name}: ${track.name} (ID ${track.id}, poprzedni $previous, VLC)")
                    }
                    which
                } else {
                    showMessage(getString(R.string.message_could_not_select_audio_track))
                    -1
                }
            }
            return
        }
        val tracks = audioTracks()
        if (tracks.isEmpty()) {
            showMessage(getString(R.string.message_no_audio_tracks)); return
        }
        val labels = tracks.map { audioLabel(it.format) }.toTypedArray()
        val sel = tracks.indexOfFirst { it.group.isTrackSelected(it.index) }
        TvDialog.singleLive(this, getString(R.string.message_audio_track_remembered_for_this_channel), labels.toList(), sel) { which ->
            selectAudio(tracks[which], remember = true)
            which
        }
    }

    private fun startAudioCheck() {
        if (audioCheckGen == streamGen || tsMode || recMode) return
        audioCheckGen = streamGen
        audioCheckStarted = SystemClock.elapsedRealtime()
        audioWindow = AudioSelectionWindow()
        audioCheckLastTarget = null
        audioCheckDescription = ""
        ui.post(audioCheck)
    }

    private val audioCheck = object : Runnable {
        override fun run() {
            if (audioCheckGen != streamGen || backgrounded || tsMode || recMode) return
            val ch = playList.getOrNull(playIndex) ?: return
            val usingVlc = prefs.engine == "vlc"
            val vlcEngine = if (usingVlc) vlc ?: return else null
            val exoTracks = if (usingVlc) emptyList() else audioTracks()
            val vlcTracks = vlcEngine?.audioTracks().orEmpty()
            val selectedId = vlcEngine?.selectedAudioTrackId()
            val candidates = if (usingVlc) vlcTracks.map {
                AudioCandidate(it.id.toString(), it.language, it.codec, it.id == selectedId)
            } else exoTracks.map {
                AudioCandidate(it.format.id.orEmpty(), it.format.language, it.format.sampleMimeType,
                    it.group.isTrackSelected(it.index))
            }
            val now = SystemClock.elapsedRealtime()
            if (candidates.isEmpty()) {
                if (now - audioCheckStarted >= 3000) {
                    AppLog.i("audio: brak obsługiwanych ścieżek dla ${ch.name}")
                    return
                }
                ui.postDelayed(this, 250)
                return
            }
            val saved = prefs.audioFor(ch.ref)
            val languages = AudioTrackLabels.preferredLanguages(prefs.audioLang)
            val (choice, remaining) = audioWindow.check(now, candidates, saved, languages, prefs.preferAc3)
            val finalCheck = remaining == 0L
            val description = "zapis=${saved.ifEmpty { "-" }}, preferowane=${languages.joinToString(",")}, dostępne=" +
                candidates.joinToString { "${it.id}:${it.language}:${it.codec}${if (it.selected) "*" else ""}" }
            if (description != audioCheckDescription) {
                audioCheckDescription = description
                AppLog.i("audio: ${ch.name}, $description")
            }
            if (choice != null) {
                val target = candidates[choice.index]
                val targetKey = "${choice.index}:${target.id}:${target.language}:${target.codec}"
                if (target.selected) audioCheckLastTarget = null
                if (!target.selected && targetKey != audioCheckLastTarget) {
                    audioCheckLastTarget = targetKey
                    if (usingVlc) {
                        if (vlcEngine?.selectAudioTrack(vlcTracks[choice.index].id) == true) {
                            AppLog.i("audio: wybieram ${choice.reason} ${target.id} dla ${ch.name}")
                        } else {
                            AppLog.i("audio: nie udało się wybrać ${choice.reason} ${target.id} dla ${ch.name}")
                        }
                    } else {
                        AppLog.i("audio: wybieram ${choice.reason} ${target.id} dla ${ch.name}")
                        selectAudio(exoTracks[choice.index], remember = false)
                    }
                }
            }
            if (finalCheck) {
                if (saved.isNotEmpty() && choice?.reason != "zapamiętana") {
                    AppLog.i("audio: zapamiętana ścieżka niedostępna dla ${ch.name}: $saved")
                }
                if (choice == null) AppLog.i("audio: brak zapamiętanej lub preferowanej ścieżki dla ${ch.name}")
                else if (!candidates[choice.index].selected) AppLog.i("audio: wybór ${choice.reason} nie został jeszcze potwierdzony dla ${ch.name}")
                else AppLog.i("audio: potwierdzono ${choice.reason} ${candidates[choice.index].id} dla ${ch.name}")
            } else {
                ui.postDelayed(this, minOf(250L, remaining))
            }
        }
    }

    private fun selectAudio(t: AudioTrack, remember: Boolean) {
        val p = player ?: return
        val previous = if (remember) audioTracks().firstOrNull { it.group.isTrackSelected(it.index) }?.format?.id else null
        if (remember) {
            ui.removeCallbacks(audioCheck)
            audioCheckGen = streamGen
        }
        if (!t.group.isTrackSelected(t.index)) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                .build()
        }
        if (tsMode) tsAudioPid = mpegTsPid(t.format.id)
        val ch = playList.getOrNull(playIndex)
        if (remember && ch != null) {
            val f = t.format
            prefs.setAudioFor(ch.ref, "${f.id ?: ""}|${f.language ?: ""}|${f.sampleMimeType ?: ""}")
            AppLog.i("wybrano audio dla ${ch.name}: ${audioLabel(f)} (id=${f.id}, poprzedni=$previous, tunelowanie=${prefs.tunneling})")
        }
    }

    private fun selectedAudioPid(): Int? =
        audioTracks().firstOrNull { it.group.isTrackSelected(it.index) }?.let { mpegTsPid(it.format.id) }

    private fun applyTimeshiftAudio() {
        if (tsAudioAppliedGen == tsAudioSourceGen) return
        val tracks = audioTracks()
        if (tracks.isEmpty()) return
        tsAudioAppliedGen = tsAudioSourceGen
        val pid = tsAudioPid
        if (pid == null) {
            applySavedAudio()
            return
        }
        val match = tracks.firstOrNull { mpegTsPid(it.format.id) == pid }
        if (match == null) {
            AppLog.i("timeshift: PID audio $pid niedostępny w źródle, używam domyślnego wyboru")
            applySavedAudio()
            return
        }
        if (!match.group.isTrackSelected(match.index)) {
            val p = player ?: return
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(match.group.mediaTrackGroup, match.index))
                .build()
        }
        AppLog.i("timeshift: wybrano audio PID=$pid")
    }

    /** Po starcie strumienia (także po wznowieniu) ustawia zapamiętaną ścieżkę kanału. */
    private fun applySavedAudio() {
        if (audioAppliedGen == streamGen) return
        val tracks = audioTracks()
        if (tracks.isEmpty()) return
        audioAppliedGen = streamGen
        val ch = playList.getOrNull(playIndex) ?: return
        val saved = prefs.audioFor(ch.ref)
        if (saved.isEmpty()) return
        val parts = saved.split("|")
        val id = parts.getOrElse(0) { "" }
        val lang = parts.getOrElse(1) { "" }
        val mime = parts.getOrElse(2) { "" }
        val match = tracks.firstOrNull { id.isNotEmpty() && it.format.id == id }
            ?: tracks.firstOrNull {
                lang.isNotBlank() &&
                    AudioTrackLabels.languageCode(it.format.language) == AudioTrackLabels.languageCode(lang) &&
                    (mime.isBlank() || AudioTrackLabels.codecName(it.format.sampleMimeType) == AudioTrackLabels.codecName(mime))
            }
            ?: return
        if (!match.group.isTrackSelected(match.index)) selectAudio(match, remember = false)
    }

    // ---------------------------------------------------------------- log na dekoder

    private val logTick = object : Runnable {
        override fun run() {
            if (AppLog.dirty) uploadLog(false)
            syncChannels()
            ui.postDelayed(this, 120_000)
        }
    }

    /** Wysyła zmiany na dekoder (synchronizacja w tle). */
    private fun syncChannels() {
        if (SyncManager.dirty) SyncManager.syncAsync()
    }

    private fun uploadLog(manual: Boolean) = Tools.uploadLog(this, prefs, manual) { showMessage(it) }



    // ---------------------------------------------------------------- odświeżanie ekranu

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    private fun afrName(m: Int) = getString(when (m) {
        1 -> R.string.settings_refresh_50
        2 -> R.string.settings_refresh_auto
        else -> R.string.settings_refresh_unchanged
    })

    @Suppress("DEPRECATION")
    private fun displayInfo(): String {
        if (Build.VERSION.SDK_INT < 23) return "?"
        val d = windowManager.defaultDisplay ?: return "?"
        val m = d.mode
        val rates = d.supportedModes
            .filter { it.physicalWidth == m.physicalWidth && it.physicalHeight == m.physicalHeight }
            .map { "%.2f".format(it.refreshRate) }
            .distinct()
            .joinToString(", ")
        return getString(R.string.diag_display_modes, "${m.physicalWidth}x${m.physicalHeight} @ " + "%.2f".format(m.refreshRate) + " Hz", rates)
    }

    private fun targetRate(fr: Float): Float = when {
        fr <= 0f -> 50f // nieznane — DVB w Europie to 25/50
        abs(fr - 25f) < 1f || abs(fr - 50f) < 1f -> 50f
        abs(fr - 23.976f) < 0.05f -> 23.976f
        abs(fr - 24f) < 0.5f -> 24f
        abs(fr - 29.97f) < 0.1f || abs(fr - 59.94f) < 0.2f -> 59.94f
        abs(fr - 30f) < 0.5f || abs(fr - 60f) < 0.5f -> 60f
        else -> 50f
    }

    @Suppress("DEPRECATION")
    private fun applyDisplayMode() {
        if (Build.VERSION.SDK_INT < 23) return
        val d = windowManager.defaultDisplay ?: return
        val lp = window.attributes
        val mode = prefs.afrMode
        var target = 0
        if (mode != 0) {
            val rate = if (mode == 1) 50f else targetRate(player?.videoFormat?.frameRate ?: -1f)
            val cur = d.mode
            val same = d.supportedModes.filter {
                it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
            }
            // bieżący tryb już ma właściwe odświeżanie — nie przełączamy (MiBox po każdej zmianie trybu
            // numeruje tryby od nowa, a kilka trybów 50 Hz bywa różnych, np. 1080p50 / 1080i50)
            val exact = (if (abs(cur.refreshRate - rate) < 0.1f) cur else null)
                ?: same.minByOrNull { abs(it.refreshRate - rate) }?.takeIf { abs(it.refreshRate - rate) < 0.1f }
            val multiple = same.firstOrNull {
                val k = it.refreshRate / rate
                k >= 1.9f && abs(k - Math.round(k)) < 0.01f
            }
            target = (exact ?: multiple)?.modeId ?: 0
            if (target == 0) AppLog.i("ekran: brak trybu dla $rate Hz (${displayInfo()})")
        }
        if (lp.preferredDisplayModeId != target) {
            val cur = d.mode
            lp.preferredDisplayModeId = target
            window.attributes = lp
            val modes = d.supportedModes.joinToString(" ") { "${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${"%.2f".format(it.refreshRate)}" }
            AppLog.i("ekran: preferowany tryb id=$target (bieżący id=${cur.modeId} ${"%.2f".format(cur.refreshRate)} Hz; tryby: $modes)")
        }
    }
}

/** Jeden wątek; po shutdown kolejne zadania są cicho odrzucane (bez RejectedExecutionException). */
private fun quietSingleThread() = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
    java.util.concurrent.LinkedBlockingQueue(), java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())
