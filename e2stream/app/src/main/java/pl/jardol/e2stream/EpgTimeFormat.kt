package pl.jardol.e2stream

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class EpgTimeFormat(timeZone: TimeZone = TimeZone.getDefault()) {
    private val locale = Locale("pl", "PL")
    private val day = SimpleDateFormat("EEE dd.MM", locale).apply { this.timeZone = timeZone }
    private val dateKey = SimpleDateFormat("yyyy-MM-dd", locale).apply { this.timeZone = timeZone }
    private val time = SimpleDateFormat("HH:mm", locale).apply { this.timeZone = timeZone }

    fun dayAndRange(begin: Long, end: Long): String {
        val start = Date(begin * 1000)
        val finish = Date(end * 1000)
        val endSuffix = if (dateKey.format(start) == dateKey.format(finish)) "" else "\ndo ${day.format(finish)}"
        return "${day.format(start)}\n${time.format(start)}–${time.format(finish)}$endSuffix"
    }

    fun range(begin: Long, end: Long): String {
        val start = Date(begin * 1000)
        val finish = Date(end * 1000)
        val endDay = if (dateKey.format(start) == dateKey.format(finish)) "" else "${day.format(finish)} "
        return "${time.format(start)}–$endDay${time.format(finish)}"
    }
}
