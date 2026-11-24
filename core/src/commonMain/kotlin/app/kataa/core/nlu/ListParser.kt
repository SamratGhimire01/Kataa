package app.kataa.core.nlu

import app.kataa.core.lists.ListKey
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * To-do lists and the journal, by voice: "today's to-do list", "add milk to my shopping list", "how many tasks
 * are done", "mark the second one done", "today's journal", "show my journal for Monday".
 * Runs on normalized text before the general rules; returns null when the sentence is about something else.
 */
object ListParser {
    private const val TD = "(?:to-?do lists?|to do lists?|todo lists?|to-?dos|to dos|todos|task list|tasks|check ?list|goals|goal list|list)"
    private val TD_WORD = Regex("\\b$TD\\b")
    private val LISTEN_VERB = Regex("^(?:please )?(?:can you |could you |let us |let me |i want to |i wanna )?(?:write|make|create|start|take|note|record|prepare|plan|set up|build|begin|dictate|do|new)(?: down| up| out)?(?: me)?$")
    private val SHOW_VERB = Regex("^(?:please )?(?:can you |could you )?(?:show|open|read|see|check|view|display|tell me|give me|what is on|what is in|what are on|what are in|what is|what are|whats on|read out|read me)(?: me)?(?: out)?$")
    private val NOT_ITEMS = Regex("^(?:is|are|was|were) (?:on|in|at|under|inside|behind|near|with|by|kept|stored|written|saved|full|long|empty|too|very|so|not|getting|piling|done|finished|over|lost|missing|gone)\\b|^(?:of|about|that|which|for|from|with|i|we|you|it)\\b|^(?:is|are) [a-z]+(?:ing|ed)\\b")
    private val OWNER = Regex("^(?:(?:my|the|a|an|our|new|this)(?: |$))+")

    private val ADD = Regex("^(?:please )?(?:can you |could you )?(?:also )?(?:add|put|include|append|write|note|jot)(?: down)? (.+) (?:to|on|in|into|onto) (?:my |the |our )?(?!do\\b|-do\\b)((?:[a-z0-9']+ ){0,3}?)$TD\\b(.*)$")
    private val REMOVE = Regex("^(?:please )?(?:remove|delete|take off|cross out|drop|erase) (.+?) (?:from|off|on|in) (?:my |the |our )?(.*?)\\b$TD\\b(.*)$")

    private val COUNT = Regex("^how many (?:of my )?(?:tasks|things|items|to-?dos|to dos|todos|goals|jobs)\\b.*$")
    private val LEFT = Regex("^(?:what|which)(?: tasks| things| items| goals)? (?:is|are) (?:still )?(?:left|remaining|pending|not done|undone)(?: to do)?$LEFT_TAIL|^what (?:else )?(?:is|do i have|do i still have|have i got|is there) (?:still )?left(?: to do)?$LEFT_TAIL")
    private const val LEFT_TAIL = "(?: (?:for |on |in )?(?:today|tonight|tomorrow|this month|this week))?(?: (?:on|in|from) (?:my |the )?(?:[a-z]+ )?(?:to-?do list|to do list|todo list|list|tasks|goals))?(?: (?:for )?(?:today|tomorrow|this month))?$"
    private val CHEAPEST = Regex("\\b(?:cheapest|least (?:costly|expensive|cost|money)|lowest cost|cost of the (?:least|smallest|cheapest|lowest)|smallest cost|least task)\\b")
    private val QUICKEST = Regex("\\b(?:quickest|shortest|fastest|easiest|smallest|least time)\\b")
    private val TOTAL_COST = Regex("^how much (?:will|would|does|do|is|did)\\b.*\\b(?:cost|costs|total|spend)\\b|\\btotal cost\\b|^how much money (?:do i|will i|would i|i)\\b")
    private val TOTAL_TIME = Regex("^how (?:long|much time) (?:will|would|does|do|is)\\b|\\btotal (?:time|duration|minutes|hours)\\b|^how much time\\b")
    /** "total time", "what's the total cost": about the list even without naming it. */
    private val BARE_TOTAL = Regex("^(?:what is |whats |what's )?(?:the |my )?total (?:time|cost|duration)$")
    private val TASKISH = Regex("\\b(?:tasks?|to-?dos|to dos|todos|to-?do list|to do list|todo list|list|goals?|things to do|jobs)\\b")

    /**
     * (form, done, strictness). Strictness 0: a clear command ("tick buy milk"). 1: "done with ...", the item must be
     * mostly named. 2: ordinary sentences too ("the rice is done", "I finished the milk" = it ran out): every word
     * of the item must be said, and never a shopping-style list item.
     */
    private val TICK = listOf(
        Triple(Regex("^(?:please )?(?:mark|tick|check|cross|strike|set)(?: off| out)? (.+?)(?: as)? (done|complete|completed|finished|off|not done|undone|incomplete|not finished)$"), null, 0),
        Triple(Regex("^(?:please )?(?:tick|check|cross|strike) off (.+)$"), true, 0),
        Triple(Regex("^(?:please )?tick (.+)$"), true, 0),
        Triple(Regex("^(?:please )?(?:untick|uncheck|unmark|untick off) (.+)$"), false, 0),
        Triple(Regex("^(.+?) (?:is|are) not (?:done|completed)(?: yet)?$"), false, 2),
        Triple(Regex("^(.+?) (?:is|are) (?:done|completed|complete)$"), true, 2),
        Triple(Regex("^(?:i am |i have |i )?(?:just )?done with (.+)$"), true, 1),
        Triple(Regex("^(?:i )?(?:just |already |have )*(?:finished|completed) (.+)$"), true, 2),
    )
    private val TICK_TODAY = Regex("\\s+(?:for |on )?(?:today|now|already|too|as well|just now)$")
    private val TICK_LIST_AFTER = Regex("^(.+?) ((?:as )?(?:done|complete|completed|finished|not done|undone|incomplete|not finished))( (?:on|in|from|of) (?:my |the |our )?(?:[a-z']+ ){0,2}?$TD)$")
    private val LIST_TAIL = Regex("\\s+(?:from|on|in|of) (?:my |the |our )?(.*?)\\b$TD\\b(.*)$")

    private val JOURNAL_START = listOf(
        Regex("^(?:(?:start|open|write|begin|new|record|take|make|add|do|create|keep|note)(?: in)?(?: a| my| the| an)? )?(?:(?:today's|todays|today|daily|a|my|new|the) )*(?:journal|diary)(?: entry| note| page)?(?: (?:for|about|of) (?:today|me|my day|myself|the day|today about me|my self))*$"),
        Regex("^(?:let me|i want to|i wanna|i would like to|time to|let us|i need to) (?:journal|write (?:in )?(?:my |a |today's )?(?:journal|diary)|write about (?:my|the|today's) day|talk about my day|record my day)$"),
        Regex("^(?:dear diary|journal time|diary time|journaling|journaling time)$"),
    )
    private val JOURNAL_INLINE = Regex("^(?:dear diary|journal|diary|today's journal|todays journal|journal entry|journal for today|today's journal about me)[:,]? (?:today )?(.+)$")
    private val JOURNAL_SHOW = listOf(
        Regex("^(?:please )?(?:show|open|read|see|play|view|give)(?: me)?(?: my| the)?(?: (.+?))? (?:journal|diary)(?: entries| entry| page| summary)?(?: (?:for|from|of|on) (.+))?$"),
        Regex("^(?:my |the )?(?:journal|diary)(?: entries| entry| page| summary)? (?:for|from|of|on) (.+)$"),
        Regex("^(?:my |the )?(.+?) (?:journal|diary)(?: entries| entry| page| summary)?$"),
        Regex("^what did i (?:write|say|journal|note|record)(?: in my (?:journal|diary))?(?: (?:on|for|about))? ?(.*)$"),
        Regex("^(?:what did i do|how was my day|how did my day go|what happened)(?: on| in)?(?: (.+))?$"),
        Regex("^what (?:is|was) in my (?:journal|diary)(?: (?:on|for|from))?(?: (.+))?$"),
    )

    fun parse(text: String, raw: String, nowMs: Long, tz: TimeZone): Understanding? {
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date
        journal(text, raw, nowMs, tz, today)?.let { return it }
        if (!TASKISH.containsMatchIn(text) && TICK.none { it.first.matches(text.replace(TICK_TODAY, "")) } && !LEFT.matches(text) && !BARE_TOTAL.containsMatchIn(text)) return null

        ADD.find(text)?.let { m ->
            // "write it on the list", "note my tasks in my list": no item named.
            val said = m.groupValues[1].trim().removeSuffix(" down").removeSuffix(" up").trim()
            val what = OWNER.replace(said, "").trim()
            if (said in setOf("it", "this", "that", "them", "these", "those", "something")) {
                // "note it on my shopping list": open that list and listen for what to add.
                keyFrom(m.groupValues[2], m.groupValues[3], today, nowMs, tz, m.groupValues[0])?.let { return Understanding.OpenList(it, listen = true) }
                return Understanding.OpenList(ListKey.day(today), listen = true)
            }
            if (what.isEmpty() || what == "down" || TD_WORD.containsMatchIn(what)) return@let
            val key = keyFrom(m.groupValues[2], m.groupValues[3], today, nowMs, tz, m.groupValues[0]) ?: return@let
            val items = app.kataa.core.lists.ListText.split(originalCase(m.groupValues[1], raw), nouns = key.startsWith("named:"))
            if (items.isNotEmpty()) return Understanding.AddToList(key, items)
        }
        REMOVE.find(text)?.let { m ->
            return Understanding.RemoveFromList(keyFrom(m.groupValues[2], m.groupValues[3], today, nowMs, tz, m.groupValues[0]), m.groupValues[1])
        }
        question(text, today, nowMs, tz)?.let { return it }
        // "mark eggs done on my shopping list" -> "mark eggs on my shopping list done"; "call didi is done today" -> "... is done".
        val tickText = text.replace(TICK_TODAY, "").replace(TICK_LIST_AFTER) { m -> "${m.groupValues[1]}${m.groupValues[3]} ${m.groupValues[2]}" }
        for ((r, done, level) in TICK) {
            val m = r.find(tickText) ?: continue
            var query = m.groupValues[1].trim()
            var key: String? = null
            LIST_TAIL.find(query)?.let { t ->
                key = keyFrom(t.groupValues[1], t.groupValues[2], today, nowMs, tz, text)
                query = query.substring(0, t.range.first).trim()
            }
            val isDone = done ?: (m.groupValues[2] !in setOf("not done", "undone", "incomplete", "not finished"))
            if (query.isNotEmpty()) return Understanding.Tick(key, query, isDone, strict = level)
        }
        return openList(text, raw, today, nowMs, tz)
    }

    /** "today's to-do list", "write my to do list for tomorrow", "november 19th to do list: buy a gift and call didi". */
    private fun openList(text: String, raw: String, today: LocalDate, nowMs: Long, tz: TimeZone): Understanding? {
        val td = TD_WORD.find(text) ?: return null
        var before = text.substring(0, td.range.first).trim()
        val after = text.substring(td.range.last + 1).trim()
        // The verb in front decides: "write my list" listens, "show my list" shows, nothing in front: show or listen.
        var listen = false
        var show = false
        val words = before.split(' ').filter { it.isNotEmpty() }
        for (n in words.size downTo 1) {
            val head = words.take(n).joinToString(" ")
            if (LISTEN_VERB.matches(head)) { listen = true; before = words.drop(n).joinToString(" "); break }
            if (SHOW_VERB.matches(head)) { show = true; before = words.drop(n).joinToString(" "); break }
        }
        before = OWNER.replace(before, "").trim()
        if (before.split(' ').size > 3) return null
        val (qualifier, rest) = splitQualifier(after, today, nowMs, tz)
        val noun = td.value
        val key = (if (before.isNotEmpty()) qualifierKey(before, today, nowMs, tz, noun) else null)
            ?: qualifier
            ?: (if (before.isEmpty()) defaultKey(noun, today) else null)
            ?: return null
        val colon = rest.startsWith(":") || rest.startsWith("-")
        val items = rest.replace(Regex("^(?:[:\\-]|is|are|includes?|has)\\s*"), "").trim()
        if (items.isNotEmpty()) {
            // "today's list is on the fridge", "my tasks are piling up", "the list of documents for visa is ...",
            // "my goals for this year are written ...": sentences about a list, not items. Items need a listen verb,
            // a colon, or a real to-do list with its day ("tomorrow's to do list pick up the parcel").
            val todoNoun = Regex("^(?:to-?do|to do|todo|task list|tasks|goals|goal list|check ?list)").containsMatchIn(noun)
            val dated = !key.startsWith("named:") && (qualifier != null || before.isNotEmpty())
            if (show) return null
            if (!listen && !colon && !(todoNoun && dated)) return null
            if (!colon && NOT_ITEMS.containsMatchIn(rest)) return null
            val split = app.kataa.core.lists.ListText.split(originalCase(items, raw), nouns = key.startsWith("named:"))
            return if (split.isEmpty()) null else Understanding.AddToList(key, split)
        }
        return Understanding.OpenList(key, listen = listen)
    }

    private fun question(text: String, today: LocalDate, nowMs: Long, tz: TimeZone): Understanding? {
        val ask = when {
            CHEAPEST.containsMatchIn(text) && (TASKISH.containsMatchIn(text) || text.contains("least task")) -> app.kataa.core.nlu.ListAsk.CHEAPEST
            QUICKEST.containsMatchIn(text) && TASKISH.containsMatchIn(text) -> ListAsk.QUICKEST
            TOTAL_COST.containsMatchIn(text) && (TASKISH.containsMatchIn(text) || BARE_TOTAL.matches(text)) -> ListAsk.TOTAL_COST
            TOTAL_TIME.containsMatchIn(text) && (TASKISH.containsMatchIn(text) || BARE_TOTAL.matches(text)) -> ListAsk.TOTAL_TIME
            COUNT.matches(text) -> ListAsk.COUNT
            LEFT.matches(text) -> ListAsk.LEFT
            else -> return null
        }
        return Understanding.ListQuestion(keyIn(text, today, nowMs, tz), ask)
    }

    /** A day or month named anywhere in a question ("how many tasks are done today", "... this month"). */
    private fun keyIn(text: String, today: LocalDate, nowMs: Long, tz: TimeZone): String {
        Regex("\\b(this month|next month|monthly|this week|tomorrow|yesterday|today|tonight)\\b").find(text)?.let { m ->
            return qualifierKey(m.value, today, nowMs, tz, "list") ?: ListKey.day(today)
        }
        Regex("\\b(?:for|on|of) (.+?)(?:'s)?(?: list| to do list)?$").find(text)?.let { m -> qualifierKey(m.groupValues[1], today, nowMs, tz, "list", namedOk = false)?.let { return it } }
        // "what's left on my shopping list": a list the user named.
        Regex("\\b(?:on|in|from|of) (?:my |the |our )?([a-z]+(?: [a-z]+)?) (?:list|to-?do list|to do list)\\b").find(text)?.let { m ->
            qualifierKey(m.groupValues[1], today, nowMs, tz, "list")?.let { return it }
        }
        if (Regex("\\bgoals?\\b").containsMatchIn(text)) return defaultKey("goals", today)
        return ListKey.day(today)
    }

    private fun keyFrom(before: String, after: String, today: LocalDate, nowMs: Long, tz: TimeZone, whole: String): String? {
        val b = OWNER.replace(before.trim(), "").trim()
        val noun = TD_WORD.find(whole)?.value ?: "list"
        if (b.isNotEmpty()) qualifierKey(b, today, nowMs, tz, noun)?.let { return it }
        splitQualifier(after.trim(), today, nowMs, tz).first?.let { return it }
        return defaultKey(noun, today)
    }

    private fun defaultKey(noun: String, today: LocalDate) =
        if (noun.startsWith("goal")) ListKey.month(today.year, today.month.ordinal + 1) else ListKey.day(today)

    /** The longest start of [after] that names a day or month ("for tomorrow", "for november 19th"); the rest is items. */
    private fun splitQualifier(after: String, today: LocalDate, nowMs: Long, tz: TimeZone): Pair<String?, String> {
        val a = after.replace(Regex("^(?:for|of|on|from|in)\\s+"), "").trim()
        if (a.isEmpty()) return null to ""
        val words = a.split(' ')
        for (n in minOf(5, words.size) downTo 1) {
            val q = words.take(n).joinToString(" ")
            qualifierKey(q, today, nowMs, tz, "list", namedOk = false)?.let { return it to words.drop(n).joinToString(" ") }
        }
        return null to a
    }

    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    /** "today's" / "tomorrow" / "november 19th" / "this month's" / "monthly" / "november" / "friday" / "shopping". */
    internal fun qualifierKey(q0: String, today: LocalDate, nowMs: Long, tz: TimeZone, noun: String, namedOk: Boolean = true): String? {
        val q = q0.trim().removeSuffix("'s").removeSuffix("'").replace(Regex("^(?:the|my|our) "), "").trim()
        if (q.isEmpty()) return null
        when (q) {
            "today", "todays", "tonight", "daily", "the day", "this day" -> return ListKey.day(today)
            "tomorrow", "tomorrows" -> return ListKey.day(today.plus(DatePeriod(days = 1)))
            "yesterday", "yesterdays" -> return ListKey.day(today.minus(DatePeriod(days = 1)))
            "day after tomorrow" -> return ListKey.day(today.plus(DatePeriod(days = 2)))
            "this month", "this months", "monthly", "month", "the month", "this month goals" -> return ListKey.month(today.year, today.month.ordinal + 1)
            "next month", "next months" -> today.plus(DatePeriod(months = 1)).let { return ListKey.month(it.year, it.month.ordinal + 1) }
            "inbox", "general", "main", "to do", "todo" -> return if (q == "inbox") ListKey.INBOX else ListKey.day(today)
        }
        MONTHS.indexOf(q.removeSuffix(" goals")).takeIf { it >= 0 }?.let { i ->
            val year = if (i + 1 < today.month.ordinal + 1) today.year + 1 else today.year
            return ListKey.month(year, i + 1)
        }
        dateOf(q, today, nowMs, tz, past = false)?.let { return ListKey.day(it) }
        if (namedOk && q.split(' ').size <= 2 && q.none(Char::isDigit) && q !in setOf("to", "for", "this", "that", "next", "last")) return ListKey.named(q)
        return null
    }

    /** "monday", "last friday", "19 november", "3 days ago", "the 19th". [past]: a weekday or date means the last one. */
    fun dateOf(q0: String, today: LocalDate, nowMs: Long, tz: TimeZone, past: Boolean): LocalDate? {
        val q = q0.trim().removeSuffix("'s").replace(Regex("^(?:on|for|from|of|the) "), "").trim()
        when (q) {
            "today", "todays", "tonight", "this morning", "this evening" -> return today
            "yesterday", "yesterdays", "last night" -> return today.minus(DatePeriod(days = 1))
            "day before yesterday" -> return today.minus(DatePeriod(days = 2))
            "tomorrow", "tomorrows" -> return today.plus(DatePeriod(days = 1))
        }
        Regex("^(\\d{1,2}|a|one|two|three|four|five|six|seven) days? ago$").find(q)?.let { m ->
            val n = Text.numberValue(m.groupValues[1]) ?: return null
            return today.minus(DatePeriod(days = n))
        }
        Regex("^(?:(last|this|next|coming) )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)$").find(q)?.let { m ->
            val dow = DayOfWeek.valueOf(m.groupValues[2].uppercase())
            val diff = (dow.ordinal - today.dayOfWeek.ordinal + 7) % 7
            val goBack = past || m.groupValues[1] == "last"
            return if (goBack) today.minus(DatePeriod(days = (7 - diff) % 7 + if (m.groupValues[1] == "last" && diff == 0) 7 else 0))
            else today.plus(DatePeriod(days = diff + if (m.groupValues[1] == "next" && diff == 0) 7 else 0))
        }
        // Dates: "19 november", "november 19th", "the 19th". Only when the words are a date and nothing else.
        if (!Regex("\\d|${MONTHS.joinToString("|")}").containsMatchIn(q)) return null
        if (q.split(' ').size > 4) return null
        // "24 november 2026": the time reader doesn't take years, so the year is set here.
        val year = Regex(" (20\\d\\d)$").find(q)?.groupValues?.get(1)?.toInt()
        val dq = if (year != null) q.substring(0, q.length - 5) else q
        val t = TimeParser.parse("on $dq", nowMs, tz) ?: return null
        if (t.range.first > 1 || t.range.last < dq.length + 2) return null
        var d = t.fireAt.toLocalDateTime(tz).date
        if (year != null) return runCatching { LocalDate(year, d.month, d.day) }.getOrNull()
        if (past && d > today) d = runCatching { LocalDate(d.year - 1, d.month, d.day) }.getOrNull() ?: return null
        return d
    }

    private fun journal(text: String, raw: String, nowMs: Long, tz: TimeZone, today: LocalDate): Understanding? {
        if (!Regex("\\b(?:journal|journaling|diary|what did i do|what did i (?:write|say|note|record)|how was my day|how did my day go|what happened)\\b").containsMatchIn(text)) return null
        if (JOURNAL_START.any { it.matches(text) }) return Understanding.Journal(null)
        for (r in JOURNAL_SHOW) {
            val m = r.find(text) ?: continue
            val words = m.groupValues.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                // "what did I do" alone is not about the journal; "show my journal" is today's.
                if (text.startsWith("what") || text.startsWith("how")) continue
                return Understanding.ShowJournal(today)
            }
            val day = words.firstNotNullOfOrNull { dateOf(it, today, nowMs, tz, past = true) } ?: continue
            return Understanding.ShowJournal(day)
        }
        JOURNAL_INLINE.find(text)?.let { m ->
            val body = m.groupValues[1]
            // "diary is in my bag" is about the diary itself.
            if (body.split(' ').size >= 4 && !Regex("^(?:my |the |a |today's |todays |today )?(?:diary|journal) (?:is|are|was|were|has|had|lies|sits|stays)\\b").containsMatchIn(text)) return Understanding.Journal(originalCase(body, raw))
        }
        return null
    }

    /** [normalized] as the user wrote it in [raw] (keeps "Didi", "Big Mart"), when it can be found. */
    private fun originalCase(normalized: String, raw: String): String {
        val i = raw.indexOf(normalized, ignoreCase = true)
        if (i >= 0) return raw.substring(i, i + normalized.length)
        // Normalizing dropped the commas ("buy milk, call didi"); they separate items, so find the words with them.
        val words = normalized.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return normalized
        val loose = Regex(words.joinToString("[\\s,]+") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        return loose.find(raw)?.value ?: normalized
    }
}
