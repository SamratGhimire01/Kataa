package app.kataa.core.embed

import kotlin.math.sqrt

/**
 * Turns text into a fixed-size vector whose cosine similarity reflects meaning.
 * Phase 2 provides the on-device ONNX implementation (bge-small-en-v1.5, 384-d).
 * Implementations must return L2-normalized vectors of length [dimension].
 */
interface Embedder {
    /** Stable id stored with every vector, e.g. "bge-small-en-v1.5@int8". Changing it triggers re-embedding. */
    val modelId: String
    val dimension: Int

    fun embed(text: String): FloatArray

    fun embedBatch(texts: List<String>): List<FloatArray> = texts.map(::embed)

    /**
     * Cosine similarity at which a meaning-only match counts as an answer. Every model has its own
     * similarity scale, so this is measured per model on the eval set (SearchQualityTest).
     */
    val answerThreshold: Double get() = 0.55

    /** Cosine similarity a memory needs to a category prototype to be auto-filed there. Also per model. */
    val categoryThreshold: Double get() = 0.30

    /** Embeds a search query. Some models (bge) want an instruction prefix for short queries. */
    fun embedQuery(text: String): FloatArray = embed(text)
}

object Vectors {
    /** float32 little-endian, the layout sqlite-vec expects for float[N] columns. */
    fun toBlob(v: FloatArray): ByteArray {
        val out = ByteArray(v.size * 4)
        for (i in v.indices) {
            val bits = v[i].toRawBits()
            out[i * 4] = bits.toByte()
            out[i * 4 + 1] = (bits shr 8).toByte()
            out[i * 4 + 2] = (bits shr 16).toByte()
            out[i * 4 + 3] = (bits shr 24).toByte()
        }
        return out
    }

    fun fromBlob(b: ByteArray): FloatArray {
        require(b.size % 4 == 0) { "vector blob size ${b.size} is not a multiple of 4" }
        return FloatArray(b.size / 4) { i ->
            val bits = (b[i * 4].toInt() and 0xff) or
                ((b[i * 4 + 1].toInt() and 0xff) shl 8) or
                ((b[i * 4 + 2].toInt() and 0xff) shl 16) or
                ((b[i * 4 + 3].toInt() and 0xff) shl 24)
            Float.fromBits(bits)
        }
    }

    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm == 0.0) return v.copyOf()
        return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }

    /** Cosine similarity of two L2-normalized vectors. */
    fun dot(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "dimension mismatch ${a.size} vs ${b.size}" }
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
