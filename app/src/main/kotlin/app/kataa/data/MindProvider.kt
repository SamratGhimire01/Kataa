package app.kataa.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import app.kataa.core.mind.LlamaModel
import app.kataa.core.mind.LlamaNative
import app.kataa.core.mind.Mind
import java.io.File
import java.util.concurrent.Executors

/**
 * Loads Kataa's on-device language model (the "mind", ~1 GB, shipped inside the APK) in the background and
 * hands it to the assistant. The fast rules work without it; the mind joins when it is ready, and is
 * freed when Android is short of memory, then reloaded the next time the app opens.
 */
class MindProvider(private val context: Context, private val onReady: (Mind?) -> Unit) {
    enum class Status { OFF, UNSUPPORTED, LOADING, READY, FAILED }

    @Volatile var status: Status = Status.OFF
        private set

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "kataa-mind").apply { priority = Thread.NORM_PRIORITY - 1 } }
    @Volatile private var model: LlamaModel? = null

    /** Why the mind can't run here, or null if it can. */
    fun unsupportedReason(): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        if (abi != "arm64-v8a") return "needs a 64-bit ARM phone"
        if (abi == "arm64-v8a" && !cpuHasDotProd()) return "this phone's processor is too old"
        if (totalRamGb() < 3.5) return "needs at least 4 GB of RAM"
        return null
    }

    fun load() {
        if (status == Status.LOADING || status == Status.READY) return
        if (unsupportedReason() != null) { status = Status.UNSUPPORTED; onReady(null); return }
        status = Status.LOADING
        thread.execute {
            val m = runCatching { open() }.onFailure { android.util.Log.e("Kataa", "mind failed to load", it) }.getOrNull()
            if (m == null) { status = Status.FAILED; onReady(null); return@execute }
            model = m
            val mind = Mind(m)
            runCatching { mind.warmUp() }
            status = Status.READY
            onReady(mind)
        }
    }

    /** Frees the ~1 GB (on low memory, or when the user turns Smart mode off). */
    fun unload() {
        if (status != Status.READY) return
        onReady(null)
        status = Status.OFF
        thread.execute { model?.close(); model = null }
    }

    private fun open(): LlamaModel? {
        LlamaNative.load()
        val threads = threads()
        // Repacked weights are 2-3x faster (tested on ARM); they use ~1 GB of RAM, which Android takes back via onTrimMemory.
        val fast = totalRamGb() >= 3.5
        // Installed as a file (older-style packaging): load it by path.
        val extracted = File(context.applicationInfo.nativeLibraryDir, MODEL_LIB)
        if (extracted.exists()) return LlamaModel.open(extracted.path, threads, CONTEXT, fast)
        // Normally it stays inside the APK, uncompressed and page aligned: memory-map it from there, no copy.
        val abi = Build.SUPPORTED_ABIS.first()
        context.assets.openNonAssetFd("lib/$abi/$MODEL_LIB").use { afd ->
            try {
                LlamaModel.openFd(afd.parcelFileDescriptor.fd, afd.startOffset, threads, CONTEXT, fast)?.let { return it }
            } catch (_: LlamaModel.MisalignedException) {
                // Falls through to a one-time copy (should not happen: native libraries are page aligned).
            }
            val copy = File(context.noBackupFilesDir, "kataa-mind.gguf")
            if (copy.length() != afd.length) {
                val tmp = File(copy.path + ".part")
                afd.createInputStream().use { inp -> tmp.outputStream().use { out -> inp.copyTo(out, 1 shl 20) } }
                tmp.renameTo(copy)
            }
            return LlamaModel.open(copy.path, threads, CONTEXT, fast)
        }
    }

    /** Big cores only: on an 8-core phone, 4 threads; never more than 4 (more makes it slower and hotter). */
    private fun threads(): Int {
        val n = Runtime.getRuntime().availableProcessors()
        return when { n >= 8 -> 4; n >= 6 -> 3; else -> 2 }
    }

    private fun totalRamGb(): Double {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem / 1e9
    }

    private fun cpuHasDotProd(): Boolean = runCatching {
        File("/proc/cpuinfo").readLines().any { it.startsWith("Features") && " asimddp" in it }
    }.getOrDefault(false)

    companion object {
        private const val MODEL_LIB = "libkataa_mind.so"
        private const val CONTEXT = 2048
    }
}
