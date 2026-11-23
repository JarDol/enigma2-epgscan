package pl.jardol.e2stream.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Window
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun shouldInterceptAppCommand(
    lParam: Long, enabled: Set<Int>, focused: Boolean, capturing: Boolean, suppressBrowser: Boolean,
): Boolean {
    val command = ((lParam ushr 16) and 0x0fff).toInt()
    val device = ((lParam ushr 16) and 0xf000).toInt()
    if (!focused) return false
    return device == 0x1000 && command in 8..10 && (capturing || command in enabled) ||
        device in setOf(0, 0x1000) && command in setOf(1, 7) && suppressBrowser
}

internal class AppCommandInterceptor(
    window: Window,
    private val shouldIntercept: (Long) -> Boolean,
    private val onError: (String) -> Unit,
    private val onReady: () -> Unit = {},
    private val onObserved: (Long, Boolean) -> Unit = { _, _ -> },
) : AutoCloseable {
    private fun interface CallWindowProc : WinUser.HOOKPROC {
        fun callback(code: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT
    }

    private fun interface SubclassProc : StdCallLibrary.StdCallCallback {
        fun callback(
            hwnd: WinDef.HWND, message: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM,
            id: WinDef.UINT_PTR, data: BaseTSD.DWORD_PTR,
        ): WinDef.LRESULT
    }

    private interface Comctl32 : StdCallLibrary {
        fun SetWindowSubclass(hwnd: WinDef.HWND, proc: SubclassProc, id: WinDef.UINT_PTR, data: BaseTSD.DWORD_PTR): Boolean
        fun RemoveWindowSubclass(hwnd: WinDef.HWND, proc: SubclassProc, id: WinDef.UINT_PTR): Boolean
        fun DefSubclassProc(hwnd: WinDef.HWND, message: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT
    }

    private val hwnd = WinDef.HWND(Native.getWindowPointer(window))
    private val id = WinDef.UINT_PTR(nextId.incrementAndGet())
    private val closed = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { action ->
        Thread(action, "E2Stream-app-command").apply { isDaemon = true }
    }
    @Volatile private var operation: (() -> Boolean)? = null
    @Volatile private var operationResult = false
    @Volatile private var installed = false
    @Volatile private var hookFailedToUninstall = false
    internal val isInstalled: Boolean get() = installed

    private val subclass = SubclassProc { target, message, wParam, lParam, _, _ ->
        val intercept = try {
            val take = message == WM_APPCOMMAND && !closed.get() && shouldIntercept(lParam.toLong())
            if (message == WM_APPCOMMAND) onObserved(lParam.toLong(), take)
            take
        } catch (e: Exception) {
            onError("Błąd obsługi komunikatu Windows: ${e.message ?: e.javaClass.simpleName}")
            false
        }
        val result = if (intercept) {
            WinDef.LRESULT(1)
        } else {
            comctl.DefSubclassProc(target, message, wParam, lParam)
        }
        if (message == WM_NCDESTROY) {
            installed = false
            if (!hookFailedToUninstall) retained.remove(this)
        }
        result
    }
    private val hookCallback = CallWindowProc { code, wParam, lParam ->
        try {
            if (code >= 0) {
                val msg = WinUser.CWPSTRUCT(Pointer(lParam.toLong()))
                if (msg.hwnd == hwnd && msg.message == controlMessage && msg.wParam.toLong() == id.toLong()) {
                    operationResult = operation?.invoke() == true
                }
            }
        } catch (e: Exception) {
            onError("Błąd przechwytywania komunikatów Windows: ${e.message ?: e.javaClass.simpleName}")
        }
        User32.INSTANCE.CallNextHookEx(null, code, wParam, lParam)
    }

    init {
        retained.add(this)
        worker.execute {
            if (tryOnWindowThread {
                    comctl.SetWindowSubclass(hwnd, subclass, id, BaseTSD.DWORD_PTR(0)).also { installed = it }
                }) {
                onReady()
            } else {
                onError("Nie udało się przechwycić głośności Windows dla pilota HID.")
                if (!installed && !hookFailedToUninstall) retained.remove(this)
            }
        }
    }

    private fun tryOnWindowThread(action: () -> Boolean): Boolean = try {
        onWindowThread(action)
    } catch (e: Exception) {
        onError("Błąd komunikacji z oknem Windows: ${e.message ?: e.javaClass.simpleName}")
        false
    }

    private fun onWindowThread(action: () -> Boolean): Boolean {
        if (!User32.INSTANCE.IsWindow(hwnd)) return false
        val threadId = User32.INSTANCE.GetWindowThreadProcessId(hwnd, IntByReference())
        if (threadId == 0 || controlMessage == 0) return false
        operation = action
        operationResult = false
        // The helper must be installed and removed on the native AWT window thread.
        val hook = User32.INSTANCE.SetWindowsHookEx(4, hookCallback, null, threadId) ?: run {
            operation = null
            return false
        }
        try {
            User32.INSTANCE.SendMessage(hwnd, controlMessage, WinDef.WPARAM(id.toLong()), WinDef.LPARAM(0))
        } finally {
            operation = null
            hookFailedToUninstall = true
            if (User32.INSTANCE.UnhookWindowsHookEx(hook)) {
                hookFailedToUninstall = false
            } else {
                onError("Nie udało się zwolnić diagnostycznego haka komunikatów Windows.")
            }
        }
        return operationResult
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.execute {
            if (installed && User32.INSTANCE.IsWindow(hwnd)) {
                if (!tryOnWindowThread { comctl.RemoveWindowSubclass(hwnd, subclass, id).also { if (it) installed = false } }) {
                    onError("Nie udało się zwolnić przechwytywania komunikatów Windows.")
                }
            } else {
                installed = false
            }
            if (!installed && !hookFailedToUninstall) retained.remove(this)
        }
        worker.shutdown()
    }

    companion object {
        private const val WM_APPCOMMAND = 0x0319
        private const val WM_NCDESTROY = 0x0082
        private val nextId = AtomicLong()
        private val retained = ConcurrentHashMap.newKeySet<AppCommandInterceptor>()
        private val comctl = Native.load("comctl32", Comctl32::class.java, W32APIOptions.DEFAULT_OPTIONS)
        private val controlMessage = User32.INSTANCE.RegisterWindowMessage("E2Stream.AppCommandInterceptor")
    }
}
