package app.kataa

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.kataa.core.assistant.AssistantContext
import app.kataa.core.assistant.Reply
import kotlinx.datetime.TimeZone

/**
 * Temporary developer console to try the backend on a real phone: type what you would say, see what
 * Kataa does. The real interface (orb, animation, design) is the frontend phase and replaces this.
 */
class DevConsoleActivity : Activity() {
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; setPadding(pad, pad, pad, pad); text = "Kataa developer console\n" }
        val input = EditText(this).apply {
            hint = "Say something, e.g. my keys are in the drawer"
            imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
            setOnEditorActionListener { v, _, _ ->
                val said = v.text.toString().trim()
                if (said.isNotEmpty()) { v.text = ""; handle(said) }
                true
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(input)
            addView(ScrollView(context).apply { addView(log) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }

    private fun handle(said: String) {
        val app = application as KataaApplication
        app.cores.worker.execute {
            val t0 = System.nanoTime()
            val line = try {
                val core = app.cores.core
                val reply = core.assistant.handle(said, AssistantContext(System.currentTimeMillis(), TimeZone.currentSystemDefault()))
                core.processPending()
                describe(reply)
            } catch (t: Throwable) {
                "error: ${t.message}"
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            runOnUiThread { log.append("\n> $said\n$line  (${ms} ms)\n") }
        }
    }

    private fun describe(r: Reply): String = when (r) {
        is Reply.Saved -> "Saved: ${r.memory.body}"
        is Reply.ReminderSet -> "Reminder: ${r.memory.body}. ${r.description}"
        is Reply.Answer -> "${r.shortAnswer}\n  from: ${r.memory.body}"
        is Reply.DidIAnswer -> if (r.done) "Yes: ${r.memory?.body}" else "No record of that today"
        is Reply.NotFound -> "I don't know yet. Save it?"
        is Reply.NeedsUnlock -> "That's in Private. Unlock to see it."
        else -> r.toString()
    }
}
