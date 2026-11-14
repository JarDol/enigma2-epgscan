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
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.Window as AwtWindow
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

private const val REPOSITORY_URL = "https://github.com/JarDol/enigma2-epgscan"
private val testTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun appVersion(): String = System.getProperty("jpackage.app-version") ?: "wersja deweloperska"

fun main() {
    val remoteDiagnostics = RemoteDiagnostics.fromEnvironment()
    val instance = SingleInstance.startOrSignal() ?: return
    application {
        val mainWindowState = rememberWindowState(placement = WindowPlacement.Maximized)
        fun toggleMaximized() {
            mainWindowState.placement = if (mainWindowState.placement == WindowPlacement.Maximized) {
                WindowPlacement.Floating
            } else WindowPlacement.Maximized
        }
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
            var bannerVisible by remember { mutableStateOf(false) }
            var bannerGeneration by remember { mutableIntStateOf(0) }
            var pinUnlockedUntil by remember { mutableLongStateOf(0L) }
            var testEvents by remember { mutableStateOf<List<RemoteTestEvent>>(emptyList()) }
            var lastTestBackAt by remember { mutableLongStateOf(0L) }
            val scope = rememberCoroutineScope()
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
                if (bouquet == null) return@LaunchedEffect
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

            fun startChannel(channel: Channel) {
                val existingSession = vlcSession
                var newSession: VlcSession? = null
                try {
                    val session = existingSession ?: VlcSession { status = it }.also { newSession = it }
                    session.setVideoScale(config.videoScaleMode)
                    val url = DecoderClient(config).streamUrl(channel)
                    session.play(url, config)
                    if (existingSession == null) vlcSession = session
                    val current = playingChannel
                    if (current != null && current.ref != channel.ref) previousChannel = current
                    playingChannel = channel
                    captureButton = null
                    status = "Otwieranie strumienia: ${channel.name}"
                    saveConfig(
                        config.copy(
                            lastBouquetRef = selectedBouquet?.ref ?: config.lastBouquetRef,
                            lastChannelRef = channel.ref,
                        ),
                    )
                } catch (e: Exception) {
                    newSession?.close()
                    status = e.message ?: "Nie udało się uruchomić VLC."
                }
            }

            fun restartPlayback() {
                val channel = playingChannel ?: return
                vlcSession = null
                startChannel(channel)
            }

            suspend fun loadBouquetChannels(bouquet: Bouquet): Boolean {
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
                val current = channels.indexOfFirst { it.ref == playingChannel?.ref }
                if (current < 0 || channels.isEmpty()) return false
                val next = (current + offset).mod(channels.size)
                startChannel(channels[next])
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
                channelListSelected = channels.indexOfFirst { it.ref == playingChannel?.ref }.coerceAtLeast(0)
                showChannelList = true
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
                    NavAction.MENU -> navigation.openMenu()
                    NavAction.SETTINGS -> openSettings()
                    NavAction.RECALL -> recall()
                    NavAction.CH_UP -> zap(true)
                    NavAction.CH_DOWN -> zap(false)
                    NavAction.PLAY_PAUSE -> vlcSession?.togglePause()
                }
            }

            fun runMenuItem(item: DesktopMenuItem) {
                when (item) {
                    DesktopMenuItem.CHANNEL_LIST -> openChannelList()
                    DesktopMenuItem.SETTINGS -> openSettings()
                    DesktopMenuItem.REMOTE_TEST -> openSettings(SettingsScreen.REMOTE, SettingsScreen.REMOTE_TEST)
                    DesktopMenuItem.WINDOW -> toggleMaximized()
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
                    SettingItem.Action(section.title, section.description) {
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
                        when {
                            config.resumeLast -> status = "Najpierw wyłącz „Po uruchomieniu wróć do ostatniego kanału”."
                            channels.isEmpty() -> status = "Najpierw połącz się z dekoderem i wybierz bukiet."
                            else -> choose(
                                "Kanał startowy (${selectedBouquet?.name.orEmpty()})",
                                listOf("pierwszy kanał") + channels.map { "${it.number}. ${it.name}" },
                                channels.indexOfFirst { it.ref == config.startChannelRef } + 1,
                            ) { index ->
                                val channel = channels.getOrNull(index - 1)
                                saveConfig(
                                    config.copy(
                                        startBouquetRef = if (channel == null) "" else selectedBouquet?.ref.orEmpty(),
                                        startChannelRef = channel?.ref.orEmpty(),
                                        startChannelName = channel?.name.orEmpty(),
                                    ),
                                    "Kanał startowy: ${channel?.name ?: "pierwszy kanał"}.",
                                )
                            }
                        }
                    },
                    SettingItem.Toggle("Odwróć ▲ / ▼ przy zmianie kanału", config.invertUpDown) {
                        saveConfig(config.copy(invertUpDown = it))
                    },
                    SettingItem.Header("Czasy okien"),
                    SettingItem.Action("Czas paska kanału (s)", "${config.bannerSec} s") {
                        editNumber("Czas paska kanału (s)", config.bannerSec, 1..30) { config.copy(bannerSec = it) }
                    },
                    SettingItem.Action(
                        "Czas listy kanałów (s, 0 = bez zamykania)",
                        if (config.channelListSec == 0) "bez zamykania" else "${config.channelListSec} s",
                    ) {
                        editNumber("Czas listy kanałów (s)", config.channelListSec, 0..600) { config.copy(channelListSec = it) }
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

                if (showChannelList) {
                    val bouquets = visibleBouquets()
                    when (key) {
                        VirtualRemoteKey.UP -> if (channels.isNotEmpty()) channelListSelected = (channelListSelected - 1 + channels.size) % channels.size
                        VirtualRemoteKey.DOWN -> if (channels.isNotEmpty()) channelListSelected = (channelListSelected + 1) % channels.size
                        VirtualRemoteKey.LEFT -> bouquetOffset(bouquets, selectedBouquet, -1)?.let(::selectBouquet)
                        VirtualRemoteKey.RIGHT -> bouquetOffset(bouquets, selectedBouquet, 1)?.let(::selectBouquet)
                        VirtualRemoteKey.OK -> channels.getOrNull(channelListSelected)?.let {
                            showChannelList = false
                            startChannel(it)
                        }
                        VirtualRemoteKey.BACK, VirtualRemoteKey.HOME -> showChannelList = false
                        VirtualRemoteKey.CHANNEL_UP -> if (channels.isNotEmpty()) {
                            channelListSelected = (channelListSelected - 8).coerceAtLeast(0)
                        }
                        VirtualRemoteKey.CHANNEL_DOWN -> if (channels.isNotEmpty()) {
                            channelListSelected = (channelListSelected + 8).coerceAtMost(channels.lastIndex)
                        }
                        VirtualRemoteKey.MENU -> {
                            showChannelList = false
                            navigation.openMenu()
                        }
                        else -> return false
                    }
                    return true
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
                    VirtualRemoteKey.MENU -> navigation.openMenu()
                    VirtualRemoteKey.PLAY_PAUSE -> vlcSession?.togglePause()
                    VirtualRemoteKey.PLAY -> vlcSession?.resume()
                    VirtualRemoteKey.PAUSE -> vlcSession?.pause()
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
                    VirtualRemoteKey.REWIND, VirtualRemoteKey.FAST_FORWARD, VirtualRemoteKey.STOP -> unavailable("Timeshift")
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
                    vlcSession?.togglePause()
                    status = "Odtwarzanie wstrzymane lub wznowione."
                    true
                }
                RemoteAction.CHANNEL_UP -> { zap(true); true }
                RemoteAction.CHANNEL_DOWN -> { zap(false); true }
                RemoteAction.CHANNEL_LIST -> { openChannelList(); true }
                RemoteAction.CHANNEL_BANNER -> { runNavAction(NavAction.CHANNEL_BANNER); true }
                RemoteAction.MENU -> { navigation.openMenu(); true }
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
                            toggleMaximized()
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
                            handled || !navigator.isVisible && !showMenu && !showChannelList && handleLegacyKey(event.keyCode)
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
                        isMaximized = mainWindowState.placement == WindowPlacement.Maximized,
                        onToggleMaximized = ::toggleMaximized,
                        onScaleChange = {
                            saveConfig(config.copy(videoScaleMode = it))
                            session?.setVideoScale(it)
                        },
                        onChannels = ::openChannelList,
                        onMenu = navigation::openMenu,
                        onBack = {
                            showChannelList = false
                            vlcSession = null
                            playingChannel = null
                            status = "Odtwarzanie zatrzymane."
                        },
                        picon = channel?.let { piconVersion; Picons.image(it) },
                        piconStyle = piconStyle(config),
                        bannerIconScale = config.uiBannerIcon / 100f,
                        showStats = config.showStats,
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
                    bouquets = visibleBouquets(),
                    channels = channels,
                    selected = channelListSelected,
                    nowEvents = nowEvents,
                    busy = busy,
                    fontScale = config.uiListFont / 100f,
                    onClose = { showChannelList = false },
                    onSelectedChange = { channelListSelected = it },
                    onBouquetSelected = ::selectBouquet,
                    onPlay = {
                        showChannelList = false
                        startChannel(it)
                    },
                    piconFor = { piconVersion; Picons.image(it) },
                    piconStyle = piconStyle(config),
                    iconScale = config.uiListIcon / 100f,
                )
            }
            if (showMenu && !navigator.isVisible) {
                MenuDialog(
                    selected = navigation.menuSelected,
                    colors = colorScheme,
                    onClose = { showMenu = false },
                    onSelect = { item ->
                        showMenu = false
                        runMenuItem(item)
                    },
                )
            }
            if (showExitConfirmation) {
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
    selected: Int,
    colors: androidx.compose.material3.ColorScheme,
    onClose: () -> Unit,
    onSelect: (DesktopMenuItem) -> Unit,
) {
    val items = DesktopMenuItem.entries
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
                        Text("Menu", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = colors.primary)
                        BackButton(onClick = onClose)
                    }
                    items.forEachIndexed { index, item ->
                        val isSelected = index == selected
                        Text(
                            item.label,
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
