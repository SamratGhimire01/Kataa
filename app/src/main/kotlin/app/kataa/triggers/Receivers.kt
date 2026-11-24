package app.kataa.triggers

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.kataa.triggers.Triggers.onCore
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/** The next time reminder (or nag) is due. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = onCore(context) { app ->
        app.cores.core.triggers.onAlarm(System.currentTimeMillis()).forEach { Notifier.show(context, it) }
        Triggers.refreshAlarm(context)
    }
}

/** Dwelled at / left a place, or left the "refresh" circle (time to pick the next nearest places). */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        onCore(context) { app ->
            val now = System.currentTimeMillis()
            var replan = false
            for (fence in event.triggeringGeofences.orEmpty()) {
                if (fence.requestId == "refresh") { replan = true; continue }
                val placeId = fence.requestId.removePrefix("place:").toLongOrNull() ?: continue
                val exit = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT
                app.cores.core.triggers.onPlace(placeId, exit, now)?.let { Notifier.show(context, it) }
            }
            if (replan) Triggers.refreshGeofences(context, event.triggeringLocation)
            Triggers.refreshAlarm(context) // nag-until-done place reminders need a time wake-up
        }
    }
}

/** A Bluetooth device connected or disconnected (car stereo, earbuds). */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val device: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
        @Suppress("MissingPermission") val name = runCatching { (if (Build.VERSION.SDK_INT >= 30) device.alias else null) ?: device.name }.getOrNull()
        val address = device.address
        onCore(context) { app ->
            val triggers = app.cores.core.triggers
            val alert = triggers.onBluetooth(address, name, connected, System.currentTimeMillis())
            if (alert != null) Notifier.show(context, alert)
            else if (connected && name != null) {
                // A car reminder is waiting but no car is chosen yet: ask once about this device.
                val wanting = triggers.unmappedBluetoothAliases().firstOrNull { it == "car" || it == "bike" }
                if (wanting != null && triggers.onBluetooth(address, name, true, 0) == null) Notifier.askBluetoothAlias(context, wanting, address, name)
            }
            Triggers.refreshAlarm(context)
        }
    }
}

/** Notification buttons. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notification = intent.getIntExtra("notification", 0)
        Notifier.cancel(context, notification)
        onCore(context) { app ->
            val t = app.cores.core.triggers
            val ids = intent.getLongArrayExtra("ids") ?: LongArray(0)
            when (intent.action) {
                DONE -> ids.forEach(t::done)
                SNOOZE -> ids.forEach { t.snooze(it, System.currentTimeMillis() + 60 * 60 * 1000) }
                NOT_NOW -> ids.forEach(t::notNow)
                SET_BT_ALIAS -> {
                    val alias = intent.getStringExtra("alias") ?: return@onCore
                    val address = intent.getStringExtra("address") ?: return@onCore
                    t.setBluetoothAlias(alias, address)
                    t.onBluetooth(address, intent.getStringExtra("name"), true, System.currentTimeMillis())?.let { Notifier.show(context, it) }
                }
            }
            Triggers.refreshAlarm(context)
        }
    }

    companion object {
        const val DONE = "app.kataa.action.DONE"
        const val SNOOZE = "app.kataa.action.SNOOZE"
        const val NOT_NOW = "app.kataa.action.NOT_NOW"
        const val SET_BT_ALIAS = "app.kataa.action.SET_BT_ALIAS"
    }
}

/** Reboot, app update, clock or time-zone change: the OS forgot our alarms and geofences. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = onCore(context) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == android.location.LocationManager.PROVIDERS_CHANGED_ACTION) {
            // The OS dropped every geofence: forget what we thought was registered, then re-plan.
            it.cores.core.db.execute("DELETE FROM active_geofences")
        }
        Triggers.refreshAll(context)
    }
}
