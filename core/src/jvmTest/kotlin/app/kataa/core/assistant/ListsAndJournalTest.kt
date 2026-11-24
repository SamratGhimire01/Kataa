package app.kataa.core.assistant

import app.kataa.core.FakeClock
import app.kataa.core.lists.ListKey
import app.kataa.core.lists.ListText
import app.kataa.core.newCore
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** To-do lists and the journal, by voice (24 Nov 2026 asks). Rules only. */
class ListsAndJournalTest {
    private val tz = TimeZone.of("Asia/Kathmandu")
    private val clock = FakeClock(LocalDateTime(2026, 11, 24, 8, 0).toInstant(tz).toEpochMilliseconds())
    private val core = newCore(clock)
    private val today = LocalDate(2026, 11, 24)
    private fun ctx() = AssistantContext(clock.now, tz, privateUnlocked = true)
    private fun say(s: String) = core.assistant.handle(s, ctx()).also { core.processPending() }
    private fun list(r: Reply) = assertIs<Reply.TaskListReply>(r, "$r")

    @Test fun splitsADictatedList() {
        assertEquals(listOf("Buy milk", "Call Didi", "Pay the electricity bill"),
            ListText.split("buy milk and call Didi then pay the electricity bill").map { ListText.parseItem(it)!!.body })
        assertEquals(listOf("buy bread and butter", "call mom"), ListText.split("buy bread and butter and call mom"))
        assertEquals(listOf("go to the bank", "study maths", "clean my room"),
            ListText.split("first go to the bank second study maths third clean my room"))
        val p = ListText.parseItem("buy a gift for 1,500 rupees")!!
        assertEquals("Buy a gift", p.body); assertEquals(1500.0, p.cost)
        assertEquals(120, ListText.parseItem("study for 2 hours")!!.minutes)
        assertEquals(30, ListText.parseItem("Walk 30 minutes")!!.minutes)
    }

    @Test fun dictateTodaysListThenTickAndCount() {
        val listen = assertIs<Reply.ListeningForList>(say("write my to-do list for today"))
        assertEquals(ListKey.day(today), listen.key)
        val added = list(core.assistant.followUp(listen, "buy milk 100 rupees and call Didi then study for 2 hours and that's it", ctx()))
        assertEquals(listOf("Buy milk", "Call Didi", "Study"), added.list.items.map { it.body })
        // "How many are done?" -> 0 of 3.
        assertEquals("0 of 3 done, 3 left.", ReplyText.of(say("how many tasks are done")))
        val ticked = list(say("mark buy milk as done"))
        assertEquals(ListAction.TICKED, ticked.action)
        assertEquals(1, ticked.list.doneCount)
        list(say("tick number 2")).let { assertEquals("Call Didi", it.items.single().body) }
        assertEquals("2 of 3 done, 1 left.", ReplyText.of(say("how many tasks are left today")))
        list(say("untick call didi")).let { assertEquals(ListAction.UNTICKED, it.action) }
        assertEquals(1, list(say("show my to do list")).list.doneCount)
    }

    @Test fun listAloneShowsOrListens() {
        assertIs<Reply.ListeningForList>(say("today's to do list"))
        say("add buy eggs to my to do list")
        assertEquals(ListAction.SHOWN, list(say("today's to-do list")).action)
        // Said inline with the items.
        val r = list(say("tomorrow's to do list: pick up the parcel and pay rent"))
        assertEquals(ListKey.day(LocalDate(2026, 11, 25)), r.list.key)
        assertEquals(2, r.items.size)
        // Dates and months.
        assertEquals(ListKey.day(LocalDate(2026, 12, 19)), assertIs<Reply.ListeningForList>(say("december 19th to do list")).key)
        assertEquals("month:2026-11", assertIs<Reply.ListeningForList>(say("this month's goals")).key)
        assertEquals("month:2026-11", assertIs<Reply.ListeningForList>(say("write my monthly goals")).key)
    }

    @Test fun costAndTimeQuestions() {
        say("add buy a gift 1500 rupees to my to do list")
        say("add recharge phone rs 200 to my to do list")
        say("add study 2 hours to my to do list")
        assertEquals("The cheapest is Recharge phone, Rs 200.", ReplyText.of(say("what is the cost of the least task")))
        assertEquals("About Rs 1700 for 2 tasks on today's list.", ReplyText.of(say("how much will today's tasks cost")))
        assertEquals("The quickest is Study, 2 h.", ReplyText.of(say("what is the quickest task")))
    }

    @Test fun shoppingListIsANamedList() {
        val r = list(say("add milk and eggs to my shopping list"))
        assertEquals("named:shopping", r.list.key)
        assertEquals(listOf("Milk", "Eggs"), r.list.items.map { it.body })
        list(say("remove eggs from my shopping list")).let { assertEquals(listOf("Milk"), it.list.items.map { i -> i.body }) }
    }

    @Test fun unfinishedTasksComeAlongToTheNextDay() {
        say("add submit the form to my to do list")
        clock.advanceDays(1.0)
        val r = list(say("show my to do list"))
        assertEquals(listOf("Submit the form"), r.list.items.map { it.body })
        assertTrue(r.list.carried(r.list.items.single()))
        list(say("i finished submit the form")).let { assertEquals(ListAction.TICKED, it.action) }
    }

    @Test fun ordinarySentencesAreNotListCommands() {
        assertIs<Reply.Saved>(say("my price list is on the desk"))
        assertIs<Reply.Saved>(say("the laundry is done"))
        assertIs<Reply.NeedsTime>(say("remind me to do the laundry"))
        assertIs<Reply.Agenda>(say("what do i have to do today"))
        assertIs<Reply.Agenda>(say("what are my reminders"))
    }

    @Test fun agendaIncludesTodaysList() {
        say("remind me to call mom at 6 pm")
        say("add buy milk to my to do list")
        val a = assertIs<Reply.Agenda>(say("what do i have today"))
        assertEquals(1, a.items.size); assertEquals(listOf("Buy milk"), a.tasks.map { it.body })
    }

    @Test fun journal() {
        for (s in listOf("today's journal about me", "today's journal", "dear diary", "let me journal", "start my journal")) {
            assertIs<Reply.ListeningForJournal>(say(s), s)
        }
        val listen = assertIs<Reply.ListeningForJournal>(say("today's journal"))
        val saved = assertIs<Reply.JournalShown>(core.assistant.followUp(listen,
            "Today I went to Bhaktapur with Ramesh. We ate juju dhau and I felt really happy. Work was slow. That's it", ctx()))
        assertTrue(saved.saved!!.body.startsWith("Today I went to Bhaktapur"))
        assertTrue(!saved.saved!!.body.contains("That's it"))
        assertEquals("Today I went to Bhaktapur with Ramesh.", saved.day.summary)
        // Next day: "show my journal for yesterday", "what did I do yesterday".
        clock.advanceDays(1.0)
        for (s in listOf("show my journal for yesterday", "what did i do yesterday", "yesterday's journal")) {
            val j = assertIs<Reply.JournalShown>(say(s), s)
            assertEquals(today, j.day.day)
            assertEquals(1, j.day.entries.size)
        }
        assertIs<Reply.JournalShown>(say("show my journal for last monday")).let { assertEquals(LocalDate(2026, 11, 23), it.day.day) }
        // Journal entries are memories: found by search.
        val a = say("when was i in bhaktapur")
        assertTrue(a is Reply.Answer && a.memory.body.contains("Bhaktapur"), "$a")
        // Background summary without the AI changes nothing; days are listed.
        assertEquals(0, core.assistant.summarizeJournals())
        assertEquals(listOf(today), core.journal.days())
    }

    @Test fun inlineJournalAndCancel() {
        val r = assertIs<Reply.JournalShown>(say("dear diary today was a long day at the office"))
        assertTrue(r.saved!!.body.contains("long day"))
        val listen = assertIs<Reply.ListeningForJournal>(say("today's journal"))
        assertIs<Reply.NotSaved>(core.assistant.followUp(listen, "cancel", ctx()))
        assertEquals(1, core.journal.entries(today).size)
    }

    @Test fun backupKeepsListsAndJournal() {
        say("add buy milk to my to do list")
        say("dear diary today I learned to make momos at home")
        val json = core.backup.exportJson()
        val other = newCore(clock)
        other.backup.importJson(json)
        assertEquals(listOf("Buy milk"), other.lists.items(ListKey.day(today)).map { it.body })
        assertEquals(1, other.journal.entries(today).size)
        assertNull(ListText.parseItem("   "))
    }
}
