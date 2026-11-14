package pl.jardol.e2stream.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/** Ostatnie źródło sterowania: mysz pokazuje przyciski „Wróć”, klawiatura i pilot je ukrywają. */
internal class InputModeTracker(private val moveThreshold: Int = 8) {
    var mouseActive by mutableStateOf(true)
        private set
    private var lastX: Int? = null
    private var lastY: Int? = null

    fun onMouseMoved(x: Int, y: Int) {
        val previousX = lastX
        val previousY = lastY
        lastX = x
        lastY = y
        if (previousX != null && previousY != null &&
            abs(x - previousX) + abs(y - previousY) >= moveThreshold
        ) {
            mouseActive = true
        }
    }

    fun onMouseButton() {
        mouseActive = true
    }

    fun onKey() {
        mouseActive = false
        lastX = null
        lastY = null
    }
}

internal enum class SettingsScreen(val title: String, val description: String = "") {
    SECTIONS("Ustawienia"),
    DECODER("Dekoder Enigma2", "adres, porty, logowanie OpenWebif, wtyczka EPG Scan, bukiety"),
    PLAYBACK("Odtwarzanie i obraz", "VLC: przeplot, akceleracja, bufor, obraz, okno, timeshift, dźwięk"),
    APPEARANCE("Wygląd", "czcionki i ikony: lista kanałów, EPG, pasek kanału"),
    EPG("EPG i ikony kanałów", "pobieranie EPG, ikony z dekodera"),
    GENERAL("Ogólne", "bukiety, kanał startowy, menu, czasy okien, harmonogram, język, PIN"),
    REMOTE("Pilot i klawiatura", "pilot HID / IR (WinLIRC), mapowanie klawiszy, OK i strzałki, test pilota"),
    INFO("Informacje i aktualizacje", "wersja aplikacji, opis aplikacji i wtyczki EPG Scan"),
    LOGS("Logi i zrzuty", "statystyki, log na dekoder, miejsce zapisu, diagnostyka pilota"),
    REMOTE_KEYS("Klawisze pilota"),
    NAV_NO_BANNER("OK i strzałki — bez paska"),
    NAV_BANNER("OK i strzałki — z paskiem kanału"),
    REMOTE_TEST("Test pilota"),
    BOUQUETS("Widoczne bukiety"),
    MENU_ITEMS("Pozycje i kolejność menu"),
    ;

    companion object {
        val sections = listOf(DECODER, PLAYBACK, APPEARANCE, EPG, GENERAL, REMOTE, INFO, LOGS)
    }
}

internal sealed interface SettingItem {
    val label: String

    data class Header(override val label: String) : SettingItem

    data class Toggle(
        override val label: String,
        val value: Boolean,
        val summary: String? = null,
        val onChange: (Boolean) -> Unit,
    ) : SettingItem

    data class Action(
        override val label: String,
        val summary: String? = null,
        val onClick: () -> Unit,
    ) : SettingItem
}

internal data class SettingsChoice(
    val title: String,
    val options: List<String>,
    val selected: Int,
    /** Dłuższy opis nad listą. */
    val body: String? = null,
    val onSelect: (Int) -> Unit,
)

internal data class SettingsEditor(
    val title: String,
    val initial: String,
    val hint: String = "",
    val secret: Boolean = false,
    val digitsOnly: Boolean = false,
    val maxLength: Int = 200,
    /** Zwraca komunikat błędu albo null, gdy wartość została przyjęta. */
    val onCommit: (String) -> String?,
)

internal fun isSelectable(item: SettingItem?): Boolean = item != null && item !is SettingItem.Header

internal fun firstSelectable(items: List<SettingItem>): Int =
    items.indexOfFirst(::isSelectable).coerceAtLeast(0)

/** Przesuwa zaznaczenie o [delta] pozycji, pomijając nagłówki i zawijając na krańcach listy. */
internal fun moveSelection(items: List<SettingItem>, current: Int, delta: Int): Int {
    if (items.none(::isSelectable) || delta == 0) return current
    val step = if (delta > 0) 1 else -1
    var remaining = abs(delta)
    var index = current.coerceIn(0, items.lastIndex)
    var guard = items.size * (remaining + 1)
    while (remaining > 0 && guard-- > 0) {
        index = (index + step).mod(items.size)
        if (isSelectable(items[index])) remaining--
    }
    return index
}

internal class SettingsNavigator {
    private data class Entry(val screen: SettingsScreen, val selected: Int)

    private var stack by mutableStateOf(emptyList<Entry>())
    var choice by mutableStateOf<SettingsChoice?>(null)
        private set
    var choiceSelected by mutableStateOf(0)
    var editor by mutableStateOf<SettingsEditor?>(null)
        private set
    var editorText by mutableStateOf("")
    var editorError by mutableStateOf<String?>(null)
        private set
    /** Klawiatura ekranowa okna edycji: zaznaczony klawisz i wielkie litery. */
    var oskRow by mutableStateOf(0)
        private set
    var oskCol by mutableStateOf(0)
        private set
    var oskShift by mutableStateOf(false)
        private set
    /** Dyktowanie Windows (Win+H) do pola edycji — ustawiane przez okno główne. */
    var onVoice: () -> Unit = {}

    fun oskLayout(): List<List<OskKey>> = OnScreenKeyboard.layout(editor?.digitsOnly == true, oskShift)

    val isOpen: Boolean get() = stack.isNotEmpty()
    val isVisible: Boolean get() = isOpen || editor != null
    val screen: SettingsScreen? get() = stack.lastOrNull()?.screen
    val selected: Int get() = stack.lastOrNull()?.selected ?: 0
    val depth: Int get() = stack.size

    fun open(screen: SettingsScreen = SettingsScreen.SECTIONS) {
        stack = if (screen == SettingsScreen.SECTIONS) listOf(Entry(screen, 0))
        else listOf(Entry(SettingsScreen.SECTIONS, SettingsScreen.sections.indexOf(screen).coerceAtLeast(0)), Entry(screen, 0))
        choice = null
    }

    fun push(screen: SettingsScreen, items: List<SettingItem> = emptyList()) {
        stack = stack + Entry(screen, firstSelectable(items))
        choice = null
    }

    fun close() {
        stack = emptyList()
        choice = null
        editor = null
    }

    fun select(index: Int) {
        val top = stack.lastOrNull() ?: return
        stack = stack.dropLast(1) + top.copy(selected = index)
    }

    fun openChoice(value: SettingsChoice) {
        choice = value
        choiceSelected = value.selected.coerceIn(0, (value.options.size - 1).coerceAtLeast(0))
    }

    fun closeChoice() {
        choice = null
    }

    fun pickChoice(index: Int) {
        val current = choice ?: return
        choice = null
        if (index in current.options.indices) current.onSelect(index)
    }

    fun openEditor(value: SettingsEditor) {
        editor = value
        editorText = value.initial
        editorError = null
        oskShift = false
        val (row, col) = OnScreenKeyboard.start(OnScreenKeyboard.layout(value.digitsOnly, false))
        oskRow = row
        oskCol = col
    }

    fun oskMove(dRow: Int, dCol: Int) {
        val (row, col) = OnScreenKeyboard.move(oskLayout(), oskRow, oskCol, dRow, dCol)
        oskRow = row
        oskCol = col
    }

    fun oskSelect(row: Int, col: Int) {
        oskRow = row
        oskCol = col
    }

    fun oskPress(key: OskKey) {
        when (key.action) {
            OskAction.CHAR -> updateEditorText(editorText + key.text)
            OskAction.SPACE -> updateEditorText("$editorText ")
            OskAction.BACKSPACE -> updateEditorText(editorText.dropLast(1))
            OskAction.CLEAR -> updateEditorText("")
            OskAction.SHIFT -> oskShift = !oskShift
            OskAction.VOICE -> onVoice()
            OskAction.DONE -> commitEditor()
        }
    }

    fun updateEditorText(value: String) {
        val current = editor ?: return
        val filtered = if (current.digitsOnly) value.filter(Char::isDigit) else value
        editorText = filtered.take(current.maxLength)
        editorError = null
    }

    fun commitEditor() {
        val current = editor ?: return
        val error = current.onCommit(editorText)
        if (error == null) {
            if (editor === current) editor = null
        } else {
            editorError = error
        }
    }

    fun cancelEditor() {
        editor = null
        editorError = null
    }

    /** Wstecz: zamyka okno wyboru lub edytor, potem schodzi poziom wyżej; na szczycie zamyka ustawienia. */
    fun back() {
        when {
            editor != null -> cancelEditor()
            choice != null -> choice = null
            stack.isNotEmpty() -> stack = stack.dropLast(1)
        }
    }

    fun handleKey(key: VirtualRemoteKey, items: List<SettingItem>): Boolean {
        editor?.let {
            when (key) {
                VirtualRemoteKey.OK -> oskLayout().getOrNull(oskRow)?.getOrNull(oskCol)?.let(::oskPress) ?: commitEditor()
                VirtualRemoteKey.UP -> oskMove(-1, 0)
                VirtualRemoteKey.DOWN -> oskMove(1, 0)
                VirtualRemoteKey.LEFT -> oskMove(0, -1)
                VirtualRemoteKey.RIGHT -> oskMove(0, 1)
                VirtualRemoteKey.DELETE, VirtualRemoteKey.CHANNEL_DOWN -> updateEditorText(editorText.dropLast(1))
                VirtualRemoteKey.CHANNEL_UP -> updateEditorText("$editorText ")
                VirtualRemoteKey.VOICE, VirtualRemoteKey.SEARCH -> onVoice()
                VirtualRemoteKey.BACK, VirtualRemoteKey.HOME -> cancelEditor()
                else -> digitOf(key)?.let { digit -> updateEditorText(editorText + digit) } ?: return false
            }
            return true
        }
        choice?.let { current ->
            val count = current.options.size
            when (key) {
                VirtualRemoteKey.UP -> if (count > 0) choiceSelected = (choiceSelected - 1).mod(count)
                VirtualRemoteKey.DOWN -> if (count > 0) choiceSelected = (choiceSelected + 1).mod(count)
                VirtualRemoteKey.OK -> pickChoice(choiceSelected)
                VirtualRemoteKey.BACK, VirtualRemoteKey.LEFT -> closeChoice()
                VirtualRemoteKey.HOME -> close()
                else -> return false
            }
            return true
        }
        if (!isOpen) return false
        when (key) {
            VirtualRemoteKey.UP -> select(moveSelection(items, selected, -1))
            VirtualRemoteKey.DOWN -> select(moveSelection(items, selected, 1))
            VirtualRemoteKey.CHANNEL_UP -> select(moveSelection(items, selected, -8))
            VirtualRemoteKey.CHANNEL_DOWN -> select(moveSelection(items, selected, 8))
            VirtualRemoteKey.OK, VirtualRemoteKey.RIGHT -> activate(items.getOrNull(selected), key == VirtualRemoteKey.OK)
            VirtualRemoteKey.BACK, VirtualRemoteKey.LEFT -> back()
            VirtualRemoteKey.HOME, VirtualRemoteKey.MENU -> close()
            else -> return false
        }
        return true
    }

    fun activate(item: SettingItem?, includeToggles: Boolean = true) {
        when (item) {
            is SettingItem.Toggle -> if (includeToggles) item.onChange(!item.value)
            is SettingItem.Action -> item.onClick()
            is SettingItem.Header, null -> Unit
        }
    }

    private fun digitOf(key: VirtualRemoteKey): Char? =
        key.name.removePrefix("DIGIT_").takeIf { key.name.startsWith("DIGIT_") }?.single()
}
