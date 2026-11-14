package pl.jardol.e2stream.desktop

enum class VideoScaleMode(val label: String) {
    FIT("Dopasuj"),
    CROP("Wypełnij — zachowaj proporcje"),
    STRETCH("Wypełnij — rozciągnij"),
}

internal data class VideoGeometry(val aspect: String?, val crop: String?)

internal fun videoGeometry(mode: VideoScaleMode, width: Int, height: Int): VideoGeometry {
    if (width <= 0 || height <= 0 || mode == VideoScaleMode.FIT) return VideoGeometry(null, null)
    val ratio = "$width:$height"
    return when (mode) {
        VideoScaleMode.FIT -> VideoGeometry(null, null)
        VideoScaleMode.CROP -> VideoGeometry(null, ratio)
        VideoScaleMode.STRETCH -> VideoGeometry(ratio, null)
    }
}

/** Fragment klatki [srcX…] rysowany w prostokącie [dstX…] widoku (piksele). */
internal data class VideoPlacement(
    val srcX: Int, val srcY: Int, val srcW: Int, val srcH: Int,
    val dstX: Float, val dstY: Float, val dstW: Float, val dstH: Float,
)

/**
 * Położenie obrazu w widoku: [aspect] to proporcje wyświetlania (z uwzględnieniem niekwadratowych pikseli SD).
 * Dopasuj — cały obraz z czarnymi pasami; Wypełnij z proporcjami — przycięcie; Rozciągnij — cały widok.
 */
internal fun videoPlacement(mode: VideoScaleMode, viewW: Float, viewH: Float, srcW: Int, srcH: Int, aspect: Float): VideoPlacement {
    val full = VideoPlacement(0, 0, srcW, srcH, 0f, 0f, viewW, viewH)
    if (viewW <= 0f || viewH <= 0f || srcW <= 0 || srcH <= 0 || aspect <= 0f || mode == VideoScaleMode.STRETCH) return full
    val viewAspect = viewW / viewH
    return when (mode) {
        VideoScaleMode.FIT -> if (aspect > viewAspect) {
            val h = viewW / aspect
            VideoPlacement(0, 0, srcW, srcH, 0f, (viewH - h) / 2f, viewW, h)
        } else {
            val w = viewH * aspect
            VideoPlacement(0, 0, srcW, srcH, (viewW - w) / 2f, 0f, w, viewH)
        }
        VideoScaleMode.CROP -> if (aspect > viewAspect) {
            val w = Math.round(srcW * viewAspect / aspect).coerceIn(1, srcW)
            VideoPlacement((srcW - w) / 2, 0, w, srcH, 0f, 0f, viewW, viewH)
        } else {
            val h = Math.round(srcH * aspect / viewAspect).coerceIn(1, srcH)
            VideoPlacement(0, (srcH - h) / 2, srcW, h, 0f, 0f, viewW, viewH)
        }
        VideoScaleMode.STRETCH -> full
    }
}
