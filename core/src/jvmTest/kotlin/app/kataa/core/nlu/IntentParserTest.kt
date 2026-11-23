package app.kataa.core.nlu

import app.kataa.core.model.MemoryKind
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Phrase-style tests: how real people talk to a memory app. */
class IntentParserTest {
    private val tz = TimeZone.of("Asia/Kathmandu")
    // Friday 15 Jan 2027, 10:30 local.
    private val now = LocalDateTime(2027, 1, 15, 10, 30).toInstant(tz).toEpochMilliseconds()

    private fun parse(s: String, known: Set<String> = emptySet()) = IntentParser.parse(s, now, tz, known)
    private fun local(t: TimeMatch) = t.fireAt.toLocalDateTime(tz)

    // ---------- Saving ----------

    @Test fun objectLocations() {
        val cases = mapOf(
            "My keys are in the blue drawer" to ("key" to "in the blue drawer"),
            "I put my passport in the top shelf of the wardrobe" to ("passport" to "in the top shelf of the wardrobe"),
            "remember that the charger is under the bed" to ("charger" to "under the bed"),
            "I left my glasses on the kitchen table." to ("glass" to "on the kitchen table"),
            "Kataa, the spare car key is behind the TV" to ("spare car key" to "behind the tv"),
            "parked at level 3 B" to ("car" to "at level 3 b"),
            "I parked on the second floor near the lift" to ("car" to "on the second floor near the lift"),
            "note: the warranty card is inside the laptop box" to ("warranty card" to "inside the laptop box"),
            "we kept the extra blankets in the attic" to ("extra blanket" to "in the attic"),
        )
        for ((text, expected) in cases) {
            val u = parse(text)
            if (u !is Understanding.Save || u.kind != MemoryKind.OBJECT_LOCATION) fail("'$text' -> $u")
            assertEquals(expected.first, u.subject, text)
            assertEquals(expected.second, u.objectLocation!!.location, text)
        }
    }

    @Test fun notesKeepUserWordsAndCase() {
        val u = parse("Remember that Ramesh's wifi password is Sunflower99")
        assertIs<Understanding.Save>(u)
        assertEquals(MemoryKind.NOTE, u.kind)
        assertEquals("Ramesh's wifi password is Sunflower99", u.body)

        val idea = parse("idea: an app that tells you where you parked")
        assertIs<Understanding.Save>(idea)
        assertEquals(MemoryKind.NOTE, idea.kind)
    }

    @Test fun doneLogsAreFactsWithStableKeys() {
        val cases = mapOf(
            "I took my meds" to "take med",
            "I just locked the front door" to "lock front door",
            "fed the cat".let { "I fed the cat" } to "feed cat",
            "I already paid the rent" to "pay rent",
            "I turned off the gas" to "turn off gas",
            "We watered the plants" to "water plant",
        )
        for ((text, key) in cases) {
            val u = parse(text)
            if (u !is Understanding.Save || u.kind != MemoryKind.FACT) fail("'$text' -> $u")
            assertEquals(key, u.subject, text)
        }
    }

    @Test fun tooShortIsUnclear() {
        assertIs<Understanding.Unclear>(parse("keys"))
        assertIs<Understanding.Unclear>(parse("   "))
    }

    // ---------- Questions ----------

    @Test fun whereQuestionsShareKeysWithSaves() {
        val pairs = listOf(
            "where are my keys?" to "key",
            "Where did I put my passport" to "passport",
            "where is the charger" to "charger",
            "where did I leave my glasses again" to "glass",
            "where's the spare car key" to "spare car key",
            "where are the extra blankets" to "extra blanket",
        )
        for ((q, key) in pairs) {
            val u = parse(q)
            if (u !is Understanding.Ask || u.type != QuestionType.WHERE) fail("'$q' -> $u")
            assertEquals(key, u.subject, q)
        }
        listOf("where did I park", "where is my car", "Where did I park the car?").forEach { q ->
            val u = parse(q)
            assertIs<Understanding.Ask>(u, q)
            assertEquals("car", u.subject, q)
        }
    }

    @Test fun didIQuestionsMatchDoneLogs() {
        val cases = mapOf(
            "did I take my meds today?" to ("take med" to 0),
            "Did I lock the front door" to ("lock front door" to 0),
            "have I fed the cat" to ("feed cat" to 0),
            "did I pay the rent yesterday" to ("pay rent" to 1),
        )
        for ((q, exp) in cases) {
            val u = parse(q)
            if (u !is Understanding.Ask || u.type != QuestionType.DID_I) fail("'$q' -> $u")
            assertEquals(exp.first, u.subject, q)
            assertEquals(exp.second, u.daysAgo, q)
        }
    }

    @Test fun generalQuestions() {
        listOf("what is Ramesh's wifi password", "what did the doctor say about my back?", "who recommended that book", "Ramesh wifi?")
            .forEach { assertIs<Understanding.Ask>(parse(it), it) }
        val w = parse("when is my dentist appointment")
        assertIs<Understanding.Ask>(w)
        assertEquals(QuestionType.WHEN, w.type)
    }

    // ---------- Reminders: time ----------

    @Test fun timeReminders() {
        data class C(val text: String, val day: Int, val hour: Int, val minute: Int, val body: String)
        val cases = listOf(
            C("remind me tomorrow at 9 to call mom", 16, 9, 0, "Call mom"),
            C("Remind me to call Mom at 5", 15, 17, 0, "Call Mom"),
            C("remind me in 2 hours to check the oven", 15, 12, 30, "Check the oven"),
            C("remind me in 30 minutes to move the laundry", 15, 11, 0, "Move the laundry"),
            C("tonight remind me to charge the power bank", 15, 20, 0, "Charge the power bank"),
            C("remind me on Monday to submit the assignment", 18, 9, 0, "Submit the assignment"),
            C("remind me at 7:45 am tomorrow to take the bus pass", 16, 7, 45, "Take the bus pass"),
            C("I have to pay the electricity bill tomorrow", 16, 9, 0, "Pay the electricity bill"),
            C("remind me at noon to drink water", 15, 12, 0, "Drink water"),
            C("remind me on the 25th to pay rent", 25, 9, 0, "Pay rent"),
        )
        for (c in cases) {
            val u = parse(c.text)
            if (u !is Understanding.Remind) fail("'${c.text}' -> $u")
            val t = assertNotNull(u.time, c.text)
            val l = local(t)
            assertEquals(c.day, l.day, "day for '${c.text}'")
            assertEquals(c.hour to c.minute, l.hour to l.minute, "time for '${c.text}'")
            assertEquals(c.body, u.body, c.text)
        }
    }

    @Test fun pastTimeTodayRollsToNextOccurrence() {
        val u = parse("remind me at 9 am to stretch") as Understanding.Remind
        val l = local(u.time!!)
        assertEquals(16, l.day) // 9:00 already passed at 10:30
        assertEquals(9, l.hour)
    }

    @Test fun recurringReminders() {
        val cases = mapOf(
            "remind me every day at 8 pm to take my vitamins" to "FREQ=DAILY",
            "every Monday remind me to put out the bins" to "FREQ=WEEKLY;BYDAY=MO",
            "remind me every weekday at 9 to check email" to "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
        )
        for ((text, rrule) in cases) {
            val u = parse(text)
            if (u !is Understanding.Remind) fail("'$text' -> $u")
            assertEquals(rrule, u.time?.rrule, text)
        }
    }

    @Test fun persistentReminders() {
        val u = parse("keep reminding me to take my meds at 8 pm until I do it") as Understanding.Remind
        assertTrue(u.persistent)
        assertEquals("Take my meds", u.body)
        assertTrue(!(parse("remind me at 8 pm to call dad") as Understanding.Remind).persistent)
    }

    // ---------- Reminders: places and moments ----------

    @Test fun storeArrivalReminders() {
        val u = parse("Remember that I have to buy milk in Big Mart")
        if (u !is Understanding.Remind) fail("$u")
        val m = assertIs<MomentMatch.Place>(u.moment)
        assertEquals(PlaceRef.Named("big mart"), m.ref)
        assertEquals("Buy milk", u.body)

        val p = parse("remind me to pick up my prescription at any pharmacy") as Understanding.Remind
        val k = assertIs<PlaceRef.Kind>((p.moment as MomentMatch.Place).ref)
        assertEquals("pharmacy", k.placeType)
        assertEquals("Pick up my prescription", p.body)

        val s = parse("when I reach the supermarket remind me to buy eggs") as Understanding.Remind
        assertEquals("supermarket", ((s.moment as MomentMatch.Place).ref as PlaceRef.Kind).placeType)
        assertEquals("Buy eggs", s.body)
    }

    @Test fun homeAndLeaveReminders() {
        val a = parse("when I get home remind me to call grandma") as Understanding.Remind
        val am = a.moment as MomentMatch.Place
        assertEquals(PlaceRef.Named("home"), am.ref)
        assertTrue(!am.onExit)
        assertEquals("Call grandma", a.body)

        val l = parse("when I leave the office remind me to take the charger") as Understanding.Remind
        val lm = l.moment as MomentMatch.Place
        assertEquals(PlaceRef.Named("office"), lm.ref)
        assertTrue(lm.onExit)

        val b = parse("remind me to take an umbrella before I leave") as Understanding.Remind
        assertTrue((b.moment as MomentMatch.Place).onExit)
        assertEquals("Take an umbrella", b.body)

        val h = parse("remind me to grab the book next time I am here") as Understanding.Remind
        assertEquals(PlaceRef.Here, (h.moment as MomentMatch.Place).ref)
    }

    @Test fun vehicleAndEarbudMoments() {
        val c = parse("remind me to fill petrol when I get in the car") as Understanding.Remind
        val cm = assertIs<MomentMatch.Bluetooth>(c.moment)
        assertEquals("car", cm.deviceAlias)
        assertTrue(!cm.onDisconnect)
        assertEquals("Fill petrol", c.body)

        val o = parse("when I get out of the car remind me to take my bag") as Understanding.Remind
        assertTrue((o.moment as MomentMatch.Bluetooth).onDisconnect)

        val e = parse("remind me to call Sita when I put on my earbuds") as Understanding.Remind
        assertEquals("earbuds", (e.moment as MomentMatch.Bluetooth).deviceAlias)
        assertEquals("Call Sita", e.body)
    }

    @Test fun objectsInsideThingsAreNotPlaces() {
        // "in the drawer" must not become a geofence.
        val u = parse("my keys are in the drawer")
        assertIs<Understanding.Save>(u)
        assertNull(PlaceParser.parse("my keys are in the drawer"))
        assertNull(PlaceParser.parse("the remote is on the sofa"))
    }

    @Test fun knownPlaceNamesWithoutWhenI() {
        val u = parse("I need to drop the parcel at office", known = setOf("office"))
        val m = assertIs<MomentMatch.Place>((u as Understanding.Remind).moment)
        assertEquals(PlaceRef.Named("office"), m.ref)
    }

    @Test fun reminderWithoutTimeOrPlaceStillRemind() {
        val u = parse("remind me to renew my passport")
        assertIs<Understanding.Remind>(u)
        assertNull(u.time)
        assertNull(u.moment)
    }

    // ---------- Fixes, places, categories ----------

    @Test fun correctionsAndUndo() {
        listOf("undo", "forget that", "Scratch that.", "never mind").forEach { assertEquals(Understanding.Undo, parse(it), it) }
        val c = parse("actually, in the car")
        assertIs<Understanding.Correct>(c)
        assertEquals("in the car", c.newText)
        assertIs<Understanding.Correct>(parse("no, it is in the second drawer"))
        // A bare "no" is not a correction.
        assertTrue(parse("no") !is Understanding.Correct)
    }

    @Test fun savingPlaces() {
        val a = parse("this is Big Mart")
        assertIs<Understanding.SavePlace>(a)
        assertEquals("big mart", a.name)
        assertTrue(a.explicit)
        assertEquals("home", (parse("save this place as home") as Understanding.SavePlace).name)
        val soft = parse("I'm at Bhatbhateni")
        assertIs<Understanding.SavePlace>(soft)
        assertTrue(!soft.explicit)
    }

    @Test fun categories() {
        val c = parse("create a category called Recipes")
        assertIs<Understanding.CreateCategory>(c)
        assertEquals("Recipes", c.name)
        val b = parse("show me my education stuff")
        assertIs<Understanding.BrowseCategory>(b)
        assertEquals("education", b.name)
    }

    // ---------- Building blocks ----------

    @Test fun textHelpers() {
        assertEquals("i am at home", Text.normalize("I'm at home!"))
        assertEquals("car key", Text.subjectKey("the car keys"))
        assertEquals("glass", Text.singular("glasses"))
        assertEquals("take", Text.baseVerb("took"))
    }

    @Test fun relativeAndDatedTimes() {
        fun at(s: String) = local(TimeParser.parse(Text.normalize(s), now, tz)!!)
        assertEquals(11, at("in half an hour").hour)
        assertEquals(17, at("day after tomorrow").day)
        assertEquals(16, at("this weekend").day) // Saturday
        assertEquals(22, at("next Friday").day)
        assertEquals(3, at("on 3 March").day)
        assertNull(TimeParser.parse("buy milk", now, tz))
    }
}
