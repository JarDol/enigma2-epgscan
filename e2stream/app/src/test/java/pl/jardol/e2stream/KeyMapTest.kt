package pl.jardol.e2stream

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyMapTest {
    @Test
    fun navigationDefaultsRetainExistingMappings() {
        val base = KeyMap.defaults.toMutableMap()
        base[KeyEvent.KEYCODE_DPAD_LEFT] = Act.AUDIO
        val map = KeyMap.navigationMap(base, "")
        assertEquals(Act.DEFAULT_OK, map[KeyEvent.KEYCODE_DPAD_CENTER])
        assertEquals(Act.AUDIO, map[KeyEvent.KEYCODE_DPAD_LEFT])
        assertEquals(Act.CH_UP, map[KeyEvent.KEYCODE_DPAD_UP])
    }

    @Test
    fun overridesAreIndependentAndCanBeReset() {
        val base = KeyMap.defaults
        val hidden = KeyMap.encode(mapOf(KeyEvent.KEYCODE_DPAD_CENTER to Act.CHANNEL_BANNER,
            KeyEvent.KEYCODE_DPAD_LEFT to Act.NONE))
        val visible = KeyMap.encode(mapOf(KeyEvent.KEYCODE_DPAD_CENTER to Act.CHANNEL_LIST,
            KeyEvent.KEYCODE_DPAD_LEFT to Act.EPG_SEARCH))
        assertEquals(Act.CHANNEL_BANNER, KeyMap.navigationMap(base, hidden)[KeyEvent.KEYCODE_DPAD_CENTER])
        assertEquals(Act.NONE, KeyMap.navigationMap(base, hidden)[KeyEvent.KEYCODE_DPAD_LEFT])
        assertEquals(Act.CHANNEL_LIST, KeyMap.navigationMap(base, visible)[KeyEvent.KEYCODE_DPAD_CENTER])
        assertEquals(Act.EPG_SEARCH, KeyMap.navigationMap(base, visible)[KeyEvent.KEYCODE_DPAD_LEFT])
        assertEquals(Act.EPG_CHANNEL, KeyMap.navigationMap(base, "")[KeyEvent.KEYCODE_DPAD_LEFT])
    }

    @Test
    fun invalidOverridesDoNotReplaceDefaults() {
        val map = KeyMap.navigationMap(KeyMap.defaults,
            "${KeyEvent.KEYCODE_DPAD_LEFT}=DEFAULT_OK,${KeyEvent.KEYCODE_DPAD_UP}=NO_SUCH_ACTION,999=MENU")
        assertEquals(Act.EPG_CHANNEL, map[KeyEvent.KEYCODE_DPAD_LEFT])
        assertEquals(Act.CH_UP, map[KeyEvent.KEYCODE_DPAD_UP])
        assertEquals(null, map[999])
    }
}
