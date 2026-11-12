package pl.jardol.e2stream.desktop

internal enum class ExitConfirmationAction { CONFIRM, CANCEL, IGNORE }

internal fun exitConfirmationAction(key: VirtualRemoteKey?): ExitConfirmationAction = when (key) {
    VirtualRemoteKey.OK -> ExitConfirmationAction.CONFIRM
    VirtualRemoteKey.BACK -> ExitConfirmationAction.CANCEL
    else -> ExitConfirmationAction.IGNORE
}
