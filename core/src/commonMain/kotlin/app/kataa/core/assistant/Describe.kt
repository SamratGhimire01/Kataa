package app.kataa.core.assistant

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Human descriptions for confirmations: "Tomorrow at 9:00 AM", "Every Monday at 9:00 AM". */
object Describe {
    private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val BYDAY = mapOf("MO" to "Monday", "TU" to "Tuesday", "WE" to "Wednesday", "TH" to "Thursday", "FR" to "Friday", "SA" to "Saturday", "SU" to "Sunday")

    /** The rule in words the time reader understands again ("every tuesday and thursday", "on the 5th of every month"). */
    fun rruleWords(rrule: String): String {
        val days = mapOf("MO" to "monday", "TU" to "tuesday", "WE" to "wednesday", "TH" to "thursday", "FR" to "friday", "SA" to "saturday", "SU" to "sunday")
        return when {
            rrule == "FREQ=DAILY" -> "every day"
            rrule.endsWith("BYDAY=MO,TU,WE,TH,FR") -> "every weekday"
            rrule.startsWith("FREQ=WEEKLY;BYDAY=") -> "every " + rrule.substringAfter("BYDAY=").split(',').mapNotNull { days[it] }.joinToString(" and ")
            rrule.startsWith("FREQ=MONTHLY") -> rrule.substringAfter("BYMONTHDAY=", "").toIntOrNull()?.let { "on the ${it}th of every month" } ?: "every month"
            rrule == "FREQ=YEARLY" -> "every year"
            else -> ""
        }
    }

    fun time(at: Instant, rrule: String?, ctx: AssistantContext): String = time(at, rrule, ctx.nowMs, ctx.timeZone)

    fun time(at: Instant, rrule: String?, nowMs: Long, tz: TimeZone): String {
        val t = at.toLocalDateTime(tz)
        val clock = clock(t.hour, t.minute)
        if (rrule != null) {
            return when {
                rrule == "FREQ=DAILY" -> "Every day at $clock"
                rrule.startsWith("FREQ=MONTHLY") -> (rrule.substringAfter("BYMONTHDAY=", "").toIntOrNull() ?: t.day).let { d -> "Every month on the $d${suffix(d)} at $clock" }
                rrule == "FREQ=YEARLY" -> "Every year on ${t.day} ${MONTHS[t.month.ordinal]} at $clock"
                rrule.endsWith("BYDAY=MO,TU,WE,TH,FR") -> "Every weekday at $clock"
                rrule.startsWith("FREQ=WEEKLY;BYDAY=") -> rrule.substringAfter("BYDAY=").split(',').mapNotNull { BYDAY[it] }.let { days ->
                    when (days.size) { 0 -> "Every week at $clock"; 1 -> "Every ${days[0]} at $clock"; else -> "Every ${days.dropLast(1).joinToString(", ")} and ${days.last()} at $clock" }
                }
                else -> "Repeating, next at $clock"
            }
        }
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date
        val minutesAway = (at.toEpochMilliseconds() - nowMs) / 60_000
        val day = when (t.date) {
            today -> if (minutesAway in 0..90) return "In $minutesAway minute${if (minutesAway == 1L) "" else "s"}" else "Today"
            today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
            else -> if (t.date.toEpochDays() - today.toEpochDays() < 7) DAY_NAMES[t.dayOfWeek.ordinal]
            else "${DAY_NAMES[t.dayOfWeek.ordinal].take(3)} ${t.day} ${MONTHS[t.month.ordinal]}"
        }
        return "$day at $clock"
    }

    fun clock(hour: Int, minute: Int): String {
        val h12 = when { hour == 0 -> 12; hour > 12 -> hour - 12; else -> hour }
        return "$h12:${minute.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
    }

    private fun suffix(d: Int) = if (d in 11..13) "th" else when (d % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
}
