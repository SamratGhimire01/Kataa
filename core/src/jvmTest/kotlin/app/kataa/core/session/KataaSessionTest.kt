package app.kataa.core.session

import app.kataa.core.FakeClock
import app.kataa.core.assistant.AssistantContext
import app.kataa.core.assistant.Reply
import app.kataa.core.model.GeoPoint
import app.kataa.core.newCore
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KataaSessionTest {
    private val tz = TimeZone.of("Asia/Kathmandu")
    private val clock = FakeClock(LocalDateTime(2027, 1, 15, 10, 30).toInstant(tz).toEpochMilliseconds())
    private val core = newCore(clock)

    /** Scripted recognizer: replays partials, levels and a final result. */
    private class FakeSpeech : SpeechEngine {
        var listener: SpeechEngine.Listener? = null
        var stopped = false
        override fun start(listener: SpeechEngine.Listener) { this.listener = listener; stopped = false }
        override fun stop() { stopped = true }
        override fun cancel() { listener = null }
        fun say(vararg words: String) {
            val l = listener!!
            val sb = StringBuilder()
            for (w in words) { sb.append(w).append(' '); l.onLevel(0.6f); l.onPartial(sb.trim().toString()) }
            l.onFinal(sb.trim().toString())
        }
    }

    private val speech = FakeSpeech()
    private var location: GeoPoint? = null
    private val session = KataaSession(
        core = { core }, speech = speech,
        context = { AssistantContext(clock.now, tz, location) },
        runOnCore = { it() }, // tests run core work inline
    )

    @Test fun voiceFlowGoesThroughStates() {
        val seen = mutableListOf<KataaState>()
        session.tapToTalk()
        seen += session.state.value
        speech.listener!!.onLevel(0.4f)
        speech.listener!!.onPartial("my keys are")
        seen += session.state.value
        speech.say("my", "keys", "are", "in", "the", "drawer")
        seen += session.state.value

        assertEquals(KataaState.Listening("", 0f), seen[0])
        assertEquals(KataaState.Listening("my keys are", 0.4f), seen[1])
        val done = assertIs<KataaState.Done>(seen[2])
        assertIs<Reply.Saved>(done.reply)
        assertEquals(app.kataa.core.model.Source.VOICE, done.reply.memory.source)

        session.dismiss()
        assertEquals(KataaState.Idle, session.state.value)
        session.tapToTalk(); speech.say("where", "are", "my", "keys")
        assertEquals("In the drawer", ((session.state.value as KataaState.Done).reply as Reply.Answer).shortAnswer)
    }

    @Test fun tappingAgainFinishesListening() {
        session.tapToTalk()
        session.tapToTalk()
        assertEquals(true, speech.stopped)
    }

    @Test fun silenceReturnsToIdleAndErrorsAreShown() {
        session.tapToTalk(); speech.listener!!.onError(SpeechError.NO_SPEECH)
        assertEquals(KataaState.Idle, session.state.value)
        session.tapToTalk(); speech.listener!!.onError(SpeechError.NO_PERMISSION)
        assertEquals(KataaState.Failed(SpeechError.NO_PERMISSION), session.state.value)
        val noMic = KataaSession({ core }, null, { AssistantContext(clock.now, tz) }, { it() })
        noMic.tapToTalk()
        assertEquals(KataaState.Failed(SpeechError.UNAVAILABLE), noMic.state.value)
    }

    @Test fun followUpQuestionsAreAnswered() {
        session.type("remind me to renew my passport")
        assertIs<Reply.NeedsTime>((session.state.value as KataaState.Done).reply)
        session.type("next Monday at 10")
        val set = assertIs<Reply.ReminderSet>((session.state.value as KataaState.Done).reply)
        assertEquals("Renew my passport", set.memory.body)
        assertEquals("Monday at 10:00 AM", set.description)

        // "where?" answered with a place.
        session.type("remind me to buy stamps")
        session.type("at any post office")
        assertEquals("When you arrive at any post office", ((session.state.value as KataaState.Done).reply as Reply.ReminderSet).description)

        // A new request instead of an answer is just handled normally.
        session.type("remind me to call the bank")
        session.type("my wallet is in the car")
        assertIs<Reply.Saved>((session.state.value as KataaState.Done).reply)
    }

    @Test fun homeFollowUp() {
        session.type("when I get home remind me to water the plants")
        assertIs<Reply.NeedsPlace>((session.state.value as KataaState.Done).reply)
        // The user prefers a time instead.
        session.type("tonight")
        assertEquals("Today at 8:00 PM", ((session.state.value as KataaState.Done).reply as Reply.ReminderSet).description)
    }
}
