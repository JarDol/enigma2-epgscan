package pl.jardol.e2stream.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackgroundStreamingTest {
    @Test
    fun backgroundStreamSurvivesOtherSessionClosingAndCanBeReadAfterPromotion() {
        val directory = Files.createTempDirectory("e2stream-background-stream").toFile()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val data = ByteArray(TimeshiftBuffer.PACKET * 200) { index ->
            if (index % TimeshiftBuffer.PACKET == 0) 0x47 else 0
        }
        server.executor = executor
        server.createContext("/stream") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { out ->
                    repeat(100) {
                        out.write(data)
                        out.flush()
                        Thread.sleep(25)
                    }
                }
            } catch (_: Exception) {
                exchange.close()
            }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/stream"
        val first = TimeshiftSession(TimeshiftBuffer(directory, 10, reserveBytes = 0), url, null)
        val second = TimeshiftSession(TimeshiftBuffer(directory, 10, reserveBytes = 0), url, null)
        try {
            val deadline = System.currentTimeMillis() + 8_000
            while ((first.buffer.total == 0L || second.buffer.total == 0L) && System.currentTimeMillis() < deadline) Thread.sleep(25)
            assertTrue(first.buffer.total > 0 && second.buffer.total > 0)
            val backgrounds = BackgroundBuffers<TimeshiftSession> { it.buffer.isRecording }
            backgrounds.park("first", first, 1)
            second.close()
            val resumed = backgrounds.take("first")!!
            val connection = URI(resumed.urlAt(resumed.buffer.oldestWall)).toURL().openConnection().apply {
                connectTimeout = 2_000
                readTimeout = 2_000
            }
            connection.getInputStream().use { assertEquals(0x47, it.read()) }
        } finally {
            first.close()
            second.close()
            server.stop(0)
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }
}
