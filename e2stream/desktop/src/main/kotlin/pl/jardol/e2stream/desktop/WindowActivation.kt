package pl.jardol.e2stream.desktop

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import java.awt.Frame
import java.awt.Window

internal fun activateWindow(window: Window): Boolean {
    if (window is Frame && window.extendedState and Frame.ICONIFIED != 0) {
        window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
    }
    window.isVisible = true
    if (!Platform.isWindows()) {
        window.toFront()
        window.requestFocus()
        return true
    }
    val hwnd = WinDef.HWND(Native.getWindowPointer(window))
    val user32 = User32.INSTANCE
    val foreground = user32.GetForegroundWindow()
    val currentThread = Kernel32.INSTANCE.GetCurrentThreadId()
    val foregroundThread = foreground?.let { user32.GetWindowThreadProcessId(it, null) } ?: 0
    val attached = foregroundThread != 0 && foregroundThread != currentThread &&
        user32.AttachThreadInput(WinDef.DWORD(currentThread.toLong()), WinDef.DWORD(foregroundThread.toLong()), true)
    return try {
        user32.BringWindowToTop(hwnd)
        user32.SetForegroundWindow(hwnd)
    } finally {
        if (attached) {
            user32.AttachThreadInput(WinDef.DWORD(currentThread.toLong()), WinDef.DWORD(foregroundThread.toLong()), false)
        }
    }
}
