package pl.jardol.e2stream.desktop

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun time(seconds: Long): String = clockFormat.format(Instant.ofEpochSecond(seconds))

@Composable
internal fun PlayerPage(
    channel: Channel,
    session: VlcSession,
    event: NowEvent?,
    status: String,
    onChannels: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    var bannerVisible by remember { mutableStateOf(true) }
    var bannerGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now().epochSecond
            delay(30_000)
        }
    }
    LaunchedEffect(channel.ref, bannerGeneration) {
        bannerVisible = true
        delay(5_000)
        bannerVisible = false
    }
    val currentEvent = event?.takeIf { it.begin <= now && now < it.end }
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val secondary = colors.onSurfaceVariant
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        SwingPanel(
            factory = { session.view },
            modifier = Modifier.fillMaxWidth().weight(1f),
            background = Color.Black,
        )
        Column(
            modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 24.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (bannerVisible) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "${channel.number}   ${channel.name}",
                        modifier = Modifier.weight(1f),
                        color = accent,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(time(now), color = secondary, fontSize = 20.sp)
                }
                Text(
                    currentEvent?.title ?: "Brak danych bieżącej audycji",
                    color = colors.onSurface,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (currentEvent != null && currentEvent.end > currentEvent.begin) {
                    val progress = ((now - currentEvent.begin).toFloat() / (currentEvent.end - currentEvent.begin)).coerceIn(0f, 1f)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(time(currentEvent.begin), color = secondary)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.weight(1f).padding(top = 8.dp).height(6.dp),
                            color = accent,
                        )
                        Text(time(currentEvent.end), color = secondary)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChannels) { Text("Lista kanałów") }
                OutlinedButton(onClick = { bannerGeneration++ }) { Text("Pasek") }
                OutlinedButton(onClick = session::togglePause) { Text("Pauza / Wznów") }
                OutlinedButton(onClick = onSettings) { Text("Ustawienia") }
                OutlinedButton(onClick = onBack) { Text("Zatrzymaj") }
                Text(status, modifier = Modifier.weight(1f).padding(top = 12.dp), color = secondary, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun ChannelListDialog(
    bouquet: Bouquet?,
    channels: List<Channel>,
    playing: Channel?,
    nowEvents: Map<String, NowEvent>,
    onClose: () -> Unit,
    onPlay: (Channel) -> Unit,
) {
    var selected by remember(channels, playing) {
        mutableIntStateOf(channels.indexOfFirst { it.ref == playing?.ref }.coerceAtLeast(0))
    }
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val secondary = colors.onSurfaceVariant
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
        resizable = true,
        onPreviewKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) false
            else when (event.key) {
                Key.DirectionDown -> {
                    if (channels.isNotEmpty()) selected = (selected + 1) % channels.size
                    true
                }
                Key.DirectionUp -> {
                    if (channels.isNotEmpty()) selected = (selected - 1 + channels.size) % channels.size
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        bouquet?.name ?: "Kanały",
                        modifier = Modifier.weight(1f),
                        color = accent,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("↑ ↓ kanał   •   OK oglądaj   •   Esc zamknij", color = secondary)
                }
                LazyColumn(Modifier.weight(3f), state = listState) {
                    itemsIndexed(channels) { index, channel ->
                        val event = nowEvents[serviceKey(channel.ref)]?.takeIf { it.begin <= now && now < it.end }
                        val isSelected = index == selected
                        val primaryText = if (isSelected) colors.onPrimaryContainer else colors.onSurface
                        val secondaryText = if (isSelected) colors.onPrimaryContainer else secondary
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(if (isSelected) colors.primaryContainer else Color.Transparent, RoundedCornerShape(6.dp))
                                .clickable {
                                    selected = index
                                    onPlay(channel)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(channel.number.toString(), modifier = Modifier.width(55.dp), color = secondaryText, fontSize = 20.sp)
                            Column(Modifier.weight(1f)) {
                                Text(channel.name, color = primaryText, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(event?.title ?: "Brak danych EPG", color = secondaryText, fontSize = 16.sp, maxLines = 1)
                            }
                            if (event != null && event.end > event.begin) {
                                Text("${time(event.begin)}–${time(event.end)}", color = secondaryText, fontSize = 16.sp)
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
                Column(Modifier.weight(1f).padding(8.dp)) {
                    val channel = channels.getOrNull(selected)
                    Text(channel?.name.orEmpty(), color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        channel?.let { nowEvents[serviceKey(it.ref)]?.takeIf { event -> event.begin <= now && now < event.end }?.title }
                            ?: "Brak danych EPG",
                        color = colors.onSurface,
                        fontSize = 19.sp,
                    )
                    Text("Wybierz kanał myszą lub naciśnij Enter.", color = secondary)
                }
            }
        }
    }
}
