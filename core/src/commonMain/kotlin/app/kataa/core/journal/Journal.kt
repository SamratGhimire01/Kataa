package app.kataa.core.journal

import app.kataa.core.db.SqlDriver
import app.kataa.core.db.queryOne
import app.kataa.core.memory.MemoryRepository
import app.kataa.core.model.Memory
import app.kataa.core.util.TimeSource
import kotlinx.datetime.LocalDate

/** One day of the journal: its summary (by the AI while charging, or the quick one) and what was said, in order. */
data class JournalDay(val day: LocalDate, val summary: String?, val summaryByAi: Boolean, val entries: List<Memory>)

/**
 * The journal. Each entry is an ordinary memory (so "when did I last feel this tired?" finds it), linked to the
 * day it was said. A day gets a quick summary at once, without any AI (saving stays instant); the background job
 * replaces it with the AI's summary when the phone is charging.
 */
class Journal(private val db: SqlDriver, private val memories: MemoryRepository, private val clock: TimeSource) {

    fun addEntry(memoryId: Long, day: LocalDate) = db.transaction {
        db.execute("INSERT OR REPLACE INTO journal_entries(memory_id, day) VALUES (?, ?)", memoryId, day.toString())
        val entries = entries(day)
        db.execute(
            """
            INSERT INTO journal_days(day, summary, summary_by_ai, summarized_at, changed_at, entry_count) VALUES (?, ?, 0, NULL, ?, ?)
            ON CONFLICT(day) DO UPDATE SET changed_at = excluded.changed_at, entry_count = excluded.entry_count,
                summary = CASE WHEN journal_days.summary_by_ai = 1 THEN journal_days.summary ELSE excluded.summary END
            """.trimIndent(),
            day.toString(), quickSummary(entries), clock.nowMs(), entries.size,
        )
    }

    /**
     * An entry was deleted (or restored): that day's summary may quote words that are gone, so it is rebuilt from
     * the live entries at once (and the AI one is asked for again). Runs in the background upkeep.
     */
    fun refreshChangedDays(): Int {
        val days = db.query(
            """
            SELECT d.day FROM journal_days d
            WHERE d.entry_count != (SELECT count(*) FROM journal_entries j JOIN memories m ON m.id = j.memory_id
                                    WHERE j.day = d.day AND m.deleted_at IS NULL)
            """.trimIndent(),
        ) { LocalDate.parse(it.string(0)) }
        for (d in days) {
            val live = entries(d)
            if (live.isEmpty()) { db.execute("DELETE FROM journal_days WHERE day = ?", d.toString()); continue }
            db.execute(
                "UPDATE journal_days SET summary = ?, summary_by_ai = 0, summarized_at = NULL, changed_at = ?, entry_count = ? WHERE day = ?",
                quickSummary(live), clock.nowMs(), live.size, d.toString(),
            )
        }
        return days.size
    }

    fun entries(day: LocalDate): List<Memory> {
        val ids = db.query(
            """
            SELECT j.memory_id FROM journal_entries j JOIN memories m ON m.id = j.memory_id
            WHERE j.day = ? AND m.deleted_at IS NULL ORDER BY m.created_at
            """.trimIndent(),
            day.toString(),
        ) { it.long(0) }
        val byId = memories.getMany(ids)
        return ids.mapNotNull(byId::get)
    }

    fun day(day: LocalDate): JournalDay {
        val row = db.queryOne("SELECT summary, summary_by_ai FROM journal_days WHERE day = ?", day.toString()) { it.stringOrNull(0) to (it.long(1) != 0L) }
        val entries = entries(day)
        return JournalDay(day, row?.first ?: entries.takeIf { it.isNotEmpty() }?.let(::quickSummary), row?.second ?: false, entries)
    }

    /** Days that have entries, newest first. */
    fun days(limit: Int = 366): List<LocalDate> = db.query(
        """
        SELECT j.day FROM journal_entries j JOIN memories m ON m.id = j.memory_id AND m.deleted_at IS NULL
        GROUP BY j.day ORDER BY j.day DESC LIMIT ?
        """.trimIndent(),
        limit,
    ) { LocalDate.parse(it.string(0)) }

    fun isJournal(memoryId: Long): Boolean =
        db.queryOne("SELECT 1 FROM journal_entries WHERE memory_id = ?", memoryId) { true } ?: false

    /** Days whose AI summary is missing or older than their last entry. */
    fun needingSummary(limit: Int = 7): List<LocalDate> = db.query(
        "SELECT day FROM journal_days WHERE summarized_at IS NULL OR summarized_at < changed_at ORDER BY day DESC LIMIT ?",
        limit,
    ) { LocalDate.parse(it.string(0)) }

    fun setSummary(day: LocalDate, summary: String, byAi: Boolean) {
        db.execute(
            "UPDATE journal_days SET summary = ?, summary_by_ai = ?, summarized_at = ? WHERE day = ?",
            summary.trim(), byAi, clock.nowMs(), day.toString(),
        )
    }

    companion object {
        /** Without AI: the first sentence of each entry, at most about 240 characters. */
        fun quickSummary(entries: List<Memory>): String {
            val parts = entries.map { firstSentence(it.body) }.filter { it.isNotEmpty() }
            val sb = StringBuilder()
            for (p in parts) {
                if (sb.length + p.length > 240 && sb.isNotEmpty()) break
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(p)
            }
            return sb.toString()
        }

        private fun firstSentence(s: String): String {
            val t = s.trim()
            val end = Regex("[.!?](\\s|$)").find(t)?.range?.first
            val one = if (end != null && end in 15..200) t.substring(0, end + 1) else t.take(160).let { if (t.length > 160) it.substringBeforeLast(' ') + "…" else it }
            return one.trim().let { if (it.last() in ".!?…") it else "$it." }
        }
    }
}
