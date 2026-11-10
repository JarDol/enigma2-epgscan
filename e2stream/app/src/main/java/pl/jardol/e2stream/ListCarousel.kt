package pl.jardol.e2stream

internal fun carouselTarget(selected: Int, first: Int, last: Int, direction: Int): Int? = when {
    first < 0 || last <= first -> null
    direction > 0 && selected == last -> first
    direction < 0 && selected == first -> last
    else -> null
}

internal fun pageTarget(selected: Int, count: Int, pageSize: Int, direction: Int): Int? {
    if (selected !in 0 until count || direction == 0) return null
    val step = pageSize.coerceAtLeast(1)
    return if (direction > 0) (selected.toLong() + step).coerceAtMost((count - 1).toLong()).toInt()
    else (selected - step).coerceAtLeast(0)
}
