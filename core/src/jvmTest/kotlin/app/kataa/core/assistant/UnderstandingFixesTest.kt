package app.kataa.core.assistant

import app.kataa.core.FakeClock
import app.kataa.core.model.MemoryKind
import app.kataa.core.newCore
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The sentences that went wrong in the 24 Nov 2026 simulation, rules only (no AI). Each must stay fixed. */
class UnderstandingFixesTest {
    private val tz = TimeZone.of("Asia/Kathmandu")
    private val clock = FakeClock(LocalDateTime(2026, 11, 24, 10, 45).toInstant(tz).toEpochMilliseconds())
    private val core = newCore(clock)

    private fun ctx(unlocked: Boolean = true) = AssistantContext(clock.now, tz, privateUnlocked = unlocked)

    /** Says [s]; a spoken password must first be shown back, and is saved on "yes". */
    private fun say(s: String, unlocked: Boolean = true): Reply {
        val r = core.assistant.handle(s, ctx(unlocked))
        return (if (r is Reply.ConfirmSecret) core.assistant.followUp(r, "yes", ctx(unlocked)) else r).also { core.processPending() }
    }

    private fun fireAt(r: Reply): LocalDateTime {
        val set = assertIs<Reply.ReminderSet>(r, "$r")
        return Instant.fromEpochMilliseconds(set.reminders.single().fireAt!!).toLocalDateTime(tz)
    }

    @Test fun passwordSaidAsWriteThePasswordOf() {
        val r = say("Write the password of wifi sunflower123")
        val saved = assertIs<Reply.Saved>(r, "$r")
        assertEquals(MemoryKind.FACT, saved.memory.kind)
        assertTrue(saved.memory.body.contains("sunflower123"))
        assertTrue((say("what is the wifi password") as Reply.Answer).shortAnswer.contains("sunflower123"))
    }

    @Test fun spokenPasswordIsConfirmedBeforeSaving() {
        val ask = assertIs<Reply.ConfirmSecret>(core.assistant.handle("my wifi password is sunflower123", ctx()))
        assertEquals("sunflower123", ask.value)
        assertEquals(0, core.memories.recent().size, "nothing saved before yes")
        // "No, it's sunflower124": shown again with the fix, then saved on yes.
        val fixed = assertIs<Reply.ConfirmSecret>(core.assistant.followUp(ask, "no it's sunflower124", ctx()))
        assertEquals("sunflower124", fixed.value)
        val saved = core.assistant.followUp(fixed, "yes", ctx())
        assertTrue(saved is Reply.Saved && saved.memory.body.contains("sunflower124"), "$saved")
        // Never spoken aloud.
        assertTrue("sunflower" !in ReplyText.spoken(fixed))
        // "No" saves nothing.
        val again = assertIs<Reply.ConfirmSecret>(core.assistant.handle("my gmail password is abc", ctx()))
        assertIs<Reply.NotSaved>(core.assistant.followUp(again, "no", ctx()))
        // Typed passwords are exact: saved at once.
        assertIs<Reply.Saved>(core.assistant.handle("netflix password is nf2026", ctx().copy(source = app.kataa.core.model.Source.TEXT)))
    }

    @Test fun passwordsAreHiddenUntilUnlock() {
        say("my atm pin is 4455")
        // Hidden even before categories are worked out (no processPending here).
        assertIs<Reply.NeedsUnlock>(core.assistant.handle("atm pin", ctx(unlocked = false)))
        assertIs<Reply.NeedsUnlock>(core.assistant.handle("show me all my passwords", ctx(unlocked = false)))
        val vault = assertIs<Reply.CategoryList>(core.assistant.handle("show me all my passwords", ctx()))
        assertEquals(1, vault.memories.size)
        assertEquals("Atm pin is ••••••", app.kataa.core.memory.Secrets.masked("Atm pin is 4455"))
        assertEquals("Facebook", app.kataa.core.memory.Secrets.service("My Facebook password is Samrat 001"))
        assertTrue(!app.kataa.core.memory.Secrets.looksSecret("Locker combination is on the gym card", app.kataa.core.model.MemoryKind.NOTE, null))
    }

    @Test fun otherPasswordWordings() {
        for (s in listOf("Save my Instagram password samrat@123", "netflix password is nf2026", "password for gmail is Kathmandu#45")) {
            val r = say(s)
            assertTrue(r is Reply.Saved && r.memory.kind == MemoryKind.FACT, "$s -> $r")
        }
        assertTrue((say("instagram password") as Reply.Answer).shortAnswer.contains("samrat@123"))
    }

    @Test fun changePasswordReplacesTheOldOne() {
        say("My Facebook password is Samrat 001")
        assertIs<Reply.Replaced>(say("change my facebook password to Samrat 002"))
        val a = assertIs<Reply.Answer>(say("facebook password"))
        assertTrue(a.memory.body.contains("Samrat 002"), "$a")
    }

    @Test fun alarms() {
        assertEquals(LocalDateTime(2026, 11, 25, 5, 0), fireAt(say("wake me up at 5 am")))
        // "7" with no am/pm at 10:45 AM: 7 PM today or 7 AM tomorrow, so it asks.
        val ask = assertIs<Reply.PickTime>(say("set an alarm for 7"))
        assertEquals("Today at 7:00 PM or Tomorrow at 7:00 AM?", ReplyText.of(ask))
        assertEquals(LocalDateTime(2026, 11, 25, 7, 0), fireAt(core.assistant.followUp(ask, "am", ctx())))
    }

    @Test fun agendaQuestions() {
        say("remind me to call mom at 6 30 pm")
        say("remind me tomorrow at 9 am to pay rent")
        val today = assertIs<Reply.Agenda>(say("what do I have to do today"))
        assertEquals(listOf("Call mom"), today.items.map { it.memory.body })
        assertIs<Reply.Agenda>(say("what are my reminders")).let { assertEquals(2, it.items.size) }
        assertIs<Reply.Agenda>(say("what do I have tomorrow")).let { assertEquals(listOf("Pay rent"), it.items.map { i -> i.memory.body }) }
        assertEquals(2, core.reminders.active().size, "asking must not create reminders")
    }

    @Test fun ambiguousTimesAsk() {
        val a = assertIs<Reply.PickTime>(say("call didi at 7:13"))
        assertEquals(listOf(LocalDateTime(2026, 11, 24, 19, 13), LocalDateTime(2026, 11, 25, 7, 13)),
            a.options.map { Instant.fromEpochMilliseconds(it.atMs).toLocalDateTime(tz) })
        assertEquals(LocalDateTime(2026, 11, 25, 7, 13), fireAt(core.assistant.followUp(a, "the second one", ctx())))
        // "after 3 days at 7": the day is kept, only the half of the clock is asked.
        val b = assertIs<Reply.PickTime>(say("remind me to call dad after 3 days at 7"))
        assertTrue(b.options.all { Instant.fromEpochMilliseconds(it.atMs).toLocalDateTime(tz).date == kotlinx.datetime.LocalDate(2026, 11, 27) }, "$b")
        assertEquals(19, fireAt(core.assistant.followUp(b, "evening", ctx())).hour)
        // Clear ones never ask.
        assertEquals(5, fireAt(say("wake me up at 5")).hour)
        assertEquals(20, fireAt(say("remind me to eat dinner at 8")).hour)
        assertEquals(19, fireAt(say("call mom at 7 pm")).hour)
        assertEquals(18, fireAt(say("remind me to pay bill at 18:00")).hour)
    }

    @Test fun unknownFactAsksThenRemembers() {
        val nf = assertIs<Reply.NotFound>(say("what is my mom's birthday"))
        assertEquals("your mom's birthday", nf.askFor)
        val r = core.assistant.followUp(nf, "12 March", ctx())
        val set = assertIs<Reply.ReminderSet>(r, "$r")
        assertEquals("My mom's birthday is 12 March", set.memory.body)
        assertEquals("FREQ=YEARLY", set.reminders.single().rrule)
        assertTrue((say("when is mom's birthday") as Reply.Answer).shortAnswer.contains("12 march", ignoreCase = true))
        // A new question instead of an answer is just handled.
        val nf2 = assertIs<Reply.NotFound>(say("what is my passport number"))
        assertIs<Reply.NotFound>(core.assistant.followUp(nf2, "where is my umbrella", ctx()))
    }

    @Test fun unknownQuestionIsNotAnsweredWithARandomNote() {
        say("remind me to call mom at 6 30 pm")
        assertIs<Reply.NotFound>(say("what is my mom's birthday"))
        say("mom's birthday is 12 march")
        assertTrue((say("when is mom's birthday") as Reply.Answer).shortAnswer.contains("12 march", ignoreCase = true))
    }

    @Test fun fillersAreIgnored() {
        say("the charger is in my office bag")
        val a = say("ugh where did i leave the stupid charger again")
        assertTrue(a is Reply.Answer && a.shortAnswer.contains("office bag", true), "$a")
        val ask = assertIs<Reply.PickTime>(say("uh remind me uh to um call didi at like 7"))
        assertEquals("Call didi", ask.memory.body)
        assertEquals(19, fireAt(core.assistant.followUp(ask, "pm", ctx())).hour)
    }

    @Test fun remindWithoutMe() {
        val r = say("remind to buy milk")
        assertTrue(r is Reply.NeedsTime && r.memory.body == "Buy milk", "$r")
    }

    @Test fun whoOwesMeMoney() {
        say("Ram owes me 500 rupees")
        val a = say("who owes me money")
        assertTrue(a is Reply.Answer && a.memory.body.contains("Ram"), "$a")
    }

    @Test fun twoThingsInOneSentence() {
        val r = say("I have to submit the assignment the day after tomorrow at 10 and my bike is parked near the gate")
        val several = assertIs<Reply.Several>(r, "$r")
        // "at 10" could be 10 AM or 10 PM: the reminder part asks, the bike part is saved.
        assertTrue(several.replies.any { it is Reply.PickTime } && several.replies.any { it is Reply.Saved }, "$r")
    }

    @Test fun ideaModeStarts() {
        for (s in listOf("I've got an idea", "I have an idea", "note this idea", "I got one idea")) {
            assertIs<Reply.ListeningForIdea>(say(s), s)
        }
        val saved = core.assistant.saveIdea("Open a small momo shop near the college gate. Cheap lunch sets for students.", AssistantContext(clock.now, tz))
        assertIs<Reply.Saved>(saved)
        val a = say("what was my idea about the momo shop")
        assertTrue(a is Reply.Answer && a.memory.body.contains("momo shop"), "$a")
    }
}
