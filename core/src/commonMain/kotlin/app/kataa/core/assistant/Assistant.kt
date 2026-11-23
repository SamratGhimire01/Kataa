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
import app.kataa.core.nlu.IntentParser
import app.kataa.core.nlu.MomentMatch
import app.kataa.core.nlu.ObjectLocation
import app.kataa.core.nlu.PlaceParser
import app.kataa.core.nlu.PlaceRef
import app.kataa.core.nlu.QuestionType
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
    data class ReminderSet(val memory: Memory, val reminders: List<Reminder>, val description: String) : Reply
    /** Reminder text was saved, but Kataa does not know where [placeName] is yet ("home" before it was set). */
    data class NeedsPlace(val memory: Memory, val placeName: String) : Reply
    /** Reminder text was saved, but no time or moment was said: ask "When should I remind you?". */
    data class NeedsTime(val memory: Memory) : Reply
    data class Answer(val shortAnswer: String, val memory: Memory, val previous: List<Memory>, val hits: List<SearchHit>) : Reply
    data class DidIAnswer(val done: Boolean, val memory: Memory?) : Reply
    data class NotFound(val query: String) : Reply
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
}

/**
 * One entry point for everything the user says or types: `handle("where are my keys?")`.
 * Pure rules + local database, so it answers in milliseconds and works offline.
 */
class Assistant(private val core: KataaCore) {

    fun handle(raw: String, ctx: AssistantContext): Reply {
        val known = core.places.userPlaces().map { it.label.lowercase() }.toSet()
        return when (val u = IntentParser.parse(raw, ctx.nowMs, ctx.timeZone, known)) {
            is Understanding.Save -> save(raw, u, ctx)
            is Understanding.Remind -> remind(raw, u, ctx)
            is Understanding.Ask -> ask(u, ctx)
            is Understanding.Correct -> correct(u.newText, ctx)
            Understanding.Undo -> undo()
            is Understanding.SavePlace -> savePlace(raw, u, ctx)
            is Understanding.CreateCategory -> Reply.CategoryCreated(core.categories.create(u.name, u.description))
            is Understanding.BrowseCategory -> browse(u, ctx)
            is Understanding.Unclear -> Reply.NeedsClarification(raw)
        }
    }

    /** "Save it" after a [Reply.NeedsClarification] or [Reply.NotFound]. */
    fun saveAsNote(raw: String, ctx: AssistantContext): Reply =
        save(raw, Understanding.Save(IntentParser.saveBody(raw), MemoryKind.NOTE, null, null), ctx)

    private fun save(raw: String, u: Understanding.Save, ctx: AssistantContext): Reply {
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
        val memory = core.memories.save(
            NewMemory(u.body, MemoryKind.REMINDER, ctx.source, rawTranscript = raw.takeIf { ctx.source == Source.VOICE }),
        )
        setLast(memory.id)
        return schedule(memory, u.time, u.moment, u.persistent, ctx)
    }

    /**
     * Answers Kataa's own follow-up question. After [Reply.NeedsTime] ("When should I remind you?") the user
     * says "tomorrow at 5" or "when I get home"; after [Reply.NeedsPlace] they can name a time instead.
     * Anything that is not an answer is handled as a new request.
     */
    fun followUp(previous: Reply, raw: String, ctx: AssistantContext): Reply {
        val memory = when (previous) {
            is Reply.NeedsTime -> previous.memory
            is Reply.NeedsPlace -> previous.memory
            Reply.NeedsLocation -> null
            else -> null
        }
        if (memory != null && core.memories.get(memory.id)?.deletedAt == null) {
            val text = Text.normalize(raw)
            val known = core.places.userPlaces().map { it.label.lowercase() }.toSet()
            val time = TimeParser.parse(text, ctx.nowMs, ctx.timeZone)
            val moment = PlaceParser.parse(text, known) ?: if (time == null) bareMoment(text) else null
            if (time != null || moment != null) return schedule(memory, time, moment, persistent = false, ctx)
        }
        return handle(raw, ctx)
    }

    /** "home", "at big mart", "any pharmacy" said on their own as an answer to "when?". */
    private fun bareMoment(text: String): MomentMatch? {
        val name = text.removePrefix("at ").removePrefix("in ").trim()
        if (name.isEmpty() || name.split(' ').size > 4) return null
        val kind = PlaceParser.typeOf(name.removePrefix("any ").trim())
        val ref = if (kind != null) PlaceRef.Kind(kind, name) else PlaceRef.Named(name)
        return MomentMatch.Place(ref, onExit = false, 0 until text.length)
    }

    private fun schedule(memory: Memory, time: app.kataa.core.nlu.TimeMatch?, moment: MomentMatch?, persistent: Boolean, ctx: AssistantContext): Reply {
        val created = mutableListOf<Reminder>()
        val parts = mutableListOf<String>()
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
                }
                is Resolved.Unknown -> return Reply.NeedsPlace(memory, target.name)
                Resolved.NoLocation -> return Reply.NeedsLocation
            }
            null -> Unit
        }
        if (created.isEmpty()) return Reply.NeedsTime(memory)
        return Reply.ReminderSet(memory, created, Text.sentence(parts.joinToString(", or ")))
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
        is PlaceRef.Kind -> Resolved.Target(PlaceTarget.Type(ref.placeType), "any ${Text.stripDeterminers(ref.spoken)}")
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
        val top = result.hits.firstOrNull()
        if (!result.confident || top == null) {
            if (!ctx.privateUnlocked && core.search.search(u.query, SearchOptions(limit = 1, includePrivate = true)).confident) {
                return Reply.NeedsUnlock(u.query)
            }
            return Reply.NotFound(u.query)
        }
        return Reply.Answer(shortAnswer(u.type, top.memory, ctx), top.memory, emptyList(), result.hits)
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
        val cat = core.categories.list().firstOrNull { c ->
            val n = c.name.lowercase()
            n == u.name || Text.singular(n) == key || c.slug == key || n.split(' ').any { Text.singular(it) == key }
        } ?: return ask(Understanding.Ask(u.fallbackQuery, QuestionType.GENERAL, null), ctx)
        if (cat.isPrivate && !ctx.privateUnlocked) return Reply.CategoryList(cat, emptyList())
        return Reply.CategoryList(cat, core.categories.memoriesIn(cat.id))
    }

    private fun lastMemory(ctx: AssistantContext): Memory? {
        val id = Migrations.getMeta(core.db, LAST_KEY)?.toLongOrNull() ?: return null
        return core.memories.get(id)?.takeIf { it.deletedAt == null && ctx.nowMs - it.updatedAt <= CORRECTION_WINDOW_MS }
    }

    private fun setLast(id: Long) = Migrations.setMeta(core.db, LAST_KEY, id.toString())

    private fun restoreTitle(s: String) = s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    companion object {
        private const val LAST_KEY = "assistant_last_memory_id"
        private const val CORRECTION_WINDOW_MS = 30 * 60 * 1000L
        private val LOCATION_ONLY = Regex("^(?:in|inside|on|under|behind|beside|next to|near|by|at|in front of|on top of|with)\\b")
    }
}
