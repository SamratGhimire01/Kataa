package app.kataa.core.memory

import app.kataa.core.model.Memory
import app.kataa.core.model.MemoryKind

/**
 * Passwords, PINs and codes. A memory is a secret from the moment it is saved, by its own words, not only after
 * the category classifier has run (which waits for embeddings): a password must never show in a list, a
 * notification or out loud before the user unlocks.
 */
object Secrets {
    private val WORD = Regex("\\b(?:password|passwords|passcode|pass code|pin|pin code|pin number|otp|cvv|pattern lock|lock code|locker code|combination|wifi key)\\b")
    /** "Facebook password is Samrat 001", "atm pin: 4455": what comes after the keyword is the secret value. */
    private val VALUE = Regex("(?i)\\b(password|passcode|pass code|pin|pin code|pin number|otp|cvv|lock code|locker code|combination|wifi key)(\\s+(?:is|=|:|was)\\s*|\\s*[:=]\\s*)(?!(?:on|in|at|under|inside|behind|near|with|kept|stored|written|saved|somewhere|the same)\\b)(\\S.*)$")

    fun looksSecret(body: String, kind: MemoryKind, subject: String?): Boolean {
        if (subject != null && WORD.containsMatchIn(subject.lowercase())) return true
        return kind != MemoryKind.REMINDER && WORD.containsMatchIn(body.lowercase()) && VALUE.containsMatchIn(body)
    }

    fun looksSecret(m: Memory): Boolean = looksSecret(m.body, m.kind, m.subject)

    /** "Facebook password is ••••••". Anything private without a recognizable value shows as "Private note". */
    fun masked(body: String): String {
        val m = VALUE.find(body) ?: return "Private note"
        return body.substring(0, m.groups[3]!!.range.first).trimEnd() + " " + DOTS
    }

    /** The secret value alone ("Samrat 001"), for copying. */
    fun value(body: String): String? = VALUE.find(body)?.groupValues?.get(3)?.trim()?.trimEnd('.')

    /** Which app or service it is for ("Facebook", "Wifi"), from "Facebook password is ...". */
    fun service(body: String): String? {
        val m = VALUE.find(body) ?: return null
        val before = body.substring(0, m.range.first).trim()
            .replace(Regex("(?i)^(?:my|the|our)\\s+"), "").trim()
        return before.ifEmpty { null }?.replaceFirstChar { it.uppercase() }
    }

    const val DOTS = "••••••"
}
