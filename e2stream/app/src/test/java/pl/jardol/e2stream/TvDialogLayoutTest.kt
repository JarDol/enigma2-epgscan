package pl.jardol.e2stream

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvDialogLayoutTest {
    @Test
    fun scheduleOverlayButtonsStayReadableAtDifferentWidths() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis() / 1000
        val timer = ZapTimers.Timer("1:0:1:123:456:", "Kanał", "Audycja", now + 120, now + 720)
        context.getSharedPreferences("zaptimers", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowSettings.setCanDrawOverlays(true)
        ZapTimers.add(context, timer)
        try {
            assertTrue(ZapOverlayMonitor.show(context, timer))
            val active = ZapOverlayMonitor::class.java.getDeclaredField("active").apply { isAccessible = true }
                .get(ZapOverlayMonitor) as Map<*, *>
            val overlay = active[timer.key()]!!
            val root = overlay.javaClass.getDeclaredField("root").apply { isAccessible = true }.get(overlay) as ViewGroup
            val buttons = root.getChildAt(root.childCount - 1) as LinearLayout
            assertEquals(3, buttons.childCount)
            val labels = (0 until buttons.childCount).map { (buttons.getChildAt(it) as Button).text.toString() }
            assertTrue(labels == listOf("Przełącz", "Dekoder", "Anuluj") ||
                labels == listOf("Switch", "Receiver", "Cancel"))

            for (width in listOf(520, 390, 300, 520)) {
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.AT_MOST),
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                assertEquals(LinearLayout.HORIZONTAL, buttons.orientation)
                assertEquals(root.width - root.paddingLeft - root.paddingRight, buttons.width)
                for (i in 0 until buttons.childCount) {
                    val button = buttons.getChildAt(i) as Button
                    assertEquals("Button wraps at width $width: ${button.text}", 1, button.lineCount)
                    assertTrue("Button clipped at width $width: ${button.text}",
                        button.left >= 0 && button.right <= buttons.width && button.bottom <= buttons.height)
                    assertTrue("Text clipped at width $width: ${button.text}",
                        button.layout.getEllipsisCount(0) == 0 &&
                            button.paint.measureText(button.text.toString()) <=
                            button.width - button.compoundPaddingLeft - button.compoundPaddingRight)
                }
            }
        } finally {
            ZapOverlayMonitor.dismissAll()
            ShadowSettings.setCanDrawOverlays(false)
        }
    }

    @Test
    fun receiverRowsKeepAddressesVisibleAndDescriptionsCompact() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val items = listOf(
            TvDialog.Item(
                "192.0.2.33:80",
                "Potwierdzony dekoder  ·  Odbiornik 4K  ·  openpli 9.2-release  ·  EPG Scan 2.18.0-beta.12  ·  mDNS",
            ),
            TvDialog.Item("198.51.100.99:80", "Wymaga logowania — Enigma2 niepotwierdzona"),
            TvDialog.Item("223.255.255.254:65535", "mDNS"),
        )
        var chosen = -1
        val dialog = TvDialog.list(activity, "Znalezione urządzenia", items, stacked = true) { chosen = it }
        try {
            val list = findList(dialog.window!!.decorView)!!
            for (width in listOf(520, 300)) {
                for ((position, item) in items.withIndex()) {
                    val row = list.adapter.getView(position, null, list)
                    row.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST),
                    )
                    row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                    val title = row.findViewWithTag<TextView>("t")
                    val subtitle = row.findViewWithTag<TextView>("s")
                    assertEquals(item.title, title.text.toString())
                    assertEquals(item.sub, subtitle.text.toString())
                    assertEquals(View.VISIBLE, subtitle.visibility)
                    assertEquals("Address must stay on one line at width $width", 1, title.lineCount)
                    assertTrue("Address must have room at width $width", title.measuredWidth >= width - 100)
                    assertTrue("Description must appear below the address", title.bottom <= subtitle.top)
                    assertTrue("Receiver row too tall at width $width: ${row.measuredHeight}", row.measuredHeight <= 140)
                }
            }
            list.performItemClick(list.adapter.getView(1, null, list), 1, list.adapter.getItemId(1))
            assertEquals(1, chosen)
            assertFalse(dialog.isShowing)
        } finally {
            dialog.dismiss()
            controller.destroy()
        }
    }

    private fun findList(view: View): ListView? {
        if (view is ListView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findList(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
