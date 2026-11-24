package app.kataa.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import app.kataa.core.session.SpeechEngine
import app.kataa.core.session.SpeechError
import app.kataa.core.speech.Whisper
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Kataa's own speech recognizer: records from the mic and transcribes on the phone with Whisper (base.en,
 * shipped in the arm64 APK). Works with no internet and never shows Google's voice screen. Listens until
 * you pause (about a second of quiet), shows words as you speak, and knows Nepali place names.
 */
class WhisperSpeechEngine private constructor(private val context: Context) : SpeechEngine {
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "kataa-ear-load") }
    private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "kataa-ear") }
    @Volatile private var ear: Whisper? = null
    @Volatile private var session: Listening? = null

    /** Loads the model in the background (60 MB, memory-mapped from the APK); call when the app opens. */
    fun warmUp() { loader.execute { if (ear == null) ear = runCatching { open() }.getOrNull() } }

    override fun start(listener: SpeechEngine.Listener) {
        session?.cancel()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onError(SpeechError.NO_PERMISSION); return
        }
        warmUp()
        session = Listening(listener).also { Thread(it, "kataa-mic").start() }
    }

    override fun stop() { session?.finish() }

    override fun cancel() { session?.cancel(); session = null }

    private inner class Listening(private val listener: SpeechEngine.Listener) : Runnable {
        private val finishing = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val partialBusy = AtomicBoolean(false)

        fun finish() = finishing.set(true)
        fun cancel() { cancelled.set(true); finishing.set(true) }

        @SuppressLint("MissingPermission")
        override fun run() {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); listener.onError(SpeechError.OTHER); return }
            val audio = FloatArrayBuilder(RATE * MAX_SECONDS)
            val frame = ShortArray(RATE / 50) // 20 ms
            var noise = 0.0
            var frames = 0
            var speechStart = -1
            var lastVoice = 0
            var lastPartialAt = 0
            try {
                rec.startRecording()
                while (!finishing.get()) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) break
                    var sum = 0.0
                    for (i in 0 until n) { val v = frame[i] / 32768f; audio.add(v); sum += v * v }
                    val rms = sqrt(sum / n)
                    frames++
                    // Noise floor from the quietest moments; speech is clearly above it.
                    noise = when { frames == 1 -> rms; rms < noise -> noise * 0.7 + rms * 0.3; else -> noise * 1.003 }
                    val threshold = maxOf(noise * 3.0, 0.010)
                    listener.onLevel(((rms - noise) / 0.12).toFloat().coerceIn(0f, 1f))
                    val ms = frames * 20
                    if (rms > threshold) { if (speechStart < 0) speechStart = maxOf(0, audio.size - RATE / 3); lastVoice = ms }
                    if (speechStart < 0 && ms > NO_SPEECH_MS) { rec.stop(); listener.onError(SpeechError.NO_SPEECH); return }
                    if (speechStart >= 0 && ms - lastVoice > END_SILENCE_MS) break
                    if (ms >= MAX_SECONDS * 1000) break
                    // Words as you speak: re-read what was said so far, now and then, when the recognizer is free.
                    if (speechStart >= 0 && ms - lastPartialAt >= PARTIAL_EVERY_MS && audio.size - speechStart < RATE * 8 &&
                        ear != null && partialBusy.compareAndSet(false, true)
                    ) {
                        lastPartialAt = ms
                        val sofar = audio.copy(speechStart)
                        decoder.execute {
                            try {
                                if (!finishing.get()) ear?.transcribe(sofar)?.let { if (!finishing.get()) listener.onPartial(it) }
                            } finally { partialBusy.set(false) }
                        }
                    }
                }
                rec.stop()
            } catch (e: Exception) {
                listener.onError(SpeechError.OTHER); return
            } finally {
                rec.release()
            }
            if (cancelled.get()) return
            if (speechStart < 0) { listener.onError(SpeechError.NO_SPEECH); return }
            val said = audio.copy(speechStart)
            decoder.execute {
                // The model loads in well under a second; wait for it if this was the very first tap.
                var e = ear
                var waited = 0
                while (e == null && waited < 8000) { Thread.sleep(100); waited += 100; e = ear }
                val text = e?.transcribe(said)
                when {
                    cancelled.get() -> Unit
                    e == null -> listener.onError(SpeechError.UNAVAILABLE)
                    text.isNullOrBlank() -> listener.onError(SpeechError.NO_SPEECH)
                    else -> listener.onFinal(text)
                }
            }
        }
    }

    private class FloatArrayBuilder(cap: Int) {
        private var data = FloatArray(cap)
        var size = 0; private set
        fun add(v: Float) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        fun copy(from: Int): FloatArray = data.copyOfRange(from.coerceIn(0, size), size)
    }

    private fun open(): Whisper? {
        val abi = Build.SUPPORTED_ABIS.first()
        val threads = when { Runtime.getRuntime().availableProcessors() >= 8 -> 4; else -> 2 }
        context.assets.openNonAssetFd("lib/$abi/$MODEL_LIB").use { afd ->
            FileInputStream(afd.fileDescriptor).channel.use { ch ->
                val buf = ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
                return Whisper.open(buf, threads)
            }
        }
    }

    companion object {
        private const val RATE = 16_000
        private const val MAX_SECONDS = 20
        private const val NO_SPEECH_MS = 7_000
        private const val END_SILENCE_MS = 1_100
        private const val PARTIAL_EVERY_MS = 1_200
        const val MODEL_LIB = "libkataa_ear.so"

        /** Kataa's recognizer, or null on phones without it (32-bit builds): use Android's recognizer there. */
        fun createOrNull(context: Context): WhisperSpeechEngine? {
            val ctx = context.applicationContext
            if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return null
            val present = runCatching { ctx.assets.openNonAssetFd("lib/arm64-v8a/$MODEL_LIB").use { true } }.getOrDefault(false)
            return if (present) WhisperSpeechEngine(ctx) else null
        }
    }
}
