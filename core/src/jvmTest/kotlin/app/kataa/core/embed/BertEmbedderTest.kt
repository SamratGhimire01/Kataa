package app.kataa.core.embed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks the Kotlin tokenizer and embedder against the reference Python (HuggingFace tokenizers + onnxruntime). */
class BertEmbedderTest {
    private val reference = Json.parseToJsonElement(javaClass.getResource("/bge_reference.json")!!.readText()).jsonArray.map { it.jsonObject }

    @Test fun tokenizerMatchesHuggingFace() {
        val dir = RealModel.dir ?: return println("SKIP model not downloaded")
        val tok = WordPieceTokenizer.fromVocabText(dir.resolve("vocab.txt").readText(), maxLength = 512)
        for (r in reference) {
            val text = r["text"]!!.jsonPrimitive.content
            val expected = r["ids"]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
            assertContentEquals(expected, tok.encode(text), text)
        }
    }

    @Test fun truncatesLongText() {
        val dir = RealModel.dir ?: return
        val tok = WordPieceTokenizer.fromVocabText(dir.resolve("vocab.txt").readText(), maxLength = 16)
        val ids = tok.encode("word ".repeat(100))
        assertEquals(16, ids.size)
        assertEquals(tok.sepId, ids.last())
    }

    @Test fun vectorsMatchReferenceAndBatchingDoesNotChangeThem() {
        val e = RealModel.embedder ?: return
        val texts = reference.map { it["text"]!!.jsonPrimitive.content }
        val batch = e.embedBatch(texts)
        reference.forEachIndexed { i, r ->
            val head = r["head"]!!.jsonArray.map { it.jsonPrimitive.float }
            val single = e.embed(texts[i])
            assertEquals(384, single.size)
            head.forEachIndexed { d, x ->
                assertTrue(abs(single[d] - x) < 2e-3, "dim $d of '${texts[i]}': ${single[d]} vs $x")
                assertTrue(abs(batch[i][d] - single[d]) < 2e-3, "batch vs single, dim $d of '${texts[i]}'")
            }
        }
    }

    @Test fun speed() {
        val e = RealModel.embedder ?: return
        val texts = List(200) { "Note number $it: my passport is in the blue suitcase under the bed" }
        e.embed("warm up")
        val t0 = System.nanoTime()
        e.embedBatch(texts)
        println("BENCH embed: ${"%.1f".format((System.nanoTime() - t0) / 1e6 / texts.size)} ms per memory")
    }

    @Test fun meaningBeatsWords() {
        val e = RealModel.embedder ?: return
        val q = e.embedQuery("where are my car keys")
        val right = e.embed("I left the car keys on the shoe rack")
        val wrong = e.embed("Buy a new car battery next month")
        assertTrue(Vectors.dot(q, right) > Vectors.dot(q, wrong) + 0.05)
    }
}
