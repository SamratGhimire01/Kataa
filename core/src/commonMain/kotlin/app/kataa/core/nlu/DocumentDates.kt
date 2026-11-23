package app.kataa.core.nlu

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** A deadline found in a bill, ticket, card or letter. */
data class Deadline(
    /** What kind ("Due", "Expires", ...), for the note: "Electricity bill: Due 12 Aug 2027". */
    val label: String,
    val date: LocalDate,
    /** When to remind: a few days before at 9:00, or the morning of if it is close. */
    val remindAtMs: Long,
)

/**
 * Life-admin deadlines in photographed or shared documents: "Due date: 12/08/2027", "Valid until Aug 12,
 * 2027", "Expiry 2027-08-12", "Pay by 5 Sep". Only dates next to a deadline word count, so a bill's
 * issue date or a birth date is ignored.
 */
object DocumentDates {
    private val CUE = Regex(
        "(due date|due on|due|pay by|payment due|last date|deadline|expires on|expires|expiry date|expiry|exp\\.? date|valid (?:until|till|thru|through)|valid upto|renew by|renewal date|submit by|return by|best before|use by)",
        RegexOption.IGNORE_CASE,
    )
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MON = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.?"
    private val ISO = Regex("\\b(20\\d\\d)[-/.](\\d{1,2})[-/.](\\d{1,2})\\b")
    private val NUMERIC = Regex("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})\\b")
    private val DAY_MON = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?[ -]$MON,?[ -]?(\\d{4})?", RegexOption.IGNORE_CASE)
    private val MON_DAY = Regex("\\b$MON[ -](\\d{1,2})(?:st|nd|rd|th)?,?[ -]?(\\d{4})?", RegexOption.IGNORE_CASE)
    private val MON_YEAR = Regex("\\b(\\d{1,2})/(\\d{2})\\b") // card expiry 08/29

    /**
     * Finds the soonest upcoming deadline. [dayFirst]: read 05/08 as 5 August (Nepal, India, UK); false
     * for the US. Within [lookAheadChars] after a cue word.
     */
    fun find(text: String, nowMs: Long, tz: TimeZone, dayFirst: Boolean = true, daysBefore: Int = 3, lookAheadChars: Int = 40): Deadline? {
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date
        val found = ArrayList<Pair<String, LocalDate>>()
        for (cue in CUE.findAll(text)) {
            val window = text.substring(cue.range.last + 1, minOf(text.length, cue.range.last + 1 + lookAheadChars))
            val date = firstDate(window, today, dayFirst, cardStyle = cue.value.lowercase().startsWith("exp") || cue.value.lowercase().startsWith("valid")) ?: continue
            if (date >= today) found += labelFor(cue.value) to date
        }
        val (label, date) = found.minByOrNull { it.second } ?: return null
        val remindDay = date.minus(daysBefore, DateTimeUnit.DAY).let { if (it <= today) date else it }
        var at = remindDay.atTime(LocalTime(9, 0)).toInstant(tz).toEpochMilliseconds()
        if (at <= nowMs) at = nowMs + 60 * 60 * 1000 // due today: remind in an hour
        return Deadline(label, date, at)
    }

    fun describe(d: Deadline) = "${d.label} ${d.date.day} ${MONTHS[d.date.month.ordinal].replaceFirstChar { it.uppercase() }} ${d.date.year}"

    private fun firstDate(s: String, today: LocalDate, dayFirst: Boolean, cardStyle: Boolean): LocalDate? {
        val candidates = ArrayList<Pair<Int, LocalDate>>()
        ISO.find(s)?.let { m -> date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { candidates += m.range.first to it } }
        NUMERIC.find(s)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); val y = year(m.groupValues[3])
            val (d, mo) = when { a > 12 -> a to b; b > 12 -> b to a; dayFirst -> a to b; else -> b to a }
            date(y, mo, d)?.let { candidates += m.range.first to it }
        }
        DAY_MON.find(s)?.let { m -> date(m.groupValues[3].ifEmpty { null }?.toInt(), month(m.groupValues[2]), m.groupValues[1].toInt(), today)?.let { candidates += m.range.first to it } }
        MON_DAY.find(s)?.let { m -> date(m.groupValues[3].ifEmpty { null }?.toInt(), month(m.groupValues[1]), m.groupValues[2].toInt(), today)?.let { candidates += m.range.first to it } }
        if (candidates.isEmpty() && cardStyle) {
            MON_YEAR.find(s)?.let { m ->
                val mo = m.groupValues[1].toInt(); val y = 2000 + m.groupValues[2].toInt()
                if (mo in 1..12) candidates += m.range.first to LocalDate(y, mo, 1).let { it.plusMonthsLastDay() }
            }
        }
        return candidates.minByOrNull { it.first }?.second
    }

    private fun LocalDate.plusMonthsLastDay(): LocalDate =
        kotlinx.datetime.LocalDate(year, month, 1).let { first ->
            val next = kotlinx.datetime.LocalDate(first.year, first.month, 1).let { it.minus(-1, DateTimeUnit.MONTH) }
            next.minus(1, DateTimeUnit.DAY)
        }

    private fun year(s: String): Int = s.toInt().let { if (it < 100) 2000 + it else it }
    private fun month(s: String): Int = MONTHS.indexOf(s.lowercase().take(3)) + 1

    private fun date(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate(y, m, d) }.getOrNull()

    /** Without a year: the next such date from today. */
    private fun date(y: Int?, m: Int, d: Int, today: LocalDate): LocalDate? {
        if (y != null) return date(y, m, d)
        val thisYear = date(today.year, m, d) ?: return null
        return if (thisYear >= today) thisYear else date(today.year + 1, m, d)
    }

    private fun labelFor(cue: String): String {
        val c = cue.lowercase()
        return when {
            c.startsWith("exp") || c.startsWith("valid") || c.startsWith("best") || c.startsWith("use by") -> "Expires"
            c.startsWith("renew") -> "Renew by"
            c.startsWith("submit") || c.startsWith("return") || c.startsWith("last date") || c.startsWith("deadline") -> "Deadline"
            else -> "Due"
        }
    }
}
