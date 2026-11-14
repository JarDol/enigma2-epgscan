package pl.jardol.e2stream.desktop

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MdnsDiscoveryTest {
    private fun name(out: ByteArrayOutputStream, value: String) {
        for (label in value.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
    }

    private fun record(out: ByteArrayOutputStream, owner: String, type: Int, data: ByteArray) {
        name(out, owner)
        out.write(byteArrayOf(0, type.toByte(), 0x80.toByte(), 1, 0, 0, 0x11, 0x94.toByte()))
        out.write(data.size shr 8)
        out.write(data.size and 0xff)
        out.write(data)
    }

    private fun bytes(block: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(block).toByteArray()

    @Test
    fun parsesPtrSrvTxtAndAddress() {
        val instance = "EPG Scan na vuuno4kse._epgscan._tcp.local"
        val packet = bytes { out ->
            out.write(byteArrayOf(0x12, 0x34, 0x84.toByte(), 0, 0, 0, 0, 4, 0, 0, 0, 0))
            record(out, "_epgscan._tcp.local", 12, bytes { name(it, instance) })
            record(out, instance, 33, bytes { d ->
                d.write(byteArrayOf(0, 0, 0, 0, 0x22, 0x42)) // priorytet, waga, port 8770
                name(d, "vuuno4kse.local")
            })
            record(out, instance, 16, bytes { d ->
                for (entry in listOf("api=1", "webif-port=80", "stream-port=8001")) {
                    d.write(entry.length)
                    d.write(entry.toByteArray())
                }
            })
            record(out, "vuuno4kse.local", 1, byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
        }
        val services = MdnsDiscovery.parseResponse(packet, "192.168.1.99")
        assertEquals(1, services.size)
        val service = services[0]
        assertEquals("192.168.1.20", service.ip)
        assertEquals(8770, service.port)
        assertEquals(80, service.webifPort)
        assertEquals(8001, service.streamPort)
        assertEquals("epg scan na vuuno4kse", service.name)
    }

    @Test
    fun queriesAndGarbageAreIgnored() {
        assertTrue(MdnsDiscovery.parseResponse(MdnsDiscovery.query(MdnsDiscovery.SERVICE), null).isEmpty())
        assertTrue(MdnsDiscovery.parseResponse(byteArrayOf(1, 2, 3), null).isEmpty())
    }
}
