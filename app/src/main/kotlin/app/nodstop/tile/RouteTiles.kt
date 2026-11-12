package app.nodstop.tile

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.nodstop.App
import app.nodstop.Shortcuts
import app.nodstop.trip.TripService
import app.nodstop.ui.MainActivity
import android.content.Intent

/**
 * Quick Settings tile for the Nth route ("To work", "From work"). Tapping opens the app and starts the route,
 * so the location service starts from the foreground. Tapping while a trip runs just shows the trip:
 * a sleepy tap never stops an alarm.
 */
abstract class RouteTile(private val index: Int) : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val route = App.instance.routes.routes.value.getOrNull(index)
        val running = TripService.status.value
        tile.label = route?.name ?: "No route ${index + 1}"
        tile.subtitle = when {
            route == null -> "Add it in the app"
            running != null && running.routeName == route.name -> "Running"
            route.problem() != null -> "Needs setup"
            else -> "Start trip"
        }
        tile.state = when {
            route == null -> Tile.STATE_UNAVAILABLE
            running != null && running.routeName == route.name -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val route = App.instance.routes.routes.value.getOrNull(index)
        val intent = if (route != null) Shortcuts.startIntent(this, route.id)
        else Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        unlockAndRun {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 20 + index, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }
    }
}

class RouteTile0 : RouteTile(0)
class RouteTile1 : RouteTile(1)
