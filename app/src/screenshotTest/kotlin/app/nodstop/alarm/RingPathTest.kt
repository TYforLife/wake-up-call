package app.nodstop.alarm

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.os.VibratorManager
import app.nodstop.App
import app.nodstop.Notifications
import app.nodstop.data.ActiveTrip
import app.nodstop.engine.AlarmReason
import app.nodstop.engine.LatLng
import app.nodstop.engine.Leg
import app.nodstop.notify.DndRestore
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The ring path, end to end on the JVM (PLAN Run 10, A2): the system alarm fires `AlarmReceiver`, which posts the
 * ring and starts `RingService`, which buzzes. One test per phone state the owner has been caught by. If any of
 * these fail, the alarm may not reach him: CI blocks the merge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RingPathTest {
    private val app: App get() = RuntimeEnvironment.getApplication() as App
    private val nm: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private val audio: AudioManager get() = app.getSystemService(AudioManager::class.java)
    private var service: ServiceController<RingService>? = null

    private fun fire() =
        AlarmReceiver().onReceive(app, Intent(app, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE))

    /** The alarm fires and the ring service runs, as on the phone. */
    private fun ring(): RingService {
        fire()
        val started = shadowOf(app).nextStartedService
        assertNotNull("the receiver must start the ring service", started)
        assertEquals(RingService::class.java.name, started.component?.className)
        val c = Robolectric.buildService(RingService::class.java, started).create().startCommand(0, 1)
        service = c
        shadowOf(Looper.getMainLooper()).idle()
        return c.get()
    }

    private fun awake() {
        RingService.stop(app)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Before
    fun setUp() {
        // The phone allows exact alarms (USE_EXACT_ALARM) and has a vibrator.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        vibrator().setHasVibrator(true)
    }

    @After
    fun tearDown() {
        runCatching { RingService.stop(app) }
        service?.let { runCatching { it.destroy() } }
    }

    private fun ringNotification(): Notification? = shadowOf(nm).getNotification(Notifications.ID_RING)

    private fun vibrator() = shadowOf(app.getSystemService(VibratorManager::class.java).defaultVibrator)

    private fun trip() = ActiveTrip(
        routeId = "r", routeName = "To work", legs = listOf(Leg("Harbour St", LatLng(-36.8, 174.7))), legOffset = 0,
        engineLegIndex = 0, startedMs = System.currentTimeMillis(), traceFile = "trip-test.jsonl",
        alarmReason = AlarmReason.WAKE, remainingS = 45.0, savedMs = System.currentTimeMillis(),
    )

    @Test
    fun `the receiver alone puts the alarm on screen, buzzing on its channel - even if the service never starts`() {
        fire()
        val n = ringNotification()
        assertNotNull("the receiver posts the ring before the service exists", n)
        n!!
        assertNotNull("full screen over the lock screen", n.fullScreenIntent)
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertTrue("insistent: it keeps buzzing until stopped", n.flags and Notification.FLAG_INSISTENT != 0)
        val ch = nm.getNotificationChannel(n.channelId)
        assertNotNull(ch)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
        assertTrue("the channel vibrates by itself", ch.shouldVibrate())
    }

    @Test
    fun `normal phone - it buzzes as an alarm`() {
        ring()
        assertTrue(vibrator().isVibrating)
        assertNotNull(ringNotification())
        awake()
        assertNull("I'm awake clears the ring", ringNotification())
    }

    @Test
    fun `Mute with Do Not Disturb access - vibrate for the ring, Mute again after`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        ring()
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        assertTrue(vibrator().isVibrating)
        awake()
        assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
    }

    @Test
    fun `Do Not Disturb priority with access - alarms through for the ring, yours again after`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        ring()
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, nm.currentInterruptionFilter)
        assertTrue(vibrator().isVibrating)
        awake()
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, nm.currentInterruptionFilter)
    }

    @Test
    fun `quiet trip on - the ring lifts it first`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        app.settings.setDndDuringTrips(true)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        app.settings.setDndRecord(DndRestore.Record(setByUs = true, previousFilter = NotificationManager.INTERRUPTION_FILTER_ALL))
        ring()
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, nm.currentInterruptionFilter)
        assertNull(app.settings.dndRecord())
        assertTrue(vibrator().isVibrating)
        assertTrue(app.settings.lastRing.value!!.contains("buzzed"))
    }

    @Test
    fun `Mute without access - it still tries to buzz, says it may not have, and the speaker joins at 30 s`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        ring()
        assertTrue(vibrator().isVibrating)
        assertNotNull(ringNotification())
        val last = app.settings.lastRing.value!!
        assertTrue(last, last.contains("may not have buzzed"))
        assertTrue(last, last.contains("speaker after 30 s"))
    }

    @Test
    fun `cold start - no trip running, a saved trip - rings, re-arms in a minute, and logs the ring in the trip`() {
        app.trips.save(trip())
        val before = System.currentTimeMillis()
        ring()
        assertTrue(vibrator().isVibrating)
        val at = app.scheduler.scheduledAtMs
        assertNotNull("re-ring safety net", at)
        assertTrue(at!! in before + 50_000..before + 70_000)
        val log = java.io.File(app.traces.dir, "trip-test.jsonl")
        assertTrue("the cold ring's report lands in the trip's log (PLAN A0)", log.exists() && log.readText().contains("\"ringd\""))
    }

    @Test
    fun `earbuds in - the ring goes to the earbuds, and it still buzzes`() {
        shadowOf(audio).setOutputDevices(
            listOf(org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder().setType(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()),
        )
        val svc = ring()
        assertTrue(vibrator().isVibrating)
        assertEquals(true, RingService.state.value?.headset)
        assertTrue(app.settings.lastRing.value!!.contains("earbuds"))
        svc.hashCode()
    }

    @Test
    fun `a test ring with nothing saved still rings`() {
        ring()
        assertTrue(vibrator().isVibrating)
        assertNotNull(ringNotification())
        assertTrue(app.settings.lastRing.value!!.startsWith("Last test ring"))
    }

    // ---- an update or a restart during a trip (PLAN Run 10, A1) ----

    private val am get() = app.getSystemService(android.app.AlarmManager::class.java)

    @Test
    fun `an update during a trip sets the alarm again and resumes tracking the same stop`() {
        app.trips.save(trip())
        val at = System.currentTimeMillis() + 120_000
        app.scheduler.set(at)
        RestartReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(at, am.nextAlarmClock?.triggerTime)
        val started = shadowOf(app).nextStartedService
        assertNotNull("tracking resumes", started)
        assertEquals("resume", started.action)
        assertEquals(false, started.getBooleanExtra("after_ring", true))
        assertNotNull("one tap to resume, if Android refuses", shadowOf(nm).getNotification(Notifications.ID_PAUSED))
    }

    @Test
    fun `after a restart, an alarm that passed while the phone was off rings now`() {
        app.trips.save(trip())
        app.scheduler.set(System.currentTimeMillis() - 60_000)
        val now = System.currentTimeMillis()
        RestartReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val at = am.nextAlarmClock?.triggerTime
        assertNotNull(at)
        assertTrue("now, not in the past and not later: $at vs $now", at!! in now - 1_000..now + 5_000)
    }

    @Test
    fun `no trip, nothing to protect - a restart sets no alarm`() {
        RestartReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(am.nextAlarmClock)
        assertNull(shadowOf(app).nextStartedService)
    }
}

