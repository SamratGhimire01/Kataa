package app.kataa.core

import app.kataa.core.model.AssignmentState
import app.kataa.core.model.MemoryEdit
import app.kataa.core.model.NewMemory
import app.kataa.core.search.SearchOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CategoryTest {
    private fun KataaCore.slugsOf(id: Long) = categories.assignmentsFor(id).map { it.slug }

    @Test
    fun startsWithElevenCategories() = newCore().use { c ->
        val cats = c.categories.list()
        assertEquals(11, cats.size)
        assertTrue(cats.single { it.slug == "private" }.isPrivate)
    }

    @Test
    fun autoAssignsOnProcessing() = newCore().use { c ->
        val exam = c.memories.save(NewMemory("Chemistry exam on Sunday, revise chapter 4"))
        val meeting = c.memories.save(NewMemory("Client meeting moved to Monday 10am"))
        val wifi = c.memories.save(NewMemory("Home wifi password is sunrise42"))
        val vague = c.memories.save(NewMemory("it was nice"))
        c.processPending()
        assertEquals("education", c.slugsOf(exam.id).first())
        assertEquals("work", c.slugsOf(meeting.id).first())
        assertEquals("private", c.slugsOf(wifi.id).first())
        assertEquals(emptyList(), c.slugsOf(vague.id))
        assertTrue(c.categories.uncategorized().any { it.id == vague.id })
        val edu = c.categories.bySlug("education")!!
        assertEquals(1, edu.memoryCount)
        assertEquals(listOf(exam.id), c.categories.memoriesIn(edu.id).map { it.id })
    }

    @Test
    fun rejectedCategoryIsNeverReAdded() = newCore().use { c ->
        val m = c.memories.save(NewMemory("Chemistry exam on Sunday"))
        c.processPending()
        val edu = c.categories.bySlug("education")!!
        c.categories.reject(m.id, edu.id)
        c.memories.update(m.id, MemoryEdit(body = "Chemistry exam moved to Monday"))
        c.processPending()
        assertFalse("education" in c.slugsOf(m.id))
        assertEquals(AssignmentState.REJECTED, c.categories.assignmentsFor(m.id, includeRejected = true).single { it.slug == "education" }.state)
    }

    @Test
    fun userChoiceWinsAndSurvivesEdits() = newCore().use { c ->
        val m = c.memories.save(NewMemory("Chemistry exam on Sunday"))
        c.processPending()
        val ideas = c.categories.bySlug("ideas")!!
        c.categories.move(m.id, ideas.id)
        assertEquals(listOf("ideas"), c.slugsOf(m.id))
        c.memories.update(m.id, MemoryEdit(body = "Chemistry exam moved to Monday"))
        c.processPending()
        assertEquals(listOf("ideas"), c.slugsOf(m.id))
    }

    @Test
    fun userCategoryWorksImmediatelyAndLearns() = newCore().use { c ->
        val guitar = c.categories.create("Guitar", "guitar practice, chords, songs to learn, strings, capo")
        val m1 = c.memories.save(NewMemory("Practice the F chord and change the strings"))
        c.processPending()
        assertEquals("guitar", c.slugsOf(m1.id).first())

        val before = c.db.query("SELECT example_count FROM categories WHERE id = ?", guitar.id) { it.long(0) }.single()
        val m2 = c.memories.save(NewMemory("Learn the intro riff of the new song"))
        c.processPending()
        c.categories.assign(m2.id, guitar.id)
        val after = c.db.query("SELECT example_count FROM categories WHERE id = ?", guitar.id) { it.long(0) }.single()
        assertEquals(before + 1, after)
    }

    @Test
    fun categoryFilterAndPrivateHiding() = newCore().use { c ->
        val exam = c.memories.save(NewMemory("Physics exam is on Friday"))
        val meeting = c.memories.save(NewMemory("Physics client meeting on Friday"))
        val pin = c.memories.save(NewMemory("Bank card PIN is 4821"))
        c.processPending()
        val edu = c.categories.bySlug("education")!!
        val r = c.search.search("physics friday", SearchOptions(categoryId = edu.id))
        assertEquals(listOf(exam.id), r.hits.map { it.memory.id })
        assertTrue(meeting.id in c.search.search("physics friday").hits.map { it.memory.id })

        assertFalse(pin.id in c.search.search("bank card pin").hits.map { it.memory.id })
        assertTrue(pin.id in c.search.search("bank card pin", SearchOptions(includePrivate = true)).hits.map { it.memory.id })
    }

    @Test
    fun archiveAndMerge() = newCore().use { c ->
        val m = c.memories.save(NewMemory("Trip to Pokhara, bus ticket booked"))
        c.processPending()
        val travel = c.categories.bySlug("travel")!!
        assertTrue("travel" in c.slugsOf(m.id))
        c.categories.archive(travel.id)
        assertFalse(c.categories.list().any { it.slug == "travel" })
        val trips = c.categories.create("Trips")
        c.categories.merge(travel.id, trips.id)
        assertTrue("trips" in c.slugsOf(m.id))
    }
}
