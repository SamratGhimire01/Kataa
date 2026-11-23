package app.kataa.core.search

import app.kataa.core.db.SqlDriver
import app.kataa.core.embed.Embedder
import app.kataa.core.embed.Vectors
import app.kataa.core.memory.MemoryRepository
import app.kataa.core.model.Memory
import app.kataa.core.util.DAY_MS
import app.kataa.core.util.TimeSource
import kotlin.math.exp

data class SearchOptions(
    val limit: Int = 10,
    /** Candidates taken from each of keyword and vector search before fusion. */
    val candidates: Int = 50,
    /** Only memories in this category. */
    val categoryId: Long? = null,
    /** Memories in private categories are hidden unless the app has unlocked them (biometric). */
    val includePrivate: Boolean = false,
    val createdAfter: Long? = null,
    val createdBefore: Long? = null,
    /** Older memories replaced by a newer one about the same subject are kept but ranked lower. */
    val supersededPenalty: Double = 0.4,
)

data class SearchHit(
    val memory: Memory,
    val score: Double,
    /** 1-based rank in keyword results, null if not matched by keywords. */
    val keywordRank: Int?,
    /** Cosine similarity to the query (1 = identical meaning), null if not in vector results. */
    val similarity: Double?,
    val superseded: Boolean,
)

data class SearchResult(
    val hits: List<SearchHit>,
    /**
     * False means "I don't know": nothing matched by keyword and nothing was semantically close enough.
     * The UI should say so (and offer to save) instead of showing a weak guess as an answer.
     */
    val confident: Boolean,
)

/**
 * Keyword (FTS5/BM25) + meaning (sqlite-vec cosine) search, fused with Reciprocal Rank Fusion,
 * then adjusted for recency, pinning and "latest wins".
 */
class HybridSearch(
    private val db: SqlDriver,
    private val memories: MemoryRepository,
    private val embedder: Embedder?,
    private val clock: TimeSource,
    /** Minimum cosine similarity for a vector-only match to count as an answer. Tuned per model in Phase 2. */
    private val minSimilarity: Double = 0.55,
) {
    fun search(query: String, options: SearchOptions = SearchOptions()): SearchResult {
        val fts = keywordCandidates(query, options)
        val vec = vectorCandidates(query, options)
        if (fts.isEmpty() && vec.isEmpty()) return SearchResult(emptyList(), confident = false)

        // Reciprocal Rank Fusion: score = sum over lists of 1 / (k + rank). Scale-free, so BM25 and cosine can be combined.
        val fused = HashMap<Long, Double>()
        fts.forEachIndexed { i, id -> fused[id] = (fused[id] ?: 0.0) + 1.0 / (RRF_K + i + 1) }
        vec.forEachIndexed { i, (id, _) -> fused[id] = (fused[id] ?: 0.0) + 1.0 / (RRF_K + i + 1) }

        val byId = memories.getMany(fused.keys)
        val hidden = if (options.includePrivate) emptySet() else privateIds(fused.keys)
        val superseded = memories.supersededIds(fused.keys)
        val ftsRank = fts.withIndex().associate { (i, id) -> id to i + 1 }
        val sim = vec.associate { (id, d) -> id to 1.0 - d }
        val now = clock.nowMs()

        val hits = fused.mapNotNull { (id, base) ->
            val m = byId[id] ?: return@mapNotNull null
            if (m.deletedAt != null || id in hidden) return@mapNotNull null
            if (options.createdAfter != null && m.createdAt < options.createdAfter) return@mapNotNull null
            if (options.createdBefore != null && m.createdAt >= options.createdBefore) return@mapNotNull null
            val ageDays = ((now - m.createdAt).coerceAtLeast(0)).toDouble() / DAY_MS
            var score = base * (1.0 + RECENCY_WEIGHT * exp(-ageDays / RECENCY_DAYS))
            if (m.pinned) score *= PIN_BOOST
            val isSuperseded = id in superseded
            if (isSuperseded) score *= options.supersededPenalty
            SearchHit(m, score, ftsRank[id], sim[id], isSuperseded)
        }.sortedWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.memory.createdAt })
            .take(options.limit)

        // A keyword hit only counts as an answer when it covers most of what was asked: "aadhar number"
        // must not be answered by "the plumber's number" just because "number" matched.
        val queryTokens = FtsQuery.tokens(query).filter { it !in QUESTION_FILLER }
        val confident = hits.any { h ->
            (h.similarity ?: 0.0) >= minSimilarity ||
                (h.keywordRank != null && keywordCoverage(queryTokens, h.memory) >= MIN_KEYWORD_COVERAGE)
        }
        return SearchResult(hits, confident)
    }

    /** Share of the query's content words found in the memory (prefix match, so "keys" ~ "key", "parked" ~ "park"). */
    private fun keywordCoverage(queryTokens: List<String>, m: Memory): Double {
        if (queryTokens.isEmpty()) return 0.0
        val words = FtsQuery.tokens(m.body + " " + (m.extractedText ?: ""), maxTokens = 512)
        val found = queryTokens.count { q -> words.any { w -> sameStem(q, w) } }
        return found.toDouble() / queryTokens.size
    }

    private fun sameStem(a: String, b: String): Boolean {
        if (a == b) return true
        val n = minOf(a.length, b.length)
        if (n < 3) return false
        val stem = maxOf(3, minOf(a.length, b.length, maxOf(a.length, b.length) - 3))
        return a.length >= stem && b.length >= stem && a.regionMatches(0, b, 0, stem)
    }

    private fun keywordCandidates(query: String, o: SearchOptions): List<Long> {
        val match = FtsQuery.build(query) ?: return emptyList()
        return if (o.categoryId == null) {
            db.query(
                "SELECT rowid FROM memories_fts WHERE memories_fts MATCH ? ORDER BY bm25(memories_fts, 1.0, 0.6) LIMIT ?",
                match, o.candidates,
            ) { it.long(0) }
        } else {
            db.query(
                """
                SELECT rowid FROM memories_fts
                WHERE memories_fts MATCH ?
                  AND rowid IN (SELECT memory_id FROM memory_categories_active WHERE category_id = ?)
                ORDER BY bm25(memories_fts, 1.0, 0.6) LIMIT ?
                """.trimIndent(),
                match, o.categoryId, o.candidates,
            ) { it.long(0) }
        }
    }

    /** (memoryId, cosine distance) nearest first. */
    private fun vectorCandidates(query: String, o: SearchOptions): List<Pair<Long, Double>> {
        val e = embedder ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val q = Vectors.toBlob(e.embedQuery(query))
        // The category constraint must be inside the KNN query: sqlite-vec applies "memory_id IN (...)"
        // before choosing the k nearest. Filtering afterwards can return nothing.
        return if (o.categoryId == null) {
            db.query(
                "SELECT memory_id, distance FROM memories_vec WHERE embedding MATCH ? AND k = ? ORDER BY distance",
                q, o.candidates,
            ) { it.long(0) to it.double(1) }
        } else {
            db.query(
                """
                SELECT memory_id, distance FROM memories_vec
                WHERE embedding MATCH ? AND k = ?
                  AND memory_id IN (SELECT memory_id FROM memory_categories_active WHERE category_id = ?)
                ORDER BY distance
                """.trimIndent(),
                q, o.candidates, o.categoryId,
            ) { it.long(0) to it.double(1) }
        }
    }

    private fun privateIds(ids: Set<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val out = HashSet<Long>()
        ids.chunked(500).forEach { chunk ->
            val ph = chunk.joinToString(",") { "?" }
            db.query(
                """
                SELECT DISTINCT a.memory_id FROM memory_categories_active a
                JOIN categories c ON c.id = a.category_id
                WHERE c.is_private = 1 AND a.memory_id IN ($ph)
                """.trimIndent(),
                *chunk.toTypedArray(),
            ) { it.long(0) }.forEach(out::add)
        }
        return out
    }

    companion object {
        private const val MIN_KEYWORD_COVERAGE = 0.6
        /** Words that shape a question but say nothing about the answer. */
        private val QUESTION_FILLER = setOf("put", "keep", "kept", "left", "leave", "much", "many", "name", "time", "day", "date", "last", "next", "get", "got")

        const val RRF_K = 60.0
        const val RECENCY_WEIGHT = 0.15
        const val RECENCY_DAYS = 30.0
        const val PIN_BOOST = 1.1
    }
}
