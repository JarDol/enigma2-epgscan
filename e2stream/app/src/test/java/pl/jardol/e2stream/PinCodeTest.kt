package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Test

class PinCodeTest {
    @Test
    fun pinContainsAtMostSixDigits() {
        assertEquals("123456", normalizePin("123456789"))
    }

    @Test
    fun pinDropsNonDigits() {
        assertEquals("123456", normalizePin("12a3-4 56"))
    }
}
