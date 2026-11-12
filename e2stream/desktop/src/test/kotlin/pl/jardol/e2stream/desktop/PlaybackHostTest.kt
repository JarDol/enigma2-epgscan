package pl.jardol.e2stream.desktop

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackHostTest {
    @Test
    fun keepsPlayerMountedWhileSettingsOpenAndClose() {
        val settingsVisible = mutableStateOf(false)
        var playerMounts = 0
        var playerDisposals = 0
        var settingsMounts = 0
        var settingsDisposals = 0
        val applier = object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = ControlledComposition(applier, recomposer)
        try {
            composition.setContent {
                PlaybackHost(
                    settingsVisible = settingsVisible.value,
                    player = {
                        DisposableEffect(Unit) {
                            playerMounts++
                            onDispose { playerDisposals++ }
                        }
                    },
                    settings = {
                        DisposableEffect(Unit) {
                            settingsMounts++
                            onDispose { settingsDisposals++ }
                        }
                    },
                )
            }
            assertEquals(1, playerMounts)
            for (visible in listOf(true, false)) {
                settingsVisible.value = visible
                composition.invalidateAll()
                if (composition.recompose()) composition.applyChanges()
            }
            assertEquals(1, playerMounts)
            assertEquals(0, playerDisposals)
            assertEquals(1, settingsMounts)
            assertEquals(1, settingsDisposals)
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
        assertEquals(1, playerDisposals)
    }
}
