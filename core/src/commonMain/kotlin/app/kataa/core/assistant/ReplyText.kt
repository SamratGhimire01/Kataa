package app.kataa.core.assistant

/** The short sentence Kataa shows and speaks for each reply. One place, so screen and voice agree. */
object ReplyText {
    /**
     * What Kataa says out loud. Same as [of], except it never speaks a password or PIN (someone may be nearby):
     * the screen shows it, the voice only says what it is.
     */
    fun spoken(r: Reply): String = when {
        r is Reply.ConfirmSecret -> "Is this ${r.service} password right? It's on the screen. Say yes, or tell me the right one."
        r is Reply.Answer && app.kataa.core.memory.Secrets.looksSecret(r.memory) ->
            "Here's your ${app.kataa.core.memory.Secrets.service(r.memory.body) ?: "password"}. It's on the screen."
        r is Reply.Replaced && app.kataa.core.memory.Secrets.looksSecret(r.memory) -> "Updated. I replaced the old one."
        r is Reply.Several -> r.replies.joinToString(" ") { spoken(it) }
        else -> of(r)
    }

    fun of(r: Reply): String = when (r) {
        is Reply.Saved -> "Got it."
        is Reply.AlreadySaved -> "Already saved: ${r.memory.body}"
        is Reply.Replaced -> "Updated. I replaced the old one."
        is Reply.ReminderSet -> "I'll remind you. ${r.description}." + (r.hint?.let { " $it" } ?: "")
        is Reply.NeedsTime -> "When should I remind you?"
        is Reply.NeedsPlace -> "Where is ${r.placeName}? Say \"save this place as ${r.placeName}\" when you're there, or tell me a time instead."
        Reply.NeedsLocation -> "I need your location for that."
        is Reply.Answer -> r.shortAnswer
        is Reply.DidIAnswer -> if (r.done) "Yes, you did." else "I have no record of that."
        is Reply.NotFound -> if (r.askFor != null) "I don't know ${r.askFor} yet. ${if (r.isWhen) "When is it?" else "What is it?"} I'll remember." else "I don't know that yet."
        is Reply.NeedsUnlock -> "That's in Private. Unlock to see it."
        is Reply.Corrected -> "Fixed."
        is Reply.Undone -> "Deleted: ${r.memory.body}"
        Reply.NothingToUndo -> "There's nothing to undo."
        is Reply.PlaceSaved ->
            if (r.errandsHere.isEmpty()) "Saved this place as ${r.place.label}."
            else "Saved ${r.place.label}. While you're here: " + r.errandsHere.size.let { if (it == 1) "1 thing to do." else "$it things to do." }
        is Reply.CategoryCreated -> "Made a new category: ${r.category.name}."
        is Reply.CategoryList -> if (r.memories.isEmpty()) "Nothing in ${r.category.name} yet." else "${r.memories.size} in ${r.category.name}."
        is Reply.NeedsClarification -> "Should I save that, or search for it?"
        is Reply.SmallTalk -> when {
            Regex("thank").containsMatchIn(r.said) -> "You're welcome."
            Regex("^(?:hi|hello|hey|good morning)").containsMatchIn(r.said) -> "Hi! Tell me something to remember, or ask me anything."
            Regex("bye|good night").containsMatchIn(r.said) -> "Bye! I'll keep everything safe."
            else -> "Okay."
        }
        is Reply.Several -> r.replies.joinToString(" ") { of(it) }
        is Reply.Agenda -> when (r.items.size) {
            0 -> if (r.label == "coming up") "You have no reminders." else "Nothing for ${r.label}."
            else -> (if (r.label == "coming up") "You have ${r.items.size}: " else "${r.label.replaceFirstChar { it.uppercase() }}: ") +
                r.items.take(5).joinToString("; ") { i -> i.memory.body.trimEnd('.') + (if (i.description.isNotEmpty()) ", ${i.description.replaceFirstChar { it.lowercase() }}" else "") } +
                (if (r.items.size > 5) "; and ${r.items.size - 5} more." else ".")
        }
        Reply.ListeningForIdea -> "Go ahead, I'm listening. Take your time."
        is Reply.ConfirmSecret -> "${r.service} password: ${r.value}. Is this right?" + if (r.replacing != null) " It will replace the old one." else ""
        Reply.NotSaved -> "Okay, I didn't save it."
        is Reply.PickTime -> r.options.joinToString(" or ") { it.label } + "?"
    }
}
