package app.kataa.core.session

import app.kataa.core.KataaCore
import app.kataa.core.assistant.AssistantContext
import app.kataa.core.assistant.Reply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the screen shows. This is the contract between backend and frontend: the orb and Kai animate
 * from these states only, so the UI can change without touching the backend.
 */
sealed interface KataaState {
    data object Idle : KataaState
    /** [level] is voice loudness 0..1 for the orb; [partial] is the live transcript. */
    data class Listening(val partial: String, val level: Float) : KataaState
    data class Thinking(val heard: String) : KataaState
    data class Done(val heard: String, val reply: Reply) : KataaState
    data class Failed(val error: SpeechError) : KataaState
}

enum class SpeechError {
    /** Nothing was said (timeout). Quietly go back to idle. */
    NO_SPEECH,
    NO_PERMISSION,
    /** No speech recognizer on this phone: offer typing. */
    UNAVAILABLE,
    /** Recognizer busy, audio failure, or anything else. */
    OTHER,
}

/** Platform speech-to-text (Android SpeechRecognizer, iOS SFSpeechRecognizer, or an on-device model). */
interface SpeechEngine {
    fun start(listener: Listener)
    /** Stop listening and deliver the final result for what was said so far. */
    fun stop()
    /** Stop and discard. */
    fun cancel()

    interface Listener {
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(error: SpeechError)
    }
}

/**
 * One conversation with Kataa: listen (or type) -> understand -> act -> show the result. Handles
 * Kataa's follow-up questions ("When should I remind you?") so the next utterance answers them.
 *
 * Threading: listener callbacks may come on any thread. All database work goes through [runOnCore]
 * (one background thread in the app), so the UI thread never waits on the database or the model.
 */
class KataaSession(
    private val core: () -> KataaCore,
    private val speech: SpeechEngine?,
    private val context: () -> AssistantContext,
    private val runOnCore: (() -> Unit) -> Unit,
    /** Waits for an accurate location; used when a request needs one and the cached fix was too old. */
    private val freshLocation: (() -> app.kataa.core.model.GeoPoint?)? = null,
) {
    private val _state = MutableStateFlow<KataaState>(KataaState.Idle)
    val state: StateFlow<KataaState> = _state.asStateFlow()

    /** The last reply, if it asked the user something. */
    @kotlin.concurrent.Volatile private var awaiting: Reply? = null

    /** True while Kataa waits for a long dictation (after "I've got an idea", "write my list", "today's journal"). */
    val wantsLongDictation: Boolean
        get() = awaiting.let { it is Reply.ListeningForIdea || it is Reply.ListeningForList || it is Reply.ListeningForJournal }

    /** What is listening now: the recognizer itself, or a [LongDictation] around it. */
    @kotlin.concurrent.Volatile private var listening: SpeechEngine? = null

    /**
     * The big orb / power key: start listening, or finish if already listening. [keepListening] (the "tap to
     * start, tap to stop" mic setting): pauses don't end it, only a second tap does.
     */
    fun tapToTalk(keepListening: Boolean = false) {
        if (_state.value is KataaState.Listening) { listening?.stop(); return }
        listen(long = keepListening || wantsLongDictation, untilStopped = keepListening)
    }

    /** Starts long dictation for the idea, list or journal Kataa just asked for (the app calls it after speaking the prompt). */
    fun listenLong() { if (_state.value !is KataaState.Listening) listen(long = true) }

    /** Hold-to-talk: listening lasts while the button is held. */
    fun holdStart() { if (_state.value !is KataaState.Listening) listen(long = true, untilStopped = true) }
    fun holdEnd() { if (_state.value is KataaState.Listening) listening?.stop() }

    private fun listen(long: Boolean, untilStopped: Boolean = false) {
        val base = speech ?: return fail(SpeechError.UNAVAILABLE)
        val engine = if (long) LongDictation(base, if (untilStopped) LongDictation.UNTIL_STOPPED else 2) else base
        listening = engine
        _state.value = KataaState.Listening("", 0f)
        val listener = object : SpeechEngine.Listener {
            override fun onLevel(level: Float) {
                (_state.value as? KataaState.Listening)?.let { _state.value = it.copy(level = level.coerceIn(0f, 1f)) }
            }
            override fun onPartial(text: String) {
                (_state.value as? KataaState.Listening)?.let { _state.value = it.copy(partial = text) }
            }
            override fun onFinal(text: String) {
                if (text.isBlank()) fail(SpeechError.NO_SPEECH) else submit(text, voice = true)
            }
            override fun onError(error: SpeechError) = fail(error)
        }
        engine.start(listener)
    }

    /** A tick box on a list card. */
    fun setTaskDone(item: app.kataa.core.lists.ListItem, done: Boolean, listKey: String) {
        runOnCore {
            try {
                val c = core()
                _state.value = KataaState.Done(item.body, c.assistant.setTaskDone(item, done, listKey, context()))
            } catch (t: Throwable) {
                _state.value = KataaState.Failed(SpeechError.OTHER)
            }
        }
    }

    fun cancel() {
        listening?.cancel()
        speech?.cancel()
        awaiting = null // closed without answering: the next sentence is a new one
        _state.value = KataaState.Idle
    }

    /** "Later" on a question card: the next sentence is a new one, not the answer. */
    fun forget() { awaiting = null }

    /** Typed input goes through exactly the same path as speech. */
    fun type(text: String) {
        if (text.isNotBlank()) submit(text.trim(), voice = false)
    }

    /** "Save it instead" after a search found nothing: stores [text] as a note. */
    fun saveInstead(text: String) {
        if (text.isBlank()) return
        _state.value = KataaState.Thinking(text)
        runOnCore {
            try {
                val c = core()
                awaiting = null
                _state.value = KataaState.Done(text, c.assistant.saveAsNote(text, context()))
                runCatching { c.processPending() }
            } catch (t: Throwable) {
                _state.value = KataaState.Failed(SpeechError.OTHER)
            }
        }
    }

    /**
     * The Save button on the password card, with the value or service as the user fixed them (null = as heard).
     * Does nothing unless Kataa is waiting for that yes.
     */
    fun confirmSecret(value: String? = null, service: String? = null) {
        val pending = awaiting as? Reply.ConfirmSecret ?: return
        _state.value = KataaState.Thinking(pending.service)
        runOnCore {
            try {
                val c = core()
                awaiting = null
                val r = c.assistant.confirmSecret(pending, context(), value ?: pending.value, service ?: pending.service)
                _state.value = KataaState.Done(pending.service, r)
                runCatching { c.processPending() }
            } catch (t: Throwable) {
                _state.value = KataaState.Failed(SpeechError.OTHER)
            }
        }
    }

    /** A time button on the "AM or PM?" card. */
    fun pickTime(option: app.kataa.core.assistant.TimeOption) {
        val pending = awaiting as? Reply.PickTime ?: return
        runOnCore {
            try {
                val c = core()
                awaiting = null
                _state.value = KataaState.Done(option.label, c.assistant.pickTimeOption(pending, option, context()))
            } catch (t: Throwable) {
                _state.value = KataaState.Failed(SpeechError.OTHER)
            }
        }
    }

    /** "Don't save" on the password card. */
    fun cancelSecret() {
        if (awaiting !is Reply.ConfirmSecret) return
        awaiting = null
        _state.value = KataaState.Done("", Reply.NotSaved)
    }

    /** Back to the resting orb (after the result card is dismissed). */
    fun dismiss() {
        if (_state.value is KataaState.Done || _state.value is KataaState.Failed) _state.value = KataaState.Idle
    }

    private fun submit(text: String, voice: Boolean) {
        _state.value = KataaState.Thinking(text)
        runOnCore {
            try {
                handleOnCore(text, voice)
            } catch (t: Throwable) {
                // Never leave the orb stuck on "thinking".
                _state.value = KataaState.Failed(SpeechError.OTHER)
            }
        }
    }

    private fun handleOnCore(text: String, voice: Boolean) {
        run {
            val c = core()
            val ctx = context().let { if (voice) it.copy(source = app.kataa.core.model.Source.VOICE) else it.copy(source = app.kataa.core.model.Source.TEXT) }
            val previous = awaiting
            var reply = if (previous != null) c.assistant.followUp(previous, text, ctx) else c.assistant.handle(text, ctx)
            if (reply == Reply.NeedsLocation) {
                // "this is Big Mart" with no recent fix: get one now and try again.
                freshLocation?.invoke()?.let { loc -> reply = c.assistant.handle(text, ctx.copy(location = loc)) }
            }
            // A question Kataa asked (when? AM or PM? is this password right?): the next thing said answers it.
            fun asks(r: Reply) = r is Reply.NeedsTime || r is Reply.NeedsPlace || r is Reply.NeedsLocation || r is Reply.ConfirmSecret ||
                r is Reply.PickTime || (r is Reply.NotFound && r.askFor != null) ||
                r is Reply.ListeningForIdea || r is Reply.ListeningForList || r is Reply.ListeningForJournal
            awaiting = if (reply is Reply.Several) (reply as Reply.Several).replies.lastOrNull(::asks) else reply.takeIf(::asks)
            _state.value = KataaState.Done(text, reply)
            // Embedding and categories happen after the answer is on screen; a failure there must not
            // turn a saved memory into an error.
            runCatching { c.processPending() }
        }
    }

    private fun fail(error: SpeechError) {
        _state.value = if (error == SpeechError.NO_SPEECH) KataaState.Idle else KataaState.Failed(error)
    }
}
