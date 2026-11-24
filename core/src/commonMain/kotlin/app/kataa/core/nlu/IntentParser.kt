package app.kataa.core.nlu

import app.kataa.core.model.MemoryKind
import kotlinx.datetime.TimeZone

enum class QuestionType { WHERE, DID_I, WHEN, GENERAL }

/** A password said by the user: which app or service it is for, and the value exactly as heard. */
data class Secret(val service: String, val value: String)

enum class ListAsk { COUNT, LEFT, CHEAPEST, QUICKEST, TOTAL_COST, TOTAL_TIME }

/** What the user meant, before anything is stored. */
sealed interface Understanding {
    data class Save(
        val body: String,
        val kind: MemoryKind,
        val subject: String?,
        val objectLocation: ObjectLocationMatch?,
        /** A fact with one current value ("my wifi password is ..."): saving it replaces the older one. */
        val replacesOld: Boolean = false,
        /** A password or PIN: Kataa shows it back for a yes before saving (voice often mishears them). */
        val secret: Secret? = null,
    ) : Understanding
    data class Remind(
        val body: String,
        val time: TimeMatch?,
        val moment: MomentMatch?,
        /** "keep reminding me", "nag me", "until I do it": the reminder comes back until marked done. */
        val persistent: Boolean,
    ) : Understanding
    data class Ask(
        val query: String,
        val type: QuestionType,
        /** Subject key for WHERE ("car key") and DID_I ("take med") questions. */
        val subject: String?,
        /** For DID_I: 0 = today, 1 = yesterday. */
        val daysAgo: Int = 0,
    ) : Understanding
    data class Correct(val newText: String) : Understanding
    data object Undo : Understanding
    /** "this is Big Mart", "save this place as home". [explicit] is false for "I'm at X", which may just be a note. */
    data class SavePlace(val name: String, val explicit: Boolean) : Understanding
    data class CreateCategory(val name: String, val description: String?) : Understanding
    data class BrowseCategory(val name: String, val fallbackQuery: String) : Understanding
    /** "What do I have today?", "what are my reminders": list reminders. [dayOffset] 0 = today, 1 = tomorrow, null = all. */
    data class Agenda(val dayOffset: Int?) : Understanding
    /** "I've got an idea": listen longer, then save what follows as an idea. */
    data object StartIdea : Understanding
    /** "Today's to-do list" said alone: show it, or listen for the items when it is empty. [listen]: "write my list" always listens. */
    data class OpenList(val key: String, val listen: Boolean) : Understanding
    /** "Add milk to my shopping list", "today's to do list: buy milk and call mom". */
    data class AddToList(val key: String, val items: List<String>) : Understanding
    /** "How many tasks are done?", "what's the cheapest task today?". */
    data class ListQuestion(val key: String, val ask: ListAsk) : Understanding
    /** "Mark buy milk done", "tick number 2", "untick the second one". [key] null = today's list, then the others. */
    data class Tick(val key: String?, val query: String, val done: Boolean, val strict: Int = 0) : Understanding
    /** "Remove milk from my shopping list". */
    data class RemoveFromList(val key: String?, val query: String) : Understanding
    /** "Today's journal", "dear diary": listen long, then save as today's journal entry. [text] when said inline. */
    data class Journal(val text: String?) : Understanding
    /** "Show my journal for yesterday", "what did I do on Monday". */
    data class ShowJournal(val day: kotlinx.datetime.LocalDate) : Understanding
    /** "Show me everything", "my timeline": every memory, list and journal day, newest first. */
    data object Timeline : Understanding
    /** Too short or ambiguous: the app should ask "Save this or search for it?". */
    data class Unclear(val text: String) : Understanding
}

/**
 * Rule-based intent detection. Fast (microseconds), predictable, and testable; an embedding-based
 * classifier and the optional on-device LLM can later handle what these rules leave as [Understanding.Unclear].
 */
object IntentParser {
    private val UNDO = Regex("^(?:undo|undo that|forget that|forget it|delete that|delete the last one|remove that|scratch that|never mind|nevermind|cancel that|that is wrong delete it)$")
    private val CORRECT = Regex("^(?:actually|no|nope|correction|sorry|i mean|i meant|wait)\\b[\\s,:-]*(?:it is|they are|it was|they were|i said)?\\s*(.+)$")
    private val CREATE_CATEGORY = Regex("^(?:please )?(?:make|create|add|start|new)(?: me)? (?:a )?(?:new )?(?:category|folder|section)(?: called| named| for)? (.+?)(?: for (.+))?$")
    private val BROWSE = Regex("^(?:show|open|list|see)(?: me)?(?: all)?(?: of)?(?: my)? (.+?)(?: stuff| things| notes| memories| category| items| list)?$")
    private val SAVE_PLACE_EXPLICIT = Regex("^(?:this is|this place is|here is|mark this(?: place)? as|save this(?: place| spot| location)? as|call this(?: place)?|remember this place as|remember this(?: place)? is) (?:the |my |a )?(.+)$")
    private val SAVE_PLACE_SOFT = Regex("^(?:i am|we are|i am now|i just reached|i reached|i am inside) (?:at|in) (?:the |my )?(.+)$")
    private val DID_I = Regex("^(?:did|have) i (?:already )?(.+?)(?: already)?(?: (today|this morning|tonight|yesterday|this evening))?$")
    private val WHERE = Regex("^where(?: is| are| was| were| did i (?:put|leave|keep|park|hide|place|store|drop)| have i (?:put|left|kept|parked)| do i keep| i put| is it that i (?:put|left))?(?: my| the| our)? (.+?)(?: again| now| at)?$")
    private val WHERE_PARKED = Regex("^where (?:did|have) (?:i|we) park(?:ed)?(?: the car| my car)?(?: again)?$|^where is (?:my |the )?(?:car|parking)$")
    private val QUESTION_START = Regex("^(?:give me|get me|tell me|show me|send me|i need|i want to know|i forgot|i do not remember|remind me what|what|when|where|who|whom|which|why|how|whose|is|are|was|were|do|does|did|can|could|have|has|had|will|should|would|tell me|find|search|look up|show me|any idea|do you know|remind me what|what about)\\b")
    private val REMIND_CUE = Regex("\\bremind me\\b|^(?:please )?remind (?:to|about)\\b|\\b(?:alert|notify|ping|nudge) me\\b|^(?:remember|do not forget|make sure i|make sure to|do not let me forget) to\\b|^(?:do not let me forget|do not forget|never let me forget)\\b|\\bnag me\\b|\\bkeep reminding me\\b")
    private val OBLIGATION = Regex("\\b(?:i |we )?(?:have to|need to|must|should|got to|gotta|ought to)\\b")
    private val PERSISTENT = Regex("\\b(?:keep reminding me|nag me|until i (?:do it|mark it|say done|finish)|do not let me forget|important|really important|must not forget)\\b")
    private val LOG_DONE = Regex("^(?:i |we )(?:just |already |have |have just )*(took|locked|fed|paid|turned off|switched off|turned on|switched on|closed|watered|gave|called|sent|charged|cleaned|took out|unplugged|plugged in|finished|did|submitted|returned|ate|drank|shut)\\b\\s*(.*)$")

    private val AGENDA = Regex(
        "^(?:what|anything)(?: else)?(?: do| have)? (?:i|we) (?:have|got|need to do|have to do|have planned)(?: to do| planned| on)?(?: (today|tonight|tomorrow|this week|now))?$" +
            "|^(?:what are|what is|show|list|show me|tell me|read|read me|what about)(?: all)?(?: of)? my (?:reminders|tasks|plans|plan|to dos|todos|to do list|schedule|agenda)(?: for)?(?: (today|tonight|tomorrow|this week))?$" +
            "|^(?:do i have |are there |i have )?any (?:reminders|tasks|plans)(?: for)?(?: (today|tonight|tomorrow|this week))?$" +
            "|^(?:my )?(?:reminders|agenda|schedule|plans)(?: for)?(?: (today|tonight|tomorrow|this week))?$",
    )
    private val IDEA = Regex("^(?:i |we )?(?:have |have got |got |have just got |just got )?(?:a |an |one |another |a new |new |big |a big )?(?:idea|thought)(?: for you| to save)?$" +
        "|^(?:note|save|write|remember|take)(?: down)? (?:this|my|an|a|one)(?: new)? idea$|^(?:let me|i want to|i wanna) (?:tell you|share)(?: an| my| one)? (?:idea|something|a thought)$|^(?:listen|start) idea(?: mode)?$|^idea time$")
    private val TIMELINE = Regex("^(?:please )?(?:show|open|give)?(?: me)? ?(?:everything|all my memories|all memories|all of it|all my stuff|my timeline|the timeline|timeline|everything i saved|everything i have saved)$")
    private val ALARM = Regex("^(?:please )?(?:can you |could you )?(?:wake me(?: up)?|set (?:an |the |my |a )?alarm|alarm)\\b(?: (?:for|at))?\\s*(.*)$")
    /** "write the password of wifi sunflower123", "save password for gmail is x". Group 1: what follows the keyword. */
    private val PW_OF = Regex("^(?:please )?(?:write|save|note|store|remember|keep|add|put)(?: down)?(?: that)?(?: my| the| this| a)? (?:password|passcode|pin|pin code) (?:of|for) (.+)$")
    /** "the password for gmail is x" said as a statement. */
    private val PW_FOR_IS = Regex("^(?:my |the |our )?(?:password|passcode|pin|pin code) (?:of|for) (?:my |the |our )?(.+?) (?:is|=) (.+)$")
    /** "save my instagram password samrat@123", "write wifi password as abc". */
    private val PW_SAVE = Regex("^(?:please )?(?:write|save|note|store|remember|keep|add|put)(?: down)?(?: that)?(?: my| the| our)? (.+?) (?:password|passcode|pin|pin code)(?: is| as| =)? (\\S.*)$")
    /** "netflix password is nf2026" without "my". */
    private val PW_BARE = Regex("^(?:the |our )?((?:[a-z0-9'-]+ ){0,2}?[a-z0-9'-]+) (?:password|passcode|pin|pin code) (?:is|=) (.+)$")
    /** "change my facebook password to x", "my new facebook password is x". */
    private val PW_CHANGE = Regex("^(?:please )?(?:change|update|set|reset|replace)(?: my| the| our)? (.+?) (?:password|passcode|pin|pin code)(?: to| as| into| with|=)? (\\S.*)$|^(?:my |the |our )?new (.+?) (?:password|passcode|pin|pin code) (?:is|=) (.+)$")

    /** "My facebook password is what" / "my locker code is what is": a question said backwards. */
    private val TRAILING_WHAT = Regex("\\b(?:is|are|was)? ?what(?: is| was| it is)?$")
    /** "My wifi password is abc123", "my locker code is 4412": a fact with a value. A newer one replaces the older. */
    private val KEY_VALUE = Regex("^(?:my|our|the) ((?:[a-z0-9']+ ){0,3}?(?:password|passcode|pin|pin code|code|number|no|id|username|user name|email|account|account number|login|otp|combination|size|blood group|birthday|address|plate|plate number|license number|policy number|locker|wifi|wi-fi)) (?:is|are|=) (.+)$")
    /** A task said on its own ("call mom", "buy milk"): becomes a reminder and Kataa asks when. */
    private val TASK_START = Regex("^(?:please )?(?:call|phone|ring|text|message|email|mail|buy|get(?! me\\b)|pick up|collect|pay|book|order|send|submit|return|renew|recharge|top up|bring|take|fix|clean|wash|charge|meet|visit|cancel|check|print|post|transfer|withdraw|deposit|apply for|finish|complete|write|reply to|wish|congratulate)\\b")
    private val NOT_A_TASK = Regex("\\b(?:is|are|was|were|am)\\b")

    private val HOME_WORDS = setOf("home", "house", "office", "work", "hostel", "room", "my room")

    fun parse(raw: String, nowMs: Long, tz: TimeZone, knownPlaceNames: Set<String> = emptySet(), lists: Boolean = true): Understanding {
        val text = Text.stripFillers(Text.normalize(raw))
        if (text.isEmpty()) return Understanding.Unclear(raw)
        val questionMark = Text.isQuestionMarked(raw)

        if (UNDO.matches(text)) return Understanding.Undo
        CORRECT.find(text)?.let { m ->
            val rest = m.groupValues[1].trim()
            if (rest.split(' ').size >= 2 || rest.startsWith("in ") || rest.startsWith("on ")) return Understanding.Correct(rest)
        }
        CREATE_CATEGORY.find(text)?.let { m ->
            return Understanding.CreateCategory(Text.sentence(m.groupValues[1].trim()), m.groupValues[2].trim().ifEmpty { null })
        }
        SAVE_PLACE_EXPLICIT.find(text)?.let { m -> return Understanding.SavePlace(m.groupValues[1].trim(), explicit = true) }
        SAVE_PLACE_SOFT.find(text)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.split(' ').size <= 3 && TimeParser.parse(text, nowMs, tz) == null) return Understanding.SavePlace(name, explicit = false)
        }

        if (IDEA.matches(text)) return Understanding.StartIdea
        if (TIMELINE.matches(text)) return Understanding.Timeline
        if (lists) ListParser.parse(text, raw, nowMs, tz)?.let { return it }
        AGENDA.find(text)?.let { m ->
            val day = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
            return Understanding.Agenda(when (day) { "today", "tonight", "now" -> 0; "tomorrow" -> 1; else -> null })
        }
        password(text, raw)?.let { return it }
        ALARM.find(text)?.let { m ->
            val wake = text.contains("wake")
            val whenText = m.groupValues[1].trim()
            val at = if (Regex("^\\d").containsMatchIn(whenText)) "at $whenText" else whenText
            val t = TimeParser.parse(at, nowMs, tz)
            if (t != null || whenText.isEmpty()) {
                return Understanding.Remind(if (wake) "Wake up" else "Alarm", t, null, persistent = true)
            }
        }

        // "my facebook password is what" / "... what is": the recognizer heard a question backwards.
        if (TRAILING_WHAT.containsMatchIn(text) && text.split(' ').size <= 10) {
            return Understanding.Ask(TRAILING_WHAT.replace(text, "").trim().ifEmpty { text }, QuestionType.GENERAL, null)
        }

        // Reminders before questions: "remind me what time..." is rare; "can you remind me to..." is common.
        val remindCue = REMIND_CUE.containsMatchIn(text)
        val time = TimeParser.parse(text, nowMs, tz)
        val moment = PlaceParser.parse(text, knownPlaceNames)
        val obligation = OBLIGATION.containsMatchIn(text)
        val task = TASK_START.containsMatchIn(text) && !NOT_A_TASK.containsMatchIn(text) && !questionMark
        if (remindCue || (obligation && (time != null || moment != null)) || task) {
            return Understanding.Remind(reminderBody(text, raw, time, moment), time, moment, PERSISTENT.containsMatchIn(text))
        }

        DID_I.find(text)?.let { m ->
            val action = m.groupValues[1]
            val daysAgo = if (m.groupValues[2] == "yesterday") 1 else 0
            return Understanding.Ask(text, QuestionType.DID_I, factKey(action), daysAgo)
        }
        if (WHERE_PARKED.matches(text)) return Understanding.Ask(text, QuestionType.WHERE, "car")
        WHERE.find(text)?.let { m ->
            val thing = m.groupValues[1].trim()
            if (thing.isNotEmpty() && thing.split(' ').size <= 4) return Understanding.Ask(text, QuestionType.WHERE, Text.subjectKey(thing))
        }
        BROWSE.find(text)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.split(' ').size <= 3) return Understanding.BrowseCategory(name, text)
        }
        if (questionMark || QUESTION_START.containsMatchIn(text)) {
            val type = if (text.startsWith("when") || text.startsWith("what time") || text.startsWith("what day")) QuestionType.WHEN else QuestionType.GENERAL
            return Understanding.Ask(text, type, null)
        }

        KEY_VALUE.find(text)?.let { m ->
            val value = m.groupValues[2].trim()
            if (value.isNotEmpty() && !value.startsWith("in ") && !value.startsWith("on ") && !value.startsWith("under ")) {
                return Understanding.Save(saveBody(raw), MemoryKind.FACT, Text.subjectKey(m.groupValues[1]), null, replacesOld = true)
            }
        }
        LOG_DONE.find(text)?.let { m ->
            val obj = m.groupValues[2]
            if (ObjectLocation.extract(text) == null) {
                return Understanding.Save(saveBody(raw), MemoryKind.FACT, factKey("${m.groupValues[1]} $obj"), null)
            }
        }
        ObjectLocation.extract(text)?.let { ol ->
            return Understanding.Save(saveBody(raw), MemoryKind.OBJECT_LOCATION, ol.subjectKey, ol)
        }
        val content = ObjectLocation.stripLeadIn(text)
        // A few words with no verb ("facebook password", "passport", "wifi code") is someone looking something
        // up, not saving it: a note always says something about the thing.
        if (content == text && looksLikeLookup(text)) return Understanding.Ask(text, QuestionType.GENERAL, null)
        if (content.split(' ').size < 2) return Understanding.Unclear(raw)
        return Understanding.Save(saveBody(raw), MemoryKind.NOTE, null, null)
    }

    private val VERBISH = Regex("\\b(?:is|are|am|was|were|be|been|has|have|had|do|does|did|will|can|put|kept|left|in|on|at|under|inside|behind|to|from|for|with|and|because|that|then|i|we|he|she|they|it)\\b")

    /** Up to three words and nothing that makes it a statement ("dad likes tea" has a verb in the middle). */
    private fun looksLikeLookup(text: String): Boolean {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.size !in 1..3 || VERBISH.containsMatchIn(text)) return false
        if (words.any { Regex("(?:ed|ing)$").containsMatchIn(it) && it.length > 4 }) return false
        if (words.size == 3 && words[1].endsWith("s") && !words[1].endsWith("ss")) return false
        return true
    }

    /** "take my meds" / "took the meds" -> "take med"; "locked the front door" -> "lock front door". */
    fun factKey(action: String): String? {
        val words = Text.normalize(action).split(' ').filter { it.isNotBlank() && it !in DETERMINERS }
        if (words.isEmpty()) return null
        val verb = Text.baseVerb(words.first())
        return (listOf(verb) + words.drop(1).map(Text::singular)).joinToString(" ")
    }

    /** Every way of saying a password: one current value per service, so a new one replaces the old. */
    private fun password(text: String, raw: String): Understanding.Save? {
        if (Text.isQuestionMarked(raw) || TRAILING_WHAT.containsMatchIn(text)) return null
        val (service, value) = PW_CHANGE.find(text)?.let { m ->
            if (m.groupValues[1].isNotEmpty()) m.groupValues[1] to m.groupValues[2] else m.groupValues[3] to m.groupValues[4]
        } ?: PW_FOR_IS.find(text)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: PW_OF.find(text)?.let { m ->
                // "wifi sunflower123" or "wifi is sunflower123": the last word is the password unless "is" splits it.
                val rest = m.groupValues[1].removePrefix("my ").removePrefix("the ")
                val i = rest.indexOf(" is ")
                when {
                    i > 0 -> rest.substring(0, i) to rest.substring(i + 4)
                    rest.contains(' ') -> rest.substringBeforeLast(' ') to rest.substringAfterLast(' ')
                    else -> return null
                }
            }
            ?: PW_SAVE.find(text)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: PW_BARE.find(text)?.takeIf { it.groupValues[1] !in setOf("my", "your", "his", "her", "what", "which") }?.let { it.groupValues[1] to it.groupValues[2] }
            ?: return null
        val svc = service.trim().removePrefix("my ").removePrefix("the ").trim()
        val v = value.trim()
        if (svc.isEmpty() || v.isEmpty() || svc.split(' ').size > 4 || v.startsWith("in ") || v.startsWith("on ")) return null
        // Said as a plain statement ("my wifi password is x"): keep the user's own words. Otherwise write it clearly.
        val statement = Regex("\\b(?:password|passcode|pin|pin code) (?:is|=) ").containsMatchIn(text) && !text.startsWith("change") && !Regex("\\bnew\\b").containsMatchIn(text)
        // "remember to change the wifi password tomorrow" is a reminder; "write down the password for the locker in my
        // notebook" says where it is written. Neither holds a password. ("find my iphone password is x" does.)
        if (Regex("^(?:to |about )").containsMatchIn(svc)) return null
        if (!statement && Regex("\\b(?:change|update|reset|check|ask|send|get|find|tell|give|share|forgot|forget)\\b").containsMatchIn(svc)) return null
        if (Regex("\\b(?:in|on|at|under|inside|behind|near|into)(?: my| the| a| our)?$|\\b(?:in|on|at|under|inside|behind|near|into) (?:my|the|a|our|his|her)\\b").containsMatchIn(svc)) return null
        if (Regex("^(?:today|tomorrow|tonight|later|soon|now|again|yesterday|later today|next week|this week|morning|evening)$").matches(v)) return null
        // "the wifi password is written on the router", "... stuck on the fridge": where it is, not what it is.
        if (Regex("^(?:written|stuck|kept|saved|stored|pasted|taped|hidden|noted|somewhere|on|in|at|under|inside|behind|near|with|in the|on the)\\b").containsMatchIn(v)) return null
        // "change the wifi password tomorrow morning", "... after 3 days": the value is a time, so this is a reminder.
        if (v.contains(' ') || v.all(Char::isLetter)) TimeParser.parse(v, 0L, TimeZone.UTC)?.let { t ->
            if (t.range.first == 0 && t.range.last >= v.length - 1) return null
        }
        val shownService = restoreCase(svc, raw).replaceFirstChar { it.uppercase() }
        val body = if (statement) saveBody(raw) else "$shownService password is ${originalCase(v, raw)}"
        return Understanding.Save(body, MemoryKind.FACT, Text.subjectKey("$svc password"), null, replacesOld = true,
            secret = Secret(shownService, originalCase(v, raw)))
    }

    /** [lower] as the user typed it (passwords are case-sensitive), when it can be found in [raw]. */
    private fun originalCase(lower: String, raw: String): String {
        val i = raw.lowercase().lastIndexOf(lower)
        return if (i >= 0) raw.substring(i, i + lower.length) else lower
    }

    /** The note as the user said it, minus "remember that" and similar, keeping their capitalization. */
    fun saveBody(raw: String): String {
        val trimmed = raw.trim().trimEnd('.', '!')
        val lowered = trimmed.lowercase()
        val m = ObjectLocation.LEAD_IN.find(lowered)
        val cut = if (m != null && m.range.first == 0) m.value.length else 0
        return Text.sentence(trimmed.substring(cut.coerceAtMost(trimmed.length)).trim().ifEmpty { trimmed })
    }

    private fun reminderBody(text: String, raw: String, time: TimeMatch?, moment: MomentMatch?): String {
        // Remove the time and moment spans (rightmost first so indexes stay valid).
        var s = text
        listOfNotNull(time?.range, moment?.range).sortedByDescending { it.first }.forEach { r ->
            if (r.first >= 0 && r.last < s.length) s = s.removeRange(r)
        }
        s = s.replace(Regex("\\s+"), " ").trim().replace(REMINDER_LEAD, "").replace(REMINDER_TAIL, "")
        s = s.replace(Regex("\\s+"), " ").trim().trimEnd(',', ' ')
        s = s.replace(Regex("(?:\\s+(?:and|to|at|in|on|when|by|please|that|then|the|a|on the|by the))+$"), "").trim()
        if (s.isEmpty()) s = text
        return Text.sentence(restoreCase(s, raw))
    }

    private val REMINDER_LEAD = Regex(
        "^(?:(?:hey |ok |okay )?(?:kataa|kai)\\s*)?(?:please |can you |could you |would you )*" +
            "(?:keep reminding me|remind me|remind|nag me|alert me|notify me|ping me|nudge me|remember|do not forget|do not let me forget|make sure i|make sure|note)?" +
            "(?: to| that| about)?\\s*(?:i |we )?(?:have to|need to|must|should|got to|gotta|ought to|will)?\\s*(?:to )?",
    )
    private val REMINDER_TAIL = Regex("\\s*(?:please|for me|okay|ok|thanks|thank you|until i do it|it is important|important)$")

    /** Puts back capitalization the user typed ("big mart" -> "Big Mart" if they wrote it that way). */
    private fun restoreCase(lower: String, raw: String): String {
        val originals = raw.split(Regex("[^\\p{L}\\p{N}'-]+")).filter { it.any(Char::isUpperCase) }.associateBy { it.lowercase() }
        return lower.split(' ').joinToString(" ") { originals[it] ?: it }
    }

    private val FILLER = setOf("my", "the", "a", "an", "our", "to", "please", "that", "i", "is", "are", "am", "at", "in", "on", "of", "and", "remember", "note", "save", "kataa", "okay", "ok")

    /** Same words in any order, ignoring filler and plurals: "Call my mom" == "call mom" == "Call mom." */
    fun sameKey(text: String): String =
        Text.normalize(text).split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() && it !in FILLER }.map(Text::singular).sorted().joinToString(" ")

    fun isHomeWord(name: String) = Text.stripDeterminers(name) in HOME_WORDS

    private val DETERMINERS = setOf("my", "the", "a", "an", "our", "his", "her", "their", "your", "some", "all", "of")
}
