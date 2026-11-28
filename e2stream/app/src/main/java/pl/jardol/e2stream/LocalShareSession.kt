package pl.jardol.e2stream

import android.app.Activity
import android.app.Dialog
import java.io.IOException

internal class LocalShareSession private constructor(
    private val activity: Activity,
    private val onFinished: () -> Unit,
) {
    private var server: LocalShareServer? = null
    private var dialog: Dialog? = null

    fun stop() {
        server?.stop()
        dialog?.dismiss()
        dialog = null
    }

    companion object {
        fun start(activity: Activity, entries: List<LocalShareServer.Entry>, warning: Int,
            onFinished: () -> Unit = {}): LocalShareSession? {
            val address = try { PhilipsSleepTest.deviceAddress() } catch (e: java.net.SocketException) {
                AppLog.i("local share: no network (${e.javaClass.simpleName})")
                ""
            }
            if (address.isEmpty()) return null
            val session = LocalShareSession(activity, onFinished)
            val server = try {
                LocalShareServer(address, activity.getString(R.string.share_title), entries) {
                    onFinished()
                    activity.runOnUiThread {
                        session.dialog?.dismiss()
                        session.dialog = null
                        session.server = null
                    }
                }
            } catch (e: IOException) {
                AppLog.i("local share: cannot start (${e.javaClass.simpleName})")
                return null
            }
            session.server = server
            session.dialog = LinkDialog.show(activity, activity.getString(R.string.share_title),
                activity.getString(warning, server.url), server.url) { session.stop() }
            server.start()
            return session
        }
    }
}
