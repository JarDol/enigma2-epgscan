package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeTestRoutingTest {
    @Test
    fun onlyInteractiveForegroundDiagnosticUsesDirectLaunch() {
        for (test in listOf(false, true)) {
            for (foreground in listOf(false, true)) {
                for (interactive in listOf(false, true)) {
                    assertEquals(test && foreground && interactive,
                        ZapWakeAlarms.testUsesForegroundLaunch(test, foreground, interactive))
                }
            }
        }
    }
}
