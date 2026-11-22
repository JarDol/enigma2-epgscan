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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.rememberUpdatedState
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

internal data class PipSurface(val channel: String, val session: VlcSession)

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
    statusSeconds: Int = 7,
    showStatusMessages: Boolean = true,
    volumeLabel: String = "",
    onVolume: (Int) -> Unit = {},
    onMute: () -> Unit = {},
    onAudio: () -> Unit = {},
    /** Następna audycja (pasek kanału jak w Androidzie). */
    next: NowEvent? = null,
    /** Okno główne — pasek jest wtedy osobnym oknem nad obrazem (obraz nie zmienia rozmiaru). */
    owner: java.awt.Window? = null,
    /** Ukryj paski (np. otwarte ustawienia). */
    barsHidden: Boolean = false,
    /** Trwające nagranie z timeshiftu — czerwony wskaźnik ● REC. */
    recordingLabel: String? = null,
    /** Stan bufora na pasku kanału (np. „B 12:34”); null — bez bufora. */
    bufferInfo: (() -> String?)? = null,
    pipWindows: List<PipSurface> = emptyList(),
    onPipClick: () -> Unit = {},
    seekPreview: VlcSession? = null,
    seekPreviewLabel: String = "",
) {
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    var statusVisible by remember { mutableStateOf(false) }
    val currentToggleControls by androidx.compose.runtime.rememberUpdatedState(onToggleControls)
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now().epochSecond
            delay(30_000)
        }
    }
    LaunchedEffect(status, session, showStatusMessages) {
        statusVisible = session != null && showStatusMessages
        delay(statusSeconds.coerceAtLeast(1) * 1000L)
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
    Box(
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
            VideoView(session, videoScaleMode, Modifier.fillMaxSize())
        }
        if (seekPreview != null && !barsHidden) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                VideoView(seekPreview, VideoScaleMode.FIT, Modifier.fillMaxSize())
                Text(
                    seekPreviewLabel,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 170.dp)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Color.White,
                )
            }
        }
        pipWindows.take(3).forEachIndexed { index, pip ->
            val alignment = when (index) {
                0 -> Alignment.TopEnd
                1 -> Alignment.TopStart
                else -> Alignment.CenterEnd
            }
            Box(
                Modifier.align(alignment).padding(16.dp).width(240.dp).height(135.dp)
                    .background(Color.Black).clickable { onPipClick() },
            ) {
                VideoView(pip.session, VideoScaleMode.FIT, Modifier.fillMaxSize())
                Text(
                    "${index + 1}  ${pip.channel}",
                    modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.7f)).padding(4.dp),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (recordingLabel != null) {
            Text(
                "● REC",
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = Color(0xFFE53935),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (session == null) {
            Box(Modifier.fillMaxSize().padding(24.dp)) {
                Text(
                    "$status\nMenu: M / pilot · przyciski myszy: prawy przycisk",
                    color = Color.White,
                )
            }
        }
        if (!barsHidden &&
            ((channel != null && bannerVisible) || controlsVisible || statusVisible || stats != null || playback != null)
        ) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(colors.surface.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
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
                    ChannelBanner(
                        channel = channel,
                        current = currentEvent,
                        next = next?.takeIf { it.begin >= (currentEvent?.end ?: now) || it.begin > now },
                        now = now,
                        fontScale = bannerFontScale,
                        picon = picon,
                        piconStyle = piconStyle,
                        iconScale = bannerIconScale,
                        bufferInfo = bufferInfo,
                        recording = recordingLabel != null,
                    )
                }
                stats?.let { Text(it, color = secondary, fontSize = 13.sp, maxLines = 1) }
                if (statusVisible && !controlsVisible) {
                    Text(status, color = secondary, maxLines = 2)
                }
                if (controlsVisible) Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
                    OutlinedButton(onClick = { onVolume(-1) }) { Text("−") }
                    OutlinedButton(onClick = onMute) { Text(volumeLabel) }
                    OutlinedButton(onClick = { onVolume(1) }) { Text("+") }
                    OutlinedButton(onClick = onAudio, enabled = session != null) { Text("Audio") }
                    OutlinedButton(onClick = onMenu) { Text("Menu") }
                    OutlinedButton(onClick = onBack, enabled = session != null) { Text("Zatrzymaj") }
                    Text(status, modifier = Modifier.padding(top = 12.dp), color = secondary, maxLines = 1)
                }
            }
            }
        }
    }
}

private val bannerClockFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

internal fun bannerClock(): String = java.time.LocalTime.now().format(bannerClockFormat)

/**
 * Stan bufora na pasku kanału: „B 12:34” (długość bufora timeshiftu), przy oglądaniu z opóźnieniem „B 12:34 · −1:05”.
 */
internal fun bufferBannerText(spanMs: Long, behindMs: Long): String =
    "B ${formatDuration(spanMs)}" + if (behindMs >= 2000) " · −${formatDuration(behindMs)}" else ""

/** Pasek kanału jak w Androidzie: ikona kanału z zegarem pod spodem, nazwa i stan bufora, bieżąca audycja z postępem i następna. */
@Composable
private fun ChannelBanner(
    channel: Channel,
    current: NowEvent?,
    next: NowEvent?,
    now: Long,
    fontScale: Float,
    picon: ImageBitmap?,
    piconStyle: PiconStyle,
    iconScale: Float,
    bufferInfo: (() -> String?)? = null,
    recording: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val secondary = colors.onSurfaceVariant
    // Zegar hh:mm:ss i stan bufora odświeżane co sekundę (pasek nie zależy od odświeżania audycji).
    var clockText by remember { mutableStateOf(bannerClock()) }
    var buffer by remember { mutableStateOf(bufferInfo?.invoke()) }
    val currentInfo by rememberUpdatedState(bufferInfo)
    LaunchedEffect(Unit) {
        while (true) {
            clockText = bannerClock()
            buffer = currentInfo?.invoke()
            delay(1000 - System.currentTimeMillis() % 1000)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (piconStyle != PiconStyle.OFF) {
                ChannelIcon(picon, piconStyle == PiconStyle.WITH_LOGO, 150.dp * iconScale, 90.dp * iconScale)
            }
            // Godzina pod logo: pogrubiona, wielkości tytułu audycji, kolor nazwy kanału.
            Text(clockText, color = accent, fontSize = 26.sp * fontScale, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${channel.number}   ${channel.name}",
                    modifier = Modifier.weight(1f),
                    color = accent,
                    fontSize = 22.sp * fontScale,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // W linii nazwy kanału — stan bufora (jak na Androidzie, wariant B), czerwone ● przy nagrywaniu.
                if (recording) Text("● ", color = Color(0xFFE53935), fontSize = 22.sp * fontScale, fontWeight = FontWeight.Bold)
                buffer?.let { Text(it, color = secondary, fontSize = 22.sp * fontScale, maxLines = 1) }
            }
            Text(
                current?.title ?: "Brak danych bieżącej audycji",
                color = colors.onSurface,
                fontSize = 26.sp * fontScale,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (current != null && current.end > current.begin) {
                val progress = ((now - current.begin).toFloat() / (current.end - current.begin)).coerceIn(0f, 1f)
                val left = ((current.end - now + 59) / 60).coerceAtLeast(0)
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(time(current.begin), color = secondary, fontSize = 18.sp * fontScale)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.weight(1f).height(8.dp),
                        color = accent,
                    )
                    Text("${time(current.end)}   (−$left min)", color = secondary, fontSize = 18.sp * fontScale)
                }
            }
            if (next != null) {
                Text(
                    "Następnie  ${time(next.begin)}  ${next.title}",
                    modifier = Modifier.padding(top = 2.dp),
                    color = secondary,
                    fontSize = 18.sp * fontScale,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
    buffered: Set<String> = emptySet(),
    recordingChannels: Set<String> = emptySet(),
    pipChannels: Set<String> = emptySet(),
    pickingPip: Boolean = false,
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
    InWindowDialog(width = 950.dp, height = 700.dp, onDismiss = onClose) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        MaterialTheme(colorScheme = colors) {
            Column(
                modifier = Modifier.fillMaxSize().background(colors.surface).focusRequester(focusRequester)
                    .focusable().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f, fill = false)) {
                        OutlinedButton(
                            onClick = { bouquetMenuExpanded = true },
                            enabled = !busy && bouquets.size > 1,
                        ) {
                            Text("${if (pickingPip) "PiP — " else ""}Bukiet: ${bouquet?.name ?: "Kanały"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    BackButton(onClick = onClose)
                }
                // Podpowiedź w osobnym wierszu — w jednym rzędzie ściskała przycisk bukietu do pionowego paska.
                Text(
                    if (recordings.isEmpty()) "← → bukiet   •   ↑ ↓ kanał   •   OK oglądaj   •   Menu bufor   •   Wstecz zamknij"
                    else "← → bukiet   •   ↑ ↓ nagranie   •   OK otwórz/odtwórz   •   Wstecz katalog wyżej/zamknij",
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                                val key = serviceKey(channel.ref)
                                val badge = when {
                                    key in pipChannels -> if (key in recordingChannels) "  ● PiP" else "  PiP"
                                    key in buffered -> if (key in recordingChannels) "  ● B" else "  B"
                                    else -> ""
                                }
                                Text(channel.name + badge, color = primaryText, fontSize = 22.sp * fontScale, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(event?.title ?: "Brak danych EPG", color = secondaryText, fontSize = 16.sp * fontScale, maxLines = 1)
                            }
                            if (event != null && event.end > event.begin) {
                                Text("${time(event.begin)}–${time(event.end)}", color = secondaryText, fontSize = 16.sp * fontScale)
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(8.dp)
                        .verticalScroll(remember(selected, channels) { androidx.compose.foundation.ScrollState(0) }),
                ) {
                    val channel = channels.getOrNull(selected)
                    val recording = channel?.let { recordings[it.ref] }
                    if (recording != null) {
                        Text(recording.title, color = accent, fontSize = 20.sp * fontScale, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            if (recording.isDir) "Katalog — OK otwiera" else recording.description.ifBlank { recordingDetails(recording) },
                            color = colors.onSurface,
                            fontSize = 17.sp * fontScale,
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
    /** Opcjonalny drugi wiersz pod pozycją (np. godziny audycji). */
    val subtitles: List<String?> = emptyList(),
    /** Opcjonalny dodatkowy wiersz pod opisem pozycji. */
    val details: List<String?> = emptyList(),
    /** Nagłówki sekcji wstawiane przed pozycją o danym indeksie. */
    val headers: Map<Int, String> = emptyMap(),
    /** Czy okno zamyka się samo po „Czasie okien akcji”. */
    val autoClose: Boolean = true,
    /** Akcja klawisza Wstecz (domyślnie samo zamknięcie). */
    val onBack: (() -> Unit)? = null,
    /** Identyfikator okna aktualizowanego w tle (np. odliczanie przełączenia). */
    val tag: String? = null,
    /** Dłuższy tekst nad listą (np. opis audycji). */
    val body: String? = null,
    /** Własny czas zamknięcia w sekundach (0 = nie zamykaj); null = „Czas okien akcji”. */
    val timeoutSec: Int? = null,
    /** Akcja klawisza Menu (domyślnie jak Wstecz). */
    val onMenu: (() -> Unit)? = null,
    /** Strzałki lewo/prawo (−1/+1) zamiast powrotu, np. zmiana godziny w EPG. */
    val onHorizontal: ((Int) -> Unit)? = null,
    val fontScale: Float = 1f,
    /** OK nie zamyka okna (wyjście klawiszem Wstecz) — np. przesunięcie dźwięku, ścieżka dźwięku. */
    val keepOpen: Boolean = false,
    val onSelect: (Int) -> Unit,
)

/** Krótki wskaźnik głośności aplikacji w prawym górnym rogu okna; nie zabiera fokusu. */
@Composable
internal fun VolumeOsd(
    volume: Int,
    muted: Boolean,
    owner: java.awt.Window,
    colors: androidx.compose.material3.ColorScheme,
) {
    InWindowDialog(width = 300.dp, height = 84.dp, onDismiss = {}, alignment = Alignment.TopEnd, scrim = false, margin = 24.dp) {
        MaterialTheme(colorScheme = colors) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = colors.surface, contentColor = colors.onSurface) {
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (muted) "Wyciszono ($volume%)" else "Głośność $volume%",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    LinearProgressIndicator(
                        progress = { if (muted) 0f else volume / DesktopConfig.MAX_VOLUME.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun QuickPickDialog(
    pick: QuickPick,
    colors: androidx.compose.material3.ColorScheme,
    onClose: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val scale = pick.fontScale.coerceIn(0.7f, 1.6f)
    val bodyHeight = if (pick.body.isNullOrBlank()) 0 else 260
    val rowsHeight = ((pick.options.size * 54 + (pick.subtitles.count { it != null } + pick.details.count { it != null }) * 24 + pick.headers.size * 40) * scale).toInt() +
        bodyHeight + if (pick.onHorizontal != null) 56 else 0
    val wide = pick.subtitles.isNotEmpty() || pick.details.isNotEmpty() || pick.headers.isNotEmpty() || bodyHeight > 0
    InWindowDialog(
        width = if (wide) (720 * scale).coerceAtLeast(640f).dp else 460.dp,
        height = (150 + rowsHeight).coerceAtMost(720).dp,
        onDismiss = onClose,
    ) {
        MaterialTheme(colorScheme = colors) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = colors.surface, contentColor = colors.onSurface) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pick.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = colors.primary)
                        BackButton(onClick = onClose)
                    }
                    if (pick.onHorizontal != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pick.onHorizontal.invoke(-1) }) { Text("◀") }
                        OutlinedButton(onClick = { pick.onHorizontal.invoke(1) }) { Text("▶") }
                        pick.onMenu?.let { OutlinedButton(onClick = it) { Text("Menu") } }
                    }
                    pick.body?.takeIf { it.isNotBlank() }?.let { body ->
                        Text(
                            body,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp).verticalScroll(rememberScrollState()),
                            fontSize = 16.sp * scale,
                            color = colors.onSurface,
                        )
                    }
                    val listState = rememberLazyListState()
                    val rowOf = remember(pick.headers) {
                        { index: Int -> index + pick.headers.keys.count { it <= index } }
                    }
                    LaunchedEffect(pick.selected) {
                        if (pick.selected in pick.options.indices) listState.animateScrollToItem((rowOf(pick.selected) - 2).coerceAtLeast(0))
                    }
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pick.options.forEachIndexed { index, option ->
                            pick.headers[index]?.let { header ->
                                item(key = "h$index") {
                                    Text(
                                        header,
                                        modifier = Modifier.padding(start = 8.dp, top = 10.dp),
                                        color = colors.primary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                    )
                                }
                            }
                            item(key = index) {
                                val isSelected = index == pick.selected
                                val textColor = if (isSelected) colors.onPrimaryContainer else colors.onSurface
                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(if (isSelected) colors.primaryContainer else Color.Transparent, RoundedCornerShape(10.dp))
                                        .clickable { onSelect(index) }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                ) {
                                    Text(option, color = textColor, fontSize = 20.sp * scale, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    pick.subtitles.getOrNull(index)?.let {
                                                                            Text(it, color = textColor.copy(alpha = 0.75f), fontSize = 15.sp * scale, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    pick.details.getOrNull(index)?.let {
                                        Text(it, color = textColor.copy(alpha = 0.75f), fontSize = 15.sp * scale, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
/** Obraz VLC rysowany w oknie aplikacji, skalowany wg [mode] i proporcji wyświetlania strumienia. */
@Composable
internal fun VideoView(session: VlcSession, mode: VideoScaleMode, modifier: Modifier = Modifier) {
    var aspect by remember(session) { mutableStateOf(0f) }
    LaunchedEffect(session) {
        while (true) {
            aspect = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { session.displayAspect() } ?: 0f
            delay(1000)
        }
    }
    val frameState = session.frames.frame
    val yuvState = session.frames.yuvState
    androidx.compose.foundation.Canvas(modifier) {
        val yuv = yuvState.value
        if (yuv != null) {
            val ratio = if (aspect > 0f) aspect else yuv.width.toFloat() / yuv.height
            val place = videoPlacement(mode, size.width, size.height, yuv.width, yuv.height, ratio)
            YuvShader.draw(
                this, session.frames, yuv,
                place.srcX.toFloat(), place.srcY.toFloat(), place.srcW.toFloat(), place.srcH.toFloat(),
                place.dstX, place.dstY, place.dstW, place.dstH,
            )
            return@Canvas
        }
        val image = frameState.value ?: return@Canvas
        val ratio = if (aspect > 0f) aspect else image.width.toFloat() / image.height
        val place = videoPlacement(mode, size.width, size.height, image.width, image.height, ratio)
        drawImage(
            image,
            srcOffset = androidx.compose.ui.unit.IntOffset(place.srcX, place.srcY),
            srcSize = androidx.compose.ui.unit.IntSize(place.srcW, place.srcH),
            dstOffset = androidx.compose.ui.unit.IntOffset(Math.round(place.dstX), Math.round(place.dstY)),
            dstSize = androidx.compose.ui.unit.IntSize(Math.round(place.dstW), Math.round(place.dstH)),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
        )
    }
}
