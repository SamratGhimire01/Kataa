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
        is Reply.Agenda -> agenda(r)
        Reply.ListeningForIdea -> "Go ahead, I'm listening. Take your time."
        is Reply.ConfirmSecret -> "${r.service} password: ${r.value}. Is this right?" + if (r.replacing != null) " It will replace the old one." else ""
        Reply.NotSaved -> "Okay, I didn't save it."
        is Reply.PickTime -> r.options.joinToString(" or ") { it.label } + "?"
        is Reply.ListeningForList -> "Go ahead, tell me ${listName(r.label)}. Say \"that's it\" when you're done."
        is Reply.TaskListReply -> list(r)
        Reply.ListeningForJournal -> "I'm listening. Tell me about your day, take your time."
        is Reply.JournalShown -> journal(r)
        Reply.Timeline -> "Here's everything, newest first."
    }

    private fun agenda(r: Reply.Agenda): String {
        val lines = r.items.map { i -> i.memory.body.trimEnd('.') + (if (i.description.isNotEmpty()) ", ${i.description.replaceFirstChar { it.lowercase() }}" else "") } +
            r.tasks.map { it.body.trimEnd('.') }
        if (lines.isEmpty()) return if (r.label == "coming up") "You have no reminders." else "Nothing for ${r.label}."
        val head = if (r.label == "coming up") "You have ${lines.size}: " else "${r.label.replaceFirstChar { it.uppercase() }}: "
        return head + lines.take(5).joinToString("; ") + (if (lines.size > 5) "; and ${lines.size - 5} more." else ".")
    }

    /** "today's list", "the list for Friday 28 November", "your shopping list". */
    private fun listName(label: String): String = when {
        label == "today" || label == "tomorrow" || label == "yesterday" -> "$label's list"
        label == "this month" -> "this month's list"
        label.endsWith(" list") -> "your ${label.replaceFirstChar { it.lowercase() }}"
        label == "Inbox" -> "your inbox"
        else -> "the list for $label"
    }

    private fun money(v: Double) = "Rs " + if (v % 1.0 == 0.0) v.toLong().toString() else ((v * 100).toLong() / 100.0).toString()

    private fun duration(min: Int) = when {
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} h"
        else -> "${min / 60} h ${min % 60} min"
    }

    private fun list(r: Reply.TaskListReply): String {
        val l = r.list
        val name = listName(l.label)
        val total = l.items.size
        return when (r.action) {
            ListAction.SHOWN -> if (total == 0) "Nothing on $name yet." else
                "${name.replaceFirstChar { it.uppercase() }}: ${l.doneCount} of $total done." +
                    (l.left.takeIf { it.isNotEmpty() }?.let { left -> " Left: " + left.take(4).joinToString(", ") { it.body } + (if (left.size > 4) ", and ${left.size - 4} more." else ".") } ?: " All done!")
            ListAction.ADDED -> when (r.items.size) {
                0 -> "Those are already on $name."
                1 -> "Added ${r.items[0].body} to $name."
                else -> "Added ${r.items.size} to $name: " + r.items.joinToString(", ") { it.body } + "."
            }
            ListAction.TICKED -> "Done: ${r.items.first().body}. ${l.doneCount} of $total done" + if (l.left.isEmpty()) ", all done!" else "."
            ListAction.UNTICKED -> "Okay, ${r.items.first().body} is not done."
            ListAction.REMOVED -> "Removed ${r.items.first().body} from $name."
            ListAction.NOT_ON_LIST -> "I couldn't find that on $name."
            ListAction.COUNT -> if (total == 0) "Nothing on $name yet." else
                "${l.doneCount} of $total done, ${l.left.size} left" + (if (l.left.isEmpty()) ". All done!" else ".")
            ListAction.LEFT -> if (total == 0) "Nothing on $name yet." else if (r.items.isEmpty()) "Nothing left. All done!" else
                "${r.items.size} left: " + r.items.take(5).joinToString(", ") { it.body } + (if (r.items.size > 5) ", and ${r.items.size - 5} more." else ".")
            ListAction.CHEAPEST -> r.items.firstOrNull()?.let { "The cheapest is ${it.body}, ${money(it.cost!!)}." }
                ?: "None of the tasks left on $name has a cost yet. Say it with the task, like \"buy milk, 100 rupees\"."
            ListAction.QUICKEST -> r.items.firstOrNull()?.let { "The quickest is ${it.body}, ${duration(it.minutes!!)}." }
                ?: "None of the tasks left on $name has a time yet. Say it with the task, like \"study, 2 hours\"."
            ListAction.TOTAL_COST -> if (r.items.isEmpty()) "None of the tasks left on $name has a cost yet." else
                "About ${money(l.totalCost)} for ${r.items.size} task${if (r.items.size == 1) "" else "s"} on $name."
            ListAction.TOTAL_TIME -> if (r.items.isEmpty()) "None of the tasks left on $name has a time yet." else
                "About ${duration(l.totalMinutes)} for ${r.items.size} task${if (r.items.size == 1) "" else "s"} on $name."
        }
    }

    private fun journal(r: Reply.JournalShown): String {
        if (r.saved != null) return "Saved in today's journal."
        val d = r.day
        if (d.entries.isEmpty() && r.tasksDone.isEmpty()) return "Nothing in your journal for ${r.label}."
        val done = if (r.tasksDone.isEmpty()) "" else " You finished ${r.tasksDone.size} task${if (r.tasksDone.size == 1) "" else "s"}."
        return (d.summary?.let { "${r.label.replaceFirstChar { c -> c.uppercase() }}: $it" } ?: "${r.label.replaceFirstChar { c -> c.uppercase() }}.") + done
    }
}
