package pl.jardol.e2stream.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RawHidInputTest {
    @Test
    fun rejectsInvalidUsagesWithoutDroppingVendorPages() {
        assertFalse(validHidUsage(0xff00.toShort(), 0))
        assertFalse(validHidUsage(0, 1))
        assertTrue(validHidUsage(0xff00.toShort(), 1))
        assertTrue(validHidUsage(0x000c, 1))
    }

    @Test
    fun rawInputDeviceStructureIsAccessibleToJna() {
        val device = RawHidInput.RawInputDevice()
        assertEquals(if (Native.POINTER_SIZE == 8) 16 else 12, device.size())
        device.write()
    }

    @Test
    fun windowsRawInputListenerStartsAndStops() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val diagnostic = AtomicReference("")
        val listener = RawHidInput(
            isApplicationFocused = { false },
            onKey = { _, _ -> },
            onError = { error -> diagnostic.set(error) },
            onDiagnostic = { message -> diagnostic.set(message) },
            suppressBrowserKeys = { false },
        )
        try {
            assertTrue(diagnostic.get().startsWith("Odbiornik HID uruchomiony"), diagnostic.get())
            val threads = Thread.getAllStackTraces().keys
            assertTrue(threads.any { it.name == "E2Stream-HID" && it.isAlive })
            assertTrue(threads.any { it.name == "E2Stream-keyboard-hook" && it.isAlive })
        } finally {
            listener.close()
        }
    }

    @Test
    fun windowsKeyboardHookReceivesInjectedKeyAndStops() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val received = CountDownLatch(1)
        val callback = WinUser.LowLevelKeyboardProc { code, wParam, data ->
            if (code >= 0 && data.vkCode == 0x87) {
                if (wParam.toInt() == 0x100) received.countDown()
                WinDef.LRESULT(1)
            } else {
                User32.INSTANCE.CallNextHookEx(null, code, wParam, WinDef.LPARAM(com.sun.jna.Pointer.nativeValue(data.pointer)))
            }
        }
        val errors = AtomicReference("")
        val hook = KeyboardHook(callback) { errors.set(it) }
        try {
            val input = WinUser.INPUT().apply {
                type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
                input.setType(WinUser.KEYBDINPUT::class.java)
                input.ki.wVk = WinDef.WORD(0x87)
            }
            assertEquals(1, User32.INSTANCE.SendInput(WinDef.DWORD(1), arrayOf(input), input.size()).toInt())
            assertTrue(received.await(3, TimeUnit.SECONDS), "Keyboard hook did not receive F24")
            assertEquals("", errors.get())
        } finally {
            hook.close()
        }
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "E2Stream-keyboard-hook" && it.isAlive })
    }

    @Test
    fun windowsKeyboardHookWorksWhileRawInputThreadIsBlocked() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val received = CountDownLatch(1)
        val listener = AtomicReference<RawHidInput?>()
        val failure = AtomicReference<Throwable?>()
        val guard = KeyboardHook(WinUser.LowLevelKeyboardProc { code, wParam, data ->
            if (code >= 0 && data.vkCode == 0x87) WinDef.LRESULT(1)
            else User32.INSTANCE.CallNextHookEx(null, code, wParam, WinDef.LPARAM(com.sun.jna.Pointer.nativeValue(data.pointer)))
        }) { failure.set(AssertionError(it)) }
        val starter = Thread {
            try {
                listener.set(RawHidInput(
                    isApplicationFocused = { false },
                    onKey = { _, _ -> },
                    onError = { failure.set(AssertionError(it)) },
                    onDiagnostic = {
                        if (it.startsWith("Odbiornik HID uruchomiony")) {
                            blocked.countDown()
                            release.await(3, TimeUnit.SECONDS)
                        }
                    },
                    suppressBrowserKeys = { false },
                    isPowerKey = { it == "K:135" },
                    onPower = { received.countDown() },
                ))
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        try {
            starter.start()
            assertTrue(blocked.await(3, TimeUnit.SECONDS), "Raw Input thread did not reach the blocking callback")
            val input = WinUser.INPUT().apply {
                type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
                input.setType(WinUser.KEYBDINPUT::class.java)
                input.ki.wVk = WinDef.WORD(0x87)
            }
            assertEquals(1, User32.INSTANCE.SendInput(WinDef.DWORD(1), arrayOf(input), input.size()).toInt())
            assertTrue(received.await(1, TimeUnit.SECONDS), "Keyboard hook stalled with the Raw Input thread")
        } finally {
            release.countDown()
            starter.join(6000)
            listener.get()?.close()
            guard.close()
        }
        assertNotNull(listener.get(), failure.get()?.toString())
        assertEquals(null, failure.get())
    }

    @Test
    fun windowsRawInputListenerDoesNotSpinWhenIdle() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val threads = ManagementFactory.getThreadMXBean()
        if (!threads.isThreadCpuTimeSupported) return
        threads.isThreadCpuTimeEnabled = true
        val listener = RawHidInput(
            isApplicationFocused = { false },
            onKey = { _, _ -> },
            onError = { error -> throw AssertionError(error) },
            onDiagnostic = {},
            suppressBrowserKeys = { false },
        )
        try {
            val thread = Thread.getAllStackTraces().keys.single { it.name == "E2Stream-HID" && it.isAlive }
            val before = threads.getThreadCpuTime(thread.id)
            Thread.sleep(2000)
            val cpuMs = (threads.getThreadCpuTime(thread.id) - before) / 1_000_000
            assertTrue(cpuMs < 500, "Idle HID thread consumed $cpuMs ms of CPU in 2 seconds")
        } finally {
            listener.close()
        }
    }
}
