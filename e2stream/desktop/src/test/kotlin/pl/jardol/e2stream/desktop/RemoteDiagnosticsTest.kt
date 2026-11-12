package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteDiagnosticsTest {
    @Test
    fun disabledDiagnosticsDoNotEvaluateOrWriteMessages() {
        val messages = mutableListOf<String>()
        val diagnostics = RemoteDiagnostics(enabled = false, write = messages::add)
        diagnostics.log { error("Disabled diagnostics must not evaluate messages") }
        assertEquals(emptyList(), messages)
    }

    @Test
    fun enabledDiagnosticsIncludeTimestampAndMessage() {
        val messages = mutableListOf<String>()
        val diagnostics = RemoteDiagnostics(enabled = true, write = messages::add, nowMillis = { 123L })
        diagnostics.log { "source=hid queueMs=12" }
        assertEquals(listOf("[pilot 123] source=hid queueMs=12"), messages)
    }
}
