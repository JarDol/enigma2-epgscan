package pl.jardol.e2stream.desktop

import com.sun.jna.Native
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicReference

class RawHidInputTest {
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
        } finally {
            listener.close()
        }
    }
}
