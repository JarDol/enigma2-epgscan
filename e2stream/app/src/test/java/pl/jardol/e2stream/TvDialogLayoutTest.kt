package pl.jardol.e2stream

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvDialogLayoutTest {
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
