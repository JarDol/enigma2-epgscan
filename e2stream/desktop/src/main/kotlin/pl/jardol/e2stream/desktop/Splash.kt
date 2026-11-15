package pl.jardol.e2stream.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState

internal enum class StageState { RUNNING, DONE, FAILED }

/** Etap uruchamiania pokazywany na ekranie startowym. */
internal data class StartStage(
    val label: String,
    val state: StageState = StageState.RUNNING,
    val ms: Long? = null,
    val detail: String? = null,
)

private val SplashBackground = Color(0xFF101418)
private val LogoScreen = Color(0xFF1E5A8A)
private val LogoAccent = Color(0xFF4FC3F7)

/** Ekran startowy z logo (jak baner aplikacji na Androidzie) — widoczny, dopóki VLC ładuje się w tle. */
@Composable
internal fun ApplicationScope.SplashWindow(stages: List<StartStage>, onClose: () -> Unit) {
    Window(
        onCloseRequest = onClose,
        title = "E2Stream",
        undecorated = true,
        resizable = false,
        state = rememberWindowState(position = WindowPosition(Alignment.Center), size = DpSize(520.dp, 400.dp)),
    ) {
        Box(Modifier.fillMaxSize().background(SplashBackground), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp),
            ) {
                AppLogo(Modifier.size(120.dp, 88.dp))
                Spacer(Modifier.height(16.dp))
                Text("E2Stream", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    System.getProperty("jpackage.app-version")?.let { "wersja $it" } ?: "wersja deweloperska",
                    color = Color(0xFFB0BEC5),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(18.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth(0.8f), color = LogoAccent, trackColor = Color(0xFF263238))
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth(0.9f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    stages.forEach { StageRow(it) }
                }
            }
        }
    }
}

/** Logo: ekran z symbolem odtwarzania na podstawce (te same kształty co ikona aplikacji na Androidzie). */
@Composable
internal fun AppLogo(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width / 140f
        val h = size.height / 102f
        drawRect(LogoScreen, Offset(0f, 0f), Size(140 * w, 84 * h))
        val play = Path().apply {
            moveTo(55 * w, 22 * h)
            lineTo(95 * w, 42 * h)
            lineTo(55 * w, 62 * h)
            close()
        }
        drawPath(play, Color.White)
        drawRect(LogoAccent, Offset(30 * w, 94 * h), Size(80 * w, 8 * h))
    }
}

@Composable
private fun StageRow(stage: StartStage) {
    val (mark, color) = when (stage.state) {
        StageState.RUNNING -> "…" to LogoAccent
        StageState.DONE -> "✓" to Color(0xFF81C784)
        StageState.FAILED -> "✗" to Color(0xFFE57373)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(mark, color = color, fontSize = 14.sp, modifier = Modifier.width(22.dp))
        Text(
            stage.label + (stage.detail?.let { " — $it" } ?: ""),
            color = if (stage.state == StageState.RUNNING) Color.White else Color(0xFFB0BEC5),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        stage.ms?.let { Text(formatStageTime(it), color = Color(0xFF78909C), fontSize = 12.sp) }
    }
}

internal fun formatStageTime(ms: Long): String =
    if (ms < 1000) "$ms ms" else "%.1f s".format(java.util.Locale.US, ms / 1000.0)
