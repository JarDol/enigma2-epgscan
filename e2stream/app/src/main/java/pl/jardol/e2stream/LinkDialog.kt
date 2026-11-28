package pl.jardol.e2stream

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
            widthFrac = 0.8f, onBack = onCancel, focusChip = true) { context, _ ->
            val density = context.resources.displayMetrics.density
            val maxSize = (200 * density).toInt().coerceAtMost((activity.resources.displayMetrics.heightPixels * 0.35f).toInt())
            val image = ImageView(context).apply {
                tag = "link_qr"
                contentDescription = url
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            object : LinearLayout(context) {
                private var lastSize = 0
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val availableWidth = View.MeasureSpec.getSize(widthMeasureSpec)
                    val heightMode = View.MeasureSpec.getMode(heightMeasureSpec)
                    val availableHeight = if (heightMode == View.MeasureSpec.UNSPECIFIED) maxSize
                        else View.MeasureSpec.getSize(heightMeasureSpec)
                    val size = minOf(maxSize, availableHeight, (availableWidth * 0.38f).toInt()).coerceAtLeast(0)
                    if (size != lastSize && size > 0) {
                        image.setImageBitmap(bitmap(url, size))
                        lastSize = size
                    }
                    image.layoutParams = (image.layoutParams as LayoutParams).apply { width = size; height = size }
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                }
            }.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val text = TextView(context).apply {
                    text = message
                    textSize = 17f
                    setTextColor(Color.WHITE)
                }
                addView(ScrollView(context).apply {
                    tag = "link_message"
                    isFocusable = true
                    addView(text)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(image, LinearLayout.LayoutParams(maxSize, maxSize).apply {
                    marginStart = (12 * density).toInt()
                })
            }
        }
}
