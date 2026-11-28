package pl.jardol.e2stream

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
