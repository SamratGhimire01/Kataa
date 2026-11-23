package app.kataa.core.nlu

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** A time found in an utterance. */
data class TimeMatch(
    val fireAt: Instant,
    /** RFC 5545 subset, e.g. "FREQ=DAILY" or "FREQ=WEEKLY;BYDAY=MO". */
    val rrule: String?,
    /** Character range in the normalized text, so callers can remove it from the reminder body. */
    val range: IntRange,
    /** True when the user gave no clock time and a default was used ("tomorrow" -> 9:00). */
    val timeWasDefaulted: Boolean,
)

/**
 * Rule-based English date/time parser for reminders. Works on [Text.normalize]d input.
 * Handles: in N minutes/hours/days/weeks, in half an hour, at 5, at 5:30 pm, at noon, today, tonight,
 * this morning/afternoon/evening, tomorrow (morning/...), day after tomorrow, on Monday, next Friday,
 * this weekend, next week, on the 25th, on 5 December / December 5, every day/morning/night/Monday/weekday/week/month.
 */
object TimeParser {
    private val DAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY,
        "tues" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
    )
    private val MONTHS = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3, "april" to 4, "apr" to 4,
        "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7, "august" to 8, "aug" to 8, "september" to 9,
        "sep" to 9, "sept" to 9, "october" to 10, "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12,
    )
    private val PARTS = mapOf("morning" to 9, "afternoon" to 14, "evening" to 18, "night" to 21, "tonight" to 20, "noon" to 12, "lunch" to 13)
    private val BYDAY = mapOf(
        DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
        DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
    )

    private const val NUM = "(\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|forty-five|fifty|couple|few)"
    private const val DAYNAME = "(monday|mon|tuesday|tues|tue|wednesday|wed|thursday|thurs|thu|friday|fri|saturday|sat|sunday|sun)"
    private const val MONTHNAME = "(january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec)"
    private const val CLOCK = "(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m|p\\.m|o'clock|oclock)?"

    private val RELATIVE = Regex("\\bin (?:(?:a )?$NUM\\s*(?:of )?)?(minutes?|mins?|hours?|hrs?|days?|weeks?|months?)\\b")
    private val HALF_HOUR = Regex("\\bin (?:half an hour|half hour|30 min(?:ute)?s?)\\b")
    private val RECURRING = Regex("\\b(?:every|each) (day|morning|afternoon|evening|night|week|month|weekday|weekdays|$DAYNAME)\\b|\\b(daily|weekly|monthly|on weekdays)\\b")
    private val AT_CLOCK = Regex("\\b(?:at|by|around) $CLOCK\\b|\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)\\b|\\b(?:at|by|around) (noon|midnight|lunch)\\b")
    private val DAY_WORD = Regex("\\b(day after tomorrow|tomorrow|today|tonight|this (?:morning|afternoon|evening)|next week|this weekend|next weekend)(?: (morning|afternoon|evening|night))?\\b")
    private val WEEKDAY = Regex("\\b(?:on |this |next |coming )?$DAYNAME(?: (morning|afternoon|evening|night))?\\b")
    private val DATE_DM = Regex("\\b(?:on )?(?:the )?(\\d{1,2})(?:st|nd|rd|th)?(?: of)? $MONTHNAME\\b")
    private val DATE_MD = Regex("\\b(?:on )?$MONTHNAME (\\d{1,2})(?:st|nd|rd|th)?\\b")
    private val DATE_D = Regex("\\bon the (\\d{1,2})(?:st|nd|rd|th)\\b")

    fun parse(text: String, nowMs: Long, tz: TimeZone): TimeMatch? {
        val now = Instant.fromEpochMilliseconds(nowMs)
        val local = now.toLocalDateTime(tz)
        val spans = mutableListOf<IntRange>()

        // 1) Relative offsets: "in 2 hours", "in half an hour".
        HALF_HOUR.find(text)?.let {
            return TimeMatch(now + kotlin.time.Duration.parse("30m"), null, it.range, false)
        }
        RELATIVE.find(text)?.let { m ->
            val n = m.groupValues[1].takeIf { it.isNotEmpty() }?.let(Text::numberValue) ?: 1
            val unit = m.groupValues[2]
            val at = when {
                unit.startsWith("min") -> now + kotlin.time.Duration.parse("${n}m")
                unit.startsWith("h") -> now + kotlin.time.Duration.parse("${n}h")
                unit.startsWith("day") -> local.date.plus(n, DateTimeUnit.DAY).atTime(local.time).toInstant(tz)
                unit.startsWith("week") -> local.date.plus(n * 7, DateTimeUnit.DAY).atTime(local.time).toInstant(tz)
                else -> local.date.plus(n, DateTimeUnit.MONTH).atTime(local.time).toInstant(tz)
            }
            return TimeMatch(at, null, m.range, false)
        }

        // 2) Clock time, if any.
        var clock: LocalTime? = null
        AT_CLOCK.find(text)?.let { m ->
            clock = clockFrom(m)
            if (clock != null) spans += m.range
        }

        // 3) Recurrence.
        var rrule: String? = null
        var date: LocalDate? = null
        var partHour: Int? = null
        RECURRING.find(text)?.let { m ->
            spans += m.range
            val what = m.groupValues[1].ifEmpty { m.groupValues[3] }
            when (what) {
                "day", "daily" -> rrule = "FREQ=DAILY"
                "morning", "afternoon", "evening", "night" -> { rrule = "FREQ=DAILY"; partHour = PARTS[what] }
                "week", "weekly" -> rrule = "FREQ=WEEKLY;BYDAY=${BYDAY[local.dayOfWeek]}"
                "month", "monthly" -> rrule = "FREQ=MONTHLY"
                "weekday", "weekdays", "on weekdays" -> {
                    rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
                    date = nextMatching(local.date, includeToday = true) { it.dayOfWeek.isoDayNumber <= 5 }
                }
                else -> DAYS[what]?.let { d ->
                    rrule = "FREQ=WEEKLY;BYDAY=${BYDAY[d]}"
                    date = nextMatching(local.date, includeToday = true) { it.dayOfWeek == d }
                }
            }
        }

        // 4) Day expressions (skipped when recurrence already fixed the day).
        if (rrule == null) {
            DAY_WORD.find(text)?.let { m ->
                spans += m.range
                val word = m.groupValues[1]
                val part = m.groupValues[2].ifEmpty { null }
                when {
                    word == "day after tomorrow" -> date = local.date.plus(2, DateTimeUnit.DAY)
                    word == "tomorrow" -> date = local.date.plus(1, DateTimeUnit.DAY)
                    word == "today" -> date = local.date
                    word == "tonight" -> { date = local.date; partHour = PARTS["tonight"] }
                    word.startsWith("this ") && word != "this weekend" -> { date = local.date; partHour = PARTS[word.removePrefix("this ")] }
                    word == "next week" -> date = nextMatching(local.date, includeToday = false) { it.dayOfWeek == DayOfWeek.MONDAY }
                    word == "this weekend" -> date = nextMatching(local.date, includeToday = true) { it.dayOfWeek == DayOfWeek.SATURDAY }
                    word == "next weekend" -> date = nextMatching(local.date, includeToday = false) { it.dayOfWeek == DayOfWeek.SATURDAY }.plus(7, DateTimeUnit.DAY)
                }
                if (part != null) partHour = PARTS[part]
            }
        }
        if (rrule == null && date == null) {
            dateFrom(text, local.date)?.let { (d, r) -> date = d; spans += r }
        }
        if (rrule == null && date == null) {
            WEEKDAY.find(text)?.let { m ->
                val day = DAYS[m.groupValues[1]]!!
                val isNext = m.value.startsWith("next ")
                var d = nextMatching(local.date, includeToday = false) { it.dayOfWeek == day }
                // "next friday" said on a Wednesday usually means the Friday after this one.
                if (isNext && d.toEpochDays() - local.date.toEpochDays() < 7 &&
                    local.dayOfWeek.isoDayNumber < day.isoDayNumber
                ) d = d.plus(7, DateTimeUnit.DAY)
                date = d
                spans += m.range
                m.groupValues[2].ifEmpty { null }?.let { partHour = PARTS[it] }
            }
        }

        if (clock == null && date == null && rrule == null && partHour == null) return null

        val defaulted = clock == null
        val time = clock ?: LocalTime(partHour ?: DEFAULT_HOUR, 0)
        var day = date ?: local.date
        var at = LocalDateTime(day, time)
        // A bare clock time already past today means the next occurrence.
        if (date == null && at <= local) {
            day = day.plus(1, DateTimeUnit.DAY)
            at = LocalDateTime(day, time)
        }
        // Recurring reminders whose first slot today has passed start at the next slot.
        if (rrule != null && at <= local) {
            day = if (rrule!!.startsWith("FREQ=DAILY")) day.plus(1, DateTimeUnit.DAY)
            else if (rrule!!.startsWith("FREQ=MONTHLY")) day.plus(1, DateTimeUnit.MONTH)
            else day.plus(7, DateTimeUnit.DAY)
            at = LocalDateTime(day, time)
        }
        val range = spans.minOf { it.first }..spans.maxOf { it.last }
        return TimeMatch(at.toInstant(tz), rrule, range, defaulted)
    }

    private const val DEFAULT_HOUR = 9

    private fun clockFrom(m: MatchResult): LocalTime? {
        val g = m.groupValues
        g[7].ifEmpty { null }?.let { word ->
            return when (word) { "noon" -> LocalTime(12, 0); "midnight" -> LocalTime(23, 59); else -> LocalTime(13, 0) }
        }
        val (hStr, minStr, suffix) = if (g[1].isNotEmpty()) Triple(g[1], g[2], g[3]) else Triple(g[4], g[5], g[6])
        var h = hStr.toIntOrNull() ?: return null
        val min = minStr.toIntOrNull() ?: 0
        if (h > 23 || min > 59) return null
        val sfx = suffix.replace(".", "")
        when {
            sfx == "pm" && h < 12 -> h += 12
            sfx == "am" && h == 12 -> h = 0
            // "at 5" with no am/pm: people mean the next sensible hour; 1-6 -> afternoon/evening.
            sfx.isEmpty() || sfx.startsWith("o") -> if (h in 1..6) h += 12
        }
        return LocalTime(h, min)
    }

    private fun dateFrom(text: String, today: LocalDate): Pair<LocalDate, IntRange>? {
        DATE_DM.find(text)?.let { m -> return resolveDate(m.groupValues[1].toInt(), MONTHS[m.groupValues[2]]!!, today)?.let { it to m.range } }
        DATE_MD.find(text)?.let { m -> return resolveDate(m.groupValues[2].toInt(), MONTHS[m.groupValues[1]]!!, today)?.let { it to m.range } }
        DATE_D.find(text)?.let { m ->
            val day = m.groupValues[1].toInt()
            var month = today.month.number
            var year = today.year
            if (day < today.day) { month += 1; if (month > 12) { month = 1; year++ } }
            return runCatching { LocalDate(year, month, day) }.getOrNull()?.let { it to m.range }
        }
        return null
    }

    private fun resolveDate(day: Int, month: Int, today: LocalDate): LocalDate? {
        val thisYear = runCatching { LocalDate(today.year, Month(month), day) }.getOrNull() ?: return null
        return if (thisYear < today) runCatching { LocalDate(today.year + 1, Month(month), day) }.getOrNull() else thisYear
    }

    private fun nextMatching(from: LocalDate, includeToday: Boolean, pred: (LocalDate) -> Boolean): LocalDate {
        var d = if (includeToday) from else from.plus(1, DateTimeUnit.DAY)
        repeat(8) { if (pred(d)) return d; d = d.plus(1, DateTimeUnit.DAY) }
        return d
    }
}
