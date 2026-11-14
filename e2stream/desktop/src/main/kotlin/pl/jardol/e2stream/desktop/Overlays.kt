package pl.jardol.e2stream.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Okno wewnątrz okna aplikacji (warstwa nad obrazem): rozmiar [width] × [height], ale nie większy niż
 * okno aplikacji — skaluje się razem z nim. Kliknięcie obok zamyka (gdy [scrim]).
 */
@Composable
internal fun InWindowDialog(
    width: Dp,
    height: Dp,
    onDismiss: () -> Unit,
    alignment: Alignment = Alignment.Center,
    scrim: Boolean = true,
    margin: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val background = if (scrim) {
        Modifier.background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { onDismiss() } }
    } else Modifier
    BoxWithConstraints(Modifier.fillMaxSize().then(background)) {
        val w = minOf(width, maxWidth * 0.96f)
        val h = minOf(height, maxHeight * 0.94f)
        Box(
            Modifier.align(alignment).padding(margin).size(w, h).clip(RoundedCornerShape(16.dp))
                .pointerInput(Unit) { detectTapGestures { } },
        ) { content() }
    }
}
