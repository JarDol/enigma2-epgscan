package pl.jardol.e2stream

import android.app.Application
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AndroidRemotePairingLifecycleTest {
    @Test fun activePairingSurvivesSettingsRecreation() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val texts = PhilipsPairingServer.Texts("Test", "Show PIN", "Enter PIN", "Pair", "Retry", "Error", "Paired")
        val protocol = AndroidTvRemoteProtocolTest()
        val (pairServer, remoteServer) = protocol.servers()
        val executor = Executors.newSingleThreadExecutor()
        val pin = ArrayBlockingQueue<String>(1)
        val server = executor.submit {
            (pairServer.accept() as SSLSocket).use { socket ->
                socket.startHandshake()
                pin.put(protocol.pin(socket.session.peerCertificates.first() as X509Certificate,
                    socket.session.localCertificates.first() as X509Certificate))
                fun exchange(request: Int, response: Int) {
                    assertTrue(request in Wire.parse(Wire.read(socket.inputStream)))
                    Wire.write(socket.outputStream,
                        Wire.message { number(1, 2); number(2, 200); bytes(response, byteArrayOf()) })
                }
                exchange(10, 11)
                exchange(20, 20)
                exchange(30, 31)
                exchange(40, 41)
            }
        }
        val session = AndroidRemotePairingSession("127.0.0.1",
            AndroidTvRemoteTest("127.0.0.1", pairServer.localPort, remoteServer.localPort), texts)
        val field = SettingsActivity::class.java.getDeclaredField("androidRemoteSession").apply { isAccessible = true }
        val clientField = SettingsActivity::class.java.getDeclaredField("androidRemoteTest").apply { isAccessible = true }
        val show = SettingsActivity::class.java.getDeclaredMethod("showAndroidRemotePairing",
            AndroidRemotePairingSession::class.java).apply { isAccessible = true }
        fun post(path: String, body: String): String {
            val conn = URL(session.url + path).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            return try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally { conn.disconnect() }
        }
        try {
            val before = controller.get()
            field.set(before, session)
            show.invoke(before, session)
            session.start()
            val start = post("/start", "")
            if (!start.contains("Enter PIN")) server.get(5, TimeUnit.SECONDS)
            assertTrue(start, start.contains("Enter PIN"))
            val code = pin.poll(5, TimeUnit.SECONDS) ?: error("No pairing PIN")
            controller.recreate()
            val after = controller.get()
            assertNotSame(before, after)
            assertSame(session, field.get(after))
            assertTrue(TvDialog.top()!!.isShowing)
            assertTrue(post("/pin", "pin=$code").contains("Paired"))
            server.get(5, TimeUnit.SECONDS)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (clientField.get(after) == null && System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertSame(session.client, clientField.get(after))
            assertEquals(null, field.get(after))
        } finally {
            controller.pause().stop().destroy()
            session.stop()
            TvDialog.dismissAll()
            pairServer.close()
            remoteServer.close()
            executor.shutdownNow()
        }
    }
}
