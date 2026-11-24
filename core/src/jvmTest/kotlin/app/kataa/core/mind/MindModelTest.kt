package app.kataa.core.mind

import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real on-device model (Qwen2.5-1.5B Q4_0 through native/llm) on the sentences that went wrong in
 * real use. Prints SKIP and passes when the host library or the model is missing.
 */
class MindModelTest {
    private val model: LlamaModel? = run {
        val lib = System.getProperty("kataa.llmLib").orEmpty()
        val gguf = System.getProperty("kataa.llmModel").orEmpty()
        if (lib.isEmpty() || gguf.isEmpty()) { println("SKIP mind: no host llm lib or model"); null }
        else { LlamaNative.load(lib); LlamaModel.open(gguf, threads = 4, fast = false) }
    }
    // Weight repacking (fast = true) gives garbage on x86 hosts with this llama.cpp build; ARM is checked separately.
    private val mind = model?.let(::Mind)?.also { it.budgetMs = 0 }

    @AfterTest fun close() { model?.close() }

    private fun understand(s: String): List<Command> {
        val t0 = System.nanoTime()
        val r = mind!!.understand(s, "Tuesday, 24 November 2026") ?: emptyList()
        println("MIND %5d ms  %-70s -> %s".format((System.nanoTime() - t0) / 1_000_000, "\"$s\"", r))
        return r
    }

    @Test fun understandsMessySentences() {
        if (mind == null) return
        understand("warm up") // first call fills the cached system prompt
        assertEquals(Command.Remind::class, understand("call more").single()::class)
        assertTrue(understand("give me the facebook passport").single().let { it is Command.Ask && it.text.contains("password", true) })
        assertTrue(understand("My Facebook password is what is").single() is Command.Ask)
        val two = understand("I have to submit the assignment the day after tomorrow and my bike is parked near the gate")
        assertTrue(two.any { it is Command.Remind } && two.any { it is Command.Save }, "$two")
        assertTrue(understand("ugh where did i leave the stupid charger again").single().let { it is Command.Ask && it.text.contains("charger") })
        assertTrue(understand("don't let me forget eggs when I'm at bhatbhateni").single().let { it is Command.Remind && it.text.contains("bhatbhateni", true) })
        assertTrue(understand("put the passport in the top shelf of the almirah").single() is Command.Save)
        understand("ok thanks")
        understand("remind me to take my medicine after dinner")
    }

    /** Past its time budget a call gives up (null), and the next call still works and is correct. */
    @Test fun budgetStopsSlowCalls() {
        val m = model ?: return
        kotlin.test.assertNull(m.complete(1, Mind.PICK_SYSTEM, "Question: wifi\nNotes:\n1. Wifi password is sunflower123", null, 20, budgetMs = 1))
        val after = Mind(m).apply { budgetMs = 0 }.pickAnswer("what is the wifi password", listOf("Call mom", "Wifi password is sunflower123"))
        assertEquals(2, after?.index, "$after")
    }

    @Test fun picksTheNoteThatAnswers() {
        if (mind == null) return
        val notes = listOf("My Facebook password is Samrat 001", "Wifi password is sunflower123", "Call mom")
        fun pick(q: String, n: List<String> = notes): Pick {
            val t0 = System.nanoTime()
            val p = mind.pickAnswer(q, n)!!
            println("PICK %5d ms  %-40s -> %s".format((System.nanoTime() - t0) / 1_000_000, q, p))
            return p
        }
        pick("warm up", notes)
        assertEquals(2, pick("what is the wifi password").index)
        assertEquals("sunflower123", pick("what is the wifi password").answer)
        assertEquals(1, pick("facebook password").index)
        assertEquals(0, pick("where are my glasses").index)
    }

    /** Whole conversations with the mind switched on, as the app runs them. */
    @Test fun assistantWithMind() {
        val m = mind ?: return
        val tz = kotlinx.datetime.TimeZone.of("Asia/Kathmandu")
        val clock = app.kataa.core.FakeClock(kotlinx.datetime.LocalDateTime(2026, 11, 24, 7, 0).toInstant(tz).toEpochMilliseconds())
        val core = app.kataa.core.newCore(clock)
        core.assistant.mind = m
        m.warmUp()
        fun say(s: String, unlocked: Boolean = true): app.kataa.core.assistant.Reply {
            val t0 = System.nanoTime()
            val c = app.kataa.core.assistant.AssistantContext(clock.now, tz, privateUnlocked = unlocked)
            val r = core.assistant.handle(s, c).let { if (it is app.kataa.core.assistant.Reply.ConfirmSecret) core.assistant.followUp(it, "yes", c) else it }
            core.processPending()
            println("TALK %5d ms  %-75s -> %s".format((System.nanoTime() - t0) / 1_000_000, "\"$s\"", app.kataa.core.assistant.ReplyText.of(r)))
            return r
        }
        // Clear sentences never touch the mind and stay instant.
        val t0 = System.nanoTime()
        kotlin.test.assertIs<app.kataa.core.assistant.Reply.Saved>(say("My keys are in the blue drawer"))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 400)
        say("My Facebook password is Samrat 001")
        say("Wifi password is sunflower123")

        println("CMDS " + m.understand("I have to submit the assignment the day after tomorrow at 10 and also my bike is parked near the gate", "Tuesday, 24 November 2026, 7:00 AM"))
        val both = say("I have to submit the assignment the day after tomorrow at 10 and also my bike is parked near the gate")
        kotlin.test.assertIs<app.kataa.core.assistant.Reply.Several>(both)
        assertTrue(both.replies.any { it is app.kataa.core.assistant.Reply.ReminderSet } && both.replies.any { it is app.kataa.core.assistant.Reply.Saved }, "$both")

        // Small talk never reaches the model and is never saved.
        assertTrue(say("ok thanks") !is app.kataa.core.assistant.Reply.Saved)

        val a = say("ugh what was the wifi thing again")
        assertTrue(a is app.kataa.core.assistant.Reply.Answer && a.shortAnswer.contains("sunflower123", true), "$a")
        val b = say("where did I leave my keys")
        assertTrue(b is app.kataa.core.assistant.Reply.Answer && b.shortAnswer.contains("blue drawer", true), "$b")
        val c = say("facebook passport")
        assertTrue(c is app.kataa.core.assistant.Reply.Answer && c.shortAnswer.contains("Samrat 001", true), "$c")
        kotlin.test.assertIs<app.kataa.core.assistant.Reply.NotFound>(say("where is my passport"))

        // A vague answer to "when?" becomes a real time.
        val ask = say("Call mom")
        kotlin.test.assertIs<app.kataa.core.assistant.Reply.NeedsTime>(ask)
        val set = core.assistant.followUp(ask, "after lunch tomorrow", app.kataa.core.assistant.AssistantContext(clock.now, tz))
        println("TALK follow-up \"after lunch tomorrow\" -> ${app.kataa.core.assistant.ReplyText.of(set)}")
        kotlin.test.assertIs<app.kataa.core.assistant.Reply.ReminderSet>(set)
    }
}
