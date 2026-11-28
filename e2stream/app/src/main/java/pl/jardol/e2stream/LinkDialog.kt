package pl.jardol.e2stream

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

internal object LinkDialog {
    fun bitmap(url: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }

    fun show(activity: Activity, title: String, message: String, url: String, onCancel: () -> Unit): Dialog =
        TvDialog.panel(activity, title, null,
            chips = listOf(TvDialog.Btn(activity.getString(R.string.dialog_cancel)) { onCancel() }),
            onBack = onCancel, focusChip = true) { context, _ ->
            val density = context.resources.displayMetrics.density
            val size = (200 * density).toInt().coerceAtMost((activity.resources.displayMetrics.heightPixels * 0.35f).toInt())
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(TextView(context).apply {
                    text = message
                    textSize = 17f
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(ImageView(context).apply {
                    setImageBitmap(bitmap(url, size))
                    contentDescription = url
                }, LinearLayout.LayoutParams(size, size).apply { topMargin = (12 * density).toInt() })
            }
        }
}
