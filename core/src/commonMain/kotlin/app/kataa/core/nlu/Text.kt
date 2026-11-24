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
        s = clockWords(s)
        return s.trim().trimEnd('.', '!', '?', ';', ':', ',').trim()
    }

    private val HESITATION = Regex("\\b(?:u+h+|u+m+|uhm+|hmm+|e+r+m*|ah+)\\b")
    private val LEAD_INTERJECTION = Regex("^(?:(?:ugh|oh|ah|so|ok so|okay so|well|bro|yaar|dude|listen|hey kataa|kataa)\\s+)+")
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
    private val MINUTE_WORDS = mapOf("o five" to "05", "ten" to "10", "fifteen" to "15", "twenty" to "20", "thirty" to "30", "forty" to "40", "forty five" to "45", "forty-five" to "45", "fifty" to "50")
    private const val HOURW = "(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"
    private const val MINW = "(o five|forty five|forty-five|fifteen|twenty|thirty|forty|fifty|ten)"
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
        s = s.replace(Regex("\\b(at|by|around) $HOURW\\b")) { m -> "${m.groupValues[1]} ${HOUR_WORDS[m.groupValues[2]]}" }
        s = s.replace(Regex("\\b(\\d{1,2}) (\\d{2})(?=\\s*(?:am|pm|a\\.m|p\\.m)\\b)"), "$1:$2")
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
