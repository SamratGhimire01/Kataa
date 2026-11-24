package app.kataa.core.lists

import app.kataa.core.nlu.Text

/** Turning a dictated list into items, and reading cost and duration out of one item. Rules only, instant. */
object ListText {
    data class Parsed(val body: String, val cost: Double?, val minutes: Int?)

    private val ORDINALS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")
    private val NUMBER_WORDS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")

    /** "number one", "number 2", "firstly", "second", "next", "then", "after that", "also", "lastly". */
    private val ORDINAL_MARK = Regex("(?i)\\b(?:number (?:one|two|three|four|five|six|seven|eight|nine|ten|\\d{1,2})|(?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)(?:ly)?|last(?:ly)?|finally)\\b[,:.]?\\s+")
    private val JOINER = Regex("(?i)[,;]?\\s+\\b(?:and then|and also|then|after that|next|also|plus)\\b[,:]?\\s+")
    /** "and" only splits before a new task verb: "buy bread and butter" stays one item, "buy bread and call mom" is two. */
    private val AND_TASK = Regex("(?i)\\s+and\\s+(?=(?:call|phone|text|message|email|buy|get|pick up|collect|pay|book|order|send|submit|return|renew|recharge|top up|bring|take|fix|clean|wash|charge|meet|visit|cancel|check|print|post|transfer|withdraw|deposit|apply|finish|complete|write|reply|read|study|go|cook|make|prepare|do|learn|practice|practise|exercise|run|walk|work|start|plan|water|feed|update|review|watch|clear|sort|file|repair|iron|drop|wish|attend|reach|ask|tell|book)\\b)")
    private val LEAD = Regex("(?i)^(?:[-*•·]\\s*|\\d{1,2}[.)]\\s*|i (?:need|have|want|got|must) to\\s+|i should\\s+|to\\s+|and\\s+|then\\s+)+")

    /**
     * Splits what the user dictated into items, in order. Keeps the user's own capitals. [nouns]: a shopping-style
     * list ("milk and eggs"), where a plain "and" separates items too.
     */
    fun split(dictation: String, nouns: Boolean = false): List<String> {
        var s = dictation.trim()
        if (s.isEmpty()) return emptyList()
        // Two or more ordinal markers ("first ... second ...") mean they are separators, not words of an item.
        if (ORDINAL_MARK.findAll(s).count() >= 2) s = s.replace(ORDINAL_MARK, "\n")
        s = s.replace(JOINER, "\n").replace(AND_TASK, "\n")
        if (nouns) s = s.replace(Regex("(?i)\\s+and\\s+"), "\n")
        return s.split(Regex("[\\n;]+|,\\s+|\\.\\s+(?=[A-Za-z])"))
            .map { it.trim().trimEnd('.', ',', ';').replace(LEAD, "").trim() }
            .filter { it.any(Char::isLetterOrDigit) }
    }

    private val COST = Regex("(?i)\\s*,?\\s*(?:for|costs?|cost of|costing|budget|price|around|about|approx(?:imately)?)?\\s*(?:(?:rs\\.?|npr|rupees|₹|\\$)\\s?(\\d[\\d,]*(?:\\.\\d+)?)|(\\d[\\d,]*(?:\\.\\d+)?)\\s?(?:rs\\.?|rupees?|rupiya|npr|dollars?|bucks)\\b)")
    private val DURATION = Regex("(?i)\\s*,?\\s*(?:for|takes?|taking|in|about|around)?\\s*(\\d{1,3}(?:\\.5)?|half an|an|a|one|two|three|four|five|ten|fifteen|twenty|thirty|forty|forty-five|fifty)\\s?(minutes?|mins?|hours?|hrs?|h)\\b")

    /** "Buy milk, 100 rupees" -> ("Buy milk", 100.0, null); "Study 2 hours" -> ("Study", null, 120). */
    fun parseItem(raw: String): Parsed? {
        var body = raw.trim()
        var cost: Double? = null
        var minutes: Int? = null
        COST.find(body)?.let { m ->
            cost = (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace(",", "").toDoubleOrNull()
            body = body.removeRange(m.range)
        }
        DURATION.find(body)?.let { m ->
            val n = when (val w = m.groupValues[1].lowercase()) {
                "half an" -> 0.5; "an", "a", "one" -> 1.0
                else -> w.toDoubleOrNull() ?: Text.numberValue(w)?.toDouble() ?: 1.0
            }
            val unit = m.groupValues[2].lowercase()
            minutes = if (unit.startsWith("h")) (n * 60).toInt() else n.toInt()
            body = body.removeRange(m.range)
        }
        body = body.replace(Regex("\\s+"), " ").trim().trimEnd(',', '.', ';', '-').trim()
            .replace(Regex("(?i)\\s+(?:for|which|that|and|it)$"), "").trim()
        if (body.isEmpty() || body.none(Char::isLetterOrDigit)) return null
        return Parsed(body.replaceFirstChar { it.uppercase() }, cost, minutes)
    }

    /** "number 2", "the second one", "2nd", "task three" -> 2/2/2/3. */
    fun ordinal(query: String): Int? {
        val q = Text.normalize(query).replace(Regex("^(?:the |my )?(?:task |item |number |no |thing )?"), "")
            .replace(Regex("\\s+(?:one|1|task|item|thing)$"), "").trim()
        q.removeSuffix("st").removeSuffix("nd").removeSuffix("rd").removeSuffix("th").toIntOrNull()?.let { return it.takeIf { n -> n in 1..99 } }
        ORDINALS.indexOf(q).takeIf { it >= 0 }?.let { return it + 1 }
        if (Regex("^(?:task|item|number|no) ").containsMatchIn(Text.normalize(query))) NUMBER_WORDS.indexOf(q).takeIf { it >= 0 }?.let { return it + 1 }
        return null
    }

    private val STOP = setOf("the", "a", "an", "my", "to", "of", "for", "and", "on", "in", "at", "with", "some", "from", "task", "item", "it", "that", "this", "is", "done")

    fun words(s: String): Set<String> = Text.normalize(s).split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 && it !in STOP }.map { stem(it) }.toSet()

    /** Share of the query's words found in the item. */
    fun overlap(query: Set<String>, item: Set<String>): Double = if (query.isEmpty()) 0.0 else query.count { it in item }.toDouble() / query.size

    private fun stem(w: String): String {
        val v = Text.baseVerb(w)
        return when {
            v != w -> v
            w.endsWith("ing") && w.length > 5 -> w.dropLast(3)
            w.endsWith("es") && w.length > 4 -> w.dropLast(2)
            w.endsWith("s") && w.length > 3 -> w.dropLast(1)
            else -> w
        }
    }
}
