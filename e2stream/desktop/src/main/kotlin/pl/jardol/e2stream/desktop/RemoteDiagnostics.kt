package pl.jardol.e2stream.desktop

internal class RemoteDiagnostics(
    private val enabled: Boolean,
    private val write: (String) -> Unit = System.err::println,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun log(message: () -> String) {
        if (enabled) write("[pilot ${nowMillis()}] ${message()}")
    }

    companion object {
        fun fromEnvironment(): RemoteDiagnostics =
            RemoteDiagnostics(System.getenv("E2STREAM_HID_TRACE") == "1")
    }
}
