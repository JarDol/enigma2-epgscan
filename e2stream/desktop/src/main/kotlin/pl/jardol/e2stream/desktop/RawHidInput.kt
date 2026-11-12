package pl.jardol.e2stream.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
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

internal class RawHidInput(
    private val isApplicationFocused: () -> Boolean,
    private val onKey: (id: String, label: String) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val ready = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()
    @Volatile private var closed = false
    @Volatile private var window: WinDef.HWND? = null
    private val windowProc = WinUser.WindowProc(::windowProc)
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
                throw IOException("Windows nie zarejestrował okna odbiornika HID.")
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
                WinUser.HWND_MESSAGE,
                null,
                module,
                null,
            ) ?: throw IOException("Windows nie utworzył okna odbiornika HID.")
            window = created
            registerConnectedDevices(created)
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
            window = null
            registeredClass?.let { User32.INSTANCE.UnregisterClass(it, Kernel32.INSTANCE.GetModuleHandle(null)) }
        }
    }

    private fun registerConnectedDevices(target: WinDef.HWND) {
        val usages = linkedSetOf<Pair<Short, Short>>()
        for (device in User32Util.GetRawInputDeviceList()) {
            if (device.dwType != RIM_TYPEHID) continue
            val info = Memory(RAW_DEVICE_INFO_SIZE.toLong())
            info.setInt(0, RAW_DEVICE_INFO_SIZE)
            val size = IntByReference(RAW_DEVICE_INFO_SIZE)
            if (RawInputApi.INSTANCE.GetRawInputDeviceInfo(
                    device.hDevice,
                    RIDI_DEVICEINFO,
                    info,
                    size,
                ) == UINT_ERROR
            ) continue
            usages += info.getShort(20) to info.getShort(22)
        }
        if (usages.isEmpty()) usages += CONSUMER_CONTROL_USAGE

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
            throw IOException("Windows nie zarejestrował urządzeń HID.")
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
                    if (isApplicationFocused()) readInput(Pointer.createConstant(lParam.toLong()))
                } catch (e: Exception) {
                    onError("Nie udało się odczytać przycisku HID: ${e.message ?: e.javaClass.simpleName}")
                }
                return User32.INSTANCE.DefWindowProc(hwnd, message, wParam, lParam)
            }
            WM_INPUT_DEVICE_CHANGE -> {
                try {
                    registerConnectedDevices(hwnd)
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
        val path = devicePath(device) ?: return
        val report = ByteArray(reportSize)
        for (index in 0 until reportCount) {
            data.read(headerSize.toLong() + 8 + reportSize.toLong() * index, report, 0, reportSize)
            val payload = report.drop(1).toByteArray()
            if (payload.isEmpty() || payload.all { it == 0.toByte() }) continue
            onKey(
                UniversalRemoteMap.hidId(path, payload),
                "Pilot HID ${vendorProduct(path)} · ${payload.joinToString("-") { "%02X".format(it.toInt() and 0xFF) }}",
            )
        }
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

    override fun close() {
        if (closed) return
        closed = true
        window?.let { User32.INSTANCE.PostMessage(it, WM_CLOSE, WinDef.WPARAM(0), WinDef.LPARAM(0)) }
        if (Thread.currentThread() !== thread) thread.join(2000)
    }

    private class RawInputDevice(pointer: Pointer? = null) : Structure(pointer) {
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
        private const val WM_INPUT_DEVICE_CHANGE = 0x00FE
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
