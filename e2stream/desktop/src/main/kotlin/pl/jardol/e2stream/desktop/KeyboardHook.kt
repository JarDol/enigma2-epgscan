package pl.jardol.e2stream.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class KeyboardHook(
    private val callback: WinUser.LowLevelKeyboardProc,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val ready = CountDownLatch(1)
    private val failure = AtomicReference<Exception?>()
    @Volatile private var closed = false
    @Volatile private var threadId = 0
    private val thread = Thread(::run, "E2Stream-keyboard-hook").apply {
        isDaemon = true
        start()
    }

    init {
        if (!ready.await(5, TimeUnit.SECONDS)) {
            close()
            throw IOException("Nie uruchomiono przechwytywania klawiszy systemowych.")
        }
        failure.get()?.let {
            close()
            throw IOException("Nie udało się uruchomić przechwytywania klawiszy systemowych.", it)
        }
    }

    private fun run() {
        var hook: WinUser.HHOOK? = null
        try {
            val message = WinUser.MSG()
            User32.INSTANCE.PeekMessage(message, null, 0, 0, 0)
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            if (closed) return
            hook = User32.INSTANCE.SetWindowsHookEx(WH_KEYBOARD_LL, callback, Kernel32.INSTANCE.GetModuleHandle(null), 0)
                ?: throw IOException("Windows nie uruchomił ochrony klawiszy (kod ${Kernel32.INSTANCE.GetLastError()}).")
            ready.countDown()
            while (!closed) {
                val result = User32.INSTANCE.GetMessage(message, null, 0, 0)
                if (result == 0) {
                    if (!closed) throw IOException("Odbiornik klawiszy Windows został nieoczekiwanie zatrzymany.")
                    break
                }
                if (result < 0) throw IOException("Błąd kolejki klawiszy Windows (kod ${Kernel32.INSTANCE.GetLastError()}).")
            }
        } catch (e: Exception) {
            failure.set(e)
            if (ready.count > 0) ready.countDown() else if (!closed) onError(e.message ?: "Błąd obsługi klawiszy.")
        } finally {
            hook?.let { User32.INSTANCE.UnhookWindowsHookEx(it) }
            ready.countDown()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val id = threadId
        if (id != 0 && User32.INSTANCE.PostThreadMessage(id, WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0)) == 0 && thread.isAlive) {
            onError("Nie udało się zamknąć odbiornika klawiszy (kod ${Kernel32.INSTANCE.GetLastError()}).")
        }
        if (Thread.currentThread() !== thread) {
            thread.join(2000)
            if (thread.isAlive) onError("Odbiornik klawiszy nie zakończył pracy.")
        }
    }

    private companion object {
        const val WH_KEYBOARD_LL = 13
        const val WM_QUIT = 0x0012
    }
}
