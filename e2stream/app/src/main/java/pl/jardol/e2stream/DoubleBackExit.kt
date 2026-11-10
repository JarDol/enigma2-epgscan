package pl.jardol.e2stream

internal enum class TimeshiftBackAction { SHOW_CHANNEL_BANNER, HIDE_BANNER, GO_LIVE, EXIT }

internal fun timeshiftBackAction(
    bannerVisible: Boolean,
    channelBanner: Boolean,
    buffered: Boolean,
    shifted: Boolean,
    backToLive: Boolean,
): TimeshiftBackAction = when {
    bannerVisible && !channelBanner -> TimeshiftBackAction.SHOW_CHANNEL_BANNER
    bannerVisible -> TimeshiftBackAction.HIDE_BANNER
    buffered && shifted && backToLive -> TimeshiftBackAction.GO_LIVE
    else -> TimeshiftBackAction.EXIT
}

internal class DoubleBackExit {
    private var lastPress: Long? = null

    fun press(now: Long): Boolean {
        val previous = lastPress
        lastPress = now
        return previous != null && now - previous in 0 until 2500
    }

    fun reset() {
        lastPress = null
    }
}
