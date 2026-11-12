package pl.jardol.e2stream.desktop

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class SingleInstanceTest {
    @Test
    fun secondLaunchSignalsExistingInstanceInsteadOfStartingAnother() {
        val directory = Files.createTempDirectory("e2stream-instance-test")
        val lockPath = directory.resolve("instance.lock")
        val requestReceived = CountDownLatch(1)
        val primary = SingleInstance.startOrSignal(lockPath)
        assertNotNull(primary)
        primary.onPowerRequest(requestReceived::countDown)
        try {
            assertNull(SingleInstance.startOrSignal(lockPath))
            kotlin.test.assertTrue(requestReceived.await(2, TimeUnit.SECONDS))
        } finally {
            primary.close()
            Files.deleteIfExists(lockPath)
            Files.deleteIfExists(directory)
        }
    }
}
