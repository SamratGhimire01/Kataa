package app.kataa.core.categories

import app.kataa.core.db.SqlDriver
import app.kataa.core.embed.Embedder
import app.kataa.core.embed.EmbeddingWorker
import app.kataa.core.embed.Vectors
import app.kataa.core.search.FtsQuery
import app.kataa.core.util.TimeSource

data class ClassifierConfig(
    /** Minimum cosine similarity to a category prototype. Model-dependent; tuned on the eval set in Phase 2. */
    val minSimilarity: Double = 0.30,
    /** Best category must beat the runner-up by this much, unless both are above [multiLabelFloor]. */
    val minMargin: Double = 0.02,
    /** A second category is added when it is this close to the best and above [minSimilarity]. */
    val multiLabelWithin: Double = 0.02,
    val multiLabelFloor: Double = 0.45,
    /** Added to the similarity when a category keyword appears in the memory. */
    val keywordBoost: Double = 0.15,
    val maxLabels: Int = 2,
)

/**
 * Zero-training categorizer. Each category has a prototype vector (its name + description, then nudged
 * towards memories the user confirms). A memory goes to the closest prototype if it is close enough.
 * Uses the memory vector already computed for search, so it adds no model inference per memory.
 */
class CategoryClassifier(
    private val db: SqlDriver,
    private val embedder: Embedder,
    private val worker: EmbeddingWorker,
    private val clock: TimeSource,
    private val config: ClassifierConfig = ClassifierConfig(),
) {
    private class Proto(val id: Long, val slug: String, val vector: FloatArray, val examples: Int, val isPrivate: Boolean)

    /** (Re)builds prototypes that are missing or were built by another model. */
    fun ensurePrototypes() {
        val stale = db.query(
            "SELECT id, name, description FROM categories WHERE centroid IS NULL OR centroid_model IS NOT ?",
            embedder.modelId,
        ) { Triple(it.long(0), it.string(1), it.stringOrNull(2)) }
        if (stale.isEmpty()) return
        val vectors = embedder.embedBatch(stale.map { (_, name, desc) -> if (desc.isNullOrBlank()) name else "$name: $desc" })
        db.transaction {
            stale.forEachIndexed { i, (id, _, _) ->
                var v = vectors[i]
                var n = 0
                // Fold back user-confirmed examples (e.g. after a model change).
                db.query(
                    "SELECT memory_id FROM memory_categories WHERE category_id = ? AND state = 'CONFIRMED' ORDER BY updated_at DESC LIMIT 50",
                    id,
                ) { it.long(0) }.forEach { mid ->
                    worker.vectorFor(mid)?.let { ex -> v = runningMean(v, n + 1, ex); n++ }
                }
                db.execute(
                    "UPDATE categories SET centroid = ?, centroid_model = ?, example_count = ? WHERE id = ?",
                    Vectors.toBlob(v), embedder.modelId, n, id,
                )
            }
        }
    }

    /** Classifies embedded memories that have not been classified since their last change. */
    fun classifyPending(maxRows: Int = 500): Int {
        ensurePrototypes()
        val protos = loadPrototypes()
        val pending = db.query(
            """
            SELECT id, body, extracted_text FROM memories
            WHERE classified_at IS NULL AND deleted_at IS NULL AND embed_model = ?
            ORDER BY id LIMIT ?
            """.trimIndent(),
            embedder.modelId, maxRows,
        ) { Triple(it.long(0), it.string(1), it.stringOrNull(2)) }
        pending.forEach { (id, body, extracted) -> classifyOne(id, EmbeddingWorker.textFor(body, extracted), protos) }
        return pending.size
    }

    private fun classifyOne(memoryId: Long, text: String, protos: List<Proto>) = db.transaction {
        val now = clock.nowMs()
        val decided = db.query("SELECT category_id, state FROM memory_categories WHERE memory_id = ?", memoryId) {
            it.long(0) to it.string(1)
        }
        val hasUserChoice = decided.any { it.second == "CONFIRMED" }
        val rejected = decided.filter { it.second == "REJECTED" }.map { it.first }.toSet()
        val v = worker.vectorFor(memoryId)
        if (!hasUserChoice && v != null) {
            val tokens = FtsQuery.tokens(text, maxTokens = 64).map(::stem).toSet()
            val scored = protos.filter { it.id !in rejected }.map { p ->
                val kw = DefaultCategories.keywordsBySlug[p.slug].orEmpty().map(::stem)
                val keywordHit = kw.any { it in tokens }
                Triple(p, Vectors.dot(v, p.vector) + if (keywordHit) config.keywordBoost else 0.0, keywordHit)
            }.sortedByDescending { it.second }
            val picks = pick(scored.map { it.second }).toMutableList()
            // Safety rule: anything that looks like a password/PIN always goes to private categories too,
            // so it is hidden from search and the lock screen even if it also fits elsewhere (e.g. Money).
            scored.forEachIndexed { i, (p, _, keywordHit) ->
                if (keywordHit && p.isPrivate && i !in picks) picks += i
            }
            db.execute("DELETE FROM memory_categories WHERE memory_id = ? AND state = 'AUTO'", memoryId)
            picks.forEach { i ->
                val (p, score, keywordHit) = scored[i]
                db.execute(
                    """
                    INSERT INTO memory_categories(memory_id, category_id, state, confidence, assigned_by, updated_at)
                    VALUES (?, ?, 'AUTO', ?, ?, ?)
                    ON CONFLICT(memory_id, category_id) DO NOTHING
                    """.trimIndent(),
                    memoryId, p.id, score.coerceIn(0.0, 1.0), if (keywordHit) "RULES" else "EMBEDDING", now,
                )
            }
        }
        db.execute("UPDATE memories SET classified_at = ? WHERE id = ?", now, memoryId)
    }

    /** Indexes (into the descending score list) of the labels to apply. */
    private fun pick(scores: List<Double>): List<Int> {
        if (scores.isEmpty() || scores[0] < config.minSimilarity) return emptyList()
        val second = scores.getOrNull(1)
        if (second != null && scores[0] - second < config.minMargin && second < config.multiLabelFloor) {
            // Two weak, near-equal candidates: too ambiguous, leave it for the user.
            return emptyList()
        }
        val out = mutableListOf(0)
        var i = 1
        while (i < scores.size && out.size < config.maxLabels &&
            scores[0] - scores[i] <= config.multiLabelWithin && scores[i] >= config.minSimilarity
        ) { out += i; i++ }
        return out
    }

    /** Moves the category prototype towards a memory the user confirmed. */
    fun learn(memoryId: Long, categoryId: Long) {
        val v = worker.vectorFor(memoryId) ?: return
        val row = db.query("SELECT centroid, example_count, centroid_model FROM categories WHERE id = ?", categoryId) {
            Triple(it.bytesOrNull(0), it.long(1).toInt(), it.stringOrNull(2))
        }.firstOrNull() ?: return
        val (blob, n, model) = row
        if (blob == null || model != embedder.modelId) return // rebuilt (with this example) by ensurePrototypes
        val updated = runningMean(Vectors.fromBlob(blob), n + 1, v)
        db.execute(
            "UPDATE categories SET centroid = ?, example_count = example_count + 1 WHERE id = ?",
            Vectors.toBlob(updated), categoryId,
        )
    }

    private fun loadPrototypes(): List<Proto> = db.query(
        "SELECT id, slug, centroid, example_count, is_private FROM categories WHERE archived_at IS NULL AND centroid IS NOT NULL AND centroid_model = ?",
        embedder.modelId,
    ) { Proto(it.long(0), it.string(1), Vectors.fromBlob(it.bytes(2)), it.long(3).toInt(), it.long(4) != 0L) }

    /** The description counts as one example; [count] is how many examples the mean already includes beyond it. */
    private fun runningMean(mean: FloatArray, count: Int, x: FloatArray): FloatArray {
        val w = 1.0f / (count + 1)
        return Vectors.normalize(FloatArray(mean.size) { mean[it] * (1 - w) + x[it] * w })
    }

    private fun stem(t: String): String = when {
        t.length > 4 && t.endsWith("ies") -> t.dropLast(3) + "y"
        t.length > 3 && t.endsWith("es") && !t.endsWith("ses") -> t.dropLast(1)
        t.length > 3 && t.endsWith("s") && !t.endsWith("ss") -> t.dropLast(1)
        else -> t
    }
}
