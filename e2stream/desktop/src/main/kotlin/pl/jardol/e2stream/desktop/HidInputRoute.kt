package pl.jardol.e2stream.desktop

internal enum class HidInputRoute { POWER, LOCAL, IGNORE }

internal fun hidInputRoute(isPower: Boolean, isApplicationFocused: Boolean): HidInputRoute =
    when {
        isPower -> HidInputRoute.POWER
        isApplicationFocused -> HidInputRoute.LOCAL
        else -> HidInputRoute.IGNORE
    }
