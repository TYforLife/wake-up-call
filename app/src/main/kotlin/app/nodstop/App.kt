package app.nodstop

import android.app.Application
import app.nodstop.alarm.AlarmScheduler
import app.nodstop.data.RouteStore
import app.nodstop.data.Settings
import app.nodstop.data.TraceLogger
import app.nodstop.data.TripStore

/** Manual wiring: one instance of each, no DI framework. */
class App : Application() {
    lateinit var routes: RouteStore
    lateinit var trips: TripStore
    lateinit var settings: Settings
    lateinit var traces: TraceLogger
    lateinit var scheduler: AlarmScheduler

    override fun onCreate() {
        super.onCreate()
        instance = this
        routes = RouteStore(this)
        trips = TripStore(this)
        settings = Settings(this)
        traces = TraceLogger(this)
        scheduler = AlarmScheduler(this)
        Notifications.createChannels(this)
        Shortcuts.update(this, routes.routes.value)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
