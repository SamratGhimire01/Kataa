package app.kataa.core

import app.kataa.core.model.MemoryKind
import app.kataa.core.model.NewMemory
import app.kataa.core.model.PlaceTarget
import app.kataa.core.model.ReminderStatus
import app.kataa.core.search.FtsQuery
import app.kataa.core.search.SearchOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryAndSearchTest {
    @Test
    fun ftsQueryDropsSyntaxAndStopwords() {
        assertEquals("\"keys\"", FtsQuery.build("Where's my keys?"))
        assertEquals("\"wifi\" OR \"password\" OR \"office\"", FtsQuery.build("what is the WiFi password for the office"))
        assertNull(FtsQuery.build("where is it?"))
    }

    @Test
    fun latestWinsForSameSubject() {
        val clock = FakeClock()
        newCore(clock).use { c ->
            val old = c.memories.save(NewMemory("keys are on the kitchen counter", MemoryKind.OBJECT_LOCATION, subject = "Keys"))
            clock.advanceDays(2.0)
            val new = c.memories.save(NewMemory("keys are in the blue drawer", MemoryKind.OBJECT_LOCATION, subject = "keys"))
            assertEquals(old.id, new.supersedesId)
            c.processPending()

            val history = c.memories.historyFor("KEYS")!!
            assertEquals(new.id, history.latest.id)
            assertEquals(listOf(old.id), history.previous.map { it.id })

            val r = c.search.search("where are my keys")
            assertTrue(r.confident)
            assertEquals(new.id, r.hits.first().memory.id)
            assertTrue(r.hits.first { it.memory.id == old.id }.superseded)

            // Trashing the newest makes the previous one the answer again.
            c.memories.trash(new.id)
            assertEquals(old.id, c.memories.historyFor("keys")!!.latest.id)
            assertEquals(old.id, c.search.search("where are my keys").hits.first().memory.id)
        }
    }

    @Test
    fun meaningMatchWithoutSharedWords() = newCore().use { c ->
        val exam = c.memories.save(NewMemory("Physics midterm is on Friday in room 204"))
        c.memories.save(NewMemory("Bought new running shoes"))
        c.processPending()
        val r = c.search.search("when is my test")
        assertEquals(exam.id, r.hits.first().memory.id)
        assertNull(r.hits.first().keywordRank)
        assertTrue(r.confident)
    }

    @Test
    fun saysIDontKnowForUnrelatedQuestions() = newCore().use { c ->
        c.memories.save(NewMemory("Passport is in the blue drawer"))
        c.processPending()
        val r = c.search.search("what is the capital of peru")
        assertFalse(r.confident)
    }

    @Test
    fun keywordHitIsSearchableBeforeEmbedding() = newCore().use { c ->
        val m = c.memories.save(NewMemory("Locker combination is on the gym card"))
        val r = c.search.search("locker")
        assertEquals(m.id, r.hits.single().memory.id)
        assertTrue(r.confident)
    }

    @Test
    fun timeFilterAndPinning() {
        val clock = FakeClock()
        newCore(clock).use { c ->
            val a = c.memories.save(NewMemory("bank account number 1234"))
            clock.advanceDays(10.0)
            val cutoff = clock.now
            val b = c.memories.save(NewMemory("bank branch moved to Lazimpat"))
            c.processPending()
            assertEquals(listOf(b.id), c.search.search("bank", SearchOptions(createdAfter = cutoff)).hits.map { it.memory.id })
            c.memories.update(a.id, app.kataa.core.model.MemoryEdit(pinned = true))
            assertTrue(c.search.search("bank").hits.any { it.memory.id == a.id })
        }
    }

    @Test
    fun trashCancelsRemindersAndPurgeDeletesOldTrash() {
        val clock = FakeClock()
        newCore(clock).use { c ->
            val m = c.memories.save(NewMemory("buy milk", MemoryKind.REMINDER))
            val r = c.reminders.addPlace(m.id, PlaceTarget.Brand("Big Mart"))
            c.memories.trash(m.id)
            assertEquals(ReminderStatus.CANCELLED, c.reminders.get(r.id)!!.status)
            clock.advanceDays(31.0)
            assertEquals(1, c.processPending().purged)
            assertNull(c.memories.get(m.id))
            assertNull(c.reminders.get(r.id))
        }
    }

    @Test
    fun recentListPaginates() = newCore().use { c ->
        repeat(5) { c.memories.save(NewMemory("note $it")) }
        val first = c.memories.recent(limit = 3)
        assertEquals(3, first.size)
        assertEquals(5, (first + c.memories.recent(limit = 3, after = first.last())).distinctBy { it.id }.size)
    }
}
