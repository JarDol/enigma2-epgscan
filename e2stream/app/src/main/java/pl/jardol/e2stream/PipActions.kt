package pl.jardol.e2stream

internal enum class ChannelPipAction { ADD, CLOSE, MENU, NONE }

internal object PipActions {
    fun channel(enabled: Boolean, isMain: Boolean, isPip: Boolean): ChannelPipAction = when {
        !enabled -> ChannelPipAction.NONE
        isPip -> ChannelPipAction.CLOSE
        isMain -> ChannelPipAction.MENU
        else -> ChannelPipAction.ADD
    }

    fun freeSlot(occupied: Set<Int>, max: Int, preferred: Int = -1): Int? =
        preferred.takeIf { it in 0 until max && it !in occupied }
            ?: (0 until max).firstOrNull { it !in occupied }

    fun dropBufferOnClose(bufferCount: Int, parked: Boolean, recording: Boolean): Boolean =
        bufferCount == 0 && !parked && !recording
}
