package app.kataa.core.speech

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs Kataa's offline recognizer on a real recording (whisper.cpp's sample). SKIPs without the model. */
class WhisperTest {
    private val lib = System.getProperty("kataa.llmLib")?.takeIf { File(it).exists() }
    private val model = File("../ear/jni/arm64-v8a/libkataa_ear.so").takeIf { it.exists() }
    private val wav = File("../native/whisper/whisper.cpp/samples/jfk.wav").takeIf { it.exists() }

    @Test fun transcribesSpeech() {
        if (lib == null || model == null || wav == null) { println("SKIP whisper (no model/lib)"); return }
        val buf = ByteBuffer.allocateDirect(model.length().toInt()).apply { put(model.readBytes()); flip() }
        val t0 = System.nanoTime()
        val ear = Whisper.open(buf, 4, lib)!!
        println("EAR load %d ms".format((System.nanoTime() - t0) / 1_000_000))
        val pcm = readWav16k(wav)
        val t1 = System.nanoTime()
        val text = ear.transcribe(pcm)
        println("EAR %d ms for %.1f s audio -> %s".format((System.nanoTime() - t1) / 1_000_000, pcm.size / 16000.0, text))
        assertTrue(text!!.contains("ask not what your country can do for you", ignoreCase = true), text)
        assertTrue(ear.transcribe(FloatArray(16000)).let { it == null || it.length < 12 }, "silence should give nothing")
        ear.close()
    }

    private fun readWav16k(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= b.limit()) {
            val id = String(f.readBytes(), pos, 4); val size = b.getInt(pos + 4)
            if (id == "data") { val n = size / 2; return FloatArray(n) { b.getShort(pos + 8 + it * 2) / 32768f } }
            pos += 8 + size
        }
        error("no data chunk")
    }
}
