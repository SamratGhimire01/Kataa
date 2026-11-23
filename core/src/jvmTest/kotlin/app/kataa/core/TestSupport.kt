package app.kataa.core

import app.kataa.core.db.JdbcSqlDriver
import app.kataa.core.embed.Embedder
import app.kataa.core.embed.Vectors
import app.kataa.core.search.FtsQuery
import app.kataa.core.util.TimeSource
import java.io.File
import kotlin.math.abs

val SQLITE_VEC: String = System.getProperty("kataa.sqliteVec")
    ?.takeIf { File("$it.so").exists() || File("$it.dylib").exists() || File(it).exists() }
    ?: error("sqlite-vec extension not found. Run scripts/fetch-sqlite-vec.sh first.")

class FakeClock(var now: Long = 1_800_000_000_000L) : TimeSource {
    override fun nowMs() = now
    fun advanceDays(d: Double) { now += (d * 24 * 60 * 60 * 1000).toLong() }
}

/**
 * Deterministic stand-in for the real ONNX model (Phase 2): hashed bag of word stems plus a few
 * synonym groups, so tests can exercise "meaning" matches that share no keywords
 * ("exam" ~ "test", "cash" ~ "money"). Real-model quality is measured separately on the eval set.
 */
class TestEmbedder(override val dimension: Int = 384, override val modelId: String = "test-hash-v1") : Embedder {
    private val groups = listOf(
        setOf("exam", "test", "quiz", "midterm", "final", "study", "studying", "semester", "class", "lecture", "assignment", "homework", "course", "school", "college"),
        setOf("money", "cash", "bank", "payment", "pay", "rent", "bill", "loan", "budget", "salary", "finance"),
        setOf("meeting", "client", "office", "boss", "manager", "project", "deadline", "work", "job", "colleague"),
        setOf("doctor", "medicine", "pill", "tablet", "hospital", "health", "dentist", "clinic", "symptom"),
        setOf("key", "keys", "wallet", "passport", "charger", "glasses", "remote", "drawer", "shelf", "cupboard", "put", "left", "object", "physical"),
        setOf("password", "pin", "wifi", "code", "secret", "passcode"),
        setOf("buy", "grocery", "groceries", "milk", "eggs", "bread", "shopping", "mart", "supermarket"),
    )

    override fun embed(text: String): FloatArray {
        val v = FloatArray(dimension)
        val words = FtsQuery.tokens(text, maxTokens = 256)
        for (w in words) {
            val s = stem(w)
            v[bucket(s)] += 1f
            groups.forEachIndexed { gi, g -> if (s in g || w in g) v[bucket("#group$gi")] += 1.5f }
        }
        return Vectors.normalize(v)
    }

    private fun bucket(s: String) = abs(s.hashCode() % dimension)
    private fun stem(w: String) = if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w
}

fun newCore(
    clock: FakeClock = FakeClock(),
    embedder: Embedder = TestEmbedder(),
    path: String = ":memory:",
    /** The test embedder's similarities are lower than a real model's; 0.35 plays the role of the real threshold. */
    minAnswerSimilarity: Double = 0.35,
): KataaCore = KataaCore(JdbcSqlDriver(path, SQLITE_VEC), embedder, clock, minAnswerSimilarity = minAnswerSimilarity)
