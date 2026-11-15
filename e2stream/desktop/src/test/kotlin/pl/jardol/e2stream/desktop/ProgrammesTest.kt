package pl.jardol.e2stream.desktop

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgrammesTest {
    private val t0 = 1_800_000_000L // s

    @Test
    fun indexSkipsShortEdgesAndCountsFromRecordingStart() {
        val log = ProgrammeLog()
        log.add("Wiadomości", "opis A", t0 - 600, t0 + 60)        // w nagraniu tylko 60 s — pominięta
        log.add("Film", "opis B", t0 + 60, t0 + 3_660)
        log.add("Pogoda", "", t0 + 3_660, t0 + 3_900)
        log.add("Sport", "", t0 + 3_900, t0 + 7_000)             // w nagraniu 60 s — pominięta
        val index = programmeIndex(log.snapshot(), t0 * 1000, (t0 + 3_960) * 1000)
        assertEquals(listOf("Film", "Pogoda"), index.map { it.title })
        assertEquals(listOf(60_000L, 3_660_000L), index.map { it.offsetMs })
        assertEquals("Pogoda", programmeAt(index, 3_700_000)?.title)
        assertEquals("Film", programmeAt(index, 0)?.title)
    }

    @Test
    fun logReplacesCorrectedEntry() {
        val log = ProgrammeLog()
        log.add("Film", "", t0, t0 + 3_600)
        log.add("Film", "pełny opis", t0, t0 + 3_700)
        log.add("Film (EIT)", "", t0, t0 + 3_700)
        assertEquals(1, log.snapshot().size)
        assertEquals("Film (EIT)", log.snapshot().single().title)
    }

    @Test
    fun titleDescriptionAndJsonRoundTrip() {
        val programmes = listOf(RecProgramme("A", 0, "opis A"), RecProgramme("B", 65_000), RecProgramme("C", 130_000), RecProgramme("D", 200_000))
        val title = indexedRecordingTitle("TVP 2 HD", t0 * 1000, (t0 + 300) * 1000, programmes)
        assertTrue(title.startsWith("TVP 2 HD "))
        assertTrue(title.endsWith(": A, B, C i 1 więcej"))
        val description = indexedRecordingDescription(programmes)
        assertTrue(description.startsWith("0:00  A\n1:05  B"), description)
        assertTrue(description.contains("A\nopis A"))
        val info = JSONObject().put("programmes", programmesJson(programmes))
        assertEquals(programmes, programmesFrom(JSONObject(info.toString())))
    }
}
