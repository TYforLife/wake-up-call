package app.nodstop

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import app.nodstop.data.Route
import app.nodstop.ui.MainActivity

/** Long-press the launcher icon → "Start To work". */
object Shortcuts {
    const val EXTRA_START_ROUTE_ID = "start_route_id"

    fun startIntent(ctx: Context, routeId: String): Intent =
        Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_START_ROUTE_ID, routeId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    const val EXTRA_PLAN_TO_NAME = "plan_to_name"
    const val EXTRA_PLAN_TO_LAT = "plan_to_lat"
    const val EXTRA_PLAN_TO_LNG = "plan_to_lng"

    /** Plan a trip from where you are to [name] at [pos] ("Get back to Symonds St", BACKLOG F23). */
    fun planIntent(ctx: Context, name: String, pos: app.nodstop.engine.LatLng): Intent =
        Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_PLAN_TO_NAME, name).putExtra(EXTRA_PLAN_TO_LAT, pos.lat).putExtra(EXTRA_PLAN_TO_LNG, pos.lng)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun id(routeId: String) = "route-$routeId"

    /** A trip started from a shortcut or a tile: the launcher ranks the shortcuts you use first. */
    fun reportUsed(ctx: Context, routeId: String) {
        ctx.getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(id(routeId))
    }

    fun update(ctx: Context, routes: List<Route>) {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
        val list = routes.take(sm.maxShortcutCountPerActivity.coerceAtMost(4)).mapIndexed { i, r ->
            ShortcutInfo.Builder(ctx, id(r.id))
                .setShortLabel("Start ${r.name}".take(25))
                .setLongLabel("Start trip: ${r.name}")
                .setIcon(Icon.createWithResource(ctx, R.drawable.ic_tile))
                .setRank(i)
                .setIntent(startIntent(ctx, r.id))
                .build()
        }
        try {
            sm.dynamicShortcuts = list
        } catch (_: IllegalStateException) {
            // The phone is still locked after a restart, or the launcher is rate-limiting: the next update sets them.
        }
    }
}
