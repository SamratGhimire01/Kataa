package app.kataa.core

import app.kataa.core.db.queryLong
import app.kataa.core.model.MemoryEdit
import app.kataa.core.model.NewMemory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The storage invariants that the original v1 spec broke. */
class SchemaTest {
    private fun KataaCore.ftsIds(match: String) =
        db.query("SELECT rowid FROM memories_fts WHERE memories_fts MATCH ? ORDER BY rowid", match) { it.long(0) }

    /** Compares the FTS index against its content view; throws if they disagree. */
    private fun KataaCore.assertFtsConsistent() {
        db.execute("INSERT INTO memories_fts(memories_fts, rank) VALUES('integrity-check', 1)")
    }

    private fun KataaCore.vecCount(id: Long) = db.queryLong("SELECT count(*) FROM memories_vec WHERE memory_id = ?", id)

    @Test
    fun editTrashRestoreDeleteKeepIndexesConsistent() = newCore().use { c ->
        val keys = c.memories.save(NewMemory("My keys are on the kitchen counter", subject = "keys"))
        val passport = c.memories.save(NewMemory("Passport is in the blue drawer"))
        c.processPending()
        c.assertFtsConsistent()
        assertEquals(listOf(keys.id), c.ftsIds("counter"))

        // Edit: old words gone, new words found, vector invalidated and re-queued.
        c.memories.update(keys.id, MemoryEdit(body = "My keys are in the blue drawer"))
        c.assertFtsConsistent()
        assertEquals(emptyList(), c.ftsIds("counter"))
        assertEquals(listOf(keys.id, passport.id), c.ftsIds("drawer"))
        assertEquals(0, c.vecCount(keys.id))
        assertEquals(1, c.embeddings.pendingCount())

        // Trash: gone from keyword and vector search.
        c.processPending()
        c.memories.trash(passport.id)
        c.assertFtsConsistent()
        assertEquals(emptyList(), c.ftsIds("passport"))
        assertEquals(0, c.vecCount(passport.id))

        // Restore: back in keyword search now, vector search after processing.
        c.memories.restore(passport.id)
        c.assertFtsConsistent()
        assertEquals(listOf(passport.id), c.ftsIds("passport"))
        c.processPending()
        assertEquals(1, c.vecCount(passport.id))

        // Hard delete of a trashed row and of a live row.
        c.memories.trash(passport.id)
        c.memories.deleteForever(passport.id)
        c.memories.deleteForever(keys.id)
        c.assertFtsConsistent()
        assertEquals(0, c.vecCount(keys.id))
        assertEquals(0L, c.db.queryLong("SELECT count(*) FROM memories_fts"))
    }

    @Test
    fun rebuildDoesNotResurrectTrash() = newCore().use { c ->
        val m = c.memories.save(NewMemory("secret garden plans"))
        c.memories.trash(m.id)
        c.db.execute("INSERT INTO memories_fts(memories_fts) VALUES('rebuild')")
        assertEquals(emptyList(), c.ftsIds("garden"))
    }

    @Test
    fun rawSpeechNeverBreaksSearch() = newCore().use { c ->
        c.memories.save(NewMemory("keys in the drawer"))
        listOf("where's my keys?", "\"keys", "keys AND NOT", "NEAR(keys drawer)", "", "???", "a the is", "keys*", "-keys").forEach {
            c.search.search(it) // must not throw
        }
        assertTrue(c.search.search("where's my keys?").hits.isNotEmpty())
    }

    @Test
    fun vacuumKeepsIdsStable() {
        val dir = Files.createTempDirectory("kataa")
        val path = dir.resolve("t.db").toString()
        newCore(path = path).use { c ->
            val ids = (1..50).map { c.memories.save(NewMemory("note $it about topic$it")).id }
            ids.filterIndexed { i, _ -> i % 2 == 0 }.forEach { c.memories.deleteForever(it) }
            c.db.execute("VACUUM")
            c.assertFtsConsistent()
            assertEquals(listOf(ids[1]), c.ftsIds("topic2"))
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun reopeningIsIdempotentAndModelChangeRequeuesVectors() {
        val dir = Files.createTempDirectory("kataa")
        val path = dir.resolve("t.db").toString()
        newCore(path = path).use { c ->
            c.memories.save(NewMemory("hello world"))
            c.processPending()
        }
        newCore(path = path).use { c -> assertEquals(0, c.embeddings.pendingCount()) }
        newCore(path = path, embedder = TestEmbedder(dimension = 256, modelId = "other-model")).use { c ->
            assertEquals(1, c.embeddings.pendingCount())
            c.processPending()
            assertEquals(0, c.embeddings.pendingCount())
            assertTrue(c.search.search("hello").confident)
        }
        dir.toFile().deleteRecursively()
    }
}
