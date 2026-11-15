package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramJournalTest {

    private val t0 = 1_700_000_000L

    @Test
    fun chaptersFromBufferWithCutFirstAndLast() {
        val j = ProgramJournal()
        j.add(JournalEvent(t0 - 600, t0 + 823, "Wiadomości", "dziennik"))
        j.add(JournalEvent(t0 + 824, t0 + 5685, "Film", "opis filmu"))
        j.add(JournalEvent(t0 + 5686, t0 + 9000, "Prognoza", ""))
        val ch = ProgramJournal.chapters(j.snapshot(), t0, t0 + 6000)
        assertEquals(listOf("Wiadomości", "Film", "Prognoza"), ch.map { it.title })
        assertEquals(listOf(0L, 824L, 5686L), ch.map { it.start })
        assertEquals(6000L, ch.last().end)
        assertTrue(ch[0].cutStart)
        assertTrue(ch[2].cutEnd)
        assertFalse(ch[1].cutStart || ch[1].cutEnd)
        val d = ProgramJournal.describe(ch, "od środka", "bez końca")
        assertTrue(d.startsWith("0:00:00 – 0:13:44  Wiadomości (od środka)\n0:13:44 – 1:34:46  Film\n"))
        assertTrue(d.contains("— Film —\nopis filmu"))
        assertEquals("Polsat 20:00–21:40: Wiadomości, Film, Prognoza", ProgramJournal.title("Polsat 20:00–21:40", ch))
    }

    @Test
    fun eitCorrectionReplacesPlannedEvent() {
        val j = ProgramJournal()
        j.add(JournalEvent(t0, t0 + 3600, "Film", "z EPG"))
        j.add(JournalEvent(t0 + 420, t0 + 4020, "Film", ""))     // EIT: faktyczny początek, bez opisu
        val s = j.snapshot()
        assertEquals(1, s.size)
        assertEquals(t0 + 420, s[0].begin)
        assertEquals("z EPG", s[0].desc)
    }

    @Test
    fun singleProgrammeKeepsItsTitleAndDescOnly() {
        val ch = ProgramJournal.chapters(listOf(JournalEvent(t0 - 100, t0 + 4000, "Mecz", "opis")), t0, t0 + 900)
        assertEquals(1, ch.size)
        assertEquals("Mecz", ProgramJournal.title("X", ch))
        assertEquals("opis", ProgramJournal.describe(ch, "a", "b"))
    }

    @Test
    fun tinyTailOfPreviousProgrammeIsSkippedAndLastCoversToEnd() {
        val ev = listOf(
            JournalEvent(t0 - 3600, t0 + 5, "Stara", ""),
            JournalEvent(t0 + 5, t0 + 100, "Nowa", ""),          // wg ramówki krótka, ale następnej nie zauważono
        )
        val ch = ProgramJournal.chapters(ev, t0, t0 + 1800)
        assertEquals(listOf("Nowa"), ch.map { it.title })
        assertEquals(0L, ch[0].start)
        assertEquals(1800L, ch[0].end)
    }

    @Test
    fun trimKeepsProgrammesStillInBuffer() {
        val j = ProgramJournal()
        j.add(JournalEvent(t0, t0 + 100, "A", ""))
        j.add(JournalEvent(t0 + 100, t0 + 200, "B", ""))
        j.add(JournalEvent(t0 + 200, t0 + 300, "C", ""))
        j.trimBefore(t0 + 150)
        assertEquals(listOf("B", "C"), j.snapshot().map { it.title })
        j.trimBefore(t0 + 10_000)
        assertEquals(listOf("C"), j.snapshot().map { it.title })
    }
}
