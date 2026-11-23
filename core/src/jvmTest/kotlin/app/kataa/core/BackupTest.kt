package app.kataa.core

import app.kataa.core.model.MemoryEdit
import app.kataa.core.model.MemoryKind
import app.kataa.core.model.NewMemory
import app.kataa.core.model.PlaceTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupTest {
    @Test
    fun roundTripIntoFreshDevice() {
        val clock = FakeClock()
        val json = newCore(clock).use { c ->
            val old = c.memories.save(NewMemory("keys on the counter", MemoryKind.OBJECT_LOCATION, subject = "keys"))
            clock.advanceDays(1.0)
            val new = c.memories.save(NewMemory("keys in the drawer", MemoryKind.OBJECT_LOCATION, subject = "keys"))
            val exam = c.memories.save(NewMemory("Physics exam Friday"))
            val trashed = c.memories.save(NewMemory("old note"))
            c.memories.trash(trashed.id)
            val home = c.places.save("home", 27.71, 85.31)
            c.reminders.addPlace(exam.id, PlaceTarget.Specific(home.id), onExit = true)
            c.reminders.addPlace(new.id, PlaceTarget.Brand("Big Mart"))
            c.processPending()
            c.categories.move(exam.id, c.categories.create("School stuff").id)
            assertTrue(old.id != new.id)
            c.backup.exportJson()
        }

        newCore(FakeClock()).use { d ->
            val report = d.backup.importJson(json)
            assertEquals(4, report.memoriesAdded)
            assertEquals(2, report.remindersAdded)
            assertEquals(1, report.categoriesAdded)
            d.processPending()

            val history = d.memories.historyFor("keys")!!
            assertEquals("keys in the drawer", history.latest.body)
            assertEquals(history.previous.single().id, history.latest.supersedesId)
            val exam = d.search.search("physics exam").hits.first().memory
            assertEquals(listOf("school-stuff"), d.categories.assignmentsFor(exam.id).map { it.slug })
            assertEquals(1, d.memories.trashed().size)
            assertEquals("home", d.places.userPlaces().single().label)

            // Importing again changes nothing.
            val again = d.backup.importJson(json)
            assertEquals(0, again.memoriesAdded + again.memoriesUpdated + again.remindersAdded + again.placesAdded + again.categoriesAdded)
        }
    }

    @Test
    fun newerEditWinsOnMerge() {
        val clock = FakeClock()
        newCore(clock).use { c ->
            val m = c.memories.save(NewMemory("dentist on Monday"))
            val snapshot = c.backup.exportJson()
            clock.advanceDays(1.0)
            c.memories.update(m.id, MemoryEdit(body = "dentist moved to Tuesday"))
            val report = c.backup.importJson(snapshot) // older copy must not overwrite the edit
            assertEquals(1, report.memoriesSkipped)
            assertEquals("dentist moved to Tuesday", c.memories.get(m.id)!!.body)
        }
    }
}
