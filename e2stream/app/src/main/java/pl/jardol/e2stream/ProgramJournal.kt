package pl.jardol.e2stream

/** Audycja zauważona jako bieżąca podczas zapisu bufora (czasy w sekundach epoki). */
data class JournalEvent(val begin: Long, val end: Long, val title: String, val desc: String)

/**
 * Audycja w nagraniu: [start] / [end] — sekundy od początku nagrania.
 * [cutStart] — audycja zaczęła się przed początkiem nagrania, [cutEnd] — trwała jeszcze po jego końcu.
 */
data class Chapter(
    val start: Long, val end: Long, val title: String, val desc: String,
    val cutStart: Boolean = false, val cutEnd: Boolean = false,
)

/**
 * Dziennik audycji bufora timeshiftu: kolejne audycje kanału zbierane na bieżąco (EPG / EIT),
 * żeby nagranie z bufora („do teraz”, „do zmiany kanału”) dostało spis audycji z czasami.
 * Zmiana kanału = nowy dziennik ([clear]).
 */
class ProgramJournal {
    private val list = ArrayList<JournalEvent>()

    /**
     * Bieżąca audycja. Ta sama audycja (ten sam początek albo ten sam tytuł i nakładający się czas —
     * np. EIT poprawił godzinę z ramówki) jest zastępowana nowszymi danymi; nowa — dopisywana.
     */
    @Synchronized fun add(ev: JournalEvent) {
        if (ev.title.isBlank() || ev.end <= ev.begin) return
        val i = list.indexOfFirst {
            it.begin == ev.begin || (it.title == ev.title && it.begin < ev.end && ev.begin < it.end)
        }
        if (i >= 0) {
            val old = list[i]
            list[i] = ev.copy(desc = ev.desc.ifBlank { old.desc })
        } else list += ev
        list.sortBy { it.begin }
        if (list.size > 300) list.subList(0, list.size - 300).clear()
    }

    @Synchronized fun clear() = list.clear()

    /** Usuwa audycje zakończone przed [epochSec] (początek bufora); ostatnia zostaje zawsze. */
    @Synchronized fun trimBefore(epochSec: Long) {
        while (list.size > 1 && nextBegin(0).let { it != null && it <= epochSec } && list[0].end <= epochSec) list.removeAt(0)
    }

    private fun nextBegin(i: Int) = list.getOrNull(i + 1)?.begin

    @Synchronized fun snapshot(): List<JournalEvent> = list.toList()

    companion object {
        /** Nagranie krótsze niż to (s) po przycięciu do zakresu — pomijane (np. sekunda poprzedniej audycji). */
        const val MIN_CHAPTER_SEC = 20L

        /**
         * Audycje nagrania obejmującego [from]..[to] (sekundy epoki). Granice: początek audycji,
         * koniec = początek następnej zauważonej (ostatnia — do końca nagrania, nawet gdy wg ramówki już się skończyła).
         */
        fun chapters(events: List<JournalEvent>, from: Long, to: Long): List<Chapter> {
            if (to <= from) return emptyList()
            val ev = events.sortedBy { it.begin }
            val out = ArrayList<Chapter>()
            for (i in ev.indices) {
                val e = ev[i]
                val next = ev.getOrNull(i + 1)?.begin
                val endAt = next ?: maxOf(e.end, to)
                val s = maxOf(e.begin, from)
                val t = minOf(endAt, to)
                if (t - s < MIN_CHAPTER_SEC) continue
                out += Chapter(s - from, t - from, e.title, e.desc, cutStart = e.begin < from - 30, cutEnd = e.end > to + 30 && next == null)
            }
            // przerwy (brak danych) i nakładki: każda audycja zaczyna się tam, gdzie kończy poprzednia; pierwsza od 0
            for (i in out.indices) {
                val c = out[i]
                val start = if (i == 0) 0L else out[i - 1].end
                if (c.start != start) out[i] = c.copy(start = start)
            }
            if (out.isNotEmpty()) out[out.size - 1] = out.last().copy(end = to - from)
            return out
        }

        fun hms(sec: Long): String = String.format(java.util.Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)

        /**
         * Opis nagrania: spis audycji z czasami, potem pełne opisy. Jedna audycja — tylko jej opis.
         * [fromMiddle] / [noEnd] — dopiski przy audycjach przyciętych.
         */
        fun describe(ch: List<Chapter>, fromMiddle: String, noEnd: String): String {
            if (ch.size <= 1) return ch.firstOrNull()?.desc ?: ""
            val sb = StringBuilder()
            for (c in ch) {
                sb.append(hms(c.start)).append(" – ").append(hms(c.end)).append("  ").append(c.title)
                if (c.cutStart) sb.append(" (").append(fromMiddle).append(")")
                if (c.cutEnd) sb.append(" (").append(noEnd).append(")")
                sb.append('\n')
            }
            for (c in ch) {
                if (c.desc.isBlank()) continue
                sb.append("\n— ").append(c.title).append(" —\n").append(c.desc.trim()).append('\n')
            }
            return sb.toString().trimEnd()
        }

        /** Tytuł nagrania z kilku audycji: „Kanał 20:00–22:15: A, B, C…”. */
        fun title(prefix: String, ch: List<Chapter>, max: Int = 3): String {
            if (ch.isEmpty()) return prefix
            if (ch.size == 1) return ch[0].title
            val names = ch.take(max).joinToString(", ") { it.title } + if (ch.size > max) "…" else ""
            return "$prefix: $names"
        }
    }
}
