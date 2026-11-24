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

    fun time(at: Instant, rrule: String?, ctx: AssistantContext): String = time(at, rrule, ctx.nowMs, ctx.timeZone)

    fun time(at: Instant, rrule: String?, nowMs: Long, tz: TimeZone): String {
        val t = at.toLocalDateTime(tz)
        val clock = clock(t.hour, t.minute)
        if (rrule != null) {
            return when {
                rrule == "FREQ=DAILY" -> "Every day at $clock"
                rrule == "FREQ=MONTHLY" -> "Every month on the ${t.day}${suffix(t.day)} at $clock"
                rrule == "FREQ=YEARLY" -> "Every year on ${t.day} ${MONTHS[t.month.ordinal]} at $clock"
                rrule.endsWith("BYDAY=MO,TU,WE,TH,FR") -> "Every weekday at $clock"
                rrule.startsWith("FREQ=WEEKLY;BYDAY=") -> "Every ${BYDAY[rrule.substringAfter("BYDAY=")] ?: "week"} at $clock"
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
