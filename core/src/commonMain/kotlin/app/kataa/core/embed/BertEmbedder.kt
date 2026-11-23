package app.kataa.core.embed

/**
 * Runs a BERT-style encoder. Platform code supplies this (ONNX Runtime on Android/JVM, Core ML or
 * ONNX Runtime on iOS); everything else (tokenizing, batching, pooling) is shared.
 */
interface EncoderRuntime : AutoCloseable {
    /**
     * [inputIds] and [attentionMask] are row-major [batch * seqLen]. Returns last_hidden_state as
     * row-major [batch * seqLen * hidden].
     */
    fun run(inputIds: LongArray, attentionMask: LongArray, batch: Int, seqLen: Int): FloatArray
}

enum class Pooling { CLS, MEAN }

/**
 * Sentence embedder on top of an [EncoderRuntime]. Defaults match bge-small-en-v1.5: CLS pooling,
 * L2 normalization, and an instruction prefix for short search queries.
 */
class BertEmbedder(
    private val tokenizer: WordPieceTokenizer,
    private val runtime: EncoderRuntime,
    override val modelId: String,
    override val dimension: Int = 384,
    private val pooling: Pooling = Pooling.CLS,
    private val queryPrefix: String = "",
    override val answerThreshold: Double = 0.55,
    override val categoryThreshold: Double = 0.30,
    /**
     * 1 for int8 models: dynamic quantization computes activation scales over the whole batch, so a
     * batched vector would depend on its neighbours. fp32 models can use larger batches.
     */
    private val maxBatch: Int = 1,
) : Embedder, AutoCloseable {

    override fun embed(text: String): FloatArray = embedBatch(listOf(text)).single()

    override fun embedQuery(text: String): FloatArray = embed(queryPrefix + text)

    override fun embedBatch(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        // Batch only texts with the same token count: no padding, so a vector never depends on what it
        // was batched with (int8 models compute activation scales over the whole batch).
        val encoded = texts.map(tokenizer::encode)
        val result = arrayOfNulls<FloatArray>(texts.size)
        val chunks = encoded.indices.groupBy { encoded[it].size }.values.flatMap { it.chunked(maxBatch) }
        for (chunk in chunks) {
            val seqLen = chunk.maxOf { encoded[it].size }
            val batch = chunk.size
            val ids = LongArray(batch * seqLen) { tokenizer.padId.toLong() }
            val mask = LongArray(batch * seqLen)
            chunk.forEachIndexed { b, idx ->
                val e = encoded[idx]
                for (t in e.indices) { ids[b * seqLen + t] = e[t].toLong(); mask[b * seqLen + t] = 1 }
            }
            val hidden = runtime.run(ids, mask, batch, seqLen)
            check(hidden.size == batch * seqLen * dimension) { "encoder returned ${hidden.size} values, expected ${batch * seqLen * dimension}" }
            chunk.forEachIndexed { b, idx -> result[idx] = Vectors.normalize(pool(hidden, b, seqLen, encoded[idx].size)) }
        }
        return result.map { it!! }
    }

    private fun pool(hidden: FloatArray, b: Int, seqLen: Int, len: Int): FloatArray {
        val base = b * seqLen * dimension
        return when (pooling) {
            Pooling.CLS -> hidden.copyOfRange(base, base + dimension)
            Pooling.MEAN -> FloatArray(dimension).also { v ->
                for (t in 0 until len) for (d in 0 until dimension) v[d] += hidden[base + t * dimension + d]
                for (d in 0 until dimension) v[d] /= len
            }
        }
    }

    override fun close() = runtime.close()

    companion object {
        const val BGE_QUERY_PREFIX = "Represent this sentence for searching relevant passages: "
    }
}
