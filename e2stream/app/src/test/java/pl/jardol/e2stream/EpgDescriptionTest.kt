package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Test

class EpgDescriptionTest {
    @Test
    fun prefersShortDescriptionAndFallsBackToLongDescription() {
        assertEquals("Krótki opis", epgDescriptionPreview("Krótki opis", "Długi opis"))
        assertEquals("program informacyjny Prowadzący", epgDescriptionPreview(" ", "\nprogram informacyjny\nProwadzący"))
        assertEquals("", epgDescriptionPreview("", "  "))
    }
}
