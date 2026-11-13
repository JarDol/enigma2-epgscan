package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun explicitSelectionOverridesSystemLanguages() {
        assertEquals("en", AppLanguage.resolve("en", listOf("pl-PL")))
        assertEquals("pl", AppLanguage.resolve("pl", listOf("en-GB")))
    }

    @Test
    fun systemUsesFirstSupportedLanguageAndFallsBackToEnglish() {
        assertEquals("pl", AppLanguage.resolve("system", listOf("de-DE", "pl-PL", "en-US")))
        assertEquals("en", AppLanguage.resolve("system", listOf("en-GB", "pl-PL")))
        assertEquals("en", AppLanguage.resolve("system", listOf("de-DE")))
        assertEquals("en", AppLanguage.resolve("system", emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidLanguageIsRejected() {
        AppLanguage.resolve("invalid", listOf("pl-PL"))
    }
}
