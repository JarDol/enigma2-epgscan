package pl.jardol.e2stream.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

class AppThemeTest {
    @Test
    fun textRemainsReadableInBothSystemThemes() {
        for (dark in listOf(false, true)) {
            val colors = appColorScheme(dark)
            val pairs = listOf(
                colors.onBackground to colors.background,
                colors.onSurface to colors.surface,
                colors.onSurfaceVariant to colors.surface,
                colors.primary to colors.surface,
                colors.error to colors.background,
                colors.onPrimary to colors.primary,
                colors.onPrimaryContainer to colors.primaryContainer,
            )
            for ((text, background) in pairs) {
                assertTrue(contrast(text, background) >= 4.5, "Insufficient contrast: $text on $background, dark=$dark")
            }
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val light = maxOf(first.luminance(), second.luminance())
        val dark = minOf(first.luminance(), second.luminance())
        return (light + 0.05) / (dark + 0.05)
    }
}
