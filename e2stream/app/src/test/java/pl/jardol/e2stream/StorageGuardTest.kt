package pl.jardol.e2stream

import org.junit.Assert.*
import org.junit.Test

class StorageGuardTest {
    @Test
    fun detectsBlockedWritesAndRemembersStallsThatEndBetweenChecks() {
        val health = StorageWriteHealth()
        health.begin(100)
        assertNull(health.storageTrouble(5_100, -1))
        assertNotNull(health.storageTrouble(5_101, 5_000))
        health.end(12_400)
        assertNotNull(health.storageTrouble(15_000, 10_000))
        assertNull(health.storageTrouble(15_000, 13_000))
        assertNull(health.storageTrouble(72_401, -1))
    }

    @Test
    fun needsThreeSlowWritesInAMinuteAndNewEvidenceAfterDroppingABuffer() {
        val health = StorageWriteHealth()
        fun write(end: Long) {
            health.begin(end - 1_001)
            health.end(end)
        }
        write(2_000)
        write(4_000)
        assertNull(health.storageTrouble(4_000, -1))
        write(6_000)
        assertNotNull(health.storageTrouble(6_000, -1))
        assertNull(health.storageTrouble(10_000, 6_001))
        write(11_000)
        assertNotNull(health.storageTrouble(11_000, 6_001))
        assertNull(health.storageTrouble(64_001, -1))
        health.reset()
        assertNull(health.storageTrouble(11_000, -1))
    }

    @Test
    fun protectsActiveRecordingsEvenAfterAWriteErrorAndPrefersFailedSafeBuffers() {
        val guard = StorageGuard()
        val recording = StorageGuard.Background("recording", recording = true, failed = true, priorityTime = 0)
        val oldest = StorageGuard.Background("oldest", recording = false, failed = false, priorityTime = 1)
        val failed = StorageGuard.Background("failed", recording = false, failed = true, priorityTime = 2)
        assertEquals("failed", guard.chooseDrop(10_000, listOf(recording, oldest, failed)))
        assertEquals("oldest", guard.chooseDrop(15_000, listOf(recording, oldest)))
        assertNull(guard.chooseDrop(20_000, listOf(recording)))
        assertTrue(guard.shouldWarn(20_000))
    }

    @Test
    fun rechecksAfterSheddingAndWarnsForCurrentOnlyWithoutSpamming() {
        val guard = StorageGuard()
        val backgrounds = listOf("bg1", "bg2").mapIndexed { i, name ->
            StorageGuard.Background(name, recording = false, failed = false, priorityTime = i.toLong())
        }
        val current = StorageWriteHealth().apply { begin(1_000); end(9_000) }
        assertNotNull(current.storageTrouble(10_000, guard.lastDropAt))
        assertEquals("bg1", guard.chooseDrop(10_000, backgrounds))
        assertFalse(guard.shouldWarn(10_000))
        assertNull(current.storageTrouble(15_000, guard.lastDropAt))

        current.begin(15_001)
        assertNotNull(current.storageTrouble(25_000, guard.lastDropAt))
        assertEquals("bg2", guard.chooseDrop(25_000, backgrounds.drop(1)))
        assertFalse(guard.shouldWarn(25_000))
        assertNotNull(current.storageTrouble(30_000, guard.lastDropAt))
        assertNull(guard.chooseDrop<String>(30_000, emptyList()))
        assertTrue(guard.shouldWarn(30_000))
        assertFalse(guard.shouldWarn(89_999))
        assertTrue(guard.shouldWarn(90_000))
    }
}
