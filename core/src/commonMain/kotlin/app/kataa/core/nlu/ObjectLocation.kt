package app.kataa.core.nlu

/** "my keys are in the blue drawer" -> subject "keys", location "in the blue drawer". */
data class ObjectLocationMatch(
    /** The thing, as spoken without determiners ("car keys"). */
    val thing: String,
    /** Stable key for linking saves and questions ("car key"). */
    val subjectKey: String,
    /** Preposition phrase ("in the blue drawer", "at level 3 b"). */
    val location: String,
)

object ObjectLocation {
    private const val PREP = "(in|inside|on|on top of|under|underneath|beneath|behind|beside|next to|near|by|at|in front of|between|above|below|with|into|onto|inside of)"
    private const val THING = "((?:(?:my|the|our|his|her|their|a|an)\\s+)?[\\p{L}\\p{N}' \\-]+?)"

    private val PUT = Regex("^(?:i |we )?(?:have |just |also )*(?:left|put|kept|placed|keep|leave|hid|stored|dropped|parked|stashed|tucked|hung|set)\\s+$THING\\s+$PREP\\s+(.+)$")
    private val IS = Regex("^$THING\\s+(?:is|are|was|were|is now|are now|is kept|are kept|is lying|are lying)\\s+$PREP\\s+(.+)$")
    private val PARKED = Regex("^(?:i |we )?(?:have |just )*parked\\s+$PREP\\s+(.+)$")

    fun extract(normalized: String): ObjectLocationMatch? {
        val s = stripLeadIn(normalized)
        PARKED.find(s)?.let { m -> return build("car", m.groupValues[1] + " " + m.groupValues[2]) }
        PUT.find(s)?.let { m -> return build(m.groupValues[1], m.groupValues[2] + " " + m.groupValues[3]) }
        IS.find(s)?.let { m -> return build(m.groupValues[1], m.groupValues[2] + " " + m.groupValues[3]) }
        return null
    }

    private fun build(rawThing: String, location: String): ObjectLocationMatch? {
        val thing = Text.stripDeterminers(rawThing)
        if (thing.isEmpty() || thing.split(' ').size > 4 || thing in PRONOUNS) return null
        val key = Text.subjectKey(thing) ?: return null
        return ObjectLocationMatch(thing, key, location.trim())
    }

    /** "remember that", "note:", "kataa," and similar openers. */
    fun stripLeadIn(s: String): String =
        s.replace(LEAD_IN, "").trim()

    internal val LEAD_IN = Regex(
        "^(?:(?:hey |ok |okay )?(?:kataa|kai)\\s*)?(?:please\\s+)?" +
            "(?:remember(?: that)?|note(?: that| down(?: that)?)?|save(?: that)?|log(?: that)?|keep in mind(?: that)?|" +
            "do not (?:let me )?forget(?: that)?|fyi|for the record)?\\s*[:,-]?\\s*",
    )

    private val PRONOUNS = setOf("i", "it", "this", "that", "they", "we", "he", "she", "you", "everything", "something")
}
