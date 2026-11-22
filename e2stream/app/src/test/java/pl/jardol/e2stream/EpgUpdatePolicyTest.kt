package pl.jardol.e2stream

import org.junit.Assert.*
import org.junit.Test

class EpgUpdatePolicyTest {
    @Test fun dailyChecksOncePerCalendarDay() {
        assertTrue(EpgUpdatePolicy.shouldCheck(EpgUpdatePolicy.DAILY, false, 999, 1000))
        assertFalse(EpgUpdatePolicy.shouldCheck(EpgUpdatePolicy.DAILY, true, 1000, 1000))
    }

    @Test fun startupChecksOnlyWhenRequested() {
        assertTrue(EpgUpdatePolicy.shouldCheck(EpgUpdatePolicy.STARTUP, true, 1000, 1000))
        assertFalse(EpgUpdatePolicy.shouldCheck(EpgUpdatePolicy.STARTUP, false, 0, 1000))
    }

    @Test fun unchangedPackIsSkippedAutomaticallyButCanBeForced() {
        val pack = EpgUpdatePolicy.packIdentity("decoder", 8770, 100, 1000, "abcd", 500, "picon-sha")
        val rebuilt = EpgUpdatePolicy.packIdentity("decoder", 8770, 200, 2000, "abcd", 500, "picon-sha")
        assertEquals(pack, rebuilt)
        assertFalse(EpgUpdatePolicy.needsImport(rebuilt, pack, false))
        assertTrue(EpgUpdatePolicy.needsImport(rebuilt, pack, true))
        assertTrue(EpgUpdatePolicy.needsImport(
            EpgUpdatePolicy.packIdentity("decoder", 8770, 100, 1000, "ef01", 500, "picon-sha"), pack, false))
        assertTrue(EpgUpdatePolicy.needsImport(
            EpgUpdatePolicy.packIdentity("decoder", 8770, 100, 1000, "abcd", 500, "new-picon-sha"), pack, false))
        assertTrue(EpgUpdatePolicy.needsImport(
            EpgUpdatePolicy.packIdentity("other", 8770, 100, 1000, "abcd", 500, "picon-sha"), pack, false))
    }

    @Test fun oldPluginUsesTimestampAndSize() {
        val pack = EpgUpdatePolicy.packIdentity("decoder", 8770, 100, 1000, "", 500, "")
        assertFalse(EpgUpdatePolicy.needsImport(pack, pack, false))
        assertTrue(EpgUpdatePolicy.needsImport(
            EpgUpdatePolicy.packIdentity("decoder", 8770, 101, 1000, "", 500, ""), pack, false))
        assertTrue(EpgUpdatePolicy.needsImport(
            EpgUpdatePolicy.packIdentity("decoder", 8770, 100, 1001, "", 500, ""), pack, false))
    }

    @Test fun oldPluginDetectsPiconOnlyPackRebuilds() {
        val pack = EpgUpdatePolicy.packIdentity("decoder", 8770, 100, 1000, "same-epg", 500, "")
        val rebuilt = EpgUpdatePolicy.packIdentity("decoder", 8770, 101, 1000, "same-epg", 500, "")
        assertTrue(EpgUpdatePolicy.needsImport(rebuilt, pack, false))
    }
}
