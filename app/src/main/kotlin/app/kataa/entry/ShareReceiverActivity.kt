package app.kataa.entry

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.kataa.KataaApplication
import app.kataa.core.model.NewMemory
import app.kataa.core.model.Source
import app.kataa.triggers.Triggers

/**
 * "Share to Kataa" from any app: links, notes, addresses, messages are saved as memories (searchable by
 * meaning, auto-categorised). Images go through text recognition (see [ImageText]). No UI beyond a toast.
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as KataaApplication
        val text = sharedText(intent)
        val images = ImageText.sharedImages(intent)
        if (text.isNullOrBlank() && images.isEmpty()) { finish(); return }
        app.cores.worker.execute {
            val core = app.cores.core
            var saved = 0
            if (!text.isNullOrBlank()) {
                core.memories.save(NewMemory(text.trim().take(MAX_CHARS), source = Source.SHARE, sourceApp = referrer?.host))
                saved++
            }
            for (uri in images) {
                val r = ImageText.read(this, uri) ?: continue
                core.memories.save(NewMemory(r.summary, source = Source.SCREENSHOT, sourceApp = referrer?.host, extractedText = r.fullText.take(MAX_CHARS)))
                    .also { m -> r.deadline?.let { core.reminders.addTime(m.id, it.remindAtMs, java.util.TimeZone.getDefault().id) } }
                saved++
            }
            core.processPending()
            Triggers.refreshAlarm(this)
            runOnUiThread { Toast.makeText(this, if (saved > 0) "Saved to Kataa" else "Nothing to save", Toast.LENGTH_SHORT).show() }
        }
        finish()
    }

    private fun sharedText(i: Intent): String? {
        if (i.action != Intent.ACTION_SEND) return null
        val subject = i.getStringExtra(Intent.EXTRA_SUBJECT)
        val body = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        return listOfNotNull(subject?.takeIf { it.isNotBlank() && body?.contains(it) != true }, body).joinToString("\n").ifBlank { null }
    }

    private companion object {
        const val MAX_CHARS = 20_000
    }
}
