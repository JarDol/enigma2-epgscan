package pl.jardol.e2stream.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.KeyEvent
import java.awt.KeyboardFocusManager

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "E2Stream",
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
            var captureAction by remember { mutableStateOf<RemoteAction?>(null) }
            var bindings by remember { mutableStateOf(RemoteKeyMap.defaults + RemoteKeyMap.parse(config.remoteBindings)) }
            var vlcSession by remember { mutableStateOf<VlcSession?>(null) }
            var playingChannel by remember { mutableStateOf<Channel?>(null) }
            val scope = rememberCoroutineScope()

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
                    val url = DecoderClient(config).streamUrl(channel)
                    session.play(url, config)
                    if (existingSession == null) vlcSession = session
                    playingChannel = channel
                    captureAction = null
                    page = DesktopPage.PLAYER
                    status = "Otwieranie strumienia: ${channel.name}"
                } catch (e: Exception) {
                    newSession?.close()
                    status = e.message ?: "Nie udało się uruchomić VLC."
                }
            }

            fun changeChannel(offset: Int): Boolean {
                val current = channels.indexOfFirst { it.ref == playingChannel?.ref }
                if (current < 0 || channels.isEmpty()) return false
                val next = (current + offset).mod(channels.size)
                startChannel(channels[next])
                return true
            }

            val sessionToClose = vlcSession
            DisposableEffect(sessionToClose) {
                onDispose { sessionToClose?.close() }
            }

            DisposableEffect(desktopWindow, settingsWindow, captureAction, bindings, vlcSession, page, channels, playingChannel) {
                val dispatcher = java.awt.KeyEventDispatcher { event ->
                    val action = captureAction
                    val focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                    if ((focusedWindow != desktopWindow && (settingsWindow == null || focusedWindow != settingsWindow)) ||
                        event.id != KeyEvent.KEY_PRESSED ||
                        event.keyCode == KeyEvent.VK_UNDEFINED
                    ) {
                        false
                    } else if (action == null) {
                        when {
                            page != DesktopPage.PLAYER -> false
                            bindings[event.keyCode] == RemoteAction.TIMESHIFT && vlcSession != null -> {
                                vlcSession?.togglePause()
                                status = "Odtwarzanie wstrzymane lub wznowione."
                                true
                            }
                            bindings[event.keyCode] == RemoteAction.CHANNEL_UP -> changeChannel(1)
                            bindings[event.keyCode] == RemoteAction.CHANNEL_DOWN -> changeChannel(-1)
                            bindings[event.keyCode] == RemoteAction.CHANNEL_LIST -> {
                                showChannelList = true
                                true
                            }
                            else -> false
                        }
                    } else {
                        bindings = bindings + (event.keyCode to action)
                        config = config.copy(remoteBindings = RemoteKeyMap.encode(bindings))
                        val keyName = RemoteKeyMap.keyName(event.keyCode)
                        status = runCatching { config.save() }
                            .fold(
                                onSuccess = { "Przypisano $keyName → ${action.label}" },
                                onFailure = { "Nie udało się zapisać ustawień: ${it.message}" },
                            )
                        captureAction = null
                        true
                    }
                }
                val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                manager.addKeyEventDispatcher(dispatcher)
                onDispose { manager.removeKeyEventDispatcher(dispatcher) }
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
                                captureAction = null
                                page = DesktopPage.CONNECTION
                            }) { Text("Dekoder") }
                            OutlinedButton(onClick = { page = DesktopPage.REMOTE }) { Text("Pilot / klawiatura") }
                            if (vlcSession != null) {
                                OutlinedButton(onClick = {
                                    captureAction = null
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
                                onBouquetSelected = { bouquet ->
                                    selectedBouquet = bouquet
                                    channels = emptyList()
                                    nowEvents = emptyMap()
                                    val currentCatalog = catalog
                                    if (currentCatalog != null) {
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
                                },
                            )
                            DesktopPage.PLAYER -> Unit
                            DesktopPage.REMOTE -> RemotePage(
                                bindings = bindings,
                                captureAction = captureAction,
                                onCapture = { captureAction = it },
                                onRemove = { keyCode ->
                                    bindings = bindings + (keyCode to RemoteAction.NONE)
                                    config = config.copy(remoteBindings = RemoteKeyMap.encode(bindings))
                                    runCatching { config.save() }
                                        .onFailure { status = "Nie udało się zapisać ustawień: ${it.message}" }
                                },
                                onReset = {
                                    bindings = RemoteKeyMap.defaults
                                    config = config.copy(remoteBindings = "")
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
                            onChannels = { showChannelList = true },
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
                                captureAction = null
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
                        channels = channels,
                        playing = playingChannel,
                        nowEvents = nowEvents,
                        onClose = { showChannelList = false },
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

private enum class DesktopPage { CONNECTION, PLAYER, REMOTE }

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
    bindings: Map<Int, RemoteAction>,
    captureAction: RemoteAction?,
    onCapture: (RemoteAction) -> Unit,
    onRemove: (Int) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Mapowanie pilota i klawiatury", style = MaterialTheme.typography.titleLarge)
        Text("Wybierz akcję, a następnie naciśnij przycisk pilota. Windows musi przekazać go aplikacji jako klawisz.")
        if (captureAction != null) {
            Text("Naciśnij teraz przycisk dla akcji: ${captureAction.label}")
        }
        RemoteAction.entries.filter { it != RemoteAction.NONE }.forEach { action ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(action.label, modifier = Modifier.weight(1f).padding(top = 12.dp))
                val keys = bindings.filterValues { it == action }.keys
                Text(
                    if (keys.isEmpty()) "Nieprzypisano"
                    else keys.joinToString { RemoteKeyMap.keyName(it) },
                    modifier = Modifier.weight(1f).padding(top = 12.dp),
                )
                OutlinedButton(onClick = { onCapture(action) }) { Text("Przypisz") }
                if (keys.isNotEmpty()) {
                    OutlinedButton(onClick = { keys.forEach(onRemove) }) { Text("Usuń") }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = onReset) { Text("Przywróć domyślne mapowanie") }
    }
}
