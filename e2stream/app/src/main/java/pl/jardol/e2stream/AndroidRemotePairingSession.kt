package pl.jardol.e2stream

import android.os.Handler
import android.os.Looper

internal class AndroidRemotePairingSession(
    address: String,
    val client: AndroidTvRemoteTest,
    texts: PhilipsPairingServer.Texts,
) {
    private val ui = Handler(Looper.getMainLooper())
    private var listener: ((Boolean) -> Unit)? = null
    private var result: Boolean? = null
    private var cancelled = false
    private val server = PhilipsPairingServer(address, client, texts, onPaired = {}, onFinished = { success ->
        ui.post {
            if (!cancelled) {
                result = success
                listener?.invoke(success)
            }
        }
    })
    val url: String get() = server.url

    fun start() = server.start()

    fun attach(onFinished: (Boolean) -> Unit) {
        listener = onFinished
        result?.let(onFinished)
    }

    fun detach() { listener = null }

    fun stop() {
        cancelled = true
        listener = null
        server.stop()
    }
}
