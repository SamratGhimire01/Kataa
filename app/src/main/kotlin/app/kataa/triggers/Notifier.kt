package app.kataa.triggers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.kataa.ui.MainActivity
import app.kataa.R
import app.kataa.core.model.ReminderType
import app.kataa.core.triggers.Alert

/** Shows Kataa's reminders. Actions: Done, Snooze 1 h (time) or Next time (place/Bluetooth). */
object Notifier {
    private const val CH_REMINDERS = "reminders"
    private const val CH_SETUP = "setup"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Your reminders: at a time, at a place, or when you get in the car"
        })
        nm.createNotificationChannel(NotificationChannel(CH_SETUP, "Quick questions", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "One-time questions, like which Bluetooth device is your car"
        })
    }

    fun show(context: Context, alert: Alert) {
        ensureChannels(context)
        val id = alert.key.hashCode()
        val ids = alert.reminders.map { it.id }.toLongArray()
        val timeBased = alert.reminders.all { it.type == ReminderType.TIME }
        val b = builder(context, CH_REMINDERS)
            .setContentTitle(alert.title)
            .setContentText(alert.text.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(alert.text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .addAction(action(context, "Done", ReminderActionReceiver.DONE, id, ids))
            .addAction(
                if (timeBased) action(context, "Snooze 1 h", ReminderActionReceiver.SNOOZE, id, ids)
                else action(context, "Next time", ReminderActionReceiver.NOT_NOW, id, ids),
            )
        // Swiping away a "nag until done" reminder doesn't finish it; it comes back later.
        if (alert.persistent) b.setOngoing(false).setOnlyAlertOnce(false)
        notify(context, id, b.build())
    }

    /** "Is 'Toyota Audio' your car?" Asked once, the first time an unknown device connects while a car reminder waits. */
    fun askBluetoothAlias(context: Context, alias: String, address: String, name: String) {
        ensureChannels(context)
        val id = "bt-ask-$alias".hashCode()
        val yes = PendingIntent.getBroadcast(
            context, id,
            Intent(context, ReminderActionReceiver::class.java).setAction(ReminderActionReceiver.SET_BT_ALIAS)
                .putExtra("alias", alias).putExtra("address", address).putExtra("name", name).putExtra("notification", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notify(context, id, builder(context, CH_SETUP)
            .setContentTitle("Is \"$name\" your $alias?")
            .setContentText("Then your \"$alias\" reminders will pop up when it connects.")
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Yes, it's my $alias", yes).build())
            .build())
    }

    fun cancel(context: Context, notificationId: Int) =
        context.getSystemService(NotificationManager::class.java).cancel(notificationId)

    private fun notify(context: Context, id: Int, n: Notification) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) return
        nm.notify(id, n)
    }

    private fun builder(context: Context, channel: String) =
        Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification).setShowWhen(true)

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(context: Context, label: String, act: String, notificationId: Int, ids: LongArray): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            context, (act + notificationId).hashCode(),
            Intent(context, ReminderActionReceiver::class.java).setAction(act).putExtra("ids", ids).putExtra("notification", notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, label, pi).build()
    }
}
