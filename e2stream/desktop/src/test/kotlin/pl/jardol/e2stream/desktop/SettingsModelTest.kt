package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsModelTest {
    private val items = listOf(
        SettingItem.Header("A"),
        SettingItem.Action("first") {},
        SettingItem.Action("second") {},
        SettingItem.Header("B"),
        SettingItem.Action("third") {},
    )

    @Test
    fun selectionSkipsHeadersAndWraps() {
        assertEquals(1, firstSelectable(items))
        assertEquals(4, moveSelection(items, 2, 1))
        assertEquals(1, moveSelection(items, 4, 1))
        assertEquals(4, moveSelection(items, 1, -1))
    }

    @Test
    fun backLeavesEditorThenChoiceThenScreens() {
        val navigator = SettingsNavigator()
        navigator.open(SettingsScreen.DECODER)
        navigator.openChoice(SettingsChoice("x", listOf("a"), 0) {})
        navigator.back()
        assertNull(navigator.choice)
        assertEquals(SettingsScreen.DECODER, navigator.screen)
        navigator.back()
        assertEquals(SettingsScreen.SECTIONS, navigator.screen)
        navigator.back()
        assertFalse(navigator.isOpen)
    }

    @Test
    fun remoteKeysDriveChoiceAndEditor() {
        val navigator = SettingsNavigator()
        navigator.open()
        var picked = -1
        navigator.openChoice(SettingsChoice("x", listOf("a", "b", "c"), 0) { picked = it })
        assertTrue(navigator.handleKey(VirtualRemoteKey.UP, items))
        assertTrue(navigator.handleKey(VirtualRemoteKey.OK, items))
        assertEquals(2, picked)

        var committed = ""
        navigator.openEditor(SettingsEditor("PIN", "", digitsOnly = true, maxLength = 2) { committed = it; null })
        navigator.handleKey(VirtualRemoteKey.DIGIT_4, items)
        navigator.handleKey(VirtualRemoteKey.DIGIT_2, items)
        navigator.handleKey(VirtualRemoteKey.DIGIT_7, items)
        navigator.handleKey(VirtualRemoteKey.OK, items)
        assertEquals("42", committed)
        assertNull(navigator.editor)
    }

    @Test
    fun rejectedEditorValueKeepsEditorOpen() {
        val navigator = SettingsNavigator()
        navigator.openEditor(SettingsEditor("Port", "0") { "zły" })
        navigator.commitEditor()
        assertEquals("zły", navigator.editorError)
        assertTrue(navigator.isVisible)
    }

    @Test
    fun okTogglesAndActivatesSelectedItem() {
        var toggled: Boolean? = null
        val list = listOf(SettingItem.Toggle("t", false) { toggled = it })
        val navigator = SettingsNavigator()
        navigator.open()
        navigator.select(0)
        navigator.handleKey(VirtualRemoteKey.OK, list)
        assertEquals(true, toggled)
    }

    @Test
    fun mouseModeNeedsRealMovementAfterKeyboard() {
        val tracker = InputModeTracker(moveThreshold = 8)
        tracker.onKey()
        assertFalse(tracker.mouseActive)
        tracker.onMouseMoved(100, 100)
        tracker.onMouseMoved(102, 101)
        assertFalse(tracker.mouseActive)
        tracker.onMouseMoved(120, 100)
        assertTrue(tracker.mouseActive)
        tracker.onKey()
        tracker.onMouseButton()
        assertTrue(tracker.mouseActive)
    }

    @Test
    fun navigationMapAppliesValidOverridesOnly() {
        val map = NavigationKeyMap.resolve(
            NavigationKeyMap.noBannerDefaults,
            "OK=CHANNEL_LIST,UP=CH_UP,MENU=SETTINGS,DOWN=BOGUS",
        )
        assertEquals(NavAction.CHANNEL_LIST, map[VirtualRemoteKey.OK])
        assertEquals(NavAction.CH_UP, map[VirtualRemoteKey.UP])
        assertEquals(NavAction.HISTORY, map[VirtualRemoteKey.DOWN])
        assertNull(map[VirtualRemoteKey.MENU])
        assertEquals(map, NavigationKeyMap.resolve(emptyMap(), NavigationKeyMap.encode(map)))
    }

    @Test
    fun vlcOptionsFollowPlaybackSettings() {
        val options = vlcMediaOptions(DesktopConfig(bufferMs = 700, vlcHwMode = "off", audioLang = "pl, en"))
        assertTrue(":network-caching=700" in options)
        assertTrue(":avcodec-hw=none" in options)
        assertTrue(":audio-language=pl,en" in options)
        assertNull(vlcDeinterlaceMode("off"))
    }

    @Test
    fun secondaryPlaybackDoesNotEnableAudio() {
        val main = vlcMediaOptions(DesktopConfig())
        val pip = vlcMediaOptions(DesktopConfig(), audioEnabled = false)
        val preview = vlcMediaOptions(DesktopConfig(), audioEnabled = false)
        assertFalse(":no-audio" in main)
        assertTrue(":no-audio" in pip)
        assertTrue(":no-audio" in preview)
    }

    @Test
    fun framePacerLimitsPublishedFramesButAllowsImmediateFirstFrame() {
        val pacer = FramePacer()
        assertTrue(pacer.shouldPublish(1_000, 2_000))
        assertFalse(pacer.shouldPublish(2_999, 2_000))
        assertTrue(pacer.shouldPublish(3_000, 2_000))
        assertTrue(pacer.shouldPublish(3_001, 0))
    }
}
