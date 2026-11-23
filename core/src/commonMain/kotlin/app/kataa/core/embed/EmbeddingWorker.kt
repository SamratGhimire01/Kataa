package app.kataa.core.embed

import app.kataa.core.db.SqlDriver

/**
 * Embeds memories that need it (new, edited, restored, or embedded by an older model).
 * Runs in the background after saves; a memory is keyword-searchable immediately and
 * meaning-searchable once this has processed it.
 */
class EmbeddingWorker(private val db: SqlDriver, private val embedder: Embedder) {

    /** Processes up to [maxRows] pending memories in batches. Returns how many were embedded. */
    fun indexPending(maxRows: Int = Int.MAX_VALUE, batchSize: Int = 32): Int {
        var done = 0
        while (done < maxRows) {
            val batch = db.query(
                """
                SELECT id, body, extracted_text FROM memories
                WHERE embed_model IS NULL AND deleted_at IS NULL
                ORDER BY id LIMIT ?
                """.trimIndent(),
                minOf(batchSize, maxRows - done),
            ) { Pending(it.long(0), it.string(1), it.stringOrNull(2)) }
            if (batch.isEmpty()) break
            // Embedding is the slow part; do it outside the write transaction.
            val vectors = embedder.embedBatch(batch.map { textFor(it.body, it.extractedText) })
            db.transaction {
                batch.forEachIndexed { i, row ->
                    val v = vectors[i]
                    check(v.size == embedder.dimension) { "embedder returned ${v.size} dims, expected ${embedder.dimension}" }
                    // Only store if the text is unchanged since we read it (an edit in between re-queues the row anyway).
                    val marked = db.execute(
                        """
                        UPDATE memories SET embed_model = ?
                        WHERE id = ? AND body = ? AND extracted_text IS ? AND deleted_at IS NULL AND embed_model IS NULL
                        """.trimIndent(),
                        embedder.modelId, row.id, row.body, row.extractedText,
                    )
                    if (marked == 1) {
                        db.execute("DELETE FROM memories_vec WHERE memory_id = ?", row.id)
                        db.execute("INSERT INTO memories_vec(memory_id, embedding) VALUES (?, ?)", row.id, Vectors.toBlob(v))
                    }
                }
            }
            done += batch.size
        }
        return done
    }

    fun pendingCount(): Long =
        db.query("SELECT count(*) FROM memories WHERE embed_model IS NULL AND deleted_at IS NULL") { it.long(0) }.first()

    fun vectorFor(memoryId: Long): FloatArray? =
        db.query("SELECT embedding FROM memories_vec WHERE memory_id = ?", memoryId) { it.bytes(0) }
            .firstOrNull()?.let(Vectors::fromBlob)

    private class Pending(val id: Long, val body: String, val extractedText: String?)

    companion object {
        fun textFor(body: String, extractedText: String?): String =
            if (extractedText.isNullOrBlank()) body else "$body\n$extractedText"
    }
}
