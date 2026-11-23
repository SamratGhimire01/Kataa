package app.kataa.entry

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.kataa.KataaApplication
import android.app.KeyguardManager
import app.kataa.core.assistant.Reply
import app.kataa.core.assistant.ReplyText
import app.kataa.core.session.KataaState
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
 * Starts listening at once, shows the result, then gets out of the way. Placeholder look; the
 * frontend phase gives it the orb.
 *
 * Over the lock screen it only *saves*: answers can contain private things, so questions asked while
 * locked are answered after unlock.
 */
class CaptureActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var text: TextView
    private lateinit var speaker: Speaker
    private var closer: Job? = null
    private val session get() = (application as KataaApplication).session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        speaker = Speaker(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        text = TextView(this).apply { textSize = 20f; setPadding(pad, pad, pad, pad); gravity = Gravity.CENTER; text = "…" }
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(text)
            setOnClickListener { session.tapToTalk() } // tap to finish / talk again
        })
        scope.launch { session.state.collect(::render) }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) session.tapToTalk() else finish()
    }

    private fun render(s: KataaState) {
        closer?.cancel()
        text.text = when (s) {
            KataaState.Idle -> "Tap to talk"
            is KataaState.Listening -> s.partial.ifEmpty { "Listening…" }
            is KataaState.Thinking -> s.heard
            is KataaState.Done -> ReplyText.of(s.reply)
            is KataaState.Failed -> "Couldn't hear you. Tap to try again."
        }
        if (s is KataaState.Done && locked() && revealsMemories(s.reply)) {
            // Over the lock screen: saving is fine, but answers wait for the owner to unlock.
            text.text = "Unlock to see the answer"
            getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = render(s)
                override fun onDismissCancelled() = finish()
            })
            return
        }
        if (s is KataaState.Done) {
            val line = ReplyText.of(s.reply)
            speaker.say(line)
            // Leave the answer up long enough to read, then close.
            closer = scope.launch { delay(1500L + line.length * 45L); session.dismiss(); finish() }
        }
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun revealsMemories(r: Reply) = r is Reply.Answer || r is Reply.DidIAnswer || r is Reply.CategoryList ||
        r is Reply.Undone || r is Reply.Corrected || (r is Reply.PlaceSaved && r.errandsHere.isNotEmpty())

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) { session.cancel(); finish() }
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
