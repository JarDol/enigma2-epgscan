package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackgroundBuffersTest {
    private data class Buffer(val name: String, var recording: Boolean = false, var pip: Boolean = false)

    @Test
    fun evictsOldestAndPromotesWithoutClosingIt() {
        val buffers = BackgroundBuffers<Buffer> { it.recording }
        val first = Buffer("first")
        val second = Buffer("second")
        val third = Buffer("third")
        assertEquals(emptyList(), buffers.park("a", first, 2))
        assertEquals(emptyList(), buffers.park("b", second, 2))
        assertEquals(listOf(first), buffers.park("c", third, 2))
        assertEquals(second, buffers.take("b"))
        assertNull(buffers.take("b"))
        assertEquals(listOf("c" to third), buffers.snapshot())
    }

    @Test
    fun capacityAndStorageCleanupNeverDiscardRecordings() {
        val buffers = BackgroundBuffers<Buffer> { it.recording }
        val recording = Buffer("recording", true)
        val disposable = Buffer("disposable")
        buffers.park("recording", recording, 1)
        assertEquals(listOf(disposable), buffers.park("disposable", disposable, 1))
        assertEquals(emptyList(), buffers.trim(0))
        assertEquals(listOf("recording" to recording), buffers.snapshot())
        recording.recording = false
        assertEquals(listOf(recording), buffers.trim(0))
    }

    @Test
    fun pipBuffersRemainProtectedUntilPipCloses() {
        val buffers = BackgroundBuffers<Buffer> { it.recording || it.pip }
        val pip = Buffer("pip", pip = true)
        val ordinary = Buffer("ordinary")
        buffers.park("pip", pip, 2)
        buffers.park("ordinary", ordinary, 2)
        assertEquals(listOf(ordinary), buffers.trim(0))
        assertEquals(listOf("pip" to pip), buffers.snapshot())
        pip.pip = false
        assertEquals(listOf(pip), buffers.trim(0))
    }
}
