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
) {
    private val _state = MutableStateFlow<KataaState>(KataaState.Idle)
    val state: StateFlow<KataaState> = _state.asStateFlow()

    /** The last reply, if it asked the user something. */
    @kotlin.concurrent.Volatile private var awaiting: Reply? = null

    /** The big orb / power key: start listening, or finish if already listening. */
    fun tapToTalk() {
        val engine = speech ?: return fail(SpeechError.UNAVAILABLE)
        if (_state.value is KataaState.Listening) { engine.stop(); return }
        _state.value = KataaState.Listening("", 0f)
        engine.start(object : SpeechEngine.Listener {
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
        })
    }

    fun cancel() {
        speech?.cancel()
        _state.value = KataaState.Idle
    }

    /** Typed input goes through exactly the same path as speech. */
    fun type(text: String) {
        if (text.isNotBlank()) submit(text.trim(), voice = false)
    }

    /** Back to the resting orb (after the result card is dismissed). */
    fun dismiss() {
        if (_state.value is KataaState.Done || _state.value is KataaState.Failed) _state.value = KataaState.Idle
    }

    private fun submit(text: String, voice: Boolean) {
        _state.value = KataaState.Thinking(text)
        runOnCore {
            val c = core()
            val ctx = context().let { if (voice) it.copy(source = app.kataa.core.model.Source.VOICE) else it.copy(source = app.kataa.core.model.Source.TEXT) }
            val previous = awaiting
            val reply = if (previous != null) c.assistant.followUp(previous, text, ctx) else c.assistant.handle(text, ctx)
            awaiting = reply.takeIf { it is Reply.NeedsTime || it is Reply.NeedsPlace || it is Reply.NeedsLocation }
            _state.value = KataaState.Done(text, reply)
            // Embedding and categories happen after the answer is on screen.
            c.processPending()
        }
    }

    private fun fail(error: SpeechError) {
        _state.value = if (error == SpeechError.NO_SPEECH) KataaState.Idle else KataaState.Failed(error)
    }
}
