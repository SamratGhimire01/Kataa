package app.kataa.core

import app.kataa.core.embed.Embedder
import app.kataa.core.embed.Vectors
import app.kataa.core.model.NewMemory
import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Speed budget check with 10,000 memories and 384-d vectors (the size of the planned model).
 * Uses random vectors so the measurement is about storage/search cost, not model speed.
 * The phone budget is "typed question -> results in < 70 ms" including the embedding (~15-40 ms);
 * here we assert the storage part stays well under that on a CI machine.
 */
class BenchmarkTest {
    private class RandomEmbedder : Embedder {
        override val modelId = "bench-random"
        override val dimension = 384
        override fun embed(text: String): FloatArray {
            val r = Random(text.hashCode())
            return Vectors.normalize(FloatArray(dimension) { r.nextFloat() - 0.5f })
        }
    }

    @Test
    fun hybridSearchOver10kMemories() = newCore(embedder = RandomEmbedder()).use { c ->
        val words = listOf("keys", "wallet", "exam", "meeting", "milk", "doctor", "bank", "bus", "charger", "passport",
            "drawer", "office", "friday", "monday", "kitchen", "car", "ticket", "rent", "gift", "laptop")
        val r = Random(42)
        val insertNs = measureNanoTime {
            c.db.transaction {
                repeat(10_000) { i ->
                    val text = (0 until 8).joinToString(" ") { words[r.nextInt(words.size)] } + " note $i"
                    c.memories.save(NewMemory(text))
                }
            }
        }
        val indexNs = measureNanoTime { c.embeddings.indexPending(batchSize = 256) }

        val queries = (0 until 60).map { "where is my ${words[it % words.size]} ${words[(it * 7) % words.size]}" }
        queries.take(10).forEach { c.search.search(it) } // warm-up
        val times = queries.drop(10).map { q -> measureNanoTime { c.search.search(q) } / 1e6 }.sorted()
        val median = times[times.size / 2]
        val p95 = times[(times.size * 95) / 100]
        println("BENCH insert10k=%.0fms index10k=%.0fms search median=%.1fms p95=%.1fms".format(insertNs / 1e6, indexNs / 1e6, median, p95))
        assertTrue(median < 60.0, "median search $median ms exceeds budget")
    }
}
