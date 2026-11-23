package app.kataa.core

import app.kataa.core.model.MemoryKind
import app.kataa.core.model.NewMemory
import app.kataa.core.model.PlaceOrigin
import app.kataa.core.model.PlaceTarget
import app.kataa.core.model.Poi
import app.kataa.core.model.ReminderStatus
import app.kataa.core.places.GeofencePlanner
import app.kataa.core.places.PlaceRepository
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlacesAndRemindersTest {
    private val ktmLat = 27.7000
    private val ktmLng = 85.3200

    private fun KataaCore.seedStores(bigMarts: Int = 150, pharmacies: Int = 40) {
        val r = Random(7)
        places.importPoiPack(
            (0 until bigMarts).map { Poi("osm:bm$it", "Big Mart", "Big Mart", "supermarket", ktmLat + r.nextDouble(-0.08, 0.08), ktmLng + r.nextDouble(-0.08, 0.08)) } +
                (0 until pharmacies).map { Poi("osm:ph$it", "Pharmacy", null, "pharmacy", ktmLat + r.nextDouble(-0.08, 0.08), ktmLng + r.nextDouble(-0.08, 0.08)) },
        )
    }

    @Test
    fun brandNormalization() {
        assertEquals("bigmart", PlaceRepository.normalizeBrand("Big Mart"))
        assertEquals("bigmart", PlaceRepository.normalizeBrand("BigMart"))
        assertEquals("bigmart", PlaceRepository.normalizeBrand(" big-mart "))
    }

    @Test
    fun watchesNearest99OfA150BranchChainPlusRefreshFence() = newCore().use { c ->
        c.seedStores()
        val home = c.places.save("home", 27.71, 85.31)
        val milk = c.memories.save(NewMemory("Buy milk at Big Mart", MemoryKind.REMINDER))
        c.reminders.addPlace(milk.id, PlaceTarget.Brand("big mart"))
        val charger = c.memories.save(NewMemory("Take the charger", MemoryKind.REMINDER))
        c.reminders.addPlace(charger.id, PlaceTarget.Specific(home.id), onExit = true)

        val plan = c.geofences.plan(ktmLat, ktmLng)
        val placeFences = plan.active.filter { it.requestId != GeofencePlanner.REFRESH_ID }
        assertEquals(99, placeFences.size)
        assertTrue(placeFences.any { it.placeId == home.id })
        // No pharmacy reminder, so no pharmacy is watched.
        val watched = placeFences.map { c.places.get(it.placeId!!)!! }
        assertTrue(watched.all { it.brand == "bigmart" || it.id == home.id })
        assertEquals(1, plan.active.count { it.requestId == GeofencePlanner.REFRESH_ID })
        assertEquals(100, plan.add.size)

        // Same position again: nothing to change with the OS.
        val again = c.geofences.plan(ktmLat, ktmLng)
        assertTrue(again.add.isEmpty() && again.remove.isEmpty())

        // Errand done: only home stays watched; the Big Mart fences are removed.
        c.reminders.forMemory(milk.id).forEach { c.reminders.markDone(it.id) }
        val after = c.geofences.plan(ktmLat, ktmLng)
        assertEquals(listOf(home.id), after.active.mapNotNull { it.placeId })
        assertEquals(98, after.remove.count { it != GeofencePlanner.REFRESH_ID }) // refresh fence is resized too
    }

    @Test
    fun arrivingAtAnyBranchShowsAllMatchingErrands() = newCore().use { c ->
        c.seedStores(bigMarts = 3, pharmacies = 1)
        val milk = c.memories.save(NewMemory("Buy milk", MemoryKind.REMINDER))
        val eggs = c.memories.save(NewMemory("Buy eggs", MemoryKind.REMINDER))
        val meds = c.memories.save(NewMemory("Pick up cough syrup", MemoryKind.REMINDER))
        c.reminders.addPlace(milk.id, PlaceTarget.Brand("BigMart"))
        c.reminders.addPlace(eggs.id, PlaceTarget.Type("supermarket"))
        c.reminders.addPlace(meds.id, PlaceTarget.Type("pharmacy"))
        val branch = c.places.nearest(ktmLat, ktmLng, brand = "big mart", limit = 1).single().first
        assertEquals(setOf(milk.id, eggs.id), c.reminders.matchingPlace(branch).map { it.memoryId }.toSet())
    }

    @Test
    fun learnsBranchesWithoutDuplicates() = newCore().use { c ->
        val a = c.places.learnBranch("Big Mart", "Big Mart", 27.7172, 85.3240)
        val b = c.places.learnBranch("big mart", "Big Mart", 27.71725, 85.32405) // ~6 m away
        val far = c.places.learnBranch("Big Mart", "Big Mart", 27.6800, 85.3100)
        assertEquals(a.id, b.id)
        assertTrue(far.id != a.id)
        assertEquals(PlaceOrigin.LEARNED, far.origin)
    }

    @Test
    fun reminderTargetMustBeExactlyOne() {
        newCore().use { c ->
            val m = c.memories.save(NewMemory("x"))
            assertFailsWith<Exception> {
                c.db.execute("INSERT INTO reminders(memory_id, type, place_brand, place_type, created_at) VALUES (?, 'PLACE_ENTER', 'a', 'b', 0)", m.id)
            }
        }
    }

    @Test
    fun nagUntilDoneComesBack() {
        val clock = FakeClock()
        newCore(clock).use { c ->
            val m = c.memories.save(NewMemory("Pay electricity bill", MemoryKind.REMINDER))
            val normal = c.reminders.addTime(m.id, clock.now + 60_000)
            val nag = c.reminders.addTime(m.id, clock.now + 60_000, persistent = true)
            clock.now += 61_000
            assertEquals(setOf(normal.id, nag.id), c.reminders.dueTimeReminders().map { it.id }.toSet())
            c.reminders.markFired(normal.id)
            c.reminders.markFired(nag.id)
            assertEquals(ReminderStatus.FIRED, c.reminders.get(normal.id)!!.status)
            assertEquals(emptyList(), c.reminders.dueTimeReminders())
            clock.now += 16 * 60_000
            assertEquals(listOf(nag.id), c.reminders.dueTimeReminders().map { it.id })
            c.reminders.markDone(nag.id)
            clock.now += 60 * 60_000
            assertEquals(emptyList(), c.reminders.dueTimeReminders())
        }
    }

    @Test
    fun bluetoothMoments() = newCore().use { c ->
        val m = c.memories.save(NewMemory("Fill petrol", MemoryKind.REMINDER))
        c.reminders.addBluetooth(m.id, "Car Audio")
        assertEquals(1, c.reminders.matchingBluetooth("car audio", connected = true).size)
        assertEquals(0, c.reminders.matchingBluetooth("car audio", connected = false).size)
    }
}
