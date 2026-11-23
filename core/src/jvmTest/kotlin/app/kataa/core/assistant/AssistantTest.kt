package app.kataa.core.assistant

import app.kataa.core.FakeClock
import app.kataa.core.model.GeoPoint
import app.kataa.core.model.MemoryKind
import app.kataa.core.model.PlaceTarget
import app.kataa.core.model.ReminderType
import app.kataa.core.newCore
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Whole conversations, as the app would run them. */
class AssistantTest {
    private val tz = TimeZone.of("Asia/Kathmandu")
    private val clock = FakeClock(LocalDateTime(2027, 1, 15, 10, 30).toInstant(tz).toEpochMilliseconds())
    private val core = newCore(clock)
    private val bigMartBranch = GeoPoint(27.6915, 85.3420)
    private val home = GeoPoint(27.7000, 85.3000)

    private fun say(s: String, at: GeoPoint? = null, unlocked: Boolean = false) =
        core.assistant.handle(s, AssistantContext(clock.now, tz, at, unlocked))

    private fun minutes(n: Int) { clock.now += n * 60_000L }

    @Test fun whereAreMyKeys() {
        assertIs<Reply.Saved>(say("My keys are in the blue drawer"))
        minutes(60)
        val a = say("where are my keys?")
        assertIs<Reply.Answer>(a)
        assertEquals("In the blue drawer", a.shortAnswer)

        // Latest wins, and the old answer stays visible as history.
        minutes(60)
        say("I put my keys on the shoe rack")
        val b = say("where did I leave my keys")
        assertIs<Reply.Answer>(b)
        assertEquals("On the shoe rack", b.shortAnswer)
        assertEquals(1, b.previous.size)
    }

    @Test fun parkingRemembersTheSpot() {
        val s = say("parked at level 3 B", at = GeoPoint(27.70, 85.31))
        assertIs<Reply.Saved>(s)
        assertEquals(27.70, s.memory.capturedAt!!.lat)
        assertEquals("At level 3 b", (say("where did I park?") as Reply.Answer).shortAnswer)
    }

    @Test fun didITakeMyMeds() {
        assertEquals(false, (say("did I take my meds today?") as Reply.DidIAnswer).done)
        say("I took my meds")
        minutes(120)
        val r = say("did I take my meds today?")
        assertIs<Reply.DidIAnswer>(r)
        assertTrue(r.done)
        // Next day it is a new question.
        clock.advanceDays(1.0)
        assertEquals(false, (say("did I take my meds today") as Reply.DidIAnswer).done)
        assertEquals(true, (say("did I take my meds yesterday") as Reply.DidIAnswer).done)
    }

    @Test fun bigMartFlow() {
        // 1. The errand: saved as a brand reminder, fires at any Big Mart Kataa knows.
        val r = say("Remember that I have to buy milk in Big Mart")
        assertIs<Reply.ReminderSet>(r)
        assertEquals("Buy milk", r.memory.body)
        assertEquals(MemoryKind.REMINDER, r.memory.kind)
        val target = r.reminders.single().placeTarget
        assertIs<PlaceTarget.Brand>(target)
        assertEquals("When you arrive at any Big Mart", r.description)

        // 2. At the store once: "this is Big Mart" teaches the branch and shows the errand right away.
        val p = say("this is Big Mart", at = bigMartBranch)
        assertIs<Reply.PlaceSaved>(p)
        assertEquals("bigmart", p.place.brand)
        assertEquals(listOf(r.reminders.single().id), p.errandsHere.map { it.id })

        // 3. The geofence planner now watches that branch.
        val plan = core.geofences.plan(home.lat, home.lng)
        assertTrue(plan.active.any { it.placeId == p.place.id })
    }

    @Test fun softPlaceSaveOnlyWhenItMatters() {
        // "I'm at Bhatbhateni" with nothing pending is just a note, not a place.
        assertIs<Reply.Saved>(say("I'm at Bhatbhateni", at = bigMartBranch))
        assertIs<Reply.ReminderSet>(say("I need to buy rice at Bhatbhateni"))
        assertIs<Reply.PlaceSaved>(say("I'm at Bhatbhateni", at = bigMartBranch))
    }

    @Test fun homeNeedsToBeSetOnce() {
        val r = say("when I get home remind me to call grandma")
        assertIs<Reply.NeedsPlace>(r)
        assertEquals("home", r.placeName)
        val saved = say("save this place as home", at = home)
        assertIs<Reply.PlaceSaved>(saved)
        val again = say("when I leave home remind me to take the charger")
        assertIs<Reply.ReminderSet>(again)
        val rem = again.reminders.single()
        assertEquals(ReminderType.PLACE_EXIT, rem.type)
        assertEquals(PlaceTarget.Specific(saved.place.id), rem.placeTarget)
        assertEquals("When you leave home", again.description)
    }

    @Test fun timeRemindersDescribeThemselves() {
        val r = say("remind me tomorrow at 9 to call mom")
        assertIs<Reply.ReminderSet>(r)
        assertEquals("Tomorrow at 9:00 AM", r.description)
        assertEquals("In 30 minutes", (say("remind me in 30 minutes to move the laundry") as Reply.ReminderSet).description)
        assertEquals("Every Monday at 9:00 AM", (say("every Monday remind me to put out the bins") as Reply.ReminderSet).description)
        assertEquals("When you get in the car", (say("remind me to fill petrol when I get in the car") as Reply.ReminderSet).description)
        assertIs<Reply.NeedsTime>(say("remind me to renew my passport"))
        assertIs<Reply.NeedsLocation>(say("remind me to grab the book next time I am here"))
        assertIs<Reply.ReminderSet>(say("remind me to grab the book next time I am here", at = home))
    }

    @Test fun anyPharmacyUsesPlaceType() {
        val r = say("remind me to pick up my prescription at any pharmacy") as Reply.ReminderSet
        assertEquals(PlaceTarget.Type("pharmacy"), r.reminders.single().placeTarget)
        assertEquals("When you arrive at any pharmacy", r.description)
    }

    @Test fun correctAndUndo() {
        say("my passport is in the suitcase")
        val c = say("actually, in the cupboard")
        assertIs<Reply.Corrected>(c)
        assertEquals("My passport is in the cupboard", c.after.body)
        assertEquals("In the cupboard", (say("where is my passport") as Reply.Answer).shortAnswer)

        val rem = say("remind me tomorrow at 9 to call the bank") as Reply.ReminderSet
        val moved = say("no, at 6 pm")
        assertIs<Reply.ReminderSet>(moved)
        assertEquals("Today at 6:00 PM", moved.description)
        assertEquals(1, core.reminders.forMemory(rem.memory.id).count { it.status.name == "SCHEDULED" })

        val u = say("undo")
        assertIs<Reply.Undone>(u)
        assertEquals("Call the bank", u.memory.body)
        assertTrue(core.reminders.forMemory(rem.memory.id).none { it.status.name == "SCHEDULED" })
        assertEquals(Reply.NothingToUndo, say("undo"))
    }

    @Test fun searchAnswersAndHonestNotFound() {
        say("Remember that Ramesh's wifi password is Sunflower99")
        say("The plumber's number is 98410 12345")
        core.processPending()
        // Passwords go to Private: locked, Kataa says it knows but needs a fingerprint.
        assertIs<Reply.NeedsUnlock>(say("what is Ramesh's wifi password?"))
        val a = say("what is Ramesh's wifi password?", unlocked = true)
        assertIs<Reply.Answer>(a)
        assertTrue(a.memory.body.contains("Sunflower99"))
        assertIs<Reply.NotFound>(say("what is my blood group?"))
        assertTrue((say("what is the plumber's number") as Reply.Answer).memory.body.contains("98410"))
    }

    @Test fun categoriesByVoice() {
        val c = say("create a category called Recipes")
        assertIs<Reply.CategoryCreated>(c)
        assertEquals("Recipes", c.category.name)
        say("My exam is on the 20th in room 4")
        core.processPending()
        val l = say("show me my education stuff")
        assertIs<Reply.CategoryList>(l)
        assertTrue(l.memories.any { it.body.contains("exam") })
    }

    @Test fun privateStaysLockedByVoice() {
        say("my bank PIN is 4321")
        core.processPending()
        val locked = say("show me my private stuff")
        assertIs<Reply.CategoryList>(locked)
        assertTrue(locked.memories.isEmpty())
        assertTrue((say("show me my private stuff", unlocked = true) as Reply.CategoryList).memories.isNotEmpty())
    }

    @Test fun handlingIsFast() {
        repeat(200) { say("my item $it is in box $it") }
        val t0 = System.nanoTime()
        repeat(200) { say("where is my item $it") }
        val perCallMs = (System.nanoTime() - t0) / 1e6 / 200
        println("BENCH assistant where-question: ${"%.2f".format(perCallMs)} ms/call")
        assertTrue(perCallMs < 20, "too slow: $perCallMs ms")
    }
}
