package pl.jardol.e2stream

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyMapTest {
    @Test
    fun navigationDefaultsMatchScreenshots() {
        val withoutBanner = KeyMap.navigationMap(KeyMap.navigationNoBannerDefaults, "")
        assertEquals(Act.CHANNEL_BANNER, withoutBanner[KeyEvent.KEYCODE_DPAD_CENTER])
        assertEquals(Act.EPG_CHANNEL, withoutBanner[KeyEvent.KEYCODE_DPAD_LEFT])
        assertEquals(Act.MULTI_EPG, withoutBanner[KeyEvent.KEYCODE_DPAD_RIGHT])
        assertEquals(Act.CHANNEL_LIST, withoutBanner[KeyEvent.KEYCODE_DPAD_UP])
        assertEquals(Act.CHANNEL_LIST, withoutBanner[KeyEvent.KEYCODE_DPAD_DOWN])

        val withBanner = KeyMap.navigationMap(KeyMap.navigationBannerDefaults, "")
        assertEquals(Act.EPG_EVENT, withBanner[KeyEvent.KEYCODE_DPAD_CENTER])
        assertEquals(Act.EPG_EVENT, withBanner[KeyEvent.KEYCODE_DPAD_LEFT])
        assertEquals(Act.EPG_CHANNEL, withBanner[KeyEvent.KEYCODE_DPAD_RIGHT])
        assertEquals(Act.CHANNEL_LIST, withBanner[KeyEvent.KEYCODE_DPAD_UP])
        assertEquals(Act.CHANNEL_LIST, withBanner[KeyEvent.KEYCODE_DPAD_DOWN])
    }

    @Test
    fun overridesAreIndependentAndCanBeReset() {
        val hidden = KeyMap.encode(mapOf(KeyEvent.KEYCODE_DPAD_CENTER to Act.CHANNEL_BANNER,
            KeyEvent.KEYCODE_DPAD_LEFT to Act.NONE))
        val visible = KeyMap.encode(mapOf(KeyEvent.KEYCODE_DPAD_CENTER to Act.CHANNEL_LIST,
            KeyEvent.KEYCODE_DPAD_LEFT to Act.EPG_SEARCH))
        assertEquals(Act.CHANNEL_BANNER, KeyMap.navigationMap(KeyMap.navigationNoBannerDefaults, hidden)[KeyEvent.KEYCODE_DPAD_CENTER])
        assertEquals(Act.NONE, KeyMap.navigationMap(KeyMap.navigationNoBannerDefaults, hidden)[KeyEvent.KEYCODE_DPAD_LEFT])
        assertEquals(Act.CHANNEL_LIST, KeyMap.navigationMap(KeyMap.navigationBannerDefaults, visible)[KeyEvent.KEYCODE_DPAD_CENTER])
        assertEquals(Act.EPG_SEARCH, KeyMap.navigationMap(KeyMap.navigationBannerDefaults, visible)[KeyEvent.KEYCODE_DPAD_LEFT])
        assertEquals(Act.EPG_CHANNEL, KeyMap.navigationMap(KeyMap.navigationNoBannerDefaults, "")[KeyEvent.KEYCODE_DPAD_LEFT])
    }

    @Test
    fun invalidOverridesDoNotReplaceDefaults() {
        val map = KeyMap.navigationMap(KeyMap.navigationNoBannerDefaults,
            "${KeyEvent.KEYCODE_DPAD_LEFT}=DEFAULT_OK,${KeyEvent.KEYCODE_DPAD_UP}=NO_SUCH_ACTION,999=MENU")
        assertEquals(Act.EPG_CHANNEL, map[KeyEvent.KEYCODE_DPAD_LEFT])
        assertEquals(Act.CHANNEL_LIST, map[KeyEvent.KEYCODE_DPAD_UP])
        assertEquals(null, map[999])
    }
}
