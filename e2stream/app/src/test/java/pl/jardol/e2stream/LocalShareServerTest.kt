package pl.jardol.e2stream

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalShareServerTest {
    @get:Rule val folder = TemporaryFolder()
    private fun get(url: String): Pair<Int, ByteArray> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        return try {
            val status = connection.responseCode
            status to (if (status == 200) connection.inputStream else connection.errorStream).use { it.readBytes() }
        } finally {
            connection.disconnect()
        }

    }

    @Test fun recordingStreamsAllPartsInOrder() {
        val a = folder.newFile("part-1.ts").apply { writeBytes(byteArrayOf(1, 2)) }
        val b = folder.newFile("part-2.ts").apply { writeBytes(byteArrayOf(3, 4, 5)) }
        val entry = LocalShareServer.recording("recording.ts", listOf(a, b))
        assertEquals(5L, entry.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), entry.open().use { it.readBytes() })
    }

    @Test fun onlyExplicitFilesAreAvailableAtRandomUrl() {
        val first = "private log".toByteArray()
        val second = "settings".toByteArray()
        val finished = CountDownLatch(1)
        val server = LocalShareServer("127.0.0.1", "Test", listOf(
            LocalShareServer.Entry("log.txt", first.size.toLong()) { first.inputStream() },
            LocalShareServer.Entry("settings.json", second.size.toLong()) { second.inputStream() },
        )) { finished.countDown() }
        server.start()
        try {
            assertEquals(404, get(server.url.substringBeforeLast('/') + "/wrong/0").first)
            assertEquals(404, get(server.url + "/../../settings.json").first)
            assertEquals(404, get(server.url + "/2").first)
            val page = get(server.url)
            assertEquals(200, page.first)
            assertTrue(String(page.second).contains("settings.json"))
            assertArrayEquals(first, get(server.url + "/0").second)
            assertArrayEquals(second, get(server.url + "/1").second)
        } finally {
            server.stop()
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
    }
}
