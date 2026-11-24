package app.kataa.core.backup

import app.kataa.core.db.SqlDriver
import app.kataa.core.util.TimeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Portable backup (format v1): plain JSON keyed by stable ids (memory uuid, category slug,
 * place label+coordinates). Vectors are not exported: they are rebuilt after import, which keeps
 * the file small and lets a backup move between embedding models.
 *
 * Encryption of the file is done by the platform layer (Android Keystore / iOS Keychain) around
 * these bytes; attachments are added to the archive when the attachment API lands (Phase 5).
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long,
    val memories: List<MemoryDto>,
    val categories: List<CategoryDto>,
    val memoryCategories: List<MemoryCategoryDto>,
    val places: List<PlaceDto>,
    val reminders: List<ReminderDto>,
    val listItems: List<ListItemDto> = emptyList(),
    val journalEntries: List<JournalEntryDto> = emptyList(),
    val journalDays: List<JournalDayDto> = emptyList(),
) {
    companion object { const val FORMAT = "kataa-backup" }
}

@Serializable
data class MemoryDto(
    val uuid: String, val kind: String, val body: String, val rawTranscript: String? = null,
    val extractedText: String? = null, val source: String, val sourceApp: String? = null, val lang: String? = null,
    val subject: String? = null, val lat: Double? = null, val lng: Double? = null, val accM: Double? = null,
    val createdAt: Long, val updatedAt: Long, val supersedesUuid: String? = null, val pinned: Boolean = false,
    val deletedAt: Long? = null,
)

@Serializable
data class CategoryDto(
    val slug: String, val name: String, val description: String? = null, val icon: String? = null,
    val color: String? = null, val isSystem: Boolean, val isPrivate: Boolean, val sortOrder: Int,
    val createdAt: Long, val archivedAt: Long? = null,
)

@Serializable
data class MemoryCategoryDto(
    val memoryUuid: String, val categorySlug: String, val state: String, val confidence: Double? = null,
    val assignedBy: String, val updatedAt: Long,
)

@Serializable
data class PlaceDto(
    val label: String, val brand: String? = null, val placeType: String? = null, val lat: Double, val lng: Double,
    val radiusM: Double, val origin: String, val poiRef: String? = null, val createdAt: Long,
)

@Serializable
data class ReminderDto(
    val memoryUuid: String, val type: String, val fireAt: Long? = null, val tz: String? = null, val rrule: String? = null,
    /** Index into [BackupFile.places]. */
    val placeIndex: Int? = null, val placeBrand: String? = null, val placeType: String? = null, val dwellMs: Long,
    val btDevice: String? = null, val persistent: Boolean = false, val status: String, val snoozeUntil: Long? = null,
    val lastFiredAt: Long? = null, val createdAt: Long,
)

@Serializable
data class ListItemDto(
    val uuid: String, val listKey: String, val body: String, val position: Int, val cost: Double? = null,
    val minutes: Int? = null, val doneAt: Long? = null, val createdAt: Long, val deletedAt: Long? = null,
)

@Serializable
data class JournalEntryDto(val memoryUuid: String, val day: String)

@Serializable
data class JournalDayDto(val day: String, val summary: String? = null, val summaryByAi: Boolean = false, val summarizedAt: Long? = null, val changedAt: Long)

data class ImportReport(
    val memoriesAdded: Int,
    val memoriesUpdated: Int,
    val memoriesSkipped: Int,
    val categoriesAdded: Int,
    val placesAdded: Int,
    val remindersAdded: Int,
)

class BackupService(private val db: SqlDriver, private val clock: TimeSource) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = false }

    fun exportJson(includeTrash: Boolean = true, includePoiPack: Boolean = false): String =
        json.encodeToString(BackupFile.serializer(), export(includeTrash, includePoiPack))

    fun export(includeTrash: Boolean = true, includePoiPack: Boolean = false): BackupFile = db.transaction {
        val memories = db.query(
            """
            SELECT m.uuid, m.kind, m.body, m.raw_transcript, m.extracted_text, m.source, m.source_app, m.lang, m.subject,
                   m.captured_lat, m.captured_lng, m.captured_acc_m, m.created_at, m.updated_at, s.uuid, m.pinned, m.deleted_at
            FROM memories m LEFT JOIN memories s ON s.id = m.supersedes_id
            WHERE ? OR m.deleted_at IS NULL ORDER BY m.id
            """.trimIndent(),
            includeTrash,
        ) {
            MemoryDto(it.string(0), it.string(1), it.string(2), it.stringOrNull(3), it.stringOrNull(4), it.string(5),
                it.stringOrNull(6), it.stringOrNull(7), it.stringOrNull(8), it.doubleOrNull(9), it.doubleOrNull(10),
                it.doubleOrNull(11), it.long(12), it.long(13), it.stringOrNull(14), it.long(15) != 0L, it.longOrNull(16))
        }
        val categories = db.query(
            "SELECT slug, name, description, icon, color, is_system, is_private, sort_order, created_at, archived_at FROM categories ORDER BY sort_order, id",
        ) {
            CategoryDto(it.string(0), it.string(1), it.stringOrNull(2), it.stringOrNull(3), it.stringOrNull(4),
                it.long(5) != 0L, it.long(6) != 0L, it.long(7).toInt(), it.long(8), it.longOrNull(9))
        }
        val links = db.query(
            """
            SELECT m.uuid, c.slug, mc.state, mc.confidence, mc.assigned_by, mc.updated_at
            FROM memory_categories mc JOIN memories m ON m.id = mc.memory_id JOIN categories c ON c.id = mc.category_id
            WHERE ? OR m.deleted_at IS NULL
            """.trimIndent(),
            includeTrash,
        ) { MemoryCategoryDto(it.string(0), it.string(1), it.string(2), it.doubleOrNull(3), it.string(4), it.long(5)) }
        val placeRows = db.query(
            "SELECT id, label, brand, place_type, lat, lng, radius_m, origin, poi_ref, created_at FROM places WHERE ? OR origin <> 'POI_PACK' ORDER BY id",
            includePoiPack,
        ) { it.long(0) to PlaceDto(it.string(1), it.stringOrNull(2), it.stringOrNull(3), it.double(4), it.double(5), it.double(6), it.string(7), it.stringOrNull(8), it.long(9)) }
        val placeIndex = placeRows.withIndex().associate { (i, p) -> p.first to i }
        val reminders = db.query(
            """
            SELECT m.uuid, r.type, r.fire_at, r.tz, r.rrule, r.place_id, r.place_brand, r.place_type, r.dwell_ms, r.bt_device,
                   r.persistent, r.status, r.snooze_until, r.last_fired_at, r.created_at
            FROM reminders r JOIN memories m ON m.id = r.memory_id WHERE ? OR m.deleted_at IS NULL ORDER BY r.id
            """.trimIndent(),
            includeTrash,
        ) {
            val pid = it.longOrNull(5)
            if (pid != null && pid !in placeIndex) null // reminder on a store-pack place that is not exported
            else ReminderDto(it.string(0), it.string(1), it.longOrNull(2), it.stringOrNull(3), it.stringOrNull(4),
                pid?.let(placeIndex::getValue), it.stringOrNull(6), it.stringOrNull(7), it.long(8), it.stringOrNull(9),
                it.long(10) != 0L, it.string(11), it.longOrNull(12), it.longOrNull(13), it.long(14))
        }.filterNotNull()
        BackupFile(
            exportedAt = clock.nowMs(), memories = memories, categories = categories, memoryCategories = links,
            places = placeRows.map { it.second }, reminders = reminders,
            listItems = db.query(
                "SELECT uuid, list_key, body, position, cost, minutes, done_at, created_at, deleted_at FROM list_items WHERE ? OR deleted_at IS NULL ORDER BY id",
                includeTrash,
            ) { ListItemDto(it.string(0), it.string(1), it.string(2), it.long(3).toInt(), it.doubleOrNull(4), it.longOrNull(5)?.toInt(), it.longOrNull(6), it.long(7), it.longOrNull(8)) },
            journalEntries = db.query(
                "SELECT m.uuid, j.day FROM journal_entries j JOIN memories m ON m.id = j.memory_id WHERE ? OR m.deleted_at IS NULL ORDER BY j.memory_id",
                includeTrash,
            ) { JournalEntryDto(it.string(0), it.string(1)) },
            journalDays = db.query(
                """
                SELECT day, summary, summary_by_ai, summarized_at, changed_at FROM journal_days d
                WHERE d.entry_count = (SELECT count(*) FROM journal_entries j JOIN memories m ON m.id = j.memory_id
                                       WHERE j.day = d.day AND m.deleted_at IS NULL)
                ORDER BY day
                """.trimIndent(),
            ) {
                JournalDayDto(it.string(0), it.stringOrNull(1), it.long(2) != 0L, it.longOrNull(3), it.long(4))
            },
        )
    }

    fun importJson(text: String): ImportReport {
        val file = json.decodeFromString(BackupFile.serializer(), text)
        require(file.format == BackupFile.FORMAT) { "not a Kataa backup" }
        require(file.version <= 1) { "backup version ${file.version} is newer than this app" }
        return import(file)
    }

    /**
     * Merges a backup into this database. Memories are matched by uuid: newer edits win, otherwise
     * the local copy is kept. User category decisions on this device are never overwritten by AUTO ones.
     * Running the same import twice changes nothing.
     */
    fun import(file: BackupFile): ImportReport = db.transaction {
        var added = 0; var updated = 0; var skipped = 0; var catsAdded = 0; var placesAdded = 0; var remindersAdded = 0

        file.categories.forEach { c ->
            catsAdded += db.execute(
                """
                INSERT OR IGNORE INTO categories(slug, name, description, icon, color, is_system, is_private, sort_order, created_at, archived_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                c.slug, c.name, c.description, c.icon, c.color, c.isSystem, c.isPrivate, c.sortOrder, c.createdAt, c.archivedAt,
            )
        }

        file.memories.forEach { m ->
            val existing = db.query("SELECT id, updated_at FROM memories WHERE uuid = ?", m.uuid) { it.long(0) to it.long(1) }.firstOrNull()
            when {
                existing == null -> {
                    db.execute(
                        """
                        INSERT INTO memories(uuid, kind, body, raw_transcript, extracted_text, source, source_app, lang, subject,
                            captured_lat, captured_lng, captured_acc_m, created_at, updated_at, pinned, deleted_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        m.uuid, m.kind, m.body, m.rawTranscript, m.extractedText, m.source, m.sourceApp, m.lang, m.subject,
                        m.lat, m.lng, m.accM, m.createdAt, m.updatedAt, m.pinned, m.deletedAt,
                    )
                    added++
                }
                m.updatedAt > existing.second -> {
                    db.execute(
                        """
                        UPDATE memories SET kind = ?, body = ?, raw_transcript = ?, extracted_text = ?, subject = ?, pinned = ?,
                            updated_at = ?, deleted_at = ? WHERE id = ?
                        """.trimIndent(),
                        m.kind, m.body, m.rawTranscript, m.extractedText, m.subject, m.pinned, m.updatedAt, m.deletedAt, existing.first,
                    )
                    updated++
                }
                else -> skipped++
            }
        }
        // Second pass: links between memories, now that every uuid exists.
        file.memories.filter { it.supersedesUuid != null }.forEach { m ->
            db.execute(
                "UPDATE memories SET supersedes_id = (SELECT id FROM memories WHERE uuid = ?) WHERE uuid = ? AND supersedes_id IS NULL",
                m.supersedesUuid, m.uuid,
            )
        }

        file.memoryCategories.forEach { l ->
            db.execute(
                """
                INSERT INTO memory_categories(memory_id, category_id, state, confidence, assigned_by, updated_at)
                SELECT m.id, c.id, ?, ?, ?, ? FROM memories m, categories c WHERE m.uuid = ? AND c.slug = ?
                ON CONFLICT(memory_id, category_id) DO UPDATE SET
                    state = excluded.state, confidence = excluded.confidence, assigned_by = excluded.assigned_by, updated_at = excluded.updated_at
                WHERE memory_categories.assigned_by <> 'USER' AND excluded.updated_at > memory_categories.updated_at
                """.trimIndent(),
                l.state, l.confidence, l.assignedBy, l.updatedAt, l.memoryUuid, l.categorySlug,
            )
        }
        // Imported links count as classified so the classifier does not overwrite them.
        db.execute(
            "UPDATE memories SET classified_at = ? WHERE classified_at IS NULL AND id IN (SELECT memory_id FROM memory_categories)",
            clock.nowMs(),
        )

        val placeIds = file.places.map { p ->
            placesAdded += db.execute(
                """
                INSERT OR IGNORE INTO places(label, brand, place_type, lat, lng, radius_m, origin, poi_ref, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                p.label, p.brand, p.placeType, p.lat, p.lng, p.radiusM, p.origin, p.poiRef, p.createdAt,
            )
            db.query("SELECT id FROM places WHERE (label = ? AND lat = ? AND lng = ?) OR (poi_ref IS NOT NULL AND poi_ref = ?)",
                p.label, p.lat, p.lng, p.poiRef) { it.long(0) }.first()
        }

        file.reminders.forEach { r ->
            val placeId = r.placeIndex?.let { placeIds[it] }
            val exists = db.query(
                """
                SELECT 1 FROM reminders x JOIN memories m ON m.id = x.memory_id
                WHERE m.uuid = ? AND x.type = ? AND x.created_at = ? AND x.fire_at IS ? AND x.place_id IS ?
                  AND x.place_brand IS ? AND x.place_type IS ? AND x.bt_device IS ?
                """.trimIndent(),
                r.memoryUuid, r.type, r.createdAt, r.fireAt, placeId, r.placeBrand, r.placeType, r.btDevice,
            ) { 1 }.isNotEmpty()
            if (!exists) {
                remindersAdded += db.execute(
                    """
                    INSERT INTO reminders(memory_id, type, fire_at, tz, rrule, place_id, place_brand, place_type, dwell_ms, bt_device,
                        persistent, status, snooze_until, last_fired_at, created_at)
                    SELECT id, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? FROM memories WHERE uuid = ?
                    """.trimIndent(),
                    r.type, r.fireAt, r.tz, r.rrule, placeId, r.placeBrand, r.placeType, r.dwellMs, r.btDevice,
                    r.persistent, r.status, r.snoozeUntil, r.lastFiredAt, r.createdAt, r.memoryUuid,
                )
            }
        }
        file.listItems.forEach { i ->
            db.execute(
                """
                INSERT INTO list_items(uuid, list_key, body, position, cost, minutes, done_at, created_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET done_at = coalesce(list_items.done_at, excluded.done_at)
                """.trimIndent(),
                i.uuid, i.listKey, i.body, i.position, i.cost, i.minutes, i.doneAt, i.createdAt, i.deletedAt,
            )
        }
        file.journalEntries.forEach { j ->
            db.execute("INSERT OR IGNORE INTO journal_entries(memory_id, day) SELECT id, ? FROM memories WHERE uuid = ?", j.day, j.memoryUuid)
        }
        file.journalDays.forEach { d ->
            db.execute(
                "INSERT OR IGNORE INTO journal_days(day, summary, summary_by_ai, summarized_at, changed_at, entry_count) SELECT ?, ?, ?, ?, ?, count(*) FROM journal_entries WHERE day = ?",
                d.day, d.summary, d.summaryByAi, d.summarizedAt, d.changedAt, d.day,
            )
        }
        ImportReport(added, updated, skipped, catsAdded, placesAdded, remindersAdded)
    }
}
