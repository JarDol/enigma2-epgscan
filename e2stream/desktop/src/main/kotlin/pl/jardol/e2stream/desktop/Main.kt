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
            fun openMenu() = navigation.openMenu(DesktopMenuItem.visible(config.menuOrder, config.menuHidden))
            LaunchedEffect(config.appLanguage) { AppLanguage.apply(config.appLanguage) }
            var lastVirtualKey by remember { mutableStateOf<VirtualRemoteKey?>(null) }
            var lastVirtualKeyAt by remember { mutableLongStateOf(0L) }
            SideEffect {
                suppressBrowserKeys.set(
                    captureButton != null || universalBindings.values.any { keys -> keys.any { it.startsWith("H:") } },
                )
                globalPowerKeys.set(if (captureButton == null) universalBindings[VirtualRemoteKey.POWER].orEmpty() else emptySet())
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

            fun closeTimeshift() {
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

            fun sessionOrNew(): VlcSession {
                val session = vlcSession ?: VlcSession { status = it }.also { vlcSession = it }
                session.onFinished = { finishedHandler.get()?.invoke() }
                session.setVideoScale(config.videoScaleMode)
                return session
            }

            fun startChannel(channel: Channel) {
                saveRecordingPosition()
                playingRecording = null
                closeTimeshift()
                try {
                    val session = sessionOrNew()
                    val url = DecoderClient(config).streamUrl(channel)
                    val ts = if (config.tsMin <= 0) null else runCatching {
                        TimeshiftSession(
                            TimeshiftBuffer(TimeshiftBuffer.dirFor(config), config.tsMin),
                            url,
                            TimeshiftSession.basicAuth(config),
                        )
                    }.onFailure { AppLog.i("timeshift: nie udało się uruchomić (${it.message})") }.getOrNull()
                    timeshift = ts
                    session.play(ts?.liveUrl() ?: url, config)
                    val current = playingChannel
                    if (current != null && current.ref != channel.ref) previousChannel = current
                    playingChannel = channel
                    if (selectedBouquet?.ref != REC_BOUQUET && channels.any { it.ref == channel.ref }) playList = channels
                    captureButton = null
                    status = "Otwieranie strumienia: ${channel.name}"
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
                    status = "Kanały w bukiecie „${bouquet.name}”: ${channels.size}."
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
                    status = "Połączono przez ${if (result.usesPlugin) "EPG Scan" else "OpenWebif"}; bukiety: ${result.bouquets.size}."
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

            LaunchedEffect(Unit) {
                if (config.host.isNotBlank()) connect(autoStart = true)
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

            fun runNavAction(action: NavAction) {
                when (action) {
                    NavAction.NONE -> Unit
                    NavAction.CHANNEL_BANNER ->
                        if (playingChannel != null) bannerGeneration++ else status = "Nie odtwarzasz żadnego kanału."
                    NavAction.CHANNEL_LIST -> openChannelList()
                    NavAction.EPG_EVENT -> unavailable("EPG wydarzenia")
                    NavAction.EPG_CHANNEL -> unavailable("EPG kanału")
                    NavAction.MULTI_EPG -> unavailable("Multi-EPG")
                    NavAction.EPG_SEARCH -> unavailable("Wyszukiwanie EPG")
                    NavAction.AUDIO -> unavailable("Wybór ścieżki dźwięku")
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
                        status = "Brak zaplanowanych przełączeń — dodasz je z opisu audycji w EPG (EPG na Windows w przygotowaniu)."
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
                    DesktopMenuItem.EXIT -> {
                        powerDialogOpen.set(true)
                        showExitConfirmation = true
                    }
                }
            }

            // ---------- Pozycje ustawień ----------

            fun choose(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
                navigator.openChoice(SettingsChoice(title, options, selected.coerceAtLeast(0), onSelect))
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
                    status = "Szukam dekoderów w sieci lokalnej…"
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
                            saveConfig(config.copy(host = found[it].ip), "Wybrano dekoder ${found[it].ip}.")
                        }
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
                    SettingItem.Header("Dźwięk"),
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
                    SettingItem.Header("EPG (wydarzenia, EPG kanału, Multi EPG, szukanie) — okna EPG w przygotowaniu"),
                    percentItem("Czcionka okien EPG", config.uiEpgFont) { config.copy(uiEpgFont = it) },
                    SettingItem.Header("Pasek kanału"),
                    percentItem("Czcionka paska kanału", config.uiBannerFont) { config.copy(uiBannerFont = it) },
                    percentItem("Ikona na pasku kanału", config.uiBannerIcon) { config.copy(uiBannerIcon = it) },
                )
                SettingsScreen.EPG -> listOf(
                    SettingItem.Header("EPG — pełny przewodnik w przygotowaniu (harmonogram jest zapisywany na zapas)"),
                    SettingItem.Action(
                        "Aktualizacja z EPG Scan",
                        (if (config.epgUpdateMode == DesktopConfig.EPG_UPDATE_STARTUP) "przy każdym uruchomieniu" else "raz dziennie") +
                            " — na razie steruje pobieraniem ikon",
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
                    SettingItem.Action("Bieżąca audycja", epgError ?: "OpenWebif, odświeżana co minutę (${nowEvents.size} kanałów)") {
                        epgRefresh++
                        status = "Odświeżam bieżące EPG…"
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
                        (if (config.epgChannelSec == 0) "bez zamykania" else "${config.epgChannelSec} s") + " • EPG w przygotowaniu",
                    ) {
                        editNumber("Czas EPG kanału (s)", config.epgChannelSec, 0..600) { config.copy(epgChannelSec = it) }
                    },
                    SettingItem.Action(
                        "Czas Multi EPG (s, 0 = bez zamykania)",
                        (if (config.epgMultiSec == 0) "bez zamykania" else "${config.epgMultiSec} s") + " • EPG w przygotowaniu",
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
                    SettingItem.Action("Wydania na GitHubie", "sprawdź nowe wersje") { openBrowser("$REPOSITORY_URL/releases") },
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
                if (navigator.isVisible) return handleSettingsKey(key)
                if (showMenu) {
                    val result = navigation.handleMenuKey(key)
                    result.activated?.let(::runMenuItem)
                    return result.handled
                }
                quickPick?.let { pick ->
                    when (key) {
                        VirtualRemoteKey.UP -> quickPick = pick.copy(selected = (pick.selected - 1).mod(pick.options.size))
                        VirtualRemoteKey.DOWN -> quickPick = pick.copy(selected = (pick.selected + 1).mod(pick.options.size))
                        VirtualRemoteKey.OK -> {
                            quickPick = null
                            pick.onSelect(pick.selected)
                        }
                        VirtualRemoteKey.BACK, VirtualRemoteKey.LEFT, VirtualRemoteKey.MENU -> {
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
                    VirtualRemoteKey.AUDIO -> runNavAction(NavAction.AUDIO)
                    VirtualRemoteKey.LAST_CHANNEL -> recall()
                    VirtualRemoteKey.REWIND, VirtualRemoteKey.FAST_FORWARD, VirtualRemoteKey.STOP ->
                        status = "Timeshift jest wyłączony — Ustawienia → Odtwarzanie i obraz → Timeshift."
                    VirtualRemoteKey.DIGIT_0, VirtualRemoteKey.DIGIT_1, VirtualRemoteKey.DIGIT_2,
                    VirtualRemoteKey.DIGIT_3, VirtualRemoteKey.DIGIT_4, VirtualRemoteKey.DIGIT_5,
                    VirtualRemoteKey.DIGIT_6, VirtualRemoteKey.DIGIT_7, VirtualRemoteKey.DIGIT_8,
                    VirtualRemoteKey.DIGIT_9 -> unavailable("Wybór kanału numerem")
                    else -> return false
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
                        // Edytor tekstu sam obsługuje wpisywane znaki, Enter i Esc.
                        navigator.editor != null -> false
                        event.keyCode == KeyEvent.VK_F11 -> {
                            toggleFullscreen()
                            true
                        }
                        isRemoteTestOpen() && navigator.choice == null -> {
                            recordTest("Klawiatura", RemoteKeyMap.keyName(event.keyCode), virtualKey)
                            if (virtualKey == VirtualRemoteKey.BACK) handleVirtualKey(VirtualRemoteKey.BACK)
                            true
                        }
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
                                    } else {
                                        key?.let(::handleVirtualKey)
                                    }
                                }
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
                        event = channel?.let { nowEvents[serviceKey(it.ref)] },
                        status = epgError ?: status,
                        bannerVisible = bannerVisible,
                        bannerFontScale = config.uiBannerFont / 100f,
                        onShowBanner = { bannerGeneration++ },
                        controlsVisible = navigation.controlsVisible,
                        onToggleControls = navigation::toggleControls,
                        videoScaleMode = config.videoScaleMode,
                        windowMode = windowMode,
                        onWindowMode = ::pickWindowMode,
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
                    if (!pick.autoClose || config.actionDialogSec <= 0) return@LaunchedEffect
                    delay(config.actionDialogSec * 1000L)
                    if (quickPick === pick) quickPick = null
                }
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
