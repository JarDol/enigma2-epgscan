package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class ExitConfirmationTest {
    @Test
    fun onlyOkAndBackActOnConfirmation() {
        assertEquals(ExitConfirmationAction.CONFIRM, exitConfirmationAction(VirtualRemoteKey.OK))
        assertEquals(ExitConfirmationAction.CANCEL, exitConfirmationAction(VirtualRemoteKey.BACK))
        VirtualRemoteKey.entries.filter { it != VirtualRemoteKey.OK && it != VirtualRemoteKey.BACK }.forEach {
            assertEquals(ExitConfirmationAction.IGNORE, exitConfirmationAction(it))
        }
        assertEquals(ExitConfirmationAction.IGNORE, exitConfirmationAction(null))
    }
}
