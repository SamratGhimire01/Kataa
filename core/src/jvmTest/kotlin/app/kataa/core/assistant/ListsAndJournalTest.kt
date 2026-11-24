package app.kataa.core.assistant

import app.kataa.core.FakeClock
import app.kataa.core.db.queryLong
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

    /** The bug-hunter's findings of 24 Nov 2026: each must stay fixed. */
    @Test fun reviewFindings() {
        // 1, 2: "write my to do list" listens (also with a date, as the app's button says it).
        assertEquals(ListKey.day(today), assertIs<Reply.ListeningForList>(say("write my to do list")).key)
        assertEquals(ListKey.day(LocalDate(2026, 11, 24)), assertIs<Reply.ListeningForList>(say("write my to do list for 24 november 2026")).key)
        assertEquals("month:2026-11", assertIs<Reply.ListeningForList>(say("write my to do list for november")).key)
        // 3: ordinary sentences with "finished" don't tick tasks.
        say("add buy milk and book bus ticket and call the gas man to my to do list")
        for (s in listOf("the milk is finished", "i finished the book", "the gas is finished")) {
            assertTrue(say(s) !is Reply.TaskListReply, s)
        }
        assertEquals(0, core.lists.items(ListKey.day(today)).count { it.done })
        // 4-8: sentences about lists, goals, the fridge and the diary are notes.
        for (s in listOf("today's list is on the fridge", "my goals for this year are written in the red notebook",
            "the list of documents for visa is passport photo and bank statement", "my tasks are piling up at work",
            "diary is in my bag", "journal is under the bed")) {
            val r = say(s)
            assertTrue(r is Reply.Saved || r is Reply.AlreadySaved, "$s -> $r")
        }
        assertTrue(say("what is left in the fridge") !is Reply.TaskListReply)
        assertEquals(3, core.lists.items(ListKey.day(today)).size)
        // Shopping list with a colon is a list.
        assertEquals("named:shopping", list(say("shopping list: rice, dal and oil")).list.key)
        // 9: dictation keeps the user's words.
        val j = assertIs<Reply.ListeningForJournal>(say("today's journal"))
        val saved = assertIs<Reply.JournalShown>(core.assistant.followUp(j, "Today I had lunch with my friend", ctx()))
        assertEquals("Today I had lunch with my friend", saved.saved!!.body)
        val l = assertIs<Reply.ListeningForList>(say("write my to do list for tomorrow"))
        assertEquals(listOf("Plan the weekend", "Meet my friend"), list(core.assistant.followUp(l, "plan the weekend and meet my friend", ctx())).items.map { it.body })
        val idea = assertIs<Reply.ListeningForIdea>(say("i have an idea"))
        assertIs<Reply.Saved>(core.assistant.followUp(idea, "open a momo shop near the bus stop", ctx())).let { assertTrue(it.memory.body.endsWith("bus stop"), it.memory.body) }
        // 17: a deleted journal entry leaves the day's summary.
        core.memories.trash(saved.saved!!.id)
        core.processPending()
        assertTrue(core.journal.day(today).summary?.contains("lunch") != true)
        // 18: "what happened at the meeting" doesn't offer to remember "your happened at the meeting".
        assertNull(assertIs<Reply.NotFound>(say("what happened at the meeting")).askFor)
        assertNull(assertIs<Reply.NotFound>(say("what is the quickest way to kalanki")).askFor)
    }

    @Test fun reviewFindingsPasswordsAndTimes() {
        fun confirm(s: String) = core.assistant.handle(s, ctx()).let { if (it is Reply.ConfirmSecret) core.assistant.followUp(it, "yes", ctx()) else it }
        confirm("my facebook password is Samrat001")
        // 10, 11: a password is never spoken.
        val again = core.assistant.handle("my facebook password is Samrat001", ctx())
        assertTrue("Samrat001" !in ReplyText.spoken(again), ReplyText.spoken(again))
        assertTrue("Samrat001" !in ReplyText.spoken(say("undo")))
        // 12, 13: not passwords.
        assertTrue(say("remember to change the wifi password tomorrow") !is Reply.ConfirmSecret)
        assertTrue(say("write down the password for the locker in my notebook") !is Reply.ConfirmSecret)
        // 14: a second alarm is its own, and asks AM or PM; a repeated reminder with a new bare time asks too.
        say("set an alarm for 6:30 am")
        assertIs<Reply.PickTime>(say("set an alarm for 7"))
        say("remind me to call mom at 6 pm")
        assertIs<Reply.PickTime>(say("remind me to call mom at 8:15"))
        // 15: "at 7 in the morning" said at 8 AM is tomorrow 7 AM.
        val r = assertIs<Reply.ReminderSet>(say("remind me to call didi at 7 in the morning"))
        assertEquals(LocalDateTime(2026, 11, 25, 7, 0), kotlin.time.Instant.fromEpochMilliseconds(r.reminders.single().fireAt!!).let { kotlinx.datetime.TimeZone.of("Asia/Kathmandu").let { z -> it.toLocalDateTimeIn(z) } })
    }

    /** Second bug-hunt pass. */
    @Test fun reviewFindingsPass2() {
        fun localOf(r: Reply.ReminderSet) = r.reminders.single().fireAt!!.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.of(tz.id)).toLocalDateTime().toString() }
        list(say("today's to do list: buy milk, call didi, pay rent, buy rice, pay electricity bill"))
        // 1: "the second one".
        assertEquals("Call didi", list(say("mark the second one done")).items.single().body)
        assertEquals("Pay rent", list(say("tick the third one")).items.single().body)
        // 2: write / note down on a named list.
        assertEquals(ListKey.named("shopping"), list(say("write rice on my shopping list")).list.key)
        assertEquals(ListKey.named("shopping"), list(say("note down sugar and eggs in my shopping list")).list.key)
        // 3: ordinary sentences are not ticks.
        assertTrue(say("the rice is done") !is Reply.TaskListReply)
        assertTrue(say("i finished the milk") !is Reply.TaskListReply)
        assertTrue(say("i finished the eggs") !is Reply.TaskListReply)
        assertEquals("Pay electricity bill", list(say("i am done with the electricity bill")).items.single().body)
        assertEquals("Buy rice", list(say("buy rice is done")).items.single().body)
        // 4: "left on my shopping list".
        val left = list(say("what's left on my shopping list"))
        assertEquals(ListKey.named("shopping"), left.list.key)
        // 5: tasks / goals for a day or month, inline.
        assertEquals(ListKey.day(LocalDate(2026, 11, 25)), list(say("tomorrow's tasks buy a gift and book a cab")).list.key)
        assertEquals(ListKey.month(2026, 11), list(say("this month's goals are save money and read five books")).list.key)
        // 6.
        assertEquals(ListAction.LEFT, list(say("what do i still have left to do")).action)
        // 7: about the journal itself.
        assertTrue(say("today's journal is in the drawer") !is Reply.JournalShown)
        // 8: "save it" inside a sentence is kept.
        assertIs<Reply.ListeningForJournal>(say("today's journal"))
        val j = assertIs<Reply.JournalShown>(core.assistant.followUp(Reply.ListeningForJournal, "I found an old photo of grandma and decided to save it", ctx()))
        assertTrue(j.saved!!.body.trimEnd('.').endsWith("save it"), j.saved!!.body)
        // 9.
        assertIs<Reply.JournalShown>(say("what did i write today"))
        // 10: morning keeps AM.
        assertEquals("2026-11-25T06:00", localOf(assertIs<Reply.ReminderSet>(say("remind me to call ram at 6 in the morning"))))
        assertEquals("2026-11-25T06:30", localOf(assertIs<Reply.ReminderSet>(say("remind me to call hari at 6:30 in the morning"))))
        // 11-13: not passwords.
        for (s in listOf("change the wifi password tomorrow morning", "update my bank password this friday", "reset the router password after 3 days",
            "write the password for the locker in the red notebook", "the wifi password is written on the router", "the wifi password is stuck on the fridge")) {
            assertTrue(say(s) !is Reply.ConfirmSecret, s)
        }
        // 14: a service named with a verb.
        assertEquals("Apple123", assertIs<Reply.ConfirmSecret>(say("find my iphone password is Apple123")).value)
        // 15: "didi birthday" without the 's.
        assertEquals("your didi birthday", assertIs<Reply.NotFound>(say("when is didi birthday")).askFor)
    }

    /** Third bug-hunt pass. */
    @Test fun reviewFindingsPass3() {
        // 1: typed commas without a space.
        assertEquals(listOf("Buy Milk", "Call Didi"), list(say("add Buy Milk,Call Didi to my to do list")).items.map { it.body })
        say("add pay rent to my to do list")
        say("add eggs and bread to my shopping list")
        // 2: "done with" the milk / eggs means they ran out.
        assertTrue(say("i'm done with the eggs") !is Reply.TaskListReply)
        assertTrue(say("done with milk") !is Reply.TaskListReply)
        // 3, 4, 5.
        assertEquals("Pay rent", list(say("tick the last one")).items.single().body)
        assertEquals("Eggs", list(say("mark eggs as done on my shopping list")).items.single().body)
        assertEquals("Call Didi", list(say("call didi is done today")).items.single().body)
        // 6: no reminder and no junk note.
        assertIs<Reply.ListeningForList>(say("note it on my shopping list"))
        // 7: the part of the day is not left in the reminder.
        val r = assertIs<Reply.ReminderSet>(say("remind me after 3 days at 6 in the morning to pay rent"))
        assertEquals("Pay rent", r.reminders.single().let { core.memories.get(it.memoryId)!!.body.trimEnd('.') })
        // 8-10: real passwords.
        for ((s, v) in listOf("my gmail password is sunday" to "sunday", "my wifi password is hidden dragon" to "hidden dragon",
            "my office wifi password is On The Rocks" to "On The Rocks", "wifi password at home is blue sky 7" to "blue sky 7")) {
            assertEquals(v, assertIs<Reply.ConfirmSecret>(say(s), s).value)
        }
        // 11: not "yours".
        for (s in listOf("what is a dad joke", "when is the baby shower", "what is the boss meeting")) assertNull(assertIs<Reply.NotFound>(say(s), s).askFor, s)
        // 13: a person is not a place.
        assertTrue(say("get the wifi password from didi").let { it !is Reply.ReminderSet || it.reminders.none { r -> r.placeTarget != null } })
    }

    /** Fourth bug-hunt pass. */
    @Test fun reviewFindingsPass4() {
        fun body(r: Reply) = core.memories.get(assertIs<Reply.ReminderSet>(r, "$r").reminders.single().memoryId)!!.body.trimEnd('.')
        // 1.
        assertEquals("Call didi", body(say("remind me tomorrow to call didi in the morning")))
        assertEquals("Water the plants", body(say("remind me after 2 days to water the plants in the evening")))
        // 2, 3.
        assertIs<Reply.ListeningForList>(say("note it down on my shopping list"))
        assertEquals(ListKey.named("grocery"), assertIs<Reply.ListeningForList>(say("write this on my grocery list")).key)
        // 4: where it is, not what it is.
        for (s in listOf("the locker password is with didi", "the wifi password is in dad's phone", "the wifi password is at home", "my netflix password is next to the tv")) {
            assertTrue(say(s) !is Reply.ConfirmSecret, s)
        }
        // 5: a complaint never replaces the saved password.
        val saved = core.assistant.handle("my wifi password is Sunflower123", ctx().copy(source = app.kataa.core.model.Source.TEXT))
        if (saved is Reply.ConfirmSecret) core.assistant.followUp(saved, "yes", ctx())
        for (s in listOf("my wifi password is not working", "my wifi password is wrong", "the wifi password is changed")) {
            val r = core.assistant.handle(s, ctx().copy(source = app.kataa.core.model.Source.TEXT))
            assertTrue(r !is Reply.ConfirmSecret && r !is Reply.Replaced, "$s -> $r")
        }
        assertEquals(1L, core.db.queryLong("SELECT count(*) FROM memories WHERE body LIKE '%Sunflower123%' AND deleted_at IS NULL"))
        // 6.
        list(say("add do the laundry and finish homework to my to do list"))
        assertEquals("Do the laundry", list(say("i'm done with the laundry")).items.single().body)
        assertEquals("Finish homework", list(say("done with homework")).items.single().body)
        // 7: people are not places.
        for (s in listOf("remind me to get money from my dad", "remind me to get the book from my friend", "remind me to give the keys when i go to didi")) {
            val r = say(s)
            assertTrue(r !is Reply.ReminderSet || r.reminders.none { it.placeTarget != null }, "$s -> $r")
        }
        // 8: days for everyone.
        for (s in listOf("when is mother's day", "what is valentine's day")) assertNull(assertIs<Reply.NotFound>(say(s), s).askFor, s)
    }

    /** Fifth bug-hunt pass. */
    @Test fun reviewFindingsPass5() {
        fun set(r: Reply) = assertIs<Reply.ReminderSet>(r, "$r").reminders.single()
        fun body(r: Reply) = core.memories.get(set(r).memoryId)!!.body.trimEnd('.')
        fun local(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.of(tz.id)).toLocalDateTime().toString()
        // 1: words ending in "ok" are kept.
        assertEquals("Read my book", body(say("remind me to read my book at 6 pm")))
        assertEquals("Post on facebook", body(say("remind me at 5 pm to post on facebook")))
        // 2: a repeating wake-up keeps the morning; a plain repeating bare hour asks.
        val wake = set(say("wake me up at 5 every day"))
        assertEquals("FREQ=DAILY", wake.rrule); assertTrue(local(wake.fireAt!!).endsWith("T05:00"), local(wake.fireAt!!))
        val p = assertIs<Reply.PickTime>(say("remind me every day at 6 to drink water"))
        val chosen = set(core.assistant.pickTimeOption(p, p.options.first { local(it.atMs).endsWith("T06:00") }, ctx()))
        assertEquals("FREQ=DAILY", chosen.rrule)
        // 3: a day of the month.
        val bill = assertIs<Reply.ReminderSet>(say("remind me to pay the electricity bill on the 5th of every month"))
        assertEquals("Pay the electricity bill", body(bill)); assertEquals("FREQ=MONTHLY;BYMONTHDAY=5", set(bill).rrule)
        assertTrue(local(set(bill).fireAt!!).startsWith("2026-12-05"), local(set(bill).fireAt!!))
        // 4: several days; every year.
        val yoga = assertIs<Reply.ReminderSet>(say("remind me every tuesday and thursday at 5 pm to go to yoga"))
        assertEquals("FREQ=WEEKLY;BYDAY=TU,TH", set(yoga).rrule); assertEquals("Go to yoga", body(yoga))
        val ram = assertIs<Reply.ReminderSet>(say("remind me every year on march 3 to wish ram"))
        assertEquals("FREQ=YEARLY", set(ram).rrule); assertEquals("Wish ram", body(ram))
        // 5, 6: a changed password and real passphrases.
        assertEquals("moonlight", assertIs<Reply.ConfirmSecret>(say("my netflix password is changed to moonlight")).value)
        assertEquals("Moonlight22", assertIs<Reply.ConfirmSecret>(say("my wifi password is now Moonlight22")).value)
        for (v in listOf("easy peasy", "correct horse battery staple", "still waters", "lost in space")) {
            assertEquals(v, assertIs<Reply.ConfirmSecret>(say("my instagram password is $v")).value)
        }
        assertTrue(say("my wifi password is too long") !is Reply.ConfirmSecret)
        // 7: "on"/"in" inside an item.
        assertEquals(listOf("Work on the report", "Start the project"), list(say("add work on the report and start the project to my to do list")).items.map { it.body })
        assertEquals("Go to the bank", list(say("add go to the bank to my list")).items.single().body)
    }

    /** Sixth bug-hunt pass. Said on Tuesday 24 Nov 2026 at 08:00. */
    @Test fun reviewFindingsPass6() {
        fun set(r: Reply) = assertIs<Reply.ReminderSet>(r, "$r").reminders.single()
        fun body(r: Reply) = core.memories.get(set(r).memoryId)!!.body.trimEnd('.')
        fun local(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.of(tz.id)).toLocalDateTime().toString()
        // 1: complaints never replace the password, typed or spoken.
        val typed = ctx().copy(source = app.kataa.core.model.Source.TEXT)
        core.assistant.handle("my wifi password is Sunflower123", typed).let { if (it is Reply.ConfirmSecret) core.assistant.followUp(it, "yes", ctx()) }
        for (s in listOf("my wifi password is not working since yesterday", "my wifi password is still not working", "my wifi password is wrong again", "my wifi password is too long to type", "my wifi password is now fixed")) {
            val r = core.assistant.handle(s, typed)
            assertTrue(r !is Reply.ConfirmSecret && r !is Reply.Replaced, "$s -> $r")
            assertTrue(say(s) !is Reply.ConfirmSecret, s)
        }
        // 2, 3: the next matching slot.
        assertEquals("2026-11-26T07:00", local(set(say("remind me every tuesday and thursday at 7 am to go to the gym")).fireAt!!))
        assertEquals("2026-11-25T07:00", local(set(say("remind me every weekday at 7 am to leave")).fireAt!!))
        assertEquals("2027-11-24T07:00", local(set(say("remind me every year on november 24 at 7 am to renew insurance")).fireAt!!))
        // 4.
        val run = say("remind me every mon, wed and fri at 6 pm to run")
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", set(run).rrule); assertEquals("Run", body(run))
        // 5: the 31st stays the 31st.
        val salary = set(say("remind me on the 31st of every month to pay salary"))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=31", salary.rrule)
        var at = salary.fireAt!!
        val seen = (1..4).map { at = app.kataa.core.triggers.Recurrence.next(at, salary.rrule!!, tz)!!; local(at).take(10) }
        assertEquals(listOf("2026-12-31", "2027-01-31", "2027-02-28", "2027-03-31"), seen)
        // 6: "the 2 tablets" is not a day.
        val tabs = say("remind me every month to take the 2 tablets")
        assertEquals("FREQ=MONTHLY", set(tabs).rrule); assertTrue(body(tabs).contains("2 tablets"), body(tabs))
        // 7, 8: repeating bare hours offer the right slots.
        val alarm = assertIs<Reply.PickTime>(say("set an alarm for 6 every weekday"))
        assertEquals(setOf("2026-11-25T06:00", "2026-11-24T18:00"), alarm.options.map { local(it.atMs) }.toSet())
        val med = assertIs<Reply.PickTime>(say("remind me every day at 7 to take medicine"))
        assertEquals(setOf("2026-11-25T07:00", "2026-11-24T19:00"), med.options.map { local(it.atMs) }.toSet())
        // 9: a new hour wins, and keeps the repeat.
        val eight = set(core.assistant.followUp(med, "at 8 pm", ctx()))
        assertEquals("2026-11-24T20:00", local(eight.fireAt!!)); assertEquals("FREQ=DAILY", eight.rrule)
    }

    /** 16: a database made by an earlier v2 build (journal_days without entry_count) is upgraded, and the journal works. */
    @Test fun upgradesV2Journal() {
        val path = java.nio.file.Files.createTempDirectory("kataa").resolve("t.db").toString()
        newCore(clock, path = path).use { c ->
            c.db.execute("DROP TABLE journal_days")
            c.db.execute("CREATE TABLE journal_days (day TEXT PRIMARY KEY, summary TEXT, summary_by_ai INTEGER NOT NULL DEFAULT 0, summarized_at INTEGER, changed_at INTEGER NOT NULL) WITHOUT ROWID")
            c.db.execute("PRAGMA user_version = 2")
        }
        newCore(clock, path = path).use { c ->
            assertIs<Reply.JournalShown>(c.assistant.followUp(Reply.ListeningForJournal, "A calm day at home with tea", ctx()))
            assertEquals(1, c.journal.day(today).entries.size)
            c.journal.setSummary(today, "AI summary", byAi = true)
            c.db.execute("UPDATE journal_days SET entry_count = 0")
            c.db.execute("PRAGMA user_version = 2")
        }
        // Upgrading keeps the AI's summary (the count is filled in, so nothing looks changed).
        newCore(clock, path = path).use { c ->
            c.db.execute("ALTER TABLE journal_days DROP COLUMN entry_count")
            c.db.execute("PRAGMA user_version = 2")
        }
        newCore(clock, path = path).use { c ->
            c.processPending()
            assertEquals("AI summary", c.journal.day(today).summary)
        }
        newCore(clock, path = path).use { c -> assertEquals(1, c.journal.day(today).entries.size) }
    }

    private fun kotlin.time.Instant.toLocalDateTimeIn(z: TimeZone) = kotlinx.datetime.Instant.fromEpochMilliseconds(toEpochMilliseconds()).let { kotlinx.datetime.TimeZone.of(z.id); it }.let { i -> kotlinx.datetime.LocalDateTime.parse(java.time.Instant.ofEpochMilli(toEpochMilliseconds()).atZone(java.time.ZoneId.of(z.id)).toLocalDateTime().toString()) }
}
