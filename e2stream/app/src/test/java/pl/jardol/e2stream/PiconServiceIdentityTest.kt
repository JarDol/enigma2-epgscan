package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PiconServiceIdentityTest {
    @Test
    fun matchesSameServiceAcrossDifferentServiceTypes() {
        assertEquals(
            piconServiceIdentity("1:0:1:1234:5678:9ABC:00000000:0:0:0:"),
            piconServiceIdentity("1:0:19:1234:5678:9ABC:00000000:0:0:0:"),
        )
    }

    @Test
    fun keepsTransportAndNamespaceInIdentity() {
        val ref = "1:0:1:1234:5678:9ABC:00000000:0:0:0:"
        assertNotEquals(
            piconServiceIdentity(ref),
            piconServiceIdentity("1:0:1:1234:5679:9ABC:00000000:0:0:0:"),
        )
        assertNotEquals(
            piconServiceIdentity(ref),
            piconServiceIdentity("1:0:1:1234:5678:9ABC:00000001:0:0:0:"),
        )
        assertNull(piconServiceIdentity("1:0:1:1234:5678:"))
    }
}
