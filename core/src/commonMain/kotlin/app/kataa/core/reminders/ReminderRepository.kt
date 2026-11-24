package app.kataa.core.reminders

import app.kataa.core.db.Row
import app.kataa.core.db.SqlDriver
import app.kataa.core.db.queryOne
import app.kataa.core.model.Place
import app.kataa.core.model.PlaceTarget
import app.kataa.core.model.Reminder
import app.kataa.core.model.ReminderStatus
import app.kataa.core.model.ReminderType
import app.kataa.core.places.PlaceRepository
import app.kataa.core.util.TimeSource

/**
 * Stores reminders and their lifecycle. Scheduling with the OS (AlarmManager, geofences, Bluetooth
 * receivers) is the platform layer's job; it reads [dueTimeReminders], [GeofencePlanner] and
 * [matchingPlace] and reports back with [markFired] / [markDone] / [snooze].
 */
class ReminderRepository(private val db: SqlDriver, private val clock: TimeSource) {

    fun addTime(memoryId: Long, fireAtMs: Long, tz: String? = null, rrule: String? = null, persistent: Boolean = false): Reminder =
        insert(memoryId, ReminderType.TIME, fireAt = fireAtMs, tz = tz, rrule = rrule, persistent = persistent)

    fun addPlace(
        memoryId: Long,
        target: PlaceTarget,
        onExit: Boolean = false,
        dwellMs: Long = DEFAULT_DWELL_MS,
        persistent: Boolean = false,
    ): Reminder = insert(
        memoryId,
        if (onExit) ReminderType.PLACE_EXIT else ReminderType.PLACE_ENTER,
        placeId = (target as? PlaceTarget.Specific)?.placeId,
        placeBrand = (target as? PlaceTarget.Brand)?.brand?.let(PlaceRepository::normalizeBrand),
        placeType = (target as? PlaceTarget.Type)?.placeType,
        dwellMs = if (onExit) 0 else dwellMs,
        persistent = persistent,
    )

    /** "When I get in the car" = when the phone connects to the car's Bluetooth. */
    fun addBluetooth(memoryId: Long, device: String, onDisconnect: Boolean = false, persistent: Boolean = false): Reminder =
        insert(
            memoryId,
            if (onDisconnect) ReminderType.BLUETOOTH_DISCONNECT else ReminderType.BLUETOOTH_CONNECT,
            btDevice = device, persistent = persistent,
        )

    fun get(id: Long): Reminder? = db.queryOne("SELECT $COLS FROM reminders WHERE id = ?", id, map = ::mapReminder)

    fun forMemory(memoryId: Long): List<Reminder> =
        db.query("SELECT $COLS FROM reminders WHERE memory_id = ? ORDER BY id", memoryId, map = ::mapReminder)

    /** Pending reminders of any type (for the "upcoming" list and for rescheduling after reboot). */
    fun active(): List<Reminder> = db.query(
        """
        SELECT ${prefixed()} FROM reminders r JOIN memories m ON m.id = r.memory_id AND m.deleted_at IS NULL
        WHERE r.status IN ('SCHEDULED','SNOOZED') ORDER BY coalesce(r.snooze_until, r.fire_at, r.created_at)
        """.trimIndent(),
        map = ::mapReminder,
    )

    /** Time reminders (and snoozed ones of any type) due at or before [nowMs]. */
    fun dueTimeReminders(nowMs: Long = clock.nowMs()): List<Reminder> = db.query(
        """
        SELECT ${prefixed()} FROM reminders r JOIN memories m ON m.id = r.memory_id AND m.deleted_at IS NULL
        WHERE (r.status = 'SCHEDULED' AND r.type = 'TIME' AND r.fire_at <= ?)
           OR (r.status = 'SNOOZED' AND r.snooze_until IS NOT NULL AND r.snooze_until <= ?)
        ORDER BY coalesce(r.snooze_until, r.fire_at)
        """.trimIndent(),
        nowMs, nowMs, map = ::mapReminder,
    )

    /** Everything to show when the user has been inside [place] long enough (grouped into one notification). */
    fun matchingPlace(place: Place, exit: Boolean = false): List<Reminder> = db.query(
        """
        SELECT ${prefixed()} FROM reminders r JOIN memories m ON m.id = r.memory_id AND m.deleted_at IS NULL
        WHERE r.status = 'SCHEDULED' AND r.type = ?
          AND (r.place_id = ? OR (r.place_brand IS NOT NULL AND ${PlaceRepository.brandMatches("r.place_brand", "?", "?")}) OR (r.place_type IS NOT NULL AND r.place_type = ?))
        ORDER BY r.created_at
        """.trimIndent(),
        if (exit) "PLACE_EXIT" else "PLACE_ENTER", place.id, place.brand, place.brand, place.placeType, place.placeType,
        map = ::mapReminder,
    )

    fun matchingBluetooth(device: String, connected: Boolean): List<Reminder> = db.query(
        """
        SELECT ${prefixed()} FROM reminders r JOIN memories m ON m.id = r.memory_id AND m.deleted_at IS NULL
        WHERE r.status = 'SCHEDULED' AND r.type = ? AND r.bt_device = ? COLLATE NOCASE
        """.trimIndent(),
        if (connected) "BLUETOOTH_CONNECT" else "BLUETOOTH_DISCONNECT", device, map = ::mapReminder,
    )

    /**
     * The notification was shown. One-off reminders become FIRED. "Nag until done" reminders are
     * re-armed for [nagAfterMs] later, so a swiped-away notification comes back until marked done.
     */
    fun markFired(id: Long, nowMs: Long = clock.nowMs(), nagAfterMs: Long = DEFAULT_NAG_MS) {
        db.execute(
            """
            UPDATE reminders SET last_fired_at = ?,
                status = CASE WHEN persistent = 1 THEN 'SNOOZED' ELSE 'FIRED' END,
                snooze_until = CASE WHEN persistent = 1 THEN ? ELSE NULL END
            WHERE id = ? AND status IN ('SCHEDULED','SNOOZED')
            """.trimIndent(),
            nowMs, nowMs + nagAfterMs, id,
        )
    }

    fun snooze(id: Long, untilMs: Long) {
        db.execute("UPDATE reminders SET status = 'SNOOZED', snooze_until = ? WHERE id = ? AND status IN ('SCHEDULED','SNOOZED','FIRED')", untilMs, id)
    }

    /** "Not now, remind me next time I'm there": back to waiting for the place/Bluetooth event. */
    fun rearm(id: Long) {
        db.execute("UPDATE reminders SET status = 'SCHEDULED', snooze_until = NULL WHERE id = ? AND type <> 'TIME'", id)
    }

    fun markDone(id: Long) { db.execute("UPDATE reminders SET status = 'DONE', snooze_until = NULL WHERE id = ?", id) }

    fun cancel(id: Long) { db.execute("UPDATE reminders SET status = 'CANCELLED', snooze_until = NULL WHERE id = ?", id) }

    fun setOsRequestId(id: Long, requestId: String?) { db.execute("UPDATE reminders SET os_request_id = ? WHERE id = ?", requestId, id) }

    private fun insert(
        memoryId: Long,
        type: ReminderType,
        fireAt: Long? = null,
        tz: String? = null,
        rrule: String? = null,
        placeId: Long? = null,
        placeBrand: String? = null,
        placeType: String? = null,
        dwellMs: Long = DEFAULT_DWELL_MS,
        btDevice: String? = null,
        persistent: Boolean = false,
    ): Reminder {
        db.execute(
            """
            INSERT INTO reminders(memory_id, type, fire_at, tz, rrule, place_id, place_brand, place_type, dwell_ms, bt_device, persistent, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            memoryId, type.name, fireAt, tz, rrule, placeId, placeBrand, placeType, dwellMs, btDevice, persistent, clock.nowMs(),
        )
        return get(db.lastInsertRowId())!!
    }

    private fun prefixed() = COLS.split(", ").joinToString(", ") { "r.$it" }

    companion object {
        const val DEFAULT_DWELL_MS = 2 * 60 * 1000L
        const val DEFAULT_NAG_MS = 15 * 60 * 1000L
        internal const val COLS = "id, memory_id, type, fire_at, tz, rrule, place_id, place_brand, place_type, dwell_ms, bt_device, " +
            "persistent, status, snooze_until, last_fired_at, created_at"

        internal fun mapReminder(r: Row): Reminder {
            val target = when {
                !r.isNull(6) -> PlaceTarget.Specific(r.long(6))
                !r.isNull(7) -> PlaceTarget.Brand(r.string(7))
                !r.isNull(8) -> PlaceTarget.Type(r.string(8))
                else -> null
            }
            return Reminder(
                id = r.long(0), memoryId = r.long(1), type = ReminderType.valueOf(r.string(2)), fireAt = r.longOrNull(3),
                tz = r.stringOrNull(4), rrule = r.stringOrNull(5), placeTarget = target, dwellMs = r.long(9),
                btDevice = r.stringOrNull(10), persistent = r.long(11) != 0L, status = ReminderStatus.valueOf(r.string(12)),
                snoozeUntil = r.longOrNull(13), lastFiredAt = r.longOrNull(14), createdAt = r.long(15),
            )
        }
    }
}
