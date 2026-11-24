package app.kataa.core.mind

/** JNI bindings to libkataa_llm (native/llm/kataa_llm.cpp). Text crosses as UTF-8 bytes. */
object LlamaNative {
    @Volatile private var loaded = false

    /** Loads the library once. [path] is for the host build in tests; Android passes null. */
    @Synchronized
    fun load(path: String? = null) {
        if (loaded) return
        if (path != null) System.load(path) else System.loadLibrary("kataa_llm")
        loaded = true
    }

    @JvmStatic external fun loadPath(pathUtf8: ByteArray, threads: Int, nCtx: Int, fast: Boolean): Long
    /** Model inside a bigger file (an uncompressed APK asset). Returns -1 when it can't be mapped at that offset. */
    @JvmStatic external fun loadFd(fd: Int, offset: Long, threads: Int, nCtx: Int, fast: Boolean): Long
    @JvmStatic external fun complete(handle: Long, slot: Int, system: ByteArray, user: ByteArray, grammar: ByteArray?, maxTokens: Int): ByteArray?
    @JvmStatic external fun free(handle: Long)
    /** Recent llama.cpp warnings and errors (then cleared): why a load failed. */
    @JvmStatic external fun lastLog(): ByteArray?
}

/**
 * The llama.cpp model behind [Mind]. Thread-safe (the native side serializes calls). [close] frees the
 * model's memory; the object is unusable after that.
 */
class LlamaModel private constructor(@Volatile private var handle: Long) : LanguageModel, AutoCloseable {

    override fun complete(slot: Int, system: String, user: String, grammar: String?, maxTokens: Int): String? {
        val h = handle
        if (h == 0L) return null
        return LlamaNative.complete(h, slot, system.encodeToByteArray(), user.encodeToByteArray(), grammar?.encodeToByteArray(), maxTokens)
            ?.decodeToString()?.takeIf { it.isNotEmpty() }
    }

    @Synchronized
    override fun close() {
        val h = handle
        handle = 0
        if (h != 0L) LlamaNative.free(h)
    }

    companion object {
        /**
         * [fast] repacks weights for the CPU's fastest kernels (about 2x quicker, but the weights then live in
         * RAM instead of being paged from the file): use it only on phones with plenty of memory.
         */
        fun open(path: String, threads: Int, nCtx: Int = 2048, fast: Boolean = true): LlamaModel? =
            LlamaNative.loadPath(path.encodeToByteArray(), threads, nCtx, fast).takeIf { it > 0 }?.let(::LlamaModel)

        /** Returns null if loading failed, and throws [MisalignedException] if the caller should copy the file out first. */
        fun openFd(fd: Int, offset: Long, threads: Int, nCtx: Int = 2048, fast: Boolean = true): LlamaModel? {
            val h = LlamaNative.loadFd(fd, offset, threads, nCtx, fast)
            if (h == -1L) throw MisalignedException()
            return h.takeIf { it > 0 }?.let(::LlamaModel)
        }
    }

    class MisalignedException : Exception("model offset not mappable")
}
