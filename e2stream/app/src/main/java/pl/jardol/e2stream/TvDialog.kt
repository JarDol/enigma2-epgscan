package pl.jardol.e2stream

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Okna w stylu listy kanałów: ciemny panel, niebieski tytuł, podpowiedzi klawiszy po prawej,
 * niebieskie podświetlenie wiersza. Zamiast systemowych przycisków: wybór OK, zamknięcie WSTECZ;
 * akcje (gdy potrzebne) jako rząd przycisków przypiętych na dole panelu.
 * Panel ma wysokość treści, najwyżej [maxH] ekranu — dłuższa treść się przewija.
 */
object TvDialog {

    const val BLUE = 0xFF4FC3F7.toInt()
    const val GREY = 0xFFB0BEC5.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    class Btn(val label: String, val onClick: () -> Unit = {})

    private class TimedDialog(a: Activity, private val timeoutSec: Int) : Dialog(a, R.style.TvDialog) {
        private val ui = Handler(Looper.getMainLooper())
        private val timer = IdleTimeout(
            { timeoutSec }, { task, delay -> ui.postDelayed(task, delay) }, { ui.removeCallbacks(it) },
        ) { dismiss() }

        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            timer.setActive(hasFocus)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) timer.reset()
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) timer.reset()
            return super.dispatchTouchEvent(event)
        }

        override fun dismiss() {
            timer.setActive(false)
            super.dismiss()
        }
    }

    /** Otwarte okna — zamykane przy wyjściu z aplikacji (po powrocie zawsze sam odtwarzacz). */
    private val open = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Dialog, Boolean>())

    fun dismissAll() {
        for (d in open.toList()) try { if (d.isShowing) d.dismiss() } catch (_: Exception) {}
        open.clear()
    }

    /** Wiersz listy: [mark] 0 — brak, 1 — zaznaczone ☑, 2 — niezaznaczone ☐, 3 — bieżący wybór ●. */
    class Item(val title: String, val sub: String = "", var mark: Int = 0, val header: Boolean = false)

    /** Pionowy układ z górnym limitem wysokości: element z wagą (treść) kurczy się, przyciski zostają. */
    private class CappedColumn(ctx: Context, private val maxPx: Int) : LinearLayout(ctx) {
        init { orientation = VERTICAL }
        override fun onMeasure(w: Int, h: Int) {
            val size = View.MeasureSpec.getSize(h)
            val cap = if (View.MeasureSpec.getMode(h) == View.MeasureSpec.UNSPECIFIED) maxPx else minOf(size, maxPx)
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
        }
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /**
     * Panel: tytuł i podpowiedź u góry, treść z [content], opcjonalnie przyciski na dole.
     * [onBack] — WSTECZ (domyślnie tylko zamknięcie); przy [cancelable] = false WSTECZ nic nie robi.
     */
    fun panel(
        a: Activity, title: String?, hint: String?, chips: List<Btn> = emptyList(),
        widthFrac: Float = 0.6f, maxH: Float = 0.85f, cancelable: Boolean = true,
        onBack: (() -> Unit)? = null, focusChip: Boolean = false, top: Boolean = false, timeoutSec: Int = 0,
        hintBelowTitle: Boolean = false,
        content: (ctx: Context, dlg: Dialog) -> View,
    ): Dialog {
        val dlg = if (timeoutSec > 0) TimedDialog(a, timeoutSec) else Dialog(a, R.style.TvDialog)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val ctx = dlg.context
        val dm = a.resources.displayMetrics

        val root = CappedColumn(ctx, (dm.heightPixels * maxH).toInt())
        root.background = GradientDrawable().apply {
            setColor(0xF2161B20.toInt()); cornerRadius = dp(ctx, 10).toFloat()
        }
        root.setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14))

        if (!title.isNullOrBlank() || !hint.isNullOrBlank()) {
            val head = LinearLayout(ctx).apply {
                orientation = if (hintBelowTitle) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = if (hintBelowTitle) Gravity.START else Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, 10))
            }
            head.addView(TextView(ctx).apply {
                text = title ?: ""; textSize = 21f; setTextColor(BLUE); tag = "title"
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }, if (hintBelowTitle) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
               else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!hint.isNullOrBlank()) head.addView(TextView(ctx).apply {
                text = hint; textSize = 13f; setTextColor(GREY); gravity = if (hintBelowTitle) Gravity.START else Gravity.END
                setPadding(if (hintBelowTitle) 0 else dp(ctx, 16), if (hintBelowTitle) dp(ctx, 4) else 0, 0, 0); tag = "hint"
            })
            root.addView(head)
        }
        val body = content(ctx, dlg)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        var first: Button? = null
        if (chips.isNotEmpty()) {
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(ctx, 12), 0, 0)
            }
            for ((index, b) in chips.withIndex()) {
                val btn = Button(ctx, null, 0, R.style.TvDialogButton)
                btn.text = b.label
                btn.tag = "chip_$index"
                btn.isFocusable = true
                btn.setOnClickListener { dlg.dismiss(); b.onClick() }
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.marginStart = dp(ctx, 10)
                bar.addView(btn, lp)
                if (first == null) first = btn
            }
            root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dlg.setContentView(root)
        dlg.setCancelable(cancelable)
        dlg.setCanceledOnTouchOutside(false)
        if (onBack != null) dlg.setOnCancelListener { onBack() }
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        open += dlg
        dlg.setOnDismissListener { open -= dlg }
        dlg.show()
        dlg.window?.setLayout((dm.widthPixels * widthFrac).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        if (top) dlg.window?.let { w ->
            // u góry ekranu — klawiatura ekranowa (dół ekranu) nie zasłania pola ani przycisków
            val lp = w.attributes
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = (dm.heightPixels * 0.06).toInt()
            w.attributes = lp
        }
        val f = first
        if (focusChip && f != null) f.requestFocus() else body.requestFocus()
        return dlg
    }

    fun setTitle(dlg: Dialog, title: String) {
        dlg.window?.decorView?.findViewWithTag<TextView>("title")?.text = title
    }

    fun setButtonLabel(dlg: Dialog?, index: Int, label: String) {
        if (dlg?.isShowing != true) return
        dlg.window?.decorView?.findViewWithTag<Button>("chip_$index")?.text = label
    }

    // ------------------------------------------------------------ listy

    private class RowAdapter(val ctx: Context, val items: List<Item>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(ctx, 52)
                setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 16), dp(ctx, 6))
                addView(TextView(ctx).apply { textSize = 20f; tag = "mark" },
                    LinearLayout.LayoutParams(dp(ctx, 36), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(TextView(ctx).apply { textSize = 19f; setTextColor(WHITE); tag = "t" },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(ctx).apply { textSize = 14f; setTextColor(GREY); tag = "s"; setPadding(dp(ctx, 12), 0, 0, 0) })
            }
            val it = items[position]
            row.minimumHeight = dp(ctx, if (it.header) 40 else 52)
            val mark = row.findViewWithTag<TextView>("mark")
            mark.visibility = if (!it.header && items.any { i -> i.mark != 0 }) View.VISIBLE else View.GONE
            mark.text = when (it.mark) { 1 -> "☑"; 2 -> "☐"; 3 -> "●"; else -> "" }
            mark.setTextColor(if (it.mark == 2) 0xFF78909C.toInt() else BLUE)
            val t = row.findViewWithTag<TextView>("t")
            t.text = it.title
            t.textSize = if (it.header) 15f else 19f
            t.setTextColor(if (it.header) BLUE else if (it.mark == 2) GREY else WHITE)
            t.setTypeface(null, if (it.header) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            val s = row.findViewWithTag<TextView>("s")
            s.text = it.sub
            s.visibility = if (it.header || it.sub.isEmpty()) View.GONE else View.VISIBLE
            return row
        }
    }

    private fun listView(ctx: Context, items: List<Item>, selected: Int): ListView {
        val lv = ListView(ctx)
        lv.adapter = RowAdapter(ctx, items)
        lv.selector = ctx.getDrawable(R.drawable.list_selector)
        lv.divider = null
        lv.isVerticalScrollBarEnabled = true
        lv.itemsCanFocus = false
        if (selected in items.indices) lv.setSelection(selected)
        return lv
    }

    fun enableCarousel(list: ListView, pageKeys: Boolean = false, bounds: () -> Pair<Int, Int> = { 0 to (list.count - 1) }) {
        list.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (pageKeys && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                val visible = (0 until list.childCount).count { i ->
                    val child = list.getChildAt(i)
                    child.top >= list.paddingTop && child.bottom <= list.height - list.paddingBottom
                }
                val direction = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                val target = pageTarget(list.selectedItemPosition, list.count, visible, direction)
                if (target != null && target != list.selectedItemPosition) {
                    val selectedView = list.getChildAt(list.selectedItemPosition - list.firstVisiblePosition)
                    val offset = selectedView?.top?.minus(list.paddingTop) ?: 0
                    list.setSelectionFromTop(target, offset)
                }
                return@setOnKeyListener true
            }
            val direction = when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_UP -> -1
                else -> return@setOnKeyListener false
            }
            val (first, last) = bounds()
            val target = carouselTarget(list.selectedItemPosition, first, last, direction)
            if (target == null) false else { list.setSelection(target); true }
        }
    }

    /** Lista: OK wybiera i zamyka okno. */
    fun list(
        a: Activity, title: String, items: List<Item>, selected: Int = -1,
        hint: String = "OK wybierz  •  WSTECZ zamknij", widthFrac: Float = 0.55f,
        onCancel: () -> Unit = {}, wrap: Boolean = false, onPick: (Int) -> Unit,
    ): Dialog = panel(a, title, hint, widthFrac = widthFrac, onBack = onCancel) { ctx, dlg ->
        listView(ctx, items, selected).apply {
            if (wrap) enableCarousel(this) { items.indexOfFirst { !it.header } to items.indexOfLast { !it.header } }
            setOnItemClickListener { _, _, w, _ ->
                if (!items[w].header) { dlg.dismiss(); onPick(w) }
            }
        }
    }

    /** Lista jednokrotnego wyboru z oznaczonym bieżącym wyborem. */
    fun single(a: Activity, title: String, labels: List<String>, selected: Int, onCancel: () -> Unit = {}, onPick: (Int) -> Unit) =
        list(a, title, labels.mapIndexed { i, l -> Item(l, mark = if (i == selected) 3 else 0) }, selected, onCancel = onCancel, onPick = onPick)

    /** Lista z polami wyboru: OK przełącza, WSTECZ zapisuje i zamyka. */
    fun multi(a: Activity, title: String, labels: List<String>, checked: BooleanArray, subs: List<String>? = null,
              onSave: (BooleanArray) -> Unit) {
        val items = labels.mapIndexed { i, l -> Item(l, subs?.getOrNull(i) ?: "", if (checked[i]) 1 else 2) }
        panel(a, title, "OK zaznacz / odznacz  •  WSTECZ zapisz i wyjdź", widthFrac = 0.55f,
            onBack = { onSave(BooleanArray(items.size) { items[it].mark == 1 }) }) { ctx, _ ->
            listView(ctx, items, 0).apply {
                setOnItemClickListener { _, _, w, _ ->
                    items[w].mark = if (items[w].mark == 1) 2 else 1
                    (adapter as BaseAdapter).notifyDataSetChanged()
                }
            }
        }
    }

    // ------------------------------------------------------------ tekst

    /** Długi tekst (README, opisy): przewijanie ▲▼, pasek położenia, WSTECZ zamyka. */
    fun text(a: Activity, title: String, text: CharSequence, sizeSp: Float = 17f) {
        panel(a, title, "▲ ▼ przewijanie  •  WSTECZ zamknij", widthFrac = 0.75f) { ctx, _ ->
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val pos = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progressDrawable = ctx.getDrawable(R.drawable.progress_epg)
            }
            val sv = scrollText(ctx, text, sizeSp) { p -> pos.progress = p }
            col.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            col.addView(pos, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 4)).apply { topMargin = dp(ctx, 10) })
            col.isFocusable = false
            sv.post { sv.requestFocus() }
            col
        }
    }

    /** Przewijany tekst sterowany pilotem (▲▼ o ~2/3 ekranu tekstu); [onPos] — położenie 0…1000. */
    fun scrollText(ctx: Context, text: CharSequence, sizeSp: Float, color: Int = 0xFFECEFF1.toInt(), onPos: (Int) -> Unit = {}): ScrollView {
        val sv = object : ScrollView(ctx) {
            override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) {
                super.onScrollChanged(l, t, ol, ot)
                val range = (getChildAt(0)?.height ?: 0) - height
                onPos(if (range <= 0) 1000 else (t * 1000 / range))
            }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                val range = (getChildAt(0)?.height ?: 0) - height
                onPos(if (range <= 0) 1000 else (scrollY * 1000 / range))
            }
        }
        sv.isFocusable = true
        sv.isFocusableInTouchMode = false
        sv.isVerticalScrollBarEnabled = false
        sv.addView(TextView(ctx).apply {
            this.text = text; textSize = sizeSp; setTextColor(color)
            setLineSpacing(dp(ctx, 4).toFloat(), 1f)
            setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0)
        })
        sv.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val step = (sv.height * 2) / 3
            val child = sv.getChildAt(0)?.height ?: 0
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (sv.scrollY + sv.height >= child) false      // koniec tekstu — fokus dalej (przyciski)
                    else { sv.smoothScrollBy(0, step); true }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (sv.scrollY <= 0) false else { sv.smoothScrollBy(0, -step); true }
                }
                else -> false
            }
        }
        return sv
    }

    /** Komunikat z akcjami na dole (np. aktualizacja, awaria). */
    fun confirm(a: Activity, title: String, message: CharSequence, chips: List<Btn>, cancelable: Boolean = true, onBack: (() -> Unit)? = null) {
        panel(a, title, null, chips, widthFrac = 0.6f, cancelable = cancelable, onBack = onBack, focusChip = true) { ctx, _ ->
            scrollText(ctx, message, 18f)
        }
    }

    /** Pole tekstowe u góry ekranu (nad klawiaturą ekranową) z przyciskami Zapisz / Anuluj. */
    fun input(a: Activity, title: String, hint: String, et: android.widget.EditText, saveLabel: String = "Zapisz", onSave: () -> Unit) {
        var saved = false
        lateinit var dlg: Dialog
        fun save() { if (!saved) { saved = true; dlg.dismiss(); onSave() } }
        dlg = panel(a, title, "OK — klawiatura  •  WSTECZ anuluj", listOf(Btn(saveLabel) { save() }, Btn("Anuluj")),
            widthFrac = 0.6f, top = true) { ctx, _ ->
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            if (hint.isNotBlank()) col.addView(TextView(ctx).apply {
                text = hint; textSize = 14f; setTextColor(GREY); setPadding(dp(ctx, 4), 0, 0, dp(ctx, 6))
            })
            et.setTextColor(WHITE)
            et.setHintTextColor(0xFF78909C.toInt())
            col.addView(et)
            col
        }
        et.setOnEditorActionListener { _, _, _ -> save(); true }
        et.requestFocus()
    }
}
