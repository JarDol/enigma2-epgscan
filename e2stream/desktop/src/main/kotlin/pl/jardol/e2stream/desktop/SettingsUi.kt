package pl.jardol.e2stream.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState

/** Czy pokazywać przyciski „Wróć” (sterowanie myszą lub ustawienie „zawsze”). */
internal val LocalBackButtonVisible = compositionLocalOf { true }

@Composable
internal fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "Wróć") {
    if (LocalBackButtonVisible.current) {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text("◀  ${tr(label)}") }
    }
}

internal data class RemoteTestEvent(
    val time: String,
    val source: String,
    val physical: String,
    val mapped: String,
)

@Composable
internal fun SettingsWindow(
    navigator: SettingsNavigator,
    items: List<SettingItem>,
    colors: ColorScheme,
    status: String,
    captureLabel: String?,
    captureMouse: Boolean = false,
    onCancelCapture: () -> Unit,
    onWindow: (java.awt.Window?) -> Unit,
    testContent: @Composable () -> Unit,
) {
    InWindowDialog(width = 1040.dp, height = 740.dp, onDismiss = navigator::close) {
        DisposableEffect(Unit) {
            onWindow(null)
            onDispose { }
        }
        MaterialTheme(colorScheme = colors) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colors.background,
                contentColor = colors.onBackground,
            ) {
                Box(Modifier.fillMaxSize()) {
                    SettingsBody(navigator, items, status, testContent)
                    navigator.choice?.let { ChoiceOverlay(navigator, it) }
                    navigator.editor?.let { EditorOverlay(navigator, it) }
                    if (captureLabel != null) CaptureOverlay(captureLabel, captureMouse, onCancelCapture)
                }
            }
        }
    }
}

@Composable
private fun SettingsBody(
    navigator: SettingsNavigator,
    items: List<SettingItem>,
    status: String,
    testContent: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val screen = navigator.screen
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (screen == null || screen == SettingsScreen.SECTIONS) "E2Stream" else tr("Ustawienia"),
                    color = colors.onSurfaceVariant,
                    fontSize = 15.sp,
                )
                Text(
                    screen?.title?.let(::tr) ?: "E2Stream",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            BackButton(onClick = navigator::back)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (screen == SettingsScreen.REMOTE_TEST) {
                testContent()
            } else if (screen != null) {
                SettingsList(navigator, items)
            }
        }
        Text(status, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            tr(
                if (screen == SettingsScreen.REMOTE_TEST) "Wstecz dwa razy — wyjście z testu"
                else "▲ ▼ wybór   •   OK zatwierdź   •   Wstecz poziom wyżej   •   Menu zamknij",
            ),
            color = colors.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun SettingsList(navigator: SettingsNavigator, items: List<SettingItem>) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val selected = navigator.selected
    KeepVisible(listState, selected, navigator.screen)
    LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(items) { index, item ->
            when (item) {
                is SettingItem.Header -> Text(
                    item.label,
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 14.dp, bottom = 2.dp, start = 4.dp),
                    color = colors.primary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                is SettingItem.Toggle, is SettingItem.Action -> {
                    val isSelected = index == selected
                    val primary = if (isSelected) colors.onPrimaryContainer else colors.onSurface
                    val secondary = if (isSelected) colors.onPrimaryContainer else colors.onSurfaceVariant
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(if (isSelected) colors.primaryContainer else colors.surface, RoundedCornerShape(12.dp))
                            .clickable {
                                navigator.select(index)
                                navigator.activate(item)
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label, color = primary, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val summary = when (item) {
                                is SettingItem.Toggle -> item.summary
                                is SettingItem.Action -> item.summary
                                else -> null
                            }
                            if (!summary.isNullOrBlank()) {
                                Text(summary, color = secondary, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (item is SettingItem.Toggle) {
                            Text(
                                if (item.value) "WŁ." else "WYŁ.",
                                modifier = Modifier
                                    .background(if (item.value) colors.primary else colors.surfaceVariant, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                color = if (item.value) colors.onPrimary else colors.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Text("›", color = secondary, fontSize = 24.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeepVisible(listState: LazyListState, selected: Int, key: Any?) {
    LaunchedEffect(selected, key) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (visible.isEmpty() || selected <= first || selected >= last) {
            listState.animateScrollToItem((selected - 2).coerceAtLeast(0))
        }
    }
}

@Composable
private fun OverlayScrim(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ChoiceOverlay(navigator: SettingsNavigator, choice: SettingsChoice) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    KeepVisible(listState, navigator.choiceSelected, choice)
    OverlayScrim {
        Column(
            Modifier.width(620.dp).heightIn(max = 600.dp)
                .background(colors.surface, RoundedCornerShape(20.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(choice.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = colors.primary)
                BackButton(onClick = navigator::closeChoice)
            }
            choice.body?.let { Text(it, color = colors.onSurface, fontSize = 16.sp) }
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(choice.options) { index, option ->
                    val isSelected = index == navigator.choiceSelected
                    Text(
                        (if (index == choice.selected) "●  " else "○  ") + option,
                        modifier = Modifier.fillMaxWidth()
                            .background(if (isSelected) colors.primaryContainer else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { navigator.pickChoice(index) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        color = if (isSelected) colors.onPrimaryContainer else colors.onSurface,
                        fontSize = 19.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorOverlay(navigator: SettingsNavigator, editor: SettingsEditor) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember(editor) { FocusRequester() }
    LaunchedEffect(editor) { focusRequester.requestFocus() }
    // Tekst wpisany pilotem lub klawiaturą ekranową — kursor na końcu.
    var field by remember(editor) {
        mutableStateOf(TextFieldValue(navigator.editorText, TextRange(navigator.editorText.length)))
    }
    if (field.text != navigator.editorText) {
        field = TextFieldValue(navigator.editorText, TextRange(navigator.editorText.length))
    }
    val layout = navigator.oskLayout()
    OverlayScrim {
        Column(
            Modifier.width(if (editor.digitsOnly) 560.dp else 900.dp)
                .background(colors.surface, RoundedCornerShape(20.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(editor.title, style = MaterialTheme.typography.titleLarge, color = colors.primary)
            OutlinedTextField(
                value = field,
                onValueChange = { value ->
                    field = value
                    navigator.updateEditorText(value.text)
                },
                singleLine = true,
                placeholder = if (editor.hint.isNotBlank()) ({ Text(editor.hint) }) else null,
                visualTransformation = if (editor.secret) PasswordVisualTransformation() else VisualTransformation.None,
                isError = navigator.editorError != null,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false
                    else when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            layout.getOrNull(navigator.oskRow)?.getOrNull(navigator.oskCol)?.let(navigator::oskPress)
                                ?: navigator.commitEditor()
                            true
                        }
                        Key.Escape -> { navigator.cancelEditor(); true }
                        else -> false
                    }
                },
            )
            navigator.editorError?.let { Text(it, color = colors.error) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                layout.forEachIndexed { rowIndex, keys ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        keys.forEachIndexed { colIndex, key ->
                            val selected = rowIndex == navigator.oskRow && colIndex == navigator.oskCol
                            Box(
                                Modifier.weight(key.weight).height(if (editor.digitsOnly) 52.dp else 44.dp)
                                    .background(
                                        if (selected) colors.primary else colors.surfaceVariant,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable {
                                        navigator.oskSelect(rowIndex, colIndex)
                                        navigator.oskPress(key)
                                        focusRequester.requestFocus()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    key.label,
                                    color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                                    fontSize = 19.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "Strzałki + OK – klawiatura ekranowa  •  cyfry pilota wpisują  •  Kanał − usuwa znak  •  " +
                    "Kanał + spacja  •  Wstecz anuluj" + if (editor.digitsOnly) "" else "  •  🎤 Dyktuj = dyktowanie Windows (Win+H)",
                color = colors.onSurfaceVariant,
                fontSize = 13.sp,
            )
            if (editor.hint.isNotBlank()) Text(editor.hint, color = colors.onSurfaceVariant, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = navigator::commitEditor) { Text("Zapisz") }
                BackButton(onClick = navigator::cancelEditor, label = "Anuluj")
            }
        }
    }
}

@Composable
private fun CaptureOverlay(label: String, captureMouse: Boolean, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OverlayScrim {
        Column(
            Modifier.width(620.dp).background(colors.surface, RoundedCornerShape(20.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Przypisz: $label", style = MaterialTheme.typography.titleLarge, color = colors.primary)
            Text(
                if (captureMouse) "Naciśnij i puść przycisk lub kombinację klawiatury i myszy."
                else "Naciśnij teraz klawisz pilota lub klawiatury.",
                fontSize = 19.sp,
            )
            Text("Bez naciśnięcia okno zamknie się po 10 sekundach.", color = colors.onSurfaceVariant)
            if (!captureMouse) OutlinedButton(onClick = onCancel) { Text("Anuluj") }
        }
    }
}

@Composable
internal fun RemoteTestPanel(events: List<RemoteTestEvent>, hidDiagnostic: String) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Naciskaj klawisze pilota lub klawiatury. Program pokazuje, co odebrał i do jakiego przycisku jest to przypisane. " +
                "Klawisze nie wykonują w tym oknie żadnych akcji (poza Power).",
            color = colors.onSurfaceVariant,
        )
        Text("Odbiornik HID: $hidDiagnostic", color = colors.onSurfaceVariant, fontSize = 14.sp)
        val last = events.firstOrNull()
        Column(
            Modifier.fillMaxWidth().background(colors.primaryContainer, RoundedCornerShape(16.dp)).padding(20.dp),
        ) {
            Text(
                last?.mapped ?: "Czekam na klawisz…",
                color = colors.onPrimaryContainer,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
            if (last != null) {
                Text("${last.source} · ${last.physical}", color = colors.onPrimaryContainer, fontSize = 17.sp)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(events.drop(1)) { event ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(event.time, color = colors.onSurfaceVariant, modifier = Modifier.width(90.dp))
                    Text(event.mapped, modifier = Modifier.width(220.dp), fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("${event.source} · ${event.physical}", color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
