package app.kataa.core

import app.kataa.core.backup.BackupService
import app.kataa.core.categories.CategoryClassifier
import app.kataa.core.categories.CategoryRepository
import app.kataa.core.categories.ClassifierConfig
import app.kataa.core.db.Migrations
import app.kataa.core.db.SqlDriver
import app.kataa.core.embed.Embedder
import app.kataa.core.embed.EmbeddingWorker
import app.kataa.core.memory.MemoryRepository
import app.kataa.core.places.GeofencePlanner
import app.kataa.core.places.PlaceRepository
import app.kataa.core.reminders.ReminderRepository
import app.kataa.core.search.HybridSearch
import app.kataa.core.util.DAY_MS
import app.kataa.core.util.IdGenerator
import app.kataa.core.util.TimeSource

data class ProcessReport(val embedded: Int, val classified: Int, val purged: Int)

/**
 * Entry point of the shared core. One instance per open database, used from one worker thread.
 *
 * Typical flow:
 *  1. `memories.save(...)` when the user speaks/types: instant, returns the saved memory.
 *  2. `processPending()` right after, in the background: embeds new text and assigns categories.
 *  3. `search.search(...)` for questions.
 */
class KataaCore(
    val db: SqlDriver,
    val embedder: Embedder,
    val clock: TimeSource = TimeSource.System,
    ids: IdGenerator = IdGenerator.Random,
    classifierConfig: ClassifierConfig = ClassifierConfig(),
    /** Vector-only matches below this cosine similarity do not count as an answer. */
    minAnswerSimilarity: Double = 0.55,
) : AutoCloseable {
    init {
        Migrations.migrate(db, embedder, clock.nowMs())
    }

    val memories = MemoryRepository(db, clock, ids)
    val embeddings = EmbeddingWorker(db, embedder)
    val search = HybridSearch(db, memories, embedder, clock, minAnswerSimilarity)
    val categories = CategoryRepository(db, clock)
    val classifier = CategoryClassifier(db, embedder, embeddings, clock, classifierConfig)
    val places = PlaceRepository(db, clock)
    val reminders = ReminderRepository(db, clock)
    val geofences = GeofencePlanner(db, clock)
    val backup = BackupService(db, clock)
    val assistant = app.kataa.core.assistant.Assistant(this)

    init {
        categories.onUserConfirmed = { memoryId, categoryId -> classifier.learn(memoryId, categoryId) }
    }

    /** Background upkeep: embed new/edited memories, categorize them, and empty old trash. */
    fun processPending(trashRetentionMs: Long = 30 * DAY_MS): ProcessReport {
        val embedded = embeddings.indexPending()
        val classified = classifier.classifyPending()
        val purged = memories.purgeTrash(trashRetentionMs)
        return ProcessReport(embedded, classified, purged)
    }

    override fun close() = db.close()
}
