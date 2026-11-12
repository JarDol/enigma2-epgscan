package pl.jardol.e2stream.desktop

internal class PowerPressTracker {
    private val pressedDevices = mutableSetOf<Long>()

    fun update(device: Long, isPower: Boolean): Boolean {
        if (!isPower) {
            pressedDevices.remove(device)
            return false
        }
        return pressedDevices.add(device)
    }

    fun remove(device: Long) {
        pressedDevices.remove(device)
    }
}
