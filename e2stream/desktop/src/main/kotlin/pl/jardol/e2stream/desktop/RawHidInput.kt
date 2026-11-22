package pl.jardol.e2stream.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Platform
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.User32Util
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal fun validHidUsage(page: Short, usage: Short): Boolean = page != 0.toShort() && usage != 0.toShort()

internal fun isForegroundApplication(): Boolean {
    if (!Platform.isWindows()) return true
    val foreground = User32.INSTANCE.GetForegroundWindow() ?: return false
    val processId = IntByReference()
    if (User32.INSTANCE.GetWindowThreadProcessId(foreground, processId) == 0) return false
    return processId.value == Kernel32.INSTANCE.GetCurrentProcessId()
}

internal class RawHidInput(
    private val isApplicationFocused: () -> Boolean,
    private val onKey: (id: String, label: String) -> Unit,
    /** Puszczenie przycisku (raport z samymi zerami albo inny przycisk tego samego urządzenia). */
    private val onRelease: (id: String) -> Unit = {},
    private val onError: (String) -> Unit,
    private val onDiagnostic: (String) -> Unit,
    private val suppressBrowserKeys: () -> Boolean,
    private val isPowerKey: (String) -> Boolean = { false },
    /** Klawisz globalny (Power albo „Aktywuj okno”) — z identyfikatorem klawisza. */
    private val onPower: (String) -> Unit = {},
    /** Zwraca true, gdy klawisz ma być obsłużony przez program i nie trafić do Windows (np. głośność). */
    private val interceptKey: (physicalId: String, down: Boolean) -> Boolean = { _, _ -> false },
    /** Przejmowanie klawiszy systemowych przy aktywnym E2Stream: "remote" (tylko z pilota USB/Bluetooth), "all", "off". */
    private val systemKeyMode: () -> String = { "off" },
    /** Przejęty klawisz systemowy z klawiatury pilota (Windows go nie dostał) — do obsługi w programie. */
    private val onSystemKey: (physicalId: String) -> Unit = {},
) : AutoCloseable {
    private val ready = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()
    @Volatile private var closed = false
    @Volatile private var window: WinDef.HWND? = null
    private val deviceInfoCache = mutableMapOf<Long, HidDeviceInfo?>()
    private var lastDiagnostic: String? = null
    private val usageRegistry = HidUsageRegistry(::registerUsages)
    private val pressedPowerKeys = mutableSetOf<Int>()
    private val powerPresses = PowerPressTracker()
    /** Ostatnio naciśnięty przycisk każdego urządzenia — do zgłoszenia puszczenia. */
    private val pressedKeys = mutableMapOf<Long, String>()
    private val windowProc = WinUser.WindowProc(::windowProc)
    private val keyboardProc = WinUser.LowLevelKeyboardProc { code, wParam, data ->
        val message = wParam.toInt()
        val physicalId = UniversalRemoteMap.keyId(data.vkCode)
        val isPower = code >= 0 && isPowerKey(physicalId)
        if (code >= 0 && (message == WM_KEYUP || message == WM_SYSKEYUP)) {
            pressedPowerKeys.remove(data.vkCode)
        }
        if (isPower && (message == WM_KEYDOWN || message == WM_SYSKEYDOWN) &&
            pressedPowerKeys.add(data.vkCode)
        ) {
            onPower(physicalId)
        }
        val down = message == WM_KEYDOWN || message == WM_SYSKEYDOWN
        val intercepted = code >= 0 && !isPower && isForegroundApplication() &&
            interceptKey(physicalId, down)
        val system = code >= 0 && !isPower && !intercepted && (data.flags and LLKHF_INJECTED) == 0 &&
            data.vkCode in SYSTEM_KEYS && isForegroundApplication() && isApplicationFocused() &&
            takeSystemKey(data.vkCode, down)
        if (intercepted || system || isPower && isForegroundApplication() ||
            code >= 0 && data.vkCode in setOf(VK_BROWSER_BACK, VK_BROWSER_HOME) &&
            isForegroundApplication() && isApplicationFocused() && suppressBrowserKeys()
        ) {
            WinDef.LRESULT(1)
        } else {
            User32.INSTANCE.CallNextHookEx(
                null,
                code,
                wParam,
                WinDef.LPARAM(Pointer.nativeValue(data.pointer)),
            )
        }
    }
    private val thread = Thread(::runMessageLoop, "E2Stream-HID").apply {
        isDaemon = true
        start()
    }

    init {
        if (!ready.await(5, TimeUnit.SECONDS)) {
            close()
            throw IOException("Nie uruchomiono odbiornika pilota HID.")
        }
        failure.get()?.let {
            close()
            throw IOException("Nie udało się uruchomić odbiornika pilota HID.", it)
        }
    }

    // ---------- Klawisze systemowe pilota (Home/przeglądarka, multimedia, Menu, Windows) ----------

    /** Ostatni klawisz z odczytu klawiatur: czy z urządzenia zewnętrznego (USB/Bluetooth) i kiedy. */
    private data class KeySource(val external: Boolean, val device: String, val at: Long)
    private val keyboardSources = HashMap<Int, KeySource>()
    @Volatile private var lastConsumerExternal = KeySource(false, "", 0L)
    private var systemDecisions = 0
    private val heldSystemKeys = HashSet<Int>()

    /**
     * Czy przejąć klawisz systemowy (Windows go nie dostanie). Źródło klawisza z odczytu urządzeń (Raw Input) —
     * wiadomości czekające w kolejce są odczytywane przed decyzją. Klawiatura laptopa (bez VID) — zwykle do Windows.
     */
    private fun takeSystemKey(vk: Int, down: Boolean): Boolean {
        val mode = systemKeyMode()
        if (mode == "off") return false
        if (!down) return heldSystemKeys.remove(vk)
        pumpRawInput()
        val now = System.currentTimeMillis()
        val keyboard = keyboardSources[vk]?.takeIf { now - it.at <= SOURCE_WINDOW_MS }
        val consumer = lastConsumerExternal.takeIf { it.external && now - it.at <= SOURCE_WINDOW_MS && vk !in WINDOWS_KEYS }
        val source = keyboard ?: consumer
        val take = when {
            mode == "all" -> true
            source != null -> source.external
            // Źródło nieznane: klawisz Windows zostaje dla Windows, pozostałe przejmowane (E2Stream jest aktywny).
            else -> vk !in WINDOWS_KEYS
        }
        if (systemDecisions < MAX_DECISION_LOGS) {
            systemDecisions++
            onDiagnostic(
                "klawisz systemowy 0x%02X: %s — %s".format(
                    vk,
                    when {
                        source == null -> "źródło nieznane"
                        source.external -> "z urządzenia ${source.device}"
                        else -> "z klawiatury komputera"
                    },
                    if (take) "przejęty przez E2Stream" else "do Windows",
                ),
            )
        }
        if (take) {
            heldSystemKeys += vk
            // Z klawiatury pilota (nie z raportu HID multimediów — ten obsługuje odczyt HID) — akcja w programie.
            if (keyboard != null || consumer == null) onSystemKey(UniversalRemoteMap.keyId(vk))
        }
        return take
    }

    private val pumpMessage = WinUser.MSG()

    /** Odczyt czekających WM_INPUT (źródło klawisza) przed decyzją w haku klawiatury. */
    private fun pumpRawInput() {
        val target = window ?: return
        var guard = 0
        while (guard++ < 16 && User32.INSTANCE.PeekMessage(pumpMessage, target, WM_INPUT, WM_INPUT, PM_REMOVE)) {
            User32.INSTANCE.DispatchMessage(pumpMessage)
        }
    }

    private fun isExternalDevice(path: String): Boolean =
        Regex("VID[_&]", RegexOption.IGNORE_CASE).containsMatchIn(path) && !path.contains("ACPI", ignoreCase = true)

    private fun runMessageLoop() {
        var registeredClass: String? = null
        var keyboardHook: WinUser.HHOOK? = null
        try {
            val className = "E2StreamRawInput${System.nanoTime()}"
            val module = Kernel32.INSTANCE.GetModuleHandle(null)
            val windowClassInfo = WinUser.WNDCLASSEX().apply {
                cbSize = size()
                lpfnWndProc = windowProc
                hInstance = module
                lpszClassName = className
            }
            if (User32.INSTANCE.RegisterClassEx(windowClassInfo).toInt() == 0) {
                throw IOException("Windows nie zarejestrował okna odbiornika HID (kod ${Kernel32.INSTANCE.GetLastError()}).")
            }
            registeredClass = className
            val created = User32.INSTANCE.CreateWindowEx(
                0,
                className,
                "E2Stream Raw Input",
                0,
                0,
                0,
                0,
                0,
                // HWND_MESSAGE is a sign-extended pointer on 64-bit Windows.
                WinDef.HWND(Pointer.createConstant(-3L)),
                null,
                module,
                null,
            ) ?: throw IOException("Windows nie utworzył okna odbiornika HID (kod ${Kernel32.INSTANCE.GetLastError()}).")
            window = created
            registerConnectedDevices()
            keyboardHook = User32.INSTANCE.SetWindowsHookEx(WH_KEYBOARD_LL, keyboardProc, module, 0)
                ?: throw IOException("Windows nie uruchomił ochrony klawiszy przeglądarki (kod ${Kernel32.INSTANCE.GetLastError()}).")
            onDiagnostic("Odbiornik HID uruchomiony. Naciśnij przycisk pilota.")
            ready.countDown()

            val message = WinUser.MSG()
            while (!closed) {
                val result = User32.INSTANCE.GetMessage(message, null, 0, 0)
                if (result <= 0) break
                User32.INSTANCE.TranslateMessage(message)
                User32.INSTANCE.DispatchMessage(message)
            }
        } catch (e: Throwable) {
            failure.set(e)
            ready.countDown()
            if (!closed) onError(e.message ?: "Błąd odbiornika HID.")
        } finally {
            keyboardHook?.let { User32.INSTANCE.UnhookWindowsHookEx(it) }
            window?.let { if (User32.INSTANCE.IsWindow(it)) User32.INSTANCE.DestroyWindow(it) }
            window = null
            registeredClass?.let { User32.INSTANCE.UnregisterClass(it, Kernel32.INSTANCE.GetModuleHandle(null)) }
        }
    }

    private fun registerConnectedDevices() {
        val usages = linkedSetOf<Pair<Short, Short>>()
        for (device in User32Util.GetRawInputDeviceList()) {
            if (device.dwType != RIM_TYPEHID) continue
            deviceUsage(device.hDevice)?.let(usages::add)
        }
        usages += CONSUMER_CONTROL_USAGE
        // Klawiatury (także klawiatura pilota-myszki): źródło klawiszy systemowych.
        usages += KEYBOARD_USAGE
        usageRegistry.registerNew(usages)
    }

    private fun deviceUsage(device: WinNT.HANDLE): Pair<Short, Short>? {
        val info = Memory(RAW_DEVICE_INFO_SIZE.toLong())
        info.setInt(0, RAW_DEVICE_INFO_SIZE)
        val size = IntByReference(RAW_DEVICE_INFO_SIZE)
        if (RawInputApi.INSTANCE.GetRawInputDeviceInfo(device, RIDI_DEVICEINFO, info, size) == UINT_ERROR) return null
        if (info.getInt(4) != RIM_TYPEHID) return null
        val page = info.getShort(20)
        val usage = info.getShort(22)
        if (!validHidUsage(page, usage)) {
            onDiagnostic("Pomijam urządzenie HID z nieprawidłowym identyfikatorem strony lub użycia.")
            return null
        }
        return page to usage
    }

    private fun registerUsages(usages: Set<Pair<Short, Short>>) {
        val target = window ?: throw IOException("Okno odbiornika HID jest niedostępne.")
        val structSize = RawInputDevice().size()
        val memory = Memory((structSize * usages.size).toLong())
        usages.forEachIndexed { index, usage ->
            RawInputDevice(memory.share((structSize * index).toLong())).apply {
                usUsagePage = usage.first
                usUsage = usage.second
                dwFlags = RIDEV_INPUTSINK or RIDEV_DEVNOTIFY
                hwndTarget = target
                write()
            }
        }
        if (!RawInputApi.INSTANCE.RegisterRawInputDevices(memory, usages.size, structSize)) {
            throw IOException("Windows nie zarejestrował urządzeń HID (kod ${Kernel32.INSTANCE.GetLastError()}).")
        }
    }

    private fun windowProc(
        hwnd: WinDef.HWND,
        message: Int,
        wParam: WinDef.WPARAM,
        lParam: WinDef.LPARAM,
    ): WinDef.LRESULT {
        when (message) {
            WM_INPUT -> {
                try {
                    readInput(Pointer.createConstant(lParam.toLong()))
                } catch (e: Exception) {
                    onError("Nie udało się odczytać przycisku HID: ${e.message ?: e.javaClass.simpleName}")
                }
                return User32.INSTANCE.DefWindowProc(hwnd, message, wParam, lParam)
            }
            WM_INPUT_DEVICE_CHANGE -> {
                try {
                    val handle = lParam.toLong()
                    deviceInfoCache.remove(handle)
                    pressedKeys.remove(handle)?.let(onRelease)
                    powerPresses.remove(handle)
                    if (wParam.toInt() == GIDC_ARRIVAL) {
                        deviceUsage(WinNT.HANDLE(Pointer.createConstant(handle)))?.let {
                            usageRegistry.registerNew(listOf(it))
                        }
                    }
                } catch (e: Exception) {
                    onError("Nie udało się odświeżyć urządzeń HID: ${e.message ?: e.javaClass.simpleName}")
                }
                return WinDef.LRESULT(0)
            }
            WM_CLOSE -> {
                User32.INSTANCE.DestroyWindow(hwnd)
                return WinDef.LRESULT(0)
            }
            WM_DESTROY -> {
                User32.INSTANCE.PostQuitMessage(0)
                return WinDef.LRESULT(0)
            }
        }
        return User32.INSTANCE.DefWindowProc(hwnd, message, wParam, lParam)
    }

    private fun readInput(rawHandle: Pointer) {
        val headerSize = 8 + Native.POINTER_SIZE * 2
        val size = IntByReference()
        if (RawInputApi.INSTANCE.GetRawInputData(rawHandle, RID_INPUT, null, size, headerSize) == UINT_ERROR) {
            return
        }
        if (size.value < headerSize || size.value > MAX_INPUT_SIZE) return
        val data = Memory(size.value.toLong())
        if (RawInputApi.INSTANCE.GetRawInputData(rawHandle, RID_INPUT, data, size, headerSize) == UINT_ERROR) {
            return
        }
        if (data.getInt(0) == RIM_TYPEKEYBOARD) {
            val keyboardDevice = WinNT.HANDLE(data.getPointer(8))
            val vk = data.getShort(headerSize.toLong() + 6).toInt() and 0xFFFF
            val info = deviceInfo(keyboardDevice)
            val path = info?.path.orEmpty()
            keyboardSources[vk] = KeySource(isExternalDevice(path), info?.vendorProduct ?: "nieznane", System.currentTimeMillis())
            return
        }
        if (data.getInt(0) != RIM_TYPEHID) return
        val device = WinNT.HANDLE(data.getPointer(8))
        val reportSize = data.getInt(headerSize.toLong())
        val reportCount = data.getInt(headerSize.toLong() + 4)
        if (reportSize <= 1 || reportCount <= 0 || reportSize.toLong() * reportCount > size.value - headerSize - 8) return
        val deviceInfo = deviceInfo(device) ?: return
        val deviceHandle = Pointer.nativeValue(device.pointer)
        // Raport multimediów / sterowania systemem z urządzenia zewnętrznego — Windows zamieni go zaraz na klawisz (VK).
        lastConsumerExternal = KeySource(isExternalDevice(deviceInfo.path), deviceInfo.vendorProduct, System.currentTimeMillis())
        val payload = ByteArray(reportSize - 1)
        for (index in 0 until reportCount) {
            val reportOffset = headerSize.toLong() + 8 + reportSize.toLong() * index
            data.read(reportOffset + 1, payload, 0, payload.size)
            if (payload.isEmpty() || payload.all { it == 0.toByte() }) {
                powerPresses.update(deviceHandle, false)
                pressedKeys.remove(deviceHandle)?.let(onRelease)
                continue
            }
            val id = UniversalRemoteMap.hidId(deviceInfo.path, payload)
            val previous = pressedKeys.put(deviceHandle, id)
            if (previous != null && previous != id) onRelease(previous)
            val isPower = isPowerKey(id)
            val newPowerPress = powerPresses.update(deviceHandle, isPower)
            when (hidInputRoute(isPower, !isPower && isForegroundApplication() && isApplicationFocused())) {
                HidInputRoute.POWER -> {
                    if (newPowerPress) onPower(id)
                    continue
                }
                HidInputRoute.IGNORE -> continue
                HidInputRoute.LOCAL -> Unit
            }
            val formattedPayload = payload.joinToString("-") { "%02X".format(it.toInt() and 0xFF) }
            onKey(
                id,
                "Pilot HID ${deviceInfo.vendorProduct} · $formattedPayload",
            )
            val reportId = data.getByte(reportOffset).toInt() and 0xFF
            val diagnostic = "HID ${deviceInfo.vendorProduct} · ${"%02X".format(reportId)}-$formattedPayload"
            if (diagnostic != lastDiagnostic) {
                lastDiagnostic = diagnostic
                onDiagnostic(diagnostic)
            }
        }
    }

    private fun deviceInfo(device: WinNT.HANDLE): HidDeviceInfo? {
        val handle = Pointer.nativeValue(device.pointer)
        if (deviceInfoCache.containsKey(handle)) return deviceInfoCache[handle]
        val info = devicePath(device)?.let { path -> HidDeviceInfo(path, vendorProduct(path)) }
        deviceInfoCache[handle] = info
        return info
    }

    private fun devicePath(device: WinNT.HANDLE): String? {
        val size = IntByReference()
        if (RawInputApi.INSTANCE.GetRawInputDeviceInfo(device, RIDI_DEVICENAME, null, size) == UINT_ERROR ||
            size.value <= 1 || size.value > MAX_DEVICE_NAME
        ) return null
        val buffer = Memory(size.value.toLong() * Native.WCHAR_SIZE)
        if (RawInputApi.INSTANCE.GetRawInputDeviceInfo(device, RIDI_DEVICENAME, buffer, size) == UINT_ERROR) return null
        return buffer.getWideString(0).takeIf(String::isNotBlank)
    }

    private fun vendorProduct(path: String): String =
        Regex("VID_([0-9A-F]{4}).*PID_([0-9A-F]{4})", RegexOption.IGNORE_CASE)
            .find(path)
            ?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }
            ?: "nieznany"

    private data class HidDeviceInfo(val path: String, val vendorProduct: String)

    override fun close() {
        if (closed) return
        closed = true
        window?.let { User32.INSTANCE.PostMessage(it, WM_CLOSE, WinDef.WPARAM(0), WinDef.LPARAM(0)) }
        if (Thread.currentThread() !== thread) thread.join(2000)
    }

    class RawInputDevice(pointer: Pointer? = null) : Structure(pointer) {
        @JvmField var usUsagePage: Short = 0
        @JvmField var usUsage: Short = 0
        @JvmField var dwFlags: Int = 0
        @JvmField var hwndTarget: WinDef.HWND? = null

        override fun getFieldOrder(): List<String> = listOf("usUsagePage", "usUsage", "dwFlags", "hwndTarget")
    }

    private interface RawInputApi : StdCallLibrary {
        fun RegisterRawInputDevices(devices: Pointer, count: Int, size: Int): Boolean
        fun GetRawInputData(
            rawInput: Pointer,
            command: Int,
            data: Pointer?,
            size: IntByReference,
            headerSize: Int,
        ): Int

        fun GetRawInputDeviceInfo(
            device: WinNT.HANDLE,
            command: Int,
            data: Pointer?,
            size: IntByReference,
        ): Int

        companion object {
            val INSTANCE: RawInputApi = Native.load("user32", RawInputApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    companion object {
        private const val WM_INPUT = 0x00FF
        private const val WH_KEYBOARD_LL = 13
        private const val VK_BROWSER_BACK = 0xA6
        private const val VK_BROWSER_HOME = 0xAC
        private const val WM_INPUT_DEVICE_CHANGE = 0x00FE
        private const val WM_KEYDOWN = 0x0100
        private const val WM_KEYUP = 0x0101
        private const val WM_SYSKEYDOWN = 0x0104
        private const val WM_SYSKEYUP = 0x0105
        private const val GIDC_ARRIVAL = 1
        private const val WM_CLOSE = 0x0010
        private const val WM_DESTROY = 0x0002
        private const val RID_INPUT = 0x10000003
        private const val RIDI_DEVICENAME = 0x20000007
        private const val RIDI_DEVICEINFO = 0x2000000B
        private const val RIM_TYPEHID = 2
        private const val RIM_TYPEKEYBOARD = 1
        private const val PM_REMOVE = 0x0001
        private const val LLKHF_INJECTED = 0x10
        private const val SOURCE_WINDOW_MS = 250L
        private const val MAX_DECISION_LOGS = 40
        private val KEYBOARD_USAGE = 0x0001.toShort() to 0x0006.toShort()
        /** Klawisz Windows (lewy, prawy). */
        private val WINDOWS_KEYS = setOf(0x5B, 0x5C)
        /**
         * Klawisze, które Windows obsługuje sam niezależnie od okna: Windows, Menu kontekstowe, uśpij, przeglądarka,
         * multimedia, poczta / aplikacje. Głośność (0xAD–0xAF) — osobne ustawienie „Głośność w programie”.
         */
        private val SYSTEM_KEYS = WINDOWS_KEYS + setOf(0x5D, 0x5F) + (0xA6..0xAC).toSet() + (0xB0..0xB7).toSet()
        private const val RIDEV_INPUTSINK = 0x00000100
        private const val RIDEV_DEVNOTIFY = 0x00002000
        private const val UINT_ERROR = -1
        private const val RAW_DEVICE_INFO_SIZE = 32
        private const val MAX_INPUT_SIZE = 1024 * 1024
        private const val MAX_DEVICE_NAME = 32_768
        private val CONSUMER_CONTROL_USAGE = 0x000C.toShort() to 0x0001.toShort()
    }
}
