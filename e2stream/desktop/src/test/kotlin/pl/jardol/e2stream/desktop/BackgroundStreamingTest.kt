package pl.jardol.e2stream.desktop

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BackgroundStreamingTest {
    @Test
    fun clientWaitsForNewDataWhenRecordingTemporarilyEmptiesBuffer() {
        val directory = Files.createTempDirectory("e2stream-reader-empty").toFile()
        var now = 1_000L
        val buffer = TimeshiftBuffer(directory, 10, clock = { now }, reserveBytes = 0)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val server = TimeshiftServer(buffer) {
            entered.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
            null
        }
        try {
            val chunk = ByteArray(TimeshiftBuffer.PACKET * 10) { if (it % TimeshiftBuffer.PACKET == 0) 0x47 else 2 }
            buffer.write(chunk, 0, chunk.size)
            val connection = URI(server.url(0, 1)).toURL().openConnection().apply {
                connectTimeout = 2_000
                readTimeout = 5_000
            }
            val input = connection.getInputStream()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(buffer.startRecording(File(directory, "recording"), 0, Long.MAX_VALUE, 100_000) {})
            now += 1_000
            buffer.stopRecording("test")
            proceed.countDown()
            val nextByte = executor.submit<Int> { input.read() }
            Thread.sleep(150)
            assertFalse(nextByte.isDone)
            buffer.write(chunk, 0, chunk.size)
            assertEquals(0x47, nextByte.get(5, TimeUnit.SECONDS))
            input.close()
        } finally {
            proceed.countDown()
            server.close()
            buffer.close()
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun clientContinuesAtNewBufferAfterRecordingMovesOldSegments() {
        val directory = Files.createTempDirectory("e2stream-reader-recording").toFile()
        var now = 1_000L
        val buffer = TimeshiftBuffer(directory, 10, clock = { now }, reserveBytes = 0)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val server = TimeshiftServer(buffer) {
            entered.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
            null
        }
        fun chunk(value: Byte) = ByteArray(TimeshiftBuffer.PACKET * 10) { index ->
            if (index % TimeshiftBuffer.PACKET == 0) 0x47 else value
        }
        try {
            val old = chunk(1)
            buffer.write(old, 0, old.size)
            val connection = URI(server.url(0, 1)).toURL().openConnection().apply {
                connectTimeout = 2_000
                readTimeout = 5_000
            }
            val input = connection.getInputStream()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(buffer.startRecording(File(directory, "recording"), 0, Long.MAX_VALUE, 100_000) {})
            now += 1_000
            buffer.stopRecording("test")
            val next = chunk(2)
            buffer.write(next, 0, next.size)
            proceed.countDown()
            input.use {
                assertEquals(0x47, it.read())
                assertEquals(2, it.read())
            }
        } finally {
            proceed.countDown()
            server.close()
            buffer.close()
            directory.deleteRecursively()
        }
    }

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
            resumed.urlAt(resumed.buffer.oldestWall)
            val base = resumed.baseWall
            resumed.secondaryUrlAt(resumed.buffer.oldestWall)
            assertEquals(base, resumed.baseWall)
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
