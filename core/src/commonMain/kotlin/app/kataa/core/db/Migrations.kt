package app.kataa.core.db

import app.kataa.core.categories.DefaultCategories
import app.kataa.core.embed.Embedder

/**
 * Brings a database up to [Schema.VERSION] and makes the vector table match the active embedder.
 * Safe to run on every open.
 */
object Migrations {
    fun migrate(db: SqlDriver, embedder: Embedder, nowMs: Long) {
        db.transaction {
            val version = db.queryLong("PRAGMA user_version") ?: 0L
            if (version > Schema.VERSION) {
                error("Database schema v$version is newer than this app (v${Schema.VERSION}). Update the app.")
            }
            if (version < 1) {
                // The vector table must exist before triggers that reference it can run.
                db.execute(Schema.createVectorTable(embedder.dimension))
                Schema.v1.forEach { db.execute(it) }
                DefaultCategories.seed(db, nowMs)
                setMeta(db, "embed_model", embedder.modelId)
                setMeta(db, "embed_dim", embedder.dimension.toString())
                db.execute("PRAGMA user_version = ${Schema.VERSION}")
            }
            // Future: if (version < 2) { ... }
            ensureEmbedderMatches(db, embedder)
        }
    }

    /**
     * If the embedding model changed, vectors from the old model are meaningless next to new ones:
     * drop them, recreate the table with the new dimension, and queue every live memory for re-embedding.
     */
    private fun ensureEmbedderMatches(db: SqlDriver, embedder: Embedder) {
        val model = getMeta(db, "embed_model")
        val dim = getMeta(db, "embed_dim")?.toIntOrNull()
        if (model == embedder.modelId && dim == embedder.dimension) return
        db.execute("DROP TABLE IF EXISTS memories_vec")
        db.execute(Schema.createVectorTable(embedder.dimension))
        db.execute("UPDATE memories SET embed_model = NULL, classified_at = NULL")
        db.execute("UPDATE categories SET centroid = NULL, centroid_model = NULL, example_count = 0")
        setMeta(db, "embed_model", embedder.modelId)
        setMeta(db, "embed_dim", embedder.dimension.toString())
    }

    fun getMeta(db: SqlDriver, key: String): String? =
        db.queryString("SELECT value FROM meta WHERE key = ?", key)

    fun setMeta(db: SqlDriver, key: String, value: String) {
        db.execute(
            "INSERT INTO meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            key, value,
        )
    }
}
