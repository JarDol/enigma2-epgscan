package pl.jardol.e2stream

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.io.ByteArrayInputStream
import java.nio.ByteOrder

/**
 * Programowy dekoder MPEG-1/2 Layer I/II (MP2) — dźwięk większości polskich kanałów z satelity.
 * Część urządzeń (np. Xiaomi Mi Box 4) nie ma sprzętowego dekodera MP2 i wtedy kanał gra bez dźwięku.
 * Renderer jest dodawany ZA dekoderami systemowymi, więc działa tylko tam, gdzie systemowego brak.
 * Obsługuje tunelowanie (DecoderAudioRenderer), wyjście PCM 16 bit. Dekodowanie: JLayer (LGPL).
 */
class Mp2Decoder(format: Format) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>(
        arrayOfNulls<DecoderInputBuffer>(16) as Array<DecoderInputBuffer>,
        arrayOfNulls<SimpleDecoderOutputBuffer>(16) as Array<SimpleDecoderOutputBuffer>,
    ) {

    private var dec = javazoom.jl.decoder.Decoder()
    @Volatile var channels = format.channelCount.takeIf { it > 0 } ?: 2
        private set
    @Volatile var sampleRate = format.sampleRate.takeIf { it > 0 } ?: 48000
        private set
    private var buf = ByteArray(4096)

    init {
        setInitialInputBufferSize(if (format.maxInputSize > 0) format.maxInputSize else 4096)
    }

    override fun getName() = "e2stream-mp2"

    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer() = SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }

    override fun createUnexpectedDecodeException(error: Throwable) = DecoderException("MP2: nieoczekiwany błąd", error)

    override fun decode(input: DecoderInputBuffer, output: SimpleDecoderOutputBuffer, reset: Boolean): DecoderException? {
        if (reset) dec = javazoom.jl.decoder.Decoder()
        val data = input.data ?: run { output.shouldBeSkipped = true; return null }
        val n = data.remaining()
        if (buf.size < n) buf = ByteArray(n)
        data.get(buf, 0, n)
        return try {
            val bs = javazoom.jl.decoder.Bitstream(ByteArrayInputStream(buf, 0, n))
            var outBytes: java.nio.ByteBuffer? = null
            while (true) {
                val h = bs.readFrame() ?: break
                val sb = dec.decodeFrame(h, bs) as javazoom.jl.decoder.SampleBuffer
                bs.closeFrame()
                channels = sb.channelCount
                sampleRate = sb.sampleFrequency
                val len = sb.bufferLength
                val pcm = sb.buffer
                val ob = outBytes?.let { prev ->
                    // kilka ramek w jednej próbce (rzadkie) — powiększ bufor
                    val bigger = output.grow(prev.position() + len * 2)
                    bigger
                } ?: output.init(input.timeUs, len * 2)
                ob.order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until len) ob.putShort(pcm[i])
                outBytes = ob
            }
            val ob = outBytes
            if (ob == null) output.shouldBeSkipped = true else ob.flip()
            null
        } catch (e: Exception) {
            // uszkodzona ramka — jak MediaCodec: pomijamy bez przerywania odtwarzania
            output.shouldBeSkipped = true
            null
        }
    }
}

class Mp2AudioRenderer(handler: Handler?, listener: AudioRendererEventListener?, sink: AudioSink) :
    DecoderAudioRenderer<Mp2Decoder>(handler, listener, sink) {

    override fun getName() = "Mp2AudioRenderer"

    override fun supportsFormatInternal(format: Format): Int {
        val mime = format.sampleMimeType ?: return C.FORMAT_UNSUPPORTED_TYPE
        if (mime != MimeTypes.AUDIO_MPEG_L2 && mime != MimeTypes.AUDIO_MPEG_L1) return C.FORMAT_UNSUPPORTED_SUBTYPE
        val ch = format.channelCount.takeIf { it > 0 } ?: 2
        val sr = format.sampleRate.takeIf { it > 0 } ?: 48000
        if (!sinkSupportsFormat(Util.getPcmFormat(C.ENCODING_PCM_16BIT, ch, sr))) return C.FORMAT_UNSUPPORTED_SUBTYPE
        return C.FORMAT_HANDLED
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?) = Mp2Decoder(format)

    override fun getOutputFormat(decoder: Mp2Decoder): Format =
        Util.getPcmFormat(C.ENCODING_PCM_16BIT, decoder.channels, decoder.sampleRate)
}

/** Fabryka rendererów: systemowe + programowy MP2 na końcu (gdy systemowego dekodera MP2 brak). */
class E2RenderersFactory(ctx: Context) : DefaultRenderersFactory(ctx) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out)
        out.add(Mp2AudioRenderer(eventHandler, eventListener, audioSink))
    }
}
