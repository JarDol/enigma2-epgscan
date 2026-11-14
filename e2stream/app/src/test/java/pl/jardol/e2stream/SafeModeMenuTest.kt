package pl.jardol.e2stream

import android.app.Application
import android.content.Context
import android.view.KeyEvent
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SafeModeMenuTest {
    @Test
    fun opensSafeMenuImmediatelyAndCanReopenItAfterBack() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("e2stream", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).playbackAttempt = "unfinished / VLC"
        val controller = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        val activity = controller.get()
        val recovery = ShadowDialog.getLatestDialog()
        assertNotNull(recovery)
        val button = recovery.window!!.decorView.findViewWithTag<Button>("chip_0")
        assertEquals(activity.getString(R.string.message_open_safe_menu), button.text.toString())

        button.performClick()

        assertFalse(recovery.isShowing)
        val menu = ShadowDialog.getLatestDialog()
        assertTrue(menu.isShowing)
        assertEquals(activity.getString(R.string.message_e2stream_safe_mode),
            menu.window!!.decorView.findViewWithTag<TextView>("title").text.toString())
        val list = menu.findViewById<ListView>(android.R.id.list)
            ?: findList(menu.window!!.decorView)
        assertNotNull(list)
        assertEquals(3, list!!.count)
        assertEquals(activity.getString(R.string.player_normal_start), (list.adapter.getItem(0) as TvDialog.Item).title)
        assertEquals(activity.getString(R.string.message_settings), (list.adapter.getItem(1) as TvDialog.Item).title)
        assertEquals(activity.getString(R.string.message_close), (list.adapter.getItem(2) as TvDialog.Item).title)
        assertEquals("unfinished / VLC", Prefs(context).playbackAttempt)
        assertNull(shadowOf(activity).nextStartedActivity)

        menu.cancel()
        assertFalse(menu.isShowing)
        activity.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        controller.pause().stop().destroy()
    }

    private fun findList(view: android.view.View): ListView? {
        if (view is ListView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findList(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
