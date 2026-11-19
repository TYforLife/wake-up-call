package app.nodstop

import android.app.Application
import app.nodstop.alarm.AlarmScheduler
import app.nodstop.data.LearnedStore
import app.nodstop.data.RouteStore
import app.nodstop.data.Settings
import app.nodstop.data.TraceLogger
import app.nodstop.data.TripStore
import app.nodstop.model.ModelManager
import app.nodstop.notify.DndController

/** Manual wiring: one instance of each, no DI framework. */
class App : Application() {
    lateinit var routes: RouteStore
    lateinit var learned: LearnedStore
    lateinit var trips: TripStore
    lateinit var settings: Settings
    lateinit var traces: TraceLogger
    lateinit var scheduler: AlarmScheduler
    lateinit var models: ModelManager

    override fun onCreate() {
        super.onCreate()
        instance = this
        org.maplibre.android.MapLibre.getInstance(this)
        routes = RouteStore(this)
        learned = LearnedStore(this)
        trips = TripStore(this)
        settings = Settings(this)
        traces = TraceLogger(this)
        scheduler = AlarmScheduler(this)
        models = ModelManager(this)
        Notifications.createChannels(this)
        // A fresh process has no trip running yet: undo Do Not Disturb if a crash left ours on (DESIGN §10).
        DndController.onTripEnd(this)
        // A ring that switched Mute to vibrate and was killed before "I'm awake": put your setting back.
        app.nodstop.alarm.RingModeController.onRingEnd(this)
        Shortcuts.update(this, routes.routes.value)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
