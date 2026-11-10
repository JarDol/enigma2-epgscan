package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListCarouselTest {
    @Test
    fun wrapsBothDirections() {
        assertEquals(0, carouselTarget(4, 0, 4, 1))
        assertEquals(4, carouselTarget(0, 0, 4, -1))
        assertEquals(2, carouselTarget(5, 2, 5, 1))
        assertEquals(5, carouselTarget(2, 2, 5, -1))
    }

    @Test
    fun doesNotWrapOtherPositionsOrEmptyLists() {
        assertNull(carouselTarget(2, 0, 4, 1))
        assertNull(carouselTarget(0, 0, 4, 1))
        assertNull(carouselTarget(4, 0, 4, -1))
        assertNull(carouselTarget(-1, 0, -1, 1))
        assertNull(carouselTarget(0, 0, 0, -1))
    }
}
