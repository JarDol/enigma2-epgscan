package pl.jardol.e2stream.desktop

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode

/** Klatka YUV 4:2:0: płaszczyzny Y, U, V (8 bit) w pełnej rozdzielczości źródła. */
internal class YuvFrame(val planes: Array<Bitmap>, val width: Int, val height: Int, val seq: Long)

/** Współczynniki YUV → RGB dla danego standardu barw (zakres ograniczony 16–235). */
internal enum class YuvMatrix(val rv: Float, val gu: Float, val gv: Float, val bu: Float, val label: String) {
    BT601(1.402f, 0.344136f, 0.714136f, 1.772f, "BT.601"),
    BT709(1.5748f, 0.1873f, 0.4681f, 1.8556f, "BT.709"),
    BT2020(1.4746f, 0.16455f, 0.57135f, 1.8814f, "BT.2020"),
    ;

    companion object {
        /** VLC 3 nie podaje standardu barw — jak dekodery TV: SD → BT.601, HD → BT.709, 4K → BT.2020. */
        fun forSize(width: Int, height: Int): YuvMatrix = when {
            width >= VideoFrames.UHD_WIDTH || height > 1200 -> BT2020
            height <= 576 -> BT601
            else -> BT709
        }
    }
}

/**
 * Przeliczanie YUV → RGB i skalowanie na karcie graficznej (shader Skia przy rysowaniu w oknie Compose), jak robi to
 * wyjście Direct3D 11 w zwykłym VLC. Procesor tylko kopiuje płaszczyzny — przy 4K 10-bit to kilkadziesiąt procent mniej.
 * Obraz BT.2020 (4K, zwykle HLG) — przybliżona konwersja barw do BT.709 ekranu.
 */
internal object YuvShader {
    private const val SKSL = """
uniform shader yPlane;
uniform shader uPlane;
uniform shader vPlane;
uniform float2 origin;
uniform float2 scale;
uniform float4 coef;
uniform float wide;

half4 main(float2 p) {
    float2 c = origin + p * scale;
    float y = (yPlane.eval(c).r * 255.0 - 16.0) / 219.0;
    float u = (uPlane.eval(c * 0.5).r * 255.0 - 128.0) / 224.0;
    float v = (vPlane.eval(c * 0.5).r * 255.0 - 128.0) / 224.0;
    float3 rgb = float3(y + coef.x * v, y - coef.y * u - coef.z * v, y + coef.w * u);
    rgb = clamp(rgb, 0.0, 1.0);
    if (wide > 0.5) {
        float3 lin = pow(rgb, float3(2.4));
        lin = float3(
            1.6605 * lin.r - 0.5876 * lin.g - 0.0728 * lin.b,
            -0.1246 * lin.r + 1.1329 * lin.g - 0.0083 * lin.b,
            -0.0182 * lin.r - 0.1006 * lin.g + 1.1187 * lin.b);
        rgb = pow(clamp(lin, 0.0, 1.0), float3(1.0 / 2.4));
    }
    return half4(half3(rgb), 1.0);
}
"""

    private val effect: RuntimeEffect? by lazy {
        runCatching { RuntimeEffect.makeForShader(SKSL) }
            .onFailure { AppLog.i("obraz: shader YUV niedostępny (${it.message}) — klatki RGB z procesora") }
            .getOrNull()
    }

    /** Czy shader się skompilował (inaczej tryb RGB). */
    val available: Boolean get() = effect != null

    /**
     * Rysuje klatkę: źródło [srcX], [srcY], [srcW]×[srcH] (piksele klatki) w prostokącie okna [dstX], [dstY], [dstW]×[dstH].
     * Płaszczyzny kopiowane pod blokadą [lock] (VLC zapisuje następną klatkę w osobnym zestawie).
     */
    fun draw(
        scope: DrawScope,
        lock: Any,
        frame: YuvFrame,
        srcX: Float, srcY: Float, srcW: Float, srcH: Float,
        dstX: Float, dstY: Float, dstW: Float, dstH: Float,
    ) {
        val runtime = effect ?: return
        if (dstW <= 0f || dstH <= 0f) return
        val images = synchronized(lock) { frame.planes.map { Image.makeFromBitmap(it) } }
        val shaders = images.map { it.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null) }
        val builder = RuntimeShaderBuilder(runtime)
        val matrix = YuvMatrix.forSize(frame.width, frame.height)
        try {
            builder.child("yPlane", shaders[0])
            builder.child("uPlane", shaders[1])
            builder.child("vPlane", shaders[2])
            builder.uniform("origin", srcX, srcY)
            builder.uniform("scale", srcW / dstW, srcH / dstH)
            builder.uniform("coef", matrix.rv, matrix.gu, matrix.gv, matrix.bu)
            builder.uniform("wide", if (matrix == YuvMatrix.BT2020) 1f else 0f)
            val shader = builder.makeShader(Matrix33.makeTranslate(dstX, dstY))
            val paint = Paint().apply { this.shader = shader }
            try {
                scope.drawContext.canvas.nativeCanvas.drawRect(Rect.makeXYWH(dstX, dstY, dstW, dstH), paint)
            } finally {
                paint.close()
                shader.close()
            }
        } finally {
            builder.close()
            shaders.forEach { it.close() }
            images.forEach { it.close() }
        }
    }
}
