package app.kataa.entry

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.kataa.core.nlu.Deadline
import app.kataa.core.nlu.DocumentDates
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.datetime.TimeZone
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reads text from a photo or screenshot on the phone (ML Kit, offline) and looks for a deadline, so
 * "share a photo of the electricity bill" becomes a searchable memory plus a reminder before it is due.
 * Must run off the main thread.
 */
object ImageText {
    data class Result(val summary: String, val fullText: String, val deadline: Deadline?)

    fun sharedImages(intent: Intent): List<Uri> {
        if (intent.type?.startsWith("image/") != true) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(stream(intent))
            Intent.ACTION_SEND_MULTIPLE -> streams(intent).take(10)
            else -> emptyList()
        }
    }

    fun read(context: Context, uri: Uri): Result? {
        val text = runCatching {
            val image = InputImage.fromFilePath(context, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try { Tasks.await(recognizer.process(image), 30, TimeUnit.SECONDS).text } finally { recognizer.close() }
        }.getOrNull()?.trim().orEmpty()
        if (text.length < 3) return null
        val deadline = DocumentDates.find(text, System.currentTimeMillis(), TimeZone.currentSystemDefault(), dayFirst = Locale.getDefault().country != "US")
        val title = text.lineSequence().map { it.trim() }.firstOrNull { it.count(Char::isLetter) >= 3 }?.take(80) ?: "Photo"
        val summary = if (deadline != null) "$title: ${DocumentDates.describe(deadline)}" else title
        return Result(summary, text, deadline)
    }

    private fun stream(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun streams(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
}
