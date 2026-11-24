package app.kataa.core.session

import app.kataa.core.FakeClock
import app.kataa.core.assistant.AssistantContext
import app.kataa.core.assistant.Reply
import app.kataa.core.newCore
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Long listening (ideas, lists, the journal), tap-to-stop and hold-to-talk: pauses never cut the user off. */
class LongListeningTest {
    /** A phone recognizer: each round ends at the first pause, with what it heard or with NO_SPEECH. */
    private class Rounds : SpeechEngine {
        var listener: SpeechEngine.Listener? = null
        var starts = 0
        var stopped = false
        override fun start(listener: SpeechEngine.Listener) { this.listener = listener; starts++ }
        override fun stop() { stopped = true; listener?.onError(SpeechError.NO_SPEECH) }
        override fun cancel() { listener = null }
        fun hear(text: String) = listener!!.onFinal(text)
        fun silence() = listener!!.onError(SpeechError.NO_SPEECH)
    }

    private class Result : SpeechEngine.Listener {
        var final: String? = null
        var error: SpeechError? = null
        override fun onLevel(level: Float) {}
        override fun onPartial(text: String) {}
        override fun onFinal(text: String) { final = text }
        override fun onError(error: SpeechError) { this.error = error }
    }

    @Test fun pausesDoNotEndItAndEverythingIsJoined() {
        val r = Rounds(); val out = Result()
        LongDictation(r, quietRounds = 2).start(out)
        r.hear("I want an app that")
        r.silence() // one pause
        r.hear("reminds me where I parked")
        assertEquals(null, out.final)
        r.silence(); r.silence() // two quiet rounds in a row: done
        assertEquals("I want an app that reminds me where I parked", out.final)
    }

    @Test fun thatsItEndsIt() {
        val r = Rounds(); val out = Result()
        LongDictation(r).start(out)
        r.hear("buy milk")
        r.hear("call didi that's it")
        assertEquals("buy milk call didi that's it", out.final)
    }

    @Test fun saveItInsideASentenceDoesNotEndIt() {
        val r = Rounds(); val out = Result()
        LongDictation(r).start(out)
        r.hear("I found an old photo and decided to save it")
        assertEquals(null, out.final)
        r.hear("okay done")
        assertEquals("I found an old photo and decided to save it okay done", out.final)
    }

    @Test fun untilStoppedIgnoresSilenceUntilTheUserStops() {
        val r = Rounds(); val out = Result()
        val d = LongDictation(r, LongDictation.UNTIL_STOPPED)
        d.start(out)
        r.hear("first thought")
        repeat(20) { r.silence() }
        assertEquals(null, out.final)
        r.hear("second thought")
        d.stop()
        assertEquals("first thought second thought", out.final)
    }

    @Test fun aBrokenRecognizerDoesNotSpinForever() {
        val r = Rounds(); val out = Result()
        LongDictation(r, LongDictation.UNTIL_STOPPED).start(out)
        repeat(200) { if (out.error == null && out.final == null) r.silence() }
        assertEquals(SpeechError.NO_SPEECH, out.error)
        assertTrue(r.starts < 100, "${r.starts}")
    }

    // The session: hold-to-talk, tap-to-stop, and the long listening Kataa asks for.
    private val tz = TimeZone.of("Asia/Kathmandu")
    private val clock = FakeClock(LocalDateTime(2026, 11, 24, 8, 0).toInstant(tz).toEpochMilliseconds())
    private val core = newCore(clock)
    private val speech = Rounds()
    private val session = KataaSession(core = { core }, speech = speech, context = { AssistantContext(clock.now, tz) }, runOnCore = { it() })

    @Test fun holdToTalkListensUntilReleased() {
        session.holdStart()
        speech.hear("my passport is in the")
        speech.silence(); speech.silence(); speech.silence()
        assertIs<KataaState.Listening>(session.state.value)
        speech.hear("blue drawer")
        session.holdEnd()
        val done = assertIs<KataaState.Done>(session.state.value)
        assertIs<Reply.Saved>(done.reply)
        assertEquals("My passport is in the blue drawer", (done.reply as Reply.Saved).memory.body.trimEnd('.'))
    }

    @Test fun tapToStopKeepsListeningUntilTappedAgain() {
        session.tapToTalk(keepListening = true)
        speech.hear("my bike key is")
        speech.silence(); speech.silence()
        assertIs<KataaState.Listening>(session.state.value)
        speech.hear("on the hook")
        session.tapToTalk() // the second tap
        assertIs<Reply.Saved>(assertIs<KataaState.Done>(session.state.value).reply)
    }

    @Test fun anIdeaIsHeardInFull() {
        session.type("i have an idea")
        assertIs<Reply.ListeningForIdea>(assertIs<KataaState.Done>(session.state.value).reply)
        session.listenLong()
        speech.hear("a shop that rents tools")
        speech.silence()
        speech.hear("by the hour")
        speech.silence(); speech.silence()
        val saved = assertIs<KataaState.Done>(session.state.value).reply
        assertTrue(saved.toString().contains("by the hour"), "$saved")
    }
}
