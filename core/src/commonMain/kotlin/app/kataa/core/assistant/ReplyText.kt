package app.kataa.core.assistant

/** The short sentence Kataa shows and speaks for each reply. One place, so screen and voice agree. */
object ReplyText {
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
        is Reply.NotFound -> "I don't know that yet."
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
        is Reply.Several -> r.replies.joinToString(" ") { of(it) }
    }
}
