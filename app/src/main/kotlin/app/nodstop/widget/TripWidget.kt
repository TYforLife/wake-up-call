package app.nodstop.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import app.nodstop.App
import app.nodstop.Notifications
import app.nodstop.R
import app.nodstop.Shortcuts
import app.nodstop.data.TransitData
import app.nodstop.isolated
import app.nodstop.trip.TripService
import app.nodstop.ui.v2.RouteOption
import app.nodstop.ui.v2.RouteText
import app.nodstop.ui.v2.TripActionUi
import app.nodstop.ui.v2.WidgetText
import app.nodstop.ui.v2.WidgetUi
import app.nodstop.ui.v2.hm
import app.nodstop.ui.v2.routeSubtitle
import app.nodstop.ui.v2.suggestedRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar

/**
 * The home screen widget (BACKLOG F10, DESIGN §5 Widget). No trip: the route Now would pick, its next bus and Start.
 * Riding: the trip sheet's one line and I'm off this bus, redrawn with the trip notification. Its buttons are the
 * app's own: Start opens the app to start the route (as the tile and the launcher shortcut do), and I'm off this bus
 * is the notification's action. It never touches the alarm.
 */
class TripWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        // The next bus may need the timetable read from disk: off the main thread, within the broadcast's time.
        val pending = goAsync()
        scope.launch {
            try {
                isolated("widget") { draw(ctx, mgr, ids, idleOrTrip(ctx)) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** What was last drawn while riding, so the trip's 2-s updates only redraw when the words change. */
        @Volatile private var shown: WidgetUi? = null

        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, TripWidget::class.java))

        /**
         * Draw it again from scratch (no trip, or a trip that just ended), off the main thread. Called from the trip
         * service and Now: a widget that fails must never take them down.
         */
        fun refresh(ctx: Context) = isolated("widget refresh") {
            val ids = ids(ctx)
            if (ids.isEmpty()) return@isolated
            shown = null
            ctx.sendBroadcast(
                Intent(ctx, TripWidget::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }

        /** The running trip, with the trip notification (`TripService`): only when its words change. */
        fun onTrip(ctx: Context, ui: TripService.TripUi) = isolated("widget") {
            val w = WidgetText.riding(ui, ::hm, System.currentTimeMillis())
            if (w == shown) return@isolated
            val ids = ids(ctx)
            if (ids.isEmpty()) return@isolated
            shown = w
            draw(ctx, AppWidgetManager.getInstance(ctx), ids, w)
        }

        private suspend fun idleOrTrip(ctx: Context): WidgetUi {
            TripService.status.value?.let { return WidgetText.riding(it, ::hm, System.currentTimeMillis()) }
            val app = App.instance
            if (app.trips.load() != null) return WidgetText.interrupted(app.scheduler.scheduledAtMs?.let(::hm) ?: "")
            val routes = app.routes.routes.value
            val route = suggestedRoute(routes, app.settings.startHistory(), Calendar.getInstance())
                ?.let { id -> routes.firstOrNull { it.id == id } }
            val option = route?.let { RouteOption(it.id, it.name, routeSubtitle(it), RouteText.problem(it)) }
            val first = route?.legs?.firstOrNull()
            val bus = first?.bus
            val nowS = LocalTime.now().toSecondOfDay()
            val deps = if (bus != null && first.boardCoords != null) TransitData.todayDepartures(ctx, bus, first.boardCoords) else emptyList()
            WidgetText.redrawAtS(deps, nowS)?.let { redrawAt(ctx, it) }
            return WidgetText.idle(option, bus, deps, nowS)
        }

        private fun draw(ctx: Context, mgr: AppWidgetManager, ids: IntArray, w: WidgetUi) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_trip)
            v.setTextViewText(R.id.widget_title, w.title)
            v.setTextViewText(R.id.widget_line, w.line)
            v.setColorStateList(R.id.widget_title, "setTextColor", if (w.danger) R.color.widget_danger else R.color.widget_fg)
            v.setViewVisibility(R.id.widget_live, if (w.live) View.VISIBLE else View.GONE)
            v.setOnClickPendingIntent(R.id.widget_root, Notifications.openApp(ctx))
            val a = w.action
            if (a == null) v.setViewVisibility(R.id.widget_action, View.GONE)
            else {
                v.setViewVisibility(R.id.widget_action, View.VISIBLE)
                v.setTextViewText(R.id.widget_action, a.label)
                v.setOnClickPendingIntent(R.id.widget_action, actionIntent(ctx, a))
            }
            mgr.updateAppWidget(ids, v)
        }

        private fun actionIntent(ctx: Context, a: WidgetUi.Action): PendingIntent = when (a.kind) {
            WidgetUi.Kind.OFF -> Notifications.tripAction(ctx, TripActionUi.Kind.OFF)
            WidgetUi.Kind.OPEN -> Notifications.openApp(ctx)
            WidgetUi.Kind.START -> a.routeId?.let {
                PendingIntent.getActivity(
                    ctx, START_CODE, Shortcuts.startIntent(ctx, it), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            } ?: Notifications.openApp(ctx)
        }

        /**
         * Draw again from [atS] (seconds after today's midnight), so the first bus shown has not left. Not the ring:
         * RTC, not a wakeup type, so it waits for the screen to be on and costs nothing while the phone sleeps; and
         * inexact, so Android may draw it up to [WINDOW_MS] late, which is why the widget shows the next two buses.
         */
        private fun redrawAt(ctx: Context, atS: Int) {
            val atMs = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + atS * 1000L
            val pi = PendingIntent.getBroadcast(
                ctx, REDRAW_CODE,
                Intent(ctx, TripWidget::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids(ctx)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            ctx.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC, atMs, WINDOW_MS, pi)
        }

        /** The shortest window Android gives an inexact alarm (API 31+). */
        private const val WINDOW_MS = 10 * 60_000L
        private const val START_CODE = 30
        private const val REDRAW_CODE = 31
    }
}
