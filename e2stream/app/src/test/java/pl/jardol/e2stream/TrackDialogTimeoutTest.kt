package pl.jardol.e2stream

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TrackDialogTimeoutTest {
    @Test fun trackDialogResetsAndClosesAfterInactivity() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val dialog = TvDialog.singleLive(controller.get(), "Audio", listOf("Polski"), 0, timeoutSec = 5) { it }
        try {
            dialog.onWindowFocusChanged(true)
            shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS)
            assertTrue(dialog.isShowing)
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS)
            assertTrue(dialog.isShowing)
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertFalse(dialog.isShowing)
        } finally {
            dialog.dismiss()
            controller.destroy()
        }
    }

    @Test fun disabledTimeoutKeepsDialogOpen() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val dialog = TvDialog.singleLive(controller.get(), "Audio", listOf("Polski"), 0) { it }
        try {
            dialog.onWindowFocusChanged(true)
            shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS)
            assertTrue(dialog.isShowing)
        } finally {
            dialog.dismiss()
            controller.destroy()
        }
    }
}
