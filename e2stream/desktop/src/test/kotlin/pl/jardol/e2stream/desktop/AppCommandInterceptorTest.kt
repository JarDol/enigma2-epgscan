package pl.jardol.e2stream.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppCommandInterceptorTest {
    @Test
    fun onlyCapturesEnabledOemVolumeCommandsInForeground() {
        fun message(command: Int, device: Int = 0x1000) = ((command or device).toLong() shl 16)
        assertTrue(shouldInterceptAppCommand(message(10), setOf(10), true, false, false))
        assertTrue(shouldInterceptAppCommand(message(9), emptySet(), true, true, false))
        assertFalse(shouldInterceptAppCommand(message(9), setOf(10), true, false, false))
        assertFalse(shouldInterceptAppCommand(message(10), setOf(10), false, false, false))
        assertFalse(shouldInterceptAppCommand(message(10, 0), setOf(10), true, false, false))
        assertTrue(shouldInterceptAppCommand(message(7, 0), emptySet(), true, false, true))
        assertTrue(shouldInterceptAppCommand(message(1), emptySet(), true, false, true))
        assertFalse(shouldInterceptAppCommand(message(7, 0x8000), emptySet(), true, false, true))
        assertFalse(shouldInterceptAppCommand(message(7, 0), emptySet(), true, false, false))
        assertFalse(shouldInterceptAppCommand(message(7, 0), emptySet(), false, false, true))
    }

    @Test
    fun windowsSubclassInterceptsAndIsRemoved() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val frame = JFrame()
        EventQueue.invokeAndWait { frame.addNotify() }
        val ready = CountDownLatch(1)
        val error = AtomicReference("")
        val interceptor = AppCommandInterceptor(frame, { true }, { error.set(it); ready.countDown() }, { ready.countDown() })
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Window interceptor did not start")
            assertEquals("", error.get())
            val hwnd = WinDef.HWND(Native.getWindowPointer(frame))
            val send = {
                User32.INSTANCE.SendMessage(hwnd, 0x0319, WinDef.WPARAM(0), WinDef.LPARAM(0x07fe0000)).toLong()
            }
            assertEquals(1, send())
            interceptor.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (interceptor.isInstalled && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse(interceptor.isInstalled, "Window interceptor did not uninstall")
            assertEquals(0, send())
            assertEquals("", error.get())
        } finally {
            interceptor.close()
            EventQueue.invokeAndWait { frame.dispose() }
        }
    }
}
