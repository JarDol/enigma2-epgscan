package pl.jardol.e2stream

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Wspólny wygląd okna audycji: ikona, kanał, tytuł, czas, opis. Używany przez EPG i okna harmonogramu. */
object EventCard {
    private val pl = Locale("pl", "PL")
    private val hm = SimpleDateFormat("HH:mm", pl)
    private val day = SimpleDateFormat("EEE dd.MM", pl)

    /** [body] — opis; null = bez miejsca na opis (np. brak EPG w pamięci). */
    fun content(ctx: Context, ev: EpgEvent, ref: String, name: String, body: String?): View {
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val prefs = Prefs(ctx)
        val scale = UiScale.pct(prefs.uiEpgFont)
        val n = System.currentTimeMillis() / 1000
        val running = ev.begin <= n && ev.end > n
        val pct = if (running && ev.duration > 0) ((n - ev.begin) * 1000 / ev.duration).toInt() else -1
        val whenText = "${day.format(Date(ev.begin * 1000))}  ${hm.format(Date(ev.begin * 1000))} – ${hm.format(Date(ev.end * 1000))}" +
            "  (${ev.duration / 60} min)" + if (running) "  •  zostało ${(ev.end - n) / 60} min" else ""

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            Picons.show(this, ctx, ref, prefs.picons, prefs.missingPiconLogo)
        }, LinearLayout.LayoutParams(px(96), px(56)).apply { marginEnd = px(16) })
        val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (name.isNotBlank()) info.addView(TextView(ctx).apply {
            text = name; textSize = 15f * scale; setTextColor(TvDialog.GREY)
        })
        info.addView(TextView(ctx).apply {
            text = ev.title; textSize = 23f * scale; setTextColor(TvDialog.WHITE)
        })
        val tl = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tl.addView(TextView(ctx).apply { text = whenText; textSize = 15f * scale; setTextColor(TvDialog.BLUE) })
        if (pct >= 0) tl.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = pct.coerceIn(0, 1000); progressDrawable = ctx.getDrawable(R.drawable.progress_epg)
        }, LinearLayout.LayoutParams(0, px(5), 1f).apply { marginStart = px(16) })
        info.addView(tl)
        head.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(head)
        if (body != null) {
            col.addView(View(ctx).apply { setBackgroundColor(0xFF37474F.toInt()) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply {
                    topMargin = px(12); bottomMargin = px(12) })
            col.addView(TvDialog.scrollText(ctx, body, 19f * scale),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return col
    }

    /** Opis audycji z zapisanego EPG (bez sieci); null, gdy brak. */
    fun cachedBody(ctx: Context, timer: ZapTimers.Timer): String? =
        body(eventOf(timer, ctx)).takeIf { it.isNotBlank() }

    fun body(ev: EpgEvent): String = buildString {
        if (ev.shortDesc.isNotBlank()) append(ev.shortDesc)
        if (ev.longDesc.isNotBlank() && ev.longDesc != ev.shortDesc) {
            if (isNotEmpty()) append("\n\n")
            append(ev.longDesc)
        }
    }

    fun eventOf(timer: ZapTimers.Timer, ctx: Context): EpgEvent {
        val events = try { EpgStore(ctx).read(timer.ref)?.second } catch (_: Exception) { null }
        return events?.firstOrNull { timer.eventId != 0 && it.id == timer.eventId }
            ?: events?.firstOrNull { it.begin == timer.begin }
            ?: EpgEvent(timer.eventId, timer.begin, (timer.end - timer.begin).coerceAtLeast(0),
                timer.title, "", "", timer.ref, timer.channel)
    }
}
