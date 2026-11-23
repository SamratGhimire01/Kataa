package app.kataa.entry

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.kataa.R

/** Home-screen widget: one big button that opens quick capture. Placeholder look until the frontend phase. */
class TalkWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val views = RemoteViews(context.packageName, R.layout.widget_talk).apply { setOnClickPendingIntent(R.id.widget_talk, open) }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
