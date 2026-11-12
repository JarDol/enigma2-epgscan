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
}
