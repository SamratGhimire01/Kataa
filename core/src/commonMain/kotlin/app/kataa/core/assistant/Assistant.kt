package app.kataa.core.assistant

import app.kataa.core.KataaCore
import app.kataa.core.db.Migrations
import app.kataa.core.model.Category
import app.kataa.core.model.GeoPoint
import app.kataa.core.model.Memory
import app.kataa.core.model.MemoryEdit
import app.kataa.core.model.MemoryKind
import app.kataa.core.model.NewMemory
import app.kataa.core.model.Place
import app.kataa.core.model.PlaceTarget
import app.kataa.core.model.Reminder
import app.kataa.core.model.Source
import app.kataa.core.mind.Command
import app.kataa.core.mind.Mind
import app.kataa.core.journal.JournalDay
import app.kataa.core.lists.ListItem
import app.kataa.core.lists.ListKey
import app.kataa.core.lists.ListText
import app.kataa.core.lists.TaskList
import app.kataa.core.nlu.IntentParser
import app.kataa.core.nlu.ListAsk
import app.kataa.core.nlu.MomentMatch
import app.kataa.core.nlu.ObjectLocation
import app.kataa.core.nlu.PlaceParser
import app.kataa.core.nlu.PlaceRef
import app.kataa.core.nlu.QuestionType
import app.kataa.core.nlu.Relations
import app.kataa.core.nlu.Text
import app.kataa.core.nlu.TimeParser
import app.kataa.core.nlu.Understanding
import app.kataa.core.places.PlaceRepository
import app.kataa.core.search.SearchHit
import app.kataa.core.search.SearchOptions
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Per-request context from the app. */
data class AssistantContext(
    val nowMs: Long,
    val timeZone: TimeZone,
    /** Current device location, when the app has it (needed for "this is Big Mart" / "here"). */
    val location: GeoPoint? = null,
    /** True after biometric unlock: private memories may be shown. */
    val privateUnlocked: Boolean = false,
    val source: Source = Source.VOICE,
)

/** Everything the UI needs to show the result of one utterance. */
sealed interface Reply {
    data class Saved(val memory: Memory) : Reply
    /** The same thing was already saved: nothing new was stored. */
    data class AlreadySaved(val memory: Memory) : Reply
    /** A fact with one current value changed ("my wifi password is ..."): [old] went to the trash. */
    data class Replaced(val old: Memory, val memory: Memory) : Reply
    data class ReminderSet(
        val memory: Memory,
        val reminders: List<Reminder>,
        val description: String,
        /** Something the user should do for it to work ("say 'this is Big Mart' when you're there"). */
        val hint: String? = null,
    ) : Reply
    /** Reminder text was saved, but Kataa does not know where [placeName] is yet ("home" before it was set). */
    data class NeedsPlace(val memory: Memory, val placeName: String) : Reply
    /** Reminder text was saved, but no time or moment was said: ask "When should I remind you?". */
    data class NeedsTime(val memory: Memory) : Reply
    data class Answer(val shortAnswer: String, val memory: Memory, val previous: List<Memory>, val hits: List<SearchHit>) : Reply
    data class DidIAnswer(val done: Boolean, val memory: Memory?) : Reply
    /**
     * Nothing saved answers [query]. [askFor] ("your mom's birthday") is set when Kataa can ask for the answer
     * and save it: the next thing the user says goes to [Assistant.followUp].
     */
    data class NotFound(val query: String, val askFor: String? = null, val isWhen: Boolean = false) : Reply
    /** The answer exists but is in Private: ask for fingerprint, then call [Assistant.handle] again with privateUnlocked. */
    data class NeedsUnlock(val query: String) : Reply
    data class Corrected(val before: Memory, val after: Memory) : Reply
    data class Undone(val memory: Memory) : Reply
    data object NothingToUndo : Reply
    /** Saved a place. [errandsHere] are place reminders that match it, to show right away. */
    data class PlaceSaved(val place: Place, val errandsHere: List<Reminder>) : Reply
    data object NeedsLocation : Reply
    data class CategoryCreated(val category: Category) : Reply
    data class CategoryList(val category: Category, val memories: List<Memory>) : Reply
    data class NeedsClarification(val text: String) : Reply
    /** One sentence held several requests ("remind me to X and my Y is Z"); each got its own reply. */
    data class Several(val replies: List<Reply>) : Reply
    /**
     * A password was heard but not saved yet: show "[service] password: [value]. Is this right?". Yes ->
     * [Assistant.confirmSecret]; a new value ("no, it's sunflower124") asks again; "no" -> [Reply.NotSaved].
     * [replacing] is the current one for that service, when this would replace it.
     */
    data class ConfirmSecret(val service: String, val value: String, val replacing: Memory?, internal val pending: Understanding.Save, internal val raw: String) : Reply
    /**
     * "Call Didi at 7:13": the reminder is saved but not scheduled until the user picks one of [options]
     * (the best guess first), by tapping or saying "PM", "morning", "tomorrow"... -> [Assistant.followUp].
     */
    data class PickTime(val memory: Memory, val options: List<TimeOption>, internal val persistent: Boolean, internal val rrule: String? = null) : Reply
    /** The user said no to saving: nothing was stored. */
    data object NotSaved : Reply
    /** "What do I have today?": the reminders for [label] ("today", "tomorrow", "coming up"), soonest first. */
    data class Agenda(val label: String, val items: List<AgendaItem>, val tasks: List<ListItem> = emptyList()) : Reply
    /** "I've got an idea": the app listens longer (pauses don't end it), then calls [Assistant.saveIdea]. */
    data object ListeningForIdea : Reply
    /** "Write my to-do list": the app listens longer, then the dictation goes to [Assistant.followUp] -> [Assistant.saveList]. */
    data class ListeningForList(val key: String, val label: String) : Reply
    /**
     * A to-do list after something was done to it or asked about it. [items] are the ones affected or the answer
     * (the ticked item, the added ones, the cheapest task). The screen shows [list] with tick boxes.
     */
    data class TaskListReply(val list: TaskList, val action: ListAction, val items: List<ListItem> = emptyList()) : Reply
    /** "Today's journal": the app listens longer (pauses don't end it), then [Assistant.saveJournal]. */
    data object ListeningForJournal : Reply
    /** One day of the journal: summary, entries, and the tasks finished that day. [saved] is the entry just added. */
    data class JournalShown(val day: JournalDay, val label: String, val tasksDone: List<ListItem>, val saved: Memory? = null) : Reply
    /** "Show me everything": the app opens the timeline (every memory, list and journal day, by date). */
    data object Timeline : Reply
    /** "ok thanks", "hello": nothing to save or search. [said] is the normalized text. */
    data class SmallTalk(val said: String) : Reply
}

enum class ListAction { SHOWN, ADDED, TICKED, UNTICKED, REMOVED, COUNT, LEFT, CHEAPEST, QUICKEST, TOTAL_COST, TOTAL_TIME, NOT_ON_LIST }

/** One choice of a [Reply.PickTime]: "7:13 PM today". */
data class TimeOption(val atMs: Long, val label: String)

/** One line of an [Reply.Agenda]: the reminder, its note, and when or where it fires ("6:30 PM", "at Big Mart"). */
data class AgendaItem(val memory: Memory, val reminder: Reminder, val description: String)

/**
 * One entry point for everything the user says or types: `handle("where are my keys?")`.
 * Pure rules + local database, so it answers in milliseconds and works offline.
 */
class Assistant(private val core: KataaCore) {

    /**
     * The on-device language model, once the app has loaded it (null on phones that can't run it, or while
     * it loads). Rules always go first; the mind is asked only when they are unsure.
     */
    @kotlin.concurrent.Volatile var mind: Mind? = null

    fun handle(raw: String, ctx: AssistantContext): Reply {
        val said = Text.normalize(raw)
        if (SMALL_TALK.matches(said)) return Reply.SmallTalk(said)
        val known = core.places.userPlaces().map { it.label.lowercase() }.toSet()
        val u = IntentParser.parse(raw, ctx.nowMs, ctx.timeZone, known)
        // Two requests in one sentence that the rules can each read on their own: no need for the AI.
        splitByRules(raw, ctx)?.let { return it }
        val m = mind
        if (m != null && needsMind(u, raw)) {
            val commands = runCatching { m.understand(raw, today(ctx)) }.getOrNull()
            if (commands != null && commands != listOf(Command.Unclear)) return run(commands, raw, ctx)
        }
        val reply = byRules(u, raw, ctx)
        // Nothing found: maybe it was misheard ("facebook passport"). Let the mind restate the question once.
        if (m != null && reply is Reply.NotFound && (u as? Understanding.Ask)?.type == QuestionType.GENERAL) {
            val again = runCatching { m.understand(raw, today(ctx)) }.getOrNull()?.singleOrNull() as? Command.Ask
            if (again != null && Text.normalize(again.text) != Text.normalize(raw)) {
                val second = askText(again.text, ctx)
                if (second !is Reply.NotFound) return second
            }
        }
        return reply
    }

    /**
     * "I have to submit the assignment on Friday and my bike is parked near the gate": split where a new clause
     * starts ("and my", "and also", "and the"), and keep the split only if every part means something by itself.
     */
    private fun splitByRules(raw: String, ctx: AssistantContext): Reply? {
        val text = Text.normalize(raw)
        if (!SPLIT.containsMatchIn(text)) return null
        val parts = text.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size !in 2..3 || parts.any { it.split(' ').size < 3 }) return null
        val known = core.places.userPlaces().map { it.label.lowercase() }.toSet()
        val meant = parts.map { IntentParser.parse(it, ctx.nowMs, ctx.timeZone, known) }
        if (meant.any { it is Understanding.Unclear || it is Understanding.Correct || it == Understanding.Undo }) return null
        if (meant.all { it is Understanding.Ask }) return null
        // A save that is only a plain note may really be the tail of the first request ("... and the bread"): keep it whole.
        if (meant.withIndex().any { (i, it) -> it is Understanding.Save && it.kind == MemoryKind.NOTE && parts[i].split(' ').size < 4 }) return null
        return Reply.Several(parts.zip(meant).map { (p, u) -> byRules(u, p, ctx) })
    }

    /**
     * "7:13" with no am/pm, morning/evening or a hint ("wake up" is morning, "dinner" is evening): both halves of
     * the clock are possible, so ask instead of guessing. Null when the time is clear.
     */
    private fun pickTime(memory: Memory, u: Understanding.Remind, raw: String, ctx: AssistantContext): Reply? {
        val t = u.time ?: return null
        val other = t.alternative ?: return null
        if (u.moment != null) return null
        val said = Text.normalize(raw)
        val hour = t.fireAt.toLocalDateTime(ctx.timeZone).hour
        val morning = MORNING_HINT.containsMatchIn(said)
        val evening = EVENING_HINT.containsMatchIn(said)
        if (morning != evening) {
            // "wake me up at 5", "dinner at 8": the words say which half; use it without asking.
            val wantAm = morning
            if ((hour < 12) == wantAm) return null
            return schedule(memory, t.copy(fireAt = other, alternative = null), null, u.persistent, ctx)
        }
        val options = listOf(t.fireAt, other).map { TimeOption(it.toEpochMilliseconds(), Describe.time(it, null, ctx)) }
        return Reply.PickTime(memory, options, u.persistent, t.rrule)
    }

    /** "What do I have today?": active reminders, for one day or all, soonest first. */
    private fun agenda(dayOffset: Int?, ctx: AssistantContext): Reply {
        val tz = ctx.timeZone
        val today = Instant.fromEpochMilliseconds(ctx.nowMs).toLocalDateTime(tz).date
        val range = dayOffset?.let { d ->
            val day = today.plus(d, DateTimeUnit.DAY)
            day.atStartOfDayIn(tz).toEpochMilliseconds() until day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz).toEpochMilliseconds()
        }
        val items = core.reminders.active().mapNotNull { r ->
            val at = r.snoozeUntil ?: r.fireAt
            if (range != null && (at == null || at !in range)) return@mapNotNull null
            val memory = core.memories.get(r.memoryId) ?: return@mapNotNull null
            val description = when {
                at != null -> Describe.time(Instant.fromEpochMilliseconds(at), r.rrule, ctx)
                r.placeTarget != null -> "at a place"
                r.btDevice != null -> "in the car"
                else -> ""
            }
            AgendaItem(memory, r, description)
        }
        // The day's to-do list belongs to "what do I have today?" too (unfinished ones only).
        val tasks = core.lists.view(ListKey.day(today.plus(dayOffset ?: 0, DateTimeUnit.DAY)), today).left
        return Reply.Agenda(when (dayOffset) { 0 -> "today"; 1 -> "tomorrow"; else -> "coming up" }, items, tasks)
    }

    /**
     * Saves what the user said after "I've got an idea" (it can be long). Stored as a note in the Ideas category,
     * so it is found like any memory ("what was my idea about the shop?") and listed under Ideas.
     */
    fun saveIdea(text: String, ctx: AssistantContext): Reply {
        val body = text.trim().ifEmpty { return Reply.NeedsClarification(text) }
        val shown = body.replaceFirstChar { it.uppercase() }.let { if (Text.normalize(it).startsWith("idea")) it else "Idea: $it" }
        val m = core.memories.save(NewMemory(body = shown, kind = MemoryKind.NOTE, source = ctx.source, rawTranscript = body.takeIf { ctx.source == Source.VOICE }))
        val ideas = core.categories.bySlug("ideas") ?: core.categories.create("Ideas", "Ideas, plans and things to try")
        core.categories.assign(m.id, ideas.id)
        setLast(m.id)
        return Reply.Saved(m)
    }

    /** Rules only, never the mind: used for the mind's own rewritten commands, so they can't loop. */
    private fun handleByRules(raw: String, ctx: AssistantContext): Reply {
        val known = core.places.userPlaces().map { it.label.lowercase() }.toSet()
        return byRules(IntentParser.parse(raw, ctx.nowMs, ctx.timeZone, known), raw, ctx)
    }

    private fun run(commands: List<Command>, raw: String, ctx: AssistantContext): Reply {
        val replies = commands.filter { it != Command.Unclear }.map { c ->
            when (c) {
                is Command.Save -> {
                    val u = IntentParser.parse(c.text, ctx.nowMs, ctx.timeZone)
                    if (u is Understanding.Save) save(c.text, u, ctx) else saveAsNote(c.text, ctx)
                }
                is Command.Remind -> handleByRules(keepUsersTime(c.text, raw, commands.count { it is Command.Remind } == 1, ctx), ctx)
                is Command.Ask -> askText(c.text, ctx)
                is Command.Place -> savePlace(raw, Understanding.SavePlace(c.name, explicit = true), ctx)
                Command.Undo -> undo()
                Command.Unclear -> Reply.NeedsClarification(raw)
            }
        }
        return when (replies.size) {
            0 -> Reply.NeedsClarification(raw)
            1 -> replies.single()
            else -> Reply.Several(replies)
        }
    }

    /**
     * The small model is good at splitting and cleaning sentences but sloppy with dates ("day after tomorrow"
     * comes back as "tomorrow"). When the user's own words had a time the rules can read, that time wins.
     */
    private fun keepUsersTime(command: String, raw: String, onlyReminder: Boolean, ctx: AssistantContext): String {
        if (!onlyReminder) return command
        val said = Text.normalize(raw)
        val users = TimeParser.parse(said, ctx.nowMs, ctx.timeZone) ?: return command
        if (users.timeWasDefaulted) return command
        val cmd = Text.normalize(command)
        val mine = TimeParser.parse(cmd, ctx.nowMs, ctx.timeZone)
        val stripped = if (mine != null && mine.range.last < cmd.length) cmd.removeRange(mine.range).replace(Regex("\\s+"), " ").trim() else cmd
        return "$stripped ${said.substring(users.range.first, users.range.last + 1)}"
    }

    /** The day from [rules] (it read the user's own words) with the time of day from [mind], when rules had none. */
    private fun mergeTimes(rules: app.kataa.core.nlu.TimeMatch?, mind: app.kataa.core.nlu.TimeMatch?, ctx: AssistantContext): app.kataa.core.nlu.TimeMatch? {
        if (rules == null) return mind
        if (mind == null || !rules.timeWasDefaulted) return rules
        val day = rules.fireAt.toLocalDateTime(ctx.timeZone).date
        val clock = mind.fireAt.toLocalDateTime(ctx.timeZone).time
        return rules.copy(fireAt = kotlinx.datetime.LocalDateTime(day, clock).toInstant(ctx.timeZone), timeWasDefaulted = false)
    }

    private fun askText(text: String, ctx: AssistantContext): Reply {
        val u = IntentParser.parse(text, ctx.nowMs, ctx.timeZone)
        return ask(u as? Understanding.Ask ?: Understanding.Ask(Text.normalize(text), QuestionType.GENERAL, null), ctx)
    }

    /** When the fast rules are probably not enough: long, multi-part, vague or unrecognized sentences. */
    private fun needsMind(u: Understanding, raw: String): Boolean {
        val text = Text.normalize(raw)
        val words = text.split(' ').count { it.isNotBlank() }
        // Small talk ("ok thanks", "hello") is never a memory: don't let the model turn it into one.
        if (SMALL_TALK.matches(text)) return false
        return when (u) {
            is Understanding.Unclear -> words >= 1
            is Understanding.Save -> u.kind == MemoryKind.NOTE && (words >= 8 || MULTI.containsMatchIn(text) || HINTS.containsMatchIn(text))
            is Understanding.Remind -> (u.time == null && u.moment == null && words >= 6) ||
                (u.time?.timeWasDefaulted == true && TIMEISH.containsMatchIn(text)) || MULTI.containsMatchIn(text)
            else -> false
        }
    }

    private fun today(ctx: AssistantContext): String {
        val d = Instant.fromEpochMilliseconds(ctx.nowMs).toLocalDateTime(ctx.timeZone)
        val day = d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val month = d.month.name.lowercase().replaceFirstChar { it.uppercase() }
        return "$day, ${d.day} $month ${d.year}, ${Describe.clock(d.hour, d.minute)}"
    }

    private fun byRules(u: Understanding, raw: String, ctx: AssistantContext): Reply {
        return when (u) {
            is Understanding.Save -> save(raw, u, ctx)
            is Understanding.Remind -> remind(raw, u, ctx)
            is Understanding.Ask -> ask(u, ctx)
            is Understanding.Correct -> correct(u.newText, ctx)
            Understanding.Undo -> undo()
            is Understanding.SavePlace -> savePlace(raw, u, ctx)
            is Understanding.CreateCategory -> Reply.CategoryCreated(core.categories.create(u.name, u.description))
            is Understanding.BrowseCategory -> browse(u, ctx)
            is Understanding.Agenda -> agenda(u.dayOffset, ctx)
            Understanding.StartIdea -> Reply.ListeningForIdea
            is Understanding.OpenList -> openList(u.key, u.listen, ctx)
            is Understanding.AddToList -> addToList(u.key, u.items, ctx)
            is Understanding.ListQuestion -> listQuestion(u.key, u.ask, ctx)
            is Understanding.Tick -> tick(u, ctx) ?: byRules(reparseWithoutLists(raw, ctx), raw, ctx)
            is Understanding.RemoveFromList -> removeFromList(u, ctx) ?: byRules(reparseWithoutLists(raw, ctx), raw, ctx)
            is Understanding.Journal -> if (u.text != null) saveJournal(u.text, ctx) else Reply.ListeningForJournal
            is Understanding.ShowJournal -> showJournal(u.day, ctx)
            Understanding.Timeline -> Reply.Timeline
            is Understanding.Unclear -> Reply.NeedsClarification(raw)
        }
    }

    private fun reparseWithoutLists(raw: String, ctx: AssistantContext): Understanding {
        val known = core.places.userPlaces().map { it.label.lowercase() }.toSet()
        return IntentParser.parse(raw, ctx.nowMs, ctx.timeZone, known, lists = false)
    }

    private fun todayOf(ctx: AssistantContext) = Instant.fromEpochMilliseconds(ctx.nowMs).toLocalDateTime(ctx.timeZone).date

    private fun view(key: String, ctx: AssistantContext) = core.lists.view(key, todayOf(ctx))

    /** "Today's to-do list": shown when it has items, otherwise Kataa listens for them. "Write my list" always listens. */
    private fun openList(key: String, listen: Boolean, ctx: AssistantContext): Reply {
        val list = view(key, ctx)
        if (listen) return Reply.ListeningForList(key, list.label)
        if (list.items.isNotEmpty()) return Reply.TaskListReply(list, ListAction.SHOWN)
        val today = todayOf(ctx)
        // "What are my tasks today?" with no list but reminders: those are the tasks.
        ListKey.dayOf(key)?.let { d ->
            val offset = (d.toEpochDays() - today.toEpochDays()).toInt()
            if (offset in 0..1) (agenda(offset, ctx) as Reply.Agenda).let { if (it.items.isNotEmpty()) return it }
        }
        // "Show my shopping list" with no list of that name: the Shopping category, if it has anything.
        if (key.startsWith("named:")) {
            val name = key.removePrefix("named:")
            core.categories.list().firstOrNull { it.name.equals(name, true) || it.slug.equals(name, true) }?.let { c ->
                if (c.memoryCount > 0) return browse(Understanding.BrowseCategory(c.name, name), ctx)
            }
        }
        return Reply.ListeningForList(key, list.label)
    }

    /** What the user dictated after [Reply.ListeningForList]: split into items and added to the list. */
    fun saveList(key: String, dictation: String, ctx: AssistantContext): Reply {
        val items = ListText.split(stripStopWords(dictation), nouns = key.startsWith("named:"))
        if (items.isEmpty()) return Reply.NotSaved
        return addToList(key, items, ctx)
    }

    private fun addToList(key: String, items: List<String>, ctx: AssistantContext): Reply {
        val existing = core.lists.items(key).map { ListText.words(it.body) }.toSet()
        // Saying the same task twice adds it once.
        val fresh = items.filter { ListText.words(it) !in existing }.distinctBy { ListText.words(it) }
        val added = core.lists.add(key, fresh)
        return Reply.TaskListReply(view(key, ctx), ListAction.ADDED, added)
    }

    /** "How many tasks are done?", "what's the cheapest task?", "how much will today's list cost?". */
    private fun listQuestion(key: String, ask: ListAsk, ctx: AssistantContext): Reply {
        val list = view(key, ctx)
        val left = list.left
        return when (ask) {
            ListAsk.COUNT -> Reply.TaskListReply(list, ListAction.COUNT)
            ListAsk.LEFT -> Reply.TaskListReply(list, ListAction.LEFT, left)
            ListAsk.CHEAPEST -> Reply.TaskListReply(list, ListAction.CHEAPEST, listOfNotNull(left.filter { it.cost != null }.minByOrNull { it.cost!! }))
            ListAsk.QUICKEST -> Reply.TaskListReply(list, ListAction.QUICKEST, listOfNotNull(left.filter { it.minutes != null }.minByOrNull { it.minutes!! }))
            ListAsk.TOTAL_COST -> Reply.TaskListReply(list, ListAction.TOTAL_COST, left.filter { it.cost != null })
            ListAsk.TOTAL_TIME -> Reply.TaskListReply(list, ListAction.TOTAL_TIME, left.filter { it.minutes != null })
        }
    }

    /** Lists to look in when the user didn't say which: today's (with carried items), this month's, then named ones. */
    private fun searchOrder(key: String?, ctx: AssistantContext): List<TaskList> {
        if (key != null) return listOf(view(key, ctx))
        val today = todayOf(ctx)
        val keys = listOf(ListKey.day(today), ListKey.month(today.year, today.month.ordinal + 1), ListKey.INBOX) +
            core.lists.keys().filter { it !in setOf(ListKey.day(today)) }
        return keys.distinct().map { core.lists.view(it, today) }.filter { it.items.isNotEmpty() }
    }

    /** "Mark milk done" / "untick number 2". Null when no list item matches (then it's an ordinary sentence). */
    private fun tick(u: Understanding.Tick, ctx: AssistantContext): Reply? {
        for (list in searchOrder(u.key, ctx)) {
            val item = core.lists.find(list, u.query, wantDone = !u.done, strict = u.strict) ?: core.lists.find(list, u.query, strict = u.strict) ?: continue
            val after = core.lists.setDone(item.id, u.done) ?: continue
            return Reply.TaskListReply(view(list.key, ctx), if (u.done) ListAction.TICKED else ListAction.UNTICKED, listOf(after))
        }
        if (u.key != null) return Reply.TaskListReply(view(u.key, ctx), ListAction.NOT_ON_LIST)
        return null
    }

    /** The tick box on the screen. */
    fun setTaskDone(item: ListItem, done: Boolean, shownKey: String, ctx: AssistantContext): Reply {
        val after = core.lists.setDone(item.id, done) ?: item
        return Reply.TaskListReply(view(shownKey, ctx), if (done) ListAction.TICKED else ListAction.UNTICKED, listOf(after))
    }

    private fun removeFromList(u: Understanding.RemoveFromList, ctx: AssistantContext): Reply? {
        for (list in searchOrder(u.key, ctx)) {
            val item = core.lists.find(list, u.query) ?: continue
            core.lists.remove(item.id)
            return Reply.TaskListReply(view(list.key, ctx), ListAction.REMOVED, listOf(item))
        }
        if (u.key != null) return Reply.TaskListReply(view(u.key, ctx), ListAction.NOT_ON_LIST)
        return null
    }

    /**
     * A journal entry (what the user said after "today's journal"). Saved as a memory (searchable) on today's page;
     * the day's quick summary updates at once, the AI one later while charging ([summarizeJournals]).
     */
    fun saveJournal(text: String, ctx: AssistantContext): Reply {
        val body = stripStopWords(text).trim().ifEmpty { return Reply.NotSaved }
        val m = core.memories.save(NewMemory(body = Text.sentence(body), kind = MemoryKind.NOTE, source = ctx.source,
            rawTranscript = body.takeIf { ctx.source == Source.VOICE }))
        val cat = core.categories.bySlug("journal") ?: core.categories.create("Journal", "My journal: how my days went, thoughts and feelings")
        core.categories.assign(m.id, cat.id)
        val day = todayOf(ctx)
        core.journal.addEntry(m.id, day)
        setLast(m.id)
        return (showJournal(day, ctx) as Reply.JournalShown).copy(saved = m)
    }

    private fun showJournal(day: kotlinx.datetime.LocalDate, ctx: AssistantContext): Reply {
        val from = day.atStartOfDayIn(ctx.timeZone).toEpochMilliseconds()
        val to = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(ctx.timeZone).toEpochMilliseconds()
        return Reply.JournalShown(core.journal.day(day), ListKey.label(ListKey.day(day), todayOf(ctx)), core.lists.doneBetween(from, to))
    }

    /**
     * Background job (the app runs it while charging): writes the AI summary for days whose journal changed.
     * Without the mind the quick summary stays. Returns how many days were summarized.
     */
    fun summarizeJournals(maxDays: Int = 7): Int {
        val m = mind ?: return 0
        var n = 0
        for (day in core.journal.needingSummary(maxDays)) {
            val entries = core.journal.entries(day)
            if (entries.isEmpty()) continue
            val summary = runCatching { m.summarizeDay(entries.map { it.body }) }.getOrNull() ?: continue
            core.journal.setSummary(day, summary, byAi = true)
            n++
        }
        if (n > 0) runCatching { m.warmUp() }
        return n
    }

    /** "...and that's it", "okay stop", "done": the words that end a long dictation are not part of it. */
    private fun stripStopWords(s: String): String =
        s.trim().replace(Regex("(?i)(?:^|[,.!\\s]+)(?:(?:okay|ok|and|so)\\s+)?(?:that's it|that is it|that's all|that is all|stop listening|i'm done|i am done|okay done|ok done)[.!]*$"), "").trim()

    /**
     * The user said yes to a [Reply.ConfirmSecret], maybe after fixing the [value] or the [service] on the card.
     * Saves it (replacing the old one for that service) and returns [Reply.Saved] or [Reply.Replaced].
     */
    fun confirmSecret(c: Reply.ConfirmSecret, ctx: AssistantContext, value: String = c.value, service: String = c.service): Reply {
        val v = value.trim().ifEmpty { return Reply.NotSaved }
        val svc = service.trim().ifEmpty { c.service }
        val same = v == c.pending.secret?.value && svc == c.pending.secret.service
        val u = if (same) c.pending else c.pending.copy(
            body = "${svc.replaceFirstChar { it.uppercase() }} password is $v",
            subject = Text.subjectKey("${svc.lowercase()} password"),
            secret = app.kataa.core.nlu.Secret(svc, v),
        )
        return save(c.raw, u, ctx, confirmed = true)
    }

    /** "What is my mom's birthday?" with nothing saved: say so, and offer to remember it ("When is it?"). */
    private fun notFound(u: Understanding.Ask): Reply {
        val m = ASKABLE.find(u.query) ?: return Reply.NotFound(u.query)
        val thing = m.groupValues[2].trim()
        if (thing.isEmpty() || thing.split(' ').size > 5 || ASKABLE_SKIP.containsMatchIn(thing)) return Reply.NotFound(u.query)
        // Only something of yours ("my mom's birthday", "dad's phone number"), not "what happened at the meeting".
        // Recognizers often drop the "'s" ("when is didi birthday"), so a family word counts as yours too.
        val ownMarker = (Regex("(?:^| )(?:my|our) |'s ").containsMatchIn(u.query + " ") || Relations.PERSONAL_FACT.matches(thing)) &&
            // "mother's day", "valentine's day", "new year's eve": days for everyone.
            !(Regex("'s (?:day|eve|night|week)$").containsMatchIn(thing) && !Regex("^(?:my|our) ").containsMatchIn(thing))
        if (!ownMarker) return Reply.NotFound(u.query)
        val yours = thing.replace(Regex("^(?:my|our)\\s+"), "your ").let { if (it.startsWith("your ")) it else "your $it" }
        val isWhen = m.groupValues[1] == "when" || Regex("\\b(?:birthday|bday|anniversary|date|day)$").containsMatchIn(thing)
        return Reply.NotFound(u.query, yours, isWhen = isWhen)
    }

    /**
     * The answer to "When is it?" / "What is it?" after [Reply.NotFound]: saved as "Mom's birthday is 12 March".
     * Birthdays and anniversaries with a date also get a yearly reminder on the day.
     */
    private fun rememberAnswer(nf: Reply.NotFound, raw: String, ctx: AssistantContext): Reply {
        val thing = nf.askFor!!.removePrefix("your ")
        val answer = raw.trim().trimEnd('.', '!').replace(Regex("(?i)^(?:it is|it's|its|on|that is|they are)\\s+"), "")
        val body = "My ${thing} is $answer"
        val subject = Text.subjectKey(thing)
        val saved = save(raw, Understanding.Save(Text.sentence(body), MemoryKind.FACT, subject, null, replacesOld = true), ctx)
        val memory = (saved as? Reply.Saved)?.memory ?: (saved as? Reply.Replaced)?.memory ?: return saved
        if (YEARLY_THING.containsMatchIn(thing)) {
            TimeParser.parse(Text.normalize(answer), ctx.nowMs, ctx.timeZone)?.let { t ->
                val day = t.fireAt.toLocalDateTime(ctx.timeZone).date
                val at = kotlinx.datetime.LocalDateTime(day, kotlinx.datetime.LocalTime(9, 0)).toInstant(ctx.timeZone)
                val r = core.reminders.addTime(memory.id, at.toEpochMilliseconds(), ctx.timeZone.id, "FREQ=YEARLY")
                return Reply.ReminderSet(memory, listOf(r), Describe.time(at, "FREQ=YEARLY", ctx), hint = "Saved. I'll remind you every year.")
            }
        }
        return saved
    }

    /** The user tapped or said one of the times of a [Reply.PickTime]. */
    fun pickTimeOption(p: Reply.PickTime, option: TimeOption, ctx: AssistantContext): Reply {
        val at = Instant.fromEpochMilliseconds(option.atMs)
        val t = app.kataa.core.nlu.TimeMatch(at, p.rrule, 0..0, timeWasDefaulted = false)
        return schedule(p.memory, t, null, p.persistent, ctx)
    }

    /**
     * An answer to "AM or PM?" that names a clock ("7 am", "7:13 pm", "at 8 pm", "tomorrow at 8 pm"). The same hour
     * as an option picks it (keeping its day); a new hour goes on the asked day unless the answer names a day; the
     * repeat is kept. Null when the answer has no clock (then "am"/"pm"/"the first one" decide).
     */
    private fun pickAnswer(p: Reply.PickTime, text: String, ctx: AssistantContext): Reply? {
        val tz = ctx.timeZone
        val hours = Regex("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\b").findAll(text).toList().ifEmpty { return null }
        val options = p.options.map { it to Instant.fromEpochMilliseconds(it.atMs).toLocalDateTime(tz) }
        val am = AM_WORD.containsMatchIn(text); val pm = PM_WORD.containsMatchIn(text)
        val h = hours.first().groupValues[1].toInt(); val min = hours.first().groupValues[2].toIntOrNull() ?: 0
        // The asked hour with a half: "7 am", "7 in the evening", "19:00".
        val twentyFour = h == 0 || h > 12
        if (twentyFour || am || pm) options.firstOrNull { (_, t) ->
            t.minute == min && (if (twentyFour) t.hour == h else t.hour % 12 == h % 12 && (if (am) t.hour < 12 else t.hour >= 12))
        }?.let { return pickTimeOption(p, it.first, ctx) }
        if (options.any { (_, t) -> t.hour % 12 == h % 12 && t.minute == min } && !am && !pm && !twentyFour) return null
        val parsed = TimeParser.parse(text, ctx.nowMs, tz)?.takeIf { it.alternative == null } ?: return null
        val said = parsed.fireAt.toLocalDateTime(tz)
        val namesDay = Regex("\\b(?:today|tomorrow|tonight|day after|monday|tuesday|wednesday|thursday|friday|saturday|sunday|next|on the|after \\d+|in \\d+ (?:days?|weeks?)|january|february|march|april|may|june|july|august|september|october|november|december)\\b").containsMatchIn(text)
        // The asked day: the option in the same half of the day as the new hour ("at 8 pm" goes with the 7 pm option).
        val sameHalf = options.firstOrNull { (_, t) -> (t.hour >= 12) == (said.hour >= 12) } ?: options.first()
        var at = if (namesDay) said else kotlinx.datetime.LocalDateTime(sameHalf.second.date, said.time)
        val nowLocal = Instant.fromEpochMilliseconds(ctx.nowMs).toLocalDateTime(tz)
        if (at <= nowLocal) {
            at = p.rrule?.let { r -> app.kataa.core.triggers.Recurrence.next(at.toInstant(tz).toEpochMilliseconds(), r, tz, afterMs = ctx.nowMs) }
                ?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz) } ?: kotlinx.datetime.LocalDateTime(at.date.plus(1, DateTimeUnit.DAY), at.time)
        }
        return schedule(p.memory, app.kataa.core.nlu.TimeMatch(at.toInstant(tz), p.rrule ?: parsed.rrule, 0..0, timeWasDefaulted = false), null, p.persistent, ctx)
    }

    /** "Save it" after a [Reply.NeedsClarification] or [Reply.NotFound]. */
    fun saveAsNote(raw: String, ctx: AssistantContext): Reply =
        save(raw, Understanding.Save(IntentParser.saveBody(raw), MemoryKind.NOTE, null, null), ctx)

    /** A live memory that says the same thing, if there is one. */
    private fun duplicateOf(body: String, kind: MemoryKind? = null): Memory? {
        val key = IntentParser.sameKey(body)
        if (key.isEmpty()) return null
        return core.memories.recent(limit = 400).firstOrNull { (kind == null || it.kind == kind) && IntentParser.sameKey(it.body) == key }
    }

    private fun save(raw: String, u: Understanding.Save, ctx: AssistantContext, confirmed: Boolean = false): Reply {
        // Spoken passwords are shown back for a yes (voice mishears letters and digits); typed ones are exact.
        if (u.secret != null && !confirmed && ctx.source == Source.VOICE) {
            val old = u.subject?.let { core.memories.historyFor(it)?.latest }
            if (old != null && IntentParser.sameKey(old.body) == IntentParser.sameKey(u.body)) return Reply.AlreadySaved(old)
            return Reply.ConfirmSecret(u.secret.service, u.secret.value, old, u, raw)
        }
        if (u.replacesOld && u.subject != null) {
            val old = core.memories.historyFor(u.subject)?.latest
            if (old != null && IntentParser.sameKey(old.body) == IntentParser.sameKey(u.body)) return Reply.AlreadySaved(old)
            if (old != null) {
                val m = core.memories.save(NewMemory(body = u.body, kind = u.kind, source = ctx.source,
                    rawTranscript = raw.takeIf { ctx.source == Source.VOICE }, subject = u.subject))
                core.memories.trash(old.id)
                setLast(m.id)
                return Reply.Replaced(old, m)
            }
        }
        // Logs ("I took my meds") repeat on purpose; everything else is stored once.
        val isLog = u.kind == MemoryKind.FACT && !u.replacesOld
        if (!isLog) duplicateOf(u.body)?.let { return Reply.AlreadySaved(it) }
        val m = core.memories.save(
            NewMemory(
                body = u.body, kind = u.kind, source = ctx.source,
                rawTranscript = raw.takeIf { ctx.source == Source.VOICE }, subject = u.subject,
                capturedAt = ctx.location.takeIf { u.kind == MemoryKind.OBJECT_LOCATION && u.subject == "car" },
            ),
        )
        setLast(m.id)
        return Reply.Saved(m)
    }

    private fun remind(raw: String, u: Understanding.Remind, ctx: AssistantContext): Reply {
        // "Call mom" said twice: keep one. A time or place said the second time is added to the first one.
        // Alarms are all called "Alarm" / "Wake up": each one is its own.
        val isAlarm = u.persistent && (u.body == "Alarm" || u.body == "Wake up")
        (if (isAlarm) null else duplicateOf(u.body, MemoryKind.REMINDER))?.let { existing ->
            val active = core.reminders.forMemory(existing.id).any { it.status.name in setOf("SCHEDULED", "SNOOZED") }
            if (u.time == null && u.moment == null) return if (active) Reply.AlreadySaved(existing) else Reply.NeedsTime(existing)
            // The same time said again: nothing new, so no second alarm.
            val t = u.time
            if (t != null && u.moment == null && core.reminders.forMemory(existing.id).any {
                    it.status.name in setOf("SCHEDULED", "SNOOZED") && it.fireAt == t.fireAt.toEpochMilliseconds() && it.rrule == t.rrule
                }) return Reply.AlreadySaved(existing)
            setLast(existing.id)
            pickTime(existing, u, raw, ctx)?.let { return it }
            return schedule(existing, u.time, u.moment, u.persistent, ctx)
        }
        val memory = core.memories.save(
            NewMemory(u.body, MemoryKind.REMINDER, ctx.source, rawTranscript = raw.takeIf { ctx.source == Source.VOICE }),
        )
        setLast(memory.id)
        pickTime(memory, u, raw, ctx)?.let { return it }
        return schedule(memory, u.time, u.moment, u.persistent, ctx)
    }

    /**
     * Answers Kataa's own follow-up question. After [Reply.NeedsTime] ("When should I remind you?") the user
     * says "tomorrow at 5" or "when I get home"; after [Reply.NeedsPlace] they can name a time instead.
     * Anything that is not an answer is handled as a new request.
     */
    fun followUp(previous: Reply, raw: String, ctx: AssistantContext): Reply {
        // Long dictation after "I've got an idea" / "write my to-do list" / "today's journal".
        if (previous is Reply.ListeningForIdea || previous is Reply.ListeningForList || previous is Reply.ListeningForJournal) {
            val text = Text.normalize(raw)
            if (NO.matches(text) || CANCEL.matches(text)) return Reply.NotSaved
            return when (previous) {
                is Reply.ListeningForList -> saveList(previous.key, raw, ctx)
                Reply.ListeningForJournal -> saveJournal(raw, ctx)
                else -> saveIdea(stripStopWords(raw), ctx)
            }
        }
        if (previous is Reply.NotFound && previous.askFor != null) {
            val text = Text.normalize(raw)
            if (NO.matches(text) || NO_TIME.matches(text)) return Reply.NotSaved
            val u = IntentParser.parse(raw, ctx.nowMs, ctx.timeZone)
            // A new question or request instead of an answer: handle it normally.
            if (u is Understanding.Ask && (QUESTION_WORD.containsMatchIn(text) || Text.isQuestionMarked(raw)) ||
                u is Understanding.Remind && REMIND_WORD.containsMatchIn(text) || u is Understanding.Agenda ||
                u == Understanding.StartIdea || u == Understanding.Undo || text.split(' ').size > 12) return handle(raw, ctx)
            return rememberAnswer(previous, raw, ctx)
        }
        if (previous is Reply.PickTime) {
            val text = Text.normalize(raw)
            pickAnswer(previous, text, ctx)?.let { return it }
            val pick = when {
                PICK_FIRST.matches(text) -> previous.options.first()
                PICK_SECOND.matches(text) -> previous.options.getOrNull(1)
                else -> previous.options.firstOrNull { o ->
                    val h = Instant.fromEpochMilliseconds(o.atMs).toLocalDateTime(ctx.timeZone).hour
                    (AM_WORD.containsMatchIn(text) && h < 12) || (PM_WORD.containsMatchIn(text) && h >= 12)
                }
            }
            if (pick != null) return pickTimeOption(previous, pick, ctx)
            // A whole new time ("tomorrow at 8 pm"): schedule that instead.
            TimeParser.parse(text, ctx.nowMs, ctx.timeZone)?.takeIf { it.alternative == null }?.let { t ->
                return schedule(previous.memory, t, null, previous.persistent, ctx)
            }
            return handle(raw, ctx)
        }
        if (previous is Reply.ConfirmSecret) {
            val text = Text.normalize(raw)
            if (YES.matches(text)) return confirmSecret(previous, ctx)
            if (NO.matches(text)) return Reply.NotSaved
            // "no it is sunflower124", "it's sunflower124": the corrected value, shown again for a yes.
            CORRECT_VALUE.find(text)?.let { m ->
                val fixed = m.groupValues[1].trim()
                val i = raw.lowercase().lastIndexOf(fixed)
                val v = if (i >= 0) raw.substring(i, i + fixed.length) else fixed
                return previous.copy(value = v)
            }
            return handle(raw, ctx)
        }
        val memory = when (previous) {
            is Reply.NeedsTime -> previous.memory
            is Reply.NeedsPlace -> previous.memory
            Reply.NeedsLocation -> null
            else -> null
        }
        if (memory != null && core.memories.get(memory.id)?.deletedAt == null) {
            val text = Text.normalize(raw)
            // "no", "skip", "just save it": keep it as a to-do without a time.
            if (NO_TIME.matches(text)) return Reply.Saved(memory)
            val known = core.places.userPlaces().map { it.label.lowercase() }.toSet()
            var time = TimeParser.parse(text, ctx.nowMs, ctx.timeZone)
            var moment = PlaceParser.parse(text, known)
            if ((time == null && moment == null) || (time?.timeWasDefaulted == true && TIMEISH.containsMatchIn(text))) {
                // "sometime next week", "when I'm done with work": the mind turns it into a clear time.
                val remind = mind?.let { m ->
                    runCatching { m.understand("remind me to ${memory.body} $raw", today(ctx)) }.getOrNull()?.firstOrNull() as? Command.Remind
                }
                if (remind != null) {
                    val t = Text.normalize(remind.text)
                    time = mergeTimes(time, TimeParser.parse(t, ctx.nowMs, ctx.timeZone), ctx)
                    if (moment == null) moment = PlaceParser.parse(t, known)
                }
            }
            if (time == null && moment == null) moment = bareMoment(text)
            if (time != null || moment != null) return schedule(memory, time, moment, persistent = false, ctx)
        }
        return handle(raw, ctx)
    }

    /** "home", "at big mart", "any pharmacy" said on their own as an answer to "when?". */
    private fun bareMoment(text: String): MomentMatch? {
        val explicit = text.startsWith("at ") || text.startsWith("in ") || text.startsWith("when i ")
        val name = text.removePrefix("when i reach ").removePrefix("when i get to ").removePrefix("at ").removePrefix("in ").trim()
        if (name.isEmpty() || name.split(' ').size > 4) return null
        // A bare word is a place only if it names one Kataa knows, or a kind of place; "after lunch" is not.
        val knownPlace = core.places.findByLabel(Text.stripDeterminers(name)).isNotEmpty() || IntentParser.isHomeWord(name)
        if (!explicit && !knownPlace && PlaceParser.typeOf(name.removePrefix("any ").trim()) == null &&
            PlaceRepository.normalizeBrand(name)?.let { b -> core.places.nearest(0.0, 0.0, brand = b, maxDistanceM = 2.1e7, limit = 1).isNotEmpty() } != true
        ) return null
        val kind = PlaceParser.typeOf(name.removePrefix("any ").trim())
        val ref = if (kind != null) PlaceRef.Kind(kind, name) else PlaceRef.Named(name)
        return MomentMatch.Place(ref, onExit = false, 0 until text.length)
    }

    private fun schedule(memory: Memory, time: app.kataa.core.nlu.TimeMatch?, moment: MomentMatch?, persistent: Boolean, ctx: AssistantContext): Reply {
        val created = mutableListOf<Reminder>()
        val parts = mutableListOf<String>()
        var hint: String? = null
        time?.let { t ->
            created += core.reminders.addTime(memory.id, t.fireAt.toEpochMilliseconds(), ctx.timeZone.id, t.rrule, persistent)
            parts += Describe.time(t.fireAt, t.rrule, ctx)
        }
        when (moment) {
            is MomentMatch.Bluetooth -> {
                created += core.reminders.addBluetooth(memory.id, moment.deviceAlias, moment.onDisconnect, persistent)
                parts += when {
                    moment.onDisconnect -> "when you get out of the ${moment.deviceAlias}"
                    moment.deviceAlias in setOf("earbuds", "headphones") -> "when you put on your ${moment.deviceAlias}"
                    else -> "when you get in the ${moment.deviceAlias}"
                }
            }
            is MomentMatch.Place -> when (val target = resolvePlace(moment.ref, ctx)) {
                is Resolved.Target -> {
                    created += core.reminders.addPlace(memory.id, target.target, onExit = moment.onExit, persistent = persistent)
                    parts += (if (moment.onExit) "when you leave " else "when you arrive at ") + target.spoken
                    // A shop Kataa has never been told about can't fire yet: say how to teach it, once.
                    (target.target as? PlaceTarget.Brand)?.let { b ->
                        val known = core.places.nearest(0.0, 0.0, brand = b.brand, maxDistanceM = 2.1e7, limit = 1).isNotEmpty()
                        if (!known) hint = "Next time you're at ${restoreTitle(b.brand)}, say \"this is ${restoreTitle(b.brand)}\" once and I'll know it from then on."
                    }
                }
                is Resolved.Unknown -> return Reply.NeedsPlace(memory, target.name)
                Resolved.NoLocation -> return Reply.NeedsLocation
            }
            null -> Unit
        }
        if (created.isEmpty()) return Reply.NeedsTime(memory)
        return Reply.ReminderSet(memory, created, Text.sentence(parts.joinToString(", or ")), hint)
    }

    private sealed interface Resolved {
        data class Target(val target: PlaceTarget, val spoken: String) : Resolved
        data class Unknown(val name: String) : Resolved
        data object NoLocation : Resolved
    }

    private fun resolvePlace(ref: PlaceRef, ctx: AssistantContext): Resolved = when (ref) {
        PlaceRef.Here -> ctx.location?.let { loc ->
            val p = core.places.save("Saved spot", loc.lat, loc.lng)
            Resolved.Target(PlaceTarget.Specific(p.id), "this spot")
        } ?: Resolved.NoLocation
        // A place the user pinned and named ("Gym") beats the kind of place ("any gym").
        is PlaceRef.Kind -> Text.stripDeterminers(ref.spoken).let { name ->
            core.places.findByLabel(name).firstOrNull { it.origin != app.kataa.core.model.PlaceOrigin.POI_PACK && it.brand == null }
                ?.let { Resolved.Target(PlaceTarget.Specific(it.id), name) }
                ?: Resolved.Target(PlaceTarget.Type(ref.placeType), "any $name")
        }
        is PlaceRef.Named -> {
            val name = Text.stripDeterminers(ref.name)
            val saved = core.places.findByLabel(name).firstOrNull { it.origin != app.kataa.core.model.PlaceOrigin.POI_PACK }
            when {
                saved != null && saved.brand == null -> Resolved.Target(PlaceTarget.Specific(saved.id), name)
                IntentParser.isHomeWord(name) -> Resolved.Unknown(name)
                // Anything else named is treated as a chain/brand: it fires at every known branch,
                // and Kataa learns branches when the user says "this is <name>".
                else -> Resolved.Target(PlaceTarget.Brand(name), "any ${restoreTitle(name)}")
            }
        }
    }

    private fun ask(u: Understanding.Ask, ctx: AssistantContext): Reply {
        if (u.type == QuestionType.DID_I && u.subject != null) {
            val tz = ctx.timeZone
            val today = Instant.fromEpochMilliseconds(ctx.nowMs).toLocalDateTime(tz).date
            val day = today.minus(u.daysAgo, DateTimeUnit.DAY)
            val from = day.atStartOfDayIn(tz).toEpochMilliseconds()
            val to = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz).toEpochMilliseconds()
            val latest = core.memories.historyFor(u.subject, limit = 20)?.let { listOf(it.latest) + it.previous }
                ?.firstOrNull { it.createdAt in from until to }
            return Reply.DidIAnswer(done = latest != null, memory = latest)
        }
        if (u.type == QuestionType.WHERE && u.subject != null) {
            core.memories.historyFor(u.subject)?.let { h ->
                val loc = ObjectLocation.extract(Text.normalize(h.latest.body))?.location
                return Reply.Answer(Text.sentence(loc ?: h.latest.body), h.latest, h.previous, emptyList())
            }
        }
        val result = core.search.search(u.query, SearchOptions(limit = 5, includePrivate = ctx.privateUnlocked))
        // With the mind: only when search is unsure (no confident hit, or two hits nearly tied) does it read
        // the closest notes and say which one answers. A confident search answers at once, without the AI.
        val m = mind
        if (m != null && result.hits.isNotEmpty() && unsure(result)) {
            val pick = runCatching { m.pickAnswer(u.query, result.hits.map { it.memory.body }) }.getOrNull()
            if (pick != null && pick.index > 0) {
                val hit = result.hits[pick.index - 1]
                return Reply.Answer(pick.answer.ifBlank { hit.memory.body }, hit.memory, emptyList(), result.hits)
            }
            if (pick != null) {
                if (!ctx.privateUnlocked && lockedAnswerExists(u)) return Reply.NeedsUnlock(u.query)
                return notFound(u)
            }
        }
        val top = result.hits.firstOrNull()
        if (!result.confident || top == null || !aboutTheSameThing(u, top)) {
            if (!ctx.privateUnlocked && lockedAnswerExists(u)) {
                return Reply.NeedsUnlock(u.query)
            }
            return notFound(u)
        }
        return Reply.Answer(shortAnswer(u.type, top.memory, ctx), top.memory, emptyList(), result.hits)
    }

    /**
     * "What is my mom's birthday?" must not be answered with "Call mom": a general question names its thing
     * last ("birthday"), and the answer has to mention it (or a word for it) unless the meaning is almost the same.
     */
    private fun aboutTheSameThing(u: Understanding.Ask, hit: SearchHit): Boolean {
        if (u.type != QuestionType.GENERAL) return true
        if ((hit.similarity ?: 0.0) >= SURE_SIMILARITY) return true
        val head = app.kataa.core.search.FtsQuery.tokens(u.query).lastOrNull { it !in VAGUE_HEADS } ?: return true
        val words = app.kataa.core.search.FtsQuery.tokens(hit.memory.body, maxTokens = 512)
        val wanted = setOf(head) + (app.kataa.core.search.FtsQuery.SYNONYMS[head] ?: emptySet())
        return words.any { w -> wanted.any { h -> w.startsWith(h.take(4)) || h.startsWith(w.take(4)) && w.length >= 4 || w == h } }
    }

    /** A Private memory would answer this (only asked when nothing visible did): worth asking for unlock. */
    private fun lockedAnswerExists(u: Understanding.Ask): Boolean {
        val r = core.search.search(u.query, SearchOptions(limit = 1, includePrivate = true))
        val top = r.hits.firstOrNull() ?: return false
        return r.confident && aboutTheSameThing(u, top)
    }

    /** Search can't decide alone: nothing confident, or the best two are nearly tied. */
    private fun unsure(result: app.kataa.core.search.SearchResult): Boolean {
        if (!result.confident) return true
        val (a, b) = result.hits.getOrNull(0) to result.hits.getOrNull(1)
        return a != null && b != null && b.score >= a.score * NEAR_TIE
    }

    private fun shortAnswer(type: QuestionType, m: Memory, ctx: AssistantContext): String {
        val norm = Text.normalize(m.body)
        when (type) {
            QuestionType.WHERE -> ObjectLocation.extract(norm)?.let { return Text.sentence(it.location) }
            QuestionType.WHEN -> TimeParser.parse(norm, m.createdAt, ctx.timeZone)?.let { t ->
                return Text.sentence(norm.substring(t.range.first, (t.range.last + 1).coerceAtMost(norm.length)).trim())
            }
            else -> Unit
        }
        return m.body
    }

    private fun correct(newText: String, ctx: AssistantContext): Reply {
        val last = lastMemory(ctx) ?: return Reply.NeedsClarification(newText)
        val norm = Text.normalize(newText)
        val reminders = core.reminders.forMemory(last.id)
        if (last.kind == MemoryKind.REMINDER) {
            // "actually, at 6" / "no, tomorrow": move the reminder, keep the text.
            TimeParser.parse(norm, ctx.nowMs, ctx.timeZone)?.let { t ->
                reminders.filter { it.status.name in setOf("SCHEDULED", "SNOOZED") }.forEach { core.reminders.cancel(it.id) }
                val r = core.reminders.addTime(last.id, t.fireAt.toEpochMilliseconds(), ctx.timeZone.id, t.rrule)
                return Reply.ReminderSet(last, listOf(r), Describe.time(t.fireAt, t.rrule, ctx))
            }
        }
        val newBody = if (last.kind == MemoryKind.OBJECT_LOCATION && last.subject != null && LOCATION_ONLY.containsMatchIn(norm)) {
            // "actually, in the car" -> "My keys are in the car"
            val old = ObjectLocation.extract(Text.normalize(last.body))
            val thing = old?.thing ?: last.subject
            val verb = if (thing.endsWith("s") && !thing.endsWith("ss")) "are" else "is"
            "My $thing $verb $norm"
        } else {
            IntentParser.saveBody(newText)
        }
        val updated = core.memories.update(last.id, MemoryEdit(body = Text.sentence(newBody)))
        return Reply.Corrected(last, updated)
    }

    private fun undo(): Reply {
        val id = Migrations.getMeta(core.db, LAST_KEY)?.toLongOrNull() ?: return Reply.NothingToUndo
        val m = core.memories.get(id)?.takeIf { it.deletedAt == null } ?: return Reply.NothingToUndo
        core.memories.trash(id)
        // Undoing a replace ("change my wifi password to x") brings the old one back.
        m.supersedesId?.let { old -> core.memories.get(old)?.takeIf { it.deletedAt != null }?.let { core.memories.restore(old) } }
        Migrations.setMeta(core.db, LAST_KEY, "")
        return Reply.Undone(m)
    }

    private fun savePlace(raw: String, u: Understanding.SavePlace, ctx: AssistantContext): Reply {
        val name = Text.stripDeterminers(u.name)
        val brandKey = PlaceRepository.normalizeBrand(name)
        val knownBrand = brandKey != null && core.places.nearest(0.0, 0.0, brand = name, maxDistanceM = 2.1e7, limit = 1).isNotEmpty()
        val pendingBrand = brandKey != null && core.reminders.active().any { (it.placeTarget as? PlaceTarget.Brand)?.brand == brandKey }
        val isHome = IntentParser.isHomeWord(name)
        if (!u.explicit && !knownBrand && !pendingBrand && !isHome) return saveAsNote(raw, ctx)
        val loc = ctx.location ?: return Reply.NeedsLocation
        val place = if (isHome) core.places.save(name, loc.lat, loc.lng)
        else core.places.learnBranch(name, restoreTitle(name), loc.lat, loc.lng)
        return Reply.PlaceSaved(place, core.reminders.matchingPlace(place))
    }

    private fun browse(u: Understanding.BrowseCategory, ctx: AssistantContext): Reply {
        val key = Text.singular(Text.stripDeterminers(u.name))
        if (key in VAULT_WORDS) {
            // "show me all my passwords": the vault, after unlock.
            if (!ctx.privateUnlocked) return Reply.NeedsUnlock(u.fallbackQuery)
            val vault = core.categories.list().first { it.isPrivate }
            return Reply.CategoryList(vault, secrets())
        }
        val cat = core.categories.list().firstOrNull { c ->
            val n = c.name.lowercase()
            n == u.name || Text.singular(n) == key || c.slug == key || n.split(' ').any { Text.singular(it) == key }
        } ?: return ask(Understanding.Ask(u.fallbackQuery, QuestionType.GENERAL, null), ctx)
        if (cat.isPrivate && !ctx.privateUnlocked) return Reply.CategoryList(cat, emptyList())
        val inside = core.categories.memoriesIn(cat.id)
        // A password filed under Money too stays hidden until unlock.
        val hidden = if (ctx.privateUnlocked) emptySet() else core.memories.privateIds(inside.map { it.id })
        return Reply.CategoryList(cat, inside.filter { it.id !in hidden })
    }

    /** Every password and other private memory, newest first: the Private category plus secrets not yet classified. */
    fun secrets(): List<Memory> {
        val recent = core.memories.recent(limit = 2000)
        val ids = core.memories.privateIds(recent.map { it.id })
        return recent.filter { it.id in ids }
    }

    private fun lastMemory(ctx: AssistantContext): Memory? {
        val id = Migrations.getMeta(core.db, LAST_KEY)?.toLongOrNull() ?: return null
        return core.memories.get(id)?.takeIf { it.deletedAt == null && ctx.nowMs - it.updatedAt <= CORRECTION_WINDOW_MS }
    }

    private fun setLast(id: Long) = Migrations.setMeta(core.db, LAST_KEY, id.toString())

    private fun restoreTitle(s: String) = s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    companion object {
        private const val LAST_KEY = "assistant_last_memory_id"
        private val CANCEL = Regex("^(?:cancel|cancel it|never mind|nevermind|forget it|stop|do not save|do not save it)$")
        private val YES = Regex("^(?:yes|yeah|yep|yup|ya|haan|ho|correct|right|that is right|that is correct|exactly|save it|save|ok|okay|ok save it|sure|confirm|yes save it|yes correct|perfect)$")
        private val NO = Regex("^(?:no|nope|nah|cancel|do not save|do not save it|forget it|never mind|nevermind|stop|wrong)$")
        private val CORRECT_VALUE = Regex("^(?:no|nope|wrong|not that)?[ ,]*(?:it is|its|it was|the password is|password is|make it|change it to|i said)\\s+(.+)$")
        private val MORNING_HINT = Regex("\\b(?:wake|wake up|breakfast|morning|sunrise|before work|before office|before college)\\b")
        private val EVENING_HINT = Regex("\\b(?:dinner|supper|tonight|evening|night|after work|after office|after college|sunset)\\b")
        private val AM_WORD = Regex("\\b(?:am|a m|morning|in the morning|tomorrow morning|early)\\b")
        private val PM_WORD = Regex("\\b(?:pm|p m|evening|night|tonight|afternoon|in the evening)\\b")
        private val PICK_FIRST = Regex("^(?:the )?(?:first|1st)(?: (?:one|1))?$|^(?:yes|yeah|ok|okay|that one|right|correct)$")
        private val PICK_SECOND = Regex("^(?:the )?(?:second|2nd|other|last)(?: (?:one|1))?$")
        private val QUESTION_WORD = Regex("^(?:what|where|when|who|whom|which|how|why|did|do|does|is|are|can|could|show|find|tell|give|search)\\b")
        private val ASKABLE = Regex("^(what|when|which)(?: is| are| was|s)? (?:my |our |the )?(.+?)$")
        private val ASKABLE_SKIP = Regex("\\b(?:time|today|tomorrow|weather|date today|day today|reminders?|tasks?|plans?)\\b|^(?:i |it |that |this |did |do |does |have |had |was |were |am |are )")
        private val YEARLY_THING = Regex("\\b(?:birthday|bday|anniversary|death anniversary|wedding day)\\b")
        private val REMIND_WORD = Regex("\\bremind\\b")
        private val VAULT_WORDS = setOf("password", "passcode", "pin", "secret", "private", "code")
        private const val CORRECTION_WINDOW_MS = 30 * 60 * 1000L
        private const val SURE_SIMILARITY = 0.80
        private val VAGUE_HEADS = setOf("thing", "things", "stuff", "one", "again", "now", "today", "please", "number", "name")
        /** Second hit within 8% of the first: let the mind choose between them. */
        private const val NEAR_TIE = 0.92
        /** Where a second request starts. The lookahead keeps "my"/"the"/"i" with the second part. */
        private val SPLIT = Regex("\\s(?:and also|and then|and|also|plus)\\s(?=(?:my|the|our|i|remind|also)\\b)")
        private val MULTI = Regex("\\b(?:and also|and then|also|and remind|and my|and i|but|plus)\\b")
        private val HINTS = Regex("\\b(?:forget|remember to|have to|need to|should|must|tell me|what|where|when|which|how)\\b")
        private val TIMEISH = Regex("\\d|\\b(?:after|before|later|lunch|dinner|breakfast|noon|evening|night|morning|afternoon|week|month|o'clock)\\b")
        private val SMALL_TALK = Regex("^(?:(?:ok|okay|ok ok|thanks|thank you|thanks a lot|thank you so much|cool|great|nice|good|hi|hello|hey|bye|good night|good morning|yes|yeah|no|sure|alright|all right|fine|wow)\\s*)+$")
        private val NO_TIME = Regex("^(?:no|nope|nah|no thanks|no thank you|skip|skip it|nothing|no time|not now|never mind|nevermind|just save it|just save|save it|later|i will decide later|do not remind me|no need|it is fine|fine|ok|okay|leave it)$")
        private val LOCATION_ONLY = Regex("^(?:in|inside|on|under|behind|beside|next to|near|by|at|in front of|on top of|with)\\b")
    }
}
