package pl.jardol.e2stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class DecoderProbeTest {
    @Test
    fun treatsHttpErrorAsReachableWhenDecoderResponds() {
        val server = ServerSocket(0)
        val responder = thread {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                while (input.readLine()?.isNotEmpty() == true) { }
                socket.getOutputStream().write(
                    "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                )
            }
            server.close()
        }

        val result = DecoderProbe.check(listOf(DecoderProbe.Endpoint("http://127.0.0.1:${server.localPort}/api/about")), 1000)
        responder.join(2000)

        assertTrue(result.reachable)
        assertTrue(result.detail.contains("503"))
    }

    @Test
    fun reportsUnreachableWhenConnectionIsRefused() {
        val server = ServerSocket(0)
        val port = server.localPort
        server.close()

        val result = DecoderProbe.check(listOf(DecoderProbe.Endpoint("http://127.0.0.1:$port/api/about")), 500)

        assertFalse(result.reachable)
    }
}
