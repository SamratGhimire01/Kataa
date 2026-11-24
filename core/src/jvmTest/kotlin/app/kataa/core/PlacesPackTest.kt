package app.kataa.core

import app.kataa.core.assistant.AssistantContext
import app.kataa.core.assistant.Reply
import app.kataa.core.model.GeoPoint
import app.kataa.core.places.GeofencePlanner
import kotlinx.datetime.TimeZone
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The offline map of Nepal's shops (app asset, from OpenStreetMap) with real chains: Big Mart, Bhat-Bhateni. */
class PlacesPackTest {
    private val pack = File("../app/src/main/assets/places/nepal.tsv.gz")
    private val tz = TimeZone.of("Asia/Kathmandu")
    private val baluwatar = GeoPoint(27.7237, 85.3312, 15.0)        // next to "Big Mart Baluwatar"
    private val bhatbhateniStore = GeoPoint(27.71860, 85.33115, 10.0) // "Bhat-Bhateni Super Market"

    private fun loaded(): KataaCore? {
        if (!pack.exists()) { println("SKIP no places pack"); return null }
        val core = newCore(FakeClock(1_795_000_000_000))
        val t0 = System.nanoTime()
        val n = GZIPInputStream(pack.inputStream()).bufferedReader().useLines { core.places.importPlacesPack("test-1", it) }
        println("PACK imported %d places in %d ms".format(n, (System.nanoTime() - t0) / 1_000_000))
        assertTrue(n > 50_000)
        assertEquals(0, GZIPInputStream(pack.inputStream()).bufferedReader().useLines { core.places.importPlacesPack("test-1", it) })
        return core
    }

    @Test fun chainReminderWatchesNearbyBranchesWithoutSetup() {
        val core = loaded() ?: return
        fun say(s: String, at: GeoPoint? = null) = core.assistant.handle(s, AssistantContext(core.clock.nowMs(), tz, location = at))

        val r = say("remind me to buy milk at Big Mart")
        assertIs<Reply.ReminderSet>(r)
        assertNull(r.hint, "Big Mart is on the map: no 'teach me' hint")

        val t0 = System.nanoTime()
        val plan = core.geofences.plan(baluwatar.lat, baluwatar.lng)
        println("PACK geofence plan %d ms, %d fences".format((System.nanoTime() - t0) / 1_000_000, plan.active.size))
        val fences = plan.active.filter { it.requestId != GeofencePlanner.REFRESH_ID }
        assertTrue(fences.size >= 10, "many Big Mart branches around Kathmandu: ${fences.size}")
        val labels = fences.mapNotNull { f -> f.placeId?.let { core.places.get(it)?.label } }
        assertTrue(labels.all { it.replace(" ", "").contains("bigmart", ignoreCase = true) }, "$labels")
        assertTrue(labels.any { it == "Big Mart Baluwatar" })

        // Walking into the Baluwatar branch: that reminder is due there.
        val branch = core.places.nearest(baluwatar.lat, baluwatar.lng, brand = "big mart", maxDistanceM = 300.0, limit = 1).single().first
        assertEquals(1, core.reminders.matchingPlace(branch).size)
    }

    @Test fun bhatbhateniMeansTheShopsNotTheTemple() {
        val core = loaded() ?: return
        core.assistant.handle("don't let me forget eggs when I'm at Bhatbhateni", AssistantContext(core.clock.nowMs(), tz))
        val labels = core.geofences.plan(bhatbhateniStore.lat, bhatbhateniStore.lng).active
            .mapNotNull { f -> f.placeId?.let { core.places.get(it)?.label } }
        assertTrue(labels.isNotEmpty())
        assertTrue(labels.none { it.contains("Temple", true) || it.contains("School", true) }, "$labels")
    }

    @Test fun imAtBigMartUsesTheMap() {
        val core = loaded() ?: return
        fun say(s: String, at: GeoPoint? = null) = core.assistant.handle(s, AssistantContext(core.clock.nowMs(), tz, location = at))
        say("remind me to buy milk at Big Mart")
        val before = core.places.userPlaces().size
        val r = say("I'm at Big Mart", baluwatar)
        assertIs<Reply.PlaceSaved>(r)
        assertEquals("Big Mart Baluwatar", r.place.label)
        assertEquals(1, r.errandsHere.size, "tells you about the milk")
        assertEquals(before, core.places.userPlaces().size, "no duplicate place learned")
        // Without a location fix it asks for location, not to "save the place".
        assertIs<Reply.NeedsLocation>(say("I'm at Big Mart"))
    }

    @Test fun anyPharmacyUsesTheMap() {
        val core = loaded() ?: return
        core.assistant.handle("remind me to buy paracetamol at any pharmacy", AssistantContext(core.clock.nowMs(), tz))
        val n = core.geofences.plan(baluwatar.lat, baluwatar.lng).active.count { it.requestId != GeofencePlanner.REFRESH_ID }
        assertTrue(n >= 20, "pharmacies near Baluwatar: $n")
    }
}
