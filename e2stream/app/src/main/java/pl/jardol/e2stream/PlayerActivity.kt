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

    private fun showDecoderTimers() {
        showMessage(getString(R.string.message_loading_timers_from_receiver), 3000)
        io.execute {
            val result = try { api.decoderTimers() to null } catch (e: Exception) { null to (e.message ?: e.toString()) }
            runOnUiThread {
                val timers = result.first
                if (timers == null) {
                    showMessage(getString(R.string.player_timers_failed, result.second), 8000)
                    return@runOnUiThread
                }
                if (timers.isEmpty()) {
                    showMessage(getString(R.string.message_no_timers_on_the_receiver), 5000)
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
                TvDialog.list(this, getString(R.string.message_receiver_timers), labels,
                    hint = getString(R.string.player_details_hint), widthFrac = 0.75f) { selected ->
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
    private val io = Executors.newSingleThreadExecutor()
    private val probeIo = Executors.newSingleThreadExecutor()

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
    private var vlcRestartAttempts = 0
    private var numberBuffer = ""
    private var suppressOkUp = false
    private var okAction = Act.DEFAULT_OK
    private val backExit = DoubleBackExit()
    private var streamGen = 0
    private var analyzer: TsAnalyzer? = null
    private var analyzerRing = -1
    private var lastDumpAt = 0L
    private var updateChecked = false
    private var epgStartupPending = false
    private var epgReturnFromSettings = false
    private var streamStartAt = 0L
    private var readyLogged = false
    private var lastStartMs = -1L
    private var audioAppliedGen = -1
    private var wakeTimerKey: String? = null
    private var wakeTestId: String? = null
    private var scheduledChannelRef: String? = null
    private var scheduledAppliedRef: String? = null      // kanał z harmonogramu dostrojony przed końcem startu
    private var scheduledAppliedAt = 0L
    private var connectionDialog: Dialog? = null
    private var connectionProbePending = false
    private var decoderUnavailable = false
    private var lastProbeAt = 0L
    @Volatile private var startupProbeGeneration = 0
    private var backupCheckRunning = false
    private var backupDialogOpen = false
    private var backupCheckGeneration = 0

    // ---------------------------------------------------------------- cykl życia

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        tsCleanupAtStart()
        epgStore = EpgStore(this)
        epg = EpgUi(this, api, io, epgStore, object : EpgUi.Host {
            override fun current(): Channel? = playList.getOrNull(playIndex)
            override fun bouquetChannels(): List<Channel> = playList
            override fun bouquetList(): List<Bouquet> = this@PlayerActivity.bouquets.filter { it.ref != REC_REF }
            override fun currentBouquet(): Bouquet? = bouquets.getOrNull(playBouquet)?.takeIf { it.ref != REC_REF }
            override fun zapTo(ref: String, onResult: ((Boolean, String) -> Unit)?) =
                this@PlayerActivity.zapTo(ref, onResult = onResult)
            override fun message(t: String) = showMessage(t, 4000)
        })
        AppLog.i("=== Start E2Stream ${appVersion()} | ${Build.MANUFACTURER} ${Build.MODEL} | ${prefs.displayName} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ekran: ${displayInfo()}")
        if (wakeTimerKey != null || wakeTestId != null) {
            AppLog.i("wybudzanie: PlayerActivity uruchomiona z alarmu key=$wakeTimerKey testId=$wakeTestId")
        }

        playerView = findViewById(R.id.playerView)
        vlcLayout = findViewById(R.id.vlcLayout)
        panel = findViewById(R.id.panel)
        panelTitle = findViewById(R.id.panelTitle)
        list = findViewById(R.id.list)
        banner = findViewById(R.id.banner)
        bannerName = findViewById(R.id.bannerName)
        bannerNow = findViewById(R.id.bannerNow)
        bannerIcon = findViewById(R.id.bannerIcon)
        bannerClock = findViewById(R.id.bannerClock)
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
                if (r == null) epg.showCurrentEvent(c)
                else if (!r.isDir) showRecordingActions(r)
            }
            true
        }

        val crash = prefs.lastCrash
        val attempt = prefs.playbackAttempt
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
        ZapWakeAlarms.setAppForeground(this, true)
        ZapWakeAlarms.sync(this)
        if (safeMode) return
        startUp()
        showWakeTestResult()
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
                TvDialog.Btn(getString(R.string.message_start_anyway)) { AppLog.i("tryb bezpieczny: kontynuuję bez dekodera") },
                TvDialog.Btn(getString(R.string.message_go_to_settings)) { openSettings() },
                TvDialog.Btn(getString(R.string.message_close)) { finish() },
            ),
            cancelable = false,
        )
    }

    private fun markPlaybackHealthy() {
        if (prefs.playbackAttempt.isNotEmpty()) {
            prefs.playbackAttempt = ""
            AppLog.i("start: odtwarzanie osiągnęło pierwszy obraz")
        }
        failStreak = 0
        vlcRestartAttempts = 0
    }

    private fun retryNormalStartup() {
        if (!safeMode || isFinishing) return
        safeMode = false
        settingsVersion = -1
        vlcRestartAttempts = 0
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
        stats.visibility = if (prefs.showStats) View.VISIBLE else View.GONE
        keyMap = KeyMap.load(prefs)
        UiScale.apply(banner, UiScale.pct(prefs.uiBannerFont), UiScale.pct(prefs.uiBannerIcon))
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
        showMessage(getString(R.string.message_checking_settings_backups_on_receiver), 10_000)
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
            if (EpgSync.due(prefs, epgStartupPending)) {
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
            if (channel != null && bouquet != null && bouquet.ref != REC_REF) {
                settingsReturnChannel = bouquet.ref to channel.ref
            }
        }
        panelTimer.setActive(false)
        ZapWakeAlarms.setAppForeground(this, false)
        settingsOpen = false      // po powrocie i tak pełny start (onStart)
        backgrounded = true       // wyjście do ekranu głównego — po powrocie start jak przy uruchomieniu
        ui.removeCallbacks(epgTick)
        ui.removeCallbacks(zapTick)
        ui.removeCallbacks(connectionGuardTick)
        connectionProbePending = false
        dismissConnectionDialog()
        releasePlayer()
        ui.removeCallbacks(logTick)
        if (!safeMode) {
            if (AppLog.dirty) uploadLog(false)
            syncChannels()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        probeIo.shutdownNow()
    }

    // ---------------------------------------------------------------- odtwarzacz

    private fun buildPlayer() {
        if (player != null) return

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
        val renderers = E2RenderersFactory(this, prefs.exoFps.toFloat()).setEnableDecoderFallback(true)

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
                }
                if (playbackState == Player.STATE_ENDED) {
                    if (recMode) recEnded() else if (tsMode) tsGoLive("koniec bufora") else scheduleRestart("koniec strumienia")
                }
            }

            override fun onRenderedFirstFrame() {
                lastStartMs = SystemClock.elapsedRealtime() - streamStartAt
                AppLog.i("start: pierwszy obraz po $lastStartMs ms")
                markPlaybackHealthy()
            }

            override fun onTracksChanged(tracks: Tracks) {
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
                if (tsMode) applyTimeshiftAudio() else applySavedAudio()
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
            }
            analyzerRing = ring
        }
        analyzer?.clean = prefs.cleanTs
        // kopia strumienia na żywo do bufora timeshiftu (nie przy nagraniach z dekodera)
        analyzer?.tee = { b, o, l -> if (!recMode && SystemClock.elapsedRealtime() >= tsWriteFrom) tsBuf?.write(b, o, l) }
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
                showMessage(getString(R.string.message_encrypted_channel_receiver_is_not_decrypting_it_card_so), 15_000)
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
            if (!encrypted && a != null && p != null && p.playbackState == Player.STATE_BUFFERING &&
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
        val analyzing = DataSource.Factory {
            AnalyzingDataSource(http.createDataSource()) { analyzer }
        }
        return ProgressiveMediaSource.Factory(analyzing, extractors)
            .createMediaSource(MediaItem.fromUri(url))
    }

    private fun startStream(url: String, startMs: Long = 0) {
        if (safeMode) return
        val p = player ?: return
        currentUrl = url
        streamGen++
        streamStartAt = SystemClock.elapsedRealtime()
        readyLogged = false
        cushionReset()
        lastStartMs = -1
        // SID serwisu = 4. pole referencji (hex), np. 1:0:19:3DD2:640:13E:820000:0:0:0:
        val sid = url.substringAfterLast('/').split(":").getOrNull(3)?.toIntOrNull(16) ?: -1
        analyzer?.reset(sid)
        // nagranie z pliku: bez poprawiania strumienia — po przewinięciu znaczniki czasu skaczą względem
        // ostatniego PCR i „poprawka” przesuwała dźwięk (obraz wyprzedzał dźwięk)
        analyzer?.clean = if (recMode) false else prefs.cleanTs
        if (decoderUnavailable && !recMode) {
            p.stop()
            proxy?.stop(); proxy = null
            vlc?.stop()
            return
        }
        prefs.playbackAttempt = "${appVersion()} / ${prefs.engine.uppercase()}"
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
        watchdog?.reset()
    }

    // ---------------------------------------------------------------- VLC

    private lateinit var vlcLayout: org.videolan.libvlc.util.VLCVideoLayout
    private var vlc: VlcEngine? = null
    private var proxy: TsProxy? = null
    private var vlcLastTime = -1L
    private var vlcLastMove = 0L

    private fun startVlc(url: String) {
        player?.stop()
        playerView.visibility = View.GONE
        vlcLayout.visibility = View.VISIBLE
        val px = proxy ?: TsProxy({ analyzer }, { api.authHeader() }).also { it.start(); proxy = it }
        px.setTarget(url)
        val v = vlc ?: VlcEngine(this, prefs, vlcLayout,
            onEvent = { m ->
                if (m == "VLC: odtwarzanie") ui.post { applySavedVlcAudio() }
                if (m == "VLC: obraz") ui.post { markPlaybackHealthy() }
                if (!readyLogged) { readyLogged = true; AppLog.i("start: $m po ${SystemClock.elapsedRealtime() - streamStartAt} ms") }
            },
            onFailure = { r -> scheduleRestart(r) }).also { vlc = it }
        AppLog.i("VLC: ${v.info()}")
        vlcLastTime = -1
        vlcLastMove = SystemClock.elapsedRealtime()
        v.play(px.url())
        ui.postDelayed({
            Thread { vlc?.vlcLog()?.forEach { AppLog.i("VLC log: $it") } }.start()
        }, 6000)
        ui.removeCallbacks(vlcTick)
        ui.postDelayed(vlcTick, 2000)
    }

    /** Zawieszenie przy VLC: czas odtwarzania stoi dłużej niż próg (po starcie: 15 s). */
    private val vlcTick = object : Runnable {
        override fun run() {
            val v = vlc ?: return
            if (prefs.engine != "vlc") return
            ui.postDelayed(this, 1000)
            val now = SystemClock.elapsedRealtime()
            val t = v.time()
            if (t != vlcLastTime) { vlcLastTime = t; vlcLastMove = now; return }
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
            if (recMode || currentUrl == null) return
            val now = SystemClock.elapsedRealtime()
            val lastFeed = analyzer?.lastFeedAt ?: 0L
            if (decoderUnavailable && lastFeed >= lastProbeAt && lastFeed > 0) {
                decoderUnavailable = false
                AppLog.i("dekoder: strumień wznowił się")
                dismissConnectionDialog()
            }
            if (decoderUnavailable) {
                if (!connectionProbePending && now - lastProbeAt >= 3000) probeStalledStream()
                return
            }
            val lastData = lastFeed.takeIf { it > 0 } ?: streamStartAt
            if (lastData <= 0 || now - lastData < 8000) return
            if (!connectionProbePending) probeStalledStream()
        }
    }

    private fun probeStalledStream() {
        if (connectionProbePending) return
        val url = currentUrl ?: return
        val generation = streamGen
        connectionProbePending = true
        lastProbeAt = SystemClock.elapsedRealtime()
        probeIo.execute {
            val result = try { api.probe() } catch (e: java.io.IOException) {
                DecoderProbe.Result(false, "${e.javaClass.simpleName}: ${e.message}")
            } catch (e: IllegalArgumentException) {
                DecoderProbe.Result(false, "${e.javaClass.simpleName}: ${e.message}")
            }
            runOnUiThread {
                connectionProbePending = false
                if (isFinishing || backgrounded || generation != streamGen || currentUrl != url) return@runOnUiThread
                if (!result.reachable) {
                    if (!decoderUnavailable) AppLog.i("dekoder: brak komunikacji (${result.detail})")
                    decoderUnavailable = true
                    if (prefs.blockOnDisconnect) showConnectionDialog(false, result.detail)
                    else if (!connectionDialogShown()) showMessage(getString(R.string.player_unreachable_retry, prefs.host), 4000)
                    return@runOnUiThread
                }
                val wasUnavailable = decoderUnavailable
                decoderUnavailable = false
                dismissConnectionDialog()
                if (wasUnavailable) AppLog.i("dekoder: komunikacja przywrócona")
                val lastData = analyzer?.lastFeedAt?.takeIf { it > 0 } ?: streamStartAt
                if (SystemClock.elapsedRealtime() - lastData >= 8000) {
                    showMessage(getString(R.string.message_receiver_responds_but_no_stream_is_arriving_reconnectin), 6000)
                    if (tsMode) restartTimeshiftRecorder(url, generation) else {
                        failStreak = 0
                        restartPending = false
                        ui.removeCallbacks(restartRunnable)
                        startStream(url, if (recMode) (player?.currentPosition ?: 0) else 0)
                    }
                }
            }
        }
    }

    private fun connectionDialogShown() = connectionDialog?.isShowing == true

    private fun showConnectionDialog(startup: Boolean, detail: String) {
        if (connectionDialogShown()) {
            TvDialog.setMessage(connectionDialog, getString(R.string.player_unreachable_detail, prefs.host, detail))
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
            getString(R.string.player_cannot_connect, prefs.host, detail),
            buttons,
            cancelable = false,
            timeoutSec = 0,
        )
    }

    private fun dismissConnectionDialog() {
        connectionDialog?.let { if (it.isShowing) it.dismiss() }
        connectionDialog = null
    }

    private fun scheduleRestart(reason: String) {
        if (safeMode || currentUrl == null || restartPending || encrypted) return
        if (prefs.engine == "vlc" && vlcRestartAttempts >= 2) {
            AppLog.i("VLC: przerwano automatyczne restarty po $vlcRestartAttempts próbach")
            showRecoveryDialog(getString(R.string.player_vlc_retries, vlcRestartAttempts, reason), stopPlayback = true)
            return
        }
        restartPending = true
        pendingReason = reason
        val delays = longArrayOf(0, 1000, 2000, 5000, 10_000)
        val d = delays[minOf(failStreak, delays.size - 1)]
        failStreak++
        dumpStall(reason)
        AppLog.i("WZNOWIENIE za $d ms: $reason")
        ui.postDelayed(restartRunnable, d)
    }

    private fun doRestart() {
        restartPending = false
        val url = currentUrl ?: return
        if (safeMode) return
        if (decoderUnavailable || connectionProbePending) return
        val lastFeed = analyzer?.lastFeedAt ?: 0L
        val lastData = lastFeed.takeIf { it > 0 } ?: streamStartAt
        val silence = SystemClock.elapsedRealtime() - lastData
        if (silence >= 8000) {
            probeStalledStream()
            return
        }
        if (lastFeed == 0L) return
        restarts++
        lastRestartReason = pendingReason
        if (tsMode) { tsGoLive(pendingReason); return }
        if (prefs.engine == "vlc") vlcRestartAttempts++
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
        showMessage(getString(R.string.player_connecting, prefs.host), 10_000)
        io.execute {
            try {
                while (generation == startupProbeGeneration && !isFinishing) {
                    val result = if (networkUp()) api.probe()
                    else DecoderProbe.Result(false, getString(R.string.player_no_network))
                    if (result.reachable) break
                    AppLog.i("dekoder: brak komunikacji przy starcie (${result.detail})")
                    runOnUiThread {
                        if (generation == startupProbeGeneration && !isFinishing) {
                            showConnectionDialog(true, result.detail)
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
                val bi = bq.indexOfFirst { it.ref == wantB }.let { if (it < 0) 0 else it }
                val ch = if (bq.isEmpty()) emptyList() else api.channels(bq[bi])
                runOnUiThread {
                    bouquets = withRec(bq)
                    if (bq.isEmpty()) {
                        showMessage(getString(R.string.message_no_bouquets_on_receiver), 8000)
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
                    showMessage(getString(R.string.player_load_failed, e.message), 15_000)
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
        tsNewChannel()
        val i = ((index % chList.size) + chList.size) % chList.size
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
        prefs.lastChannel = ch.ref

        failStreak = 0
        ui.removeCallbacks(restartRunnable)
        restartPending = false
        encrypted = false; scrHits = 0; scrTick = 0
        watchdog?.paused = false
        analyzer?.takeScramble()
        AppLog.i("kanał: ${ch.num} ${ch.name} [${bouquets.getOrNull(bIdx)?.name}] ${api.streamUrl(ch)}")
        startStream(api.streamUrl(ch))
        showBanner(ch)
        fetchNow(ch)
    }

    private fun play(index: Int) = playIn(playBouquet, playList, index)

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
            val ch = try { api.channels(bq) } catch (e: Exception) { return@execute }
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

    private fun showPanel() {
        if (bouquets.isEmpty()) return
        // w trakcie nagrania albo po powrocie z niego — lista otwiera się na nagraniach
        if ((recMode || panelOnRec) && bouquets.any { it.ref == REC_REF }) { showRecordings(); return }
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
        panelTimer.setActive(false)
        panel.visibility = View.GONE
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
        if (event.action == KeyEvent.ACTION_DOWN) resetPanelTimer()
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) resetPanelTimer()
        return super.dispatchTouchEvent(event)
    }

    private fun refreshPanel(sel: Int) {
        val bn = bouquets.getOrNull(panelBouquet)
        val sub = if (bn?.ref == REC_REF && recStack.isNotEmpty()) "  ›  " + recDir.trimEnd('/').substringAfterLast('/') else ""
        panelTitle.text = "◀  ${bn?.name ?: ""}$sub  ▶"
        adapter.notifyDataSetChanged()
        if (panelList.isNotEmpty()) list.setSelection(sel.coerceIn(0, panelList.size - 1))
    }

    private fun panelBouquetStep(d: Int) {
        if (bouquets.isEmpty()) return
        val b = ((panelBouquet + d) % bouquets.size + bouquets.size) % bouquets.size
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
                            if (bq.ref == REC_REF) getString(R.string.player_recording_error, error.message ?: error.toString())
                            else getString(R.string.player_bouquet_failed, bq.name, error.message),
                            8000,
                        )
                        return@runOnUiThread
                    }
                    panelList = ch.orEmpty()
                    if (panelList.isEmpty() && bq.ref != REC_REF) showMessage(getString(R.string.player_bouquet_empty, bq.name), 5000)
                    // nagrania: zaznaczone ostatnio oglądane
                    val sel = if (bq.ref == REC_REF) panelList.indexOfFirst { it.rec?.path == recLastPath }.coerceAtLeast(0) else 0
                    refreshPanel(sel)
                    loadPanelEpg()
                }
            }
        }
    }

    private fun loadPanelEpg() {
        val b = bouquets.getOrNull(panelBouquet) ?: return
        if (b.ref == REC_REF) return
        val target = panelList
        io.execute {
            val map = try { api.epgNow(b) } catch (e: Exception) { emptyMap() }
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
        panelOnRec = false          // wybrany kanał TV — lista znów otwiera się na bukiecie kanału
        hidePanel()
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
            val playing = panelBouquet == playBouquet && position == playIndex
            val selected = position == list.selectedItemPosition && list.hasFocus()
            name.text = if (playing) "▶  ${c.name}" else c.name
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
        showMessage(getString(R.string.player_schedule_message, t.channel, t.title), 4000)
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
            showMessage(getString(R.string.message_no_scheduled_switches_add_one_from_programme_details_lo), 6000)
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
        TvDialog.list(this, getString(R.string.message_scheduled_switches), items,
            hint = getString(R.string.player_details_remove_hint), widthFrac = 0.72f,
            onLongPick = { position ->
                historyEntries[position]?.let {
                    ui.post { showZapHistoryEntry(it) }
                    true
                } ?: false
            }) { w -> actions[w]?.invoke() }
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
        showMessage(getString(R.string.player_recording_search, timer.title), 5000)
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

    /** Nowy kanał (albo zmiana ustawień): bufor od zera, odtwarzanie na żywo. */
    private fun tsNewChannel() {
        if (tsMode) tsLeave()
        tsWriteFrom = if (prefs.tsDelay < 0) Long.MAX_VALUE else SystemClock.elapsedRealtime() + prefs.tsDelay * 1000L
        val min = prefs.tsMin
        if (min <= 0) {
            tsBuf?.reset(); tsBuf = null
            return
        }
        val dir = TimeshiftBuffer.dir(this, prefs)
        if (dir == null) {
            // wybrany dysk USB niedostępny, a pamięci urządzenia nie wolno użyć — timeshift wyłączony do czasu podłączenia
            if (tsBuf != null || !tsWarned) {
                tsWarned = true
                AppLog.i("timeshift: nośnik ${prefs.tsDir.ifBlank { "pamięć urządzenia (brak zgody)" }} niedostępny — timeshift wyłączony")
                showMessage(getString(R.string.player_ts_disabled, getString(if (prefs.tsDir.isBlank()) R.string.player_ts_disk_not_selected else R.string.player_ts_usb_unavailable)), 5000)
            }
            tsBuf?.reset(); tsBuf = null
            return
        }
        tsWarned = false
        val b = tsBuf
        if (b == null || b.dir != dir) {
            b?.reset()
            tsBuf = TimeshiftBuffer(dir, min)
            AppLog.i("timeshift: $min min w ${dir.absolutePath} (wolne ${TimeshiftBuffer.gb(dir.usableSpace)})")
        } else {
            b.maxMin = min
            b.reset()
        }
    }

    private var tsWarned = false
    @Volatile private var tsWriteFrom = 0L        // zapis do bufora rusza dopiero po tej chwili (opóźnienie po zmianie kanału)

    /** Przy starcie: pliki bufora z poprzedniego uruchomienia (np. aplikacja przerwana) — kasowane w tle. */
    private fun tsCleanupAtStart() {
        if (tsCleanedAtStart) return
        tsCleanedAtStart = true
        TimeshiftBuffer.cleanupAsync(java.io.File(TimeshiftBuffer.internalBase(this), "timeshift"))
        prefs.tsDir.takeIf { it.isNotBlank() && TimeshiftBuffer.available(it) }?.let {
            TimeshiftBuffer.cleanupAsync(java.io.File(it, "timeshift"))
        }
    }

    /** Wyjście z aplikacji / nagranie: koniec timeshiftu, bufor skasowany (w tle). */
    private fun tsStopAll() {
        if (tsMode) tsLeave()
        tsCtl = false; tsChBar = false
        tsBuf?.reset()
    }

    /** Koniec odtwarzania z bufora (bez uruchamiania strumienia na żywo). */
    private fun tsLeave() {
        tsMode = false
        tsAudioPid = null
        tsSetShifted(false)
        tsCtl = false
        tsRec?.stop(); tsRec = null
        ui.removeCallbacks(shiftTick)
        ui.removeCallbacks(tsApply)
        tsPending = 0
    }

    private fun restartTimeshiftRecorder(url: String, generation: Int) {
        if (!tsMode || tsLiveUrl != url || streamGen != generation) return
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
        showMessage(getString(R.string.message_timeshift_is_not_configured_settings_playback_and_video), 5000)
        return false
    }

    private fun tsEnter(pause: Boolean, backMs: Long) {
        val url = currentUrl
        if (!tsConfigured()) return
        if (recMode) { showMessage(getString(R.string.message_timeshift_is_unavailable_when_playing_a_recording), 3000); return }
        if (url == null) { showMessage(getString(R.string.message_timeshift_no_channel_playing), 3000); return }
        if (tsBuf == null) tsNewChannel()          // bufor mógł nie powstać (np. dysk podłączony po starcie)
        val b = tsBuf
        if (b == null) { showMessage(getString(R.string.message_timeshift_unavailable_usb_drive_unavailable_or_device_s), 5000); return }
        b.failed?.let { showMessage(getString(R.string.player_ts_error, it), 5000); return }
        // bufor jeszcze nie zapisywany (opóźnienie / tryb ręczny) lub prawie pusty: pauza rusza od teraz, cofnięcie niemożliwe
        val fresh = b.total < 2_000_000
        if (fresh && !pause) { showMessage(getString(R.string.message_timeshift_buffer_is_still_empty_nothing_to_rewind), 4000); return }
        tsWriteFrom = 0
        val p = player ?: return
        // obraz na ekranie jest za danymi o zapas odtwarzacza
        val behind = if (vlc != null) prefs.bufferMs.toLong() else p.totalBufferedDuration
        tsAudioPid = vlc?.selectedAudioTrackId() ?: selectedAudioPid()
        val target = if (fresh) SystemClock.elapsedRealtime() else
            (SystemClock.elapsedRealtime() - behind - 300 - backMs).coerceAtLeast(b.oldestWall + 10_000)
        AppLog.i("timeshift: zachowuję ścieżkę audio PID=${tsAudioPid ?: "auto"}")
        AppLog.i("timeshift: ${if (pause) "pauza" else "cofnięcie ${backMs / 1000} s"} (bufor ${b.spanMs() / 60000} min)")
        // strumień na żywo odtwarzacza zatrzymany — zapis przejmuje osobne połączenie
        ui.removeCallbacks(vlcTick)
        proxy?.stop(); proxy = null
        vlc?.release(); vlc = null
        p.stop()
        tsLiveUrl = url
        tsMode = true
        restartTimeshiftRecorder(url, streamGen)
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
            DataSource.Factory { TimeshiftDataSource(b, off, hdr) { } },
            DefaultExtractorsFactory().setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS),
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
            showMessage(getString(R.string.message_live), 2000)
            // ~6 s za końcem bufora: odtwarzacz ma od razu zapas na start (dane z dekodera przychodzą paczkami)
            tsPlayFrom(SystemClock.elapsedRealtime() - 6000, play = true)
            if (showControls) tsCtlOpen() else playList.getOrNull(playIndex)?.let { showBanner(it) }
            return
        }
        tsLeave()
        tsCtl = controlsActive
        AppLog.i("timeshift: na żywo${if (reason.isNotBlank()) " ($reason)" else ""}")
        showMessage(getString(R.string.message_live), 2000)
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
        val span = (tsBuf?.takeIf { it.total > 0 }?.spanMs() ?: 0L)
        bannerClock.text = hm.format(Date())
        bannerNow.text = if (span > 0) getString(R.string.player_ts_live_buffer, fmtMs(span)) else getString(R.string.player_ts_live_empty)
        bannerStart.text = if (span > 0) "−${fmtMs(span)}" else ""
        bannerEnd.text = getString(R.string.player_live_lower)
        bannerProgress.progress = 1000
        bannerNext.text = getString(R.string.player_ts_controls, prefs.tsStepSec, prefs.tsStepMin)
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
        val base = if (tsPending != 0L) tsPending else tsShownWall()
        tsPending = base + delta
        tsShowBanner(sticky = player?.playWhenReady == false)
        ui.removeCallbacks(tsApply)
        ui.postDelayed(tsApply, 700)
    }

    private val tsApply = Runnable {
        val b = tsBuf
        val target = tsPending
        tsPending = 0
        if (b == null || !tsMode || target == 0L) return@Runnable
        val now = SystemClock.elapsedRealtime()
        when {
            target >= now - 6000 -> tsGoLive("przewinięte do końca")
            target < b.oldestWall -> {
                showMessage(getString(R.string.player_ts_start, b.spanMs() / 60000), 2500)
                tsPlayFrom(b.oldestWall + 10_000, play = player?.playWhenReady != false)
            }
            else -> tsPlayFrom(target, play = player?.playWhenReady != false)
        }
    }

    /** Klawisze w trybie timeshiftu. */
    private fun tsKey(code: Int): Boolean {
        if (code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) AppLog.i("timeshift: strzałka $code, pasek kanału=$tsChBar, widoczny=${banner.visibility == View.VISIBLE}")
        // Strzałki przewijają wyłącznie na widocznym pasku timeshiftu.
        if (code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT &&
            !timeshiftNavigationOnBanner(banner.visibility == View.VISIBLE, tsChBar)) return false
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val back = if (code == KeyEvent.KEYCODE_DPAD_LEFT) prefs.tsStepSec * 1000L else prefs.tsStepMin * 60_000L
                if (tsMode) { tsSetShifted(true); tsSeek(-back) } else tsEnter(pause = false, backMs = back)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> {
                val forward = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) prefs.tsStepSec * 1000L else prefs.tsStepMin * 60_000L
                if (tsMode && tsShifted) tsSeek(forward) else { showMessage(getString(R.string.message_already_live), 1500); tsCtlOpen() }
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
        val shown = if (tsPending != 0L) tsPending else tsShownWall()
        val behind = (now - shown).coerceAtLeast(0)
        val span = b.spanMs().coerceAtLeast(1)
        val paused = player?.playWhenReady == false
        bannerClock.text = hm.format(Date(System.currentTimeMillis() - behind))
        bannerNow.text = (if (paused) "⏸  Timeshift" else "▶  Timeshift") + "  −${fmtMs(behind)}"
        bannerStart.text = "−${fmtMs(span)}"
        bannerEnd.text = getString(R.string.player_live_lower)
        bannerProgress.progress = ((span - behind) * 1000 / span).toInt().coerceIn(0, 1000)
        bannerNext.text = getString(R.string.player_ts_controls, prefs.tsStepSec, prefs.tsStepMin)
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
                showMessage(getString(R.string.message_timeshift_buffer_full_resuming_from_the_oldest_recorded), 5000)
                AppLog.i("timeshift: pauza dłuższa niż okno — wznowienie od początku bufora")
                tsPlayFrom(b.oldestWall + 10_000, play = true)
                return
            }
            if (tsShifted && !tsChBar && banner.visibility == View.VISIBLE) {
                tsUpdateBanner()
            }
        }
    }

    // ---------------------------------------------------------------- nagrania z dekodera

    private val REC_REF = REC_BOUQUET
    private var recDir = ""                       // bieżący katalog (puste = domyślny dekodera)
    private val recStack = ArrayList<String>()    // katalogi nadrzędne (powrót „‹ wstecz”)
    private var recMode = false
    private var recItem: Recording? = null
    private var recBannerUntil = 0L
    private var panelOnRec = false                // po powrocie z nagrania lista otwiera się na nagraniach
    private var recLastPath = ""
    private val recPos by lazy { getSharedPreferences("recpos", MODE_PRIVATE) }
    private val dateFmt by lazy { SimpleDateFormat("EEE dd.MM  HH:mm", Locale.forLanguageTag(AppLanguage.selected(this))) }

    /** Bukiet „Nagrania” zawsze na końcu listy bukietów. */
    private fun withRec(bq: List<Bouquet>) = bq.filter { it.ref != REC_REF } +
        (if (REC_REF in prefs.hiddenBouquets) emptyList() else listOf(Bouquet("Nagrania", REC_REF)))

    /** Kanały bukietu albo (dla „Nagrania”) zawartość bieżącego katalogu nagrań. Poza wątkiem UI. */
    private fun channelsOf(bq: Bouquet): List<Channel> {
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

    /** Lista kanałów otwarta na bukiecie „Nagrania”. */
    private fun showRecordings() {
        val i = bouquets.indexOfFirst { it.ref == REC_REF }
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
        TvDialog.list(this, r.title, listOf(TvDialog.Item(getString(R.string.message_play)), TvDialog.Item(getString(R.string.message_delete))),
            hint = getString(R.string.epg_hint_actions), widthFrac = 0.45f) { selected ->
            if (selected == 0) pickRecording(r) else confirmDeleteRecording(r)
        }
    }

    private fun confirmDeleteRecording(r: Recording) {
        TvDialog.confirm(this, getString(R.string.message_delete_recording), "${r.title}\n${r.path}",
            listOf(TvDialog.Btn(getString(R.string.message_delete)) { deleteRecording(r) }))
    }

    private fun deleteRecording(r: Recording) {
        io.execute {
            val result = try { api.movieDelete(r.sref) } catch (e: Exception) { false to (e.message ?: e.toString()) }
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
        if (bouquets.getOrNull(bqIndex)?.ref != REC_REF) return
        io.execute {
            val channels = try { channelsOf(bouquets[bqIndex]) } catch (e: Exception) {
                runOnUiThread { showMessage(getString(R.string.player_recording_error, e.message ?: e.toString()), 6000) }
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
        tsStopAll()
        hidePanel()
        recMode = true
        recItem = r
        panelOnRec = true
        recLastPath = r.path
        failStreak = 0
        ui.removeCallbacks(restartRunnable)
        restartPending = false
        val url = api.fileUrl(r.path)
        AppLog.i("nagranie: ${r.title} (${r.service}) $url od ${fmtMs(startMs)}")
        startStream(url, startMs)
        showRecBanner()
        ui.removeCallbacks(recTick)
        ui.postDelayed(recTick, 500)
    }

    /** Koniec nagrania albo wyjście: zapamiętanie miejsca. */
    private fun leaveRec() {
        if (!recMode) return
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
        recItem?.let { recPos.edit().remove(recPosKey(it)).apply() }
        AppLog.i("nagranie: koniec")
        recMode = false
        recItem = null
        showMessage(getString(R.string.message_end_of_recording), 3000)
        stopPlayback()
        showRecordings()
    }

    private fun recSeek(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, maxOf(0, dur - 1000)))
        showRecBanner()
    }

    private fun recTogglePause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        if (!p.playWhenReady) saveRecPos()
        showRecBanner(sticky = !p.playWhenReady)
    }

    /** Klawisze podczas odtwarzania nagrania. */
    private fun recKey(code: Int, e: KeyEvent): Boolean {
        val fast = e.repeatCount > 3          // przytrzymanie przyspiesza przewijanie
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> recSeek(if (fast) -60_000 else -10_000)
            KeyEvent.KEYCODE_DPAD_RIGHT -> recSeek(if (fast) 120_000 else 30_000)
            KeyEvent.KEYCODE_DPAD_UP -> recSeek(5 * 60_000)
            KeyEvent.KEYCODE_DPAD_DOWN -> recSeek(-5 * 60_000)
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
        bannerClock.text = hm.format(Date())
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
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it > 0 } ?: r.lengthSec * 1000
        val paused = !p.playWhenReady
        bannerNow.text = (if (paused) "⏸  " else "▶  ") + listOf(r.service, if (r.begin > 0) dateFmt.format(Date(r.begin * 1000)) else "")
            .filter { it.isNotBlank() }.joinToString("  •  ")
        bannerStart.text = fmtMs(pos)
        bannerEnd.text = if (dur > 0) "${fmtMs(dur)}   (−${fmtMs(maxOf(0, dur - pos))})" else ""
        bannerProgress.progress = if (dur > 0) (pos * 1000 / dur).toInt().coerceIn(0, 1000) else 0
        bannerNext.text = getString(R.string.player_recording_controls)
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
    private val hideMessage = Runnable { message.visibility = View.GONE }

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
        bannerClock.text = hm.format(Date())
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

    private fun showMessage(t: String, ms: Long = 3000) {
        message.text = t
        message.visibility = View.VISIBLE
        ui.removeCallbacks(hideMessage)
        ui.postDelayed(hideMessage, ms)
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
            analyzer?.let { a ->
                appendLine(getString(R.string.diag_ts_rate, a.bitrateKbps / 1000.0, a.maxGapMs))
                appendLine(getString(R.string.diag_ts_bursts, a.burstAvgMs, a.cleaned, a.tsFixed))
                appendLine(getString(R.string.diag_ts_errors, a.ccErrors, a.teiErrors, a.scrambled, a.syncLoss, a.pcrJumps, a.pmtChanges))
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
                    else showMessage(getString(R.string.message_press_back_again_to_exit), 2500)
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
        if ((tsCtl || (keyCode == KeyEvent.KEYCODE_BACK && tsMode && tsShifted)) && tsKey(keyCode)) {
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
                    showMessage(getString(R.string.message_press_back_again_to_exit), 2500)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (panel.visibility != View.VISIBLE && isOk(keyCode)) {
            suppressOkUp = true
            // widoczny pasek + timeshift: menu przewijania; w pozostałych przypadkach menu główne
            if (prefs.tsMin > 0 && !recMode && banner.visibility == View.VISIBLE) {
                if (tsShifted) tsShowBanner(sticky = player?.playWhenReady == false) else tsCtlOpen()
            } else showQuickMenu()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
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
                tsMode -> showMessage(getString(R.string.message_already_live), 1500)
                prefs.tsMin > 0 -> showMessage(getString(R.string.message_already_live), 1500)
                else -> tsConfigured()
            }
            Act.TS_LIVE -> if (tsMode) tsGoLive() else if (prefs.tsMin <= 0) tsConfigured()
            Act.TIMESHIFT -> when {
                tsShifted -> tsTogglePause()
                tsMode -> { tsSetShifted(true); player?.playWhenReady = false; tsShowBanner(sticky = true) }   // już z bufora
                else -> tsEnter(pause = true, backMs = 0)
            }
        }
    }

    private fun openSettings(onCancel: () -> Unit = {}) {
        PinLock.ask(this, prefs, onCancel = onCancel) {
            settingsOpen = true
            settingsSig = streamSig()
            settingsBq = prefs.hiddenBouquets.toString()
            startActivity(Intent(this, SettingsActivity::class.java))
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
        prefs.engine, prefs.vlcDeint, prefs.vlcHwMode, prefs.vlcChroma, prefs.exoFps, prefs.tsMin, prefs.tsDir, prefs.tsDelay,
    ).joinToString("|")

    override fun onResume() {
        super.onResume()
        if (recreateForLanguage()) return
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
        UiScale.apply(banner, UiScale.pct(prefs.uiBannerFont), UiScale.pct(prefs.uiBannerIcon))
        applyDisplayMode()
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

    private fun checkUpdate(manual: Boolean) = Tools.checkUpdate(this, manual) { showMessage(it, 8000) }



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
        )
        val items = QuickMenu.ordered(prefs).map { getString(it.labelRes) to actions.getValue(it.id) } +
            listOf(getString(R.string.message_settings) to { openSettings() })
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
                if (b.ref == REC_REF) continue
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
            TvDialog.single(this, getString(R.string.message_audio_track_remembered_for_this_channel), labels, selected) { which ->
                val track = tracks.getOrNull(which)
                if (track != null && engine?.selectAudioTrack(track.id) == true) {
                    playList.getOrNull(playIndex)?.let { ch ->
                        prefs.setAudioFor(ch.ref, "${track.id}|${track.language}|${track.codec}")
                        AppLog.i("wybrano audio dla ${ch.name}: ${track.name} (ID ${track.id})")
                    }
                } else {
                    showMessage(getString(R.string.message_could_not_select_audio_track))
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
        TvDialog.single(this, getString(R.string.message_audio_track_remembered_for_this_channel), labels.toList(), sel) { which ->
            selectAudio(tracks[which], remember = true)
        }
    }

    private fun applySavedVlcAudio(attempt: Int = 0) {
        val ch = playList.getOrNull(playIndex) ?: return
        val engine = vlc ?: return
        val tracks = engine.audioTracks()
        if (tracks.isEmpty()) {
            if (attempt < 12) ui.postDelayed({ applySavedVlcAudio(attempt + 1) }, 250)
            else AppLog.i("VLC: brak ścieżek audio po uruchomieniu kanału ${ch.name}")
            return
        }
        val saved = prefs.audioFor(ch.ref).split("|")
        val savedId = saved.firstOrNull()?.substringAfterLast('/')?.toIntOrNull()
        val savedLanguage = saved.getOrElse(1) { "" }
        val savedCodec = saved.getOrElse(2) { "" }
        val match = tracks.firstOrNull { savedId != null && it.id == savedId }
            ?: tracks.firstOrNull {
                savedLanguage.isNotBlank() &&
                    AudioTrackLabels.languageCode(it.language) == AudioTrackLabels.languageCode(savedLanguage) &&
                    (savedCodec.isBlank() || AudioTrackLabels.codecName(it.codec) == AudioTrackLabels.codecName(savedCodec))
            }
        val selectedId = engine.selectedAudioTrackId()
        val preferred = preferredAudioTrackIndex(
            tracks.map { it.language },
            AudioTrackLabels.preferredLanguages(prefs.audioLang),
        )?.let(tracks::get)
        val fallback = preferred ?: tracks.firstOrNull { it.id == selectedId } ?: tracks.firstOrNull()
        val target = match ?: fallback ?: return
        if (selectedId != target.id) {
            if (engine.selectAudioTrack(target.id)) {
                val action = when {
                    match != null -> "przywrócono zapamiętaną"
                    preferred != null -> "ustawiono preferowaną"
                    else -> "ustawiono domyślną"
                }
                AppLog.i("VLC: $action ścieżkę audio dla ${ch.name}: ${target.name} (ID ${target.id})")
            } else {
                AppLog.i("VLC: nie udało się wybrać ścieżki audio dla ${ch.name}: ${target.name} (ID ${target.id})")
            }
        }
    }

    private fun selectAudio(t: AudioTrack, remember: Boolean) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
            .build()
        if (tsMode) tsAudioPid = mpegTsPid(t.format.id)
        val ch = playList.getOrNull(playIndex)
        if (remember && ch != null) {
            val f = t.format
            prefs.setAudioFor(ch.ref, "${f.id ?: ""}|${f.language ?: ""}|${f.sampleMimeType ?: ""}")
            AppLog.i("wybrano audio dla ${ch.name}: ${audioLabel(f)}")
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

    private fun uploadLog(manual: Boolean) = Tools.uploadLog(this, prefs, manual) { showMessage(it, 6000) }



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
