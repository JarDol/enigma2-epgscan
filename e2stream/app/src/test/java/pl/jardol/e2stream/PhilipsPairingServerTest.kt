package pl.jardol.e2stream

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PhilipsPairingServerTest {
    private class Tv : PhilipsSleepTest.Transport {
        var requests = 0
        var grants = 0
        override fun post(path: String, body: JSONObject, authorization: String?): PhilipsSleepTest.Response {
            if (path == "/6/pair/request") {
                requests++
                return PhilipsSleepTest.Response(200, """{"error_id":"SUCCESS","auth_key":"password","timestamp":12}""")
            }
            assertEquals("/6/pair/grant", path)
            if (authorization == null)
                return PhilipsSleepTest.Response(401, "", """Digest realm="tv", nonce="nonce", qop="auth"""")
            grants++
            assertEquals("3456", body.getJSONObject("auth").getString("pin"))
            return PhilipsSleepTest.Response(200, """{"error_id":"SUCCESS"}""")
        }
    }

    private val texts = PhilipsPairingServer.Texts("Test", "Show PIN", "Enter PIN", "Pair", "Retry", "Error", "Paired")

    private fun request(url: String, body: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        if (body != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val status = conn.responseCode
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        conn.disconnect()
        return status to text
    }

    @Test fun phoneRequestsPinAndPairsWithoutClosingTvPrompt() {
        val tv = Tv()
        val paired = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val server = PhilipsPairingServer("127.0.0.1", PhilipsSleepTest("127.0.0.1", tv), texts,
            onPaired = { paired.countDown() }, onFinished = { finished.countDown() })
        server.start()
        try {
            assertEquals(404, request(server.url.substringBeforeLast('/') + "/wrong").first)
            assertEquals(200, request(server.url).first)
            assertEquals(0, tv.requests)
            assertEquals(405, request(server.url + "/start").first)
            assertTrue(request(server.url + "/start", "").second.contains("Enter PIN"))
            assertEquals(1, tv.requests)
            assertEquals(200, request(server.url + "/pin", "pin=bad").first)
            assertEquals(0, tv.grants)
            val result = request(server.url + "/pin", "pin=3456")
            assertEquals(200, result.first)
            assertTrue(result.second.contains("Paired"))
            assertFalse(result.second.contains("3456"))
            assertTrue(paired.await(5, TimeUnit.SECONDS))
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(1, tv.grants)
        } finally {
            server.stop()
        }
    }

    @Test fun cancellationClosesServerWithoutPairing() {
        val tv = Tv()
        val finished = CountDownLatch(1)
        var paired = false
        val server = PhilipsPairingServer("127.0.0.1", PhilipsSleepTest("127.0.0.1", tv), texts,
            onPaired = { paired = true }, onFinished = { finished.countDown() })
        server.start()
        server.stop()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertFalse(paired)
        assertEquals(0, tv.requests)
    }

    @Test fun cancelDoesNotBlockCallerAndClosesActiveTargetOffThread() {
        val requested = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val callingThread = Thread.currentThread()
        var closeThread: Thread? = null
        var paired = false
        val target = object : PhilipsPairingServer.PairingTarget {
            override val pinPattern = Regex("[0-9]{4}")
            override fun requestPin() {
                requested.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            override fun confirmPin(pin: String) = Unit
            override fun cancel() {
                closeThread = Thread.currentThread()
                cancelled.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val server = PhilipsPairingServer("127.0.0.1", target, texts,
            onPaired = { paired = true }, onFinished = { finished.countDown() })
        val executor = Executors.newSingleThreadExecutor()
        server.start()
        try {
            val request = executor.submit {
                try { request(server.url + "/start", "") } catch (_: java.io.IOException) { }
            }
            assertTrue(requested.await(5, TimeUnit.SECONDS))
            server.stop()
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertNotEquals(callingThread, closeThread)
            release.countDown()
            request.get(5, TimeUnit.SECONDS)
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertFalse(paired)
        } finally {
            release.countDown()
            server.stop()
            executor.shutdownNow()
        }
    }
}
