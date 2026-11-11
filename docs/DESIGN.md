Bus Alarm — Design (v0 → first ride test)
Owner: Tae. Platform: Android (Samsung), Kotlin. Built mostly by Claude Code cloud sessions
started from a phone. No laptop in the loop: CI builds the APK, the phone downloads it.
---
1. Problem
Sleep on the bus and get woken reliably before the stop. Being woken too late is far worse
than being woken a little early.
Owner's commute (the first test):
Leg 1: bus about 10 min → transfer stop
Wait about 8 min
Leg 2: bus about 25 min → final stop
Earbuds are usually in; otherwise the phone is in hand, pocket or bag.
Success metric (after a week of rides): wake lead time between 2 and 6 min on at least 95% of legs,
and zero missed stops.
Out of scope for v0: AT real-time API, AT stop search, maps, iOS, Play Store, accounts, backend.
---
2. The core idea: a dead-man's switch
> **There is always exactly one system alarm scheduled (`AlarmManager.setAlarmClock`).
> The live GPS loop keeps pushing it forward. Nothing else ever rings.**
On every good GPS fix the engine computes two times and schedules the earlier one:
wakeAt: the accurate estimate of when to ring, biased early.
`wakeAt = now + d / v_est − LEAD`
failsafeAt: when the alarm must ring if no further fixes arrive, assuming the bus could
go as fast as `V_MAX`.
`failsafeAt = now + max(DEADMAN_MIN, d / V_MAX − LEAD)`
`scheduled = min(wakeAt, failsafeAt)`
While fixes keep arriving (every 1–3 s), `failsafeAt` keeps being pushed forward and never
fires. It only fires if updates stop. Near the stop it sits about 30 s ahead.
What this buys:
Failure	What happens
App process killed (Samsung)	The last scheduled alarm still fires, no later than safe.
GPS lost, app alive	Updates stop, so failsafe fires (early-biased).
GPS never gets a fix	The initial no-fix backup fires at `legStart + expectedRide − LEAD`.
Phone silent / DND	System alarm, plus alarm audio stream, plus a full-screen intent.
Bus faster than expected	Covered by `V_MAX` in failsafe and the 15 s speed window in wakeAt.
Bus slower than expected	You get woken early (acceptable by design).
There is a single ring path: `AlarmManager` → `AlarmReceiver` → `RingService` → `AlarmActivity`.
"Ring now" means scheduling the alarm at `now`. There is no second code path to keep in sync.
Why both approximations err early
`d` is straight-line distance, which is never more than the remaining route distance.
`v_est = max(V_FLOOR, avgSpeed90s, avgSpeed15s)` uses the fastest recent speed, and that is at
least the closing speed along a winding route.
Both make the ETA estimate shorter than reality, so the alarm comes early. Recorded-route
matching (v2) removes most of that early bias later.
The invariant (the spec the tests enforce)
> For any trip whose speed never exceeds `V_MAX`, at every instant the scheduled alarm time
> is no later than `trueArrival − LEAD + TOLERANCE`, where `TOLERANCE` is 20 s, and that
> holds even if all updates stop at that instant.
---
3. Parameters (engine `Config`, all overridable)
Name	Default	Notes
`LEAD`	150 s	Ring this long before the estimated arrival.
`HEADS_UP`	300 s	One gentle vibration and notification at this ETA.
`V_FLOOR`	25 km/h	Stops ETA blowing up at red lights or stops.
`V_MAX`	90 km/h	Worst-case bus speed, used by failsafe.
`WIN_LONG` / `WIN_SHORT`	90 s / 15 s	Speed windows (path length ÷ time).
`DEADMAN_MIN`	30 s	Smallest push-forward.
`MAX_ACCURACY`	50 m	Ignore fixes that are worse.
`MAX_FIX_AGE`	10 s	Ignore stale or cached fixes.
`OVERSHOOT_NEAR` / `OVERSHOOT_GROW`	300 m / 300 m	Overshot = once `minD < NEAR`, `d > minD + GROW`.
---
4. Engine (`:engine`, pure Kotlin/JVM, zero Android imports)
A deterministic state machine: `step(state, input) -> (state, output)`.
No clocks, no randomness, no I/O. Time arrives in inputs.
Types (sketch)
```kotlin
data class LatLng(val lat: Double, val lng: Double)
data class Leg(
    val name: String,
    val dest: LatLng,
    val expectedRideMin: Int,     // e.g. 10, 25
    val expectedWaitMin: Int = 0, // wait BEFORE this leg, e.g. 8 for leg 2
    val alarmEnabled: Boolean = true,
)
data class Fix(val tMs: Long, val fixTimeMs: Long, val pos: LatLng, val accuracyM: Float)

sealed interface Input {
    val tMs: Long
    data class Start(override val tMs: Long, val legs: List<Leg>) : Input
    data class FixIn(override val tMs: Long, val fix: Fix) : Input
    data class Tick(override val tMs: Long) : Input        // every ~5 s from the service
    data class Rang(override val tMs: Long) : Input        // system alarm actually fired
    data class Ack(override val tMs: Long) : Input         // user held "I'm awake"
    data class SkipLeg(override val tMs: Long) : Input     // "I'm off this bus" button
    data class Stop(override val tMs: Long) : Input
}

enum class Phase { IDLE, RIDING, RINGING, DONE }

data class Output(
    val alarmAtMs: Long?,      // the ONE system alarm; null = cancel. Shell reschedules only if changed (>1 s).
    val headsUp: Boolean,      // edge-triggered, once per leg
    val overshoot: Boolean,    // edge-triggered while RINGING -> max escalation + "You passed your stop"
    val status: Status,        // legIndex, phase, distanceM, etaS, vEstKmh, lastFixAgeS, gpsOk
    val events: List<TraceEvent>,
)
```
Behaviour
Start: leg 0 active. `alarmAt = start + expectedRide − LEAD` (no-fix backup).
FixIn (accepted only if accuracy and age pass) while RIDING:
Update the speed windows, `d`, and `minD`.
`alarmAt = min(wakeAt, failsafeAt)`, clamped to `≥ now`.
If `etaEst ≤ HEADS_UP` and not yet sent this leg, emit `headsUp`.
Tick: no scheduling changes (the dead-man's switch handles silence). Updates `lastFixAgeS`
and `gpsOk` for the UI only.
Rang: phase becomes RINGING; `alarmAt = null` (RingService is ringing now).
FixIn while RINGING: overshoot check only.
Ack: advance to the next leg, or DONE. For the next leg,
`alarmAt = now + expectedWait + expectedRide − LEAD` (its no-fix backup). Then normal fixes take over.
Waiting at the transfer stop needs no special case: `v_est` falls to `V_FLOOR` and the ETA stays long.
SkipLeg: same as Ack but with no ring involved.
Leg with `alarmEnabled = false`: tracked, never scheduled; it advances when `d < 150 m`
and speed is under 5 km/h for 20 s.
Stop: `alarmAt = null`, DONE.
A cold start with no state cannot defer anything. This is deliberate: the receiver always rings.
Trace format (shared by the app logger and the test replay)
JSON Lines, one object per line, `kotlinx.serialization`:
`{"t":1731300000000,"k":"fix","lat":-36.78,"lng":174.75,"acc":8.0,"ft":1731299999500}`
Kinds: `start` (with legs), `fix`, `tick`, `rang`, `ack`, `skip`, `stop`, `out`
(engine output summary: `alarmAt, headsUp, overshoot, d, eta, v`).
Replaying the inputs of a recorded trace must reproduce the recorded `out` lines exactly.
Required tests (`:engine:test`, all must pass before any push)
Synthetic scenario tests, built with a small trace generator (a polyline, a speed profile and a fix interval):
Steady 50 km/h straight approach: rings 140–160 s before arrival.
Busway 85 km/h: rings at least 130 s before arrival.
Winding route (path 1.4× straight line): rings at least 150 s before arrival.
Stop-and-go traffic averaging 15 km/h: rings between 150 s and 12 min before arrival.
Crawl at 10 km/h, then accelerate to 85 km/h 4 km out: rings at least 130 s before arrival.
GPS dropout 4 km out while the bus continues at 90 km/h: the last scheduled alarm is at least 130 s before arrival.
No fix ever: alarm at `start + expectedRide − LEAD`.
Jitter: fixes with accuracy over 50 m, or stale fixes, are ignored (alarm unchanged).
Overshoot while ringing: `overshoot` emitted once.
Two legs (10 min, wait 8, 25 min): Ack on leg 1 arms leg 2's backup; no ring during the wait; leg 2 rings correctly.
Invariant property test: 2,000 seeded random trips (random polylines, piecewise speed
profiles ≤ `V_MAX`, random dropouts and kill points). At every step, assert the invariant in §2.
Replay test: every `*.jsonl` in `engine/src/test/resources/traces/` reproduces its `out` lines.
The folder starts empty; real ride logs get added after each ride.
---
5. Android shell (`:app`)
Kotlin, Jetpack Compose (Material 3), single activity plus `AlarmActivity`.
No DI framework, no Room, no Hilt: manual wiring, JSON in app storage.
Dependencies: Compose BOM, `play-services-location`, `kotlinx-serialization-json`. Nothing else
without a reason written in the PR.
`minSdk 31`, `targetSdk`/`compileSdk` = latest stable available (36 if it resolves).
Package `nz.busalarm.app` (placeholder).
Components
Component	Job
`TripService`	Foreground service, type `location`. Started from the UI (foreground), so no background-location permission is needed. Fused location at HIGH_ACCURACY, every 2 s. Feeds the engine, applies `alarmAtMs`, posts heads-up, and runs the ongoing notification ("Leg 2 · 3.4 km · ~6 min · GPS ok"). A `Tick` every 5 s. Writes the trace.
`AlarmScheduler`	Wraps `AlarmManager.setAlarmClock(AlarmClockInfo(t, showIntent), receiverPI)` with one fixed request code, so rescheduling replaces it. Cancels on null.
`AlarmReceiver`	Always starts `RingService`, with no conditions. Sends `Rang` to the engine if the process is alive.
`RingService`	Foreground service (type `systemExempted`, which is allowed for apps holding `USE_EXACT_ALARM`; if that fails on device, `mediaPlayback`). Posts the full-screen-intent notification on the `alarm` channel (the channel itself is silent; the service plays the audio), plays escalating audio and vibration, holds a partial wake lock.
`AlarmActivity`	Shows over the lock screen and turns the screen on. A big hold-for-1.5-s "I'm awake" button (a pocket brush can't dismiss it). Shows the leg name, "Get off at X", and an overshoot banner. Volume keys do not dismiss it.
`LocationSource`	Interface. `FusedLocationSource` (real) and `SimulatedLocationSource` (synthetic approach, for couch tests).
`TraceLogger`	Writes JSONL per trip to app files. "Share last trip log" uses the system share sheet.
Ring escalation (earbuds-aware)
Headset = any output device of type BT A2DP, BLE headset, wired, or USB headset.
Time	Earbuds connected	No earbuds
0–15 s	Alarm tone into the earbuds only (`USAGE_MEDIA`, exclusive transient audio focus so music or podcasts pause, media volume forced to 60%), plus continuous vibration	`USAGE_ALARM` tone at about 50% alarm volume, plus vibration
15–30 s	Earbuds at 100%	`USAGE_ALARM` at 80%
30 s+ (or overshoot)	Speaker, `USAGE_ALARM` at 100%, plus vibration	Same
Restore the user's volumes after Ack. The ring never auto-stops; only Ack or Stop trip end it.
These routing assumptions must be confirmed on the device in the couch test.
Permissions
`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` (and `_MEDIA_PLAYBACK` if used),
`USE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `WAKE_LOCK`, `VIBRATE`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
Screens (v0)
Home: list of Routes (e.g. "To work": [Transfer stop, Final stop]), Start per route, Stop trip.
Shows the build number and short SHA (from BuildConfig) at the bottom.
Route editor: name, plus 1–3 legs. Each leg has a name, a paste coordinates field,
expected ride minutes, expected wait minutes, and an alarm on/off toggle.
The parser accepts `-36.78123, 174.75123`, `-36.78123 174.75123`, and Google Maps URLs containing `@lat,lng` or `q=lat,lng`.
Warn if the point is outside New Zealand.
Trip: live status from the engine (leg, distance, ETA, v_est, GPS age, scheduled alarm time),
plus "I'm off this bus" (SkipLeg).
Readiness: a green or red row per check, each with a button that opens the right settings page:
notifications, fine location, `canUseFullScreenIntent()`, `canScheduleExactAlarms()`,
ignoring battery optimisations, alarm-stream volume above 0. Plus static Samsung instructions:
Settings → Apps → Bus Alarm → Battery → Unrestricted
Settings → Battery → Background usage limits → make sure it's not in Sleeping / Deep sleeping apps;
add it to Never sleeping apps
Never Force stop the app; Android cancels its alarms when you do.
Debug: Test ring in 30 s (the real AlarmManager path), Simulated trip (start N km
out at X km/h toward a leg's destination, in real time, through the full engine and alarm path),
Share last trip log, a view of the last 200 trace events.
---
6. Build and delivery (phone-only)
Gradle project: `:engine` (Kotlin JVM) and `:app`. Gradle wrapper committed.
Debug signing: commit `keystore/debug.keystore` (standard debug credentials) and point
`signingConfigs.debug` at it, so every CI build installs over the previous one. It is never used for release.
`versionCode = GITHUB_RUN_NUMBER` (1 locally); `BuildConfig.GIT_SHA`.
GitHub Actions `.github/workflows/android.yml`:
On push to any branch, plus `workflow_dispatch`. `concurrency: debug-release`, cancel in progress.
`actions/setup-java` (Temurin 21) and `gradle/actions/setup-gradle`.
`./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug`
`permissions: contents: write`; publish with the `gh` CLI to a rolling prerelease `debug-latest`:
create it if missing, `gh release upload ... app-debug.apk --clobber`, and set the notes to branch and SHA.
Phone install URL: `https://github.com/<owner>/<repo>/releases/download/debug-latest/app-debug.apk`
(signed in to GitHub in the phone browser; allow "Install unknown apps" for that browser once).
Cloud sessions have no emulator and no device. Verify via JVM tests and `assembleDebug`.
Anything device-only goes on the couch-test checklist in the PR description.
---
7. Test plan
Couch test (tonight, about 20 min)
Readiness screen all green; Samsung steps done.
Test ring in 30 s → lock the phone → it rings. Repeat with: silent mode, DND on, earbuds in
(it should start in the earbuds and go to the speaker at 30 s), and with the app swiped away from recents.
Simulated trip (3 km out, 50 km/h) → lock the phone → heads-up at about 5 min ETA, ring at about 2.5 min.
Kill test: simulated trip at 5 km and 60 km/h, then swipe the app away after 1 min. The alarm must
still ring early (dead-man's switch). Note how long it took.
Hold-to-ack works; a short tap does not dismiss.
Share the log and confirm the file opens.
First ride (tomorrow)
Stay awake on the first ride, and set a normal phone alarm as a backup.
Route "To work": leg 1 = transfer stop (10 min ride), leg 2 = final stop (wait 8, ride 25).
Tap Start when boarding bus 1. When it rings, note the time and how far you were from the stop.
Afterwards: share the log → upload it to the repo under `engine/src/test/resources/traces/`
(GitHub web, "Add file → Upload files") so it becomes a regression test.
---
8. Roadmap (after v0 is trusted)
AT stop search: bundle `stops.txt` from AT's static GTFS as a generated asset; search by name.
Recorded-route matching: ride once, keep the trace as the route's path, and use along-route
distance instead of straight-line. This removes most of the early bias, still with no external data.
AT real-time (optional booster): better ETA in traffic. Never required. Ghost buses only
degrade to GPS-only.
Adaptive GPS rate far from the stop (battery).
Public release: proxy with a cached feed (one AT key, one client), Play Store alarm-app declarations,
privacy policy.
9. Open questions
AT API terms for a public app with optional donations. The "free for non-commercial" claim came
from a third-party API directory, not from AT. Ask AT directly before any public release:
> To: atapidev@at.govt.nz
  > Subject: Real-time API use in a free public app with optional donations
  >
  > Hi, I'm building a free Android app that wakes bus passengers before their stop, using AT's GTFS
  > and real-time feeds through a single server-side key with caching. The app would have no ads or
  > paid features, but might link to an optional "buy me a coffee" donation page. Would that count as
  > non-commercial use under the API terms? If not, what's the process for a commercial agreement?
  > Thanks, Tae
Donations on the Play Store: put the link on the project page or README rather than in the app.
Check Play's payments policy before adding any in-app tip button.
