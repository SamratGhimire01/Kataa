package app.kataa.core.mind

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A small on-device language model. One chat turn in, text out. Must be called off the main thread. */
interface LanguageModel {
    /**
     * Answers [user] given [system]. [grammar] (GBNF) constrains the output, so JSON always parses.
     * Returns null if the model failed or ran past [budgetMs] (0 = no limit). Each [slot] (0 or 1) keeps its own
     * system prompt cached between calls.
     */
    fun complete(slot: Int, system: String, user: String, grammar: String?, maxTokens: Int, budgetMs: Int = 0): String?
}

/** One thing the user wants, rewritten into a form Kataa's fast rules understand. */
sealed interface Command {
    data class Save(val text: String) : Command
    data class Remind(val text: String) : Command
    data class Ask(val text: String) : Command
    data class Place(val name: String) : Command
    data object Undo : Command
    data object Unclear : Command
}

/** Which saved note answers a question, and the answer in a few words. */
data class Pick(val index: Int, val answer: String)

/**
 * Kataa's "mind": the language model used only where the rules are unsure. Two jobs:
 *  1. [understand]: turn a messy, long or misheard sentence into one to three clean commands.
 *  2. [pickAnswer]: given a question and the closest saved notes, say which note answers it, briefly.
 * The prompts are short and fixed, with examples, so the cached system prompt does most of the work.
 */
class Mind(private val model: LanguageModel) {

    /**
     * How long the user may wait for the model, per call. Past it Kataa answers with its rules: the AI is there
     * to make replies better, never to make them slow. 0 = no limit (tests that measure quality).
     */
    @kotlin.concurrent.Volatile var budgetMs: Int = DEFAULT_BUDGET_MS

    fun understand(said: String, today: String): List<Command>? {
        val raw = model.complete(0, UNDERSTAND_SYSTEM, "Today is $today.\nUser said: \"${said.trim().take(400)}\"", UNDERSTAND_GRAMMAR, 120, budgetMs)
            ?: return null
        val parsed = runCatching { json.decodeFromString<CommandsJson>(raw) }.getOrNull() ?: return null
        return parsed.commands.mapNotNull(::toCommand).take(3).ifEmpty { null }
    }

    /** [notes] are the search's best candidates, best first (at most 5). */
    /** Reads both system prompts once, so the first real question is as fast as the rest. */
    fun warmUp() {
        model.complete(0, UNDERSTAND_SYSTEM, "Today is Monday.\nUser said: \"hi\"", UNDERSTAND_GRAMMAR, 1)
        model.complete(1, PICK_SYSTEM, "Question: hi\nNotes:\n1. hi", PICK_GRAMMAR, 1)
    }

    fun pickAnswer(question: String, notes: List<String>): Pick? {
        if (notes.isEmpty()) return null
        val list = notes.take(5).mapIndexed { i, n -> "${i + 1}. ${n.trim().take(300)}" }.joinToString("\n")
        val raw = model.complete(1, PICK_SYSTEM, "Question: ${question.trim().take(300)}\nNotes:\n$list", PICK_GRAMMAR, 60, budgetMs) ?: return null
        val p = runCatching { json.decodeFromString<PickJson>(raw) }.getOrNull() ?: return null
        if (p.note !in 1..notes.size.coerceAtMost(5)) return Pick(0, "")
        return Pick(p.note, p.answer.trim())
    }

    private fun toCommand(s: String): Command? {
        val t = s.trim()
        fun after(prefix: String) = t.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
        return when {
            t == "UNDO" -> Command.Undo
            t == "UNCLEAR" -> Command.Unclear
            t.startsWith("SAVE:") -> after("SAVE:")?.let(Command::Save)
            t.startsWith("REMIND:") -> after("REMIND:")?.let(Command::Remind)
            t.startsWith("ASK:") -> after("ASK:")?.let(Command::Ask)
            t.startsWith("PLACE:") -> after("PLACE:")?.let(Command::Place)
            else -> null
        }
    }

    @Serializable private class CommandsJson(val commands: List<String> = emptyList())
    @Serializable private class PickJson(val note: Int = 0, val answer: String = "")

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val UNDERSTAND_SYSTEM = """
You are the understanding layer of Kataa, a personal memory app. The user talks to it by voice to save things,
set reminders and ask where things are. Speech recognition makes mistakes, so fix words that are clearly misheard
("call more" means "call mom", "facebook passport" means "facebook password", "where is my kiss" means "where are my keys").

Rewrite what the user said as 1 to 3 commands. Each command is one of:
SAVE: <the fact, as a short sentence in the user's voice>
REMIND: remind me to <task> <when, e.g. "at 5 pm", "tomorrow morning", "every day at 9 am", or where, e.g. "when I reach Big Mart", "when I get home">
ASK: <a short question, e.g. "where are my keys", "what is my wifi password", "when is the dentist appointment">
PLACE: <shop or place name> (ONLY when the user says they are at that place right now, like "I'm at Big Mart" or "this is the gym")
UNDO (they want to delete or cancel the last thing)
UNCLEAR (nothing to save, remind or ask)

Rules:
- Keep names, numbers, passwords and codes exactly as said. Never invent facts.
- Anything the user has to do ("I have to", "I need to", "don't forget", "call mom", "buy milk") is REMIND, with the time if one was said.
- Where an object is ("my bike is parked near the gate") is SAVE, never PLACE.
- Keep days and dates as said ("day after tomorrow" stays "day after tomorrow", "after 20 days" stays "after 20 days").
- Turn vague times into clear ones: "after lunch" -> "at 2 pm", "tonight" -> "at 8 pm", "in the evening" -> "at 6 pm", "next week" -> "next Monday at 9 am".
- "tell me", "give me", "what was", "I forgot" followed by a thing is ASK.
- Statements about where something is, what something is, or what happened are SAVE.
- Split sentences that contain more than one request.

Examples:
User said: "um so I kept the spare car key inside the red box in the cupboard"
{"commands":["SAVE: The spare car key is inside the red box in the cupboard"]}
User said: "call more"
{"commands":["REMIND: remind me to call mom"]}
User said: "facebook passport"
{"commands":["ASK: what is my facebook password"]}
User said: "my facebook password is what"
{"commands":["ASK: what is my facebook password"]}
User said: "I need to pay the electricity bill sometime after lunch tomorrow and also my locker code is 4412"
{"commands":["REMIND: remind me to pay the electricity bill tomorrow at 2 pm","SAVE: My locker code is 4412"]}
User said: "I have to return the library book on Friday and my scooter is parked behind the bank"
{"commands":["REMIND: remind me to return the library book on Friday at 9 am","SAVE: My scooter is parked behind the bank"]}
User said: "don't let me forget the milk when I'm at big mart"
{"commands":["REMIND: remind me to buy milk when I reach Big Mart"]}
User said: "where did I put that thing, the charger"
{"commands":["ASK: where is my charger"]}
User said: "no no delete what I just said"
{"commands":["UNDO"]}
User said: "ok I'm at bhatbhateni now remember this shop"
{"commands":["PLACE: Bhatbhateni"]}
User said: "the dentist said come back on 14th December at 10"
{"commands":["SAVE: Dentist appointment on 14 December at 10 am","REMIND: remind me about the dentist appointment on 14 December at 9 am"]}
User said: "hmm okay"
{"commands":["UNCLEAR"]}

Reply with JSON only.
""".trim()

        private val UNDERSTAND_GRAMMAR = """
root ::= "{\"commands\":[" cmd ("," cmd)? ("," cmd)? "]}"
cmd ::= "\"" ( ("SAVE: " | "REMIND: " | "ASK: " | "PLACE: ") txt | "UNDO" | "UNCLEAR" ) "\""
txt ::= [^"\\\n] [^"\\\n]*
""".trim()

        const val DEFAULT_BUDGET_MS = 1500

        val PICK_SYSTEM = """
You answer questions using only the user's own saved notes in a memory app.
You get a question and numbered notes. Reply with the number of the note that answers the question, and the answer
in as few words as possible, copied from that note (keep names, numbers and passwords exactly).
If no note answers the question, reply with note 0 and an empty answer. Never guess and never use outside knowledge.

Example:
Question: what is my wifi password
Notes:
1. My facebook password is Samrat 001
2. Wifi password is sunflower123
{"note":2,"answer":"sunflower123"}

Example:
Question: where are my glasses
Notes:
1. My keys are in the blue drawer
{"note":0,"answer":""}

Reply with JSON only.
""".trim()

        private val PICK_GRAMMAR = """
root ::= "{\"note\":" [0-5] ",\"answer\":\"" [^"\\\n]* "\"}"
""".trim()
    }
}
