package pl.jardol.e2stream

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import android.app.Application
import android.os.Looper
import android.widget.Button
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PhilipsSleepTestTest {
    private class Tv : PhilipsSleepTest.Transport {
        var pin = ""
        var signature = ""
        var keys = 0
        val standbySent = CountDownLatch(1)
        var failGrant = false
        override fun post(path: String, body: JSONObject, authorization: String?): PhilipsSleepTest.Response {
            if (path == "/6/pair/request") {
                assertEquals("control", body.getJSONArray("scope").getString(2))
                return PhilipsSleepTest.Response(200, """{"error_id":"SUCCESS","auth_key":"password","timestamp":12}""")
            }
            if (authorization == null)
                return PhilipsSleepTest.Response(401, "", """Digest realm="tv", nonce="nonce", qop="auth"""")
            val fields = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]+))""").findAll(authorization)
                .associate { it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[3] } }
            fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val user = fields.getValue("username")
            val ha1 = md5("$user:tv:password")
            val ha2 = md5("POST:$path")
            assertEquals(md5("$ha1:nonce:00000001:${fields.getValue("cnonce")}:auth:$ha2"), fields["response"])
            return if (path == "/6/pair/grant") {
                val auth = body.getJSONObject("auth")
                pin = auth.getString("pin")
                signature = auth.getString("auth_signature")
                assertEquals(12L, auth.getLong("auth_timestamp"))
                assertTrue(auth.get("auth_timestamp") is Number)
                if (failGrant) PhilipsSleepTest.Response(200, """{"error_id":"INVALID_PIN"}""")
                else PhilipsSleepTest.Response(200, """{"error_id":"SUCCESS"}""")
            } else {
                assertEquals("/6/input/key", path)
                assertEquals("Standby", body.getString("key"))
                keys++
                standbySent.countDown()
                PhilipsSleepTest.Response(200, "{}")
            }
        }
    }

    @Test fun pairsUsingPinAndSendsAuthenticatedStandby() {
        val tv = Tv()
        val client = PhilipsSleepTest("192.168.7.36", tv)
        client.requestPin()
        client.confirmPin("3456")
        assertEquals("3456", tv.pin)
        assertEquals("OWIxZWI5MWE2YTFlYzg3ODUwYjQwMzJlYzg1MWJlMDFkOGM3Yjk5Mw==", tv.signature)
        client.standby()
        assertEquals(1, tv.keys)
    }

    @Test fun rejectedPinDoesNotAllowStandby() {
        val tv = Tv().apply { failGrant = true }
        val client = PhilipsSleepTest("192.168.7.36", tv)
        client.requestPin()
        try { client.confirmPin("3456"); fail("Expected rejected PIN") } catch (_: IOException) { }
        try { client.standby(); fail("Expected unpaired error") } catch (_: IOException) { }
        assertEquals(0, tv.keys)
    }

    @Test fun rejectsPublicAndMalformedAddressesAndUnsupportedDigest() {
        assertTrue(PhilipsSleepTest.isLocalAddress("192.168.7.36"))
        assertTrue(PhilipsSleepTest.isLocalAddress("127.0.0.1"))
        assertFalse(PhilipsSleepTest.isLocalAddress("example.com"))
        assertFalse(PhilipsSleepTest.isLocalAddress("8.8.8.8"))
        assertFalse(PhilipsSleepTest.isLocalAddress("192.168.1.999"))
        try {
            PhilipsSleepTest.digestAuthorization("""Digest realm="tv",nonce="n",qop="auth-int"""",
                "POST", "/6/input/key", "user", "password")
            fail("Expected unsupported digest")
        } catch (_: IOException) { }
    }

    @Test fun countdownCanBeCancelledAndRecordingBlocksStandby() {
        val tv = Tv()
        val client = PhilipsSleepTest("192.168.7.36", tv)
        client.requestPin()
        client.confirmPin("3456")
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val field = SettingsActivity::class.java.getDeclaredField("philipsSleepTest").apply { isAccessible = true }
        field.set(activity, client)
        val show = SettingsActivity::class.java.getDeclaredMethod("showPhilipsSleepTest").apply { isAccessible = true }
        try {
            AppState.recording = false
            show.invoke(activity)
            TvDialog.top()!!.window!!.decorView.findViewWithTag<Button>("chip_1").performClick()
            shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
            TvDialog.top()!!.onBackPressed()
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
            assertEquals(0, tv.keys)
            show.invoke(activity)
            TvDialog.top()!!.window!!.decorView.findViewWithTag<Button>("chip_1").performClick()
            AppState.recording = true
            shadowOf(Looper.getMainLooper()).idleFor(11, TimeUnit.SECONDS)
            assertEquals(0, tv.keys)
        } finally {
            AppState.recording = false
            TvDialog.dismissAll()
            controller.pause().stop().destroy()
        }
    }

    @Test fun countdownSendsStandbyAfterPairing() {
        val tv = Tv()
        val client = PhilipsSleepTest("192.168.7.36", tv)
        client.requestPin()
        client.confirmPin("3456")
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        SettingsActivity::class.java.getDeclaredField("philipsSleepTest").apply { isAccessible = true }.set(activity, client)
        try {
            AppState.recording = false
            SettingsActivity::class.java.getDeclaredMethod("showPhilipsSleepTest")
                .apply { isAccessible = true }.invoke(activity)
            TvDialog.top()!!.window!!.decorView.findViewWithTag<Button>("chip_1").performClick()
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
            assertTrue(tv.standbySent.await(5, TimeUnit.SECONDS))
            assertEquals(1, tv.keys)
        } finally {
            AppState.recording = false
            TvDialog.dismissAll()
            controller.pause().stop().destroy()
        }
    }
}
