package app.kataa.core.memory

import app.kataa.core.db.Row
import app.kataa.core.db.SqlDriver
import app.kataa.core.db.queryOne
import app.kataa.core.model.GeoPoint
import app.kataa.core.model.Memory
import app.kataa.core.model.MemoryEdit
import app.kataa.core.model.MemoryKind
import app.kataa.core.model.NewMemory
import app.kataa.core.model.Source
import app.kataa.core.model.SubjectHistory
import app.kataa.core.util.IdGenerator
import app.kataa.core.util.TimeSource

/**
 * Saving is deliberately cheap: one INSERT, no ML. Embedding and categorizing happen afterwards
 * (see [app.kataa.core.KataaCore.processPending]), so the UI can confirm instantly.
 */
class MemoryRepository(
    private val db: SqlDriver,
    private val clock: TimeSource,
    private val ids: IdGenerator,
) {
    fun save(new: NewMemory): Memory = db.transaction {
        val body = new.body.trim()
        require(body.isNotEmpty()) { "memory body is empty" }
        val now = clock.nowMs()
        val subject = normalizeSubject(new.subject)
        val supersedes = subject?.let { latestIdFor(it) }
        db.execute(
            """
            INSERT INTO memories(uuid, kind, body, raw_transcript, extracted_text, source, source_app, lang, subject,
                                 captured_lat, captured_lng, captured_acc_m, created_at, updated_at, supersedes_id, pinned)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            ids.newUuid(), new.kind.name, body, new.rawTranscript, new.extractedText?.trim()?.ifEmpty { null },
            new.source.name, new.sourceApp, new.lang, subject,
            new.capturedAt?.lat, new.capturedAt?.lng, new.capturedAt?.accuracyM,
            now, now, supersedes, new.pinned,
        )
        get(db.lastInsertRowId())!!
    }

    fun get(id: Long): Memory? = db.queryOne("SELECT $COLUMNS FROM memories WHERE id = ?", id, map = ::mapMemory)

    fun getByUuid(uuid: String): Memory? =
        db.queryOne("SELECT $COLUMNS FROM memories WHERE uuid = ?", uuid, map = ::mapMemory)

    fun getMany(ids: Collection<Long>): Map<Long, Memory> {
        if (ids.isEmpty()) return emptyMap()
        val result = HashMap<Long, Memory>(ids.size)
        ids.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.query("SELECT $COLUMNS FROM memories WHERE id IN ($placeholders)", *chunk.toTypedArray(), map = ::mapMemory)
                .forEach { result[it.id] = it }
        }
        return result
    }

    /** Newest first. Pass the last memory of the previous page as [after] to get the next page. */
    fun recent(limit: Int = 50, after: Memory? = null): List<Memory> =
        db.query(
            """
            SELECT $COLUMNS FROM memories
            WHERE deleted_at IS NULL AND (? IS NULL OR (created_at, id) < (?, ?))
            ORDER BY created_at DESC, id DESC LIMIT ?
            """.trimIndent(),
            after?.id, after?.createdAt, after?.id, limit, map = ::mapMemory,
        )

    fun update(id: Long, edit: MemoryEdit): Memory = db.transaction {
        val current = get(id) ?: throw NoSuchElementException("memory $id")
        val newSubject = when {
            edit.clearSubject -> null
            edit.subject != null -> normalizeSubject(edit.subject)
            else -> current.subject
        }
        db.execute(
            """
            UPDATE memories SET body = ?, extracted_text = ?, kind = ?, subject = ?, pinned = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            edit.body?.trim()?.also { require(it.isNotEmpty()) { "memory body is empty" } } ?: current.body,
            if (edit.extractedText != null) edit.extractedText.trim().ifEmpty { null } else current.extractedText,
            (edit.kind ?: current.kind).name,
            newSubject,
            edit.pinned ?: current.pinned,
            clock.nowMs(),
            id,
        )
        get(id)!!
    }

    /** Moves to trash. It disappears from search and its reminders are cancelled; [restore] brings it back. */
    fun trash(id: Long): Boolean =
        db.execute("UPDATE memories SET deleted_at = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL", clock.nowMs(), clock.nowMs(), id) > 0

    fun restore(id: Long): Boolean =
        db.execute("UPDATE memories SET deleted_at = NULL, updated_at = ? WHERE id = ? AND deleted_at IS NOT NULL", clock.nowMs(), id) > 0

    fun trashed(limit: Int = 100): List<Memory> =
        db.query("SELECT $COLUMNS FROM memories WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC LIMIT ?", limit, map = ::mapMemory)

    /** Permanently deletes one memory (live or trashed). */
    fun deleteForever(id: Long): Boolean = db.execute("DELETE FROM memories WHERE id = ?", id) > 0

    /** Permanently deletes memories that have been in the trash longer than [olderThanMs]. */
    fun purgeTrash(olderThanMs: Long): Int =
        db.execute("DELETE FROM memories WHERE deleted_at IS NOT NULL AND deleted_at < ?", clock.nowMs() - olderThanMs)

    /** "Where are my keys?": the newest live memory about [subject] and what it replaced. */
    fun historyFor(subject: String, limit: Int = 5): SubjectHistory? {
        val norm = normalizeSubject(subject) ?: return null
        val rows = db.query(
            "SELECT $COLUMNS FROM memories WHERE subject = ? AND deleted_at IS NULL ORDER BY created_at DESC, id DESC LIMIT ?",
            norm, limit + 1, map = ::mapMemory,
        )
        if (rows.isEmpty()) return null
        return SubjectHistory(rows.first(), rows.drop(1))
    }

    /** Ids of live memories that a newer live memory replaces. */
    fun supersededIds(candidates: Collection<Long>): Set<Long> {
        if (candidates.isEmpty()) return emptySet()
        val out = HashSet<Long>()
        candidates.chunked(500).forEach { chunk ->
            val ph = chunk.joinToString(",") { "?" }
            db.query(
                "SELECT DISTINCT supersedes_id FROM memories WHERE deleted_at IS NULL AND supersedes_id IN ($ph)",
                *chunk.toTypedArray(),
            ) { it.long(0) }.forEach(out::add)
        }
        return out
    }

    fun countLive(): Long = db.queryOne("SELECT count(*) FROM memories WHERE deleted_at IS NULL") { it.long(0) } ?: 0

    private fun latestIdFor(subject: String): Long? =
        db.queryOne(
            "SELECT id FROM memories WHERE subject = ? AND deleted_at IS NULL ORDER BY created_at DESC, id DESC LIMIT 1",
            subject,
        ) { it.long(0) }

    companion object {
        internal const val COLUMNS = "id, uuid, kind, body, raw_transcript, extracted_text, source, source_app, lang, subject, " +
            "captured_lat, captured_lng, captured_acc_m, created_at, updated_at, supersedes_id, pinned, deleted_at"

        /** "My Keys " -> "my keys"; blank -> null. Stemming/aliasing is the understanding layer's job (Phase 4). */
        fun normalizeSubject(s: String?): String? =
            s?.trim()?.lowercase()?.replace(Regex("\\s+"), " ")?.ifEmpty { null }

        internal fun mapMemory(r: Row): Memory = Memory(
            id = r.long(0),
            uuid = r.string(1),
            kind = MemoryKind.valueOf(r.string(2)),
            body = r.string(3),
            rawTranscript = r.stringOrNull(4),
            extractedText = r.stringOrNull(5),
            source = Source.valueOf(r.string(6)),
            sourceApp = r.stringOrNull(7),
            lang = r.stringOrNull(8),
            subject = r.stringOrNull(9),
            capturedAt = r.doubleOrNull(10)?.let { lat -> GeoPoint(lat, r.double(11), r.doubleOrNull(12)) },
            createdAt = r.long(13),
            updatedAt = r.long(14),
            supersedesId = r.longOrNull(15),
            pinned = r.long(16) != 0L,
            deletedAt = r.longOrNull(17),
        )
    }
}
