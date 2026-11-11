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

    fun update(ctx: Context, routes: List<Route>) {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
        val list = routes.take(sm.maxShortcutCountPerActivity.coerceAtMost(4)).mapIndexed { i, r ->
            ShortcutInfo.Builder(ctx, "route-${r.id}")
                .setShortLabel("Start ${r.name}".take(25))
                .setLongLabel("Start trip: ${r.name}")
                .setIcon(Icon.createWithResource(ctx, R.drawable.ic_tile))
                .setRank(i)
                .setIntent(startIntent(ctx, r.id))
                .build()
        }
        runCatching { sm.dynamicShortcuts = list }
    }
}
