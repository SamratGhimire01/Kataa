package app.kataa.core.model

enum class MemoryKind { NOTE, OBJECT_LOCATION, REMINDER, TASK, FACT }

enum class Source { VOICE, TEXT, SHARE, SCREENSHOT, IMPORT }

data class GeoPoint(val lat: Double, val lng: Double, val accuracyM: Double? = null)

data class Memory(
    val id: Long,
    val uuid: String,
    val kind: MemoryKind,
    val body: String,
    val rawTranscript: String?,
    val extractedText: String?,
    val source: Source,
    val sourceApp: String?,
    val lang: String?,
    val subject: String?,
    val capturedAt: GeoPoint?,
    val createdAt: Long,
    val updatedAt: Long,
    val supersedesId: Long?,
    val pinned: Boolean,
    val deletedAt: Long?,
) {
    val isTrashed: Boolean get() = deletedAt != null
}

/** Input for saving a memory. Only [body] is required. */
data class NewMemory(
    val body: String,
    val kind: MemoryKind = MemoryKind.NOTE,
    val source: Source = Source.TEXT,
    val rawTranscript: String? = null,
    val extractedText: String? = null,
    val sourceApp: String? = null,
    val lang: String? = "en",
    /** Normalized thing this memory is about ("keys"). Newer memories with the same subject supersede older ones. */
    val subject: String? = null,
    val capturedAt: GeoPoint? = null,
    val pinned: Boolean = false,
)

/** Partial update. Null fields are left unchanged; use [clearSubject] to remove a subject. */
data class MemoryEdit(
    val body: String? = null,
    val extractedText: String? = null,
    val kind: MemoryKind? = null,
    val subject: String? = null,
    val clearSubject: Boolean = false,
    val pinned: Boolean? = null,
)

/** The newest live memory about a subject plus the older ones it replaced (newest first). */
data class SubjectHistory(val latest: Memory, val previous: List<Memory>)

enum class AssignmentState { AUTO, CONFIRMED, REJECTED }

enum class AssignedBy { RULES, EMBEDDING, LLM, USER }

data class Category(
    val id: Long,
    val slug: String,
    val name: String,
    val description: String?,
    val icon: String?,
    val color: String?,
    val isSystem: Boolean,
    val isPrivate: Boolean,
    val sortOrder: Int,
    val archivedAt: Long?,
    /** Live memories currently in this category (AUTO or CONFIRMED). */
    val memoryCount: Int,
)

data class CategoryAssignment(
    val categoryId: Long,
    val slug: String,
    val name: String,
    val state: AssignmentState,
    val confidence: Double?,
    val assignedBy: AssignedBy,
)

enum class PlaceOrigin { USER, LEARNED, POI_PACK }

data class Place(
    val id: Long,
    val label: String,
    val brand: String?,
    val placeType: String?,
    val lat: Double,
    val lng: Double,
    val radiusM: Double,
    val origin: PlaceOrigin,
    val poiRef: String?,
)

/** A shop/amenity from the offline store pack. */
data class Poi(
    val ref: String,
    val label: String,
    val brand: String?,
    val placeType: String?,
    val lat: Double,
    val lng: Double,
    val radiusM: Double = 150.0,
)

enum class ReminderType { TIME, PLACE_ENTER, PLACE_EXIT, BLUETOOTH_CONNECT, BLUETOOTH_DISCONNECT }

enum class ReminderStatus { SCHEDULED, FIRED, SNOOZED, DONE, CANCELLED }

/** What a place reminder is attached to: one place, any branch of a chain, or any place of a kind. */
sealed interface PlaceTarget {
    data class Specific(val placeId: Long) : PlaceTarget
    data class Brand(val brand: String) : PlaceTarget
    data class Type(val placeType: String) : PlaceTarget
}

data class Reminder(
    val id: Long,
    val memoryId: Long,
    val type: ReminderType,
    val fireAt: Long?,
    val tz: String?,
    val rrule: String?,
    val placeTarget: PlaceTarget?,
    val dwellMs: Long,
    val btDevice: String?,
    /** "Nag until done": the reminder comes back after being dismissed until marked done. */
    val persistent: Boolean,
    val status: ReminderStatus,
    val snoozeUntil: Long?,
    val lastFiredAt: Long?,
    val createdAt: Long,
)
