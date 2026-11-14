package pl.jardol.e2stream.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlinx.coroutines.delay

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun time(seconds: Long): String = clockFormat.format(Instant.ofEpochSecond(seconds))

internal fun bouquetOffset(bouquets: List<Bouquet>, current: Bouquet?, offset: Int): Bouquet? {
    if (bouquets.isEmpty() || offset == 0) return null
    val currentIndex = bouquets.indexOfFirst { it.ref == current?.ref }
    if (currentIndex < 0) return null
    return bouquets[(currentIndex + offset).mod(bouquets.size)]
}

@Composable
internal fun PlayerPage(
    channel: Channel?,
    session: VlcSession?,
    event: NowEvent?,
    status: String,
    bannerVisible: Boolean,
    bannerFontScale: Float,
    onShowBanner: () -> Unit,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    videoScaleMode: VideoScaleMode,
    windowMode: WindowMode,
    onWindowMode: () -> Unit,
    onScaleMenu: () -> Unit,
    onChannels: () -> Unit,
    onMenu: () -> Unit,
    onBack: () -> Unit,
    picon: ImageBitmap? = null,
    piconStyle: PiconStyle = PiconStyle.OFF,
    bannerIconScale: Float = 1f,
    showStats: Boolean = false,
    playback: PlaybackBar? = null,
    onTogglePause: () -> Unit = { session?.togglePause() },
    onSeek: ((forward: Boolean) -> Unit)? = null,
    onLive: (() -> Unit)? = null,
) {
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    var statusVisible by remember { mutableStateOf(false) }
    val currentToggleControls by androidx.compose.runtime.rememberUpdatedState(onToggleControls)
    DisposableEffect(session) {
        val listener = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(event)) currentToggleControls()
            }
        }
        session?.addVideoMouseListener(listener)
        onDispose { session?.removeVideoMouseListener(listener) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now().epochSecond
            delay(30_000)
        }
    }
    LaunchedEffect(status, session) {
        statusVisible = session != null
        delay(7_000)
        statusVisible = false
    }
    var stats by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showStats, session) {
        stats = null
        if (!showStats || session == null) return@LaunchedEffect
        var previous: PlaybackStats? = null
        while (true) {
            val current = runCatching { session.statistics() }.getOrNull()
            stats = current?.let { formatStats(it, previous) } ?: "Statystyki: brak danych z VLC"
            previous = current
            delay(1000)
        }
    }
    val currentEvent = event?.takeIf { it.begin <= now && now < it.end }
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val secondary = colors.onSurfaceVariant
    Column(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        currentToggleControls()
                    }
                }
            }
        },
    ) {
        if (session != null) {
            SwingPanel(
                factory = { session.view },
                modifier = Modifier.fillMaxWidth().weight(1f),
                background = Color.Black,
            )
        } else {
            Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp)) {
                Text(
                    "$status\nMenu: M / pilot · przyciski myszy: prawy przycisk",
                    color = Color.White,
                )
            }
        }
        if ((channel != null && bannerVisible) || controlsVisible || statusVisible || stats != null || playback != null) {
            Column(
                modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 24.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (playback != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            playback.title,
                            modifier = Modifier.weight(1f),
                            color = accent,
                            fontSize = 20.sp * bannerFontScale,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(time(now), color = secondary, fontSize = 20.sp * bannerFontScale)
                    }
                    Text(playback.line, color = colors.onSurface, fontSize = 17.sp * bannerFontScale, maxLines = 1)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(playback.start, color = secondary, fontSize = 14.sp * bannerFontScale)
                        LinearProgressIndicator(
                            progress = { playback.progress },
                            modifier = Modifier.weight(1f).padding(top = 8.dp).height(6.dp),
                            color = accent,
                        )
                        Text(playback.end, color = secondary, fontSize = 14.sp * bannerFontScale)
                    }
                    Text(playback.hint, color = secondary, fontSize = 13.sp, maxLines = 1)
                }
                if (channel != null && bannerVisible && playback == null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (piconStyle != PiconStyle.OFF) {
                            ChannelIcon(picon, piconStyle == PiconStyle.WITH_LOGO, 72.dp * bannerIconScale, 44.dp * bannerIconScale)
                        }
                        Text(
                            "${channel.number}   ${channel.name}",
                            modifier = Modifier.weight(1f),
                            color = accent,
                            fontSize = 20.sp * bannerFontScale,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(time(now), color = secondary, fontSize = 20.sp * bannerFontScale)
                    }
                    Text(
                        currentEvent?.title ?: "Brak danych bieżącej audycji",
                        color = colors.onSurface,
                        fontSize = 21.sp * bannerFontScale,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (currentEvent != null && currentEvent.end > currentEvent.begin) {
                        val progress = ((now - currentEvent.begin).toFloat() / (currentEvent.end - currentEvent.begin)).coerceIn(0f, 1f)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(time(currentEvent.begin), color = secondary, fontSize = 14.sp * bannerFontScale)
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.weight(1f).padding(top = 8.dp).height(6.dp),
                                color = accent,
                            )
                            Text(time(currentEvent.end), color = secondary, fontSize = 14.sp * bannerFontScale)
                        }
                    }
                }
                stats?.let { Text(it, color = secondary, fontSize = 13.sp, maxLines = 1) }
                if (statusVisible && !controlsVisible) {
                    Text(status, color = secondary, maxLines = 2)
                }
                if (controlsVisible) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onChannels) { Text("Lista kanałów") }
                    OutlinedButton(onClick = onShowBanner, enabled = channel != null) { Text("Pasek") }
                    OutlinedButton(onClick = onTogglePause, enabled = session != null) { Text("Pauza / Wznów") }
                    if (onSeek != null) {
                        OutlinedButton(onClick = { onSeek(false) }) { Text("◀◀") }
                        OutlinedButton(onClick = { onSeek(true) }) { Text("▶▶") }
                    }
                    if (onLive != null) OutlinedButton(onClick = onLive) { Text("Na żywo") }
                    // Lista trybów otwiera się w osobnym oknie — rozwijane menu Compose chowa się pod obrazem VLC.
                    OutlinedButton(onClick = onScaleMenu) { Text("Obraz: ${videoScaleMode.label}") }
                    OutlinedButton(onClick = onWindowMode) { Text(windowMode.label) }
                    OutlinedButton(onClick = onMenu) { Text("Menu") }
                    OutlinedButton(onClick = onBack, enabled = session != null) { Text("Zatrzymaj") }
                    Text(status, modifier = Modifier.weight(1f).padding(top = 12.dp), color = secondary, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun ChannelListDialog(
    bouquet: Bouquet?,
    bouquets: List<Bouquet>,
    channels: List<Channel>,
    selected: Int,
    nowEvents: Map<String, NowEvent>,
    busy: Boolean,
    fontScale: Float,
    onClose: () -> Unit,
    onSelectedChange: (Int) -> Unit,
    onBouquetSelected: (Bouquet) -> Unit,
    onPlay: (Channel) -> Unit,
    piconFor: (Channel) -> ImageBitmap? = { null },
    piconStyle: PiconStyle = PiconStyle.OFF,
    iconScale: Float = 0.8f,
    recordings: Map<String, Recording> = emptyMap(),
) {
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val secondary = colors.onSurfaceVariant
    var bouquetMenuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now().epochSecond
            delay(30_000)
        }
    }
    LaunchedEffect(selected, channels) {
        if (selected in channels.indices) listState.animateScrollToItem(selected)
    }
    DialogWindow(
        onCloseRequest = onClose,
        title = "Lista kanałów — ${bouquet?.name.orEmpty()}",
        state = rememberDialogState(width = 950.dp, height = 700.dp),
        undecorated = true,
        resizable = false,
        onPreviewKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) false
            else when (event.key) {
                Key.DirectionDown -> {
                    if (channels.isNotEmpty()) onSelectedChange((selected + 1) % channels.size)
                    true
                }
                Key.DirectionUp -> {
                    if (channels.isNotEmpty()) onSelectedChange((selected - 1 + channels.size) % channels.size)
                    true
                }
                Key.DirectionLeft -> {
                    bouquetOffset(bouquets, bouquet, -1)?.let(onBouquetSelected)
                    true
                }
                Key.DirectionRight -> {
                    bouquetOffset(bouquets, bouquet, 1)?.let(onBouquetSelected)
                    true
                }
                Key.Enter, Key.NumPadEnter -> {
                    channels.getOrNull(selected)?.let(onPlay)
                    true
                }
                Key.Escape -> {
                    onClose()
                    true
                }
                else -> false
            }
        },
    ) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        MaterialTheme(colorScheme = colors) {
            Column(
                modifier = Modifier.fillMaxSize().background(colors.surface).focusRequester(focusRequester)
                    .focusable().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { bouquetMenuExpanded = true },
                            enabled = !busy && bouquets.size > 1,
                        ) {
                            Text("Bukiet: ${bouquet?.name ?: "Kanały"}")
                        }
                        DropdownMenu(
                            expanded = bouquetMenuExpanded,
                            onDismissRequest = { bouquetMenuExpanded = false },
                        ) {
                            bouquets.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.name) },
                                    onClick = {
                                        bouquetMenuExpanded = false
                                        onBouquetSelected(item)
                                    },
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { bouquetOffset(bouquets, bouquet, -1)?.let(onBouquetSelected) },
                        enabled = !busy && bouquets.size > 1,
                    ) { Text("◀ Bukiet") }
                    OutlinedButton(
                        onClick = { bouquetOffset(bouquets, bouquet, 1)?.let(onBouquetSelected) },
                        enabled = !busy && bouquets.size > 1,
                    ) { Text("Bukiet ▶") }
                    Text(
                        if (recordings.isEmpty()) "← → bukiet   •   ↑ ↓ kanał   •   OK oglądaj   •   Wstecz zamknij"
                        else "← → bukiet   •   ↑ ↓ nagranie   •   OK otwórz/odtwórz   •   Wstecz katalog wyżej/zamknij",
                        color = secondary,
                    )
                    BackButton(onClick = onClose)
                }
                LazyColumn(Modifier.weight(3f), state = listState) {
                    itemsIndexed(channels) { index, channel ->
                        val recording = recordings[channel.ref]
                        val event = nowEvents[serviceKey(channel.ref)]?.takeIf { it.begin <= now && now < it.end }
                        val isSelected = index == selected
                        val primaryText = if (isSelected) colors.onPrimaryContainer else colors.onSurface
                        val secondaryText = if (isSelected) colors.onPrimaryContainer else secondary
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(if (isSelected) colors.primaryContainer else Color.Transparent, RoundedCornerShape(6.dp))
                                .clickable {
                                    onSelectedChange(index)
                                    onPlay(channel)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (recording != null) {
                                Text(
                                    if (recording.isDir) "" else channel.number.toString(),
                                    modifier = Modifier.width(55.dp * fontScale),
                                    color = secondaryText,
                                    fontSize = 20.sp * fontScale,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (recording.isDir && recording.path != "..") "▸ ${recording.title}" else recording.title,
                                        color = primaryText,
                                        fontSize = 22.sp * fontScale,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                    )
                                    if (!recording.isDir) {
                                        Text(recordingDetails(recording), color = secondaryText, fontSize = 16.sp * fontScale, maxLines = 1)
                                    }
                                }
                                return@Row
                            }
                            if (piconStyle != PiconStyle.OFF) {
                                ChannelIcon(piconFor(channel), piconStyle == PiconStyle.WITH_LOGO, 80.dp * iconScale, 48.dp * iconScale)
                                androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                            }
                            Text(channel.number.toString(), modifier = Modifier.width(55.dp * fontScale), color = secondaryText, fontSize = 20.sp * fontScale)
                            Column(Modifier.weight(1f)) {
                                Text(channel.name, color = primaryText, fontSize = 22.sp * fontScale, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(event?.title ?: "Brak danych EPG", color = secondaryText, fontSize = 16.sp * fontScale, maxLines = 1)
                            }
                            if (event != null && event.end > event.begin) {
                                Text("${time(event.begin)}–${time(event.end)}", color = secondaryText, fontSize = 16.sp * fontScale)
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
                Column(Modifier.weight(1f).padding(8.dp)) {
                    val channel = channels.getOrNull(selected)
                    val recording = channel?.let { recordings[it.ref] }
                    if (recording != null) {
                        Text(recording.title, color = accent, fontSize = 20.sp * fontScale, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            if (recording.isDir) "Katalog — OK otwiera" else recording.description.ifBlank { recordingDetails(recording) },
                            color = colors.onSurface,
                            fontSize = 17.sp * fontScale,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        return@Column
                    }
                    Text(channel?.name.orEmpty(), color = accent, fontSize = 20.sp * fontScale, fontWeight = FontWeight.Bold)
                    Text(
                        channel?.let { nowEvents[serviceKey(it.ref)]?.takeIf { event -> event.begin <= now && now < event.end }?.title }
                            ?: "Brak danych EPG",
                        color = colors.onSurface,
                        fontSize = 19.sp * fontScale,
                    )
                    Text("Wybierz kanał myszą lub naciśnij Enter.", color = secondary)
                }
            }
        }
    }
}

/** Ikony kanałów: wyłączone, tylko picony, picony z logo E2Stream w miejscu brakujących. */
internal enum class PiconStyle { OFF, PICONS, WITH_LOGO }

internal fun piconStyle(config: DesktopConfig): PiconStyle = when {
    !config.picons -> PiconStyle.OFF
    config.missingPiconLogo -> PiconStyle.WITH_LOGO
    else -> PiconStyle.PICONS
}

@Composable
internal fun ChannelIcon(image: ImageBitmap?, logoFallback: Boolean, width: Dp, height: Dp) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.width(width).height(height), contentAlignment = Alignment.Center) {
        when {
            image != null -> Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            logoFallback -> Box(
                Modifier.fillMaxSize().background(colors.primaryContainer, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("E2", color = colors.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = (height.value * 0.4f).sp)
            }
        }
    }
}

internal fun formatStats(current: PlaybackStats, previous: PlaybackStats?): String {
    val fps = previous?.let { (current.displayed - it.displayed).coerceAtLeast(0) }
    val lost = previous?.let { (current.lost - it.lost).coerceAtLeast(0) }
    return buildString {
        append("Statystyki: ")
        if (current.width > 0) append("${current.width}×${current.height}  ")
        if (current.codec.isNotBlank()) append("${current.codec}  ")
        append("${current.inputKbps} kb/s")
        if (fps != null) append("  •  $fps kl./s")
        append("  •  utracone klatki: ${current.lost}")
        if (lost != null && lost > 0) append(" (+$lost)")
        append("  •  uszkodzone pakiety: ${current.corrupted}")
        append("  •  nieciągłości: ${current.discontinuities}")
        append("  •  utracony dźwięk: ${current.audioLost}")
    }
}
/** Krótka lista wyboru w osobnym oknie — widoczna także nad obrazem VLC. */
internal data class QuickPick(
    val title: String,
    val options: List<String>,
    val selected: Int,
    val onSelect: (Int) -> Unit,
)

@Composable
internal fun QuickPickDialog(
    pick: QuickPick,
    colors: androidx.compose.material3.ColorScheme,
    onClose: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    DialogWindow(
        onCloseRequest = onClose,
        title = pick.title,
        state = rememberDialogState(width = 460.dp, height = (150 + pick.options.size * 54).dp),
        undecorated = true,
        resizable = false,
    ) {
        MaterialTheme(colorScheme = colors) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = colors.surface, contentColor = colors.onSurface) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pick.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = colors.primary)
                        BackButton(onClick = onClose)
                    }
                    pick.options.forEachIndexed { index, option ->
                        val isSelected = index == pick.selected
                        Text(
                            option,
                            modifier = Modifier.fillMaxWidth()
                                .background(if (isSelected) colors.primaryContainer else Color.Transparent, RoundedCornerShape(10.dp))
                                .clickable { onSelect(index) }
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