package pl.jardol.e2stream

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PrefsHistoryTest {
    @Test fun pipAppendDoesNotReorderHistoryButWatchingDoes() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.history = listOf("a:" to "A", "b:" to "B")
        prefs.historyAppend("B", "B renamed")
        prefs.historyAppend("c:", "C")
        assertEquals(listOf("a:", "b:", "c:"), prefs.history.map { it.first })
        prefs.historyPush("B", "B renamed")
        assertEquals(listOf("B", "a:", "c:"), prefs.history.map { it.first })
    }
}
