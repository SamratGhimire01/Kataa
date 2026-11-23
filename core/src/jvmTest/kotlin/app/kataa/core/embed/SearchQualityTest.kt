package app.kataa.core.embed

import app.kataa.core.newCore
import app.kataa.core.search.SearchOptions
import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs [SearchEval] through the full hybrid search with the real model. Prints EVAL lines. */
class SearchQualityTest {
    @Test fun realModelSearchQuality() {
        val e = RealModel.embedder ?: return println("SKIP model not downloaded")
        val core = newCore(embedder = e, minAnswerSimilarity = e.answerThreshold)
        val ids = SearchEval.memories.map { core.memories.save(app.kataa.core.model.NewMemory(it)).id }
        core.processPending()

        var top1 = 0; var top3 = 0; var mrr = 0.0
        val answerSims = ArrayList<Double>(); val misses = ArrayList<String>()
        for ((q, idx) in SearchEval.queries) {
            val hits = core.search.search(q, SearchOptions(limit = 5, includePrivate = true)).hits
            val rank = hits.indexOfFirst { it.memory.id == ids[idx] }
            if (rank == 0) top1++
            if (rank in 0..2) top3++
            if (rank >= 0) mrr += 1.0 / (rank + 1)
            if (rank != 0) misses += "  '$q' -> rank $rank, got '${hits.firstOrNull()?.memory?.body}'"
            hits.getOrNull(rank.coerceAtLeast(0))?.similarity?.let { if (rank >= 0) answerSims += it }
        }
        val noneSims = SearchEval.unanswerable.map { q ->
            core.search.search(q, SearchOptions(limit = 5, includePrivate = true)).hits.maxOfOrNull { it.similarity ?: 0.0 } ?: 0.0
        }
        val n = SearchEval.queries.size
        println("EVAL queries=$n top1=${"%.2f".format(top1.toDouble() / n)} top3=${"%.2f".format(top3.toDouble() / n)} mrr=${"%.3f".format(mrr / n)}")
        println("EVAL answer-similarity p10=${pct(answerSims, 10)} p25=${pct(answerSims, 25)} median=${pct(answerSims, 50)}")
        println("EVAL no-answer top-similarity median=${pct(noneSims, 50)} p90=${pct(noneSims, 90)} max=${pct(noneSims, 100)}")
        for (t in listOf(0.55, 0.60, 0.62, 0.65, 0.68, 0.70, 0.72, 0.75)) {
            val keep = answerSims.count { it >= t }.toDouble() / answerSims.size
            val reject = noneSims.count { it < t }.toDouble() / noneSims.size
            println("EVAL threshold=$t answers-kept=${"%.2f".format(keep)} unanswerable-rejected=${"%.2f".format(reject)}")
        }
        misses.forEach(::println)
        val answered = SearchEval.queries.count { (q, idx) ->
            val r = core.search.search(q, SearchOptions(includePrivate = true)); r.confident && r.hits.first().memory.id == ids[idx]
        }
        val wronglyAnswered = SearchEval.unanswerable.count { core.search.search(it, SearchOptions(includePrivate = true)).confident }
        println("EVAL at threshold ${e.answerThreshold}: answered correctly $answered/$n, unanswerable answered anyway $wronglyAnswered/${SearchEval.unanswerable.size}")
        assertTrue(top3.toDouble() / n >= 0.90, "top-3 recall dropped")
        assertTrue(answered.toDouble() / n >= 0.80, "too many 'I don't know' for answerable questions")
        assertTrue(wronglyAnswered <= 3, "answers questions it has no memory for")
    }

    @Test fun realModelCategories() {
        val e = RealModel.embedder ?: return
        val core = newCore(embedder = e)
        val ids = SearchEval.memories.map { core.memories.save(app.kataa.core.model.NewMemory(it)).id }
        core.processPending()
        var ok = 0
        ids.forEachIndexed { i, id ->
            val got = core.categories.assignmentsFor(id).map { it.slug }
            if (got.any { it in SearchEval.categories[i] }) ok++
            else println("  category miss: '${SearchEval.memories[i]}' -> $got (want ${SearchEval.categories[i]})")
        }
        println("EVAL categories: $ok/${ids.size} acceptable")
        assertTrue(ok >= 34, "category accuracy dropped")
        // The safety rule holds with the real model too.
        listOf(3, 13, 31).forEach { assertTrue("private" in core.categories.assignmentsFor(ids[it]).map { a -> a.slug }) }
    }

    private fun pct(xs: List<Double>, p: Int): String {
        if (xs.isEmpty()) return "-"
        val s = xs.sorted()
        return "%.3f".format(s[((p / 100.0) * (s.size - 1)).toInt()])
    }
}
