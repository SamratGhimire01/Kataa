package app.kataa.core.triggers

import app.kataa.core.FakeClock
import app.kataa.core.assistant.AssistantContext
import app.kataa.core.assistant.Reply
import app.kataa.core.model.GeoPoint
import app.kataa.core.model.ReminderStatus
import app.kataa.core.newCore
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriggerEngineTest {
    private val tz = TimeZone.of("Asia/Kathmandu")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, zone: TimeZone = tz) = LocalDateTime(y, mo, d, h, mi).toInstant(zone).toEpochMilliseconds()
    private val clock = FakeClock(at(2027, 1, 15, 10, 30))
    private val core = newCore(clock)
    private fun say(s: String, loc: GeoPoint? = null) = core.assistant.handle(s, AssistantContext(clock.now, tz, loc))
    private fun local(ms: Long, zone: TimeZone = tz) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)

    @Test fun recurrenceRules() {
        val fri8 = at(2027, 1, 15, 8)
        assertEquals(at(2027, 1, 16, 8), Recurrence.next(fri8, "FREQ=DAILY", tz))
        assertEquals(at(2027, 1, 18, 8), Recurrence.next(fri8, "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", tz)) // skips weekend
        assertEquals(at(2027, 1, 18, 8), Recurrence.next(fri8, "FREQ=WEEKLY;BYDAY=MO", tz))
        assertEquals(at(2027, 2, 15, 8), Recurrence.next(fri8, "FREQ=MONTHLY", tz))
        // The 31st in February -> 28th; then back to the 31st in March.
        val jan31 = at(2027, 1, 31, 9)
        val feb = Recurrence.next(jan31, "FREQ=MONTHLY", tz)!!
        assertEquals(28, local(feb).day)
        // Catching up after the phone was off for 3 days: next occurrence after now, not a backlog.
        assertEquals(at(2027, 1, 19, 8), Recurrence.next(fri8, "FREQ=DAILY", tz, afterMs = at(2027, 1, 18, 9)))
        // Daylight saving: stays at 8:00 local in New York across the March change.
        val ny = TimeZone.of("America/New_York")
        val n = Recurrence.next(at(2027, 3, 13, 8, zone = ny), "FREQ=DAILY", ny)!!
        assertEquals(8, local(n, ny).hour)
        assertNull(Recurrence.next(fri8, "FREQ=HOURLY", tz))
        // Birthdays: same day next year.
        kotlin.test.assertNotNull(Recurrence.next(fri8, "FREQ=YEARLY", tz))
    }

    @Test fun alarmFiresOnceAndRepeatsMove() {
        val once = say("remind me in 30 minutes to move the laundry") as Reply.ReminderSet
        val daily = say("remind me every day at 8 pm to take my vitamins") as Reply.ReminderSet
        assertEquals(clock.now + 30 * 60_000, core.triggers.nextWakeAt())
        assertTrue(core.triggers.onAlarm(clock.now).isEmpty())

        clock.now += 30 * 60_000
        val alerts = core.triggers.onAlarm(clock.now)
        assertEquals(listOf("Move the laundry"), alerts.map { it.text })
        assertEquals(ReminderStatus.FIRED, core.reminders.get(once.reminders[0].id)!!.status)
        assertTrue(core.triggers.onAlarm(clock.now).isEmpty(), "never fires twice")

        val firstDaily = core.reminders.get(daily.reminders[0].id)!!.fireAt!!
        assertEquals(firstDaily, core.triggers.nextWakeAt())
        clock.now = firstDaily
        assertEquals(listOf("Take my vitamins"), core.triggers.onAlarm(clock.now).map { it.text })
        val moved = core.reminders.get(daily.reminders[0].id)!!
        assertEquals(ReminderStatus.SCHEDULED, moved.status)
        assertEquals(firstDaily + 24 * 3600_000, moved.fireAt)
    }

    @Test fun nagUntilDone() {
        val r = say("keep reminding me to take my meds at 8 pm") as Reply.ReminderSet
        val id = r.reminders[0].id
        clock.now = r.reminders[0].fireAt!!
        assertEquals(1, core.triggers.onAlarm(clock.now).size)
        val nagAt = core.triggers.nextWakeAt()!!
        assertEquals(clock.now + 15 * 60_000, nagAt)
        clock.now = nagAt
        assertEquals("Still to do", core.triggers.onAlarm(clock.now).single().title)
        core.triggers.done(id)
        assertNull(core.triggers.nextWakeAt())
    }

    @Test fun storeErrandsAreGroupedIntoOneNotification() {
        say("I need to buy milk at Big Mart")
        say("remind me to get eggs at Big Mart")
        val branch = (say("this is Big Mart", GeoPoint(27.69, 85.34)) as Reply.PlaceSaved).place
        val alert = assertNotNull(core.triggers.onPlace(branch.id, exit = false, clock.now))
        assertEquals("At Big Mart", alert.title)
        assertEquals("• Buy milk\n• Get eggs", alert.text)
        assertNull(core.triggers.onPlace(branch.id, exit = false, clock.now), "done once fired")
        // "Not now": waits for the next visit.
        core.triggers.notNow(alert.reminders[0].id)
        assertEquals(1, core.triggers.onPlace(branch.id, exit = false, clock.now)!!.reminders.size)
    }

    @Test fun bluetoothNeedsTheCarChosenOnce() {
        say("remind me to fill petrol when I get in the car")
        assertEquals(listOf("car"), core.triggers.unmappedBluetoothAliases())
        assertNull(core.triggers.onBluetooth("AA:BB:CC:DD:EE:FF", "Toyota Audio", connected = true, clock.now))
        core.triggers.setBluetoothAlias("car", "aa:bb:cc:dd:ee:ff")
        assertTrue(core.triggers.unmappedBluetoothAliases().isEmpty())
        val a = assertNotNull(core.triggers.onBluetooth("AA:BB:CC:DD:EE:FF", "Toyota Audio", connected = true, clock.now))
        assertEquals("In the car", a.title)
        assertEquals("• Fill petrol", a.text)

        // Earbuds are recognised by name without setup.
        say("remind me to call Sita when I put on my earbuds")
        assertEquals("• Call Sita", core.triggers.onBluetooth("11:22", "Galaxy Buds2 Pro", connected = true, clock.now)!!.text)
    }

    @Test fun trashedMemoryNeverFires() {
        val r = say("remind me in 10 minutes to call the bank") as Reply.ReminderSet
        core.memories.trash(r.memory.id)
        assertNull(core.triggers.nextWakeAt())
        clock.now += 11 * 60_000
        assertTrue(core.triggers.onAlarm(clock.now).isEmpty())
    }
}
