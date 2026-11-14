package pl.jardol.e2stream.desktop

internal class RemoteDiagnostics(
    enabled: Boolean,
    private val write: (String) -> Unit = { AppLog.i(it) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Śledzenie klawiszy pilota w logu aplikacji (ustawienie albo E2STREAM_HID_TRACE=1). */
    @Volatile var enabled: Boolean = enabled

    fun log(message: () -> String) {
        if (enabled) write("[pilot ${nowMillis()}] ${message()}")
    }

    companion object {
        fun fromEnvironment(): RemoteDiagnostics =
            RemoteDiagnostics(System.getenv("E2STREAM_HID_TRACE") == "1")
    }
}
