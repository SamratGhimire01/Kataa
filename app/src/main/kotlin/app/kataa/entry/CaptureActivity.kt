package app.kataa.entry

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.kataa.KataaApplication
import app.kataa.core.assistant.Reply
import app.kataa.core.assistant.ReplyText
import app.kataa.core.session.KataaState
import app.kataa.ui.Look
import app.kataa.ui.Motion
import app.kataa.ui.NebulaView
import app.kataa.ui.ShineTextView
import app.kataa.ui.Ui
import app.kataa.core.session.SpeechError
import app.kataa.voice.AndroidSpeechEngine
import app.kataa.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Quick capture: opened by the power-key assistant, the quick tile, the home widget and app shortcuts.
 * A glass sheet rises from the bottom with the Nebula drop, starts listening at once, shows the result
 * (tick and the shining word), then gets out of the way.
 *
 * Over the lock screen it only *saves*: answers can contain private things, so questions asked while
 * locked are answered after unlock.
 */
class CaptureActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var speaker: Speaker
    private lateinit var look: Look
    private lateinit var ui: Ui
    private lateinit var sheet: LinearLayout
    private lateinit var nebula: NebulaView
    private lateinit var shine: ShineTextView
    private lateinit var line: android.widget.TextView
    private var closer: Job? = null
    private val session get() = (application as KataaApplication).session
    private var inVoiceTyping = false
    private val voiceTyping = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
        inVoiceTyping = false
        val text = AndroidSpeechEngine.voiceTypingResult(res.data)
        if (text != null) session.type(text) else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        speaker = Speaker(this)
        look = Look.current(this)
        ui = Ui(this, look)

        nebula = NebulaView(this).apply { look = this@CaptureActivity.look; moveTo(0.5f, 2.3f, instant = true) }
        shine = ShineTextView(this).apply { look = this@CaptureActivity.look; setMaxTextSize(ui.dp(40f)) }
        line = ui.text(18f, 600, look.fg, Gravity.CENTER, lines = 4).apply { text = "Listening…" }
        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(NebulaView.withAlpha(look.bg, 0.94f)); cornerRadius = ui.dp(34f); setStroke(1, look.glassLine)
            }
            setPadding(ui.dpi(24f), ui.dpi(10f), ui.dpi(24f), ui.dpi(28f))
            addView(nebula, LinearLayout.LayoutParams(-1, ui.dpi(200f)))
            addView(FrameLayout(this@CaptureActivity).apply {
                addView(line, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                addView(shine, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            }, LinearLayout.LayoutParams(-1, ui.dpi(96f)))
            setOnClickListener { session.tapToTalk() } // tap to finish / talk again
            translationY = ui.dp(400f)
        }
        val root = FrameLayout(this).apply {
            addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = ui.dpi(10f); rightMargin = ui.dpi(10f) })
            setOnClickListener { session.cancel(); finish() }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            (sheet.layoutParams as FrameLayout.LayoutParams).bottomMargin = b + ui.dpi(10f)
            sheet.requestLayout(); insets
        }
        setContentView(root)
        sheet.animate().translationY(0f).setDuration(650).setInterpolator(Motion.spring).start()
        scope.launch { session.state.collect(::render) }
        (application as KataaApplication).ensureMind()
        start(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        start(intent)
    }

    private fun start(intent: Intent?) {
        intent?.getStringExtra(EXTRA_TEXT)?.let { session.type(it); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        speaker.stop()
        if (session.state.value !is KataaState.Listening) session.tapToTalk()
    }

    @Deprecated("Simple one-off permission request")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        @Suppress("DEPRECATION") super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) session.tapToTalk() else finish()
    }

    private fun say(text: String) {
        shine.hide()
        line.visibility = View.VISIBLE
        line.text = text
    }

    private fun render(s: KataaState) {
        closer?.cancel()
        when (s) {
            KataaState.Idle -> { nebula.setState(NebulaView.State.IDLE); say("Tap to talk") }
            is KataaState.Listening -> { nebula.setState(NebulaView.State.LISTEN); nebula.voiceLevel = s.level; say(s.partial.ifEmpty { "Listening…" }) }
            is KataaState.Thinking -> { nebula.setState(NebulaView.State.THINK); say(s.heard) }
            is KataaState.Done -> {}
            is KataaState.Failed -> {
                nebula.setState(NebulaView.State.IDLE)
                say(if (s.error == SpeechError.UNAVAILABLE) "Voice isn't working right now. Tap to try again." else "Couldn't hear you. Tap to try again.")
            }
        }
        if (s !is KataaState.Done) return
        if (locked() && revealsMemories(s.reply)) {
            // Over the lock screen: saving is fine, but answers wait for the owner to unlock.
            say("Unlock to see the answer")
            getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = render(s)
                override fun onDismissCancelled() = finish()
            })
            return
        }
        val text = ReplyText.of(s.reply)
        val word = when (s.reply) {
            is Reply.Saved -> "Saved"
            is Reply.AlreadySaved -> "Already saved"
            is Reply.Replaced -> "Updated"
            is Reply.Several -> if ((s.reply as Reply.Several).replies.none { it is Reply.Answer }) "All set" else null
            is Reply.ReminderSet -> "Reminder set"
            is Reply.PlaceSaved -> "Place saved"
            is Reply.Corrected -> "Fixed"
            is Reply.Undone -> "Deleted"
            else -> null
        }
        if (word != null) {
            nebula.setState(NebulaView.State.DONE)
            line.visibility = View.INVISIBLE
            shine.show(word)
        } else {
            nebula.setState(NebulaView.State.IDLE)
            say(text)
        }
        if (Look.speakReplies(this)) speaker.say(text)
        // Leave the result up long enough to read, then slide away.
        closer = scope.launch {
            delay(if (word != null) 2600L else 1800L + text.length * 45L)
            session.dismiss()
            sheet.animate().translationY(sheet.height + ui.dp(40f)).setDuration(380).setInterpolator(Motion.exit).withEndAction { finish() }.start()
        }
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun revealsMemories(r: Reply) = r is Reply.Answer || r is Reply.DidIAnswer || r is Reply.CategoryList ||
        r is Reply.Undone || r is Reply.Corrected || (r is Reply.PlaceSaved && r.errandsHere.isNotEmpty())

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !inVoiceTyping) { session.cancel(); finish() }
    }

    override fun onDestroy() {
        scope.cancel()
        speaker.shutdown()
        super.onDestroy()
    }

    companion object {
        /** Text to handle instead of listening (from a shortcut or share). */
        const val EXTRA_TEXT = "app.kataa.extra.TEXT"
    }
}
