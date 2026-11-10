package pl.jardol.e2stream

internal fun timeshiftNavigationOnBanner(visible: Boolean, channelBanner: Boolean): Boolean =
    visible && !channelBanner
