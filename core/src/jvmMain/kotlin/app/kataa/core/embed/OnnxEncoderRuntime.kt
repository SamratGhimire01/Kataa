package app.kataa.core.embed

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime encoder. The same `ai.onnxruntime` Java API ships in `onnxruntime-android`, so the
 * Android app reuses this class unchanged.
 */
class OnnxEncoderRuntime(modelBytes: ByteArray, threads: Int = 2) : EncoderRuntime {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelBytes,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(true)
        },
    )
    private val needsTokenTypes = "token_type_ids" in session.inputNames

    constructor(modelFile: File, threads: Int = 2) : this(modelFile.readBytes(), threads)

    override fun run(inputIds: LongArray, attentionMask: LongArray, batch: Int, seqLen: Int): FloatArray {
        val shape = longArrayOf(batch.toLong(), seqLen.toLong())
        val tensors = LinkedHashMap<String, OnnxTensor>()
        try {
            tensors["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape)
            tensors["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape)
            if (needsTokenTypes) tensors["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(inputIds.size)), shape)
            session.run(tensors).use { out ->
                val t = out.get(0) as OnnxTensor
                val buf: FloatBuffer = t.floatBuffer
                return FloatArray(buf.remaining()).also { buf.get(it) }
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    override fun close() = session.close()
}

object Models {
    /** bge-small-en-v1.5, int8-quantized ONNX (34 MB). See scripts/fetch-model.sh. */
    fun bgeSmall(dir: File, threads: Int = 2): BertEmbedder = BertEmbedder(
        tokenizer = WordPieceTokenizer.fromVocabText(File(dir, "vocab.txt").readText()),
        runtime = OnnxEncoderRuntime(File(dir, "model_quantized.onnx"), threads),
        modelId = "bge-small-en-v1.5@int8",
        dimension = 384,
        pooling = Pooling.CLS,
        queryPrefix = BertEmbedder.BGE_QUERY_PREFIX,
        // Eval (Nov 2026, 76 questions + 15 unanswerable): 0.62 answers 84% correctly and wrongly
        // answers 2 of 15 unanswerable questions; 0.65 answers 80% and wrongly answers none.
        // "Saved but never found" was the #2 user pain point, so we lean toward answering.
        answerThreshold = 0.62,
        // Correct categories scored 0.44-0.81 (keyword boost included); below 0.45 stays "Uncategorized".
        categoryThreshold = 0.45,
    )
}
