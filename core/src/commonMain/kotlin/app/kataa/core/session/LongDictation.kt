package app.kataa.core.session

/**
 * Long dictation on top of any recognizer: an idea, a to-do list, the journal, or hold-to-talk. Phone recognizers
 * stop at the first pause; this starts them again and joins what was heard, until the user taps stop, says
 * "that's it", or stays quiet for [quietRounds] rounds in a row.
 */
class LongDictation(private val engine: SpeechEngine, private val quietRounds: Int = 2) : SpeechEngine {
    private var emptyRounds = 0
    private val parts = mutableListOf<String>()
    @kotlin.concurrent.Volatile private var stopping = false
    @kotlin.concurrent.Volatile private var over = false
    private var quiet = 0

    override fun start(listener: SpeechEngine.Listener) {
        parts.clear(); stopping = false; over = false; quiet = 0; emptyRounds = 0
        round(listener)
    }

    private fun text() = parts.joinToString(" ") { it.trim() }.trim()

    private fun round(l: SpeechEngine.Listener) {
        // A recognizer that fails instantly must not spin forever: stop after many rounds in a row that heard nothing.
        // Rounds that hear speech never count, so a long dictation is never cut while the user is talking.
        if (emptyRounds > MAX_EMPTY_ROUNDS) { finish(l); return }
        engine.start(object : SpeechEngine.Listener {
            override fun onLevel(level: Float) { if (!over) l.onLevel(level) }
            override fun onPartial(text: String) { if (!over) l.onPartial((parts + text).joinToString(" ").trim()) }
            override fun onFinal(text: String) {
                if (over) return
                if (text.isNotBlank()) { parts += text; quiet = 0; emptyRounds = 0 } else emptyRounds++
                if (stopping || STOP.containsMatchIn(text.trim())) finish(l)
                else { l.onPartial(text()); round(l) }
            }
            override fun onError(error: SpeechError) {
                if (over) return
                when {
                    error == SpeechError.NO_SPEECH -> { quiet++; emptyRounds++; if (stopping || quiet >= quietRounds) finish(l) else round(l) }
                    parts.isNotEmpty() -> finish(l)
                    else -> { over = true; l.onError(error) }
                }
            }
        })
    }

    private fun finish(l: SpeechEngine.Listener) {
        if (over) return
        over = true
        val all = text()
        if (all.isEmpty()) l.onError(SpeechError.NO_SPEECH) else l.onFinal(all)
    }

    override fun stop() { stopping = true; engine.stop() }

    override fun cancel() { over = true; engine.cancel() }

    companion object {
        /** For "tap to stop" and hold-to-talk: only the user ends it. */
        const val UNTIL_STOPPED = Int.MAX_VALUE
        private const val MAX_EMPTY_ROUNDS = 60
        /** Said at the end: "that's it", "okay done", "stop listening". ("save it" is not: "...decided to save it".) */
        val STOP = Regex("(?i)(?:^|\\s)(?:that's it|that is it|that's all|that is all|ok(?:ay)? done|i am done|i'm done|stop listening)[.!]?$")
    }
}
