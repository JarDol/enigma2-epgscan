package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DiscoveryTest {
    private class Server(code: Int, body: String, requests: MutableList<Map<String, String>>) {
        private val socket = ServerSocket(0)
        val port = socket.localPort
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 2000
                        val reader = client.getInputStream().bufferedReader()
                        reader.readLine()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val split = line.indexOf(':')
                            if (split > 0) headers[line.substring(0, split)] = line.substring(split + 1).trim()
                        }
                        synchronized(requests) { requests.add(headers) }
                        val bytes = body.toByteArray()
                        val output = client.getOutputStream()
                        output.write("HTTP/1.1 $code response\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(bytes)
                        output.flush()
                    }
                } catch (e: SocketException) {
                    if (!socket.isClosed) throw e
                }
            }
        }
        fun stop() { socket.close(); worker.join(3000) }
    }

    private fun server(code: Int, body: String, requests: MutableList<Map<String, String>> = mutableListOf()) =
        Server(code, body, requests)

    @Test
    fun subnetIsBoundedAndUsesNetworkAndBroadcastCorrectly() {
        val subnet = Discovery.subnetHosts("192.168.10.65/30")!!
        assertEquals(listOf("192.168.10.65", "192.168.10.66"), subnet)
        assertEquals(254, Discovery.subnetHosts("10.2.3.8/24")!!.size)
        for (invalid in listOf("10.2.3.0/23", "10.2.3.0/31", "10.2.3.256/24", "host/24",
            "127.0.0.1/24", "10.2.3.0", "224.1.1.1/24")) {
            assertNull(invalid, Discovery.subnetHosts(invalid))
        }
    }

    @Test
    fun confirmsOpenWebifAndReadsPluginOnCustomPortWithoutCredentials() {
        val requests = mutableListOf<Map<String, String>>()
        val web = server(200, """{"info":{"webifver":"2.0","enigmaver":"1","brand":"Vu","model":"Duo"}}""", requests)
        val plugin = server(200, """{"name":"EPG Scan","api":1,"version":"2.18","model":"Duo"}""", requests)
        try {
            val receiver = Discovery.probe("127.0.0.1", web.port, plugin.port)!!
            assertTrue(receiver.verified)
            assertTrue(receiver.description.contains("Vu Duo"))
            assertTrue(receiver.description.contains("EPG Scan 2.18"))
            assertEquals(plugin.port, receiver.pluginPort)
            val captured = synchronized(requests) { requests.toList() }
            assertEquals(2, captured.size)
            for (headers in captured) {
                assertFalse(headers.keys.any { it.equals("Authorization", ignoreCase = true) })
                assertFalse(headers.keys.any { it.equals("X-E2S-Token", ignoreCase = true) })
            }
        } finally {
            web.stop()
            plugin.stop()
        }
    }

    @Test
    fun arbitraryWebServicesAreNotPresentedAsConfirmedReceivers() {
        val unrelated = server(200, """{"info":{"model":"NAS"}}""")
        val login = server(401, "")
        val plugin = server(401, "")
        try {
            assertNull(Discovery.probe("127.0.0.1", unrelated.port, plugin.port))
            val unverified = Discovery.probe("127.0.0.1", login.port, plugin.port)
            assertNotNull(unverified)
            assertFalse(unverified!!.verified)
        } finally {
            unrelated.stop()
            login.stop()
            plugin.stop()
        }
    }

    @Test
    fun mdnsPluginCanConfirmReceiverWithoutOpenWebifAndCarriesPorts() {
        val web = server(404, "")
        val plugin = server(200, """{"name":"EPG Scan","api":1,"version":"2.18","model":"Box"}""")
        try {
            val receiver = Discovery.probe("127.0.0.1", web.port, plugin.port, 8002, advertised = true)!!
            assertTrue(receiver.verified)
            assertTrue(receiver.viaMdns)
            assertEquals(8002, receiver.streamPort)
            assertEquals(plugin.port, receiver.pluginPort)
        } finally {
            web.stop()
            plugin.stop()
        }
    }

    @Test
    fun mergesMdnsWithSubnetResultAndPreservesAdvertisedPorts() {
        val local = FoundReceiver("10.1.1.2", 80, null, null, "Vu Duo", true, false)
        val mdns = FoundReceiver("10.1.1.2", 80, 9876, 8002, "", false, true)
        val otherPort = FoundReceiver("10.1.1.2", 8080, null, null, "", false, false)
        val result = Discovery.merge(listOf(local, mdns, otherPort))
        assertEquals(2, result.size)
        assertTrue(result[0].verified)
        assertTrue(result[0].viaMdns)
        assertEquals("Vu Duo", result[0].description)
        assertEquals(9876, result[0].pluginPort)
        assertEquals(8002, result[0].streamPort)
    }

    @Test
    fun validatesAdvertisedPorts() {
        val attributes = mapOf("webif-port" to "8080".toByteArray(), "stream-port" to "65536".toByteArray())
        assertEquals(8080, MdnsDiscovery.portAttribute(attributes, "webif-port"))
        assertNull(MdnsDiscovery.portAttribute(attributes, "stream-port"))
        assertNull(MdnsDiscovery.portAttribute(attributes, "missing"))
    }
}
