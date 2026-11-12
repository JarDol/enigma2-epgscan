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
