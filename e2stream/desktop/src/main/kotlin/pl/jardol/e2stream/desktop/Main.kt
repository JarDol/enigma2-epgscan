package pl.jardol.e2stream.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.Window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.Window as AwtWindow
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

private const val REPOSITORY_URL = "https://github.com/JarDol/enigma2-epgscan"
private const val REC_PREFIX = "rec:"
private const val REC_UP = ".."
private val testTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun appVersion(): String = System.getProperty("jpackage.app-version") ?: "wersja deweloperska"

fun main() {
    // libVLC zwraca napisy w UTF-8; bez tego JNA czyta je jako cp1250 („ĹšcieĹĽka” zamiast „Ścieżka”).
    System.setProperty("jna.encoding", "UTF-8")
    val remoteDiagnostics = RemoteDiagnostics.fromEnvironment()
    val instance = SingleInstance.startOrSignal() ?: return
    application {
        val startWindowMode = remember { runCatching { DesktopConfig.load().windowMode }.getOrDefault(WindowMode.MAXIMIZED) }
        val mainWindowState = rememberWindowState(
            placement = if (startWindowMode == WindowMode.WINDOW) WindowPlacement.Floating else WindowPlacement.Maximized,
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "E2Stream",
            state = mainWindowState,
        ) {
        val colorScheme = appColorScheme(isSystemInDarkTheme())
        MaterialTheme(colorScheme = colorScheme) {
            val desktopWindow = this.window
            var settingsWindow by remember { mutableStateOf<AwtWindow?>(null) }
            val loadedConfig = remember { runCatching { DesktopConfig.load() } }
            var config by remember { mutableStateOf(loadedConfig.getOrDefault(DesktopConfig())) }
            val navigation = remember { DesktopNavigation() }
            val navigator = remember { SettingsNavigator() }
            val inputMode = remember { InputModeTracker() }
            var status by remember {
                mutableStateOf(
                    loadedConfig.exceptionOrNull()?.let { "Nie udało się odczytać ustawień: ${it.message}" }
                        ?: "Podłącz aplikację do dekodera Enigma2: Menu → Ustawienia → Dekoder Enigma2.",
                )
            }
            var busy by remember { mutableStateOf(false) }
            var catalog by remember { mutableStateOf<DecoderCatalog?>(null) }
            var selectedBouquet by remember { mutableStateOf<Bouquet?>(null) }
            var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
            var nowEvents by remember { mutableStateOf<Map<String, NowEvent>>(emptyMap()) }
            var epgError by remember { mutableStateOf<String?>(null) }
            var pluginStatus by remember { mutableStateOf("nie sprawdzono") }
            var showChannelList by remember { mutableStateOf(false) }
            var showMenu by navigation::showMenu
            var channelListSelected by remember { mutableIntStateOf(0) }
            var captureButton by remember { mutableStateOf<VirtualRemoteKey?>(null) }
            var hidDiagnostic by remember { mutableStateOf("Oczekiwanie na uruchomienie odbiornika HID.") }
            var lircState by remember { mutableStateOf("wyłączony") }
            var piconsBusy by remember { mutableStateOf(false) }
            var piconStatus by remember { mutableStateOf("") }
            var piconVersion by remember { mutableIntStateOf(0) }
            var epgRefresh by remember { mutableIntStateOf(0) }
            var logUploadStatus by remember { mutableStateOf("") }
            val hidDiagnosticDispatcher = remember {
                LatestValueDispatcher<String>(
                    dispatch = { action -> EventQueue.invokeLater(action) },
                    onValue = { hidDiagnostic = it },
                )
            }
            val suppressBrowserKeys = remember { AtomicBoolean(false) }
            val globalPowerKeys = remember { AtomicReference<Set<String>>(emptySet()) }
            val powerDialogOpen = remember { AtomicBoolean(false) }
            var showExitConfirmation by remember { mutableStateOf(false) }
            val bindings = remember { RemoteKeyMap.defaults + RemoteKeyMap.parse(config.remoteBindings) }
            var universalBindings by remember {
                mutableStateOf<Map<VirtualRemoteKey, Set<String>>>(
                    UniversalRemoteMap.parse(config.universalRemoteBindings),
                )
            }
            var vlcSession by remember { mutableStateOf<VlcSession?>(null) }
            var playingChannel by remember { mutableStateOf<Channel?>(null) }
            var previousChannel by remember { mutableStateOf<Channel?>(null) }
            // Kanały, po których przełączają Kanał +/−; lista kanałów może w tym czasie pokazywać inny bukiet lub nagrania.
            var playList by remember { mutableStateOf<List<Channel>>(emptyList()) }
            var recDir by remember { mutableStateOf("") }
            var recStack by remember { mutableStateOf<List<String>>(emptyList()) }
            var recRows by remember { mutableStateOf<Map<String, Recording>>(emptyMap()) }
            var playingRecording by remember { mutableStateOf<Recording?>(null) }
            val recPositions = remember { RecordingPositions() }
            var timeshift by remember { mutableStateOf<TimeshiftSession?>(null) }
            var tsPendingTarget by remember { mutableStateOf<Long?>(null) }
            var tsPendingGeneration by remember { mutableIntStateOf(0) }
            var playbackBarVisible by remember { mutableStateOf(false) }
            var playbackBarGeneration by remember { mutableIntStateOf(0) }
            var playbackTick by remember { mutableIntStateOf(0) }
            val finishedHandler = remember { AtomicReference<(() -> Unit)?>(null) }
            var bannerVisible by remember { mutableStateOf(false) }
            var bannerGeneration by remember { mutableIntStateOf(0) }
            var pinUnlockedUntil by remember { mutableLongStateOf(0L) }
            var testEvents by remember { mutableStateOf<List<RemoteTestEvent>>(emptyList()) }
            var lastTestBackAt by remember { mutableLongStateOf(0L) }
            val scope = rememberCoroutineScope()
            val audioChoices = remember { AudioChoices() }
            val epgRepo = remember { EpgRepository() }
            var epgSyncBusy by remember { mutableStateOf(false) }
            var epgSyncStatus by remember { mutableStateOf("") }
            var epgStartupDone by remember { mutableStateOf(false) }
            var volumeOsdGeneration by remember { mutableIntStateOf(0) }
            var volumeOsdVisible by remember { mutableStateOf(false) }
            val volumeKeyIds = remember { AtomicReference<Set<String>>(emptySet()) }
            val captureActive = remember { AtomicBoolean(false) }
            fun openMenu() = navigation.openMenu(
                DesktopMenuItem.visible(config.menuOrder, config.menuHidden)
                    .filter { it != DesktopMenuItem.SAVE_TIMESHIFT || (timeshift != null && playingRecording == null) },
            )
            LaunchedEffect(config.appLanguage) { AppLanguage.apply(config.appLanguage) }
            var lastVirtualKey by remember { mutableStateOf<VirtualRemoteKey?>(null) }
            var lastVirtualKeyAt by remember { mutableLongStateOf(0L) }
            SideEffect {
                suppressBrowserKeys.set(
                    captureButton != null || universalBindings.values.any { keys -> keys.any { it.startsWith("H:") } },
                )
                globalPowerKeys.set(if (captureButton == null) universalBindings[VirtualRemoteKey.POWER].orEmpty() else emptySet())
                captureActive.set(captureButton != null)
                volumeKeyIds.set(
                    if (!config.volumeKeysApp) emptySet()
                    else listOf(VirtualRemoteKey.VOLUME_UP, VirtualRemoteKey.VOLUME_DOWN, VirtualRemoteKey.MUTE)
                        .flatMap { universalBindings[it].orEmpty() }.filter { it.startsWith("K:") }.toSet(),
                )
            }

            fun recordTest(source: String, physical: String, key: VirtualRemoteKey?) {
                val event = RemoteTestEvent(
                    time = LocalTime.now().format(testTimeFormat),
                    source = source,
                    physical = physical,
                    mapped = key?.label ?: "nieprzypisany",
                )
                testEvents = (listOf(event) + testEvents).take(40)
            }

            fun isRemoteTestOpen(): Boolean =
                navigator.screen == SettingsScreen.REMOTE_TEST && navigator.editor == null && captureButton == null

            val requestPower = {
                if (powerDialogOpen.compareAndSet(false, true)) {
                    SwingUtilities.invokeLater {
                        var confirmationShown = false
                        try {
                            if (isRemoteTestOpen()) recordTest("Power (globalnie)", "przechwycony", VirtualRemoteKey.POWER)
                            val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                            val appWasActive = isForegroundApplication() && belongsToApplication(focused, desktopWindow)
                            remoteDiagnostics.log { "power=request active=$appWasActive" }
                            if (appWasActive) {
                                captureButton = null
                                showExitConfirmation = true
                                confirmationShown = true
                            } else {
                                val target = settingsWindow?.takeIf { it.isShowing } ?: desktopWindow
                                val activated = activateWindow(target)
                                remoteDiagnostics.log { "power=activate success=$activated" }
                                if (!activated) {
                                    status = "Nie udało się przełączyć na okno E2Stream."
                                }
                            }
                        } finally {
                            if (!confirmationShown) powerDialogOpen.set(false)
                        }
                    }
                }
            }
            fun cancelExitConfirmation() {
                showExitConfirmation = false
                powerDialogOpen.set(false)
            }
            fun handleExitConfirmation(key: VirtualRemoteKey?) {
                when (exitConfirmationAction(key)) {
                    ExitConfirmationAction.CONFIRM -> exitApplication()
                    ExitConfirmationAction.CANCEL -> cancelExitConfirmation()
                    ExitConfirmationAction.IGNORE -> Unit
                }
            }
            val currentRequestPower = rememberUpdatedState(requestPower)
            DisposableEffect(instance) {
                instance.onPowerRequest { currentRequestPower.value() }
                onDispose { instance.close() }
            }

            DisposableEffect(Unit) {
                val listener = AWTEventListener { event ->
                    if (event is MouseEvent) when (event.id) {
                        MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_DRAGGED ->
                            inputMode.onMouseMoved(event.xOnScreen, event.yOnScreen)
                        MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_WHEEL -> inputMode.onMouseButton()
                    }
                }
                val toolkit = Toolkit.getDefaultToolkit()
                toolkit.addAWTEventListener(
                    listener,
                    AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK,
                )
                onDispose { toolkit.removeAWTEventListener(listener) }
            }

            LaunchedEffect(selectedBouquet, config.host, config.httpPort, config.username, config.password, epgRefresh) {
                val bouquet = selectedBouquet
                epgError = null
                if (bouquet == null || bouquet.ref == REC_BOUQUET) return@LaunchedEffect
                val client = DecoderClient(config)
                while (true) {
                    try {
                        nowEvents = withContext(Dispatchers.IO) { client.loadNow(bouquet) }
                        epgError = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        nowEvents = emptyMap()
                        epgError = "Nie udało się pobrać EPG: ${e.message ?: "nieznany błąd"}"
                    }
                    delay(60_000)
                }
            }

            LaunchedEffect(playingChannel?.ref, bannerGeneration) {
                if (playingChannel == null) {
                    bannerVisible = false
                    return@LaunchedEffect
                }
                bannerVisible = true
                delay(config.bannerSec * 1000L)
                bannerVisible = false
            }

            LaunchedEffect(showChannelList, channelListSelected, selectedBouquet, config.channelListSec) {
                if (showChannelList && config.channelListSec > 0) {
                    delay(config.channelListSec * 1000L)
                    showChannelList = false
                }
            }

            LaunchedEffect(captureButton) {
                if (captureButton != null) {
                    delay(10_000)
                    captureButton = null
                    status = "Nie naciśnięto klawisza — przypisywanie anulowane."
                }
            }

            /** Rutynowa informacja — tylko do logu, bez paska komunikatu pod obrazem. */
            fun logInfo(message: String) {
                scope.launch(Dispatchers.IO) { AppLog.i(message) }
            }

            fun saveConfig(updated: DesktopConfig, message: String? = null) {
                config = updated
                runCatching { updated.save() }
                    .onSuccess { if (message != null) status = message }
                    .onFailure { status = "Nie udało się zapisać ustawień: ${it.message}" }
            }

            val fullscreen = remember { BorderlessFullscreen(desktopWindow) }
            var windowMode by remember { mutableStateOf(startWindowMode) }
            var lastWindowedMode by remember {
                mutableStateOf(if (startWindowMode == WindowMode.WINDOW) WindowMode.WINDOW else WindowMode.MAXIMIZED)
            }
            fun applyWindowMode(mode: WindowMode) {
                when (mode) {
                    WindowMode.WINDOW, WindowMode.MAXIMIZED -> {
                        fullscreen.exit()
                        val placement = if (mode == WindowMode.WINDOW) WindowPlacement.Floating else WindowPlacement.Maximized
                        mainWindowState.placement = placement
                        desktopWindow.extendedState = if (mode == WindowMode.WINDOW) Frame.NORMAL else Frame.MAXIMIZED_BOTH
                    }
                    WindowMode.FULLSCREEN -> {
                        if (fullscreen.isActive) return
                        desktopWindow.extendedState = Frame.NORMAL
                        mainWindowState.placement = WindowPlacement.Floating
                        runCatching { fullscreen.enter() }
                            .onFailure { status = "Nie udało się włączyć pełnego ekranu: ${it.message}" }
                    }
                }
            }
            fun setWindowMode(mode: WindowMode) {
                if (mode != WindowMode.FULLSCREEN) lastWindowedMode = mode
                windowMode = mode
                applyWindowMode(mode)
                if (config.windowMode != mode) saveConfig(config.copy(windowMode = mode))
            }
            fun toggleFullscreen() {
                setWindowMode(if (windowMode == WindowMode.FULLSCREEN) lastWindowedMode else WindowMode.FULLSCREEN)
            }
            LaunchedEffect(Unit) {
                if (startWindowMode == WindowMode.FULLSCREEN) {
                    while (!desktopWindow.isShowing) delay(50)
                    delay(200)
                    applyWindowMode(WindowMode.FULLSCREEN)
                }
            }
            // Maksymalizacja/przywrócenie z paska tytułu lub Win+strzałki aktualizuje bieżący tryb.
            LaunchedEffect(mainWindowState.placement) {
                if (windowMode == WindowMode.FULLSCREEN) return@LaunchedEffect
                val mode = if (mainWindowState.placement == WindowPlacement.Maximized) WindowMode.MAXIMIZED else WindowMode.WINDOW
                if (mode != windowMode) {
                    windowMode = mode
                    lastWindowedMode = mode
                    if (config.windowMode != mode) saveConfig(config.copy(windowMode = mode))
                }
            }

            LaunchedEffect(config.logDir) {
                AppLog.setDirectory(AppLog.dirFor(config))
            }
            LaunchedEffect(Unit) {
                AppLog.i("E2Stream ${appVersion()} uruchomiony; Java ${System.getProperty("java.version")}, ${System.getProperty("os.name")}")
            }
            LaunchedEffect(status) { withContext(Dispatchers.IO) { AppLog.i(status) } }
            LaunchedEffect(epgError) { epgError?.let { withContext(Dispatchers.IO) { AppLog.i(it) } } }

            suspend fun uploadLog(manual: Boolean) {
                val current = config
                val result = runCatching {
                    withContext(Dispatchers.IO) { DecoderUpload.send(current, DecoderUpload.logName(), AppLog.snapshot()) }
                }
                result.onSuccess { where ->
                    logUploadStatus = "wysłano: $where"
                    if (manual) status = "Log wysłany: $where"
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    AppLog.markDirty()
                    logUploadStatus = "błąd: ${error.message}"
                    if (manual) status = "Nie udało się wysłać logu: ${error.message}"
                }
            }

            LaunchedEffect(config.logFtp, config.host) {
                if (!config.logFtp || config.host.isBlank()) return@LaunchedEffect
                while (true) {
                    delay(120_000)
                    if (AppLog.takeDirty()) uploadLog(manual = false)
                }
            }

            fun visibleBouquets(): List<Bouquet> {
                val all = catalog?.bouquets.orEmpty()
                return all.filter { it.ref !in config.hiddenBouquets }.ifEmpty { all }
            }

            /** Bukiety listy kanałów: widoczne bukiety TV i wirtualny bukiet „Nagrania”. */
            fun listBouquets(): List<Bouquet> {
                val tv = visibleBouquets()
                return if (catalog == null || REC_BOUQUET in config.hiddenBouquets) tv else tv + RECORDINGS_BOUQUET
            }

            // EIT ze strumienia: tunel do VLC (bez timeshiftu) i wersja danych do odświeżenia paska.
            var streamTap by remember { mutableStateOf<StreamTap?>(null) }
            var eitVersion by remember { mutableIntStateOf(0) }
            var eitJob by remember { mutableStateOf<Job?>(null) }

            fun closeTimeshift() {
                streamTap?.let { tap ->
                    streamTap = null
                    scope.launch(Dispatchers.IO) { runCatching { tap.close() } }
                }
                val ts = timeshift ?: return
                timeshift = null
                tsPendingTarget = null
                scope.launch(Dispatchers.IO) { runCatching { ts.close() } }
            }

            fun saveRecordingPosition() {
                val recording = playingRecording ?: return
                val session = vlcSession ?: return
                val position = session.time()
                if (position > 0) recPositions.save(recording.path, position, session.length())
            }

            fun showPlaybackBar() {
                playbackBarGeneration++
                playbackTick++
            }

            var audioApplyJob by remember { mutableStateOf<Job?>(null) }
            /**
             * Zapamiętana dla kanału ścieżka dźwięku, a bez niej — pierwsza w preferowanym języku (jak w Androidzie);
             * ścieżki pojawiają się chwilę po starcie, więc kilka prób.
             */
            fun applySavedAudio() {
                val channel = playingChannel ?: return
                if (playingRecording != null) return
                val saved = audioChoices.get(config.host, channel.ref)
                val preferred = AudioTrackLabels.preferredLanguages(config.audioLang)
                if (saved.isBlank() && preferred.isEmpty()) return
                audioApplyJob?.cancel()
                audioApplyJob = scope.launch {
                    repeat(12) { attempt ->
                        val session = vlcSession ?: return@launch
                        if (playingChannel?.ref != channel.ref) return@launch
                        val tracks = session.audioTracks()
                        val savedId = savedAudioId(saved)
                        // Dopasowanie po języku dopiero, gdy właściwy PID się nie pojawił.
                        val match = if (saved.isNotBlank()) {
                            tracks.firstOrNull { it.id == savedId }
                                ?: if (attempt >= 3) matchAudioChoice(tracks, saved) else null
                        } else if (attempt >= 2 && tracks.isNotEmpty()) {
                            val current = tracks.firstOrNull { it.id == session.selectedAudioTrack() }
                            if (current != null && AudioTrackLabels.languageCode(current.language) in preferred) return@launch
                            preferredAudioTrack(tracks, preferred) ?: return@launch
                        } else null
                        if (match != null) {
                            if (session.selectedAudioTrack() != match.id) {
                                session.selectAudioTrack(match.id)
                                AppLog.i("audio: ${channel.name} → ${match.name}")
                            }
                            return@launch
                        }
                        delay(250)
                    }
                }
            }

            fun sessionOrNew(): VlcSession {
                val session = vlcSession ?: VlcSession { status = it }.also { vlcSession = it }
                session.onFinished = { finishedHandler.get()?.invoke() }
                session.onPlaying = { applySavedAudio() }
                session.setVideoScale(config.videoScaleMode)
                session.setAudioLevel(config.appVolume, config.appMuted)
                return session
            }

            /** Nowa wersja EIT teraz/następne; krótka zwłoka, bo „teraz” i „następne” przychodzą osobno. */
            fun onStreamEit(channel: Channel, now: EitEvent?, next: EitEvent?) {
                if (playingChannel?.ref != channel.ref) return
                eitJob?.cancel()
                eitJob = scope.launch {
                    delay(800)
                    if (playingChannel?.ref != channel.ref) return@launch
                    val events = listOfNotNull(now, next)
                    val persist = config.eitReplace
                    val changes = withContext(Dispatchers.IO) { epgRepo.applyEit(channel.ref, channel.name, events, persist) }
                    for ((old, new) in changes) {
                        logInfo(
                            "EIT: ${channel.name} – " + if (old == null) "nowa audycja ${new.title} ${EpgTime.range(new.begin, new.end)}"
                            else "zmiana: ${old.title} ${EpgTime.range(old.begin, old.end)} → ${new.title} ${EpgTime.range(new.begin, new.end)}",
                        )
                    }
                    now?.let { event ->
                        nowEvents = nowEvents + (serviceKey(channel.ref) to NowEvent(event.title, event.begin, event.end))
                    }
                    eitVersion++
                }
            }

            // Pasek kanału: bieżąca i następna audycja z EPG aplikacji (z EIT), jak w Androidzie.
            var bannerNowNext by remember { mutableStateOf<Pair<NowEvent?, NowEvent?>>(null to null) }
            LaunchedEffect(playingChannel?.ref, bannerGeneration, eitVersion, epgRefresh) {
                val channel = playingChannel
                if (channel == null) {
                    bannerNowNext = null to null
                    return@LaunchedEffect
                }
                val current = config
                fun EpgEvent.asNow() = NowEvent(title, begin, end)
                val local = withContext(Dispatchers.IO) { epgRepo.nowNext(channel.ref) }
                bannerNowNext = local.first?.asNow() to local.second?.asNow()
                if (local.first != null && local.second != null) return@LaunchedEffect
                // Brak w zapisanym EPG — EPG kanału z dekodera (w tle).
                val loaded = withContext(Dispatchers.IO) {
                    runCatching { epgRepo.load(DecoderClient(current), channel.ref) }.getOrNull()
                } ?: return@LaunchedEffect
                if (playingChannel?.ref != channel.ref) return@LaunchedEffect
                val nowS = System.currentTimeMillis() / 1000
                val cur = loaded.firstOrNull { it.begin <= nowS && nowS < it.end }
                val next = loaded.firstOrNull { it.begin >= (cur?.end ?: nowS) }
                bannerNowNext = cur?.asNow() to next?.asNow()
            }

            fun startChannel(channel: Channel) {
                saveRecordingPosition()
                playingRecording = null
                closeTimeshift()
                try {
                    val session = sessionOrNew()
                    val url = DecoderClient(config).streamUrl(channel)
                    val sid = serviceSid(channel.ref)
                    val eit = if (!config.eitStream || sid <= 0) null else EitReader(sid) { now, next ->
                        EventQueue.invokeLater { onStreamEit(channel, now, next) }
                    }
                    val ts = if (config.tsMin <= 0) null else runCatching {
                        TimeshiftSession(
                            TimeshiftBuffer(TimeshiftBuffer.dirFor(config), config.tsMin),
                            url,
                            TimeshiftSession.basicAuth(config),
                            eit?.let { reader -> reader::feed },
                        )
                    }.onFailure { AppLog.i("timeshift: nie udało się uruchomić (${it.message})") }.getOrNull()
                    timeshift = ts
                    val tap = if (ts != null || eit == null) null else runCatching {
                        StreamTap(url, TimeshiftSession.basicAuth(config), eit::feed)
                    }.onFailure { AppLog.i("EIT: tunel strumienia nie działa (${it.message})") }.getOrNull()
                    streamTap = tap
                    session.play(ts?.liveUrl() ?: tap?.url ?: url, config)
                    if (eit != null) scope.launch {
                        delay(15_000)
                        if (playingChannel?.ref == channel.ref && eit.eitPackets == 0L) {
                            logInfo("EIT: dekoder nie wysyła EIT w strumieniu (Enigma2: Include EIT in http streams wyłączone?)")
                        }
                    }
                    val current = playingChannel
                    if (current != null && current.ref != channel.ref) previousChannel = current
                    playingChannel = channel
                    if (selectedBouquet?.ref != REC_BOUQUET && channels.any { it.ref == channel.ref }) playList = channels
                    captureButton = null
                    logInfo("Otwieranie strumienia: ${channel.name}")
                    saveConfig(
                        config.copy(
                            lastBouquetRef = selectedBouquet?.ref?.takeIf { it != REC_BOUQUET } ?: config.lastBouquetRef,
                            lastChannelRef = channel.ref,
                        ),
                    )
                } catch (e: Exception) {
                    closeTimeshift()
                    status = e.message ?: "Nie udało się uruchomić VLC."
                }
            }

            fun playRecording(recording: Recording) {
                saveRecordingPosition()
                closeTimeshift()
                try {
                    val session = sessionOrNew()
                    val start = recPositions.get(recording.path)
                    session.play(DecoderClient(config).recordingUrl(recording.path), config, start)
                    playingChannel?.let { previousChannel = it }
                    playingChannel = null
                    playingRecording = recording
                    bannerVisible = false
                    showPlaybackBar()
                    status = "Nagranie: ${recording.title}" +
                        if (start > 0) " — wznowienie od ${formatDuration(start)}" else ""
                } catch (e: Exception) {
                    status = e.message ?: "Nie udało się odtworzyć nagrania."
                }
            }

            fun stopPlayback(message: String) {
                saveRecordingPosition()
                closeTimeshift()
                showChannelList = false
                vlcSession = null
                playingChannel?.let { previousChannel = it }
                playingChannel = null
                playingRecording = null
                playbackBarVisible = false
                status = message
            }

            fun restartPlayback() {
                val channel = playingChannel ?: return
                vlcSession = null
                startChannel(channel)
            }

            suspend fun loadRecordings(dir: String, select: String? = null): Boolean {
                selectedBouquet = RECORDINGS_BOUQUET
                channels = emptyList()
                nowEvents = emptyMap()
                channelListSelected = 0
                busy = true
                return try {
                    val current = config
                    val (directory, items) = withContext(Dispatchers.IO) { DecoderClient(current).movies(dir) }
                    recDir = directory
                    val rows = buildList {
                        if (recStack.isNotEmpty()) add(Recording(REC_UP, "‹ wstecz", isDir = true))
                        addAll(items)
                    }
                    recRows = rows.associateBy { REC_PREFIX + it.path }
                    var number = 0
                    channels = rows.map { Channel(if (it.isDir) 0 else ++number, it.title, REC_PREFIX + it.path) }
                    val wanted = select ?: playingRecording?.path
                    channelListSelected = channels.indexOfFirst { it.ref == REC_PREFIX + wanted }.coerceAtLeast(0)
                    status = "Nagrania w $directory: $number."
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    channels = emptyList()
                    recRows = emptyMap()
                    status = "Nie udało się pobrać nagrań: ${e.message ?: "nieznany błąd"}"
                    false
                } finally {
                    busy = false
                }
            }

            fun openRecordingDir(row: Recording) {
                if (row.path == REC_UP) {
                    val parent = recStack.lastOrNull() ?: return
                    val child = recDir
                    recStack = recStack.dropLast(1)
                    scope.launch { loadRecordings(parent, select = child) }
                } else {
                    recStack = recStack + recDir
                    scope.launch { loadRecordings(row.path) }
                }
            }

            suspend fun loadBouquetChannels(bouquet: Bouquet): Boolean {
                if (bouquet.ref == REC_BOUQUET) return loadRecordings(recDir)
                val currentCatalog = catalog ?: return false
                selectedBouquet = bouquet
                channels = emptyList()
                nowEvents = emptyMap()
                channelListSelected = 0
                busy = true
                return try {
                    channels = withContext(Dispatchers.IO) {
                        DecoderClient(config).loadChannels(bouquet, currentCatalog.usesPlugin)
                    }
                    logInfo("Kanały w bukiecie „${bouquet.name}”: ${channels.size}.")
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    channels = emptyList()
                    status = e.message ?: "Nie udało się pobrać kanałów."
                    false
                } finally {
                    busy = false
                }
            }

            fun selectBouquet(bouquet: Bouquet) {
                scope.launch { loadBouquetChannels(bouquet) }
            }

            fun connect(autoStart: Boolean) {
                scope.launch {
                    busy = true
                    status = "Łączenie z dekoderem…"
                    val result = try {
                        val current = config
                        withContext(Dispatchers.IO) { DecoderClient(current).loadBouquets() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        status = e.message ?: "Nie udało się połączyć z dekoderem."
                        null
                    } finally {
                        busy = false
                    }
                    if (result == null) return@launch
                    catalog = result
                    pluginStatus = if (result.usesPlugin) "jest — połączono przez EPG Scan" else "brak — połączono przez OpenWebif"
                    logInfo("Połączono przez ${if (result.usesPlugin) "EPG Scan" else "OpenWebif"}; bukiety: ${result.bouquets.size}.")
                    val visible = visibleBouquets()
                    val startFromSettings = autoStart && !config.resumeLast && config.startChannelRef.isNotBlank()
                    val preferredBouquet = if (startFromSettings) config.startBouquetRef else config.lastBouquetRef
                    val bouquet = visible.firstOrNull { it.ref == preferredBouquet } ?: visible.firstOrNull() ?: return@launch
                    if (!loadBouquetChannels(bouquet) || !autoStart) return@launch
                    val target = when {
                        config.resumeLast -> channels.firstOrNull { it.ref == config.lastChannelRef }
                        startFromSettings -> channels.firstOrNull { it.ref == config.startChannelRef } ?: channels.firstOrNull()
                        else -> channels.firstOrNull()
                    }
                    target?.let(::startChannel)
                }
            }

            fun fetchPicons(fresh: Boolean, manual: Boolean) {
                val currentCatalog = catalog
                if (currentCatalog == null) {
                    if (manual) status = "Najpierw połącz się z dekoderem."
                    return
                }
                if (piconsBusy) return
                scope.launch {
                    piconsBusy = true
                    val current = config
                    val bouquets = visibleBouquets()
                    try {
                        val result = withContext(Dispatchers.IO) {
                            if (fresh) Picons.clear()
                            val all = bouquets.flatMap { bouquet ->
                                piconStatus = "Kanały: ${bouquet.name}…"
                                runCatching { DecoderClient(current).loadChannels(bouquet, currentCatalog.usesPlugin) }
                                    .getOrDefault(emptyList())
                            }.distinctBy { serviceKey(it.ref) }
                            all.size to Picons.fetch(current, all) { piconStatus = it }
                        }
                        piconStatus = "ikony: ${result.second}/${result.first} kanałów"
                        piconVersion++
                        saveConfig(config.copy(lastPiconFetchDay = LocalDate.now().toString()))
                        if (manual) status = "Ikony kanałów: ${result.second} z ${result.first}."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        piconStatus = "błąd: ${e.message}"
                        if (manual) status = "Nie udało się pobrać ikon: ${e.message}"
                    } finally {
                        piconsBusy = false
                    }
                }
            }

            LaunchedEffect(catalog) {
                if (catalog == null || !config.picons) return@LaunchedEffect
                val due = config.epgUpdateMode == DesktopConfig.EPG_UPDATE_STARTUP ||
                    config.lastPiconFetchDay != LocalDate.now().toString()
                if (due) fetchPicons(fresh = false, manual = false)
            }

            var audioSyncStatus by remember { mutableStateOf("") }
            var audioSyncBusy by remember { mutableStateOf(false) }
            var audioSyncJob by remember { mutableStateOf<Job?>(null) }

            /** Ścieżki dźwięku z dekoderem (wspólne z Androidem); wymaga wtyczki EPG Scan. */
            fun syncAudio(manual: Boolean) {
                val currentCatalog = catalog
                if (currentCatalog == null || !currentCatalog.usesPlugin) {
                    if (manual) status = "Synchronizacja ścieżek wymaga połączenia przez wtyczkę EPG Scan."
                    return
                }
                if (audioSyncBusy || !config.backupDecision) return
                audioSyncBusy = true
                val current = config
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { syncAudioChoices(current, audioChoices, appVersion()) }
                        audioSyncStatus = "${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))} — $result"
                        AppLog.i("synchronizacja ścieżek dźwięku: $result")
                        if (manual) status = "Ścieżki dźwięku: $result."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        audioSyncStatus = "błąd: ${e.message}"
                        AppLog.i("synchronizacja ścieżek dźwięku: błąd ${e.message}")
                        if (manual) status = "Nie udało się zsynchronizować ścieżek: ${e.message}"
                    } finally {
                        audioSyncBusy = false
                    }
                }
            }

            /** Po zmianie wyboru — wysyłka po chwili (kilka zmian pod rząd to jedna synchronizacja). */
            fun scheduleAudioSync() {
                if (!config.audioSync) return
                audioSyncJob?.cancel()
                audioSyncJob = scope.launch {
                    delay(5_000)
                    syncAudio(manual = false)
                }
            }

            LaunchedEffect(catalog) {
                if (catalog?.usesPlugin == true) syncAudio(manual = false)
            }
            // Kopia ustawień na dekoderze po zmianach (z opóźnieniem — kilka zmian to jeden zapis).
            val backupKey = remember(config) { runCatching { SettingsBackup.backup(config).toString() }.getOrDefault("") }
            LaunchedEffect(backupKey) {
                delay(30_000)
                if (catalog?.usesPlugin == true) syncAudio(manual = false)
            }

            var epgLastAttempt by remember { mutableLongStateOf(0L) }
            var epgStoreCount by remember { mutableIntStateOf(epgRepo.store.count()) }

            fun syncEpg(manual: Boolean) {
                val currentCatalog = catalog
                if (currentCatalog == null) {
                    if (manual) status = "Najpierw połącz się z dekoderem."
                    return
                }
                if (epgSyncBusy) return
                epgSyncBusy = true
                epgLastAttempt = System.currentTimeMillis()
                val current = config
                val bouquets = visibleBouquets()
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            EpgSync.run(
                                current,
                                currentCatalog.usesPlugin,
                                epgRepo.store,
                                channels = {
                                    bouquets.flatMap { bouquet ->
                                        epgSyncStatus = "EPG: kanały ${bouquet.name}…"
                                        runCatching { DecoderClient(current).loadChannels(bouquet, currentCatalog.usesPlugin) }
                                            .getOrDefault(emptyList())
                                    }.filterNot { it.ref.startsWith(REC_PREFIX) }.distinctBy { serviceKey(it.ref) }
                                },
                                manual = manual,
                            ) { epgSyncStatus = it }
                        }
                        epgRepo.invalidate()
                        epgStoreCount = epgRepo.store.count()
                        epgSyncStatus = "EPG: ${result.message}"
                        val now = System.currentTimeMillis()
                        saveConfig(
                            config.copy(
                                lastEpgFetch = now,
                                epgPackIdentity = result.packIdentity ?: config.epgPackIdentity,
                                epgPackCheckAt = now,
                            ),
                        )
                        AppLog.i("epg: ${result.message}")
                        if (manual) status = "EPG: ${result.message}."
                        epgStartupDone = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        epgSyncStatus = "błąd EPG: ${e.message}"
                        AppLog.i("epg: błąd pobierania: ${e.message}")
                        if (manual) status = "Nie udało się pobrać EPG: ${e.message}"
                    } finally {
                        epgSyncBusy = false
                    }
                }
            }

            LaunchedEffect(catalog) {
                val currentCatalog = catalog ?: return@LaunchedEffect
                while (true) {
                    // Po błędzie ponawiaj najwcześniej po 30 minutach.
                    val backoff = System.currentTimeMillis() - epgLastAttempt < 30 * 60_000L
                    if (!backoff && EpgSync.isDue(config, currentCatalog.usesPlugin, epgStartupDone)) syncEpg(manual = false)
                    delay(60_000)
                }
            }

            LaunchedEffect(Unit) {
                if (config.host.isNotBlank() && config.backupDecision) connect(autoStart = true)
            }

            // ---------- Kopia ustawień przy pierwszej konfiguracji (jak w Androidzie) ----------
            var backupCheckRunning by remember { mutableStateOf(false) }
            fun finishBackupDecision(updated: DesktopConfig = config, message: String? = null) {
                saveConfig(updated.copy(backupDecision = true), message)
                connect(autoStart = true)
            }
            fun showChoice(choice: SettingsChoice) {
                if (!navigator.isOpen) navigator.open(SettingsScreen.DECODER)
                navigator.openChoice(choice)
            }
            fun checkInitialBackup() {
                if (backupCheckRunning || config.backupDecision || config.host.isBlank()) return
                backupCheckRunning = true
                val current = config
                status = "Sprawdzam kopie ustawień na dekoderze…"
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            parseDeviceProfiles(DecoderClient(current).syncDevices())
                                .filter { it.backup != null && it.file != "device-${syncDeviceId()}.json" }
                        }
                    }
                    backupCheckRunning = false
                    if (config.host != current.host || config.backupDecision) return@launch
                    result.onFailure { error ->
                        AppLog.i("kopia ustawień: błąd pobierania: ${error.message}")
                        showChoice(
                            SettingsChoice(
                                "Kopia ustawień",
                                listOf("Ponów", "Kontynuuj bez kopii"),
                                0,
                                body = "Nie udało się sprawdzić kopii ustawień (wymaga wtyczki EPG Scan): " +
                                    "${error.message ?: "brak odpowiedzi"}.\n\nMożesz ponowić próbę lub kontynuować bez kopii.",
                            ) { if (it == 0) checkInitialBackup() else finishBackupDecision() },
                        )
                    }.onSuccess { profiles ->
                        if (profiles.isEmpty()) {
                            AppLog.i("kopia ustawień: brak profili na dekoderze")
                            finishBackupDecision()
                            return@onSuccess
                        }
                        AppLog.i("kopia ustawień: znaleziono ${profiles.size} profili")
                        val stamp = DateTimeFormatter.ofPattern("dd.MM HH:mm")
                        val labels = profiles.map { profile ->
                            val kind = if (profile.isWindows) "Windows" else "Android"
                            val time = Instant.ofEpochMilli(profile.updated).atZone(java.time.ZoneId.systemDefault()).format(stamp)
                            "${profile.name.ifBlank { profile.model }}  •  $kind, $time"
                        }
                        showChoice(
                            SettingsChoice(
                                "Znaleziono kopie ustawień",
                                labels + "Nie importuj — ustawienia domyślne",
                                0,
                                body = "Na dekoderze są zapisane profile innych odbiorników. Możesz przenieść ustawienia z jednego z nich " +
                                    "albo zacząć od domyślnych. Import obejmuje też PIN, hasła i token wtyczki; adres dekodera pozostaje bez zmian." +
                                    " Z Androida przenoszone są ustawienia wspólne (porty, logowanie, czasy, wygląd, timeshift, EPG).",
                            ) { index ->
                                val profile = profiles.getOrNull(index)
                                val backup = profile?.backup
                                if (profile == null || backup == null) {
                                    finishBackupDecision()
                                } else {
                                    val restored = SettingsBackup.restore(config, backup)
                                    universalBindings = UniversalRemoteMap.parse(restored.config.universalRemoteBindings)
                                    if (restored.config.windowMode != windowMode) setWindowMode(restored.config.windowMode)
                                    AppLog.i("kopia ustawień: przywrócono ${restored.count} ustawień z ${profile.name}")
                                    finishBackupDecision(
                                        restored.config,
                                        "Przeniesiono ${restored.count} ustawień z „${profile.name.ifBlank { profile.model }}”.",
                                    )
                                }
                            },
                        )
                    }
                }
            }
            LaunchedEffect(config.host, config.pluginPort, config.backupDecision) {
                if (config.host.isBlank() || config.backupDecision) return@LaunchedEffect
                delay(500)
                checkInitialBackup()
            }

            fun savePhysicalBinding(button: VirtualRemoteKey, id: String, label: String) {
                universalBindings = UniversalRemoteMap.assign(universalBindings, button, id)
                saveConfig(
                    config.copy(universalRemoteBindings = UniversalRemoteMap.encode(universalBindings)),
                    "Przypisano $label → ${button.label}.",
                )
                captureButton = null
            }

            fun changeChannel(offset: Int): Boolean {
                val anchor = playingChannel ?: previousChannel ?: return false
                val list = playList.ifEmpty { channels.filterNot { it.ref.startsWith(REC_PREFIX) } }
                val current = list.indexOfFirst { it.ref == anchor.ref }
                if (current < 0 || list.isEmpty()) return false
                val next = (current + offset).mod(list.size)
                startChannel(list[next])
                return true
            }

            fun zap(up: Boolean) {
                changeChannel(if (up != config.invertUpDown) 1 else -1)
            }

            fun openChannelList() {
                if (selectedBouquet == null) {
                    status = "Połącz się z dekoderem: Menu → Ustawienia → Dekoder Enigma2."
                    return
                }
                val playingRef = playingChannel?.ref ?: playingRecording?.let { REC_PREFIX + it.path }
                channelListSelected = channels.indexOfFirst { it.ref == playingRef }.coerceAtLeast(0)
                showChannelList = true
            }

            fun openRecordingsList() {
                scope.launch {
                    if (selectedBouquet?.ref != REC_BOUQUET || channels.isEmpty()) loadRecordings(recDir)
                    else {
                        val playingRef = playingRecording?.let { REC_PREFIX + it.path }
                        channelListSelected = channels.indexOfFirst { it.ref == playingRef }.coerceAtLeast(0)
                    }
                    showChannelList = true
                }
            }

            fun activateListRow(row: Channel) {
                val recording = recRows[row.ref]
                if (row.ref.startsWith(REC_PREFIX) && recording != null) {
                    if (recording.isDir) openRecordingDir(recording)
                    else {
                        showChannelList = false
                        playRecording(recording)
                    }
                } else {
                    showChannelList = false
                    startChannel(row)
                }
            }

            fun togglePlaybackPause() {
                val session = vlcSession ?: return
                session.togglePause()
                if (session.isPaused) saveRecordingPosition()
                if (playingRecording != null || timeshift != null) showPlaybackBar()
                status = if (session.isPaused) "Pauza." else "Odtwarzanie wznowione."
            }

            fun seekRecording(deltaMs: Long) {
                val session = vlcSession ?: return
                val position = session.time()
                if (position < 0) return
                val length = session.length()
                val target = (position + deltaMs).coerceAtLeast(0)
                    .let { if (length > 0) it.coerceAtMost(length - 1000) else it }
                session.seekTo(target)
                if (session.isPaused) session.resume()
                showPlaybackBar()
            }

            fun stopRecordingToList() {
                val recording = playingRecording ?: return
                stopPlayback("Nagranie zatrzymane: ${recording.title}")
                openRecordingsList()
            }

            fun timeshiftShown(ts: TimeshiftSession, session: VlcSession): Long = ts.shownWall(session.time())

            fun timeshiftShifted(): Boolean {
                val ts = timeshift ?: return false
                val session = vlcSession ?: return false
                return session.isPaused || monotonicMs() - timeshiftShown(ts, session) > LIVE_MARGIN_MS
            }

            fun timeshiftPlay(target: TimeshiftTarget) {
                val ts = timeshift ?: return
                val session = vlcSession ?: return
                val url = when (target) {
                    TimeshiftTarget.Live -> ts.liveUrl()
                    is TimeshiftTarget.At -> ts.urlAt(target.wall)
                }
                session.play(url, config)
                showPlaybackBar()
            }

            fun timeshiftGoLive() {
                if (timeshift == null) return
                tsPendingTarget = null
                if (!timeshiftShifted()) {
                    status = "Już na żywo."
                    showPlaybackBar()
                    return
                }
                timeshiftPlay(TimeshiftTarget.Live)
                status = "Na żywo."
            }

            fun timeshiftSeekBy(deltaMs: Long) {
                val ts = timeshift
                val session = vlcSession
                if (ts == null || session == null) {
                    status = "Timeshift jest wyłączony — Ustawienia → Odtwarzanie i obraz → Timeshift."
                    return
                }
                val now = monotonicMs()
                val base = tsPendingTarget ?: timeshiftShown(ts, session)
                if (deltaMs > 0 && now - base <= LIVE_MARGIN_MS) {
                    status = "Już na żywo."
                    showPlaybackBar()
                    return
                }
                tsPendingTarget = (base + deltaMs).coerceAtMost(now)
                tsPendingGeneration++
                showPlaybackBar()
            }

            LaunchedEffect(tsPendingGeneration) {
                if (tsPendingGeneration == 0) return@LaunchedEffect
                delay(700)
                val target = tsPendingTarget ?: return@LaunchedEffect
                val ts = timeshift ?: return@LaunchedEffect
                tsPendingTarget = null
                when (val resolved = timeshiftTarget(target, monotonicMs(), ts.buffer.oldestWall)) {
                    TimeshiftTarget.Live -> {
                        timeshiftPlay(resolved)
                        status = "Na żywo."
                    }
                    is TimeshiftTarget.At -> {
                        timeshiftPlay(resolved)
                        if (resolved.clampedToOldest) status = "Początek bufora timeshiftu."
                    }
                }
            }

            LaunchedEffect(playbackBarGeneration) {
                if (playbackBarGeneration == 0) return@LaunchedEffect
                playbackBarVisible = true
                delay(config.tsBannerSec * 1000L)
                while (vlcSession?.isPaused == true || tsPendingTarget != null) delay(500)
                playbackBarVisible = false
            }

            LaunchedEffect(vlcSession, playingRecording, timeshift) {
                if (vlcSession == null || (playingRecording == null && timeshift == null)) return@LaunchedEffect
                var lastSave = monotonicMs()
                while (true) {
                    delay(500)
                    playbackTick++
                    val session = vlcSession ?: break
                    if (playingRecording != null && !session.isPaused && monotonicMs() - lastSave > 15_000) {
                        lastSave = monotonicMs()
                        saveRecordingPosition()
                    }
                    val ts = timeshift ?: continue
                    val failure = ts.buffer.failed
                    val channel = playingChannel
                    if (failure != null) {
                        closeTimeshift()
                        status = "Timeshift wyłączony: $failure"
                        if (channel != null) runCatching { session.play(DecoderClient(config).streamUrl(channel), config) }
                        break
                    }
                    if (ts.buffer.trimmed && tsPendingTarget == null &&
                        timeshiftShown(ts, session) < ts.buffer.oldestWall + 5_000
                    ) {
                        timeshiftPlay(TimeshiftTarget.At(ts.buffer.oldestWall + 10_000, clampedToOldest = true))
                        status = "Bufor timeshiftu pełny — odtwarzanie od najstarszego miejsca."
                    }
                }
            }

            SideEffect {
                finishedHandler.set {
                    val recording = playingRecording
                    when {
                        recording != null -> {
                            recPositions.remove(recording.path)
                            playingRecording = null
                            stopPlayback("Koniec nagrania: ${recording.title}")
                            openRecordingsList()
                        }
                        timeshift != null -> timeshiftPlay(
                            if (timeshiftShifted()) TimeshiftTarget.At(timeshift!!.buffer.oldestWall + 10_000, true)
                            else TimeshiftTarget.Live,
                        )
                    }
                }
            }

            // ---------- Watchdog: zawieszony obraz i brak dekodera (jak w Androidzie) ----------
            var decoderDown by remember { mutableStateOf(false) }
            val restartBackoff = remember { RestartBackoff() }
            /** Ponowne otwarcie bieżącego odtwarzania; timeshift zachowuje bufor i miejsce. */
            fun reopenPlayback(reason: String) {
                logInfo("watchdog: $reason")
                val ts = timeshift
                val session = vlcSession
                val recording = playingRecording
                when {
                    recording != null -> {
                        saveRecordingPosition()
                        playRecording(recording)
                    }
                    ts != null && session != null && ts.buffer.failed == null -> {
                        val url = if (timeshiftShifted()) ts.urlAt(timeshiftShown(ts, session)) else ts.liveUrl()
                        session.play(url, config)
                    }
                    else -> restartPlayback()
                }
            }
            LaunchedEffect(vlcSession, playingChannel?.ref, playingRecording?.path, config.watchdog, config.stallSec) {
                val session = vlcSession ?: return@LaunchedEffect
                if (!config.watchdog || (playingChannel == null && playingRecording == null)) return@LaunchedEffect
                val watch = StallWatch(config.stallSec * 1000L)
                watch.reset(monotonicMs())
                var wasDown = false
                while (true) {
                    delay(1000)
                    val now = monotonicMs()
                    if (decoderDown) {
                        wasDown = true
                        continue
                    }
                    if (wasDown) {
                        wasDown = false
                        watch.reset(now)
                    }
                    val time = session.time()
                    if (!watch.check(now, time, session.isPaused || tsPendingTarget != null)) {
                        if (time > 0 && !session.isPaused) restartBackoff.playing(now)
                        continue
                    }
                    restartBackoff.stalled()
                    val current = config
                    val reachable = withContext(Dispatchers.IO) { probeDecoder(current) }
                    if (!reachable) {
                        logInfo("dekoder: brak komunikacji — czekam na powrót (co 3 s)")
                        decoderDown = true
                        continue
                    }
                    val wait = restartBackoff.nextDelay()
                    delay(wait)
                    reopenPlayback("obraz stoi — ponowne otwarcie strumienia (próba ${restartBackoff.streak})")
                    watch.reset(monotonicMs())
                }
            }
            LaunchedEffect(decoderDown) {
                if (!decoderDown) return@LaunchedEffect
                runCatching { vlcSession?.stop() }
                var attempt = 0
                while (decoderDown) {
                    attempt++
                    status = "Brak komunikacji z dekoderem ${config.host} — ponawiam co 3 s (próba $attempt)."
                    delay(3000)
                    val current = config
                    if (withContext(Dispatchers.IO) { probeDecoder(current) }) {
                        decoderDown = false
                        logInfo("dekoder: komunikacja przywrócona — wznawiam")
                        status = "Dekoder znów odpowiada — wznawiam odtwarzanie."
                        val recording = playingRecording
                        val channel = playingChannel
                        when {
                            recording != null -> playRecording(recording)
                            channel != null -> startChannel(channel)
                        }
                    }
                }
            }

            fun openSettings(vararg path: SettingsScreen) {
                fun show() {
                    showMenu = false
                    showChannelList = false
                    navigator.open(path.firstOrNull() ?: SettingsScreen.SECTIONS)
                    path.drop(1).forEach { navigator.push(it) }
                }
                if (config.pin.isEmpty() || System.currentTimeMillis() < pinUnlockedUntil) {
                    show()
                    return
                }
                showMenu = false
                navigator.openEditor(
                    SettingsEditor(
                        title = "Podaj PIN do ustawień",
                        initial = "",
                        secret = true,
                        digitsOnly = true,
                        maxLength = 6,
                    ) { value ->
                        if (value != config.pin) "Błędny PIN." else {
                            pinUnlockedUntil = if (config.pinMin <= 0) Long.MAX_VALUE
                            else System.currentTimeMillis() + config.pinMin * 60_000L
                            show()
                            null
                        }
                    },
                )
            }

            fun recall() {
                val previous = previousChannel
                if (previous == null) status = "Brak poprzedniego kanału." else startChannel(previous)
            }

            fun unavailable(feature: String) {
                status = "$feature nie jest jeszcze dostępne w wersji Windows."
            }

            var quickPick by remember { mutableStateOf<QuickPick?>(null) }
            fun pickVideoScale() {
                val modes = VideoScaleMode.entries
                quickPick = QuickPick("Obraz — skalowanie", modes.map { it.label }, modes.indexOf(config.videoScaleMode)) {
                    saveConfig(config.copy(videoScaleMode = modes[it]), "Skalowanie obrazu: ${modes[it].label}.")
                    vlcSession?.setVideoScale(modes[it])
                }
            }
            fun pickWindowMode() {
                val modes = WindowMode.entries
                quickPick = QuickPick("Okno / pełny ekran", modes.map { it.label }, modes.indexOf(windowMode)) {
                    setWindowMode(modes[it])
                }
            }

            // ---------- Harmonogramy ----------

            val zapStore = remember { ZapTimerStore() }
            var zapPrompt by remember { mutableStateOf<ZapTimer?>(null) }

            fun openZapTimers() {
                scope.launch {
                    val days = config.zapHistoryDays
                    val (timers, history) = withContext(Dispatchers.IO) { zapStore.list() to zapStore.history(days) }
                    if (timers.isEmpty() && history.isEmpty()) {
                        status = "Brak zaplanowanych przełączeń — dodasz je z opisu audycji w EPG (Info lub EPG kanału)."
                        return@launch
                    }
                    val headers = buildMap {
                        if (timers.isNotEmpty()) put(0, "PRZYSZŁE WYDARZENIA")
                        if (history.isNotEmpty()) put(timers.size, "ZREALIZOWANE")
                    }
                    quickPick = QuickPick(
                        title = tr("Przełączenia wg harmonogramu"),
                        options = timers.map { "${it.channel} — ${it.title}" } + history.map { "${it.timer.channel} — ${it.timer.title}" },
                        selected = 0,
                        subtitles = timers.map { formatTimerRange(it.begin, it.end) } +
                            history.map { "${formatTimerRange(it.timer.begin, it.timer.end)}  •  ${ZapOutcome.label(it.outcome)}" },
                        headers = headers,
                    ) { index ->
                        val timer = timers.getOrNull(index)
                        val entry = history.getOrNull(index - timers.size)
                        val title = timer?.let { "${it.channel} — ${it.title}" } ?: entry?.let { "${it.timer.channel} — ${it.timer.title}" }
                            ?: return@QuickPick
                        quickPick = QuickPick(
                            title = title,
                            options = listOf(if (timer != null) "Usuń przełączenie" else "Usuń z historii", "Wróć do listy"),
                            selected = 1,
                            onBack = ::openZapTimers,
                        ) { choice ->
                            if (choice != 0) openZapTimers()
                            else scope.launch {
                                withContext(Dispatchers.IO) {
                                    if (timer != null) zapStore.remove(timer) else entry?.let(zapStore::removeHistory)
                                }
                                status = if (timer != null) "Usunięto przełączenie: ${timer.title}." else "Usunięto z historii."
                                openZapTimers()
                            }
                        }
                    }
                }
            }

            fun openDecoderTimers() {
                if (config.host.isBlank()) {
                    status = "Połącz się z dekoderem: Menu → Ustawienia → Dekoder Enigma2."
                    return
                }
                status = "Pobieranie harmonogramów z dekodera…"
                scope.launch {
                    val current = config
                    val result = withContext(Dispatchers.IO) { runCatching { DecoderClient(current).decoderTimers() } }
                    val timers = result.getOrElse {
                        status = "Nie udało się pobrać harmonogramów dekodera: ${it.message}"
                        return@launch
                    }
                    if (timers.isEmpty()) {
                        status = "Brak harmonogramów na dekoderze."
                        return@launch
                    }
                    val now = Instant.now().epochSecond
                    val upcoming = timers.filter { it.end > now }
                    val done = timers.filter { it.end <= now }.sortedByDescending { it.begin }
                    val all = upcoming + done
                    fun describe(timer: DecoderTimer) = formatTimerRange(timer.begin, timer.end) +
                        (if (timer.switchOnly) "  •  przełączenie" else "  •  nagranie") +
                        (if (timer.disabled) "  •  wyłączony" else "")
                    status = "Harmonogramy dekodera: ${upcoming.size} przyszłych, ${done.size} zrealizowanych."
                    quickPick = QuickPick(
                        title = tr("Harmonogramy dekodera"),
                        options = all.map { "${it.channel} — ${it.title}" },
                        selected = 0,
                        subtitles = all.map(::describe),
                        headers = buildMap {
                            if (upcoming.isNotEmpty()) put(0, "PRZYSZŁE")
                            if (done.isNotEmpty()) put(upcoming.size, "ZREALIZOWANE")
                        },
                    ) { index ->
                        val timer = all[index]
                        quickPick = QuickPick(
                            title = "${timer.channel} — ${timer.title}",
                            options = listOf("Usuń z dekodera", "Wróć do listy"),
                            selected = 1,
                            subtitles = listOf(describe(timer), timer.description.ifBlank { null }),
                            onBack = ::openDecoderTimers,
                        ) { choice ->
                            if (choice != 0) openDecoderTimers()
                            else quickPick = QuickPick(
                                title = "Usunąć z dekodera?",
                                options = listOf("Tak — usuń", "Nie"),
                                selected = 1,
                                subtitles = listOf("${timer.channel} — ${timer.title}", null),
                                onBack = ::openDecoderTimers,
                            ) { confirm ->
                                if (confirm != 0) openDecoderTimers()
                                else scope.launch {
                                    val (ok, message) = withContext(Dispatchers.IO) {
                                        runCatching { DecoderClient(current).timerDelete(timer) }
                                            .getOrElse { false to (it.message ?: "błąd połączenia") }
                                    }
                                    status = if (ok) "Usunięto z dekodera: ${timer.title}." else "Nie usunięto: $message"
                                    openDecoderTimers()
                                }
                            }
                        }
                    }
                }
            }

            fun fireZap(timer: ZapTimer) {
                if (quickPick?.tag == timer.key) quickPick = null
                zapPrompt = null
                scope.launch { withContext(Dispatchers.IO) { zapStore.complete(timer, ZapOutcome.SWITCHED) } }
                val channel = channels.firstOrNull { serviceKey(it.ref) == serviceKey(timer.ref) } ?: Channel(0, timer.channel, timer.ref)
                showChannelList = false
                startChannel(channel)
                status = "Przełączono wg harmonogramu: ${timer.channel} — ${timer.title}"
            }

            fun recordZap(timer: ZapTimer) {
                if (quickPick?.tag == timer.key) quickPick = null
                zapPrompt = null
                val current = config
                scope.launch {
                    val (ok, message) = withContext(Dispatchers.IO) {
                        runCatching { DecoderClient(current).timerAddRecording(timer.ref, timer.title, timer.begin, timer.end, timer.eventId) }
                            .getOrElse { false to (it.message ?: "błąd połączenia") }
                    }
                    withContext(Dispatchers.IO) { zapStore.complete(timer, if (ok) ZapOutcome.RECORDED else ZapOutcome.RECORD_FAILED) }
                    status = if (ok) "Nagranie zaplanowane na dekoderze: ${timer.title}." else "Nie udało się zaplanować nagrania: $message"
                }
            }

            fun zapCountdown(timer: ZapTimer, now: Long): QuickPick {
                val left = (timer.begin - now).coerceAtLeast(0)
                return QuickPick(
                    title = "Przełączenie wg harmonogramu",
                    options = listOf("Przełącz (%d:%02d)".format(left / 60, left % 60), "Nagraj na dekoderze"),
                    selected = quickPick?.takeIf { it.tag == timer.key }?.selected ?: 0,
                    subtitles = listOf("${timer.channel} — ${timer.title}", formatTimerRange(timer.begin, timer.end)),
                    autoClose = false,
                    tag = timer.key,
                    onBack = {
                        zapPrompt = null
                        scope.launch { withContext(Dispatchers.IO) { zapStore.complete(timer, ZapOutcome.SKIPPED) } }
                        status = "Pominięto przełączenie: ${timer.title}."
                    },
                ) { if (it == 0) fireZap(timer) else recordZap(timer) }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    delay(1_000)
                    val now = Instant.now().epochSecond
                    val timers = withContext(Dispatchers.IO) { zapStore.list() }
                    val prompt = zapPrompt
                    if (prompt != null) {
                        when {
                            timers.none { it.key == prompt.key } -> {
                                zapPrompt = null
                                if (quickPick?.tag == prompt.key) quickPick = null
                            }
                            now >= prompt.begin -> fireZap(prompt)
                            quickPick?.tag == prompt.key -> quickPick = zapCountdown(prompt, now)
                        }
                        continue
                    }
                    // Audycje, które zaczęły się dawno temu (aplikacja była wyłączona), trafiają do zrealizowanych.
                    val missed = timers.filter { now - it.begin > 120 }
                    if (missed.isNotEmpty()) {
                        withContext(Dispatchers.IO) { missed.forEach { zapStore.complete(it, ZapOutcome.SKIPPED) } }
                    }
                    val due = timers.firstOrNull { it.begin - now in -120L..30L } ?: continue
                    if (now >= due.begin) fireZap(due)
                    else {
                        zapPrompt = due
                        quickPick = zapCountdown(due, now)
                    }
                }
            }

            // ---------- Głośność i ścieżka dźwięku ----------

            fun setAppVolume(volume: Int, muted: Boolean) {
                val value = volume.coerceIn(0, DesktopConfig.MAX_VOLUME)
                saveConfig(config.copy(appVolume = value, appMuted = muted))
                vlcSession?.setAudioLevel(value, muted)
                volumeOsdGeneration++
            }

            fun changeVolume(direction: Int) = setAppVolume(config.appVolume + direction * config.volumeStep, false)

            fun toggleMute() = setAppVolume(config.appVolume, !config.appMuted)

            /**
             * Wybór ścieżki dźwięku. Z ustawień ([inSettings]) — jako wybór w oknie ustawień, więc „Wróć”
             * wraca do listy ustawień; z odtwarzacza — jako osobne okno.
             */
            fun showAudioPick(inSettings: Boolean = false) {
                val session = vlcSession
                if (session == null) {
                    status = "Nic nie jest odtwarzane."
                    return
                }
                val tracks = session.audioTracks()
                if (tracks.isEmpty()) {
                    status = "Strumień nie podał jeszcze ścieżek dźwięku — spróbuj za chwilę."
                    return
                }
                val channel = playingChannel.takeIf { playingRecording == null }
                val current = session.selectedAudioTrack()
                val title = if (channel != null) "Ścieżka dźwięku (zapamiętywana dla kanału)" else "Ścieżka dźwięku"
                val selected = tracks.indexOfFirst { it.id == current }.coerceAtLeast(0)
                val onSelect = select@{ index: Int ->
                    val track = tracks[index]
                    audioApplyJob?.cancel()
                    if (!session.selectAudioTrack(track.id)) {
                        status = "VLC nie przełączył ścieżki dźwięku."
                        return@select
                    }
                    if (channel != null) {
                        audioChoices.set(config.host, channel.ref, track)
                        scheduleAudioSync()
                    }
                    status = "Dźwięk: ${track.name}" + if (channel != null) " — zapamiętano dla ${channel.name}." else "."
                }
                if (inSettings) {
                    navigator.openChoice(
                        SettingsChoice(title, tracks.map { if (it.id == current) "${it.name}  (odtwarzana)" else it.name }, selected, onSelect = onSelect),
                    )
                    return
                }
                quickPick = QuickPick(
                    title = title,
                    options = tracks.map { it.name },
                    selected = selected,
                    subtitles = tracks.map { if (it.id == current) "odtwarzana" else null },
                ) { index -> onSelect(index) }
            }

            // ---------- EPG ----------

            val epgScale = config.uiEpgFont / 100f

            fun epgChannelFor(ref: String, name: String): Channel =
                (playList + channels).firstOrNull { serviceKey(it.ref) == serviceKey(ref) } ?: Channel(0, name, ref)

            suspend fun loadEpg(channel: Channel): List<EpgEvent>? {
                val current = config
                return withContext(Dispatchers.IO) { runCatching { epgRepo.load(DecoderClient(current), channel.ref) } }
                    .getOrElse {
                        status = "Nie udało się pobrać EPG: ${it.message ?: "błąd połączenia"}"
                        null
                    }
            }

            fun recordEvent(event: EpgEvent, channel: Channel, back: (() -> Unit)?) {
                quickPick = QuickPick(
                    title = "Nagrać na dekoderze?",
                    options = listOf("Nagraj", "Anuluj"),
                    selected = 0,
                    body = "${channel.name} — ${event.title}\n${EpgTime.dayAndRange(event.begin, event.end)}",
                    onBack = back,
                    fontScale = epgScale,
                ) { choice ->
                    if (choice != 0) {
                        back?.invoke()
                        return@QuickPick
                    }
                    val current = config
                    scope.launch {
                        val (ok, message) = withContext(Dispatchers.IO) {
                            runCatching { DecoderClient(current).timerAddRecording(channel.ref, event.title, event.begin, event.end, event.id) }
                                .getOrElse { false to (it.message ?: "błąd połączenia") }
                        }
                        status = if (ok) "Nagranie zaplanowane na dekoderze: ${event.title}."
                        else "Nie udało się zaplanować nagrania: $message"
                    }
                }
            }

            fun scheduleZap(event: EpgEvent, channel: Channel, back: (() -> Unit)?) {
                val timer = ZapTimer(channel.ref, channel.name, event.title, event.begin, event.end, event.id)
                val time = EpgTime.clock(event.begin)
                scope.launch {
                    val collisions = withContext(Dispatchers.IO) {
                        zapStore.list().filter { it.begin == event.begin && it.key != timer.key }
                    }
                    if (collisions.isEmpty()) {
                        withContext(Dispatchers.IO) { zapStore.add(timer) }
                        status = "Przełączenie o $time: ${channel.name} — ${event.title}."
                        return@launch
                    }
                    val other = collisions.first()
                    quickPick = QuickPick(
                        title = "O $time jest już zaplanowane przełączenie",
                        options = listOf("Zamień na nowe", "Zostaw dotychczasowe", "Nagraj nowe na dekoderze"),
                        selected = 0,
                        body = "Zaplanowane: ${other.channel} — ${other.title}\nNowe: ${channel.name} — ${event.title}",
                        onBack = back,
                        fontScale = epgScale,
                    ) { choice ->
                        when (choice) {
                            0 -> scope.launch {
                                withContext(Dispatchers.IO) { zapStore.replaceAt(timer) }
                                status = "Przełączenie o $time zamienione: ${channel.name} — ${event.title}."
                            }
                            1 -> status = "Zostawiono przełączenie: ${other.channel} — ${other.title}."
                            else -> recordEvent(event, channel, back)
                        }
                    }
                }
            }

            // Okna EPG odwołują się do siebie nawzajem; implementacje są przypisywane niżej.
            var showChannelEpgRef: (Channel, Int?) -> Unit = { _, _ -> }
            var showMultiEpgRef: (Bouquet?, Long?, String?) -> Unit = { _, _, _ -> }
            var openEpgSearchRef: (String) -> Unit = {}
            fun showChannelEpg(channel: Channel, selectEventId: Int? = null) = showChannelEpgRef(channel, selectEventId)
            fun showMultiEpg(bouquet: Bouquet? = null, at: Long? = null, selectRef: String? = null) =
                showMultiEpgRef(bouquet, at, selectRef)
            fun openEpgSearch(initial: String = "") = openEpgSearchRef(initial)

            fun showEvent(event: EpgEvent, channel: Channel, onBack: (() -> Unit)?) {
                scope.launch {
                    val now = Instant.now().epochSecond
                    val scheduled = withContext(Dispatchers.IO) { zapStore.list().firstOrNull { it.key == ZapTimer(channel.ref, "", "", event.begin, 0).key } }
                    val isCurrent = playingChannel?.let { serviceKey(it.ref) == serviceKey(channel.ref) } == true
                    val reopen = { showEvent(event, channel, onBack) }
                    val actions = buildList<Pair<String, () -> Unit>> {
                        add(
                            (if (isCurrent) "Oglądaj" else "Przełącz na kanał") to {
                                showChannelList = false
                                if (!isCurrent) startChannel(channel)
                            },
                        )
                        if (event.end > now) add("Nagraj na dekoderze" to { recordEvent(event, channel, reopen) })
                        if (event.begin > now) {
                            if (scheduled != null) {
                                add(
                                    "Usuń przełączenie o ${EpgTime.clock(event.begin)}" to {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { zapStore.remove(scheduled) }
                                            status = "Usunięto przełączenie: ${event.title}."
                                        }
                                    },
                                )
                            } else {
                                add("Przełącz o ${EpgTime.clock(event.begin)}" to { scheduleZap(event, channel, reopen) })
                            }
                        }
                        if (onBack == null) add("EPG kanału" to { showChannelEpg(channel, event.id) })
                    }
                    val header = EpgTime.dayAndRange(event.begin, event.end) +
                        (EpgTime.progress(event, now)?.let { "  •  $it" } ?: "") +
                        (if (scheduled != null) "  •  zaplanowane przełączenie" else "")
                    quickPick = QuickPick(
                        title = "${channel.name} — ${event.title}",
                        options = actions.map { it.first },
                        selected = 0,
                        body = listOf(header, epgDescription(event)).filter { it.isNotBlank() }.joinToString("\n\n"),
                        onBack = onBack,
                        fontScale = epgScale,
                    ) { actions[it].second() }
                }
            }

            fun showCurrentEvent() {
                val channel = playingChannel
                if (channel == null) {
                    status = "Nie odtwarzasz żadnego kanału."
                    return
                }
                scope.launch {
                    val events = loadEpg(channel) ?: return@launch
                    val now = Instant.now().epochSecond
                    val event = events.firstOrNull { it.begin <= now && it.end > now } ?: events.firstOrNull { it.end > now }
                    if (event == null) status = "Brak EPG dla kanału ${channel.name}." else showEvent(event, channel, null)
                }
            }

            fun pickEpgChannel(then: (Channel) -> Unit) {
                val currentCatalog = catalog
                val bouquets = visibleBouquets()
                if (currentCatalog == null || bouquets.isEmpty()) {
                    status = "Najpierw połącz się z dekoderem."
                    return
                }
                quickPick = QuickPick(
                    title = "EPG — wybierz bukiet",
                    options = bouquets.map { it.name },
                    selected = bouquets.indexOfFirst { it.ref == selectedBouquet?.ref }.coerceAtLeast(0),
                ) { index ->
                    val bouquet = bouquets[index]
                    val current = config
                    scope.launch {
                        val list = if (bouquet.ref == selectedBouquet?.ref && channels.isNotEmpty()) channels
                        else withContext(Dispatchers.IO) {
                            runCatching { DecoderClient(current).loadChannels(bouquet, currentCatalog.usesPlugin) }.getOrNull()
                        }
                        if (list.isNullOrEmpty()) {
                            status = "Brak kanałów w bukiecie „${bouquet.name}”."
                            return@launch
                        }
                        quickPick = QuickPick(
                            title = "EPG — ${bouquet.name}",
                            options = list.map { "${it.number}  ${it.name}" },
                            selected = list.indexOfFirst { it.ref == playingChannel?.ref }.coerceAtLeast(0),
                            fontScale = epgScale,
                        ) { then(list[it]) }
                    }
                }
            }

            fun runEpgSearch(query: String) {
                val current = config
                status = "Szukanie w EPG: „$query”…"
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { DecoderClient(current).epgSearch(query) } }
                    val now = Instant.now().epochSecond
                    val list = result.getOrElse {
                        status = "Nie udało się przeszukać EPG: ${it.message ?: "błąd połączenia"}"
                        return@launch
                    }.filter { it.end > now }
                    if (list.isEmpty()) {
                        status = "Nic nie znaleziono w EPG: „$query”."
                        return@launch
                    }
                    status = "EPG: „$query” — wyników: ${list.size}."
                    quickPick = QuickPick(
                        title = "Szukaj w EPG: „$query” (${list.size})",
                        options = list.map { it.title },
                        selected = 0,
                        subtitles = list.map { "${it.sname.ifBlank { "?" }}  •  ${EpgTime.dayAndRange(it.begin, it.end)}" },
                        timeoutSec = config.epgChannelSec,
                        onMenu = { openEpgSearch(query) },
                        fontScale = epgScale,
                        tag = "epg-search",
                    ) { index ->
                        val event = list[index]
                        showEvent(event, epgChannelFor(event.sref, event.sname)) { runEpgSearch(query) }
                    }
                }
            }

            fun openEpgSearchImpl(initial: String) {
                showMenu = false
                navigator.openEditor(
                    SettingsEditor("Szukaj w EPG", initial, "tytuł audycji, co najmniej 2 znaki") { value ->
                        val query = value.trim()
                        if (query.length < 2) "Wpisz co najmniej 2 znaki." else {
                            runEpgSearch(query)
                            null
                        }
                    },
                )
            }

            fun epgMenu(back: () -> Unit, onChannel: (Channel) -> Unit) {
                quickPick = QuickPick(
                    title = "EPG — opcje",
                    options = listOf("Wybierz inny kanał", "Multi EPG", "Szukaj w EPG"),
                    selected = 0,
                    onBack = back,
                ) { choice ->
                    when (choice) {
                        0 -> pickEpgChannel(onChannel)
                        1 -> showMultiEpg()
                        else -> openEpgSearchImpl("")
                    }
                }
            }

            fun showChannelEpgImpl(channel: Channel, selectEventId: Int?) {
                status = "EPG: ${channel.name}…"
                scope.launch {
                    val events = loadEpg(channel) ?: return@launch
                    val now = Instant.now().epochSecond
                    val list = events.filter { it.end > now }
                    if (list.isEmpty()) {
                        status = "Brak EPG dla kanału ${channel.name}."
                        return@launch
                    }
                    status = "EPG: ${channel.name} — audycji: ${list.size}."
                    val dayStarts = list.indices.filter { it == 0 || !EpgTime.sameDay(list[it - 1].begin, list[it].begin) }
                    quickPick = QuickPick(
                        title = "EPG: ${channel.name}",
                        options = list.map { it.title },
                        selected = list.indexOfFirst { it.id == selectEventId }.coerceAtLeast(0),
                        subtitles = list.map { event ->
                            listOfNotNull(
                                EpgTime.range(event.begin, event.end),
                                EpgTime.progress(event, now),
                                epgPreview(event).takeIf { it.isNotBlank() },
                            ).joinToString("  •  ")
                        },
                        headers = dayStarts.associateWith { EpgTime.day(list[it].begin).uppercase() },
                        timeoutSec = config.epgChannelSec,
                        tag = "epg-channel",
                        fontScale = epgScale,
                        onMenu = { epgMenu(back = { showChannelEpg(channel) }, onChannel = { showChannelEpg(it) }) },
                        onHorizontal = { direction ->
                            quickPick?.let { pick ->
                                val start = dayStarts.lastOrNull { it <= pick.selected } ?: 0
                                val target = if (direction > 0) dayStarts.firstOrNull { it > pick.selected }
                                else if (pick.selected > start) start else dayStarts.lastOrNull { it < start }
                                if (target != null) quickPick = pick.copy(selected = target)
                            }
                        },
                    ) { index -> showEvent(list[index], channel) { showChannelEpg(channel, list[index].id) } }
                }
            }

            fun showMultiEpgImpl(bouquet: Bouquet?, at: Long?, selectRef: String?) {
                val visible = visibleBouquets()
                val target = bouquet ?: selectedBouquet?.takeIf { it.ref != REC_BOUQUET }
                    ?: visible.firstOrNull { it.ref == config.lastBouquetRef } ?: visible.firstOrNull()
                val currentCatalog = catalog
                if (target == null || currentCatalog == null) {
                    status = "Najpierw połącz się z dekoderem."
                    return
                }
                val current = config
                scope.launch {
                    val list = if (target.ref == selectedBouquet?.ref && channels.isNotEmpty()) channels
                    else withContext(Dispatchers.IO) {
                        runCatching { DecoderClient(current).loadChannels(target, currentCatalog.usesPlugin) }.getOrNull()
                    }.orEmpty()
                    if (list.isEmpty()) {
                        status = "Brak kanałów w bukiecie „${target.name}”."
                        return@launch
                    }
                    if (list.any { epgRepo.cached(it.ref) == null }) status = "Multi EPG: wczytywanie ${list.size} kanałów…"
                    val client = DecoderClient(current)
                    val limit = Semaphore(4)
                    val epg = list.map { channel ->
                        async(Dispatchers.IO) {
                            limit.withPermit { serviceKey(channel.ref) to runCatching { epgRepo.load(client, channel.ref) }.getOrDefault(emptyList()) }
                        }
                    }.awaitAll().toMap()
                    val now = Instant.now().epochSecond
                    val time = (at ?: now).coerceAtLeast(now)
                    val rows = list.map { channel ->
                        val events = epg[serviceKey(channel.ref)].orEmpty()
                        val running = events.firstOrNull { it.begin <= time && it.end > time }
                        val next = events.firstOrNull { it.begin > time }
                        Triple(channel, running, next)
                    }
                    status = "Multi EPG: ${target.name}."
                    val wanted = selectRef ?: quickPick?.takeIf { it.tag == "multi-epg" }?.let { list.getOrNull(it.selected)?.ref } ?: playingChannel?.ref
                    quickPick = QuickPick(
                        title = "Multi EPG: ${target.name} — ${EpgTime.day(time)} ${EpgTime.clock(time)}",
                        options = rows.map { (channel, running, _) -> "${channel.number}  ${channel.name}:  ${running?.title ?: "—"}" },
                        selected = list.indexOfFirst { it.ref == wanted }.coerceAtLeast(0),
                        subtitles = rows.map { (_, running, next) ->
                            listOfNotNull(
                                running?.let { EpgTime.range(it.begin, it.end) },
                                next?.let { "następnie ${EpgTime.clock(it.begin)} ${it.title}" },
                            ).joinToString("  •  ").ifBlank { null }
                        },
                        timeoutSec = config.epgMultiSec,
                        tag = "multi-epg",
                        fontScale = epgScale,
                        onMenu = {
                            quickPick = QuickPick(
                                title = "Multi EPG — opcje",
                                options = listOf("Wybierz bukiet", "Szukaj w EPG"),
                                selected = 0,
                                onBack = { showMultiEpg(target, time) },
                            ) { choice ->
                                if (choice == 0) {
                                    quickPick = QuickPick(
                                        title = "Multi EPG — bukiet",
                                        options = visible.map { it.name },
                                        selected = visible.indexOfFirst { it.ref == target.ref }.coerceAtLeast(0),
                                        onBack = { showMultiEpg(target, time) },
                                    ) { showMultiEpg(visible[it]) }
                                } else {
                                    openEpgSearchImpl("")
                                }
                            }
                        },
                        onHorizontal = { direction ->
                            val selected = quickPick?.selected ?: 0
                            // Krok co pół godziny, wyrównany do pełnych i połówek godzin; nie wcześniej niż teraz.
                            val shifted = if (direction > 0) (time / 1800 + 1) * 1800 else ((time - 1) / 1800) * 1800
                            showMultiEpg(target, shifted.coerceAtLeast(Instant.now().epochSecond), list.getOrNull(selected)?.ref)
                        },
                    ) { index ->
                        val (channel, running, _) = rows[index]
                        if (running != null) showEvent(running, channel) { showMultiEpg(target, time, channel.ref) }
                        else showChannelEpg(channel)
                    }
                }
            }

            showChannelEpgRef = ::showChannelEpgImpl
            showMultiEpgRef = ::showMultiEpgImpl
            openEpgSearchRef = ::openEpgSearchImpl

            /** Pytanie z wyborem: w ustawieniach jako okno wyboru, poza nimi jako okienko nad obrazem. */
            fun ask(title: String, body: String, options: List<String>, onSelect: (Int) -> Unit) {
                if (navigator.isVisible) navigator.openChoice(SettingsChoice(title, options, 0, body = body, onSelect = onSelect))
                else quickPick = QuickPick(title, options, 0, body = body, timeoutSec = 0, onSelect = onSelect)
            }

            // ---------- Aktualizacja aplikacji z GitHuba ----------
            var updateStatus by remember { mutableStateOf("") }
            var updateBusy by remember { mutableStateOf(false) }
            fun installUpdate(release: Updater.Release) {
                if (updateBusy) return
                updateBusy = true
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) {
                            Updater.download(release) { percent ->
                                EventQueue.invokeLater { updateStatus = "pobieranie ${release.version}: $percent%" }
                            }
                        }
                        logInfo("aktualizacja: pobrano ${file.name} — uruchamiam instalator")
                        updateStatus = "uruchamiam instalator ${release.version}…"
                        withContext(Dispatchers.IO) { Updater.launchInstaller(file) }
                        delay(1500)
                        exitApplication()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        updateStatus = "błąd: ${e.message}"
                        status = "Nie udało się pobrać aktualizacji: ${e.message}"
                    } finally {
                        updateBusy = false
                    }
                }
            }
            fun checkUpdate(manual: Boolean) {
                if (updateBusy) return
                updateBusy = true
                updateStatus = "sprawdzanie…"
                val channel = config.updateChannel
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { Updater.newer(appVersion(), channel) } }
                    updateBusy = false
                    saveConfig(config.copy(lastUpdateCheck = System.currentTimeMillis()))
                    result.onFailure {
                        updateStatus = "błąd sprawdzania: ${it.message}"
                        if (manual) status = "Nie udało się sprawdzić aktualizacji: ${it.message}"
                    }.onSuccess { release ->
                        if (release == null) {
                            updateStatus = "masz najnowszą wersję (${appVersion()})"
                            if (manual) status = "E2Stream ${appVersion()} — to najnowsza wersja."
                            return@onSuccess
                        }
                        val kind = if (release.test) "testowa" else "stabilna"
                        updateStatus = "dostępna ${release.version} ($kind)"
                        logInfo("aktualizacja: dostępna ${release.version} ($kind)")
                        ask(
                            "Nowa wersja E2Stream ${release.version}",
                            "Zainstalowana: ${appVersion()}. Dostępna: ${release.version} ($kind).\n\n" +
                                "Instalator zostanie pobrany i uruchomiony, a E2Stream się zamknie. Po instalacji uruchom aplikację ponownie.",
                            listOf("Zainstaluj teraz", "Później"),
                        ) { if (it == 0) installUpdate(release) }
                    }
                }
            }
            LaunchedEffect(Unit) {
                delay(20_000)
                if (config.autoUpdate && System.currentTimeMillis() - config.lastUpdateCheck > 20 * 3_600_000L) checkUpdate(manual = false)
            }

            // ---------- Zapis bufora timeshiftu do nagrań na komputerze ----------
            var tsSaveBusy by remember { mutableStateOf(false) }
            fun saveTimeshiftBuffer(fromWall: Long, label: String) {
                val ts = timeshift ?: return
                val channel = playingChannel ?: return
                if (tsSaveBusy) {
                    status = "Zapis bufora już trwa."
                    return
                }
                val dir = TimeshiftBuffer.recordingsDir(config)
                val title = bannerNowNext.first?.title ?: nowEvents[serviceKey(channel.ref)]?.title
                val target = java.io.File(dir, TimeshiftBuffer.recordingName(channel.name, title, java.time.LocalDateTime.now()))
                tsSaveBusy = true
                status = "Zapisuję bufor timeshiftu ($label)…"
                scope.launch {
                    try {
                        val bytes = withContext(Dispatchers.IO) {
                            ts.buffer.saveTo(target, fromWall) { percent ->
                                if (percent % 10 == 0) EventQueue.invokeLater { status = "Zapis bufora: $percent%" }
                            }
                        }
                        if (bytes <= 0) {
                            target.delete()
                            status = "Bufor timeshiftu jest pusty — nic nie zapisano."
                        } else {
                            logInfo("timeshift: zapisano ${bytes / 1_000_000} MB do ${target.path}")
                            status = "Zapisano ${bytes / 1_000_000} MB: ${target.path}"
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        status = "Nie udało się zapisać bufora: ${e.message}"
                    } finally {
                        tsSaveBusy = false
                    }
                }
            }
            fun pickTimeshiftSave() {
                val ts = timeshift
                if (ts == null) {
                    status = "Timeshift jest wyłączony — Ustawienia → Odtwarzanie i obraz → Timeshift."
                    return
                }
                val now = monotonicMs()
                val span = ts.buffer.spanMs()
                val options = mutableListOf<Pair<String, Long>>()
                options += "Cały bufor (${formatDuration(span)})" to Long.MIN_VALUE
                val event = bannerNowNext.first
                if (event != null) {
                    val eventWall = now - (System.currentTimeMillis() / 1000 - event.begin) * 1000
                    if (eventWall > ts.buffer.oldestWall) {
                        options += "Od początku audycji „${event.title}” (${formatDuration(now - eventWall)})" to eventWall
                    }
                }
                for (minutes in listOf(5, 15, 30)) {
                    if (span > minutes * 60_000L) options += "Ostatnie $minutes min" to now - minutes * 60_000L
                }
                quickPick = QuickPick(
                    "Zapisz bufor timeshiftu",
                    options.map { it.first },
                    0,
                    body = "Plik .ts trafi do: ${TimeshiftBuffer.recordingsDir(config).path} (zmiana: Ustawienia → Odtwarzanie i obraz). " +
                        "Nie zmieniaj kanału do końca zapisu.",
                ) { index ->
                    val (label, from) = options[index]
                    saveTimeshiftBuffer(from, label)
                }
            }

            fun runNavAction(action: NavAction) {
                when (action) {
                    NavAction.NONE -> Unit
                    NavAction.CHANNEL_BANNER ->
                        if (playingChannel != null) bannerGeneration++ else status = "Nie odtwarzasz żadnego kanału."
                    NavAction.CHANNEL_LIST -> openChannelList()
                    NavAction.EPG_EVENT -> showCurrentEvent()
                    NavAction.EPG_CHANNEL -> playingChannel?.let { showChannelEpg(it) } ?: pickEpgChannel { showChannelEpg(it) }
                    NavAction.MULTI_EPG -> showMultiEpg()
                    NavAction.EPG_SEARCH -> openEpgSearch()
                    NavAction.AUDIO -> showAudioPick()
                    NavAction.MENU -> openMenu()
                    NavAction.SETTINGS -> openSettings()
                    NavAction.RECALL -> recall()
                    NavAction.CH_UP -> zap(true)
                    NavAction.CH_DOWN -> zap(false)
                    NavAction.PLAY_PAUSE -> togglePlaybackPause()
                    NavAction.TIMESHIFT -> when {
                        timeshift != null || playingRecording != null -> showPlaybackBar()
                        else -> status = "Timeshift jest wyłączony — Ustawienia → Odtwarzanie i obraz → Timeshift."
                    }
                }
            }

            fun runMenuItem(item: DesktopMenuItem) {
                when (item) {
                    DesktopMenuItem.CHANNEL_LIST -> openChannelList()
                    DesktopMenuItem.RECORDINGS -> openRecordingsList()
                    DesktopMenuItem.ZAP_TIMERS -> openZapTimers()
                    DesktopMenuItem.DECODER_TIMERS -> openDecoderTimers()
                    DesktopMenuItem.SETTINGS -> openSettings()
                    DesktopMenuItem.REMOTE_TEST -> openSettings(SettingsScreen.REMOTE, SettingsScreen.REMOTE_TEST)
                    DesktopMenuItem.IMAGE -> pickVideoScale()
                    DesktopMenuItem.WINDOW -> pickWindowMode()
                    DesktopMenuItem.MULTI_EPG -> showMultiEpg()
                    DesktopMenuItem.EPG_CHANNEL -> runNavAction(NavAction.EPG_CHANNEL)
                    DesktopMenuItem.EPG_SEARCH -> openEpgSearch()
                    DesktopMenuItem.AUDIO -> showAudioPick()
                    DesktopMenuItem.SAVE_TIMESHIFT -> pickTimeshiftSave()
                    DesktopMenuItem.EXIT -> {
                        powerDialogOpen.set(true)
                        showExitConfirmation = true
                    }
                }
            }

            // ---------- Pozycje ustawień ----------

            fun choose(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
                navigator.openChoice(SettingsChoice(title, options, selected.coerceAtLeast(0), onSelect = onSelect))
            }

            fun editText(
                title: String,
                value: String,
                hint: String = "",
                secret: Boolean = false,
                apply: (String) -> DesktopConfig,
            ) {
                navigator.openEditor(SettingsEditor(title, value, hint, secret) { input ->
                    saveConfig(apply(input), "Zapisano: $title.")
                    null
                })
            }

            fun editNumber(title: String, value: Int, range: IntRange, apply: (Int) -> DesktopConfig) {
                navigator.openEditor(
                    SettingsEditor(title, value.toString(), "${range.first}–${range.last}", digitsOnly = true, maxLength = 6) { input ->
                        val number = input.toIntOrNull()
                        if (number == null || number !in range) "Podaj liczbę z zakresu ${range.first}–${range.last}."
                        else {
                            saveConfig(apply(number), "Zapisano: $title = $number.")
                            null
                        }
                    },
                )
            }

            fun percentItem(label: String, value: Int, apply: (Int) -> DesktopConfig): SettingItem {
                val options = (50..140 step 10).toList()
                return SettingItem.Action(label, "$value%") {
                    choose(label, options.map { "$it%" }, options.indexOf(value)) { saveConfig(apply(options[it]), "$label: ${options[it]}%.") }
                }
            }

            fun openBrowser(url: String) {
                status = runCatching { java.awt.Desktop.getDesktop().browse(URI(url)) }
                    .fold({ "Otwarto w przeglądarce: $url" }, { "Nie udało się otworzyć przeglądarki: ${it.message}" })
            }

            fun checkPlugin() {
                scope.launch {
                    pluginStatus = "sprawdzanie…"
                    val current = config
                    pluginStatus = try {
                        "jest — ${withContext(Dispatchers.IO) { DecoderClient(current).pluginVersion() }}"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "brak odpowiedzi (${e.message ?: "błąd"}) — używany będzie OpenWebif"
                    }
                    status = "Wtyczka EPG Scan: $pluginStatus"
                }
            }

            fun discover() {
                scope.launch {
                    busy = true
                    status = "Szukam dekoderów w sieci lokalnej (mDNS i adresy sieci)…"
                    val current = config
                    val found = try {
                        withContext(Dispatchers.IO) { ReceiverDiscovery.scan(current.httpPort, current.pluginPort) }
                    } finally {
                        busy = false
                    }
                    if (found.isEmpty()) {
                        status = "Nie znaleziono dekoderów na porcie ${current.httpPort}."
                    } else {
                        status = "Znalezione dekodery: ${found.size}."
                        choose("Znalezione dekodery", found.map { "${it.ip}   ${it.label}" }, found.indexOfFirst { it.ip == config.host }) {
                            val receiver = found[it]
                            saveConfig(
                                config.copy(
                                    host = receiver.ip,
                                    pluginPort = receiver.pluginPort ?: config.pluginPort,
                                    httpPort = receiver.httpPort ?: config.httpPort,
                                    streamPort = receiver.streamPort ?: config.streamPort,
                                ),
                                "Wybrano dekoder ${receiver.ip}${if (receiver.viaMdns) " (mDNS)" else ""}.",
                            )
                        }
                    }
                }
            }

            /** EIT ze strumienia: włączenie tylko wtedy, gdy Enigma2 wysyła EIT (ustawienie odczytane przez EPG Scan). */
            fun enableStreamEit() {
                status = "Sprawdzam ustawienie EIT w Enigma2…"
                val current = config
                scope.launch {
                    val state: Boolean? = withContext(Dispatchers.IO) {
                        runCatching { DecoderClient(current).pluginInfo() }.getOrNull()
                            ?.takeIf { it.has("stream_eit") && !it.isNull("stream_eit") }?.optBoolean("stream_eit")
                    }
                    fun on() = saveConfig(
                        config.copy(eitStream = true),
                        "EIT ze strumienia włączone — działa od następnego przełączenia kanału.",
                    )
                    when (state) {
                        true -> on()
                        false -> navigator.openChoice(
                            SettingsChoice(
                                "EIT w strumieniu wyłączone",
                                listOf("OK"),
                                0,
                                body = "Enigma2 nie wysyła EIT w strumieniu, więc tej opcji nie można włączyć. Na dekoderze: " +
                                    "Menu → Ustawienia → System → Dostosuj (poziom ustawień: ekspert) → „Include EIT in http streams” = tak. " +
                                    "Potem włącz tę opcję tutaj.",
                            ) {},
                        )
                        null -> navigator.openChoice(
                            SettingsChoice(
                                "Nie można sprawdzić",
                                listOf("Włącz", "Anuluj"),
                                1,
                                body = "Nie da się odczytać ustawienia EIT w Enigma2 (potrzebna wtyczka EPG Scan 2.19.1 lub nowsza). " +
                                    "Jeśli Enigma2 nie wysyła EIT w strumieniu, opcja niczego nie zmieni. Włączyć?",
                            ) { if (it == 0) on() },
                        )
                    }
                }
            }

            fun remoteKeyItem(key: VirtualRemoteKey): SettingItem {
                val assigned = universalBindings[key].orEmpty().sorted()
                val summary = assigned.joinToString(", ") { UniversalRemoteMap.label(it) }.ifEmpty { "— brak —" }
                return SettingItem.Action(
                    key.label,
                    if (key == VirtualRemoteKey.POWER) "działa globalnie • $summary" else summary,
                ) {
                    val options = listOf("Dodaj klawisz (naciśnij go na pilocie)") +
                        assigned.map { "Usuń: ${UniversalRemoteMap.label(it)}" }
                    choose(key.label, options, 0) { index ->
                        if (index == 0) {
                            captureButton = key
                            status = "Naciśnij klawisz dla: ${key.label}."
                        } else {
                            universalBindings = UniversalRemoteMap.remove(universalBindings, key, assigned[index - 1])
                            saveConfig(
                                config.copy(universalRemoteBindings = UniversalRemoteMap.encode(universalBindings)),
                                "Usunięto klawisz z: ${key.label}.",
                            )
                        }
                    }
                }
            }

            fun navigationItems(withBanner: Boolean): List<SettingItem> {
                val defaults = if (withBanner) NavigationKeyMap.bannerDefaults else NavigationKeyMap.noBannerDefaults
                val stored = if (withBanner) config.navBanner else config.navNoBanner
                val map = NavigationKeyMap.resolve(defaults, stored)
                fun store(updated: Map<VirtualRemoteKey, NavAction>) {
                    val encoded = NavigationKeyMap.encode(updated)
                    saveConfig(if (withBanner) config.copy(navBanner = encoded) else config.copy(navNoBanner = encoded))
                }
                return listOf<SettingItem>(
                    SettingItem.Header(if (withBanner) "Gdy pasek kanału jest widoczny" else "Gdy pasek kanału jest ukryty"),
                ) + NavigationKeyMap.keys.map { key ->
                    val action = map[key] ?: NavAction.NONE
                    SettingItem.Action(key.label, action.label) {
                        val actions = NavAction.entries
                        choose("${key.label} — ${if (withBanner) "z paskiem" else "bez paska"}", actions.map { it.label }, actions.indexOf(action)) {
                            store(map + (key to actions[it]))
                            status = "${key.label} → ${actions[it].label}."
                        }
                    }
                } + listOf(
                    SettingItem.Header("Pozostałe"),
                    SettingItem.Action("Przywróć domyślne") {
                        store(defaults)
                        status = "Przywrócono domyślne mapowanie OK i strzałek."
                    },
                )
            }

            fun settingsItems(screen: SettingsScreen?): List<SettingItem> = when (screen) {
                null -> emptyList()
                SettingsScreen.SECTIONS -> SettingsScreen.sections.map { section ->
                    SettingItem.Action(tr(section.title), tr(section.description)) {
                        navigator.push(section, settingsItems(section))
                    }
                }
                SettingsScreen.DECODER -> listOf(
                    SettingItem.Header("Dekoder"),
                    SettingItem.Action("Adres IP dekodera", config.host.ifBlank { "nie ustawiono" }) {
                        editText("Adres IP dekodera", config.host, "np. 192.168.1.20") { config.copy(host = it.trim()) }
                    },
                    SettingItem.Action("Szukaj dekoderów w sieci", if (busy) "trwa…" else "sieć lokalna, port OpenWebif") {
                        if (!busy) discover()
                    },
                    SettingItem.Action("Port OpenWebif", config.httpPort.toString()) {
                        editNumber("Port OpenWebif", config.httpPort, 1..65535) { config.copy(httpPort = it) }
                    },
                    SettingItem.Action("Port streamingu", config.streamPort.toString()) {
                        editNumber("Port streamingu", config.streamPort, 1..65535) { config.copy(streamPort = it) }
                    },
                    SettingItem.Action("Użytkownik OpenWebif", config.username.ifBlank { "puste = bez logowania" }) {
                        editText("Użytkownik OpenWebif", config.username, "puste = bez logowania") { config.copy(username = it.trim()) }
                    },
                    SettingItem.Action("Hasło OpenWebif", if (config.password.isEmpty()) "brak" else "••••••") {
                        editText("Hasło OpenWebif", config.password, secret = true) { config.copy(password = it) }
                    },
                    SettingItem.Header("Wtyczka EPG Scan na dekoderze"),
                    SettingItem.Action("Stan wtyczki", pluginStatus) { checkPlugin() },
                    SettingItem.Action("Port wtyczki", config.pluginPort.toString()) {
                        editNumber("Port wtyczki", config.pluginPort, 1024..65535) { config.copy(pluginPort = it) }
                    },
                    SettingItem.Action("Token wtyczki", if (config.pluginToken.isEmpty()) "puste = bez (sieć lokalna)" else "••••••") {
                        editText("Token wtyczki", config.pluginToken, "puste = bez (sieć lokalna)", secret = true) {
                            config.copy(pluginToken = it.trim())
                        }
                    },
                    SettingItem.Header("FTP (potrzebne tylko, gdy na dekoderze nie ma wtyczki EPG Scan)"),
                    SettingItem.Action("FTP — użytkownik", config.ftpUser) {
                        editText("FTP — użytkownik", config.ftpUser, "domyślnie root") { config.copy(ftpUser = it.trim().ifEmpty { "root" }) }
                    },
                    SettingItem.Action("FTP — hasło", if (config.ftpPass.isEmpty()) "brak" else "••••••") {
                        editText("FTP — hasło", config.ftpPass, secret = true) { config.copy(ftpPass = it) }
                    },
                    SettingItem.Action("FTP — katalog na dekoderze", config.ftpDir) {
                        editText("FTP — katalog na dekoderze", config.ftpDir, DesktopConfig.DEFAULT_FTP_DIR) {
                            config.copy(ftpDir = it.trim().ifEmpty { DesktopConfig.DEFAULT_FTP_DIR })
                        }
                    },
                    SettingItem.Header("Bukiety i kanały"),
                    SettingItem.Action(
                        "Połącz i pobierz bukiety",
                        when {
                            busy -> "trwa…"
                            catalog == null -> "nie połączono"
                            else -> "bukiety: ${catalog?.bouquets?.size} (${if (catalog?.usesPlugin == true) "EPG Scan" else "OpenWebif"})"
                        },
                    ) { if (!busy) connect(autoStart = false) },
                    SettingItem.Action(
                        "Bukiet",
                        selectedBouquet?.let { "${it.name} • kanały: ${channels.size}" } ?: "najpierw połącz z dekoderem",
                    ) {
                        val visible = visibleBouquets()
                        if (visible.isEmpty()) status = "Najpierw połącz się z dekoderem."
                        else choose("Bukiet", visible.map { it.name }, visible.indexOfFirst { it.ref == selectedBouquet?.ref }) {
                            selectBouquet(visible[it])
                        }
                    },
                )
                SettingsScreen.PLAYBACK -> listOf(
                    SettingItem.Header("VLC"),
                    SettingItem.Action("Usuwanie przeplotu", DesktopConfig.deinterlaceModes[config.vlcDeint]) {
                        val keys = DesktopConfig.deinterlaceModes.keys.toList()
                        choose("Usuwanie przeplotu", DesktopConfig.deinterlaceModes.values.toList(), keys.indexOf(config.vlcDeint)) {
                            saveConfig(config.copy(vlcDeint = keys[it]), "Usuwanie przeplotu: ${DesktopConfig.deinterlaceModes[keys[it]]}.")
                            restartPlayback()
                        }
                    },
                    SettingItem.Action("Akceleracja sprzętowa", DesktopConfig.hardwareModes[config.vlcHwMode]) {
                        val keys = DesktopConfig.hardwareModes.keys.toList()
                        choose("Akceleracja sprzętowa", DesktopConfig.hardwareModes.values.toList(), keys.indexOf(config.vlcHwMode)) {
                            saveConfig(config.copy(vlcHwMode = keys[it]), "Akceleracja sprzętowa: ${DesktopConfig.hardwareModes[keys[it]]}.")
                            restartPlayback()
                        }
                    },
                    SettingItem.Action("Bufor sieciowy (ms)", "${config.bufferMs} ms — mniej = szybszy obraz, więcej = odporność na zacięcia") {
                        editNumber("Bufor sieciowy (ms)", config.bufferMs, 250..10_000) { config.copy(bufferMs = it) }
                    },
                    SettingItem.Action("Skalowanie obrazu", config.videoScaleMode.label) {
                        val modes = VideoScaleMode.entries
                        choose("Skalowanie obrazu", modes.map { it.label }, modes.indexOf(config.videoScaleMode)) {
                            saveConfig(config.copy(videoScaleMode = modes[it]), "Skalowanie obrazu: ${modes[it].label}.")
                            vlcSession?.setVideoScale(modes[it])
                        }
                    },
                    SettingItem.Action("Okno programu", windowMode.label) {
                        val modes = WindowMode.entries
                        choose("Okno programu", modes.map { it.label }, modes.indexOf(windowMode)) {
                            setWindowMode(modes[it])
                            status = "Okno programu: ${modes[it].label}."
                        }
                    },
                    SettingItem.Header("Watchdog"),
                    SettingItem.Toggle(
                        "Watchdog",
                        config.watchdog,
                        "gdy obraz stoi — ponowne otwarcie strumienia; gdy dekoder nie odpowiada — czekanie i wznowienie tego samego kanału",
                    ) { saveConfig(config.copy(watchdog = it)) },
                    SettingItem.Action("Próg zawieszenia", "${config.stallSec} s (+2 s zapasu; po starcie kanału 15 s)") {
                        editNumber("Próg zawieszenia obrazu (s)", config.stallSec, 1..30) { config.copy(stallSec = it) }
                    },
                    SettingItem.Header("Timeshift"),
                    SettingItem.Action(
                        "Timeshift (bufor na dysku)",
                        if (config.tsMin <= 0) "wyłączony" else "${config.tsMin} min — działa od następnego przełączenia kanału",
                    ) {
                        val dir = TimeshiftBuffer.dirFor(config)
                        val fit = TimeshiftBuffer.fitMinutes(dir)
                        val choices = TimeshiftBuffer.CHOICES
                        choose(
                            "Timeshift — zmieści się ok. $fit min",
                            choices.map { if (it == 0) "wyłączony" else if (it > fit) "$it min (za mało miejsca)" else "$it min" },
                            choices.indexOf(config.tsMin),
                        ) {
                            val minutes = choices[it]
                            if (minutes > fit) {
                                status = "Za mało miejsca na dysku na $minutes min timeshiftu (zmieści się ok. $fit min)."
                            } else {
                                saveConfig(config.copy(tsMin = minutes), if (minutes == 0) "Timeshift wyłączony." else "Timeshift: $minutes min.")
                                if (minutes == 0) closeTimeshift()
                                timeshift?.buffer?.maxMin = minutes
                            }
                        }
                    },
                    SettingItem.Action("Katalog bufora", TimeshiftBuffer.dirFor(config).path) {
                        editText("Katalog bufora timeshiftu", config.tsDir, "pusty = ${TimeshiftBuffer.defaultDir().path}") {
                            config.copy(tsDir = it.trim())
                        }
                    },
                    SettingItem.Action("Krok ◀ ▶", "${config.tsStepSec} s") {
                        val steps = listOf(5, 10, 15, 30, 60)
                        choose("Krok ◀ ▶ (sekundy)", steps.map { "$it s" }, steps.indexOf(config.tsStepSec)) {
                            saveConfig(config.copy(tsStepSec = steps[it]), "Krok ◀ ▶: ${steps[it]} s.")
                        }
                    },
                    SettingItem.Action("Krok ▲ ▼", "${config.tsStepMin} min") {
                        val steps = listOf(1, 3, 5, 10, 15)
                        choose("Krok ▲ ▼ (minuty)", steps.map { "$it min" }, steps.indexOf(config.tsStepMin)) {
                            saveConfig(config.copy(tsStepMin = steps[it]), "Krok ▲ ▼: ${steps[it]} min.")
                        }
                    },
                    SettingItem.Toggle("Wstecz wraca na żywo", config.tsBackLive) {
                        saveConfig(config.copy(tsBackLive = it))
                    },
                    SettingItem.Action("Katalog nagrań z bufora", TimeshiftBuffer.recordingsDir(config).path) {
                        editText(
                            "Katalog nagrań zapisanych z bufora timeshiftu",
                            config.recLocalDir,
                            "pusty = ${TimeshiftBuffer.recordingsDir(config.copy(recLocalDir = "")).path}",
                        ) { config.copy(recLocalDir = it.trim()) }
                    },
                    SettingItem.Action("Otwórz katalog nagrań", "Menu → Zapisz bufor timeshiftu zapisuje tam pliki .ts") {
                        val dir = TimeshiftBuffer.recordingsDir(config)
                        status = runCatching { dir.mkdirs(); java.awt.Desktop.getDesktop().open(dir) }
                            .fold({ "Otwarto: ${dir.path}" }, { "Nie udało się otworzyć katalogu: ${it.message}" })
                    },
                    SettingItem.Header("Dźwięk"),
                    SettingItem.Action(
                        "Głośność aplikacji",
                        "${config.appVolume}%" + (if (config.appMuted) " (wyciszone)" else "") + " — niezależna od głośności Windows",
                    ) {
                        editNumber("Głośność aplikacji (%)", config.appVolume, 0..DesktopConfig.MAX_VOLUME) {
                            vlcSession?.setAudioLevel(it, config.appMuted)
                            config.copy(appVolume = it)
                        }
                    },
                    SettingItem.Toggle("Wyciszenie", config.appMuted) { setAppVolume(config.appVolume, it) },
                    SettingItem.Toggle(
                        "Klawisze głośności sterują aplikacją",
                        config.volumeKeysApp,
                        "gdy E2Stream jest na wierzchu; w tle działają jak zwykle w Windows",
                    ) { saveConfig(config.copy(volumeKeysApp = it)) },
                    SettingItem.Action("Krok głośności", "${config.volumeStep}%") {
                        editNumber("Krok głośności (%)", config.volumeStep, 1..25) { config.copy(volumeStep = it) }
                    },
                    SettingItem.Action("Ścieżka dźwięku teraz", "wybór ścieżki bieżącego kanału (zapamiętywany)") {
                        showAudioPick(inSettings = true)
                    },
                    SettingItem.Action("Zapomnij zapamiętane ścieżki", "zapamiętane kanały: ${audioChoices.count(config.host)}") {
                        audioChoices.clear(config.host)
                        status = "Usunięto zapamiętane ścieżki dźwięku."
                    },
                    SettingItem.Toggle(
                        "Ścieżki dźwiękowe wspólne",
                        config.audioSync,
                        "wybory ścieżek synchronizowane z dekoderem (wtyczka EPG Scan)",
                    ) {
                        saveConfig(config.copy(audioSync = it))
                        if (it) syncAudio(manual = true)
                    },
                    SettingItem.Action(
                        "Synchronizuj ścieżki teraz",
                        audioSyncStatus.ifBlank { if (catalog?.usesPlugin == true) "jeszcze nie synchronizowano" else "wymaga wtyczki EPG Scan" },
                    ) { syncAudio(manual = true) },
                    SettingItem.Action("Preferowany język", config.audioLang.ifBlank { "domyślny strumienia" }) {
                        editText("Preferowany język dźwięku", config.audioLang, "kody języków, np. pl,pol,en") {
                            config.copy(audioLang = it.trim())
                        }
                    },
                    SettingItem.Header("Zmiany VLC"),
                    SettingItem.Action("Zastosuj teraz", "uruchamia ponownie bieżący kanał z nowymi ustawieniami") {
                        if (playingChannel == null) status = "Nie odtwarzasz żadnego kanału." else restartPlayback()
                    },
                )
                SettingsScreen.APPEARANCE -> listOf(
                    SettingItem.Header("Lista kanałów"),
                    percentItem("Czcionka listy kanałów", config.uiListFont) { config.copy(uiListFont = it) },
                    percentItem("Ikony na liście kanałów", config.uiListIcon) { config.copy(uiListIcon = it) },
                    SettingItem.Header("EPG (wydarzenia, EPG kanału, Multi EPG, szukanie)"),
                    percentItem("Czcionka okien EPG", config.uiEpgFont) { config.copy(uiEpgFont = it) },
                    SettingItem.Header("Pasek kanału"),
                    percentItem("Czcionka paska kanału", config.uiBannerFont) { config.copy(uiBannerFont = it) },
                    percentItem("Ikona na pasku kanału", config.uiBannerIcon) { config.copy(uiBannerIcon = it) },
                )
                SettingsScreen.EPG -> listOf(
                    SettingItem.Header("EPG"),
                    SettingItem.Action(
                        "Aktualizacja z EPG Scan",
                        if (config.epgUpdateMode == DesktopConfig.EPG_UPDATE_STARTUP) "przy każdym uruchomieniu" else "raz dziennie",
                    ) {
                        val modes = listOf(DesktopConfig.EPG_UPDATE_DAILY, DesktopConfig.EPG_UPDATE_STARTUP)
                        val labels = listOf("raz dziennie", "przy każdym uruchomieniu")
                        choose("Aktualizacja z EPG Scan", labels, modes.indexOf(config.epgUpdateMode)) {
                            saveConfig(config.copy(epgUpdateMode = modes[it]), "Aktualizacja z EPG Scan: ${labels[it]}.")
                        }
                    },
                    SettingItem.Toggle("Pobieraj EPG codziennie (bez wtyczki EPG Scan)", config.epgDaily) {
                        saveConfig(config.copy(epgDaily = it))
                    },
                    SettingItem.Action("Godzina pobierania EPG", config.epgHour) {
                        navigator.openEditor(SettingsEditor("Godzina pobierania EPG", config.epgHour, "HH:MM", maxLength = 5) { input ->
                            val hour = DesktopConfig.normalizeHour(input)
                            if (hour == null) "Format HH:MM, np. 05:00."
                            else {
                                saveConfig(config.copy(epgHour = hour), "Godzina pobierania EPG: $hour.")
                                null
                            }
                        })
                    },
                    SettingItem.Action("EPG na ile dni do przodu", "${config.epgDays}") {
                        editNumber("EPG na ile dni do przodu", config.epgDays, 1..14) { config.copy(epgDays = it) }
                    },
                    SettingItem.Toggle(
                        "EIT ze strumienia (teraz/następne)",
                        config.eitStream,
                        "aktualne „teraz/następne” z oglądanego kanału (Enigma2: Include EIT in http streams); " +
                            "działa od następnego przełączenia kanału",
                    ) { enabled -> if (enabled) enableStreamEit() else saveConfig(config.copy(eitStream = false)) },
                ) + (if (!config.eitStream) emptyList() else listOf(
                    SettingItem.Toggle(
                        "Podmieniaj EPG po odebraniu EIT",
                        config.eitReplace,
                        "zmieniona audycja trafia do zapisanego EPG kanału (do następnego pobrania EPG)",
                    ) { saveConfig(config.copy(eitReplace = it)) },
                )) + listOf(
                    SettingItem.Action("Bieżąca audycja", epgError ?: "OpenWebif, odświeżana co minutę (${nowEvents.size} kanałów)") {
                        epgRefresh++
                        status = "Odświeżam bieżące EPG…"
                    },
                    SettingItem.Action(
                        "Pobierz EPG teraz",
                        when {
                            epgSyncBusy -> "trwa… $epgSyncStatus"
                            epgSyncStatus.isNotBlank() -> epgSyncStatus
                            config.lastEpgFetch > 0 -> "ostatnio: " + EpgTime.day(config.lastEpgFetch / 1000) + " " + EpgTime.clock(config.lastEpgFetch / 1000)
                            else -> "jeszcze nie pobierano"
                        },
                    ) { syncEpg(manual = true) },
                    SettingItem.Action("Zapisane EPG", "kanałów: $epgStoreCount — usuń, aby pobrać od nowa") {
                        epgRepo.store.clear()
                        epgRepo.invalidate()
                        epgStoreCount = 0
                        saveConfig(config.copy(lastEpgFetch = 0, epgPackIdentity = ""), "Usunięto zapisane EPG.")
                    },
                    SettingItem.Header("Ikony kanałów"),
                    SettingItem.Toggle("Ikony kanałów z dekodera", config.picons) {
                        saveConfig(config.copy(picons = it))
                        if (it && Picons.count() == 0) fetchPicons(fresh = false, manual = true)
                    },
                    SettingItem.Toggle("Zastępuj brakujące picony logo E2Stream", config.missingPiconLogo) {
                        saveConfig(config.copy(missingPiconLogo = it))
                    },
                    SettingItem.Action("Katalog picon na dekoderze", config.piconDir.ifBlank { "odczytaj z ustawień Enigmy2" }) {
                        editText("Katalog picon na dekoderze", config.piconDir, "puste = odczytaj z ustawień Enigmy2") {
                            config.copy(piconDir = it.trim().trimEnd('/'))
                        }
                    },
                    SettingItem.Action(
                        "Pobierz EPG i ikony teraz",
                        when {
                            piconsBusy -> "trwa… $piconStatus"
                            piconStatus.isNotBlank() -> piconStatus
                            else -> "zapisane ikony: ${Picons.count()}"
                        },
                    ) {
                        epgRefresh++
                        syncEpg(manual = true)
                        fetchPicons(fresh = false, manual = true)
                    },
                    SettingItem.Action("Pobierz ikony od nowa", "usuwa zapisane ikony i pobiera wszystkie") {
                        fetchPicons(fresh = true, manual = true)
                    },
                )
                SettingsScreen.GENERAL -> listOf(
                    SettingItem.Header("Bukiety i kanały"),
                    SettingItem.Action(
                        "Widoczne bukiety",
                        if (config.hiddenBouquets.isEmpty()) "wszystkie" else "ukryte: ${config.hiddenBouquets.size}",
                    ) {
                        if (catalog == null) status = "Najpierw połącz się z dekoderem."
                        else navigator.push(SettingsScreen.BOUQUETS, settingsItems(SettingsScreen.BOUQUETS))
                    },
                    SettingItem.Toggle("Po uruchomieniu wróć do ostatniego kanału", config.resumeLast) {
                        saveConfig(config.copy(resumeLast = it))
                    },
                    SettingItem.Action(
                        "Po uruchomieniu przełącz na kanał",
                        if (config.resumeLast) "nieaktywne (włączony powrót do ostatniego)"
                        else config.startChannelName.ifBlank { "pierwszy kanał" },
                    ) {
                        val currentCatalog = catalog
                        val bouquets = visibleBouquets()
                        when {
                            config.resumeLast -> status = "Najpierw wyłącz „Po uruchomieniu wróć do ostatniego kanału”."
                            currentCatalog == null || bouquets.isEmpty() -> status = "Najpierw połącz się z dekoderem."
                            else -> choose(
                                "Kanał startowy — bukiet",
                                listOf("pierwszy kanał") + bouquets.map { it.name },
                                if (config.startChannelRef.isBlank()) 0
                                else bouquets.indexOfFirst { it.ref == config.startBouquetRef } + 1,
                            ) { bouquetIndex ->
                                val bouquet = bouquets.getOrNull(bouquetIndex - 1)
                                if (bouquet == null) {
                                    saveConfig(
                                        config.copy(startBouquetRef = "", startChannelRef = "", startChannelName = ""),
                                        "Kanał startowy: pierwszy kanał.",
                                    )
                                    return@choose
                                }
                                scope.launch {
                                    status = "Pobieranie kanałów bukietu „${bouquet.name}”…"
                                    val list = try {
                                        if (bouquet.ref == selectedBouquet?.ref && channels.isNotEmpty()) channels
                                        else withContext(Dispatchers.IO) {
                                            DecoderClient(config).loadChannels(bouquet, currentCatalog.usesPlugin)
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        status = e.message ?: "Nie udało się pobrać kanałów."
                                        return@launch
                                    }
                                    if (list.isEmpty()) {
                                        status = "Bukiet „${bouquet.name}” nie zawiera kanałów."
                                        return@launch
                                    }
                                    status = "Wybierz kanał startowy z bukietu „${bouquet.name}”."
                                    choose(
                                        "Kanał startowy (${bouquet.name})",
                                        list.map { "${it.number}. ${it.name}" },
                                        list.indexOfFirst { it.ref == config.startChannelRef },
                                    ) { index ->
                                        val channel = list[index]
                                        saveConfig(
                                            config.copy(
                                                startBouquetRef = bouquet.ref,
                                                startChannelRef = channel.ref,
                                                startChannelName = "${channel.name} (${bouquet.name})",
                                            ),
                                            "Kanał startowy: ${channel.name} (${bouquet.name}).",
                                        )
                                    }
                                }
                            }
                        }
                    },
                    SettingItem.Toggle("Odwróć ▲ / ▼ przy zmianie kanału", config.invertUpDown) {
                        saveConfig(config.copy(invertUpDown = it))
                    },
                    SettingItem.Header("Menu"),
                    SettingItem.Action(
                        tr("Pozycje i kolejność menu"),
                        if (config.menuHidden.isEmpty()) "wszystkie widoczne" else "ukryte: ${config.menuHidden.size}",
                    ) {
                        navigator.push(SettingsScreen.MENU_ITEMS, settingsItems(SettingsScreen.MENU_ITEMS))
                    },
                    SettingItem.Header("Czasy okien"),
                    SettingItem.Action("Czas paska kanału (s)", "${config.bannerSec} s") {
                        editNumber("Czas paska kanału (s)", config.bannerSec, 1..30) { config.copy(bannerSec = it) }
                    },
                    SettingItem.Action("Czas paska timeshiftu i nagrań (s)", "${config.tsBannerSec} s") {
                        editNumber("Czas paska timeshiftu i nagrań (s)", config.tsBannerSec, 1..30) { config.copy(tsBannerSec = it) }
                    },
                    SettingItem.Action(
                        "Czas listy kanałów (s, 0 = bez zamykania)",
                        if (config.channelListSec == 0) "bez zamykania" else "${config.channelListSec} s",
                    ) {
                        editNumber("Czas listy kanałów (s)", config.channelListSec, 0..600) { config.copy(channelListSec = it) }
                    },
                    SettingItem.Action(
                        "Czas okien akcji (s, 0 = bez zamykania)",
                        "menu i okna wyboru: " + if (config.actionDialogSec == 0) "bez zamykania" else "${config.actionDialogSec} s",
                    ) {
                        editNumber("Czas okien akcji (s)", config.actionDialogSec, 0..600) { config.copy(actionDialogSec = it) }
                    },
                    SettingItem.Action(
                        "Czas okien potwierdzenia (s, 0 = bez zamykania)",
                        "np. wyłączenie aplikacji: " + if (config.confirmDialogSec == 0) "bez zamykania" else "${config.confirmDialogSec} s",
                    ) {
                        editNumber("Czas okien potwierdzenia (s)", config.confirmDialogSec, 0..600) { config.copy(confirmDialogSec = it) }
                    },
                    SettingItem.Action(
                        "Czas EPG kanału (s, 0 = bez zamykania)",
                        (if (config.epgChannelSec == 0) "bez zamykania" else "${config.epgChannelSec} s"),
                    ) {
                        editNumber("Czas EPG kanału (s)", config.epgChannelSec, 0..600) { config.copy(epgChannelSec = it) }
                    },
                    SettingItem.Action(
                        "Czas Multi EPG (s, 0 = bez zamykania)",
                        (if (config.epgMultiSec == 0) "bez zamykania" else "${config.epgMultiSec} s"),
                    ) {
                        editNumber("Czas Multi EPG (s)", config.epgMultiSec, 0..600) { config.copy(epgMultiSec = it) }
                    },
                    SettingItem.Toggle("Pokazuj okna informacyjne", !config.hideInfoDialogs) {
                        saveConfig(config.copy(hideInfoDialogs = !it))
                    },
                    SettingItem.Action("Czas okien informacyjnych (s)", "${config.infoDialogSec} s") {
                        editNumber("Czas okien informacyjnych (s)", config.infoDialogSec, 1..600) { config.copy(infoDialogSec = it) }
                    },
                    SettingItem.Header("Harmonogram"),
                    SettingItem.Action(
                        "Czyść zrealizowane po",
                        zapHistoryLabel(config.zapHistoryDays) + " • przełączenia wg harmonogramu",
                    ) {
                        val days = ZapTimerStore.HISTORY_DAYS
                        choose("Czyść zrealizowane po", days.map(::zapHistoryLabel), days.indexOf(config.zapHistoryDays)) {
                            saveConfig(config.copy(zapHistoryDays = days[it]), "Czyść zrealizowane po: ${zapHistoryLabel(days[it])}.")
                        }
                    },
                    SettingItem.Header(tr("Język aplikacji")),
                    SettingItem.Action(tr("Język aplikacji"), AppLanguage.label(config.appLanguage)) {
                        val languages = AppLanguage.choices
                        choose(tr("Język aplikacji"), languages.map(AppLanguage::label), languages.indexOf(config.appLanguage)) {
                            AppLanguage.apply(languages[it])
                            saveConfig(config.copy(appLanguage = languages[it]), "${tr("Język aplikacji")}: ${AppLanguage.label(languages[it])}.")
                        }
                    },
                    SettingItem.Header("Sterowanie"),
                    SettingItem.Action("Przycisk Wróć w oknach", config.backButtonMode.label) {
                        val modes = BackButtonMode.entries
                        choose("Przycisk Wróć w oknach", modes.map { it.label }, modes.indexOf(config.backButtonMode)) {
                            saveConfig(config.copy(backButtonMode = modes[it]), "Przycisk Wróć: ${modes[it].label}.")
                        }
                    },
                    SettingItem.Header("Blokada"),
                    SettingItem.Action("PIN do ustawień", if (config.pin.isEmpty()) "brak blokady" else "ustawiony") {
                        navigator.openEditor(
                            SettingsEditor(
                                "PIN do ustawień",
                                config.pin,
                                "maks. 6 cyfr; puste = bez blokady",
                                secret = true,
                                digitsOnly = true,
                                maxLength = 6,
                            ) { value ->
                                pinUnlockedUntil = if (config.pinMin <= 0) Long.MAX_VALUE
                                else System.currentTimeMillis() + config.pinMin * 60_000L
                                saveConfig(config.copy(pin = value), if (value.isEmpty()) "Usunięto PIN." else "Ustawiono PIN.")
                                null
                            },
                        )
                    },
                    SettingItem.Action(
                        "Pytaj o PIN ponownie po",
                        if (config.pin.isEmpty()) "nieaktywne (brak PIN-u)"
                        else if (config.pinMin <= 0) "do wyjścia z aplikacji" else "${config.pinMin} min",
                    ) {
                        val minutes = listOf(1, 5, 15, 60, 0)
                        if (config.pin.isEmpty()) status = "Najpierw ustaw PIN."
                        else choose(
                            "Pytaj o PIN ponownie po",
                            minutes.map { if (it == 0) "do wyjścia z aplikacji" else "$it min" },
                            minutes.indexOf(config.pinMin),
                        ) { saveConfig(config.copy(pinMin = minutes[it])) }
                    },
                )
                SettingsScreen.REMOTE -> listOf(
                    SettingItem.Header("Pilot"),
                    SettingItem.Action("Klawisze pilota", "przypisz klawisze pilota i klawiatury do przycisków") {
                        navigator.push(SettingsScreen.REMOTE_KEYS, settingsItems(SettingsScreen.REMOTE_KEYS))
                    },
                    SettingItem.Action("Mapowanie OK i strzałek — bez paska", "co robią OK i strzałki podczas oglądania") {
                        navigator.push(SettingsScreen.NAV_NO_BANNER, settingsItems(SettingsScreen.NAV_NO_BANNER))
                    },
                    SettingItem.Action("Mapowanie OK i strzałek — z paskiem kanału", "gdy widoczny jest pasek kanału") {
                        navigator.push(SettingsScreen.NAV_BANNER, settingsItems(SettingsScreen.NAV_BANNER))
                    },
                    SettingItem.Action("Test pilota", "sprawdź, jakie klawisze odbiera program") {
                        testEvents = emptyList()
                        navigator.push(SettingsScreen.REMOTE_TEST)
                    },
                    SettingItem.Header("Power"),
                    remoteKeyItem(VirtualRemoteKey.POWER),
                    SettingItem.Header("Odbiornik pilota HID"),
                    SettingItem.Action("Stan odbiornika", hidDiagnostic) {},
                    SettingItem.Header("Pilot IR przez WinLIRC / LIRC"),
                    SettingItem.Toggle("Odbieraj klawisze z WinLIRC / LIRC", config.lircEnabled) {
                        saveConfig(config.copy(lircEnabled = it), if (it) "Pilot IR: łączenie z WinLIRC/LIRC." else "Pilot IR wyłączony.")
                    },
                    SettingItem.Action("Serwer WinLIRC / LIRC", "${config.lircHost}:${config.lircPort}") {
                        editText("Adres serwera WinLIRC / LIRC", config.lircHost, "np. 127.0.0.1") {
                            config.copy(lircHost = it.trim().ifEmpty { "127.0.0.1" })
                        }
                    },
                    SettingItem.Action("Port serwera WinLIRC / LIRC", config.lircPort.toString()) {
                        editNumber("Port serwera WinLIRC / LIRC", config.lircPort, 1..65535) { config.copy(lircPort = it) }
                    },
                    SettingItem.Action("Stan pilota IR", lircState) {},
                )
                SettingsScreen.REMOTE_KEYS -> listOf<SettingItem>(
                    SettingItem.Action("Przywróć domyślne klawisze", "Enter, strzałki, Esc, Home, PgUp/PgDn, F1, cyfry") {
                        choose("Przywrócić domyślne klawisze?", listOf("Tak, przywróć", "Anuluj"), 1) {
                            if (it == 0) {
                                universalBindings = UniversalRemoteMap.defaults
                                saveConfig(config.copy(universalRemoteBindings = ""), "Przywrócono domyślne klawisze.")
                            }
                        }
                    },
                    SettingItem.Header("Przyciski"),
                ) + VirtualRemoteKey.entries.map(::remoteKeyItem)
                SettingsScreen.NAV_NO_BANNER -> navigationItems(withBanner = false)
                SettingsScreen.NAV_BANNER -> navigationItems(withBanner = true)
                SettingsScreen.REMOTE_TEST -> emptyList()
                SettingsScreen.BOUQUETS -> listOf<SettingItem>(SettingItem.Header("Bukiety widoczne na liście kanałów")) +
                    catalog?.bouquets.orEmpty().map { bouquet ->
                        SettingItem.Toggle(bouquet.name, bouquet.ref !in config.hiddenBouquets) { visible ->
                            val hidden = if (visible) config.hiddenBouquets - bouquet.ref else config.hiddenBouquets + bouquet.ref
                            if (catalog?.bouquets.orEmpty().all { it.ref in hidden }) {
                                status = "Co najmniej jeden bukiet musi być widoczny."
                            } else {
                                saveConfig(config.copy(hiddenBouquets = hidden))
                            }
                        }
                    } + SettingItem.Toggle("Nagrania (z dysku dekodera)", REC_BOUQUET !in config.hiddenBouquets) { visible ->
                        saveConfig(
                            config.copy(
                                hiddenBouquets = if (visible) config.hiddenBouquets - REC_BOUQUET else config.hiddenBouquets + REC_BOUQUET,
                            ),
                        )
                    }
                SettingsScreen.MENU_ITEMS -> {
                    val order = DesktopMenuItem.inOrder(config.menuOrder)
                    listOf<SettingItem>(SettingItem.Header("OK — ukryj / pokaż, przesuń. Ustawienia i Wyłącz są zawsze na końcu.")) +
                        order.mapIndexed { index, item ->
                            val hidden = item.name in config.menuHidden
                            SettingItem.Action("${index + 1}. ${tr(item.label)}", if (hidden) "ukryta" else "widoczna") {
                                choose(
                                    tr(item.label),
                                    listOf(if (hidden) "Pokaż pozycję" else "Ukryj pozycję", "Przesuń wyżej", "Przesuń niżej"),
                                    0,
                                ) { choice ->
                                    if (choice == 0) {
                                        saveConfig(
                                            config.copy(menuHidden = if (hidden) config.menuHidden - item.name else config.menuHidden + item.name),
                                            "${tr(item.label)}: ${if (hidden) "widoczna" else "ukryta"} w menu.",
                                        )
                                        return@choose
                                    }
                                    val target = index + if (choice == 1) -1 else 1
                                    if (target !in order.indices) {
                                        status = "Pozycja jest już ${if (choice == 1) "pierwsza" else "ostatnia"}."
                                        return@choose
                                    }
                                    val moved = order.toMutableList().apply { add(target, removeAt(index)) }
                                    saveConfig(config.copy(menuOrder = moved.map { it.name }))
                                    navigator.select(target + 1)
                                }
                            }
                        }
                }
                SettingsScreen.INFO -> listOf(
                    SettingItem.Header("Aplikacja E2Stream dla Windows"),
                    SettingItem.Action("Wersja", appVersion()) {},
                    SettingItem.Action(
                        "Sprawdź i zainstaluj aktualizację",
                        updateStatus.ifBlank {
                            if (config.lastUpdateCheck > 0) "ostatnio: " + EpgTime.day(config.lastUpdateCheck / 1000) + " " +
                                EpgTime.clock(config.lastUpdateCheck / 1000) else "jeszcze nie sprawdzano"
                        },
                    ) { checkUpdate(manual = true) },
                    SettingItem.Toggle(
                        "Aktualizacja automatyczna",
                        config.autoUpdate,
                        "raz na dobę przy uruchomieniu sprawdza GitHuba i proponuje instalację",
                    ) { saveConfig(config.copy(autoUpdate = it)) },
                    SettingItem.Action(
                        "Kanał aktualizacji",
                        if (config.updateChannel == "test") "stabilne i testowe" else "tylko stabilne",
                    ) {
                        val channels = listOf("stable", "test")
                        choose("Kanał aktualizacji", listOf("tylko stabilne", "stabilne i testowe"), channels.indexOf(config.updateChannel)) {
                            saveConfig(config.copy(updateChannel = channels[it]))
                            updateStatus = ""
                        }
                    },
                    SettingItem.Action("Wydania na GitHubie", "wszystkie wersje") { openBrowser("$REPOSITORY_URL/releases") },
                    SettingItem.Action("Opis wersji Windows (README)") {
                        openBrowser("$REPOSITORY_URL/blob/windows/0.1.0-test/e2stream/desktop/README.md")
                    },
                    SettingItem.Action("Opis aplikacji (README)") { openBrowser("$REPOSITORY_URL/blob/main/e2stream/README.md") },
                    SettingItem.Header("Wtyczka EPG Scan"),
                    SettingItem.Action("Wtyczka na dekoderze", pluginStatus) { checkPlugin() },
                    SettingItem.Action("Opis wtyczki (README)") { openBrowser("$REPOSITORY_URL/blob/main/epgscan/EPGScan/README.md") },
                )
                SettingsScreen.LOGS -> listOf(
                    SettingItem.Header("Logi i diagnostyka"),
                    SettingItem.Toggle("Statystyki na ekranie", config.showStats) {
                        saveConfig(config.copy(showStats = it))
                    },
                    SettingItem.Action("Wyślij log na dekoder", "${config.ftpDir}/${DecoderUpload.logName()}") {
                        if (config.host.isBlank()) status = "Najpierw ustaw adres dekodera."
                        else {
                            status = "Wysyłam log na dekoder…"
                            scope.launch { uploadLog(manual = true) }
                        }
                    },
                    SettingItem.Toggle("Wysyłaj log na dekoder (co 2 min)", config.logFtp) {
                        saveConfig(config.copy(logFtp = it))
                    },
                    SettingItem.Action("Ostatnie wysłanie logu", logUploadStatus.ifBlank { "jeszcze nie wysyłano" }) {},
                    SettingItem.Action("Miejsce zapisu logów", AppLog.dirFor(config).toString()) {
                        editText("Miejsce zapisu logów", config.logDir, "puste = ${AppLog.defaultDir()}") { config.copy(logDir = it.trim()) }
                    },
                    SettingItem.Action("Otwórz katalog logów", "Eksplorator Windows") {
                        val dir = AppLog.dirFor(config).toFile().also { it.mkdirs() }
                        status = runCatching { java.awt.Desktop.getDesktop().open(dir) }
                            .fold({ "Otwarto katalog logów: $dir" }, { "Nie udało się otworzyć katalogu: ${it.message}" })
                    },
                    SettingItem.Action("Zrzuty TS przy zawieszeniu", "nie dotyczy — w Windows obraz odtwarza VLC (bez bufora TS aplikacji)") {},
                    SettingItem.Header("Pilot"),
                    SettingItem.Action(
                        "Śledzenie pilota",
                        if (remoteDiagnostics.enabled) "włączone (E2STREAM_HID_TRACE=1, wyjście na konsolę)"
                        else "wyłączone — uruchom z E2STREAM_HID_TRACE=1",
                    ) {},
                    SettingItem.Action("Test pilota", "podgląd odbieranych klawiszy") {
                        testEvents = emptyList()
                        navigator.push(SettingsScreen.REMOTE_TEST)
                    },
                )
            }

            // ---------- Klawisze ----------

            fun handleSettingsKey(key: VirtualRemoteKey): Boolean {
                if (isRemoteTestOpen() && navigator.choice == null) {
                    if (key == VirtualRemoteKey.BACK) {
                        val now = System.currentTimeMillis()
                        if (now - lastTestBackAt < 2000) {
                            lastTestBackAt = 0
                            navigator.back()
                        } else {
                            lastTestBackAt = now
                            status = "Naciśnij Wstecz jeszcze raz, aby wyjść z testu."
                        }
                    }
                    return true
                }
                return navigator.handleKey(key, settingsItems(navigator.screen))
            }

            val editorNavKeys = remember {
                setOf(
                    VirtualRemoteKey.UP, VirtualRemoteKey.DOWN, VirtualRemoteKey.LEFT, VirtualRemoteKey.RIGHT,
                    VirtualRemoteKey.OK, VirtualRemoteKey.VOICE, VirtualRemoteKey.DELETE,
                    VirtualRemoteKey.CHANNEL_UP, VirtualRemoteKey.CHANNEL_DOWN,
                )
            }
            /** Dyktowanie Windows (Win+H) do pola tekstowego, które ma fokus — mówi się do mikrofonu komputera. */
            fun startDictation() {
                scope.launch {
                    delay(250)
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val robot = java.awt.Robot()
                            robot.keyPress(KeyEvent.VK_WINDOWS)
                            robot.keyPress(KeyEvent.VK_H)
                            robot.delay(40)
                            robot.keyRelease(KeyEvent.VK_H)
                            robot.keyRelease(KeyEvent.VK_WINDOWS)
                        }.isSuccess
                    }
                    status = if (ok) "Dyktowanie Windows: mów teraz (mikrofon komputera); ponowne naciśnięcie kończy."
                    else "Nie udało się uruchomić dyktowania Windows (Win+H)."
                }
            }
            SideEffect { navigator.onVoice = ::startDictation }

            // ---------- Numer kanału z cyfr pilota ----------
            var numberBuffer by remember { mutableStateOf("") }
            var numberJob by remember { mutableStateOf<Job?>(null) }
            fun commitNumber() {
                numberJob?.cancel()
                numberJob = null
                val number = numberBuffer.toIntOrNull()
                numberBuffer = ""
                if (number == null) return
                val list = playList.ifEmpty { channels.filterNot { it.ref.startsWith(REC_PREFIX) } }
                val target = list.firstOrNull { it.number == number }
                if (target != null) startChannel(target) else status = "Brak kanału o numerze $number w bieżącym bukiecie."
            }
            fun digit(value: Int) {
                if (numberBuffer.length >= 4) numberBuffer = ""
                numberBuffer += value.toString()
                numberJob?.cancel()
                numberJob = scope.launch {
                    delay(1800)
                    commitNumber()
                }
            }

            // ---------- Długie OK: Menu, a przy pasku kanału — timeshift (jak w Androidzie) ----------
            fun openTimeshiftControls() {
                if (timeshift != null) {
                    bannerVisible = false
                    showPlaybackBar()
                } else {
                    quickPick = QuickPick(
                        "Timeshift",
                        listOf("Ustawienia timeshiftu", "Zamknij"),
                        0,
                        body = "Timeshift jest wyłączony. Włącz go w Ustawienia → Odtwarzanie i obraz → Timeshift " +
                            "(bufor na dysku komputera).",
                    ) { if (it == 0) openSettings(SettingsScreen.PLAYBACK) }
                }
            }
            fun longOk() {
                remoteDiagnostics.log { "key=OK long banner=$bannerVisible" }
                if (playingRecording == null && playingChannel != null && bannerVisible) openTimeshiftControls()
                else openMenu()
            }

            fun handleVirtualKey(key: VirtualRemoteKey): Boolean {
                if (showExitConfirmation) {
                    handleExitConfirmation(key)
                    return true
                }
                val now = System.currentTimeMillis()
                if (lastVirtualKey == key && now - lastVirtualKeyAt < 90) {
                    remoteDiagnostics.log { "key=$key ignored=repeat intervalMs=${now - lastVirtualKeyAt}" }
                    return true
                }
                lastVirtualKey = key
                lastVirtualKeyAt = now
                remoteDiagnostics.log { "key=$key settings=${navigator.screen} channelList=$showChannelList" }

                if (key == VirtualRemoteKey.POWER) {
                    currentRequestPower.value()
                    return true
                }
                when (key) {
                    VirtualRemoteKey.VOLUME_UP, VirtualRemoteKey.VOLUME_DOWN, VirtualRemoteKey.MUTE ->
                        if (!config.volumeKeysApp) return false
                    else -> Unit
                }
                when (key) {
                    VirtualRemoteKey.VOLUME_UP -> { changeVolume(1); return true }
                    VirtualRemoteKey.VOLUME_DOWN -> { changeVolume(-1); return true }
                    VirtualRemoteKey.MUTE -> { toggleMute(); return true }
                    else -> Unit
                }
                if (navigator.isVisible) return handleSettingsKey(key)
                if (numberBuffer.isNotEmpty() && !showMenu && quickPick == null && !showChannelList) {
                    when (key) {
                        VirtualRemoteKey.OK -> { commitNumber(); return true }
                        VirtualRemoteKey.BACK -> {
                            numberJob?.cancel()
                            numberBuffer = ""
                            return true
                        }
                        else -> Unit
                    }
                }
                if (showMenu) {
                    val result = navigation.handleMenuKey(key)
                    result.activated?.let(::runMenuItem)
                    return result.handled
                }
                quickPick?.let { pick ->
                    when (key) {
                        VirtualRemoteKey.UP -> quickPick = pick.copy(selected = (pick.selected - 1).mod(pick.options.size))
                        VirtualRemoteKey.DOWN -> quickPick = pick.copy(selected = (pick.selected + 1).mod(pick.options.size))
                        VirtualRemoteKey.CHANNEL_UP -> quickPick = pick.copy(selected = (pick.selected - 8).coerceAtLeast(0))
                        VirtualRemoteKey.CHANNEL_DOWN -> quickPick = pick.copy(selected = (pick.selected + 8).coerceAtMost(pick.options.lastIndex))
                        VirtualRemoteKey.OK -> {
                            quickPick = null
                            pick.onSelect(pick.selected)
                        }
                        VirtualRemoteKey.LEFT, VirtualRemoteKey.RIGHT -> {
                            val direction = if (key == VirtualRemoteKey.RIGHT) 1 else -1
                            when {
                                pick.onHorizontal != null -> pick.onHorizontal.invoke(direction)
                                direction < 0 -> {
                                    quickPick = null
                                    pick.onBack?.invoke()
                                }
                                else -> return false
                            }
                        }
                        VirtualRemoteKey.MENU -> {
                            quickPick = null
                            (pick.onMenu ?: pick.onBack)?.invoke()
                        }
                        VirtualRemoteKey.BACK -> {
                            quickPick = null
                            pick.onBack?.invoke()
                        }
                        else -> return false
                    }
                    return true
                }

                if (showChannelList) {
                    val bouquets = listBouquets()
                    when (key) {
                        VirtualRemoteKey.UP -> if (channels.isNotEmpty()) channelListSelected = (channelListSelected - 1 + channels.size) % channels.size
                        VirtualRemoteKey.DOWN -> if (channels.isNotEmpty()) channelListSelected = (channelListSelected + 1) % channels.size
                        VirtualRemoteKey.LEFT -> bouquetOffset(bouquets, selectedBouquet, -1)?.let(::selectBouquet)
                        VirtualRemoteKey.RIGHT -> bouquetOffset(bouquets, selectedBouquet, 1)?.let(::selectBouquet)
                        VirtualRemoteKey.OK -> channels.getOrNull(channelListSelected)?.let(::activateListRow)
                        VirtualRemoteKey.BACK -> if (selectedBouquet?.ref == REC_BOUQUET && recStack.isNotEmpty()) {
                            openRecordingDir(Recording(REC_UP, ""))
                        } else {
                            showChannelList = false
                        }
                        VirtualRemoteKey.HOME -> showChannelList = false
                        VirtualRemoteKey.INFO, VirtualRemoteKey.GUIDE -> channels.getOrNull(channelListSelected)
                            ?.takeUnless { it.ref.startsWith(REC_PREFIX) || selectedBouquet?.ref == REC_BOUQUET }
                            ?.let { showChannelEpg(it) }
                        VirtualRemoteKey.CHANNEL_UP -> if (channels.isNotEmpty()) {
                            channelListSelected = (channelListSelected - 8).coerceAtLeast(0)
                        }
                        VirtualRemoteKey.CHANNEL_DOWN -> if (channels.isNotEmpty()) {
                            channelListSelected = (channelListSelected + 8).coerceAtMost(channels.lastIndex)
                        }
                        VirtualRemoteKey.MENU -> {
                            showChannelList = false
                            openMenu()
                        }
                        else -> return false
                    }
                    return true
                }

                val session = vlcSession
                if (playingRecording != null && session != null) {
                    val handled = when (key) {
                        VirtualRemoteKey.LEFT -> { seekRecording(-10_000); true }
                        VirtualRemoteKey.RIGHT -> { seekRecording(30_000); true }
                        VirtualRemoteKey.UP -> { seekRecording(300_000); true }
                        VirtualRemoteKey.DOWN -> { seekRecording(-300_000); true }
                        VirtualRemoteKey.FAST_FORWARD -> { seekRecording(60_000); true }
                        VirtualRemoteKey.REWIND -> { seekRecording(-60_000); true }
                        VirtualRemoteKey.OK, VirtualRemoteKey.PLAY_PAUSE -> { togglePlaybackPause(); true }
                        VirtualRemoteKey.PLAY -> { if (session.isPaused) togglePlaybackPause(); true }
                        VirtualRemoteKey.PAUSE -> { if (!session.isPaused) togglePlaybackPause(); true }
                        VirtualRemoteKey.INFO -> { showPlaybackBar(); true }
                        VirtualRemoteKey.STOP -> { stopRecordingToList(); true }
                        VirtualRemoteKey.BACK -> {
                            if (playbackBarVisible && !session.isPaused) playbackBarVisible = false else stopRecordingToList()
                            true
                        }
                        else -> false
                    }
                    if (handled) return true
                }
                val ts = timeshift
                if (ts != null && session != null) {
                    val stepMs = config.tsStepSec * 1000L
                    val stepMinMs = config.tsStepMin * 60_000L
                    val barOpen = playbackBarVisible
                    val handled = when (key) {
                        VirtualRemoteKey.PLAY_PAUSE -> { togglePlaybackPause(); true }
                        VirtualRemoteKey.PLAY -> { if (session.isPaused) togglePlaybackPause(); true }
                        VirtualRemoteKey.PAUSE -> { if (!session.isPaused) togglePlaybackPause(); true }
                        VirtualRemoteKey.REWIND -> { timeshiftSeekBy(-stepMs); true }
                        VirtualRemoteKey.FAST_FORWARD -> { timeshiftSeekBy(stepMs); true }
                        VirtualRemoteKey.STOP -> { timeshiftGoLive(); true }
                        VirtualRemoteKey.LEFT -> barOpen.also { if (it) timeshiftSeekBy(-stepMs) }
                        VirtualRemoteKey.RIGHT -> barOpen.also { if (it) timeshiftSeekBy(stepMs) }
                        VirtualRemoteKey.DOWN -> barOpen.also { if (it) timeshiftSeekBy(-stepMinMs) }
                        VirtualRemoteKey.UP -> barOpen.also { if (it) timeshiftSeekBy(stepMinMs) }
                        VirtualRemoteKey.OK -> barOpen.also { if (it) togglePlaybackPause() }
                        VirtualRemoteKey.BACK -> when {
                            barOpen && !session.isPaused -> { playbackBarVisible = false; true }
                            config.tsBackLive && timeshiftShifted() -> { timeshiftGoLive(); true }
                            else -> false
                        }
                        else -> false
                    }
                    if (handled) return true
                }

                if (key in NavigationKeyMap.keys) {
                    val map = if (bannerVisible) {
                        NavigationKeyMap.resolve(NavigationKeyMap.bannerDefaults, config.navBanner)
                    } else {
                        NavigationKeyMap.resolve(NavigationKeyMap.noBannerDefaults, config.navNoBanner)
                    }
                    runNavAction(map[key] ?: NavAction.NONE)
                    return true
                }
                when (key) {
                    VirtualRemoteKey.CHANNEL_UP -> zap(true)
                    VirtualRemoteKey.CHANNEL_DOWN -> zap(false)
                    VirtualRemoteKey.SETTINGS, VirtualRemoteKey.HOME -> openSettings()
                    VirtualRemoteKey.MENU -> openMenu()
                    VirtualRemoteKey.PLAY_PAUSE -> togglePlaybackPause()
                    VirtualRemoteKey.PLAY -> if (vlcSession?.isPaused == true) togglePlaybackPause()
                    VirtualRemoteKey.PAUSE -> if (vlcSession?.isPaused == false) togglePlaybackPause()
                    VirtualRemoteKey.BACK -> when {
                        bannerVisible -> bannerVisible = false
                        navigation.controlsVisible -> navigation.toggleControls()
                        else -> status = "Menu: przycisk Menu na pilocie lub M na klawiaturze."
                    }
                    VirtualRemoteKey.INFO -> runNavAction(NavAction.EPG_EVENT)
                    VirtualRemoteKey.GUIDE, VirtualRemoteKey.TV_DATA -> runNavAction(NavAction.MULTI_EPG)
                    VirtualRemoteKey.SEARCH -> runNavAction(NavAction.EPG_SEARCH)
                    VirtualRemoteKey.VOICE -> {
                        openEpgSearch()
                        startDictation()
                    }
                    VirtualRemoteKey.DELETE -> Unit
                    VirtualRemoteKey.AUDIO -> runNavAction(NavAction.AUDIO)
                    VirtualRemoteKey.LAST_CHANNEL -> recall()
                    VirtualRemoteKey.REWIND, VirtualRemoteKey.FAST_FORWARD, VirtualRemoteKey.STOP ->
                        status = "Timeshift jest wyłączony — Ustawienia → Odtwarzanie i obraz → Timeshift."
                    VirtualRemoteKey.DIGIT_0, VirtualRemoteKey.DIGIT_1, VirtualRemoteKey.DIGIT_2,
                    VirtualRemoteKey.DIGIT_3, VirtualRemoteKey.DIGIT_4, VirtualRemoteKey.DIGIT_5,
                    VirtualRemoteKey.DIGIT_6, VirtualRemoteKey.DIGIT_7, VirtualRemoteKey.DIGIT_8,
                    VirtualRemoteKey.DIGIT_9 -> if (playingRecording == null) digit(key.ordinal - VirtualRemoteKey.DIGIT_0.ordinal)
                    else -> return false
                }
                return true
            }

            val okPress = remember { OkPress() }
            var okLongJob by remember { mutableStateOf<Job?>(null) }
            var okReleaseJob by remember { mutableStateOf<Job?>(null) }
            /** Długie OK ma znaczenie tylko podczas oglądania, bez otwartych okien. */
            fun okHoldContext(): Boolean =
                !showExitConfirmation && captureButton == null && !navigator.isVisible && !showMenu &&
                    quickPick == null && !showChannelList && numberBuffer.isEmpty()
            fun okReleased() {
                okReleaseJob?.cancel()
                okLongJob?.cancel()
                if (okPress.up()) {
                    lastVirtualKey = null
                    handleVirtualKey(VirtualRemoteKey.OK)
                }
            }
            /**
             * OK wciśnięte: false = obsłużyć zwykle (od razu). [autoReleaseMs] > 0 dla źródeł bez zdarzenia puszczenia
             * (WinLIRC podaje tylko powtórzenia) — brak kolejnego powtórzenia w tym czasie oznacza puszczenie.
             */
            fun okPressed(autoReleaseMs: Long = 0): Boolean {
                if (!okPress.isDown && !okHoldContext()) return false
                if (okPress.down(System.currentTimeMillis())) {
                    okLongJob?.cancel()
                    okLongJob = scope.launch {
                        delay(OK_LONG_MS)
                        if (okPress.longDue(System.currentTimeMillis()) && okHoldContext()) longOk()
                    }
                }
                if (autoReleaseMs > 0) {
                    okReleaseJob?.cancel()
                    okReleaseJob = scope.launch {
                        delay(autoReleaseMs)
                        okReleased()
                    }
                }
                return true
            }

            fun handleLegacyKey(keyCode: Int): Boolean = when (bindings[keyCode]) {
                RemoteAction.TIMESHIFT -> {
                    togglePlaybackPause()
                    true
                }
                RemoteAction.TS_REWIND -> {
                    if (playingRecording != null) seekRecording(-config.tsStepSec * 1000L)
                    else timeshiftSeekBy(-config.tsStepSec * 1000L)
                    true
                }
                RemoteAction.TS_FORWARD -> {
                    if (playingRecording != null) seekRecording(config.tsStepSec * 1000L)
                    else timeshiftSeekBy(config.tsStepSec * 1000L)
                    true
                }
                RemoteAction.TS_LIVE -> {
                    if (playingRecording != null) stopRecordingToList() else timeshiftGoLive()
                    true
                }
                RemoteAction.CHANNEL_UP -> { zap(true); true }
                RemoteAction.CHANNEL_DOWN -> { zap(false); true }
                RemoteAction.CHANNEL_LIST -> { openChannelList(); true }
                RemoteAction.CHANNEL_BANNER -> { runNavAction(NavAction.CHANNEL_BANNER); true }
                RemoteAction.MENU -> { openMenu(); true }
                RemoteAction.SETTINGS -> { openSettings(); true }
                RemoteAction.RECALL -> { recall(); true }
                RemoteAction.EPG_EVENT -> { runNavAction(NavAction.EPG_EVENT); true }
                RemoteAction.EPG_CHANNEL -> { runNavAction(NavAction.EPG_CHANNEL); true }
                RemoteAction.MULTI_EPG -> { runNavAction(NavAction.MULTI_EPG); true }
                RemoteAction.EPG_SEARCH -> { runNavAction(NavAction.EPG_SEARCH); true }
                RemoteAction.AUDIO -> { runNavAction(NavAction.AUDIO); true }
                RemoteAction.STATS -> {
                    saveConfig(config.copy(showStats = !config.showStats))
                    true
                }
                RemoteAction.NONE, null -> false
                else -> {
                    status = "${bindings[keyCode]?.label} nie jest jeszcze dostępne w wersji Windows."
                    true
                }
            }

            val sessionToClose = vlcSession
            DisposableEffect(sessionToClose) {
                onDispose { sessionToClose?.close() }
            }
            val timeshiftToClose = timeshift
            DisposableEffect(timeshiftToClose) {
                onDispose { timeshiftToClose?.let { ts -> runCatching { ts.close() } } }
            }
            LaunchedEffect(Unit) {
                if (config.tsMin <= 0) TimeshiftBuffer.cleanupAsync(TimeshiftBuffer.dirFor(config))
            }

            DisposableEffect(desktopWindow) {
                val dispatcher = java.awt.KeyEventDispatcher { event ->
                    val focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                    if (event.id == KeyEvent.KEY_RELEASED && okPress.isDown &&
                        UniversalRemoteMap.findKey(universalBindings, UniversalRemoteMap.keyId(event.keyCode)) == VirtualRemoteKey.OK
                    ) {
                        okReleased()
                        return@KeyEventDispatcher true
                    }
                    if (!belongsToApplication(focusedWindow, desktopWindow) ||
                        !isForegroundApplication() ||
                        event.id != KeyEvent.KEY_PRESSED ||
                        event.keyCode == KeyEvent.VK_UNDEFINED
                    ) {
                        return@KeyEventDispatcher false
                    }
                    inputMode.onKey()
                    val physicalId = UniversalRemoteMap.keyId(event.keyCode)
                    val virtualKey = UniversalRemoteMap.findKey(universalBindings, physicalId)
                    when {
                        showExitConfirmation -> {
                            val key = when (event.keyCode) {
                                KeyEvent.VK_ENTER -> VirtualRemoteKey.OK
                                KeyEvent.VK_ESCAPE -> VirtualRemoteKey.BACK
                                else -> virtualKey
                            }
                            handleExitConfirmation(key)
                            true
                        }
                        captureButton != null -> {
                            val button = captureButton ?: return@KeyEventDispatcher false
                            savePhysicalBinding(button, physicalId, RemoteKeyMap.keyName(event.keyCode))
                            true
                        }
                        // Edytor: strzałki i OK sterują klawiaturą ekranową, resztę znaków wpisuje pole tekstowe.
                        navigator.editor != null -> virtualKey in editorNavKeys && handleVirtualKey(virtualKey!!)
                        event.keyCode == KeyEvent.VK_F11 -> {
                            toggleFullscreen()
                            true
                        }
                        isRemoteTestOpen() && navigator.choice == null -> {
                            recordTest("Klawiatura", RemoteKeyMap.keyName(event.keyCode), virtualKey)
                            if (virtualKey == VirtualRemoteKey.BACK) handleVirtualKey(VirtualRemoteKey.BACK)
                            true
                        }
                        virtualKey == VirtualRemoteKey.OK && okPressed() -> true
                        else -> {
                            if (virtualKey != null) remoteDiagnostics.log { "source=keyboard key=$virtualKey" }
                            val handled = virtualKey != null && handleVirtualKey(virtualKey)
                            handled || !navigator.isVisible && !showMenu && !showChannelList && quickPick == null &&
                                handleLegacyKey(event.keyCode)
                        }
                    }
                }
                val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                manager.addKeyEventDispatcher(dispatcher)
                onDispose { manager.removeKeyEventDispatcher(dispatcher) }
            }

            DisposableEffect(desktopWindow) {
                val rawInput = if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) null else try {
                    RawHidInput(
                        isApplicationFocused = {
                            belongsToApplication(
                                KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow,
                                desktopWindow,
                            )
                        },
                        onKey = { physicalId, label ->
                            val receivedAt = System.nanoTime()
                            EventQueue.invokeLater {
                                val queueMs = (System.nanoTime() - receivedAt) / 1_000_000
                                if (!isForegroundApplication() || !belongsToApplication(
                                        KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow,
                                        desktopWindow,
                                    )
                                ) {
                                    remoteDiagnostics.log { "source=hid $label queueMs=$queueMs ignored=focus" }
                                    return@invokeLater
                                }
                                inputMode.onKey()
                                val button = captureButton
                                if (button != null) {
                                    remoteDiagnostics.log { "source=hid $label queueMs=$queueMs capture=$button" }
                                    savePhysicalBinding(button, physicalId, label)
                                } else {
                                    val key = UniversalRemoteMap.findKey(universalBindings, physicalId)
                                    remoteDiagnostics.log { "source=hid $label queueMs=$queueMs mapped=${key ?: "none"}" }
                                    if (isRemoteTestOpen() && navigator.choice == null) {
                                        recordTest("Pilot HID", UniversalRemoteMap.label(physicalId), key)
                                        if (key == VirtualRemoteKey.BACK) handleVirtualKey(key)
                                    } else if (key != VirtualRemoteKey.OK || !okPressed()) {
                                        key?.let(::handleVirtualKey)
                                    }
                                }
                            }
                        },
                        onRelease = { physicalId ->
                            EventQueue.invokeLater {
                                if (okPress.isDown &&
                                    UniversalRemoteMap.findKey(universalBindings, physicalId) == VirtualRemoteKey.OK
                                ) okReleased()
                            }
                        },
                        onError = { message ->
                            EventQueue.invokeLater {
                                remoteDiagnostics.log { "error=$message" }
                                hidDiagnostic = message
                                status = message
                            }
                        },
                        onDiagnostic = hidDiagnosticDispatcher::submit,
                        suppressBrowserKeys = { suppressBrowserKeys.get() },
                        isPowerKey = { it in globalPowerKeys.get() },
                        onPower = { currentRequestPower.value() },
                        interceptKey = intercept@{ physicalId, down ->
                            val volumeKey = physicalId in setOf("K:173", "K:174", "K:175")
                            val swallow = (captureActive.get() && volumeKey) || physicalId in volumeKeyIds.get()
                            if (!swallow) return@intercept false
                            if (down) EventQueue.invokeLater {
                                inputMode.onKey()
                                val label = UniversalRemoteMap.label(physicalId)
                                val key = UniversalRemoteMap.findKey(universalBindings, physicalId)
                                val button = captureButton
                                remoteDiagnostics.log { "source=hook $label mapped=${key ?: "none"}" }
                                when {
                                    showExitConfirmation -> Unit
                                    button != null -> savePhysicalBinding(button, physicalId, label)
                                    isRemoteTestOpen() && navigator.choice == null -> recordTest("Klawiatura", label, key)
                                    key != null -> handleVirtualKey(key)
                                }
                            }
                            true
                        },
                    )
                } catch (e: Exception) {
                    status = "Obsługa pilota HID niedostępna: ${e.message ?: e.javaClass.simpleName}"
                    remoteDiagnostics.log { "error=$status" }
                    hidDiagnostic = status
                    null
                }
                onDispose { rawInput?.close() }
            }

            DisposableEffect(config.lircEnabled, config.lircHost, config.lircPort) {
                if (!config.lircEnabled) {
                    lircState = "wyłączony"
                    return@DisposableEffect onDispose { }
                }
                val repeatable = setOf(
                    VirtualRemoteKey.UP, VirtualRemoteKey.DOWN, VirtualRemoteKey.LEFT, VirtualRemoteKey.RIGHT,
                    VirtualRemoteKey.CHANNEL_UP, VirtualRemoteKey.CHANNEL_DOWN,
                    VirtualRemoteKey.VOLUME_UP, VirtualRemoteKey.VOLUME_DOWN,
                )
                val client = LircClient(
                    host = config.lircHost,
                    port = config.lircPort,
                    onEvent = lirc@{ event ->
                        val id = event.physicalId
                        // Power działa globalnie — także gdy E2Stream nie jest w pierwszym planie.
                        if (event.repeat == 0 && id in globalPowerKeys.get()) {
                            remoteDiagnostics.log { "source=lirc $id power" }
                            currentRequestPower.value()
                            return@lirc
                        }
                        EventQueue.invokeLater {
                            val label = UniversalRemoteMap.label(id)
                            if (!isForegroundApplication() || !belongsToApplication(
                                    KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow,
                                    desktopWindow,
                                )
                            ) {
                                remoteDiagnostics.log { "source=lirc $label ignored=focus" }
                                return@invokeLater
                            }
                            inputMode.onKey()
                            val button = captureButton
                            val key = UniversalRemoteMap.findKey(universalBindings, id)
                            remoteDiagnostics.log { "source=lirc $label repeat=${event.repeat} mapped=${key ?: "none"}" }
                            when {
                                button != null -> if (event.repeat == 0) savePhysicalBinding(button, id, label)
                                isRemoteTestOpen() && navigator.choice == null -> if (event.repeat == 0) {
                                    recordTest("Pilot IR (LIRC)", label, key)
                                    if (key == VirtualRemoteKey.BACK) handleVirtualKey(key)
                                }
                                key == null -> Unit
                                key == VirtualRemoteKey.OK && okPressed(autoReleaseMs = 350) -> Unit
                                event.repeat == 0 || (event.repeat >= 3 && key in repeatable) -> handleVirtualKey(key)
                            }
                        }
                    },
                    onState = { state -> EventQueue.invokeLater { lircState = state } },
                )
                onDispose { client.close() }
            }

            // ---------- Okna ----------

            CompositionLocalProvider(
                LocalBackButtonVisible provides (config.backButtonMode == BackButtonMode.ALWAYS || inputMode.mouseActive),
            ) {
            val session = vlcSession
            val channel = playingChannel
            val playbackBar = playbackBarFor(
                visible = playbackBarVisible,
                tick = playbackTick,
                session = session,
                recording = playingRecording,
                timeshift = timeshift,
                pendingTarget = tsPendingTarget,
                channelName = channel?.name,
                config = config,
            )
            PlaybackHost(
                settingsVisible = navigator.isVisible,
                player = {
                    PlayerPage(
                        channel = channel,
                        session = session,
                        event = bannerNowNext.first ?: channel?.let { nowEvents[serviceKey(it.ref)] },
                        next = bannerNowNext.second,
                        owner = desktopWindow,
                        barsHidden = navigator.isVisible,
                        status = epgError ?: status,
                        bannerVisible = bannerVisible,
                        bannerFontScale = config.uiBannerFont / 100f,
                        onShowBanner = { bannerGeneration++ },
                        controlsVisible = navigation.controlsVisible,
                        onToggleControls = navigation::toggleControls,
                        videoScaleMode = config.videoScaleMode,
                        windowMode = windowMode,
                        onWindowMode = ::pickWindowMode,
                        volumeLabel = if (config.appMuted) "Wyciszone" else "Głośność ${config.appVolume}%",
                        onVolume = ::changeVolume,
                        onMute = ::toggleMute,
                        onAudio = ::showAudioPick,
                        onScaleMenu = ::pickVideoScale,
                        onChannels = ::openChannelList,
                        onMenu = ::openMenu,
                        onBack = { stopPlayback("Odtwarzanie zatrzymane.") },
                        playback = playbackBar,
                        onTogglePause = ::togglePlaybackPause,
                        onSeek = when {
                            playingRecording != null -> { forward -> seekRecording(if (forward) 30_000 else -10_000) }
                            timeshift != null -> { forward ->
                                timeshiftSeekBy((if (forward) 1 else -1) * config.tsStepSec * 1000L)
                            }
                            else -> null
                        },
                        onLive = if (timeshift != null) ::timeshiftGoLive else null,
                        picon = channel?.let { piconVersion; Picons.image(it) },
                        piconStyle = piconStyle(config),
                        bannerIconScale = config.uiBannerIcon / 100f,
                        showStats = config.showStats,
                        statusSeconds = config.infoDialogSec,
                        showStatusMessages = !config.hideInfoDialogs,
                    )
                },
                settings = {
                    SettingsWindow(
                        navigator = navigator,
                        items = settingsItems(navigator.screen),
                        colors = colorScheme,
                        status = status,
                        captureLabel = captureButton?.label,
                        onCancelCapture = {
                            captureButton = null
                            status = "Przypisywanie anulowane."
                        },
                        onWindow = { settingsWindow = it },
                        testContent = { RemoteTestPanel(testEvents, "$hidDiagnostic\nPilot IR (WinLIRC/LIRC): $lircState") },
                    )
                },
            )
            if (showChannelList && !navigator.isVisible) {
                ChannelListDialog(
                    bouquet = selectedBouquet,
                    bouquets = listBouquets(),
                    channels = channels,
                    selected = channelListSelected,
                    nowEvents = nowEvents,
                    busy = busy,
                    fontScale = config.uiListFont / 100f,
                    onClose = { showChannelList = false },
                    onSelectedChange = { channelListSelected = it },
                    onBouquetSelected = ::selectBouquet,
                    onPlay = ::activateListRow,
                    piconFor = { piconVersion; Picons.image(it) },
                    piconStyle = piconStyle(config),
                    iconScale = config.uiListIcon / 100f,
                    recordings = if (selectedBouquet?.ref == REC_BOUQUET) recRows else emptyMap(),
                )
            }
            if (showMenu && !navigator.isVisible) {
                MenuDialog(
                    items = navigation.menuItems,
                    selected = navigation.menuSelected,
                    colors = colorScheme,
                    onClose = { showMenu = false },
                    onSelect = { item ->
                        showMenu = false
                        runMenuItem(item)
                    },
                )
                LaunchedEffect(navigation.menuSelected) {
                    if (config.actionDialogSec <= 0) return@LaunchedEffect
                    delay(config.actionDialogSec * 1000L)
                    showMenu = false
                }
            }
            quickPick?.takeIf { !navigator.isVisible && !showMenu }?.let { pick ->
                key(pick.tag ?: pick.title, pick.options.size, pick.headers.size) {
                    QuickPickDialog(
                        pick = pick,
                        colors = colorScheme,
                        onClose = {
                            quickPick = null
                            pick.onBack?.invoke()
                        },
                        onSelect = { index ->
                            quickPick = null
                            pick.onSelect(index)
                        },
                    )
                }
                LaunchedEffect(pick) {
                    val seconds = pick.timeoutSec ?: if (pick.autoClose) config.actionDialogSec else 0
                    if (seconds <= 0) return@LaunchedEffect
                    delay(seconds * 1000L)
                    if (quickPick === pick) quickPick = null
                }
            }
            if (volumeOsdGeneration > 0) {
                LaunchedEffect(volumeOsdGeneration) {
                    volumeOsdVisible = true
                    delay(2_000)
                    volumeOsdVisible = false
                }
            }
            if (volumeOsdVisible && !navigator.isVisible) {
                VolumeOsd(
                    volume = config.appVolume,
                    muted = config.appMuted,
                    owner = desktopWindow,
                    colors = colorScheme,
                )
            }
            if (showExitConfirmation) {
                LaunchedEffect(Unit) {
                    if (config.confirmDialogSec <= 0) return@LaunchedEffect
                    delay(config.confirmDialogSec * 1000L)
                    cancelExitConfirmation()
                }
                DialogWindow(
                    onCloseRequest = ::cancelExitConfirmation,
                    title = "Wyłączyć E2Stream?",
                    state = rememberDialogState(width = 440.dp, height = 220.dp),
                    undecorated = true,
                    resizable = false,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = colorScheme.surface,
                        contentColor = colorScheme.onSurface,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Wyłączyć E2Stream?", style = MaterialTheme.typography.titleLarge)
                            Text("Odtwarzanie zostanie zatrzymane.")
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = ::exitApplication) { Text("OK — wyłącz") }
                                OutlinedButton(onClick = ::cancelExitConfirmation) { Text("Wróć — anuluj") }
                            }
                        }
                    }
                }
            }
            }
        }
    }
    }
}

@Composable
private fun MenuDialog(
    items: List<DesktopMenuItem>,
    selected: Int,
    colors: androidx.compose.material3.ColorScheme,
    onClose: () -> Unit,
    onSelect: (DesktopMenuItem) -> Unit,
) {
    DialogWindow(
        onCloseRequest = onClose,
        title = "E2Stream",
        state = rememberDialogState(width = 460.dp, height = (150 + items.size * 54).dp),
        undecorated = true,
        resizable = false,
    ) {
        MaterialTheme(colorScheme = colors) {
            Surface(Modifier.fillMaxSize(), color = colors.surface, contentColor = colors.onSurface) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Menu"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = colors.primary)
                        BackButton(onClick = onClose)
                    }
                    items.forEachIndexed { index, item ->
                        val isSelected = index == selected
                        Text(
                            tr(item.label),
                            modifier = Modifier.fillMaxWidth()
                                .background(if (isSelected) colors.primaryContainer else Color.Transparent, RoundedCornerShape(10.dp))
                                .clickable { onSelect(item) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            color = if (isSelected) colors.onPrimaryContainer else colors.onSurface,
                            fontSize = 20.sp,
                        )
                    }
                }
            }
        }
    }
}

private fun belongsToApplication(window: AwtWindow?, mainWindow: AwtWindow): Boolean {
    var current = window
    while (current != null) {
        if (current === mainWindow) return true
        current = current.owner
    }
    return false
}
