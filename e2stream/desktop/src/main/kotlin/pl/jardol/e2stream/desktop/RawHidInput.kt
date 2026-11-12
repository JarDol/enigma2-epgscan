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
    private val onError: (String) -> Unit,
    private val onDiagnostic: (String) -> Unit,
    private val suppressBrowserKeys: () -> Boolean,
    private val isPowerKey: (String) -> Boolean = { false },
    private val onPower: () -> Unit = {},
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
            onPower()
        }
        if (isPower && isForegroundApplication() ||
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
        usageRegistry.registerNew(usages)
    }

    private fun deviceUsage(device: WinNT.HANDLE): Pair<Short, Short>? {
        val info = Memory(RAW_DEVICE_INFO_SIZE.toLong())
        info.setInt(0, RAW_DEVICE_INFO_SIZE)
        val size = IntByReference(RAW_DEVICE_INFO_SIZE)
        if (RawInputApi.INSTANCE.GetRawInputDeviceInfo(device, RIDI_DEVICEINFO, info, size) == UINT_ERROR) return null
        if (info.getInt(4) != RIM_TYPEHID) return null
        return info.getShort(20) to info.getShort(22)
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
        if (data.getInt(0) != RIM_TYPEHID) return
        val device = WinNT.HANDLE(data.getPointer(8))
        val reportSize = data.getInt(headerSize.toLong())
        val reportCount = data.getInt(headerSize.toLong() + 4)
        if (reportSize <= 1 || reportCount <= 0 || reportSize.toLong() * reportCount > size.value - headerSize - 8) return
        val deviceInfo = deviceInfo(device) ?: return
        val deviceHandle = Pointer.nativeValue(device.pointer)
        val payload = ByteArray(reportSize - 1)
        for (index in 0 until reportCount) {
            val reportOffset = headerSize.toLong() + 8 + reportSize.toLong() * index
            data.read(reportOffset + 1, payload, 0, payload.size)
            if (payload.isEmpty() || payload.all { it == 0.toByte() }) {
                powerPresses.update(deviceHandle, false)
                continue
            }
            val id = UniversalRemoteMap.hidId(deviceInfo.path, payload)
            val isPower = isPowerKey(id)
            val newPowerPress = powerPresses.update(deviceHandle, isPower)
            when (hidInputRoute(isPower, !isPower && isForegroundApplication() && isApplicationFocused())) {
                HidInputRoute.POWER -> {
                    if (newPowerPress) onPower()
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
        private const val RIDEV_INPUTSINK = 0x00000100
        private const val RIDEV_DEVNOTIFY = 0x00002000
        private const val UINT_ERROR = -1
        private const val RAW_DEVICE_INFO_SIZE = 32
        private const val MAX_INPUT_SIZE = 1024 * 1024
        private const val MAX_DEVICE_NAME = 32_768
        private val CONSUMER_CONTROL_USAGE = 0x000C.toShort() to 0x0001.toShort()
    }
}
