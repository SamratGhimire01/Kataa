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
import kotlinx.datetime.minus
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
    /** True when the user gave no time of day at all and the default was used ("tomorrow" -> 9:00). */
    val timeWasDefaulted: Boolean,
    /**
     * The other half of the clock when the user said a bare hour ("at 7:13", no am/pm, no morning/evening):
     * [fireAt] is the best guess and this the other reading, so the app can ask "AM or PM?". Null when clear.
     */
    val alternative: Instant? = null,
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
    private val PARTS = mapOf("morning" to 9, "afternoon" to 14, "evening" to 18, "night" to 21, "tonight" to 20, "noon" to 12, "lunch" to 13, "after lunch" to 14, "breakfast" to 8, "after breakfast" to 9, "dinner" to 20, "after dinner" to 21, "before bed" to 22, "bedtime" to 22)
    private val BYDAY = mapOf(
        DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
        DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
    )

    private const val NUM = "(\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|forty-five|fifty|couple|few)"
    private const val DAYNAME = "(monday|mon|tuesday|tues|tue|wednesday|wed|thursday|thurs|thu|friday|fri|saturday|sat|sunday|sun)"
    private const val MONTHNAME = "(january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec)"
    private const val CLOCK = "(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m|p\\.m|o'clock|oclock)?"

    private val RELATIVE = Regex("\\b(?:in|after|within) (?:(?:a )?$NUM\\s*(?:of )?)?(minutes?|mins?|hours?|hrs?|days?|weeks?|months?|years?)(?: (?:from now|later|time))?\\b|\\b$NUM (minutes?|mins?|hours?|hrs?|days?|weeks?|months?|years?) (?:from now|later|from today)\\b")
    private val PART_ALONE = Regex("\\b(?:in the|this) (morning|afternoon|evening)\\b|\\bat night\\b|\\b(after lunch|after breakfast|after dinner|before bed|bedtime|(?:at|before|during) (?:lunch|breakfast|dinner))\\b")
    private val HALF_HOUR = Regex("\\bin (?:half an hour|half hour|30 min(?:ute)?s?)\\b")
    private val RECURRING = Regex("\\b(?:every|each) (day|morning|afternoon|evening|night|week|month|weekday|weekdays|$DAYNAME)\\b|\\b(daily|weekly|monthly|on weekdays)\\b")
    private val AT_CLOCK = Regex("\\b(?:at|by|around) $CLOCK\\b|\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)\\b|\\b(?:at|by|around) (noon|midnight|lunch)\\b|\\b(\\d{1,2}):(\\d{2})\\b")
    private val DAY_WORD = Regex("\\b(day after tomorrow|tomorrow|today|tonight|this (?:morning|afternoon|evening)|next week|this weekend|next weekend)(?: (morning|afternoon|evening|night))?\\b")
    private val WEEKDAY = Regex("\\b(?:on |this |next |coming )?$DAYNAME(?: (morning|afternoon|evening|night))?\\b")
    private val DATE_DM = Regex("\\b(?:on )?(?:the )?(\\d{1,2})(?:st|nd|rd|th)?(?: of)? $MONTHNAME\\b")
    private val DATE_MD = Regex("\\b(?:on )?$MONTHNAME (\\d{1,2})(?:st|nd|rd|th)?\\b")
    private val DATE_D = Regex("\\bon the (\\d{1,2})(?:st|nd|rd|th)\\b")

    fun parse(text: String, nowMs: Long, tz: TimeZone): TimeMatch? {
        val now = Instant.fromEpochMilliseconds(nowMs)
        val local = now.toLocalDateTime(tz)
        val spans = mutableListOf<IntRange>()

        // 1) Relative offsets: "in 2 hours", "in half an hour", "after 20 days", "3 weeks from now".
        HALF_HOUR.find(text)?.let {
            return TimeMatch(now + kotlin.time.Duration.parse("30m"), null, it.range, false)
        }
        var relDate: LocalDate? = null
        RELATIVE.find(text)?.let { m ->
            val numStr = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val unit = m.groupValues[2].ifEmpty { m.groupValues[4] }
            val n = numStr.takeIf { it.isNotEmpty() }?.let(Text::numberValue) ?: 1
            when {
                unit.startsWith("min") -> return TimeMatch(now + kotlin.time.Duration.parse("${n}m"), null, m.range, false)
                unit.startsWith("h") -> return TimeMatch(now + kotlin.time.Duration.parse("${n}h"), null, m.range, false)
                unit.startsWith("day") -> relDate = local.date.plus(n, DateTimeUnit.DAY)
                unit.startsWith("week") -> relDate = local.date.plus(n * 7, DateTimeUnit.DAY)
                unit.startsWith("month") -> relDate = local.date.plus(n, DateTimeUnit.MONTH)
                else -> relDate = local.date.plus(n, DateTimeUnit.YEAR)
            }
            spans += m.range
        }

        // 2) Clock time, if any.
        var clock: LocalTime? = null
        var halfGuessed = false
        AT_CLOCK.find(text)?.let { m ->
            clock = clockFrom(m)
            if (clock != null) spans += m.range
            halfGuessed = clock != null && bareHour(m)
        }

        if (relDate != null) {
            // "after 20 days at 2 pm": that day at that time; with no time, the same time as now.
            val clk = clock
            val t = clk ?: if (partOf(text) != null) LocalTime(partOf(text)!!, 0) else local.time
            val adj = if (clk != null && clk.hour < 12 && !hasAmPm(text)) partOf(text)?.takeIf { it >= 12 }?.let { LocalTime(clk.hour + 12, clk.minute) } ?: clk else t
            val other = if (clk != null && halfGuessed && partOf(text) == null) LocalDateTime(relDate!!, otherHalf(adj)).toInstant(tz) else null
            return TimeMatch(LocalDateTime(relDate!!, adj).toInstant(tz), null, spans.minOf { it.first }..spans.maxOf { it.last }, false, other)
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

        if (partHour == null) PART_ALONE.find(text)?.let { m ->
            val meal = m.groupValues[2]
            partHour = if (meal.isNotEmpty()) PARTS[meal] ?: PARTS[meal.substringAfter(' ')] else PARTS[m.groupValues[1].ifEmpty { "night" }]
            spans += m.range
        }
        if (clock == null && date == null && rrule == null && partHour == null) return null

        // "tomorrow evening at 7", "at 7 tonight": a part of the day says which half of the clock.
        clock?.let { c ->
            if (c.hour in 1..11 && !hasAmPm(text) && (partHour ?: 0) >= 12) clock = LocalTime(c.hour + 12, c.minute)
        }

        val defaulted = clock == null && partHour == null
        val time = clock ?: LocalTime(partHour ?: DEFAULT_HOUR, 0)
        var day = date ?: local.date
        var at = LocalDateTime(day, time)
        // "at 8" said at 9 in the morning means 8 in the evening, if that is still ahead today.
        if (date == null && rrule == null && clock != null && clock!!.hour in 7..11 && !hasAmPm(text) && at <= local) {
            val pm = LocalDateTime(day, LocalTime(clock!!.hour + 12, clock!!.minute))
            if (pm > local) at = pm
        }
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
        var other: Instant? = null
        if (halfGuessed && partHour == null && rrule == null) {
            var alt = LocalDateTime(at.date, otherHalf(at.time))
            if (date == null && alt <= local) alt = LocalDateTime(alt.date.plus(1, DateTimeUnit.DAY), alt.time)
            if (date == null && alt.date > local.date.plus(1, DateTimeUnit.DAY)) alt = LocalDateTime(alt.date.minus(1, DateTimeUnit.DAY), alt.time)
            if (alt != at && alt > local) other = alt.toInstant(tz)
        }
        return TimeMatch(at.toInstant(tz), rrule, range, defaulted, other)
    }

    private fun otherHalf(t: LocalTime) = LocalTime((t.hour + 12) % 24, t.minute)

    /** An hour said without am/pm or a 24-hour form ("at 7", "7:13"), so it could be morning or evening. */
    private fun bareHour(m: MatchResult): Boolean {
        val g = m.groupValues
        if (g[7].isNotEmpty() || g[3].isNotEmpty() && !g[3].startsWith("o") || g[6].isNotEmpty()) return false
        val h = (g[1].ifEmpty { g[4].ifEmpty { g[8] } }).let { if (it.length == 2 && it.startsWith("0")) return false else it.toIntOrNull() } ?: return false
        return h in 1..12
    }

    private const val DEFAULT_HOUR = 9

    private val AMPM_WORD = Regex("\\d\\s*(?:am|pm|a\\.m|p\\.m)\\b")
    private fun hasAmPm(text: String) = AMPM_WORD.containsMatchIn(text)

    private val PART_WORD = Regex("\\b(morning|afternoon|evening|night|tonight)\\b")
    private fun partOf(text: String): Int? = PART_WORD.find(text)?.let { PARTS[it.groupValues[1]] }

    private fun clockFrom(m: MatchResult): LocalTime? {
        val g = m.groupValues
        g[7].ifEmpty { null }?.let { word ->
            return when (word) { "noon" -> LocalTime(12, 0); "midnight" -> LocalTime(23, 59); else -> LocalTime(13, 0) }
        }
        val (hStr, minStr, suffix) = when {
            g[1].isNotEmpty() -> Triple(g[1], g[2], g[3])
            g[4].isNotEmpty() -> Triple(g[4], g[5], g[6])
            else -> Triple(g[8], g[9], "")
        }
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
