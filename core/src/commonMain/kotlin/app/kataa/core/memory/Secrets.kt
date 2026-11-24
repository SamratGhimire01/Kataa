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
    /** "Facebook password is Samrat 001", "atm pin: 4455", "my pin for the bank is 1234": after the keyword is the value. */
    private val VALUE = Regex("(?i)\\b(password|passcode|pass code|pin|pin code|pin number|otp|cvv|lock code|locker code|combination|wifi key)(\\s+(?:for|at|in|of)\\s+(?:the\\s+|my\\s+|our\\s+)?[\\w'-]+(?:\\s+[\\w'-]+)?(?=\\s+(?:is|was)\\b|\\s*=))?(\\s+(?:is|=|:|was)\\s*|\\s*[:=]\\s*)(?!(?:on|in|at|under|inside|behind|near|with|kept|stored|written|saved|somewhere|the same|not|wrong|too|expired|blocked|incorrect|broken|forgotten|lost|locked)\\b)(\\S.*)$")

    fun looksSecret(body: String, kind: MemoryKind, subject: String?): Boolean {
        if (subject != null && WORD.containsMatchIn(subject.lowercase())) return true
        return kind != MemoryKind.REMINDER && WORD.containsMatchIn(body.lowercase()) && VALUE.containsMatchIn(body)
    }

    fun looksSecret(m: Memory): Boolean = looksSecret(m.body, m.kind, m.subject)

    /** "Facebook password is ••••••". Anything private without a recognizable value shows as "Private note". */
    fun masked(body: String): String {
        val m = VALUE.find(body) ?: return "Private note"
        return body.substring(0, m.groups[4]!!.range.first).trimEnd() + " " + DOTS
    }

    /** The secret value alone ("Samrat 001"), for copying. */
    fun value(body: String): String? = VALUE.find(body)?.groupValues?.get(4)?.trim()?.trimEnd('.')

    private val OWNER = Regex("(?i)^(?:(?:my|the|our)(?:\\s+|$))+")

    /** Which app or service it is for ("Facebook", "Wifi", "bank" from "my pin for the bank is ..."); null when none is named. */
    fun service(body: String): String? {
        val m = VALUE.find(body) ?: return null
        val before = body.substring(0, m.range.first).trim().replace(OWNER, "").trim()
        val forPart = m.groupValues[2].trim().replace(Regex("(?i)^(?:for|at|in|of)\\s+"), "").replace(OWNER, "").trim()
        return before.ifEmpty { forPart }.ifEmpty { null }
    }

    /** The body with a new value in place of the old one: "Wifi password at home is NEW1". */
    fun replaceValue(body: String, value: String): String? {
        if (value.isBlank()) return null
        val g = VALUE.find(body)?.groups?.get(4) ?: return null
        return body.substring(0, g.range.first) + value.trim()
    }

    /** "pin", "passcode", "password", "otp", ...: what kind of secret it is. */
    fun kind(body: String): String = VALUE.find(body)?.groupValues?.get(1)?.lowercase()?.let { if (it.startsWith("pin")) "pin" else it } ?: "password"

    /** What the secret is called: "Wifi password", "ATM PIN", "PIN". */
    fun name(body: String): String? {
        if (VALUE.find(body) == null) return null
        return app.kataa.core.nlu.Secret.nameOf(service(body) ?: "", kind(body))
    }

    /** The body and subject for a secret written from its parts (the app's edit, a corrected confirm card). */
    fun compose(service: String, kind: String, value: String): Pair<String, String> {
        val svc = service.trim()
        val name = app.kataa.core.nlu.Secret.nameOf(svc, kind)
        val body = (if (svc.isEmpty()) "My " + (if (kind.startsWith("pin")) "PIN" else kind) else name) + " is " + value.trim()
        val key = if (svc.isEmpty()) kind else "${svc.lowercase()} $kind"
        return body to (app.kataa.core.nlu.Text.subjectKey(key) ?: key)
    }

    const val DOTS = "••••••"
}
