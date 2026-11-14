package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone
import java.util.Locale

class EpgTimeFormatTest {
    private val format = EpgTimeFormat(TimeZone.getTimeZone("UTC"))

    @Test
    fun sameDayShowsDateAndBothTimes() {
        assertEquals("wt. 10.11\n20:20–21:00", format.dayAndRange(1794342000, 1794344400))
        assertEquals("20:20–21:00", format.range(1794342000, 1794344400))
    }

    @Test
    fun crossingMidnightShowsEndDay() {
        assertEquals("23:30–śr. 11.11 00:05", format.range(1794353400, 1794355500))
        assertEquals("wt. 10.11\n23:30–00:05\ndo śr. 11.11", format.dayAndRange(1794353400, 1794355500))
    }

    @Test
    fun englishLocaleAppliesToDayNamesAndCrossDayLabel() {
        val english = EpgTimeFormat(TimeZone.getTimeZone("UTC"), Locale.ENGLISH, "until")
        assertEquals("Tue 10.11\n23:30–00:05\nuntil Wed 11.11", english.dayAndRange(1794353400, 1794355500))
    }
}
