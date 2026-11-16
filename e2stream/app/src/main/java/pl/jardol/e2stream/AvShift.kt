package pl.jardol.e2stream

import androidx.media3.common.C
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.TrackOutput

/**
 * Przesunięcie dźwięku względem obrazu (ustawienie „Przesunięcie dźwięku”, −1000…+1000 ms, krok 50 ms).
 * ExoPlayer: czasy próbek dźwięku z ekstraktora przesuwane o [ms] (działa także przy tunelowaniu — telewizor
 * dostaje już przesunięte znaczniki); VLC: opóźnienie dźwięku odtwarzacza. Ujemne — dźwięk wcześniej
 * (gdy obraz wyprzedza dźwięk), dodatnie — dźwięk później. Nic nie jest zapisywane do bufora ani nagrań.
 */
object AvShift {
    @Volatile var ms: Int = 0

    const val MIN = -1000
    const val MAX = 1000
    const val STEP = 50

    val values: List<Int> = (MIN..MAX step STEP).toList()

    /** Fabryka ekstraktorów z przesunięciem dźwięku. */
    fun wrap(base: ExtractorsFactory): ExtractorsFactory =
        ExtractorsFactory { base.createExtractors().map { Shift(it) }.toTypedArray() }

    private class Shift(private val inner: Extractor) : Extractor by inner {
        override fun init(output: ExtractorOutput) = inner.init(Output(output))
    }

    private class Output(private val o: ExtractorOutput) : ExtractorOutput by o {
        override fun track(id: Int, type: Int): TrackOutput =
            o.track(id, type).let { if (type == C.TRACK_TYPE_AUDIO) Track(it) else it }
    }

    private class Track(private val t: TrackOutput) : TrackOutput by t {
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) =
            t.sampleMetadata(if (ms != 0 && timeUs != C.TIME_UNSET) timeUs + ms * 1000L else timeUs, flags, size, offset, cryptoData)
    }
}

/** „0 ms”, „−200 ms — dźwięk wcześniej”, „+200 ms — dźwięk później”. */
fun avShiftLabel(ctx: android.content.Context, v: Int): String = when {
    v == 0 -> ctx.getString(R.string.av_shift_zero)
    v < 0 -> ctx.getString(R.string.av_shift_earlier, "−${-v}")
    else -> ctx.getString(R.string.av_shift_later, "+$v")
}
