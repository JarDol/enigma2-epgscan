package pl.jardol.e2stream.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.KeyEvent
import java.awt.KeyboardFocusManager
import java.awt.Frame
import java.awt.Window as AwtWindow
import javax.swing.SwingUtilities
import java.awt.EventQueue
import javax.swing.JOptionPane
import java.util.concurrent.atomic.AtomicBoolean

fun main() {
    val instance = SingleInstance.startOrSignal() ?: return
    application {
        val mainWindowState = rememberWindowState(placement = WindowPlacement.Fullscreen)
        Window(
            onCloseRequest = ::exitApplication,
            title = "E2Stream",
            state = mainWindowState,
        ) {
        val colorScheme = appColorScheme(isSystemInDarkTheme())
        MaterialTheme(colorScheme = colorScheme) {
            val desktopWindow = this.window
            var settingsWindow by remember { mutableStateOf<java.awt.Window?>(null) }
            val loadedConfig = remember { runCatching { DesktopConfig.load() } }
            var config by remember { mutableStateOf(loadedConfig.getOrDefault(DesktopConfig())) }
            var page by remember { mutableStateOf(DesktopPage.CONNECTION) }
            var status by remember {
                mutableStateOf(
                    loadedConfig.exceptionOrNull()?.let { "Nie udało się odczytać ustawień: ${it.message}" }
                        ?: "Podłącz aplikację do dekodera Enigma2.",
                )
            }
            var busy by remember { mutableStateOf(false) }
            var catalog by remember { mutableStateOf<DecoderCatalog?>(null) }
            var selectedBouquet by remember { mutableStateOf<Bouquet?>(null) }
            var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
            var nowEvents by remember { mutableStateOf<Map<String, NowEvent>>(emptyMap()) }
            var epgError by remember { mutableStateOf<String?>(null) }
            var showChannelList by remember { mutableStateOf(false) }
            var channelListSelected by remember { mutableIntStateOf(0) }
            var captureButton by remember { mutableStateOf<VirtualRemoteKey?>(null) }
            var selectedRemoteButton by remember { mutableStateOf(VirtualRemoteKey.OK) }
            var hidDiagnostic by remember { mutableStateOf("Oczekiwanie na uruchomienie odbiornika HID.") }
            val hidDiagnosticDispatcher = remember {
                LatestValueDispatcher<String>(
                    dispatch = { action -> EventQueue.invokeLater(action) },
                    onValue = { hidDiagnostic = it },
                )
            }
            val suppressBrowserKeys = remember { AtomicBoolean(false) }
            var bindings by remember { mutableStateOf(RemoteKeyMap.defaults + RemoteKeyMap.parse(config.remoteBindings)) }
            var universalBindings by remember {
                mutableStateOf<Map<VirtualRemoteKey, Set<String>>>(
                    UniversalRemoteMap.parse(config.universalRemoteBindings),
                )
            }
            var vlcSession by remember { mutableStateOf<VlcSession?>(null) }
            var playingChannel by remember { mutableStateOf<Channel?>(null) }
            val scope = rememberCoroutineScope()
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            var lastVirtualKey by remember { mutableStateOf<VirtualRemoteKey?>(null) }
            var lastVirtualKeyAt by remember { mutableLongStateOf(0L) }
            SideEffect {
                suppressBrowserKeys.set(
                    captureButton != null || universalBindings.values.any { keys -> keys.any { it.startsWith("H:") } },
                )
            }

            DisposableEffect(instance) {
                instance.onPowerRequest {
                    SwingUtilities.invokeLater {
                        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                        val appWasActive = belongsToApplication(focused, desktopWindow)
                        if (appWasActive) {
                            val answer = JOptionPane.showConfirmDialog(
                                focused ?: desktopWindow,
                                "Odtwarzanie zostanie zatrzymane.",
                                "Zamknąć E2Stream?",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.QUESTION_MESSAGE,
                            )
                            if (answer == JOptionPane.YES_OPTION) exitApplication()
                        } else {
                            val target = settingsWindow?.takeIf { it.isShowing } ?: desktopWindow
                            if (target is Frame && target.extendedState and Frame.ICONIFIED != 0) {
                                target.extendedState = target.extendedState and Frame.ICONIFIED.inv()
                            }
                            target.isVisible = true
                            target.toFront()
                            target.requestFocus()
                        }
                    }
                }
                onDispose { instance.close() }
            }

            LaunchedEffect(selectedBouquet, config.host, config.httpPort, config.username, config.password) {
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

            fun startChannel(channel: Channel) {
                val existingSession = vlcSession
                var newSession: VlcSession? = null
                try {
                    val session = existingSession ?: VlcSession { status = it }.also { newSession = it }
                    session.setVideoScale(config.videoScaleMode)
                    val url = DecoderClient(config).streamUrl(channel)
                    session.play(url, config)
                    if (existingSession == null) vlcSession = session
                    playingChannel = channel
                    captureButton = null
                    page = DesktopPage.PLAYER
                    status = "Otwieranie strumienia: ${channel.name}"
                } catch (e: Exception) {
                    newSession?.close()
                    status = e.message ?: "Nie udało się uruchomić VLC."
                }
            }

            fun selectBouquet(bouquet: Bouquet) {
                selectedBouquet = bouquet
                channels = emptyList()
                nowEvents = emptyMap()
                channelListSelected = 0
                val currentCatalog = catalog ?: return
                scope.launch {
                    busy = true
                    try {
                        channels = withContext(Dispatchers.IO) {
                            DecoderClient(config).loadChannels(bouquet, currentCatalog.usesPlugin)
                        }
                        status = "Kanały w bukiecie „${bouquet.name}”: ${channels.size}."
                    } catch (e: Exception) {
                        channels = emptyList()
                        status = e.message ?: "Nie udało się pobrać kanałów."
                    } finally {
                        busy = false
                    }
                }
            }

            fun savePhysicalBinding(button: VirtualRemoteKey, id: String, label: String) {
                if (button == VirtualRemoteKey.POWER) {
                    status = "Przypisz Power w ustawieniach pilota jako uruchomienie E2Stream."
                    captureButton = null
                    return
                }
                universalBindings = UniversalRemoteMap.assign(universalBindings, button, id)
                config = config.copy(universalRemoteBindings = UniversalRemoteMap.encode(universalBindings))
                status = runCatching { config.save() }
                    .fold(
                        onSuccess = { "Przypisano $label → ${button.label}." },
                        onFailure = { "Nie udało się zapisać ustawień: ${it.message}" },
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

            fun handleVirtualKey(key: VirtualRemoteKey): Boolean {
                val now = System.currentTimeMillis()
                if (lastVirtualKey == key && now - lastVirtualKeyAt < 90) return true
                lastVirtualKey = key
                lastVirtualKeyAt = now

                if (page != DesktopPage.PLAYER) {
                    return when (key) {
                        VirtualRemoteKey.BACK, VirtualRemoteKey.HOME -> {
                            captureButton = null
                            page = if (vlcSession == null) DesktopPage.CONNECTION else DesktopPage.PLAYER
                            true
                        }
                        VirtualRemoteKey.UP -> { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up); true }
                        VirtualRemoteKey.DOWN -> { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down); true }
                        VirtualRemoteKey.LEFT -> { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left); true }
                        VirtualRemoteKey.RIGHT -> { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right); true }
                        else -> false
                    }
                }

                if (showChannelList) {
                    when (key) {
                        VirtualRemoteKey.UP -> if (channels.isNotEmpty()) channelListSelected = (channelListSelected - 1 + channels.size) % channels.size
                        VirtualRemoteKey.DOWN -> if (channels.isNotEmpty()) channelListSelected = (channelListSelected + 1) % channels.size
                        VirtualRemoteKey.LEFT -> bouquetOffset(catalog?.bouquets.orEmpty(), selectedBouquet, -1)?.let(::selectBouquet)
                        VirtualRemoteKey.RIGHT -> bouquetOffset(catalog?.bouquets.orEmpty(), selectedBouquet, 1)?.let(::selectBouquet)
                        VirtualRemoteKey.OK -> channels.getOrNull(channelListSelected)?.let {
                            showChannelList = false
                            startChannel(it)
                        }
                        VirtualRemoteKey.BACK -> showChannelList = false
                        VirtualRemoteKey.CHANNEL_UP -> if (channels.isNotEmpty()) {
                            channelListSelected = (channelListSelected - 8).coerceAtLeast(0)
                        }
                        VirtualRemoteKey.CHANNEL_DOWN -> if (channels.isNotEmpty()) {
                            channelListSelected = (channelListSelected + 8).coerceAtMost(channels.lastIndex)
                        }
                        else -> return false
                    }
                    return true
                }

                when (key) {
                    VirtualRemoteKey.UP, VirtualRemoteKey.CHANNEL_UP -> changeChannel(1)
                    VirtualRemoteKey.DOWN, VirtualRemoteKey.CHANNEL_DOWN -> changeChannel(-1)
                    VirtualRemoteKey.OK -> showChannelList = true
                    VirtualRemoteKey.SETTINGS -> page = DesktopPage.CONNECTION
                    VirtualRemoteKey.PLAY_PAUSE -> vlcSession?.togglePause()
                    VirtualRemoteKey.PLAY -> vlcSession?.resume()
                    VirtualRemoteKey.PAUSE -> vlcSession?.pause()
                    VirtualRemoteKey.BACK ->
                        status = "Przycisk Wstecz zamyka listę kanałów lub wraca z ustawień."
                    VirtualRemoteKey.HOME -> page = DesktopPage.CONNECTION
                    VirtualRemoteKey.LEFT -> status = "EPG kanału nie jest jeszcze dostępne w wersji Windows."
                    VirtualRemoteKey.RIGHT, VirtualRemoteKey.GUIDE, VirtualRemoteKey.TV_DATA ->
                        status = "Multi-EPG nie jest jeszcze dostępne w wersji Windows."
                    VirtualRemoteKey.INFO -> status = "EPG wydarzenia nie jest jeszcze dostępne w wersji Windows."
                    VirtualRemoteKey.SEARCH -> status = "Wyszukiwanie EPG nie jest jeszcze dostępne w wersji Windows."
                    VirtualRemoteKey.AUDIO -> status = "Wybór ścieżki audio nie jest jeszcze dostępny w wersji Windows."
                    VirtualRemoteKey.MENU -> status = "Menu nie jest jeszcze dostępne w wersji Windows."
                    VirtualRemoteKey.LAST_CHANNEL -> status = "Historia kanałów nie jest jeszcze dostępna w wersji Windows."
                    VirtualRemoteKey.REWIND, VirtualRemoteKey.FAST_FORWARD, VirtualRemoteKey.STOP ->
                        status = "Timeshift nie jest jeszcze dostępny w wersji Windows."
                    VirtualRemoteKey.DIGIT_0, VirtualRemoteKey.DIGIT_1, VirtualRemoteKey.DIGIT_2,
                    VirtualRemoteKey.DIGIT_3, VirtualRemoteKey.DIGIT_4, VirtualRemoteKey.DIGIT_5,
                    VirtualRemoteKey.DIGIT_6, VirtualRemoteKey.DIGIT_7, VirtualRemoteKey.DIGIT_8,
                    VirtualRemoteKey.DIGIT_9 -> status = "Wybór kanału numerem nie jest jeszcze dostępny w wersji Windows."
                    VirtualRemoteKey.POWER -> Unit
                }
                return true
            }

            val sessionToClose = vlcSession
            DisposableEffect(sessionToClose) {
                onDispose { sessionToClose?.close() }
            }

            DisposableEffect(desktopWindow, settingsWindow, captureButton, bindings, universalBindings, vlcSession, page, channels, playingChannel, showChannelList) {
                val dispatcher = java.awt.KeyEventDispatcher { event ->
                    val focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                    if (!belongsToApplication(focusedWindow, desktopWindow) ||
                        event.id != KeyEvent.KEY_PRESSED ||
                        event.keyCode == KeyEvent.VK_UNDEFINED
                    ) {
                        false
                    } else if (event.keyCode == KeyEvent.VK_F11 && captureButton == null) {
                        mainWindowState.placement = if (mainWindowState.placement == WindowPlacement.Fullscreen) {
                            WindowPlacement.Floating
                        } else WindowPlacement.Fullscreen
                        true
                    } else if (captureButton != null) {
                        val button = captureButton ?: return@KeyEventDispatcher false
                        savePhysicalBinding(button, UniversalRemoteMap.keyId(event.keyCode), RemoteKeyMap.keyName(event.keyCode))
                        true
                    } else {
                        val physicalId = UniversalRemoteMap.keyId(event.keyCode)
                        val virtualKey = UniversalRemoteMap.findKey(universalBindings, physicalId)
                        if (virtualKey != null && (page == DesktopPage.PLAYER ||
                            page == DesktopPage.REMOTE && virtualKey in setOf(
                                VirtualRemoteKey.BACK, VirtualRemoteKey.HOME,
                                VirtualRemoteKey.UP, VirtualRemoteKey.DOWN,
                                VirtualRemoteKey.LEFT, VirtualRemoteKey.RIGHT,
                            ))
                        ) {
                            handleVirtualKey(virtualKey)
                        } else if (page == DesktopPage.PLAYER) {
                            when (bindings[event.keyCode]) {
                                RemoteAction.TIMESHIFT -> {
                                    vlcSession?.togglePause()
                                    status = "Odtwarzanie wstrzymane lub wznowione."
                                    true
                                }
                                RemoteAction.CHANNEL_UP -> changeChannel(1)
                                RemoteAction.CHANNEL_DOWN -> changeChannel(-1)
                                RemoteAction.CHANNEL_LIST -> {
                                    channelListSelected = channels.indexOfFirst { it.ref == playingChannel?.ref }.coerceAtLeast(0)
                                    showChannelList = true
                                    true
                                }
                                RemoteAction.SETTINGS -> { page = DesktopPage.CONNECTION; true }
                                RemoteAction.NONE, null -> false
                                else -> {
                                    status = "${bindings[event.keyCode]?.label} nie jest jeszcze dostępne w wersji Windows."
                                    true
                                }
                            }
                        } else false
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
                            EventQueue.invokeLater {
                                if (!belongsToApplication(
                                        KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow,
                                        desktopWindow,
                                    )
                                ) return@invokeLater
                                val button = captureButton
                                if (button != null) savePhysicalBinding(button, physicalId, label)
                                else UniversalRemoteMap.findKey(universalBindings, physicalId)?.let(::handleVirtualKey)
                            }
                        },
                        onError = { message -> EventQueue.invokeLater { hidDiagnostic = message; status = message } },
                        onDiagnostic = hidDiagnosticDispatcher::submit,
                        suppressBrowserKeys = { suppressBrowserKeys.get() },
                    )
                } catch (e: Exception) {
                    status = "Obsługa pilota HID niedostępna: ${e.message ?: e.javaClass.simpleName}"
                    hidDiagnostic = status
                    null
                }
                onDispose { rawInput?.close() }
            }

            val settingsContent: @Composable () -> Unit = {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().focusable().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("E2Stream", style = MaterialTheme.typography.headlineMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                captureButton = null
                                page = DesktopPage.CONNECTION
                            }) { Text("Dekoder") }
                            OutlinedButton(onClick = { page = DesktopPage.REMOTE }) { Text("Pilot / klawiatura") }
                            if (vlcSession != null) {
                                OutlinedButton(onClick = {
                                    captureButton = null
                                    page = DesktopPage.PLAYER
                                }) { Text("Wróć do oglądania") }
                            }
                        }
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        epgError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                        when (page) {
                            DesktopPage.CONNECTION -> ConnectionPage(
                                config = config,
                                onConfigChange = { config = it },
                                busy = busy,
                                catalog = catalog,
                                selectedBouquet = selectedBouquet,
                                channels = channels,
                                onPlay = ::startChannel,
                                onConnect = {
                                    scope.launch {
                                        busy = true
                                        status = "Łączenie z dekoderem…"
                                        try {
                                            val result = withContext(Dispatchers.IO) {
                                                val updated = config.copy(
                                                    httpPort = config.httpPort,
                                                    streamPort = config.streamPort,
                                                    pluginPort = config.pluginPort,
                                                )
                                                DecoderClient(updated).loadBouquets().also { updated.save() }
                                            }
                                            catalog = result
                                            selectedBouquet = null
                                            channels = emptyList()
                                            nowEvents = emptyMap()
                                            status = "Połączono przez ${if (result.usesPlugin) "EPG Scan" else "OpenWebif"}; bukiety: ${result.bouquets.size}."
                                        } catch (e: Exception) {
                                            status = e.message ?: "Nie udało się połączyć z dekoderem."
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                onBouquetSelected = ::selectBouquet,
                            )
                            DesktopPage.PLAYER -> Unit
                            DesktopPage.REMOTE -> RemotePage(
                                bindings = universalBindings,
                                selected = selectedRemoteButton,
                                captureButton = captureButton,
                                hidDiagnostic = hidDiagnostic,
                                onSelect = { selectedRemoteButton = it },
                                onCapture = {
                                    if (it == VirtualRemoteKey.POWER) {
                                        status = "W ustawieniach pilota przypisz Power do uruchamiania programu E2Stream."
                                    } else {
                                        selectedRemoteButton = it
                                        captureButton = it
                                        status = "Naciśnij przycisk fizycznego pilota lub klawiatury."
                                    }
                                },
                                onCancelCapture = { captureButton = null },
                                onRemove = { key, id ->
                                    universalBindings = UniversalRemoteMap.remove(universalBindings, key, id)
                                    config = config.copy(universalRemoteBindings = UniversalRemoteMap.encode(universalBindings))
                                    runCatching { config.save() }
                                        .onFailure { status = "Nie udało się zapisać ustawień: ${it.message}" }
                                },
                                onReset = {
                                    universalBindings = UniversalRemoteMap.defaults
                                    captureButton = null
                                    config = config.copy(universalRemoteBindings = "")
                                    runCatching { config.save() }
                                        .onFailure { status = "Nie udało się zapisać ustawień: ${it.message}" }
                                },
                            )
                        }
                    }
                }
            }

            val session = vlcSession
            val channel = playingChannel
            if (session != null && channel != null) {
                PlaybackHost(
                    settingsVisible = page != DesktopPage.PLAYER,
                    player = {
                        PlayerPage(
                            channel = channel,
                            session = session,
                            event = nowEvents[serviceKey(channel.ref)],
                            status = epgError ?: status,
                            videoScaleMode = config.videoScaleMode,
                            isFullscreen = mainWindowState.placement == WindowPlacement.Fullscreen,
                            onToggleFullscreen = {
                                mainWindowState.placement = if (mainWindowState.placement == WindowPlacement.Fullscreen) {
                                    WindowPlacement.Floating
                                } else WindowPlacement.Fullscreen
                            },
                            onScaleChange = {
                                config = config.copy(videoScaleMode = it)
                                session.setVideoScale(it)
                                runCatching { config.save() }
                                    .onFailure { error -> status = "Nie udało się zapisać ustawień: ${error.message}" }
                            },
                            onChannels = {
                                channelListSelected = channels.indexOfFirst { it.ref == playingChannel?.ref }.coerceAtLeast(0)
                                showChannelList = true
                            },
                            onSettings = { page = DesktopPage.CONNECTION },
                            onBack = {
                                showChannelList = false
                                vlcSession = null
                                playingChannel = null
                                page = DesktopPage.CONNECTION
                                status = "Odtwarzanie zatrzymane."
                            },
                        )
                    },
                    settings = {
                        DialogWindow(
                            onCloseRequest = {
                                captureButton = null
                                page = DesktopPage.PLAYER
                            },
                            title = "E2Stream — ustawienia",
                            state = rememberDialogState(width = 900.dp, height = 600.dp),
                            resizable = true,
                        ) {
                            val dialog = window
                            DisposableEffect(dialog) {
                                settingsWindow = dialog
                                onDispose { settingsWindow = null }
                            }
                            MaterialTheme(colorScheme = colorScheme) { settingsContent() }
                        }
                    },
                )
                if (showChannelList && page == DesktopPage.PLAYER) {
                    ChannelListDialog(
                        bouquet = selectedBouquet,
                        bouquets = catalog?.bouquets.orEmpty(),
                        channels = channels,
                        selected = channelListSelected,
                        nowEvents = nowEvents,
                        busy = busy,
                        onClose = { showChannelList = false },
                        onSelectedChange = { channelListSelected = it },
                        onBouquetSelected = ::selectBouquet,
                        onPlay = {
                            showChannelList = false
                            startChannel(it)
                        },
                    )
                }
            } else {
                settingsContent()
            }
        }
    }
    }
}

private enum class DesktopPage { CONNECTION, PLAYER, REMOTE }

private fun belongsToApplication(window: AwtWindow?, mainWindow: AwtWindow): Boolean {
    var current = window
    while (current != null) {
        if (current === mainWindow) return true
        current = current.owner
    }
    return false
}

@Composable
private fun ConnectionPage(
    config: DesktopConfig,
    onConfigChange: (DesktopConfig) -> Unit,
    busy: Boolean,
    catalog: DecoderCatalog?,
    selectedBouquet: Bouquet?,
    channels: List<Channel>,
    onPlay: (Channel) -> Unit,
    onConnect: () -> Unit,
    onBouquetSelected: (Bouquet) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Połączenie z dekoderem", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = config.host,
                onValueChange = { onConfigChange(config.copy(host = it)) },
                label = { Text("Adres dekodera") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            NumberField("OpenWebif", config.httpPort, { onConfigChange(config.copy(httpPort = it)) })
            NumberField("Streaming", config.streamPort, { onConfigChange(config.copy(streamPort = it)) })
            NumberField("EPG Scan", config.pluginPort, { onConfigChange(config.copy(pluginPort = it)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = config.username,
                onValueChange = { onConfigChange(config.copy(username = it)) },
                label = { Text("Login OpenWebif") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = config.password,
                onValueChange = { onConfigChange(config.copy(password = it)) },
                label = { Text("Hasło OpenWebif") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = config.pluginToken,
                onValueChange = { onConfigChange(config.copy(pluginToken = it)) },
                label = { Text("Token EPG Scan (opcjonalnie)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Button(onClick = onConnect, enabled = !busy) {
            Text(if (busy) "Łączenie…" else "Połącz i pobierz bukiety")
        }

        if (catalog != null) {
            Text("Bukiety", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BouquetPicker(catalog.bouquets, selectedBouquet, busy, onBouquetSelected)
                Text("Kanały: ${channels.size}", modifier = Modifier.padding(top = 12.dp))
            }
            if (channels.isNotEmpty()) {
                Card(Modifier.fillMaxWidth().height(360.dp)) {
                    LazyColumn(Modifier.padding(12.dp)) {
                        items(channels) { channel ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    "${channel.number}. ${channel.name}",
                                    modifier = Modifier.weight(1f).padding(top = 10.dp),
                                )
                                Button(onClick = { onPlay(channel) }) { Text("Oglądaj") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val normalized = input.filter(Char::isDigit).take(5)
            val port = normalized.toIntOrNull()
            if (port != null && port in 1..65535) {
                text = normalized
                onValueChange(port)
            }
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.width(125.dp),
    )
}

@Composable
private fun BouquetPicker(
    bouquets: List<Bouquet>,
    selected: Bouquet?,
    busy: Boolean,
    onSelected: (Bouquet) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, enabled = bouquets.isNotEmpty() && !busy) {
            Text(selected?.name ?: "Wybierz bukiet")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            bouquets.forEach { bouquet ->
                DropdownMenuItem(
                    text = { Text(bouquet.name) },
                    onClick = {
                        expanded = false
                        onSelected(bouquet)
                    },
                )
            }
        }
    }
}

@Composable
private fun RemotePage(
    bindings: Map<VirtualRemoteKey, Set<String>>,
    selected: VirtualRemoteKey,
    captureButton: VirtualRemoteKey?,
    hidDiagnostic: String,
    onSelect: (VirtualRemoteKey) -> Unit,
    onCapture: (VirtualRemoteKey) -> Unit,
    onCancelCapture: () -> Unit,
    onRemove: (VirtualRemoteKey, String) -> Unit,
    onReset: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(
            modifier = Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                .background(colors.surfaceVariant, RoundedCornerShape(32.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("PILOT UNIWERSALNY", style = MaterialTheme.typography.titleMedium)
            @Composable
            fun key(key: VirtualRemoteKey, modifier: Modifier = Modifier) {
                OutlinedButton(onClick = { onSelect(key) }, modifier = modifier, shape = RoundedCornerShape(18.dp)) {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text(key.label, maxLines = 1)
                        val count = bindings[key].orEmpty().size
                        Text(
                            if (key == VirtualRemoteKey.POWER) "uruchom E2Stream"
                            else if (count == 0) "—" else "$count klawiszy",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                key(VirtualRemoteKey.POWER, Modifier.weight(1f))
                key(VirtualRemoteKey.HOME, Modifier.weight(1f))
                key(VirtualRemoteKey.BACK, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                key(VirtualRemoteKey.MENU, Modifier.weight(1f))
                key(VirtualRemoteKey.SETTINGS, Modifier.weight(1f))
            }
            key(VirtualRemoteKey.UP, Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally).width(110.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                key(VirtualRemoteKey.LEFT, Modifier.weight(1f))
                key(VirtualRemoteKey.OK, Modifier.weight(1f))
                key(VirtualRemoteKey.RIGHT, Modifier.weight(1f))
            }
            key(VirtualRemoteKey.DOWN, Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally).width(110.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                key(VirtualRemoteKey.CHANNEL_UP, Modifier.weight(1f))
                key(VirtualRemoteKey.CHANNEL_DOWN, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                key(VirtualRemoteKey.INFO, Modifier.weight(1f))
                key(VirtualRemoteKey.GUIDE, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                key(VirtualRemoteKey.TV_DATA, Modifier.weight(1f))
                key(VirtualRemoteKey.SEARCH, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                key(VirtualRemoteKey.AUDIO, Modifier.weight(1f))
                key(VirtualRemoteKey.LAST_CHANNEL, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                key(VirtualRemoteKey.REWIND, Modifier.weight(1f))
                key(VirtualRemoteKey.PLAY_PAUSE, Modifier.weight(1f))
                key(VirtualRemoteKey.FAST_FORWARD, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                key(VirtualRemoteKey.PLAY, Modifier.weight(1f))
                key(VirtualRemoteKey.PAUSE, Modifier.weight(1f))
                key(VirtualRemoteKey.STOP, Modifier.weight(1f))
            }
            (1..9).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { number ->
                        key(VirtualRemoteKey.valueOf("DIGIT_$number"), Modifier.weight(1f))
                    }
                }
            }
            key(VirtualRemoteKey.DIGIT_0, Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally).width(110.dp))
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(selected.label, style = MaterialTheme.typography.titleLarge, color = colors.primary)
            Text(hidDiagnostic, style = MaterialTheme.typography.bodySmall)
            if (selected == VirtualRemoteKey.POWER) {
                Text(
                    "W ustawieniach pilota Xiaomi przypisz Power do uruchamiania programu E2Stream. " +
                        "Gdy program działa, kolejne uruchomienie przywoła jego okno; jeśli już jest aktywne, zapyta o zamknięcie.",
                )
            } else {
                Text("Przypisz do tego przycisku kilka klawiszy fizycznych. Pilot HID jest wykrywany tylko wtedy, gdy E2Stream jest aktywny.")
                if (captureButton == selected) {
                    Text("Naciśnij teraz klawisz pilota lub klawiatury.", color = colors.primary)
                    OutlinedButton(onClick = onCancelCapture) { Text("Anuluj") }
                } else {
                    Button(onClick = { onCapture(selected) }) { Text("Dodaj klawisz fizyczny") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(bindings[selected].orEmpty().sorted()) { physicalId ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                UniversalRemoteMap.label(physicalId),
                                modifier = Modifier.weight(1f).padding(top = 12.dp),
                            )
                            OutlinedButton(onClick = { onRemove(selected, physicalId) }) { Text("Usuń") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = onReset) { Text("Przywróć domyślne klawisze") }
        }
    }
}
