package pl.jardol.e2stream.desktop

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window

/**
 * Pełny ekran bez ramki — okno zasłania cały monitor razem z paskiem zadań. Styl okna jest
 * zmieniany na istniejącym HWND, bo ponowne tworzenie okna zerwałoby powierzchnię wideo VLC.
 * Wyłączny tryb AWT (setFullScreenWindow) nie jest używany, bo minimalizuje okno przy otwieraniu dialogów.
 */
internal class BorderlessFullscreen(private val window: Window) {
    private var savedStyle: Int? = null
    private var savedBounds: Rectangle? = null

    val isActive: Boolean get() = savedStyle != null || savedBounds != null

    fun enter() {
        if (isActive) return
        savedBounds = window.bounds
        if (!Platform.isWindows()) {
            val screen = window.graphicsConfiguration?.bounds
                ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
            window.bounds = screen
            return
        }
        val user32 = User32.INSTANCE
        val hwnd = WinDef.HWND(Native.getWindowPointer(window))
        val style = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        savedStyle = style
        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, style and (WinUser.WS_CAPTION or WinUser.WS_THICKFRAME).inv())
        val monitor = user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST)
        val info = WinUser.MONITORINFO()
        val rect = if (user32.GetMonitorInfo(monitor, info).booleanValue()) info.rcMonitor else null
        if (rect != null) {
            user32.SetWindowPos(
                hwnd, WinDef.HWND(com.sun.jna.Pointer.createConstant(0)),
                rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top,
                SWP_FRAMECHANGED or SWP_NOOWNERZORDER or WinUser.SWP_SHOWWINDOW,
            )
        }
    }

    fun exit() {
        val bounds = savedBounds
        val style = savedStyle
        savedBounds = null
        savedStyle = null
        if (style != null && Platform.isWindows()) {
            val user32 = User32.INSTANCE
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, style)
            val b = bounds ?: return
            user32.SetWindowPos(
                hwnd, WinDef.HWND(com.sun.jna.Pointer.createConstant(0)),
                b.x, b.y, b.width, b.height,
                SWP_FRAMECHANGED or SWP_NOOWNERZORDER or WinUser.SWP_SHOWWINDOW,
            )
        } else if (bounds != null) {
            window.bounds = bounds
        }
    }

    private companion object {
        const val SWP_FRAMECHANGED = 0x0020
        const val SWP_NOOWNERZORDER = 0x0200
    }
}
