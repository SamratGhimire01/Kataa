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
    enum class Status { OFF, UNSUPPORTED, MISSING, LOADING, READY, FAILED }

    @Volatile var status: Status = Status.OFF
        private set

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "kataa-mind").apply { priority = Thread.NORM_PRIORITY - 1 } }
    @Volatile private var model: LlamaModel? = null

    /** Why the last load failed, in a few words for Settings (null when it didn't fail). */
    @Volatile var failure: String? = null
        private set

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
        if (!installed()) { status = Status.MISSING; onReady(null); return }
        status = Status.LOADING
        thread.execute {
            failure = null
            val m = runCatching { open() }.onFailure {
                android.util.Log.e("Kataa", "mind failed to load", it)
                failure = "${it.javaClass.simpleName}: ${it.message}"
            }.getOrNull()
            if (m == null) {
                if (failure == null) failure = nativeReason()
                status = Status.FAILED; onReady(null); return@execute
            }
            model = m
            val mind = Mind(m)
            runCatching { mind.warmUp() }
            status = Status.READY
            onReady(mind)
        }
    }

    /** The AI is either inside the APK (the big build) or was added from files (the small build). */
    fun installed(): Boolean = imported.length() == MODEL_SIZE || File(context.applicationInfo.nativeLibraryDir, MODEL_LIB).exists() ||
        runCatching { context.assets.openNonAssetFd("lib/${Build.SUPPORTED_ABIS.first()}/$MODEL_LIB").use { true } }.getOrDefault(false)

    private val imported get() = File(context.noBackupFilesDir, "kataa-mind.gguf")

    /**
     * Adds the AI from the downloaded piece files (Kataa-AI-part1.bin, part2.bin, in name order). Joins them,
     * checks the result is exactly Kataa's model (size and SHA-256), then loads it. Runs on the mind thread;
     * [done] gets null on success or a short reason.
     */
    fun importParts(parts: List<Pair<String, () -> java.io.InputStream>>, done: (String?) -> Unit) {
        thread.execute {
            val tmp = File(context.noBackupFilesDir, "kataa-mind.gguf.part")
            val error = runCatching {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                tmp.outputStream().buffered(1 shl 20).use { out ->
                    for ((_, open) in parts.sortedBy { it.first.lowercase() }) open().use { inp ->
                        val buf = ByteArray(1 shl 20)
                        while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
                    }
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                when {
                    tmp.length() != MODEL_SIZE -> "Those files are not the whole AI. Pick both pieces (part1 and part2)."
                    sha != MODEL_SHA256 -> "Those files don't match Kataa's AI. Download both pieces again."
                    else -> { imported.delete(); if (tmp.renameTo(imported)) null else "Couldn't save the AI on this phone." }
                }
            }.getOrElse { "Couldn't read the files: ${it.message}" }
            tmp.delete()
            if (error == null) { status = Status.OFF; load() }
            done(error)
        }
    }

    /** Frees the ~1 GB (on low memory, or when the user turns Smart mode off). */
    fun unload() {
        if (status == Status.FAILED || status == Status.MISSING) { status = Status.OFF; return }
        if (status != Status.READY) return
        onReady(null)
        status = Status.OFF
        thread.execute { model?.close(); model = null }
    }

    private fun open(): LlamaModel? {
        LlamaNative.load()
        val threads = threads()
        val fastNow = totalRamGb() >= 3.5
        // Added from files (the small build): load it by path. Repacked weights need ~1 GB of extra RAM;
        // if the phone can't spare it, load it plain (memory-mapped, slower but light).
        if (imported.length() == MODEL_SIZE) {
            if (fastNow) LlamaModel.open(imported.path, threads, CONTEXT, true)?.let { return it }
            return LlamaModel.open(imported.path, threads, CONTEXT, false)
        }
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
    /** The last error lines from llama.cpp, plus the phone's RAM, for the Settings line. */
    private fun nativeReason(): String {
        val log = runCatching { LlamaNative.lastLog()?.decodeToString() }.getOrNull().orEmpty()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(3).joinToString(" | ")
        return "%.1f GB RAM. %s".format(totalRamGb(), log.ifEmpty { "no details" }).take(400)
    }

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
        /** scripts/fetch-llm-model.sh builds exactly this file. */
        const val MODEL_SIZE = 975_065_632L
        const val MODEL_SHA256 = "e15d020b0cfd1f9d59e679cb9f3fa221ffad79f7b359a13146deaa8d7ddb9a13"
    }
}
