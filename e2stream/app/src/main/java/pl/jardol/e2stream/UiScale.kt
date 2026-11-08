package pl.jardol.e2stream

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Skalowanie wyglądu okien (ustawienia → Wygląd): czcionki, stałe szerokości/wysokości i wysokość wierszy
 * mnożone przez współczynnik; ikony (ImageView) — osobnym współczynnikiem. Bazowe wartości zapamiętane,
 * więc wielokrotne wywołanie nie powiększa/zmniejsza w kółko.
 */
object UiScale {
    private class Base(val text: Float, val w: Int, val h: Int, val minH: Int)
    private val base = WeakHashMap<View, Base>()

    fun apply(root: View, text: Float, img: Float = text) {
        if (text == 1f && img == 1f && root !in base) return
        walk(root, text, img)
        root.requestLayout()
    }

    private fun walk(v: View, text: Float, img: Float) {
        val lp = v.layoutParams
        val b = base.getOrPut(v) {
            Base(if (v is TextView) v.textSize else 0f, lp?.width ?: 0, lp?.height ?: 0, v.minimumHeight)
        }
        val f = if (v is ImageView) img else text
        if (v is TextView && b.text > 0f) v.setTextSize(TypedValue.COMPLEX_UNIT_PX, b.text * text)
        if (lp != null) {
            if (b.w > 0) lp.width = (b.w * f).toInt()
            if (b.h > 0) lp.height = (b.h * f).toInt().coerceAtLeast(1)
        }
        if (b.minH > 0) v.minimumHeight = (b.minH * text).toInt()
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), text, img)
    }

    fun pct(v: Int) = v.coerceIn(40, 200) / 100f
}
