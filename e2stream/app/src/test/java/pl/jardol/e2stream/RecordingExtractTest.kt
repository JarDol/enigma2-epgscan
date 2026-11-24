package pl.jardol.e2stream

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RecordingExtractTest {
    private fun recording(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "rec-extract-${System.nanoTime()}").apply { mkdirs() }
        val segments = (1..4).map { i ->
            val name = "part-%05d.ts".format(i)
            File(dir, name).writeBytes(byteArrayOf(i.toByte()))
            RecordingSegment(name, (1000L + (i - 1) * 60) * 1000, (1000L + i * 60) * 1000)
        }
        File(dir, "part-00000.ts").writeBytes(byteArrayOf(42))
        val meta = JSONObject().put("done", true).put("title", "Buffer")
        LocalRecordings.putSegments(meta, segments)
        LocalRecordings.putEvents(meta, listOf(JournalEvent(1090, 1100, "Programme", "Description")))
        assertTrue(LocalRecordings.writeMeta(dir, meta))
        return dir
    }

    @Test fun extractingKeepsOnlyWholeOverlappingSegmentsAndHeader() {
        val dir = recording()
        val event = RecordingExtract.available(dir).single()
        RecordingExtract.extract(dir, event)
        assertEquals(listOf("part-00000.ts", "part-00002.ts", "part-00003.ts"),
            LocalRecordings.parts(dir).map { it.name })
        val meta = LocalRecordings.readMeta(dir)!!
        assertEquals("Programme", meta.getString("title"))
        assertEquals(1060, meta.getLong("begin"))
        assertEquals(120, meta.getLong("lengthSec"))
        assertEquals(2, LocalRecordings.segments(meta).size)
        assertFalse(File(dir, "extract.json").exists())
        dir.deleteRecursively()
    }

    @Test fun interruptedExtractionResumesWithoutLosingSelectedParts() {
        val dir = recording()
        val meta = LocalRecordings.readMeta(dir)!!.put("title", "Selected")
        LocalRecordings.putSegments(meta, listOf(LocalRecordings.segments(meta)[1]))
        val keep = JSONArray().put("part-00000.ts").put("part-00002.ts")
        File(dir, "extract.json").writeText(JSONObject().put("meta", meta).put("keep", keep).toString())
        assertEquals(listOf("part-00000.ts", "part-00002.ts"), LocalRecordings.parts(dir).map { it.name })
        RecordingExtract.recover(dir)
        assertEquals(listOf("part-00000.ts", "part-00002.ts"), LocalRecordings.parts(dir).map { it.name })
        assertEquals("Selected", LocalRecordings.readMeta(dir)!!.getString("title"))
        assertFalse(File(dir, "extract.json").exists())
        dir.deleteRecursively()
    }

    @Test fun missingIndexOrSegmentsNeverOfferExtraction() {
        val dir = recording()
        File(dir, "part-00002.ts").delete()
        assertTrue(RecordingExtract.available(dir).isEmpty())
        dir.deleteRecursively()
    }

    @Test fun interruptedExtractionWithMissingSelectedSegmentPreservesOriginal() {
        val dir = recording()
        val meta = LocalRecordings.readMeta(dir)!!
        LocalRecordings.putSegments(meta, listOf(LocalRecordings.segments(meta)[1]))
        File(dir, "extract.json").writeText(JSONObject().put("meta", meta).put("keep",
            JSONArray().put("part-00000.ts").put("part-00002.ts")).toString())
        assertTrue(File(dir, "part-00002.ts").delete())
        assertThrows(java.io.IOException::class.java) { RecordingExtract.recover(dir) }
        assertTrue(File(dir, "part-00001.ts").exists())
        assertTrue(File(dir, "part-00003.ts").exists())
        assertTrue(File(dir, "extract.json").exists())
        dir.deleteRecursively()
    }
}
