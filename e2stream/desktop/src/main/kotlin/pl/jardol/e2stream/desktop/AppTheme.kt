package pl.jardol.e2stream.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal fun appColorScheme(dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = Color(0xFF4FC3F7),
            onPrimary = Color(0xFF003544),
            primaryContainer = Color(0xFF00516A),
            onPrimaryContainer = Color(0xFFC5EFFF),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF006684),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFBDE9FF),
            onPrimaryContainer = Color(0xFF001F29),
        )
    }
