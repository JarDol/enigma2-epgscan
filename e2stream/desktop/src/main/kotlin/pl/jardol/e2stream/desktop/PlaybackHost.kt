package pl.jardol.e2stream.desktop

import androidx.compose.runtime.Composable

@Composable
internal fun PlaybackHost(
    settingsVisible: Boolean,
    player: @Composable () -> Unit,
    settings: @Composable () -> Unit,
) {
    player()
    if (settingsVisible) settings()
}
