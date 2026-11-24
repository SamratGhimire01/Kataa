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
    val worker = Executors.newSingleThreadExecutor { r ->
        Thread({
            // Keep the single core thread alive whatever one task throws.
            try { r.run() } catch (t: Throwable) { android.util.Log.e("Kataa", "core task failed", t) }
        }, "kataa-core").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /** True when the old database could not be opened and a fresh one was started (offer "Restore backup"). */
    @Volatile var startedFresh = false
        private set

    val core: KataaCore by lazy {
        val driver = openDatabase()
        val vocab = context.assets.open("$MODEL/vocab.txt").bufferedReader().use { it.readText() }
        KataaCore(driver, Models.bgeSmall(mapModel(), vocab, threads = inferenceThreads()))
    }

    /**
     * Opens the encrypted database. If it can't be opened (the Keystore key was wiped, e.g. after a
     * factory-reset restore, or the file is damaged) the old file is kept aside, not deleted, and a fresh
     * database starts so the app still works; the user can then restore an encrypted backup.
     */
    private fun openDatabase(): NativeSqlDriver {
        val file = context.getDatabasePath("kataa.db").also { it.parentFile?.mkdirs() }
        return try {
            open(file)
        } catch (e: Exception) {
            if (!file.exists()) throw e
            val stamp = System.currentTimeMillis()
            for (suffix in listOf("", "-wal", "-shm")) {
                java.io.File(file.path + suffix).takeIf { it.exists() }?.renameTo(java.io.File("${file.path}.unreadable-$stamp$suffix"))
            }
            DatabaseKey.reset(context)
            startedFresh = true
            open(file)
        }
    }

    private fun open(file: java.io.File): NativeSqlDriver {
        val key = DatabaseKey.get(context)
        try {
            return NativeSqlDriver(file.path, key)
        } finally {
            key.fill(0)
        }
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
