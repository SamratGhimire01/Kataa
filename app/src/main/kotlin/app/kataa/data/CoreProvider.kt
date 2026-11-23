package app.kataa.data

import android.app.ActivityManager
import android.content.Context
import app.kataa.core.KataaCore
import app.kataa.core.db.NativeSqlDriver
import app.kataa.core.embed.Models
import app.kataa.security.DatabaseKey
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/**
 * Opens Kataa's core once, off the main thread: encrypted database + on-device search model.
 * Everything touching the core runs on [worker] (one thread, one connection), so the UI never blocks.
 */
class CoreProvider(private val context: Context) {
    val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kataa-core").apply { priority = Thread.NORM_PRIORITY - 1 } }

    val core: KataaCore by lazy {
        val key = DatabaseKey.get(context)
        val driver = try {
            NativeSqlDriver(context.getDatabasePath("kataa.db").also { it.parentFile?.mkdirs() }.path, key)
        } finally {
            key.fill(0)
        }
        val vocab = context.assets.open("$MODEL/vocab.txt").bufferedReader().use { it.readText() }
        KataaCore(driver, Models.bgeSmall(mapModel(), vocab, threads = inferenceThreads()))
    }

    /**
     * Memory-maps the model straight out of the APK (stored uncompressed): no copy on disk, and the
     * 34 MB never touches the Java heap.
     */
    private fun mapModel(): MappedByteBuffer =
        context.assets.openFd("$MODEL/model_quantized.onnx").use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        }

    /** 1 thread on low-RAM phones (keeps the UI smooth), 2 otherwise. */
    private fun inferenceThreads(): Int {
        val am = context.getSystemService(ActivityManager::class.java)
        return if (am.isLowRamDevice || Runtime.getRuntime().availableProcessors() <= 4) 1 else 2
    }

    private companion object {
        const val MODEL = "bge-small-en-v1.5"
    }
}
