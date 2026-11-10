package pl.jardol.e2stream

internal fun carouselTarget(selected: Int, first: Int, last: Int, direction: Int): Int? = when {
    first < 0 || last <= first -> null
    direction > 0 && selected == last -> first
    direction < 0 && selected == first -> last
    else -> null
}
