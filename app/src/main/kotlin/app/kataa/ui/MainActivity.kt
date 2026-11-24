package app.kataa.ui

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import app.kataa.DevConsoleActivity
import app.kataa.KataaApplication
import app.kataa.R
import app.kataa.core.assistant.Describe
import app.kataa.core.assistant.Reply
import app.kataa.core.assistant.ReplyText
import app.kataa.core.model.Category
import app.kataa.core.model.Memory
import app.kataa.core.model.MemoryKind
import app.kataa.core.model.PlaceTarget
import app.kataa.core.model.Reminder
import app.kataa.core.model.ReminderType
import app.kataa.core.session.KataaState
import app.kataa.core.session.SpeechError
import app.kataa.triggers.Triggers
import app.kataa.data.MindProvider
import app.kataa.voice.AndroidSpeechEngine
import app.kataa.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import java.util.Calendar
import kotlin.time.Instant

/**
 * Kataa's one screen. The Nebula drop lives in the middle; everything else (what you said, the
 * "done" word, the answer with its 3D object, memories, reminders, settings) fades in over it.
 * It reads the same [KataaState] stream as every other entry point.
 */
class MainActivity : ComponentActivity() {
    private enum class Scene { HOME, TALK, ANSWER, MEMORIES, REMINDERS, CATEGORY, SETTINGS }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private val app get() = application as KataaApplication
    private val session get() = app.session
    private lateinit var look: Look
    private lateinit var ui: Ui
    private lateinit var speaker: Speaker

    private lateinit var root: FrameLayout
    private lateinit var nebula: NebulaView
    private lateinit var home: FracLayout
    private lateinit var talk: FracLayout
    private lateinit var answer: FracLayout
    private lateinit var listLayer: FrameLayout
    private lateinit var listScroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var dockButtons: List<View>

    // Home
    private lateinit var greetSmall: TextView
    private lateinit var nextChip: LinearLayout
    private lateinit var nextIcon: ImageView
    private lateinit var nextText: TextView

    // Talk
    private lateinit var said: TextView
    private lateinit var shine: ShineTextView
    private lateinit var sub: TextView
    private lateinit var snap: LinearLayout
    private lateinit var typeBar: EditText

    // Answer
    private lateinit var asked: TextView
    private lateinit var objView: ImageView
    private lateinit var bigText: TextView
    private lateinit var metaText: TextView
    private lateinit var saveIt: LinearLayout
    private var notFoundText: String? = null

    private var scene = Scene.HOME
    private var lastState: KataaState? = null
    private var snapFor: Memory? = null
    private var pendingUnlockQuery: String? = null
    private var insetTop = 0
    private var insetBottom = 0
    private val autoClose = Runnable { session.dismiss() }
    private val thinkingHint = Runnable {
        if (lastState is KataaState.Thinking) {
            sub.text = "Thinking…"; sub.alpha = 0f; sub.translationY = 0f
            sub.animate().alpha(1f).setDuration(500).start()
        }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startTalking() else showMessage("Kataa needs the microphone to hear you. You can type instead.", typeLink = true)
    }
    /** The phone's own voice typing screen: the fallback when in-app listening can't start. */
    private val voiceTyping = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = AndroidSpeechEngine.voiceTypingResult(res.data)
        if (text != null) session.type(text)
    }
    private val reminderPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { askReminderPermissions(next = true) }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val m = snapFor ?: return@registerForActivityResult
        snapFor = null
        if (ok && Spots.has(this, m.uuid)) {
            nebula.setState(NebulaView.State.DONE)
            shine.show("Photo saved"); sub.text = "Next time you ask, you'll see it"
            snap.visibility = View.INVISIBLE
            main.postDelayed(autoClose, 3800)
        } else main.postDelayed(autoClose, 1500)
    }
    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val q = pendingUnlockQuery ?: return@registerForActivityResult
        pendingUnlockQuery = null
        if (res.resultCode == RESULT_OK) { app.unlockPrivateFor(60_000); session.type(q) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        look = Look.current(this)
        ui = Ui(this, look)
        speaker = Speaker(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION") run { window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = look.light; isAppearanceLightNavigationBars = look.light
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(look.bg))

        build()
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            insetTop = bars.top; insetBottom = maxOf(bars.bottom, ime.bottom)
            for (f in listOf(home, talk, answer)) { f.insetTop = bars.top; f.insetBottom = bars.bottom; f.requestLayout() }
            (dock.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + ui.dpi(18f)
            list.setPadding(ui.dpi(22f), bars.top + ui.dpi(20f), ui.dpi(22f), bars.bottom + ui.dpi(120f))
            (typeBar.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin = insetBottom + ui.dpi(104f)
            dock.requestLayout(); typeBar.requestLayout()
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    typeBar.visibility == View.VISIBLE -> hideTypeBar()
                    scene == Scene.CATEGORY -> openMemories()
                    scene != Scene.HOME -> { session.cancel(); go(Scene.HOME) }
                    else -> finish()
                }
            }
        })
        go(Scene.HOME, instant = true)
        scope.launch { session.state.collect(::render) }
        if (intent?.getBooleanExtra(EXTRA_TALK, false) == true) main.postDelayed({ startTalking() }, 350)
    }

    override fun onResume() {
        super.onResume()
        refreshHome()
        app.ensureMind()
    }

    override fun onDestroy() {
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        speaker.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- building

    private fun build() {
        root = FrameLayout(this).apply { setBackgroundColor(look.bg) }
        nebula = NebulaView(this).apply { look = this@MainActivity.look }
        root.addView(nebula, FrameLayout.LayoutParams(-1, -1))

        home = FracLayout(this)
        greetSmall = ui.text(16f, 500, look.dim)
        home.add(greetSmall, FracLayout.Spec(0.07f, 0.84f, x = 0f))
        home.add(ui.text(30f, 800).apply { text = "What should I\nremember?" }, FracLayout.Spec(0.105f, 0.84f, x = 0f))
        nextIcon = ImageView(this)
        nextText = ui.text(14.5f, 500, lines = 1)
        nextChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ui.glass(); setPadding(ui.dpi(8f), ui.dpi(7f), ui.dpi(16f), ui.dpi(7f))
            addView(nextIcon, LinearLayout.LayoutParams(ui.dpi(28f), ui.dpi(28f)).apply { marginEnd = ui.dpi(10f) })
            addView(nextText)
            visibility = View.INVISIBLE
            setOnClickListener { openReminders() }
        }
        ui.pressable(nextChip)
        home.add(nextChip, FracLayout.Spec(0.24f, 0.84f, x = 0f, wrap = true))
        val gear = ui.icon(R.drawable.ic_settings, 24f, look.dim).apply {
            setPadding(ui.dpi(8f), ui.dpi(8f), ui.dpi(8f), ui.dpi(8f)); contentDescription = "Settings"
            setOnClickListener { openSettings() }
        }
        home.add(FrameLayout(this).apply { addView(gear, FrameLayout.LayoutParams(ui.dpi(40f), ui.dpi(40f))) }, FracLayout.Spec(0.005f, 0.88f, x = 1f, wrap = true))
        val orbTap = View(this).apply {
            minimumHeight = ui.dpi(280f); contentDescription = "Talk to Kataa"
            setOnClickListener { startTalking() }
        }
        home.add(orbTap, FracLayout.Spec(0.47f, 0.7f, centerY = true))
        home.add(ui.text(15f, 500, look.dim, Gravity.CENTER).apply { text = "Tap the drop to talk" }, FracLayout.Spec(0.70f))
        home.add(ui.text(15f, 700, look.accent, Gravity.CENTER).apply {
            text = "or type instead"; setPadding(0, ui.dpi(10f), 0, ui.dpi(10f)); setOnClickListener { showTypeBar() }
        }, FracLayout.Spec(0.725f))

        talk = FracLayout(this)
        said = ui.text(20f, 500, look.fg, Gravity.CENTER, lines = 5)
        talk.add(said, FracLayout.Spec(0.70f, 0.84f, centerY = true))
        shine = ShineTextView(this).apply { look = this@MainActivity.look; setMaxTextSize(ui.dp(50f)) }
        talk.add(shine, FracLayout.Spec(0.655f, 0.92f, centerY = true))
        sub = ui.text(16f, 500, look.dim, Gravity.CENTER, lines = 3)
        talk.add(sub, FracLayout.Spec(0.70f, 0.84f))
        snap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ui.glass(); setPadding(ui.dpi(18f), ui.dpi(12f), ui.dpi(20f), ui.dpi(12f))
            addView(ui.icon(R.drawable.ic_camera, 20f, look.accent), LinearLayout.LayoutParams(ui.dpi(20f), ui.dpi(20f)).apply { marginEnd = ui.dpi(10f) })
            addView(ui.text(15.5f, 700).apply { text = "Snap the spot" })
            visibility = View.INVISIBLE
            setOnClickListener { snapTheSpot() }
        }
        ui.pressable(snap)
        talk.add(snap, FracLayout.Spec(0.80f, 0.9f, wrap = true))
        talk.setOnClickListener { if (lastState is KataaState.Listening) session.tapToTalk() else if (lastState is KataaState.Done || lastState is KataaState.Failed) session.dismiss() }

        answer = FracLayout(this)
        asked = ui.text(15.5f, 500, look.dim, Gravity.CENTER, lines = 2)
        answer.add(asked, FracLayout.Spec(0.035f, 0.84f))
        objView = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; elevation = ui.dp(0f) }
        answer.add(objView, FracLayout.Spec(0.37f, 0.56f, centerY = true))
        bigText = ui.text(38f, 800, look.fg, Gravity.CENTER, lines = 3)
        metaText = ui.text(15.5f, 500, look.dim, Gravity.CENTER, lines = 2)
        answer.add(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(bigText); addView(metaText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dpi(10f) })
        }, FracLayout.Spec(0.6f, 0.88f))
        saveIt = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ui.glass(); setPadding(ui.dpi(20f), ui.dpi(12f), ui.dpi(20f), ui.dpi(12f))
            addView(ui.text(15.5f, 700).apply { text = "Save it instead" })
            visibility = View.GONE
            setOnClickListener {
                val t = notFoundText ?: return@setOnClickListener
                main.removeCallbacks(autoClose); visibility = View.GONE; session.saveInstead(t)
            }
        }
        ui.pressable(saveIt)
        answer.add(saveIt, FracLayout.Spec(0.84f, 0.9f, wrap = true))
        answer.setOnClickListener { session.dismiss() }

        listLayer = FrameLayout(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS; addView(list) }
        listLayer.addView(listScroll, FrameLayout.LayoutParams(-1, -1))

        for (layer in listOf(home, talk, answer, listLayer)) { layer.alpha = 0f; layer.visibility = View.INVISIBLE; root.addView(layer, FrameLayout.LayoutParams(-1, -1)) }

        typeBar = EditText(this).apply {
            hint = "Say it in words: \"my keys are in the drawer\""
            setHintTextColor(look.dim); setTextColor(look.fg); typeface = look.font(this@MainActivity, 500); textSize = 16f
            background = ui.glass(); setPadding(ui.dpi(20f), ui.dpi(14f), ui.dpi(20f), ui.dpi(14f))
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { v, _, _ -> val s = v.text.toString(); v.text = null; hideTypeBar(); if (s.isNotBlank()) session.type(s); true }
            visibility = View.GONE
        }
        root.addView(typeBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = ui.dpi(18f); rightMargin = ui.dpi(18f); bottomMargin = ui.dpi(104f) })

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ui.glass(); setPadding(ui.dpi(14f), ui.dpi(9f), ui.dpi(14f), ui.dpi(9f))
        }
        val memBtn = dockIcon(R.drawable.ic_grid, "Memories") { openMemories() }
        val mic = FrameLayout(this).apply {
            background = ui.accentFill(); contentDescription = "Talk"
            addView(ui.icon(R.drawable.ic_mic, 26f, look.onAccent), FrameLayout.LayoutParams(ui.dpi(26f), ui.dpi(26f), Gravity.CENTER))
            setOnClickListener { startTalking() }
            elevation = ui.dp(6f)
        }
        ui.pressable(mic)
        val remBtn = dockIcon(R.drawable.ic_bell, "Reminders") { openReminders() }
        dock.addView(memBtn, LinearLayout.LayoutParams(ui.dpi(48f), ui.dpi(48f)))
        dock.addView(mic, LinearLayout.LayoutParams(ui.dpi(60f), ui.dpi(60f)).apply { marginStart = ui.dpi(22f); marginEnd = ui.dpi(22f) })
        dock.addView(remBtn, LinearLayout.LayoutParams(ui.dpi(48f), ui.dpi(48f)))
        dockButtons = listOf(memBtn, mic, remBtn)
        root.addView(dock, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dpi(18f) })
    }

    private fun dockIcon(res: Int, label: String, onClick: () -> Unit) = FrameLayout(this).apply {
        contentDescription = label
        addView(ui.icon(res, 24f, look.fg), FrameLayout.LayoutParams(ui.dpi(24f), ui.dpi(24f), Gravity.CENTER))
        setOnClickListener { onClick() }
        alpha = 0.8f
        ui.pressable(this)
    }

    // ---------------------------------------------------------------- scenes

    private fun layerFor(s: Scene): View = when (s) {
        Scene.HOME -> home; Scene.TALK -> talk; Scene.ANSWER -> answer
        else -> listLayer
    }

    private fun go(next: Scene, instant: Boolean = false) {
        val from = layerFor(scene); val to = layerFor(next)
        val prev = scene
        scene = next
        if (from !== to || instant) {
            from.animate().cancel()
            if (from !== to) from.animate().alpha(0f).setDuration(if (instant) 0 else 380).setInterpolator(Motion.exit)
                .withEndAction { if (layerFor(scene) !== from) from.visibility = View.INVISIBLE }.start()
            to.visibility = View.VISIBLE
            to.animate().cancel()
            if (to.alpha < 0.05f) { to.scaleX = 0.985f; to.scaleY = 0.985f }
            to.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(if (instant) 0 else 120).setDuration(if (instant) 0 else 700).setInterpolator(Motion.standard).start()
        }
        when (next) {
            Scene.HOME -> orb(NebulaView.Mode.NEBULA, 0.47f, 1.32f, 1f)
            Scene.TALK -> orb(NebulaView.Mode.NEBULA, 0.42f, 1.32f, 1f)
            Scene.ANSWER -> orb(NebulaView.Mode.AURA, 0.37f, 1.35f, 1f)
            else -> orb(NebulaView.Mode.AURA, 0.1f, 1.1f, 0.45f)
        }
        dockButtons[0].alpha = if (next == Scene.MEMORIES || next == Scene.CATEGORY) 1f else 0.7f
        dockButtons[2].alpha = if (next == Scene.REMINDERS) 1f else 0.7f
        if (next == Scene.HOME && prev != Scene.HOME) refreshHome()
    }

    /** Moves the drop; switching between drop and glow fades through, so there is never a jump. */
    private fun orb(mode: NebulaView.Mode, cy: Float, scale: Float, alpha: Float) {
        nebula.moveTo(cy, scale)
        if (nebula.mode == mode) { nebula.animate().alpha(alpha).setDuration(600).start(); return }
        nebula.animate().alpha(0f).setDuration(320).setInterpolator(Motion.exit).withEndAction {
            nebula.mode = mode
            nebula.moveTo(cy, scale, instant = true)
            nebula.animate().alpha(alpha).setDuration(650).setInterpolator(Motion.standard).start()
        }.start()
    }

    // ---------------------------------------------------------------- talking

    private fun startTalking() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        hideTypeBar()
        speaker.stop()
        main.removeCallbacks(autoClose)
        session.tapToTalk()
    }

    private fun showTypeBar() {
        typeBar.visibility = View.VISIBLE
        typeBar.alpha = 0f; typeBar.translationY = ui.dp(16f)
        typeBar.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(Motion.standard).start()
        typeBar.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(typeBar, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideTypeBar() {
        if (typeBar.visibility != View.VISIBLE) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(typeBar.windowToken, 0)
        typeBar.animate().alpha(0f).translationY(ui.dp(16f)).setDuration(250).withEndAction { typeBar.visibility = View.GONE }.start()
    }

    private fun render(s: KataaState) {
        val prev = lastState
        lastState = s
        when (s) {
            KataaState.Idle -> {
                nebula.setState(NebulaView.State.IDLE)
                shine.hide()
                if (scene == Scene.TALK || scene == Scene.ANSWER) go(Scene.HOME)
            }
            is KataaState.Listening -> {
                main.removeCallbacks(autoClose)
                if (prev !is KataaState.Listening) {
                    resetTalk()
                    go(Scene.TALK)
                }
                nebula.setState(NebulaView.State.LISTEN)
                nebula.voiceLevel = s.level
                setSaid(s.partial.ifEmpty { "Listening…" }, dim = s.partial.isEmpty())
            }
            is KataaState.Thinking -> {
                if (scene != Scene.TALK) { resetTalk(); go(Scene.TALK) }
                nebula.setState(NebulaView.State.THINK)
                setSaid(s.heard, dim = false)
                // The on-device AI can take a few seconds on hard sentences: say so instead of looking stuck.
                main.removeCallbacks(thinkingHint)
                main.postDelayed(thinkingHint, 1200)
            }
            is KataaState.Done -> { main.removeCallbacks(thinkingHint); if (sub.text == "Thinking…") sub.text = ""; showReply(s.heard, s.reply) }
            is KataaState.Failed -> when (s.error) {
                SpeechError.NO_SPEECH -> session.dismiss()
                SpeechError.NO_PERMISSION -> { session.dismiss(); micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                SpeechError.UNAVAILABLE -> {
                    session.dismiss()
                    val i = AndroidSpeechEngine.voiceTypingIntent(this)
                    if (i != null) runCatching { voiceTyping.launch(i) }.onFailure { showTypeBar() }
                    else showMessage("This phone has no voice typing installed. Install Google or Speech Services by Google from the Play Store, or type instead.", typeLink = true)
                }
                SpeechError.OTHER -> showMessage("I couldn't catch that (error ${AndroidSpeechEngine.lastErrorCode}). Tap the drop to try again.")
            }
        }
    }

    private fun resetTalk() {
        main.removeCallbacks(thinkingHint)
        shine.hide(); sub.text = ""; sub.alpha = 0f; snap.visibility = View.INVISIBLE; said.alpha = 1f; said.text = ""
    }

    private fun setSaid(text: String, dim: Boolean) {
        said.setTextColor(if (dim) look.dim else look.fg)
        said.text = text
    }

    private fun showReply(heard: String, r: Reply, speak: Boolean = true) {
        if (speak && Look.speakReplies(this)) speaker.say(ReplyText.of(r))
        when (r) {
            is Reply.Answer -> showAnswer(heard, r.memory, r.shortAnswer)
            is Reply.DidIAnswer -> showDidI(heard, r)
            is Reply.Saved -> done("Saved", r.memory.body, offerPhoto = r.memory.takeIf { it.kind == MemoryKind.OBJECT_LOCATION })
            is Reply.AlreadySaved -> done("Already saved", r.memory.body)
            is Reply.Several -> {
                // One sentence, several things: show an answer if one was asked, else one "Done" for all.
                val answer = r.replies.firstOrNull { it is Reply.Answer || it is Reply.NeedsUnlock || it is Reply.NotFound }
                if (answer != null) showReply(heard, answer, speak = false)
                else done("All set", r.replies.joinToString("\n") { ReplyText.of(it) })
                return
            }
            is Reply.Replaced -> done("Updated", r.memory.body)
            is Reply.NeedsTime, is Reply.NeedsPlace -> {
                // Kataa asked a question: listen for the answer without another tap.
                showMessage(ReplyText.of(r), keepOpen = true)
                main.postDelayed({ if (lastState is KataaState.Done && scene == Scene.TALK) startTalking() }, if (Look.speakReplies(this)) 2600L else 1200L)
            }
            is Reply.ReminderSet -> {
                val needsPlace = r.reminders.any { it.placeTarget != null } &&
                    (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                        (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED))
                val permHint = if (needsPlace) "\nTurn on location in Settings > Allow reminders (pick \"Allow all the time\") so this can ring." else ""
                done("Reminder set", "${r.memory.body}. ${r.description}." + (r.hint?.let { "\n$it" } ?: "") + permHint)
            }
            is Reply.PlaceSaved -> done("Place saved", ReplyText.of(r))
            is Reply.Corrected -> done("Fixed", r.after.body)
            is Reply.Undone -> done("Deleted", r.memory.body)
            is Reply.CategoryCreated -> done("Added", "New group: ${r.category.name}")
            is Reply.CategoryList -> { session.dismiss(); openCategory(r.category) }
            is Reply.NeedsUnlock -> {
                showMessage("That's in Private. Unlock to see it.")
                val km = getSystemService(KeyguardManager::class.java)
                @Suppress("DEPRECATION")
                km.createConfirmDeviceCredentialIntent("Unlock Private", "To show this answer")?.let { pendingUnlockQuery = r.query; unlock.launch(it) }
            }
            is Reply.NotFound -> showNotFound(heard)
            else -> showMessage(ReplyText.of(r), keepOpen = true)
        }
    }

    private fun done(word: String, detail: String, offerPhoto: Memory? = null) {
        if (scene != Scene.TALK) go(Scene.TALK)
        nebula.setState(NebulaView.State.DONE)
        said.animate().alpha(0f).setDuration(250).start()
        shine.show(word)
        sub.text = detail
        sub.alpha = 0f; sub.translationY = ui.dp(8f)
        sub.animate().alpha(1f).translationY(0f).setStartDelay(850).setDuration(600).setInterpolator(Motion.standard).start()
        snapFor = offerPhoto
        if (offerPhoto != null) {
            snap.visibility = View.VISIBLE; snap.alpha = 0f; snap.translationY = ui.dp(10f)
            snap.animate().alpha(1f).translationY(0f).setStartDelay(1400).setDuration(700).setInterpolator(Motion.spring).start()
        }
        main.postDelayed(autoClose, if (offerPhoto != null) 6500 else 4200)
    }

    private fun showMessage(text: String, keepOpen: Boolean = false, typeLink: Boolean = false) {
        if (scene != Scene.TALK) { resetTalk(); go(Scene.TALK) }
        nebula.setState(NebulaView.State.IDLE)
        shine.hide()
        setSaid(text, dim = false)
        said.alpha = 0f
        said.animate().alpha(1f).setDuration(500).start()
        if (typeLink) main.postDelayed({ showTypeBar() }, 600)
        if (!keepOpen) main.postDelayed(autoClose, 4500)
    }

    private fun showAnswer(heard: String, memory: Memory, short: String) {
        prepareAnswer(heard)
        bigText.text = short
        metaText.text = "Saved ${relative(memory.createdAt)}"
        app.cores.worker.execute {
            val slugs = runCatching { app.cores.core.categories.assignmentsFor(memory.id).map { it.slug } }.getOrDefault(emptyList())
            val photo = Spots.load(this, memory.uuid, resources.displayMetrics.widthPixels)
            runOnUiThread { setObject(photo, ObjectIcons.forText(memory.body + " " + (memory.subject ?: ""), slugs)) }
        }
    }

    private fun showDidI(heard: String, r: Reply.DidIAnswer) {
        prepareAnswer(heard)
        bigText.text = if (r.done) "Yes, you did" else "No record of that"
        metaText.text = r.memory?.let { "${it.body}, ${relative(it.createdAt)}" } ?: "Say \"I took my medicine\" next time and I'll remember"
        setObject(null, r.memory?.let { ObjectIcons.forText(it.body) } ?: ObjectIcons.notFound)
    }

    private fun showNotFound(heard: String) {
        prepareAnswer(heard)
        bigText.text = "Not saved yet"
        metaText.text = "Tell me when you put it somewhere, and I'll remember"
        setObject(null, ObjectIcons.notFound)
        notFoundText = heard
        saveIt.visibility = View.VISIBLE; saveIt.alpha = 0f; saveIt.translationY = ui.dp(10f)
        saveIt.animate().alpha(1f).translationY(0f).setStartDelay(900).setDuration(600).setInterpolator(Motion.spring).start()
    }

    private fun prepareAnswer(heard: String) {
        val q = SpannableStringBuilder("You asked ")
        val startQ = q.length
        q.append("“").append(heard).append("”")
        q.setSpan(ForegroundColorSpan(look.fg), startQ, q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        asked.text = q
        saveIt.visibility = View.GONE; notFoundText = null
        objView.setImageDrawable(null)
        go(Scene.ANSWER)
        main.postDelayed(autoClose, 14_000)
    }

    /** The answer's picture pops in, then floats gently. A real photo (if one was taken) wins over the 3D object. */
    private fun setObject(photo: android.graphics.Bitmap?, iconRes: Int) {
        objView.animate().cancel()
        if (photo != null) {
            objView.setImageBitmap(photo); objView.scaleType = ImageView.ScaleType.CENTER_CROP
            ui.rounded(objView, 28f)
        } else {
            objView.setImageResource(iconRes); objView.scaleType = ImageView.ScaleType.FIT_CENTER
            objView.clipToOutline = false
        }
        objView.alpha = 0f; objView.scaleX = 0.7f; objView.scaleY = 0.7f; objView.translationY = ui.dp(24f)
        objView.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(200).setDuration(1100).setInterpolator(Motion.spring)
            .withEndAction { bob() }.start()
        bigText.alpha = 0f; bigText.translationY = ui.dp(12f)
        bigText.animate().alpha(1f).translationY(0f).setStartDelay(450).setDuration(700).setInterpolator(Motion.standard).start()
        metaText.alpha = 0f
        metaText.animate().alpha(1f).setStartDelay(800).setDuration(600).start()
    }

    private fun bob() {
        if (scene != Scene.ANSWER) return
        val up = objView.translationY > -ui.dp(3f)
        objView.animate().translationY(if (up) -ui.dp(7f) else 0f).rotation(if (up) -1f else 0f)
            .setStartDelay(0).setDuration(2500).setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction { bob() }.start()
    }

    private fun snapTheSpot() {
        val m = snapFor ?: return
        main.removeCallbacks(autoClose)
        runCatching { takePhoto.launch(Spots.captureUri(this, m.uuid)) }
            .onFailure { snapFor = null; showMessage("No camera app found on this phone.") }
        snapFor = m
    }

    // ---------------------------------------------------------------- home data

    private fun refreshHome() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greetSmall.text = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
        app.cores.worker.execute {
            val next = runCatching {
                val c = app.cores.core
                c.reminders.active().sortedBy { it.fireAt ?: Long.MAX_VALUE }.firstOrNull()?.let { r -> c.memories.get(r.memoryId)?.let { r to it } }
            }.getOrNull()
            val line = next?.let { (r, m) -> m.body to whenText(r) }
            runOnUiThread {
                if (line == null) { nextChip.visibility = View.INVISIBLE; return@runOnUiThread }
                nextIcon.setImageResource(ObjectIcons.forText(line.first).takeIf { it != ObjectIcons.note } ?: ObjectIcons.reminder)
                val sb = SpannableStringBuilder(line.first)
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("  ").append(line.second)
                val dimStart = sb.length - line.second.length
                sb.setSpan(ForegroundColorSpan(look.dim), dimStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                nextText.text = sb
                if (nextChip.visibility != View.VISIBLE) {
                    nextChip.visibility = View.VISIBLE; nextChip.alpha = 0f; nextChip.translationY = ui.dp(8f)
                    nextChip.animate().alpha(1f).translationY(0f).setDuration(600).setInterpolator(Motion.standard).start()
                }
            }
        }
    }

    private fun whenText(r: Reminder): String {
        val c = app.cores.core
        return when (r.type) {
            ReminderType.TIME -> r.fireAt?.let { Describe.time(Instant.fromEpochMilliseconds(it), r.rrule, System.currentTimeMillis(), TimeZone.currentSystemDefault()) } ?: ""
            ReminderType.PLACE_ENTER, ReminderType.PLACE_EXIT -> {
                val where = when (val t = r.placeTarget) {
                    is PlaceTarget.Specific -> c.places.get(t.placeId)?.label ?: "the place"
                    is PlaceTarget.Brand -> "any ${t.brand}"
                    is PlaceTarget.Type -> "a ${t.placeType}"
                    null -> "the place"
                }
                if (r.type == ReminderType.PLACE_ENTER) "when you're at $where" else "when you leave $where"
            }
            ReminderType.BLUETOOTH_CONNECT -> if (r.btDevice == "car") "when you get in the car" else "when ${r.btDevice} connects"
            ReminderType.BLUETOOTH_DISCONNECT -> if (r.btDevice == "car") "when you leave the car" else "when ${r.btDevice} disconnects"
        }
    }

    // ---------------------------------------------------------------- lists

    private fun startList(next: Scene, title: String, count: String? = null, back: (() -> Unit)? = null) {
        list.removeAllViews()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (back != null) head.addView(ui.icon(R.drawable.ic_back, 26f, look.fg).apply {
            setPadding(0, 0, ui.dpi(6f), 0); setOnClickListener { back() }; contentDescription = "Back"
        }, LinearLayout.LayoutParams(ui.dpi(34f), ui.dpi(28f)))
        head.addView(ui.text(32f, 800).apply { text = title }, LinearLayout.LayoutParams(0, -2, 1f))
        if (count != null) head.addView(ui.text(14f, 500, look.dim).apply { text = count })
        list.addView(head, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dpi(36f) })
        listScroll.scrollTo(0, 0)
        session.cancel()
        go(next)
    }

    private fun sectionTitle(text: String) = ui.text(17f, 700).apply { this.text = text }.also {
        list.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dpi(30f); bottomMargin = ui.dpi(6f) })
    }

    private fun openMemories() {
        startList(Scene.MEMORIES, "Memories")
        val search = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ui.glass(); setPadding(ui.dpi(18f), 0, ui.dpi(10f), 0)
            addView(ui.icon(R.drawable.ic_search, 20f, look.dim), LinearLayout.LayoutParams(ui.dpi(20f), ui.dpi(20f)).apply { marginEnd = ui.dpi(12f) })
            addView(EditText(this@MainActivity).apply {
                hint = "Ask or search"; setHintTextColor(look.dim); setTextColor(look.fg); textSize = 16f; typeface = look.font(this@MainActivity, 500)
                background = null; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
                setOnEditorActionListener { v, _, _ ->
                    val q = v.text.toString(); if (q.isNotBlank()) { v.text = null; hideKeyboard(v); session.type(q) }; true
                }
            }, LinearLayout.LayoutParams(0, ui.dpi(52f), 1f))
            addView(ui.icon(R.drawable.ic_mic, 22f, look.accent).apply {
                setPadding(ui.dpi(8f), ui.dpi(8f), ui.dpi(8f), ui.dpi(8f)); setOnClickListener { startTalking() }; contentDescription = "Ask by voice"
            }, LinearLayout.LayoutParams(ui.dpi(40f), ui.dpi(40f)))
        }
        list.addView(search, LinearLayout.LayoutParams(-1, ui.dpi(54f)).apply { topMargin = ui.dpi(18f) })
        val shelves = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(shelves)
        val recent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(recent)

        app.cores.worker.execute {
            val c = app.cores.core
            val cats = runCatching { c.categories.list().filter { it.memoryCount > 0 }.sortedByDescending { it.memoryCount } }.getOrDefault(emptyList())
            val mem = runCatching { c.memories.recent(30) }.getOrDefault(emptyList())
            val total = runCatching { c.memories.countLive() }.getOrDefault(0L)
            runOnUiThread {
                if (scene != Scene.MEMORIES) return@runOnUiThread
                ((list.getChildAt(0) as LinearLayout).getChildAt(0) as? TextView)?.let { }
                val head = list.getChildAt(0) as LinearLayout
                head.addView(ui.text(14f, 500, look.dim).apply { text = if (total == 1L) "1 saved" else "$total saved" })
                if (mem.isEmpty()) {
                    shelves.addView(emptyState("Nothing saved yet", "Tap the mic and say where you put something, like \"my passport is in the top drawer\"."))
                    return@runOnUiThread
                }
                var n = 0
                cats.chunked(3).forEach { row -> shelves.addView(shelf(row, n)); n += row.size }
                addMemoryRows(recent, mem)
            }
        }
    }

    private fun emptyState(title: String, body: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, ui.dpi(48f), 0, 0)
        addView(ImageView(this@MainActivity).apply { setImageResource(ObjectIcons.note) }, LinearLayout.LayoutParams(ui.dpi(84f), ui.dpi(84f)))
        addView(ui.text(20f, 800, look.fg, Gravity.CENTER).apply { text = title }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dpi(16f) })
        addView(ui.text(15f, 500, look.dim, Gravity.CENTER).apply { text = body }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dpi(8f) })
    }

    /** A glass shelf with up to three groups standing on it; the objects drop onto it when the screen opens. */
    private fun shelf(cats: List<Category>, startIndex: Int): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ui.dpi(26f), 0, 0) }
        val items = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val names = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until 3) {
            val cat = cats.getOrNull(i)
            val cell = FrameLayout(this)
            val name = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            if (cat != null) {
                val img = ImageView(this).apply {
                    setImageResource(ObjectIcons.forCategory(cat.slug)); contentDescription = cat.name
                    alpha = 0f; translationY = -ui.dp(36f)
                    animate().alpha(1f).translationY(0f).setStartDelay(220L + (startIndex + i) * 80L).setDuration(900).setInterpolator(Motion.spring).start()
                }
                cell.addView(img, FrameLayout.LayoutParams(ui.dpi(62f), ui.dpi(62f), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = -ui.dpi(2f) })
                cell.setOnClickListener { openCategory(cat) }
                ui.pressable(cell)
                name.addView(ui.text(14.5f, 700, lines = 1).apply { text = cat.name })
                name.addView(ui.text(14f, 500, look.dim).apply { text = "  ${cat.memoryCount}" })
                name.setOnClickListener { openCategory(cat) }
            }
            items.addView(cell, LinearLayout.LayoutParams(0, ui.dpi(70f), 1f))
            names.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        }
        box.addView(items)
        val ledge = View(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(look.glassHi, look.glass)).apply { cornerRadius = ui.dp(99f); setStroke(1, look.glassLine) }
        }
        box.addView(ledge, LinearLayout.LayoutParams(-1, ui.dpi(9f)))
        val glowUnder = View(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(NebulaView.withAlpha(look.accent, if (look.light) 0.18f else 0.22f), NebulaView.withAlpha(look.accent, 0f)))
        }
        box.addView(glowUnder, LinearLayout.LayoutParams(-1, ui.dpi(14f)).apply { leftMargin = ui.dpi(18f); rightMargin = ui.dpi(18f) })
        box.addView(names, LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dpi(4f) })
        return box
    }

    private fun addMemoryRows(into: LinearLayout, mem: List<Memory>) {
        val startOfDay = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val (today, earlier) = mem.partition { it.createdAt >= startOfDay }
        var i = 0
        if (today.isNotEmpty()) { into.addView(sectionLabel("Today")); today.forEach { into.addView(memoryRow(it, i++)) } }
        if (earlier.isNotEmpty()) { into.addView(sectionLabel("Earlier")); earlier.forEach { into.addView(memoryRow(it, i++)) } }
    }

    private fun sectionLabel(text: String) = ui.text(17f, 700).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dpi(30f); bottomMargin = ui.dpi(4f) }
    }

    private fun memoryRow(m: Memory, index: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ui.dpi(11f), 0, ui.dpi(11f))
        addView(ImageView(this@MainActivity).apply { setImageResource(ObjectIcons.forText(m.body + " " + (m.subject ?: ""))) },
            LinearLayout.LayoutParams(ui.dpi(38f), ui.dpi(38f)).apply { marginEnd = ui.dpi(14f) })
        addView(ui.text(15.5f, 600, lines = 2).apply { text = m.body }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ui.text(13f, 500, look.dim).apply { text = shortTime(m.createdAt) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dpi(12f) })
        setOnClickListener { memoryOptions(m) }
        alpha = 0f
        animate().alpha(1f).setStartDelay(500L + index.coerceAtMost(10) * 60L).setDuration(600).start()
    }

    private fun memoryOptions(m: Memory) {
        dialog().setTitle(m.body)
            .setMessage("Saved ${relative(m.createdAt)}")
            .setPositiveButton("Keep", null)
            .setNegativeButton("Delete") { _, _ ->
                app.cores.worker.execute { runCatching { app.cores.core.memories.trash(m.id); app.cores.core.reminders.forMemory(m.id).forEach { app.cores.core.reminders.cancel(it.id) } }
                    runCatching { Triggers.refreshAll(this) }
                    runOnUiThread { if (scene == Scene.MEMORIES) openMemories() } }
            }.show()
    }

    private fun openCategory(cat: Category) {
        startList(Scene.CATEGORY, cat.name, "${cat.memoryCount}", back = { openMemories() })
        val big = ImageView(this).apply { setImageResource(ObjectIcons.forCategory(cat.slug)); alpha = 0f; translationY = -ui.dp(24f)
            animate().alpha(1f).translationY(0f).setStartDelay(200).setDuration(900).setInterpolator(Motion.spring).start() }
        list.addView(big, LinearLayout.LayoutParams(ui.dpi(96f), ui.dpi(96f)).apply { topMargin = ui.dpi(18f); gravity = Gravity.CENTER_HORIZONTAL })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(rows)
        app.cores.worker.execute {
            val mem = runCatching { app.cores.core.categories.memoriesIn(cat.id, 100) }.getOrDefault(emptyList())
            runOnUiThread {
                if (scene != Scene.CATEGORY) return@runOnUiThread
                if (cat.isPrivate) { rows.addView(emptyState("Private", "Ask Kataa by voice, then unlock with your fingerprint or PIN.")); return@runOnUiThread }
                mem.forEachIndexed { i, m -> rows.addView(memoryRow(m, i)) }
            }
        }
    }

    private fun openReminders() {
        startList(Scene.REMINDERS, "Reminders")
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(rows)
        app.cores.worker.execute {
            val c = app.cores.core
            val items = runCatching {
                c.reminders.active().mapNotNull { r -> c.memories.get(r.memoryId)?.let { Triple(r, it, whenText(r)) } }
                    .sortedBy { it.first.fireAt ?: Long.MAX_VALUE }
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (scene != Scene.REMINDERS) return@runOnUiThread
                if (items.isEmpty()) {
                    rows.addView(emptyState("No reminders", "Try \"remind me to buy milk when I'm at Big Mart\" or \"remind me at 8 to call mom\"."))
                    return@runOnUiThread
                }
                items.forEachIndexed { i, (r, m, w) ->
                    rows.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, ui.dpi(14f), 0, ui.dpi(14f))
                        val icon = ObjectIcons.forText(m.body).takeIf { it != ObjectIcons.note } ?: ObjectIcons.reminder
                        addView(ImageView(this@MainActivity).apply { setImageResource(icon) }, LinearLayout.LayoutParams(ui.dpi(40f), ui.dpi(40f)).apply { marginEnd = ui.dpi(14f) })
                        addView(LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(ui.text(16f, 700, lines = 2).apply { text = m.body })
                            addView(ui.text(14f, 500, look.dim, lines = 2).apply { text = w.replaceFirstChar { it.uppercase() } + if (r.persistent) ", until done" else "" },
                                LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dpi(3f) })
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        setOnClickListener {
                            dialog().setTitle(m.body).setMessage(w.replaceFirstChar { it.uppercase() })
                                .setPositiveButton("Done") { _, _ -> changeReminder { app.cores.core.reminders.markDone(r.id) } }
                                .setNegativeButton("Delete") { _, _ -> changeReminder { app.cores.core.reminders.cancel(r.id) } }
                                .setNeutralButton("Close", null).show()
                        }
                        alpha = 0f
                        animate().alpha(1f).setStartDelay(250L + i * 60L).setDuration(600).start()
                    })
                }
            }
        }
    }

    private fun changeReminder(block: () -> Unit) {
        app.cores.worker.execute {
            runCatching(block); runCatching { Triggers.refreshAll(this) }
            runOnUiThread { if (scene == Scene.REMINDERS) openReminders() }
        }
    }

    private fun openSettings() {
        startList(Scene.SETTINGS, "Settings", back = { go(Scene.HOME) })
        sectionTitle("Look")
        for (l in Look.entries) {
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dpi(14f), ui.dpi(12f), ui.dpi(14f), ui.dpi(12f))
                if (l == look) background = ui.glass(18f)
                addView(View(this@MainActivity).apply {
                    background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(l.accent, l.accent2)).apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(ui.dpi(5f), l.bg)
                    }
                }, LinearLayout.LayoutParams(ui.dpi(38f), ui.dpi(38f)).apply { marginEnd = ui.dpi(14f) })
                addView(ui.text(16f, 600).apply { text = l.label }, LinearLayout.LayoutParams(0, -2, 1f))
                if (l == look) addView(ui.icon(R.drawable.ic_check, 22f, look.accent))
                setOnClickListener { if (l != look) { Look.save(this@MainActivity, l); recreate() } }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dpi(6f) })
        }
        sectionTitle("Voice")
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(16f, 600).apply { text = "Speak replies out loud" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Switch(this@MainActivity).apply {
                isChecked = Look.speakReplies(this@MainActivity)
                thumbTintList = android.content.res.ColorStateList.valueOf(look.accent)
                setOnCheckedChangeListener { _, on -> Look.setSpeakReplies(this@MainActivity, on) }
            })
        }, LinearLayout.LayoutParams(-1, ui.dpi(52f)))
        sectionTitle("Smart mode")
        val mindNow = app.mind
        val mindDetail = ui.text(13.5f, 500, look.dim)
        fun mindText() = when {
            !Look.smartMode(this) -> "Off. Kataa uses only its fast rules."
            mindNow.unsupportedReason() != null -> "Not available: ${mindNow.unsupportedReason()}."
            mindNow.status == MindProvider.Status.READY -> "On. The AI on your phone handles long or unclear sentences and picks the best answer. Nothing leaves your phone."
            mindNow.status == MindProvider.Status.FAILED -> "Couldn't start the AI on this phone."
            else -> "Starting the AI…"
        }
        mindDetail.text = mindText()
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(16f, 600).apply { text = "On-device AI" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Switch(this@MainActivity).apply {
                isChecked = Look.smartMode(this@MainActivity)
                thumbTintList = android.content.res.ColorStateList.valueOf(look.accent)
                setOnCheckedChangeListener { _, on ->
                    Look.setSmartMode(this@MainActivity, on)
                    if (on) app.ensureMind() else mindNow.unload()
                    mindDetail.text = mindText()
                }
            })
        }, LinearLayout.LayoutParams(-1, ui.dpi(52f)))
        list.addView(mindDetail, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dpi(8f) })
        if (mindNow.status == MindProvider.Status.LOADING) main.postDelayed({ if (scene == Scene.SETTINGS) mindDetail.text = mindText() }, 4000)
        sectionTitle("Reminders")
        list.addView(settingsRow("Allow reminders", "Notifications, location for store reminders, exact alarms, car Bluetooth") { askReminderPermissions(next = false) })
        list.addView(settingsRow("Car, backups and tools", "Pick your car's Bluetooth, save an encrypted backup, restore") {
            startActivity(Intent(this, DevConsoleActivity::class.java))
        })
        list.addView(ui.text(13.5f, 500, look.dim).apply {
            text = "Everything you save stays on this phone, encrypted. Kataa has no internet permission.\n3D objects: Microsoft Fluent Emoji (MIT). Fonts: Space Grotesk, Manrope, Nunito (OFL)."
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dpi(34f) })
    }

    private fun settingsRow(title: String, detail: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, ui.dpi(12f), 0, ui.dpi(12f))
        addView(ui.text(16f, 600).apply { text = title })
        addView(ui.text(13.5f, 500, look.dim).apply { text = detail }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dpi(3f) })
        setOnClickListener { onClick() }
    }

    /** Step by step, as Android requires: notifications + location first, background location separately. */
    /** Which step of the reminder permission walk we're on; each step is asked once per tap, so a "deny" never loops. */
    private var permStep = 0

    private fun askReminderPermissions(next: Boolean) {
        permStep = if (next) permStep + 1 else 0
        val first = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        when {
            permStep == 0 && first.isNotEmpty() -> reminderPermissions.launch(first.toTypedArray())
            permStep <= 1 && Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED -> {
                permStep = 1
                // Android shows this as a settings page: the user has to pick "Allow all the time".
                Toast.makeText(this, "For place reminders, pick \"Allow all the time\"", Toast.LENGTH_LONG).show()
                reminderPermissions.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }
            permStep <= 2 && Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms() -> {
                permStep = 2
                Toast.makeText(this, "Turn on alarms so reminders ring on time", Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
            }
            else -> {
                app.cores.worker.execute { runCatching { Triggers.refreshAll(this) } }
                Toast.makeText(this, reminderStatus(), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** A plain summary of what reminders can do with the permissions the user gave. */
    private fun reminderStatus(): String {
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val notif = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
        val exact = Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val place = has(Manifest.permission.ACCESS_FINE_LOCATION) && (Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        return listOf(
            if (notif) "Notifications on" else "Notifications OFF",
            if (exact) "alarms on time" else "alarms may be a few minutes late",
            if (place) "place reminders on" else "place reminders OFF",
        ).joinToString(", ")
    }

    // ---------------------------------------------------------------- small things

    private fun dialog() = AlertDialog.Builder(this,
        if (look.light) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert)

    private fun hideKeyboard(v: View) = getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)

    private fun relative(ms: Long): String {
        val d = (System.currentTimeMillis() - ms) / 1000
        return when {
            d < 60 -> "just now"
            d < 3600 -> "${d / 60} min ago"
            d < 86_400 -> (d / 3600).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
            d < 2 * 86_400 -> "yesterday"
            d < 30 * 86_400 -> "${d / 86_400} days ago"
            else -> "on " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))
        }
    }

    private fun shortTime(ms: Long): String {
        val d = System.currentTimeMillis() - ms
        return if (d < 86_400_000L) android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(ms))
        else java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }

    companion object {
        /** Open straight into listening (shortcut, widget). */
        const val EXTRA_TALK = "app.kataa.extra.TALK"
    }
}
