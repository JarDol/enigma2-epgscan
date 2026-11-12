package pl.jardol.e2stream.desktop

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class UniversalRemoteTest {
    @Test
    fun assigningPhysicalKeyMovesItToOneVirtualButtonAndSurvivesSerialization() {
        val upKey = UniversalRemoteMap.keyId(KeyEvent.VK_UP)
        val assigned = UniversalRemoteMap.assign(
            UniversalRemoteMap.defaults,
            VirtualRemoteKey.BACK,
            upKey,
        )

        assertEquals(VirtualRemoteKey.BACK, UniversalRemoteMap.findKey(assigned, upKey))
        assertEquals(assigned, UniversalRemoteMap.parse(UniversalRemoteMap.encode(assigned)))
    }

    @Test
    fun assigningMultipleKeysKeepsBothAliasesOnTheSameButton() {
        val bindings = UniversalRemoteMap.assign(
            UniversalRemoteMap.assign(
                UniversalRemoteMap.defaults,
                VirtualRemoteKey.INFO,
                UniversalRemoteMap.keyId(KeyEvent.VK_I),
            ),
            VirtualRemoteKey.INFO,
            UniversalRemoteMap.keyId(KeyEvent.VK_F2),
        )

        assertEquals(
            setOf(UniversalRemoteMap.keyId(KeyEvent.VK_I), UniversalRemoteMap.keyId(KeyEvent.VK_F2)),
            bindings[VirtualRemoteKey.INFO],
        )
    }

    @Test
    fun powerBindingSurvivesSerializationAndCanBeReassigned() {
        val id = UniversalRemoteMap.hidId("test-device", byteArrayOf(0x20, 0, 0))
        val assigned = UniversalRemoteMap.assign(UniversalRemoteMap.defaults, VirtualRemoteKey.POWER, id)
        val restored = UniversalRemoteMap.parse(UniversalRemoteMap.encode(assigned))
        assertEquals(VirtualRemoteKey.POWER, UniversalRemoteMap.findKey(restored, id))
        val moved = UniversalRemoteMap.assign(restored, VirtualRemoteKey.BACK, id)
        assertEquals(emptySet(), moved[VirtualRemoteKey.POWER])
        assertEquals(VirtualRemoteKey.BACK, UniversalRemoteMap.findKey(moved, id))
    }
}
