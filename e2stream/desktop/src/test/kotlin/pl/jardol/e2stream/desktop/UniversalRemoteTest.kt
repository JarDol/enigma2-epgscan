package pl.jardol.e2stream.desktop

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun activateKeyDoesNotTakeTheKeyFromOtherButtons() {
        val id = UniversalRemoteMap.hidId("test-device", byteArrayOf(0x20, 0, 0))
        val power = UniversalRemoteMap.assign(UniversalRemoteMap.defaults, VirtualRemoteKey.POWER, id)
        val both = UniversalRemoteMap.assign(power, VirtualRemoteKey.ACTIVATE, id)
        assertEquals(setOf(id), both[VirtualRemoteKey.POWER])
        assertEquals(setOf(id), both[VirtualRemoteKey.ACTIVATE])
        assertEquals(VirtualRemoteKey.POWER, UniversalRemoteMap.findKey(both, id))
        val moved = UniversalRemoteMap.assign(both, VirtualRemoteKey.BACK, id)
        assertEquals(setOf(id), moved[VirtualRemoteKey.ACTIVATE])
        assertEquals(emptySet(), moved[VirtualRemoteKey.POWER])
        assertEquals("Pilot HID · 20", UniversalRemoteMap.label(id))
    }

    @Test
    fun backgroundActivationAcceptsOnlyConfiguredHidCollectionsAndIr() {
        val mapped = UniversalRemoteMap.hidId("remote", byteArrayOf(1))
        val anotherButton = UniversalRemoteMap.hidId("remote", byteArrayOf(2))
        val keyboardHid = UniversalRemoteMap.hidId("keyboard", byteArrayOf(2))
        val bindings = UniversalRemoteMap.assign(UniversalRemoteMap.defaults, VirtualRemoteKey.OK, mapped)
        val devices = UniversalRemoteMap.configuredHidDevices(bindings)
        assertTrue(UniversalRemoteMap.activatesFromBackground(anotherButton, emptySet(), true, devices))
        assertFalse(UniversalRemoteMap.activatesFromBackground(keyboardHid, emptySet(), true, devices))
        assertFalse(UniversalRemoteMap.activatesFromBackground(anotherButton, emptySet(), false, devices))
        assertTrue(UniversalRemoteMap.activatesFromBackground(keyboardHid, setOf(keyboardHid), false, devices))
        assertTrue(UniversalRemoteMap.activatesFromBackground("L:remote:OK", emptySet(), true, devices))
        assertFalse(UniversalRemoteMap.activatesFromBackground("L:remote:OK", emptySet(), false, devices))
    }

    @Test
    fun keyboardCannotActivateInBackgroundEvenWhenExplicitlyAssigned() {
        val keyboardKey = UniversalRemoteMap.keyId(KeyEvent.VK_S)
        assertFalse(UniversalRemoteMap.activatesFromBackground(keyboardKey, setOf(keyboardKey), true, emptySet()))
        assertFalse(UniversalRemoteMap.isPowerOrActivationKey(keyboardKey, false, setOf(keyboardKey), setOf(keyboardKey), true, emptySet()))
        assertTrue(UniversalRemoteMap.isPowerOrActivationKey(keyboardKey, true, setOf(keyboardKey), emptySet(), true, emptySet()))
        val hid = UniversalRemoteMap.hidId("remote", byteArrayOf(1))
        assertTrue(UniversalRemoteMap.isPowerOrActivationKey(hid, false, setOf(hid), emptySet(), false, emptySet()))
    }
}
