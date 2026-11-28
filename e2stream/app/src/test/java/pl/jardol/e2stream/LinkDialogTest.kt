package pl.jardol.e2stream

import android.app.Activity
import android.app.Application
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.Button
import android.widget.ImageView
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LinkDialogTest {
    @Test fun qrContainsFullLink() {
        val url = "http://192.168.1.50:45678/1234567890abcdef"
        val bitmap = LinkDialog.bitmap(url, 240)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        assertEquals(url, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
    }

    @Test
    @Config(application = Application::class, qualifiers = "w640dp-h360dp-land-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun longInstructionsDoNotClipQrOrCancelButton() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val url = "http://192.168.1.50:45678/1234567890abcdef"
        val dialog = LinkDialog.show(controller.get(), "Android TV Remote",
            ("Long instructions for pairing and downloading files. $url\n").repeat(12), url) {}
        try {
            val qr = dialog.window!!.decorView.findViewWithTag<ImageView>("link_qr")
            val button = dialog.window!!.decorView.findViewWithTag<Button>("chip_0")
            val body = qr.parent as View
            val root = body.parent as View
            root.measure(View.MeasureSpec.makeMeasureSpec(512, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.AT_MOST))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            assertTrue(qr.width > 0)
            assertEquals(qr.width, qr.height)
            assertTrue(qr.left >= 0 && qr.right <= body.width && qr.top >= 0 && qr.bottom <= body.height)
            assertTrue(body.top >= 0 && body.bottom <= root.height)
            val bar = button.parent as View
            assertTrue(bar.bottom <= root.height && button.bottom <= bar.height)
            val bitmap = (qr.drawable as BitmapDrawable).bitmap
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            assertEquals(url, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
        } finally {
            dialog.dismiss()
            controller.destroy()
        }
    }
}
