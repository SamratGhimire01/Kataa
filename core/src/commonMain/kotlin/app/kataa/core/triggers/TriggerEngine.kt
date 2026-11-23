package app.kataa.core.triggers

import app.kataa.core.KataaCore
import app.kataa.core.db.Migrations
import app.kataa.core.db.queryOne
import app.kataa.core.model.Memory
import app.kataa.core.model.Place
import app.kataa.core.model.Reminder
import app.kataa.core.model.ReminderType
import kotlinx.datetime.TimeZone

/** One notification to show. Place errands at the same store are grouped into one [Alert]. */
data class Alert(
    /** Stable id for the notification (so updates replace instead of stacking). */
    val key: String,
    val title: String,
    val text: String,
    val reminders: List<Reminder>,
    val memories: List<Memory>,
    /** Keeps coming back until marked done. */
    val persistent: Boolean,
)

/**
 * Turns OS events (alarm fired, arrived at a place, car connected) into notifications, and keeps
 * reminder state right: one-offs become FIRED, "nag until done" ones come back, repeating ones move to
 * their next occurrence. Platform code only schedules OS wake-ups and shows what this returns.
 */
class TriggerEngine(private val core: KataaCore) {

    /** When the OS should wake Kataa next for time reminders (and nags), or null for nothing pending. */
    fun nextWakeAt(): Long? = core.db.queryOne(
        """
        SELECT min(coalesce(r.snooze_until, r.fire_at)) FROM reminders r
        JOIN memories m ON m.id = r.memory_id AND m.deleted_at IS NULL
        WHERE (r.status = 'SCHEDULED' AND r.type = 'TIME') OR (r.status = 'SNOOZED' AND r.snooze_until IS NOT NULL)
        """.trimIndent(),
    ) { it.longOrNull(0) }

    /** The alarm fired: everything due now, each as its own notification. */
    fun onAlarm(nowMs: Long): List<Alert> = core.db.transaction {
        core.reminders.dueTimeReminders(nowMs).mapNotNull { r ->
            val m = core.memories.get(r.memoryId) ?: return@mapNotNull null
            fire(r, nowMs)
            Alert("r${r.id}", title(r), m.body, listOf(r), listOf(m), r.persistent)
        }
    }

    /** The user has been at [place] for the dwell time (or just left it, when [exit]). */
    fun onPlace(placeId: Long, exit: Boolean, nowMs: Long): Alert? = core.db.transaction {
        val place = core.places.get(placeId) ?: return@transaction null
        val due = core.reminders.matchingPlace(place, exit)
        if (due.isEmpty()) return@transaction null
        due.forEach { fire(it, nowMs) }
        val memories = due.mapNotNull { core.memories.get(it.memoryId) }
        val title = if (exit) "Before you leave ${place.label}" else "At ${place.label}"
        Alert("p${place.id}${if (exit) "x" else ""}", title, memories.joinToString("\n") { "• ${it.body}" }, due, memories, due.any { it.persistent })
    }

    /** Bluetooth device connected / disconnected. Matches reminders by the alias the user gave it ("car"). */
    fun onBluetooth(address: String, deviceName: String?, connected: Boolean, nowMs: Long): Alert? = core.db.transaction {
        val aliases = aliasesFor(address, deviceName)
        val due = aliases.flatMap { core.reminders.matchingBluetooth(it, connected) }.distinctBy { it.id }
        if (due.isEmpty()) return@transaction null
        due.forEach { fire(it, nowMs) }
        val memories = due.mapNotNull { core.memories.get(it.memoryId) }
        val alias = aliases.first()
        val title = when {
            !connected -> "Before you leave the $alias"
            alias in setOf("earbuds", "headphones") -> "Now that your $alias are on"
            else -> "In the $alias"
        }
        Alert("b$alias${if (connected) "" else "x"}", title, memories.joinToString("\n") { "• ${it.body}" }, due, memories, due.any { it.persistent })
    }

    /** "This Bluetooth device is my car." */
    fun setBluetoothAlias(alias: String, address: String) =
        Migrations.setMeta(core.db, "bt_alias.${alias.lowercase()}", address.uppercase())

    fun bluetoothAlias(alias: String): String? = Migrations.getMeta(core.db, "bt_alias.${alias.lowercase()}")?.takeIf { it.isNotEmpty() }

    /** Aliases waiting for a device ("car" reminders exist but no car is chosen yet): the app should ask. */
    fun unmappedBluetoothAliases(): List<String> =
        core.reminders.active().filter { it.type == ReminderType.BLUETOOTH_CONNECT || it.type == ReminderType.BLUETOOTH_DISCONNECT }
            .mapNotNull { it.btDevice?.lowercase() }.distinct().filter { bluetoothAlias(it) == null }

    // Notification actions
    fun done(reminderId: Long) = core.reminders.markDone(reminderId)
    fun snooze(reminderId: Long, untilMs: Long) = core.reminders.snooze(reminderId, untilMs)
    /** "Next time I'm there." */
    fun notNow(reminderId: Long) = core.reminders.rearm(reminderId)

    private fun fire(r: Reminder, nowMs: Long) {
        val next = if (r.type == ReminderType.TIME && r.rrule != null && r.fireAt != null) {
            Recurrence.next(r.fireAt, r.rrule, r.tz?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: TimeZone.currentSystemDefault(), afterMs = nowMs)
        } else null
        if (next != null) {
            core.db.execute("UPDATE reminders SET fire_at = ?, last_fired_at = ?, status = 'SCHEDULED', snooze_until = NULL WHERE id = ?", next, nowMs, r.id)
        } else {
            core.reminders.markFired(r.id, nowMs)
        }
    }

    private fun aliasesFor(address: String, name: String?): List<String> {
        val mapped = core.db.query("SELECT key FROM meta WHERE key LIKE 'bt_alias.%' AND value = ?", address.uppercase()) {
            it.string(0).removePrefix("bt_alias.")
        }
        if (mapped.isNotEmpty()) return mapped
        // Not chosen yet: earbuds/headphones are recognisable by name; a car is not, so it waits for the user.
        val n = name?.lowercase().orEmpty()
        return when {
            listOf("buds", "airpods", "earbud", "earphone").any { it in n } -> listOf("earbuds")
            listOf("headphone", "wh-1000", "headset").any { it in n } -> listOf("headphones")
            else -> emptyList()
        }
    }

    private fun title(r: Reminder) = if (r.snoozeUntil != null && r.lastFiredAt != null) "Still to do" else "Reminder"
}
