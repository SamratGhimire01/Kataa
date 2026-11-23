package app.kataa.core.categories

import app.kataa.core.db.Row
import app.kataa.core.db.SqlDriver
import app.kataa.core.db.queryOne
import app.kataa.core.memory.MemoryRepository
import app.kataa.core.model.AssignedBy
import app.kataa.core.model.AssignmentState
import app.kataa.core.model.Category
import app.kataa.core.model.CategoryAssignment
import app.kataa.core.model.Memory
import app.kataa.core.util.TimeSource

class CategoryRepository(
    private val db: SqlDriver,
    private val clock: TimeSource,
) {
    /** Called when the user confirms or moves a memory, so the classifier can learn from it. */
    var onUserConfirmed: ((memoryId: Long, categoryId: Long) -> Unit)? = null

    fun list(includeArchived: Boolean = false): List<Category> = db.query(
        """
        SELECT c.id, c.slug, c.name, c.description, c.icon, c.color, c.is_system, c.is_private, c.sort_order, c.archived_at,
               (SELECT count(*) FROM memory_categories_active a WHERE a.category_id = c.id)
        FROM categories c
        WHERE ? OR c.archived_at IS NULL
        ORDER BY c.sort_order, c.name
        """.trimIndent(),
        includeArchived, map = ::mapCategory,
    )

    fun get(id: Long): Category? = list(includeArchived = true).firstOrNull { it.id == id }

    fun bySlug(slug: String): Category? = list(includeArchived = true).firstOrNull { it.slug == slug }

    /** Creates a user category. It works for auto-sorting immediately: its description is embedded on next processing. */
    fun create(
        name: String,
        description: String? = null,
        icon: String? = null,
        color: String? = null,
        isPrivate: Boolean = false,
    ): Category = db.transaction {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "category name is empty" }
        val base = slugify(clean)
        var slug = base
        var n = 2
        while (db.queryOne("SELECT 1 FROM categories WHERE slug = ?", slug) { 1 } != null) slug = "$base-${n++}"
        val order = (db.queryOne("SELECT max(sort_order) FROM categories") { it.longOrNull(0) } ?: 0L) + 10
        db.execute(
            "INSERT INTO categories(slug, name, description, icon, color, is_system, is_private, sort_order, created_at) VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?)",
            slug, clean, description?.trim()?.ifEmpty { null }, icon, color, isPrivate, order, clock.nowMs(),
        )
        get(db.lastInsertRowId())!!
    }

    fun update(id: Long, name: String? = null, description: String? = null, icon: String? = null, color: String? = null, isPrivate: Boolean? = null) {
        val c = get(id) ?: throw NoSuchElementException("category $id")
        val descChanged = description != null && description != c.description
        val nameChanged = name != null && name.trim() != c.name
        db.execute(
            """
            UPDATE categories SET name = ?, description = ?, icon = ?, color = ?, is_private = ?,
                   centroid = CASE WHEN ? THEN NULL ELSE centroid END,
                   centroid_model = CASE WHEN ? THEN NULL ELSE centroid_model END,
                   example_count = CASE WHEN ? THEN 0 ELSE example_count END
            WHERE id = ?
            """.trimIndent(),
            name?.trim() ?: c.name, description ?: c.description, icon ?: c.icon, color ?: c.color, isPrivate ?: c.isPrivate,
            descChanged || nameChanged, descChanged || nameChanged, descChanged || nameChanged, id,
        )
    }

    fun archive(id: Long) { db.execute("UPDATE categories SET archived_at = ? WHERE id = ?", clock.nowMs(), id) }

    fun unarchive(id: Long) { db.execute("UPDATE categories SET archived_at = NULL WHERE id = ?", id) }

    /** Merges [fromId] into [intoId]: user decisions are kept, then [fromId] is deleted. */
    fun merge(fromId: Long, intoId: Long) = db.transaction {
        require(fromId != intoId)
        db.execute(
            """
            INSERT INTO memory_categories(memory_id, category_id, state, confidence, assigned_by, updated_at)
            SELECT memory_id, ?, state, confidence, assigned_by, ? FROM memory_categories WHERE category_id = ? AND state <> 'REJECTED'
            ON CONFLICT(memory_id, category_id) DO NOTHING
            """.trimIndent(),
            intoId, clock.nowMs(), fromId,
        )
        db.execute("DELETE FROM categories WHERE id = ?", fromId)
    }

    /** User puts a memory in a category ("save this under education", or moving it in the UI). */
    fun assign(memoryId: Long, categoryId: Long) {
        db.execute(
            """
            INSERT INTO memory_categories(memory_id, category_id, state, confidence, assigned_by, updated_at)
            VALUES (?, ?, 'CONFIRMED', 1.0, 'USER', ?)
            ON CONFLICT(memory_id, category_id) DO UPDATE SET state = 'CONFIRMED', confidence = 1.0, assigned_by = 'USER', updated_at = excluded.updated_at
            """.trimIndent(),
            memoryId, categoryId, clock.nowMs(),
        )
        onUserConfirmed?.invoke(memoryId, categoryId)
    }

    /** User removes a category from a memory. It is remembered so auto-sorting never re-adds it. */
    fun reject(memoryId: Long, categoryId: Long) {
        db.execute(
            """
            INSERT INTO memory_categories(memory_id, category_id, state, confidence, assigned_by, updated_at)
            VALUES (?, ?, 'REJECTED', NULL, 'USER', ?)
            ON CONFLICT(memory_id, category_id) DO UPDATE SET state = 'REJECTED', confidence = NULL, assigned_by = 'USER', updated_at = excluded.updated_at
            """.trimIndent(),
            memoryId, categoryId, clock.nowMs(),
        )
    }

    /** Move = confirm the new one and reject every other current category. */
    fun move(memoryId: Long, toCategoryId: Long) = db.transaction {
        assignmentsFor(memoryId).filter { it.categoryId != toCategoryId && it.state != AssignmentState.REJECTED }
            .forEach { reject(memoryId, it.categoryId) }
        assign(memoryId, toCategoryId)
    }

    fun assignmentsFor(memoryId: Long, includeRejected: Boolean = false): List<CategoryAssignment> = db.query(
        """
        SELECT c.id, c.slug, c.name, mc.state, mc.confidence, mc.assigned_by
        FROM memory_categories mc JOIN categories c ON c.id = mc.category_id
        WHERE mc.memory_id = ? AND (? OR mc.state <> 'REJECTED')
        ORDER BY CASE mc.state WHEN 'CONFIRMED' THEN 0 WHEN 'AUTO' THEN 1 ELSE 2 END, mc.confidence DESC
        """.trimIndent(),
        memoryId, includeRejected,
    ) { CategoryAssignment(it.long(0), it.string(1), it.string(2), AssignmentState.valueOf(it.string(3)), it.doubleOrNull(4), AssignedBy.valueOf(it.string(5))) }

    fun memoriesIn(categoryId: Long, limit: Int = 50): List<Memory> = db.query(
        """
        SELECT ${prefixed("m")} FROM memory_categories_active a JOIN memories m ON m.id = a.memory_id
        WHERE a.category_id = ? ORDER BY m.created_at DESC LIMIT ?
        """.trimIndent(),
        categoryId, limit, map = MemoryRepository::mapMemory,
    )

    /** Live, processed memories that ended up with no category: the "needs a category" inbox. */
    fun uncategorized(limit: Int = 50): List<Memory> = db.query(
        """
        SELECT ${prefixed("m")} FROM memories m
        WHERE m.deleted_at IS NULL AND m.classified_at IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM memory_categories_active a WHERE a.memory_id = m.id)
        ORDER BY m.created_at DESC LIMIT ?
        """.trimIndent(),
        limit, map = MemoryRepository::mapMemory,
    )

    private fun prefixed(alias: String) = MemoryRepository.COLUMNS.split(", ").joinToString(", ") { "$alias.$it" }

    private fun mapCategory(r: Row) = Category(
        id = r.long(0), slug = r.string(1), name = r.string(2), description = r.stringOrNull(3),
        icon = r.stringOrNull(4), color = r.stringOrNull(5), isSystem = r.long(6) != 0L, isPrivate = r.long(7) != 0L,
        sortOrder = r.long(8).toInt(), archivedAt = r.longOrNull(9), memoryCount = r.long(10).toInt(),
    )

    companion object {
        fun slugify(name: String): String =
            name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
                .replace(Regex("-+"), "-").trim('-').ifEmpty { "category" }
    }
}
