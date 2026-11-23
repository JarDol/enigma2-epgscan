package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipActionsTest {
    @Test fun actionsFollowMainAndPipState() {
        assertEquals(ChannelPipAction.ADD, PipActions.channel(true, false, false))
        assertEquals(ChannelPipAction.CLOSE, PipActions.channel(true, false, true))
        assertEquals(ChannelPipAction.MENU, PipActions.channel(true, true, false))
        assertEquals(ChannelPipAction.NONE, PipActions.channel(false, false, false))
    }

    @Test fun fullPipHasNoFreeSlotAndPreferredSlotIsCheckedAgain() {
        assertNull(PipActions.freeSlot(setOf(0, 1, 2), 3))
        assertEquals(2, PipActions.freeSlot(setOf(0, 1), 3, 1))
        assertEquals(1, PipActions.freeSlot(setOf(0), 3, 1))
    }

    @Test fun closingPipKeepsActiveRecordingAndParkedBuffer() {
        assertFalse(PipActions.dropBufferOnClose(0, false, true))
        assertFalse(PipActions.dropBufferOnClose(0, true, false))
        assertFalse(PipActions.dropBufferOnClose(1, false, false))
        assertTrue(PipActions.dropBufferOnClose(0, false, false))
    }
}
