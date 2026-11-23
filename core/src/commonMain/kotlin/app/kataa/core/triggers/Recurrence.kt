package app.kataa.core.triggers

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Next occurrence for the small RRULE subset the understanding layer produces:
 * FREQ=DAILY, FREQ=WEEKLY;BYDAY=MO[,TU...], FREQ=MONTHLY. Computed in the reminder's own time zone,
 * so "every day at 8" stays at 8:00 across daylight-saving changes and travel.
 */
object Recurrence {
    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )

    /** First occurrence strictly after [afterMs] (and after [previousMs]). Null if [rrule] is not understood. */
    fun next(previousMs: Long, rrule: String, tz: TimeZone, afterMs: Long = previousMs): Long? {
        val parts = rrule.split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val prev = Instant.fromEpochMilliseconds(previousMs).toLocalDateTime(tz)
        val time = prev.time
        val floor = maxOf(previousMs, afterMs)
        var date = prev.date
        repeat(800) {
            date = when (parts["FREQ"]) {
                "DAILY" -> date.plus(1, DateTimeUnit.DAY)
                "WEEKLY" -> {
                    val days = parts["BYDAY"]?.split(',')?.mapNotNull(DAYS::get)?.toSet().orEmpty().ifEmpty { setOf(prev.dayOfWeek) }
                    var d = date.plus(1, DateTimeUnit.DAY)
                    while (d.dayOfWeek !in days) d = d.plus(1, DateTimeUnit.DAY)
                    d
                }
                "MONTHLY" -> nextMonthSameDay(date, prev.date.day)
                else -> return null
            }
            val at = date.atTime(time).toInstant(tz).toEpochMilliseconds()
            if (at > floor) return at
        }
        return null
    }

    /** "On the 31st" in a 30-day month falls on the last day of that month. */
    private fun nextMonthSameDay(from: kotlinx.datetime.LocalDate, wantedDay: Int): kotlinx.datetime.LocalDate {
        val firstNext = kotlinx.datetime.LocalDate(from.year, from.month, 1).plus(1, DateTimeUnit.MONTH)
        val lastDay = firstNext.plus(1, DateTimeUnit.MONTH).plus(-1, DateTimeUnit.DAY).day
        return kotlinx.datetime.LocalDate(firstNext.year, firstNext.month, minOf(wantedDay, lastDay))
    }

    @Suppress("unused")
    private fun LocalDateTime.ms(tz: TimeZone) = toInstant(tz).toEpochMilliseconds()
}
