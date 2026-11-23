package app.kataa

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.kataa.core.assistant.ReplyText
import app.kataa.core.session.KataaState
import app.kataa.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Temporary developer console to try the backend on a real phone: tap Talk or type, see what Kataa
 * does. The real interface (orb, Kai, animation, design) is the frontend phase and replaces this; it
 * will read the same [KataaState] stream.
 */
class DevConsoleActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var speaker: Speaker

    private val session get() = (application as KataaApplication).session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = Speaker(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { setPadding(pad, pad, pad, 0); textSize = 18f }
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; setPadding(pad, pad, pad, pad); text = "Kataa developer console\n" }
        val talk = Button(this).apply { text = "Talk"; setOnClickListener { talk() } }
        val input = EditText(this).apply {
            hint = "Or type, e.g. my keys are in the drawer"
            imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
            setOnEditorActionListener { v, _, _ -> session.type(v.text.toString()); v.text = ""; true }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(talk); addView(input); addView(status)
            addView(ScrollView(context).apply { addView(log) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        scope.launch { session.state.collect(::render) }
    }

    private fun talk() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        speaker.stop()
        session.tapToTalk()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) session.tapToTalk()
    }

    private fun render(s: KataaState) {
        status.text = when (s) {
            KataaState.Idle -> "Ready"
            is KataaState.Listening -> "Listening " + "▮".repeat((s.level * 10).toInt()) + "\n" + s.partial
            is KataaState.Thinking -> "…"
            is KataaState.Done -> ReplyText.of(s.reply)
            is KataaState.Failed -> "Mic problem: ${s.error}. You can type instead."
        }
        if (s is KataaState.Done) {
            val text = ReplyText.of(s.reply)
            log.append("\n> ${s.heard}\n$text\n  ${s.reply::class.simpleName}\n")
            speaker.say(text)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        speaker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val REQ_MIC = 1
    }
}
