package pl.jardol.e2stream.desktop

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.IOException

/** Keeps the display awake only while E2Stream is playing video. */
internal class WindowsDisplayAwake(
    private val isWindows: Boolean = Platform.isWindows(),
    private val setExecutionState: (Int) -> Int = ::setWindowsExecutionState,
) {
    private var awake = false

    @Synchronized
    fun setAwake(required: Boolean) {
        if (!isWindows || awake == required) return
        val flags = ES_CONTINUOUS or if (required) ES_DISPLAY_REQUIRED else 0
        val result = try {
            setExecutionState(flags)
        } catch (e: Exception) {
            throw IOException("Windows nie przyjął żądania zasilania.", e)
        } catch (e: LinkageError) {
            throw IOException("Interfejs zasilania Windows jest niedostępny.", e)
        }
        if (result == 0) throw IOException("Windows odrzucił żądanie utrzymania ekranu.")
        awake = required
    }

    private interface ExecutionStateApi : StdCallLibrary {
        fun SetThreadExecutionState(flags: Int): Int

        companion object {
            val INSTANCE: ExecutionStateApi = Native.load(
                "kernel32",
                ExecutionStateApi::class.java,
                W32APIOptions.DEFAULT_OPTIONS,
            )
        }
    }

    internal companion object {
        const val ES_DISPLAY_REQUIRED = 0x00000002
        const val ES_CONTINUOUS = 0x80000000.toInt()

        fun setWindowsExecutionState(flags: Int): Int = ExecutionStateApi.INSTANCE.SetThreadExecutionState(flags)
    }
}
