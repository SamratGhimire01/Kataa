package app.kataa.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** "Add AI from files": the two downloaded pieces join into exactly Kataa's model, in either pick order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MindImportTest {
    private val ctx = org.robolectric.RuntimeEnvironment.getApplication() as Context

    private fun import(parts: List<File>): String? {
        val done = CountDownLatch(1); var result: String? = "not run"
        MindProvider(ctx) {}.importParts(parts.map { f -> f.name to { f.inputStream() } }) { result = it; done.countDown() }
        done.await(5, TimeUnit.MINUTES)
        return result
    }

    @Test fun joinsPiecesInNameOrder() {
        val model = File("../mind/jni/arm64-v8a/libkataa_mind.so")
        if (model.length() != MindProvider.MODEL_SIZE) { println("SKIP no model"); return }
        val dir = File(System.getProperty("java.io.tmpdir"), "kataa-pieces").apply { mkdirs() }
        val half = model.length() / 2
        val p1 = File(dir, "Kataa-AI-part1.bin"); val p2 = File(dir, "Kataa-AI-part2.bin")
        model.inputStream().use { inp ->
            p1.outputStream().use { out -> val b = ByteArray(1 shl 20); var left = half; while (left > 0) { val n = inp.read(b, 0, minOf(b.size.toLong(), left).toInt()); out.write(b, 0, n); left -= n } }
            p2.outputStream().use { out -> inp.copyTo(out, 1 shl 20) }
        }
        // Wrong: only one piece.
        assertNotNull(import(listOf(p1)))
        // Right, picked in reverse order.
        assertNull(import(listOf(p2, p1)))
        assertEquals(MindProvider.MODEL_SIZE, File(ctx.noBackupFilesDir, "kataa-mind.gguf").length())
        p1.delete(); p2.delete(); File(ctx.noBackupFilesDir, "kataa-mind.gguf").delete()
    }
}
