package app.kataa.core.nlu

/**
 * Text clean-up shared by the understanding layer. Input is speech-recognizer or keyboard text in English.
 */
object Text {
    private val CONTRACTIONS = listOf(
        "where's" to "where is", "what's" to "what is", "when's" to "when is", "who's" to "who is",
        "how's" to "how is", "it's" to "it is", "that's" to "that is", "there's" to "there is",
        "they're" to "they are", "we're" to "we are", "you're" to "you are", "i'm" to "i am",
        "i've" to "i have", "i'll" to "i will", "i'd" to "i would", "don't" to "do not",
        "doesn't" to "does not", "didn't" to "did not", "can't" to "can not", "won't" to "will not",
        "isn't" to "is not", "aren't" to "are not", "wasn't" to "was not", "haven't" to "have not",
        "let's" to "let us",
    )

    private val NUMBER_WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "forty-five" to 45, "fifty" to 50,
        "couple" to 2, "few" to 3,
    )

    /** Lowercase, straight quotes, expanded contractions, single spaces, no trailing punctuation. */
    fun normalize(raw: String): String {
        var s = raw.trim().lowercase()
            .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        for ((from, to) in CONTRACTIONS) s = s.replace(Regex("\\b" + Regex.escape(from) + "\\b"), to)
        s = s.replace(Regex("[\\s,]+"), " ").replace(Regex(" ([.!?;:])"), "$1")
        // "7 p.m.", "a. m.": the way speech recognizers write it.
        s = s.replace(Regex("(?<=\\d)([ap])\\. ?m\\b\\.?(?![\\w.])|(?<=\\d )([ap])\\. ?m\\b\\.?(?![\\w.])|^([ap])\\. ?m\\b\\.?$")) { m ->
            (m.groupValues[1] + m.groupValues[2] + m.groupValues[3]) + "m"
        }
        s = clockWords(s)
        s = ordinalDates(s)
        return s.trim().trimEnd('.', '!', '?', ';', ':', ',').trim()
    }

    private val HESITATION = Regex("\\b(?:u+h+|u+m+|uhm+|hmm+|e+r+m*|ah+)\\b")
    private val LEAD_INTERJECTION = Regex("^(?:(?:ok google|okay google|hey google|hey|hi|hello|ok|okay|ugh|oh|ah|so|ok so|okay so|well|bro|yaar|dude|listen|hey kataa|kataa)\\s+)+")
    private val SWEAR = Regex("\\b(?:stupid|damn|damned|freaking|fucking|bloody|silly|goddamn)\\s+")
    private val TRAIL = Regex("\\s+(?:bro|yaar|dude|man|please)$")

    /**
     * Drops what people say while thinking ("uh", "um", "ugh ...", "the stupid charger", "at like 7"), so the rules
     * see the sentence itself. Never removes content words: "I like tea" keeps "like".
     */
    fun stripFillers(normalized: String): String {
        var s = normalized.replace(HESITATION, " ").replace(Regex("\\s+"), " ").trim()
        val lead = s.replace(LEAD_INTERJECTION, "")
        if (lead.split(' ').size >= 2) s = lead
        s = s.replace(SWEAR, "")
        s = s.replace(Regex("\\blike (?=\\d|around|about|half|quarter)"), "")
        s = s.replace(TRAIL, "")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private val HOUR_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    )
    private val MINUTE_WORDS: Map<String, String> = buildMap {
        val units = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        units.forEachIndexed { i, u -> put("o $u", "0${i + 1}"); put("oh $u", "0${i + 1}") }
        listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
            .forEachIndexed { i, w -> put(w, "${10 + i}") }
        listOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50).forEach { (w, n) ->
            put(w, "$n"); units.forEachIndexed { i, u -> put("$w $u", "${n + i + 1}"); put("$w-$u", "${n + i + 1}") }
        }
    }
    private const val HOURW = "(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"
    private const val MINW = "((?:o|oh) (?:one|two|three|four|five|six|seven|eight|nine)|(?:twenty|thirty|forty|fifty)(?:[ -](?:one|two|three|four|five|six|seven|eight|nine))?|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen)"
    /** "alarm for 6 45": after "for", only a clock that ends the sentence or comes before a day or am/pm. */
    private const val CLOCK_END = "(?=\\s*(?:am|pm|tomorrow|today|tonight|in the|on|every|$))"
    private const val AMPM = "(?=\\s*(?:am|pm|a\\.m|p\\.m|o'clock|oclock|in the|tonight|tomorrow|today|$))"

    /**
     * Spoken clock times to digits, so the time parser sees one form: "two thirty pm" -> "2:30 pm",
     * "at five" -> "at 5", "6 30 pm" -> "6:30 pm", "half past 4" -> "4:30", "quarter to 5" -> "4:45".
     */
    private fun clockWords(input: String): String {
        var s = input
        s = s.replace(Regex("\\bhalf past (\\d{1,2}|$HOURW)\\b")) { m -> "${hour(m.groupValues[1])}:30" }
        s = s.replace(Regex("\\bquarter past (\\d{1,2}|$HOURW)\\b")) { m -> "${hour(m.groupValues[1])}:15" }
        s = s.replace(Regex("\\bquarter to (\\d{1,2}|$HOURW)\\b")) { m -> "${(hour(m.groupValues[1]) + 11) % 12 + 1}:45" }
        s = s.replace(Regex("\\b$HOURW $MINW$AMPM")) { m -> "${HOUR_WORDS[m.groupValues[1]]}:${MINUTE_WORDS[m.groupValues[2]]}" }
        s = s.replace(Regex("\\b(\\d{1,2}) $MINW$AMPM")) { m -> "${m.groupValues[1]}:${MINUTE_WORDS[m.groupValues[2]]}" }
        s = s.replace(Regex("\\b$HOURW$AMPM")) { m -> HOUR_WORDS[m.groupValues[1]].toString() }
        s = s.replace(Regex("\\b(\\d{1,2}) (\\d{2})(?=\\s*(?:am|pm|a\\.m|p\\.m)\\b)"), "$1:$2")
        // "at seven fifteen to take pills", "alarm for 6 45", "at 5 30 tomorrow": an hour and minutes after at/by/for.
        s = s.replace(Regex("\\b(at|by|around) $HOURW $MINW\\b(?![-\\w]| (?:minutes?|mins?|hours?|days?|weeks?|years?|people|times|kg|rupees?))|\\b(for) $HOURW $MINW$CLOCK_END")) { m ->
            val g = if (m.groupValues[1].isNotEmpty()) m.groupValues.subList(1, 4) else m.groupValues.subList(4, 7)
            "${g[0]} ${HOUR_WORDS[g[1]]}:${MINUTE_WORDS[g[2]]}"
        }
        s = s.replace(Regex("\\b(at|by|around) (\\d{1,2}) ([0-5]\\d)\\b(?![-\\w]| (?:minutes?|mins?|hours?|days?|weeks?|years?|year olds?|people|times|kg|rupees?|of))|\\b(for) (\\d{1,2}) ([0-5]\\d)$CLOCK_END")) { m ->
            val g = if (m.groupValues[1].isNotEmpty()) m.groupValues.subList(1, 4) else m.groupValues.subList(4, 7)
            if (g[1].toInt() <= 23) "${g[0]} ${g[1]}:${g[2]}" else m.value
        }
        s = s.replace(Regex("\\b(at|by|around) $HOURW\\b")) { m -> "${m.groupValues[1]} ${HOUR_WORDS[m.groupValues[2]]}" }
        return s
    }

    private val ORDINAL_WORDS = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8,
        "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12, "thirteenth" to 13, "fourteenth" to 14,
        "fifteenth" to 15, "sixteenth" to 16, "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19, "twentieth" to 20,
        "thirtieth" to 30,
    )
    private const val MONTHS = "(?:january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)"
    private const val MONTHS_NOT_NAMES = "(?:january|february|march|april|june|july|august|september|october|november|december|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)"
    private const val ORDW = "(?:(twenty|thirty)[ -])?(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth|thirtieth)"

    private fun ordinalNumber(tens: String, unit: String): String? {
        val n = (when (tens) { "twenty" -> 20; "thirty" -> 30; else -> 0 }) + (ORDINAL_WORDS[unit] ?: return null)
        if (n !in 1..31 || tens.isNotEmpty() && ORDINAL_WORDS.getValue(unit) > 9) return null
        val sfx = if (n % 10 == 1 && n != 11) "st" else if (n % 10 == 2 && n != 12) "nd" else if (n % 10 == 3 && n != 13) "rd" else "th"
        return "$n$sfx"
    }

    /** "march twenty first", "the twenty third of may": spoken day numbers next to a month, as digits ("march 21st"). */
    private fun ordinalDates(input: String): String {
        var s = input.replace(Regex("\\b($MONTHS_NOT_NAMES|(?<=\\b(?:on|is|in|of|by|until|till) )(?:may|jan)) (?:the )?$ORDW\\b(?! thing)")) { m ->
            ordinalNumber(m.groupValues[2], m.groupValues[3])?.let { "${m.groupValues[1]} $it" } ?: m.value
        }
        s = s.replace(Regex("\\b$ORDW(?= of $MONTHS\\b)")) { m -> ordinalNumber(m.groupValues[1], m.groupValues[2]) ?: m.value }
        return s
    }

    private fun hour(w: String) = w.toIntOrNull() ?: HOUR_WORDS[w] ?: 0

    fun isQuestionMarked(raw: String): Boolean = raw.trim().endsWith("?")

    fun numberValue(token: String): Int? = token.toIntOrNull() ?: NUMBER_WORDS[token]

    /** "my blue keys" -> "blue keys"; leading articles and possessives removed. */
    fun stripDeterminers(phrase: String): String =
        phrase.trim().replace(Regex("^(?:(?:my|the|a|an|our|his|her|their|your|some|this|that)\\s+)+"), "").trim()

    /**
     * Stable key for a thing, used to link "my keys are..." with "where are my keys".
     * Determiners removed, each word singularized with simple rules (applied the same way on save and on ask).
     */
    fun subjectKey(phrase: String): String? =
        stripDeterminers(normalize(phrase)).split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { singular(it) }.ifEmpty { null }

    fun singular(w: String): String = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") || w.endsWith("sses")) -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") -> w.dropLast(1)
        else -> w
    }

    /** First letter uppercase, the rest untouched. */
    fun sentence(s: String): String = s.trim().replaceFirstChar { it.uppercase() }

    /** Common irregular past forms -> base verb, for "I took my meds" ~ "did I take my meds". */
    val IRREGULAR_PAST = mapOf(
        "took" to "take", "gave" to "give", "fed" to "feed", "paid" to "pay", "put" to "put", "left" to "leave",
        "kept" to "keep", "sent" to "send", "brought" to "bring", "bought" to "buy", "ate" to "eat", "drank" to "drink",
        "wrote" to "write", "made" to "make", "did" to "do", "went" to "go", "shut" to "shut", "set" to "set",
        "got" to "get", "hid" to "hide", "rang" to "ring", "called" to "call", "told" to "tell", "met" to "meet",
        "read" to "read", "spent" to "spend", "lent" to "lend", "returned" to "return",
    )

    /** "locked" -> "lock", "took" -> "take", "watered" -> "water". Good enough for log subjects. */
    fun baseVerb(w: String): String = IRREGULAR_PAST[w] ?: when {
        w.length > 4 && w.endsWith("ied") -> w.dropLast(3) + "y"
        w.length > 5 && w.endsWith("ed") && w[w.length - 3] == w[w.length - 4] && w[w.length - 3] !in "lsz" -> w.dropLast(3) // stopped -> stop
        w.length > 4 && w.endsWith("ed") && (w.endsWith("ated") || w.endsWith("osed") || w.endsWith("ured") || w.endsWith("ived") || w.endsWith("aced")) -> w.dropLast(1)
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        else -> w
    }
}
