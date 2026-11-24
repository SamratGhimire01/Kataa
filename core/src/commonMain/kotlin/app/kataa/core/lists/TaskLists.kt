package app.kataa.core.lists

import app.kataa.core.db.Row
import app.kataa.core.db.SqlDriver
import app.kataa.core.db.queryOne
import app.kataa.core.util.IdGenerator
import app.kataa.core.util.TimeSource
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** One line of a to-do list. [cost] in the user's money (rupees), [minutes] how long it takes, when they said so. */
data class ListItem(
    val id: Long,
    val uuid: String,
    val listKey: String,
    val body: String,
    val position: Int,
    val cost: Double?,
    val minutes: Int?,
    val doneAt: Long?,
    val createdAt: Long,
) {
    val done: Boolean get() = doneAt != null
}

/**
 * A list as shown: its own items, plus (for today's list only) the unfinished ones from the last week, marked
 * by their own [ListItem.listKey]. Nothing is ever lost or shamed: yesterday's undone tasks just come along.
 */
data class TaskList(val key: String, val label: String, val items: List<ListItem>) {
    val doneCount: Int get() = items.count { it.done }
    val left: List<ListItem> get() = items.filter { !it.done }
    val totalCost: Double get() = left.sumOf { it.cost ?: 0.0 }
    val totalMinutes: Int get() = left.sumOf { it.minutes ?: 0 }
    fun carried(item: ListItem) = item.listKey != key
}

/**
 * Which list: "day:2026-11-24" (a day's to-do list), "month:2026-11" (things to do or goals this month),
 * "named:shopping" (a list the user named), or "inbox".
 */
object ListKey {
    const val INBOX = "inbox"
    fun day(d: LocalDate) = "day:$d"
    fun month(year: Int, month: Int) = "month:$year-${month.toString().padStart(2, '0')}"
    fun named(name: String) = "named:" + name.trim().lowercase().replace(Regex("\\s+"), " ")

    fun dayOf(key: String): LocalDate? = if (key.startsWith("day:")) runCatching { LocalDate.parse(key.removePrefix("day:")) }.getOrNull() else null

    /** "today", "tomorrow", "Friday 28 November", "November", "this month", "Shopping list", "Inbox". */
    fun label(key: String, today: LocalDate): String {
        dayOf(key)?.let { d ->
            return when (d) {
                today -> "today"
                today.plus(DatePeriod(days = 1)) -> "tomorrow"
                today.minus(DatePeriod(days = 1)) -> "yesterday"
                else -> "${title(d.dayOfWeek.name)} ${d.day} ${title(d.month.name)}"
            }
        }
        if (key.startsWith("month:")) {
            val (y, m) = key.removePrefix("month:").split('-').map(String::toInt)
            if (y == today.year && m == today.month.ordinal + 1) return "this month"
            return title(kotlinx.datetime.Month.entries[m - 1].name) + if (y != today.year) " $y" else ""
        }
        if (key.startsWith("named:")) return title(key.removePrefix("named:")) + " list"
        return "Inbox"
    }

    private fun title(s: String) = s.lowercase().replaceFirstChar { it.uppercase() }
}

class TaskLists(private val db: SqlDriver, private val clock: TimeSource, private val ids: IdGenerator) {

    /** Adds [bodies] (each already one task) to the end of [key]. Cost and duration said in the text are read out. */
    fun add(key: String, bodies: List<String>): List<ListItem> = db.transaction {
        var pos = (db.queryOne("SELECT max(position) FROM list_items WHERE list_key = ? AND deleted_at IS NULL", key) { it.longOrNull(0) } ?: 0L).toInt()
        bodies.mapNotNull { raw ->
            val p = ListText.parseItem(raw) ?: return@mapNotNull null
            pos += 1
            db.execute(
                "INSERT INTO list_items(uuid, list_key, body, position, cost, minutes, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ids.newUuid(), key, p.body, pos, p.cost, p.minutes, clock.nowMs(),
            )
            get(db.lastInsertRowId())!!
        }
    }

    fun get(id: Long): ListItem? = db.queryOne("SELECT $COLS FROM list_items WHERE id = ?", id, map = ::map)

    fun items(key: String): List<ListItem> =
        db.query("SELECT $COLS FROM list_items WHERE list_key = ? AND deleted_at IS NULL ORDER BY position, id", key, map = ::map)

    /** The list as the user sees it. Today's list also carries the unfinished items of the previous [carryDays] days. */
    fun view(key: String, today: LocalDate, carryDays: Int = 7): TaskList {
        val own = items(key)
        val carried = if (key == ListKey.day(today)) {
            val from = ListKey.day(today.minus(DatePeriod(days = carryDays)))
            db.query(
                """
                SELECT $COLS FROM list_items WHERE list_key >= ? AND list_key < ? AND list_key LIKE 'day:%'
                  AND done_at IS NULL AND deleted_at IS NULL ORDER BY list_key, position
                """.trimIndent(),
                from, key, map = ::map,
            )
        } else emptyList()
        return TaskList(key, ListKey.label(key, today), carried + own)
    }

    fun setDone(id: Long, done: Boolean): ListItem? {
        db.execute("UPDATE list_items SET done_at = ? WHERE id = ?", if (done) clock.nowMs() else null, id)
        return get(id)
    }

    fun edit(id: Long, body: String): ListItem? {
        val p = ListText.parseItem(body) ?: return get(id)
        db.execute("UPDATE list_items SET body = ?, cost = ?, minutes = ? WHERE id = ?", p.body, p.cost, p.minutes, id)
        return get(id)
    }

    fun setCost(id: Long, cost: Double?, minutes: Int?) {
        db.execute("UPDATE list_items SET cost = ?, minutes = ? WHERE id = ?", cost, minutes, id)
    }

    fun remove(id: Long) { db.execute("UPDATE list_items SET deleted_at = ? WHERE id = ?", clock.nowMs(), id) }

    fun move(id: Long, key: String) {
        val pos = (db.queryOne("SELECT max(position) FROM list_items WHERE list_key = ? AND deleted_at IS NULL", key) { it.longOrNull(0) } ?: 0L) + 1
        db.execute("UPDATE list_items SET list_key = ?, position = ? WHERE id = ?", key, pos, id)
    }

    /** Lists that have anything in them, newest first (for the timeline and "show all my lists"). */
    fun keys(): List<String> =
        db.query("SELECT list_key FROM list_items WHERE deleted_at IS NULL GROUP BY list_key ORDER BY max(created_at) DESC") { it.string(0) }

    /** Items finished on [day] (any list), for "what did I do yesterday". */
    fun doneBetween(fromMs: Long, toMs: Long): List<ListItem> =
        db.query("SELECT $COLS FROM list_items WHERE done_at >= ? AND done_at < ? AND deleted_at IS NULL ORDER BY done_at", fromMs, toMs, map = ::map)

    /**
     * The item [query] means: "number 2" / "the second one" by position, otherwise the one sharing most words
     * ("milk" finds "Buy milk and eggs"). Null when nothing matches well enough.
     */
    fun find(list: TaskList, query: String, wantDone: Boolean? = null, strict: Int = 0): ListItem? {
        // "I finished the eggs" about a shopping list means they ran out, not that they were bought.
        if (strict >= 1 && list.key.startsWith("named:")) return null
        val pool = list.items.filter { wantDone == null || it.done == wantDone }.ifEmpty { return null }
        if (ListText.isLast(query)) return list.items.lastOrNull()?.takeIf { it in pool }
        ListText.ordinal(query)?.let { n -> return list.items.getOrNull(n - 1)?.takeIf { it in pool } }
        val q = ListText.words(query)
        if (q.isEmpty()) return null
        // [strict]: the sentence could be ordinary ("I finished the book"), so the item must be named too: mostly (1,
        // "book" alone is not "Book bus ticket") or wholly (2, "the rice is done" is not "Buy rice").
        val need = when (strict) { 0 -> 0.0; 1 -> 0.6; else -> 1.0 }
        return pool.map { it to ListText.overlap(q, ListText.words(it.body)) }
            .filter { (item, s) -> s >= 0.5 && ListText.overlap(ListText.words(item.body).let { w -> if (strict == 1) (w - WEAK_VERBS).ifEmpty { w } else w }, q) >= need }
            .maxByOrNull { (_, s) -> s }?.first
    }

    private fun map(r: Row) = ListItem(
        r.long(0), r.string(1), r.string(2), r.string(3), r.long(4).toInt(), r.doubleOrNull(5), r.longOrNull(6)?.toInt(),
        r.longOrNull(7), r.long(8),
    )

    companion object {
        /** "Do the laundry", "Finish homework": "done with the laundry" names it. ("Buy milk" still needs "buy".) */
        private val WEAK_VERBS = setOf("do", "finish", "complete", "make", "start", "work")
        private const val COLS = "id, uuid, list_key, body, position, cost, minutes, done_at, created_at"
    }
}
