package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryTest {
    @Test
    fun repeatedVlcFailuresUseStartupRecoveryOnlyBeforePlayback() {
        val recovery = PlaybackRecovery()
        assertEquals(VlcFailureAction.RETRY, recovery.vlcFailureAction())
        recovery.recordVlcRestart()
        recovery.recordVlcRestart()
        assertEquals(VlcFailureAction.STARTUP_RECOVERY, recovery.vlcFailureAction())

        recovery.markHealthy()
        assertTrue(recovery.hasPlayed)
        assertEquals(VlcFailureAction.PLAYBACK_RECOVERY, recovery.vlcFailureAction())
    }

    @Test
    fun decoderOutageClearsRetryBudgetButRetainsActiveSession() {
        val recovery = PlaybackRecovery()
        recovery.markHealthy()
        recovery.recordVlcRestart()
        recovery.recordVlcRestart()
        recovery.resetVlcRestarts()
        assertTrue(recovery.hasPlayed)
        assertEquals(VlcFailureAction.RETRY, recovery.vlcFailureAction())

        recovery.resetSession()
        assertFalse(recovery.hasPlayed)
        recovery.recordVlcRestart()
        recovery.recordVlcRestart()
        assertEquals(VlcFailureAction.STARTUP_RECOVERY, recovery.vlcFailureAction())
    }
}
