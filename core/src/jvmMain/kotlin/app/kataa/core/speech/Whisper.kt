package app.kataa.core.speech

import app.kataa.core.mind.LlamaNative
import java.nio.ByteBuffer

/** JNI bindings to Kataa's offline speech recognizer (native/whisper, built into libkataa_llm). */
object WhisperNative {
    @JvmStatic external fun init(model: ByteBuffer, threads: Int): Long
    @JvmStatic external fun transcribe(handle: Long, pcm: FloatArray, prompt: ByteArray?): ByteArray?
    @JvmStatic external fun free(handle: Long)
}

/**
 * whisper.cpp speech-to-text, fully offline. [transcribe] takes 16 kHz mono audio and returns what was said.
 * Thread-safe (the native side serializes calls). Native handles can be negative on Android (tagged
 * pointers), so only 0 means failure.
 */
class Whisper private constructor(@Volatile private var handle: Long) : AutoCloseable {

    fun transcribe(pcm16k: FloatArray, prompt: String = VOCABULARY): String? {
        val h = handle
        if (h == 0L || pcm16k.isEmpty()) return null
        return WhisperNative.transcribe(h, pcm16k, prompt.encodeToByteArray())?.decodeToString()?.let(::clean)
    }

    @Synchronized
    override fun close() {
        val h = handle
        handle = 0
        if (h != 0L) WhisperNative.free(h)
    }

    companion object {
        /** [model] must be a direct buffer holding the ggml model file. [libPath] is for host tests. */
        fun open(model: ByteBuffer, threads: Int, libPath: String? = null): Whisper? {
            LlamaNative.load(libPath)
            require(model.isDirect) { "model must be a direct ByteBuffer" }
            return WhisperNative.init(model, threads).takeIf { it != 0L }?.let(::Whisper)
        }

        /**
         * Words Whisper should expect: how people talk to Kataa, and Nepali places and shops it would
         * otherwise mishear ("Bhatbhateni", not "but but any"). Whisper reads this as earlier speech.
         */
        const val VOCABULARY =
            "Remind me to call mom. Where are my keys? My wifi password is sunflower123. I'm at Big Mart. " +
                "Remind me to buy milk at Bhatbhateni. Salesberry, Bhat-Bhateni, Big Mart, Kathmandu, Lalitpur, Bhaktapur, " +
                "Pokhara, Baneshwor, Koteshwor, Thamel, Boudha, Chabahil, Kalanki, Maharajgunj, Baluwatar, Jawalakhel, " +
                "Pulchowk, Lazimpat, Patan, momo, dal bhat, khaja, didi, dai, bhai, aama, buwa, ATM, eSewa, Khalti."

        private val NOISE = Regex("\\[(?:BLANK_AUDIO|MUSIC|NOISE|SOUND|inaudible)[^\\]]*]|\\((?:[^)]*(?:music|noise|silence|inaudible)[^)]*)\\)", RegexOption.IGNORE_CASE)

        /** Drops "[BLANK_AUDIO]"-style tags and whitespace; null when nothing was said. */
        fun clean(text: String): String? = text.replace(NOISE, " ").replace(Regex("\\s+"), " ").trim().ifEmpty { null }
    }
}
