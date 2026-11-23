package app.kataa.triggers

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import app.kataa.KataaApplication
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Keeps the OS wake-ups in sync with the database: one exact alarm for the next time reminder, and up
 * to 100 geofences around the nearest places that have errands. Cheap to call after every change.
 * Must run on the core worker thread.
 */
object Triggers {
    private const val ALARM_REQUEST = 1001

    fun refreshAll(context: Context) {
        refreshAlarm(context)
        refreshGeofences(context, null)
    }

    fun refreshAlarm(context: Context) {
        val app = context.applicationContext as KataaApplication
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, ALARM_REQUEST, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val next = app.cores.core.triggers.nextWakeAt()
        if (next == null) { am.cancel(pi); return }
        // Exact when allowed (the user grants "Alarms & reminders" once); otherwise within a few minutes.
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }

    /**
     * Re-plans geofences around [where] (or the last known location). Needs fine + background location;
     * without them place reminders still work by "this is Big Mart" while the app is open.
     */
    @SuppressLint("MissingPermission")
    fun refreshGeofences(context: Context, where: Location?) {
        if (!hasBackgroundLocation(context)) return
        val app = context.applicationContext as KataaApplication
        val location = where ?: lastLocation(context) ?: return
        val plan = app.cores.core.geofences.plan(location.latitude, location.longitude)
        val client = LocationServices.getGeofencingClient(context)
        if (plan.remove.isNotEmpty()) client.removeGeofences(plan.remove)
        if (plan.add.isEmpty()) return
        val fences = plan.add.map { f ->
            val refresh = f.placeId == null
            Geofence.Builder()
                .setRequestId(f.requestId)
                .setCircularRegion(f.lat, f.lng, f.radiusM.toFloat().coerceAtLeast(MIN_RADIUS_M))
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(if (refresh) Geofence.GEOFENCE_TRANSITION_EXIT else Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT)
                // Two minutes inside before "you're at Big Mart": no alerts while driving past.
                .setLoiteringDelay(DWELL_MS)
                .build()
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0) // no alert for places you are already in when fences are (re)registered
            .addGeofences(fences)
            .build()
        client.addGeofences(request, geofencePendingIntent(context))
    }

    fun geofencePendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1002, Intent(context, GeofenceReceiver::class.java),
        // Geofencing fills in the event extras, so this one must be mutable.
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Android 8–9 have no separate background permission: fine location covers geofences. */
    fun hasBackgroundLocation(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 29 ||
                context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    @SuppressLint("MissingPermission")
    private fun hasLocation(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The phone's last known fix if it is recent (no waiting). Call off the main thread. */
    @SuppressLint("MissingPermission")
    fun recentLocation(context: Context, maxAgeMs: Long = 3 * 60_000L): Location? = if (!hasLocation(context)) null else runCatching {
        val l = com.google.android.gms.tasks.Tasks.await(LocationServices.getFusedLocationProviderClient(context).lastLocation, 800, java.util.concurrent.TimeUnit.MILLISECONDS)
        l?.takeIf { System.currentTimeMillis() - it.time <= maxAgeMs }
    }.getOrNull()

    /** A fresh, accurate fix ("this is Big Mart", "I parked here"); waits up to a few seconds. Call off the main thread. */
    @SuppressLint("MissingPermission")
    fun currentLocation(context: Context): Location? = if (!hasLocation(context)) null else runCatching {
        val client = LocationServices.getFusedLocationProviderClient(context)
        com.google.android.gms.tasks.Tasks.await(client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null), 8, java.util.concurrent.TimeUnit.SECONDS)
            ?: com.google.android.gms.tasks.Tasks.await(client.lastLocation, 2, java.util.concurrent.TimeUnit.SECONDS)
    }.getOrNull()

    private fun lastLocation(context: Context): Location? = runCatching {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val task = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
        com.google.android.gms.tasks.Tasks.await(task, 20, java.util.concurrent.TimeUnit.SECONDS)
            ?: com.google.android.gms.tasks.Tasks.await(client.lastLocation, 5, java.util.concurrent.TimeUnit.SECONDS)
    }.getOrNull()

    /** Runs [block] on the core worker and keeps the receiver alive until it finishes. */
    fun BroadcastReceiver.onCore(context: Context, block: (KataaApplication) -> Unit) {
        val pending = goAsync()
        val app = context.applicationContext as KataaApplication
        app.cores.worker.execute {
            // An exception here would kill the worker thread and crash the app from the background.
            try { block(app) } catch (t: Throwable) { android.util.Log.e("Kataa", "trigger failed", t) } finally { pending.finish() }
        }
    }

    private const val DWELL_MS = 2 * 60 * 1000
    private const val MIN_RADIUS_M = 100f
}
