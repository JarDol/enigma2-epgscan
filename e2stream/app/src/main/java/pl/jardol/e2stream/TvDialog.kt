package pl.jardol.e2stream

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView

/**
 * Własne okno dialogowe na telewizory: tytuł, treść (tekst przewijany albo lista) i rząd przycisków
 * zawsze na dole okna. AlertDialog o stałym rozmiarze na części telewizorów (Philips) ściskał
 * przyciski do kilku pikseli, a przy krótkiej treści rysował je w połowie okna.
 * Okno ma wysokość treści, najwyżej [maxH] ekranu — dłuższa treść się przewija.
 */
object TvDialog {

    class Btn(val label: String, val onClick: () -> Unit = {})

    /** Pionowy układ z górnym limitem wysokości: element z wagą (treść) kurczy się, przyciski zostają. */
    private class CappedColumn(ctx: Context, private val maxPx: Int) : LinearLayout(ctx) {
        init { orientation = VERTICAL }
        override fun onMeasure(w: Int, h: Int) {
            val size = View.MeasureSpec.getSize(h)
            val cap = if (View.MeasureSpec.getMode(h) == View.MeasureSpec.UNSPECIFIED) maxPx else minOf(size, maxPx)
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
        }
    }

    /**
     * Pokazuje okno. [content] buduje treść w kontekście okna (kolory motywu). Przycisk zamyka okno
     * i wywołuje swoją akcję. Zwraca okno (np. do zmiany tytułu).
     */
    fun show(
        a: Activity, title: String?, buttons: List<Btn>,
        widthFrac: Float = 0.7f, maxH: Float = 0.85f, focusFirstButton: Boolean = false,
        content: (ctx: Context, dlg: Dialog) -> View,
    ): Dialog {
        val dlg = Dialog(a, R.style.TvDialog)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val ctx = dlg.context
        val dm = a.resources.displayMetrics
        val dp = dm.density
        fun px(v: Int) = (v * dp).toInt()

        val root = CappedColumn(ctx, (dm.heightPixels * maxH).toInt())
        root.setPadding(px(24), px(18), px(24), px(14))
        if (!title.isNullOrBlank()) {
            root.addView(TextView(ctx).apply {
                text = title
                textSize = 20f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 0, 0, px(10))
                tag = "title"
            })
        }
        val body = content(ctx, dlg)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        var first: Button? = null
        if (buttons.isNotEmpty()) {
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, px(12), 0, 0)
            }
            for (b in buttons) {
                val btn = Button(ctx, null, 0, R.style.TvDialogButton)
                btn.text = b.label
                btn.isFocusable = true
                btn.setOnClickListener { dlg.dismiss(); b.onClick() }
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.marginStart = px(10)
                bar.addView(btn, lp)
                if (first == null) first = btn
            }
            root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dlg.setContentView(root)
        dlg.show()
        dlg.window?.setLayout((dm.widthPixels * widthFrac).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        if (focusFirstButton && first != null) first!!.requestFocus() else body.requestFocus()
        return dlg
    }

    fun setTitle(dlg: Dialog, title: String) {
        (dlg.window?.decorView?.findViewWithTag<TextView>("title"))?.text = title
    }

    /** Długi tekst (np. README) z przyciskiem „Zamknij”. */
    fun text(a: Activity, title: String, text: CharSequence, sizeSp: Float = 17f) {
        show(a, title, listOf(Btn("Zamknij"))) { ctx, _ ->
            ScrollView(ctx).apply {
                isFocusable = true
                addView(TextView(ctx).apply {
                    this.text = text; textSize = sizeSp; setTextColor(0xFFFFFFFF.toInt())
                    setLineSpacing(3 * ctx.resources.displayMetrics.density, 1f)
                })
            }
        }
    }

    /** Lista z polami wyboru; [onSave] dostaje stan po „Zapisz”. */
    fun multi(a: Activity, title: String, items: List<String>, checked: BooleanArray, onSave: (BooleanArray) -> Unit) {
        lateinit var lv: ListView
        show(a, title, listOf(Btn("Anuluj"), Btn("Zapisz") {
            val st = BooleanArray(items.size) { lv.isItemChecked(it) }
            onSave(st)
        })) { ctx, _ ->
            lv = ListView(ctx)
            lv.choiceMode = ListView.CHOICE_MODE_MULTIPLE
            lv.adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_multiple_choice, items)
            for (i in items.indices) lv.setItemChecked(i, checked[i])
            lv
        }
    }

    /** Lista jednokrotnego wyboru: wybór zamyka okno. */
    fun single(a: Activity, title: String, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        show(a, title, emptyList()) { ctx, dlg ->
            val lv = ListView(ctx)
            lv.choiceMode = ListView.CHOICE_MODE_SINGLE
            lv.adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_single_choice, items)
            if (selected >= 0) { lv.setItemChecked(selected, true); lv.setSelection(selected) }
            lv.setOnItemClickListener { _, _, w, _ -> dlg.dismiss(); onPick(w) }
            lv
        }
    }
}
