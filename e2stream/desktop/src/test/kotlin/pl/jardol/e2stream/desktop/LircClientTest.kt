package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LircClientTest {
    @Test
    fun parsesWinLircLineAndBindsButtonToVirtualKey() {
        val event = parseLircLine("0000000000f40bf0 0a KEY_POWER Samsung:TV\n")!!

        assertEquals(LircEvent(remote = "Samsung:TV", button = "KEY_POWER", repeat = 10), event)
        assertEquals("L:Samsung_TV:KEY_POWER", event.physicalId)
        assertEquals("Pilot IR · Samsung_TV · KEY_POWER", UniversalRemoteMap.label(event.physicalId))

        val bindings = UniversalRemoteMap.assign(UniversalRemoteMap.defaults, VirtualRemoteKey.POWER, event.physicalId)
        assertEquals(VirtualRemoteKey.POWER, UniversalRemoteMap.findKey(bindings, event.physicalId))
        assertEquals(bindings, UniversalRemoteMap.parse(UniversalRemoteMap.encode(bindings)))
    }

    @Test
    fun rejectsIncompleteOrMalformedLines() {
        assertNull(parseLircLine(""))
        assertNull(parseLircLine("0000f40bf0 00 KEY_OK"))
        assertNull(parseLircLine("0000f40bf0 zz KEY_OK remote"))
    }
}
