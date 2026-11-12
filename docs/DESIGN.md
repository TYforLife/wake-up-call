# Nodstop: design (v0.2: GPS-only, learns your routes)

Owner: Tae. Platform: Android (Samsung), Kotlin. Built mostly by Claude Code cloud sessions
started from a phone. No laptop in the loop: CI builds the APK, the phone downloads it.

Name: **Nodstop** ("nod off, don't miss your stop"), package `app.nodstop`. Final name and
application ID must be confirmed before the first Play release; the ID can't change after that.

---

## 1. Problem

Sleep on the bus and get woken reliably before the stop. Being woken too late is far worse
than being woken a little early.

Owner's commute (the first test):

| Route | Leg | Get off at |
|---|---|---|
| To work (board stop 3209, ~7:40) | 1 | Smales Farm Stop D (3353) |
| | 2 | 866 → Symonds Street Cemetery (1069), boarding at Smales Farm Stop A (3360) |
| From work (board Upper Symonds St 7151, ~17:05–17:10) | 1 | AUT City Campus (7069), awake, alarm off |
| | 2 | NX2 → Smales Farm Stop B (3355) |
| | 3 | Bond Crescent (3230), boarding at Smales Farm Stop C (3362) |

Both routes are seeded with stop names only. **There are no time inputs anywhere**: you set where
you get off (paste a Google Maps point, or stand at the stop and tap "Use my location"; close is
fine), and GPS does the rest. After a ride or two the app learns where you really get off and the
way the bus goes (§2.3). Personal coordinates stay on the phone, never in the repo.

Earbuds are usually in; otherwise the phone is in hand, pocket or bag.

**Success metric** (after a week of rides): the alarm rings **30–60 s before the stop** on at least
90% of legs once the route is learned, never less than 25 s, and zero missed stops.

**What the first rides taught us (v0 → v0.2):** v0 aimed 2.5 min out, used straight-line distance
and "the bus could get back to its top speed of the last 5 min". On the 866 (motorway, then slow city
streets) that rang many minutes early. On the way home it never rang at the right stop: leg progress
depended on typed ride/wait minutes and a 150 m "arrived" radius at a roughly pasted stop, so the trip
stalled on the AUT leg and later alarms were mis-attributed. v0.2 removes every time input, aims for
~45 s, learns routes, and detects boarding and getting off from GPS.

Out of scope for now: AT real-time API, AT stop search, maps, iOS, Play Store, accounts, backend,
the phone speaker as a default ring.

---

## 2. The core idea: a dead-man's switch

> **Whenever there is a stop left to protect, exactly one system alarm is scheduled
> (`AlarmManager.setAlarmClock`). The live GPS loop keeps pushing it forward. Nothing else rings.**

On every good GPS fix (time `ft`) for the leg you might sleep on, the engine schedules the earlier of:

- **wakeAt**: `ft + d / v_est − LEAD`, with `LEAD` = 45 s (owner's target: 30–60 s before the stop).
  - `d`: distance left **along the learned route** (× 0.95) when the fix matches a past ride, else the
    straight line, to the stop (the learned one if known, else the pasted one).
  - `v_est = max(V_FLOOR, avg15, v_route)`:
    - `avg15`: current speed, path length ÷ time over the last 15 s.
    - `v_route` on a learned route: `d ÷ the fastest time any past ride took from here`. That's how fast a
      bus really covers the rest: it knows the last stretch is slow city streets, and that a bus held at
      a light near the stop can pull away fast. On a first ride, the fastest 15-s average of the last 30 s.
    - `V_FLOOR` = 25 km/h (halved on a learned route). It also means "ring by ~310 m" when stopped.
- **failsafeAt**: when the alarm must ring if no further fixes arrive, assuming the bus could go as
  fast as `V_MAX`: `ft + max(DEADMAN_MIN, straight / V_MAX − LEAD)`. Measured on the straight line.
- `scheduled = max(now, min(wakeAt, failsafeAt))`.

While fixes keep arriving (every 1–3 s), `failsafeAt` keeps being pushed forward and never fires.
Before the first fix of a trip, the phone's **last known position** (up to 3 min old) arms the
failsafe from its own time, so a trip that never gets a fix is still covered. With no position at
all, a **No GPS** alarm rings 3 min in: you're unprotected, stay awake.

### 2.1 Phases of a leg (no timers)

| Phase | Meaning | Alarm protects |
|---|---|---|
| WAITING | At the stop, not on a bus yet | this leg (wake/failsafe as above) |
| RIDING | Boarding detected: displacement speed ≥ 15 km/h over 20 s | this leg |
| RINGING | The alarm is ringing | nothing scheduled (RingService is ringing) |
| AWAKE | You held "I'm awake" for this stop | the **next** alarm-on leg's failsafe; plus "rode past" watch |
| (leg done) | You got off: within 300 m of the stop, under 8 km/h, for 45 s in a row | next leg starts WAITING |

- **Getting off ends the leg**, not an ack and not a timer. That 45 s is you standing at the transfer
  or walking away; a bus dwell is shorter. The spot where that slow spell began is where you got off.
- **An ack only counts as "awake for this stop" when the stop was ≤ 3 min away.** An early safety ring
  (GPS lost far out, No GPS) is acked and tracking continues, so it rings again near the stop. After
  an ack, alarms from fixes older than the ack can't ring for 2 min (no instant re-ring).
- **Rode past after "I'm awake"** (once within 300 m, now 300 m beyond it at bus speed): rings again,
  "You passed your stop". Acking that ends the leg.
- **Passed the stop without ringing** (a badly pasted stop): rings now.
- **A light nudge at the stop**: once per leg, when you're awake (or the leg's alarm is off), within
  60 m of the stop, or stopped within 200 m: one short buzz (through Do Not Disturb, no sound) and
  "You're at Smales Farm, get off now". For when you're not looking out the window.
- **Alarm-off legs** (you're awake) are tracked the same way; they arm the next alarm-on leg's failsafe.

### 2.2 What this buys

| Failure | What happens |
|---|---|
| App process killed (Samsung) | The last scheduled alarm still fires, no later than safe. After "I'm awake", the app resumes the same leg from the saved trip, awake for it or still tracking it depending on why it rang. |
| GPS lost, app alive | Updates stop, so the failsafe fires (early-biased); acking it keeps tracking. |
| GPS never gets a fix | Failsafe from the last known position; with none, a No GPS ring at 3 min. |
| Phone silent / DND | System alarm, alarm-usage vibration, earbuds (media stream), plus a full-screen intent. |
| Bus faster than expected | `V_MAX` in the failsafe, `avg15`, and past rides' fastest times in wakeAt. |
| Bus slower than expected | Rings a bit early. |
| Roughly pasted stop | Learned from where you got off (§2.3); until then, a point past the real stop costs ~d/v seconds. |

There is a single ring path: `AlarmManager` → `AlarmReceiver` → `RingService` → `AlarmActivity`.
"Ring now" means scheduling the alarm at `now`. The at-stop nudge is a single short vibration and a
notification, not the alarm.

### 2.3 Route learning

When a leg ends with you getting off (and you'd boarded), the engine emits a ride: the trail (a point
every ~25 m from boarding to the point nearest where you got off, each with the distance and time
left on that ride) and the get-off spot. The app stores it per pasted stop (`learned.json`, on the
phone only):

- **Stop**: a running average of where you got off (ignored if > 300 m from the pasted stop).
- **Tracks**: the newest 5 rides.

On later rides each fix is matched onto the tracks (nearest segment within 60 m). The distance left is
the smallest over the matching tracks, × 0.95, so GPS noise or a cut corner never makes it too long.
`v_route` uses the fastest past time from that point. Off the learned route, the engine falls back to
the straight line. Changing a stop's coordinates starts learning afresh; "Forget" is in the route editor.

### Why the approximations err early

- Straight-line `d` is never more than the remaining road distance; the learned distance is scaled down.
- `v_est` takes the fastest of the current speed, the floor and the fastest past time from here.
- The failsafe assumes `V_MAX` on the straight line.

### The invariant (the spec the tests enforce)

> From the first known position, for any trip whose speed never exceeds `V_MAX`, and which, inside
> the last `(LEAD + DEADMAN_MIN) × V_MAX ≈ 1.9 km`, never goes faster than the fastest 15-s average the
> phone has observed in the last 30 s (or `V_FLOOR`), at every instant the scheduled alarm time is no
> later than `trueArrival − LEAD + TOLERANCE` (`TOLERANCE` 20 s, so **never less than 25 s ahead**),
> and that holds even if all updates stop at that instant.

Inside ~1.9 km the 30 s dead-man minimum dominates the failsafe, so safety rests on the speed estimate.
No estimate that only looks backwards can promise anything about a bus that suddenly goes faster than
it has been seen going. Past rides narrow that gap (`v_route`). Outside that radius the failsafe alone
guarantees the bound for any speed up to `V_MAX`. The property test checks it has teeth: the same
trips break the bound without the failsafe, or without `V_FLOOR`.

---

## 3. Parameters (engine `Config`, all overridable)

| Name | Default | Notes |
|---|---|---|
| `LEAD` | 45 s | Ring this long before the estimated arrival (target 30–60 s). |
| `V_FLOOR` | 25 km/h | Stops ETA blowing up when stopped; ≈ "ring by 310 m". Halved on a learned route. |
| `V_MAX` | 90 km/h | Worst-case bus speed, used by the failsafe. |
| `WIN_SHORT` | 15 s | Current speed (path length ÷ time). |
| `WIN_PEAK` / `PEAK_AVG` | 30 s / 15 s | First rides only: fastest 15-s average of the last 30 s. |
| `DEADMAN_MIN` | 30 s | Smallest push-forward. |
| `MAX_ACCURACY` / `MAX_FIX_AGE` | 50 m / 10 s | Ignore worse or older fixes. |
| `NO_FIX` | 180 s | No position at all: "No GPS" ring. |
| Boarding | 15 km/h over 20 s | Displacement speed. |
| Getting off | 300 m, < 8 km/h, 45 s | Continuous. Its start is where you got off. |
| `OVERSHOOT_NEAR` / `GROW` | 300 m / 300 m | At bus speed (≥ 15 km/h). |
| Nudge | 60 m, or stopped within 200 m | Once per leg. |
| `ACK_COUNTS_WITHIN` | 180 s | Earlier acks keep tracking the stop. |
| `REARM` | 120 s | After an ack, stale fixes can't ring sooner. |
| Learning | 60 m match, × 0.95, 25 m trail, 5 tracks, 300 m max stop shift | §2.3 |

---

## 4. Engine (`:engine`, pure Kotlin/JVM, zero Android imports)

A deterministic state machine: `step(state, input) -> (state, output)`.
No clocks, no randomness, no I/O. Time arrives in inputs.

### Types (see `Model.kt`)

- `Leg(name, dest, alarmEnabled, learned: Learned?)`: no times. `Learned(stop, tracks, rides)`;
  `Track(points: [TrackPoint(lat, lng, toEndM, toEndS)])`.
- `Input`: `Start(tMs, legs, awakeOnFirst, lastKnown: Fix?)`, `FixIn`, `Tick`, `Rang`, `Ack`, `SkipLeg`, `Stop`.
- `Phase`: IDLE, WAITING, RIDING, RINGING, AWAKE, DONE. `AlarmReason`: WAKE, FAILSAFE, NO_GPS,
  OVERSHOOT, NEXT_LEG (shown on the alarm screen).
- `Output(alarmAtMs, overshoot, arrived, boarded, learned: LearnedUpdate?, status, events)`.
  `status` has the phase, the distance (along the route or straight), a realistic ETA for display
  (past rides' typical time, or current speed), the alarm time and reason, and learned-ride count.

`RingPlan` (the ring ladder, §5) and `CoordParser` (pasted coordinates) also live in the engine.

### Behaviour

§2.1 is the behaviour. Also:
- **Tick**: no scheduling changes; updates GPS age for the UI.
- **SkipLeg** ("I'm off this bus"): ends the leg at once (not learned).
- **Stop**: `alarmAt = null`, DONE.
- A cold start with no state cannot defer anything. This is deliberate: the receiver always rings.

### Trace format (shared by the app logger and the test replay)

JSON Lines, one object per line, `kotlinx.serialization`:

```
{"t":1731300000000,"k":"fix","lat":-36.78,"lng":174.75,"acc":8.0,"ft":1731299999500}
```

Kinds: `start` (with `legs` including what's learned, optional `awake` and last-known `lat/lng/acc/ft`),
`fix`, `tick`, `rang`, `ack`, `skip`, `stop`, `out` (`alarmAt, ar` (reason), `overshoot, arrived, boarded,
d, eta, v, leg, ph`), plus app annotations ignored by replay: `rate` and `note`.

Replaying the inputs of a recorded trace must reproduce the recorded `out` lines exactly.

### Required tests (`:engine:test`, all must pass before any push)

Synthetic trips from the generator in `engine/src/test`: polylines, speed profiles with acceleration
limits and stops, and time-correlated GPS error (AR(1), τ = 20 s, plus 1 m white noise).

1. Steady 50 km/h straight approach: rings 30–60 s before arrival (20 seeds).
2. Busway 85 km/h: at least 25 s.
3. Winding route (1.4× straight line): first ride errs early (≥ 45 s); the learned route rings 30–60 s.
4. Stop-and-go averaging 15 km/h: between 25 s and 3 min.
5. Crawl at 10 km/h, then 85 km/h from 1.9 km out: at least 25 s.
6. GPS dropout 1.9 km out at 90 km/h: the last scheduled alarm is at least 25 s before arrival.
7. No fix ever: with a last known position, still ≥ 25 s; with none, a No GPS ring at 3 min that an ack doesn't end.
8. Jitter: fixes over 50 m accuracy, or stale, are ignored (alarm and state unchanged).
9. Overshoot while ringing: emitted once.
10. Two legs with an 8 min transfer, no times entered: both ring 30–60 s early; leg 1 ends by you getting off and waiting; no ring during the wait; boarding the second bus is detected.
11. Invariant property test: 2,000 seeded random trips (random polylines, piecewise speeds ≤ `V_MAX`,
    noise, bad and stale fixes, dropouts and kill points). At every step, assert §2's invariant. Plus a
    teeth check (violations without the failsafe or without `V_FLOOR`).
12. Replay test: every `*.jsonl` in `engine/src/test/resources/traces/` reproduces its `out` lines
    (the folder starts empty); a synthetic trip also round-trips through the format.

Extra tests:
- motorway then slow city (the 866 complaint): 64 s first ride, 44 s learned
- a red light near the stop, with and without a learned route
- **the evening commute regression**: an alarm-off hop, two transfers, rings for both sleep legs, none while waiting
- a roughly pasted stop corrected by where you got off
- an early failsafe ring that doesn't use up the leg
- riding past after an ack
- the at-stop nudge, boarding detection, resume awake
- learning: track building, matching, merging

---

## 5. Android shell (`:app`)

Kotlin, Jetpack Compose (Material 3), single activity plus `AlarmActivity`.
No DI framework, no Room, no Hilt: manual wiring (`App`), JSON in app storage.

Dependencies: Compose BOM, `activity-compose` (hosts Compose), `play-services-location`,
`kotlinx-serialization-json`; JUnit 5 for tests. Nothing else without a reason written in the PR.

`minSdk 31`, `targetSdk`/`compileSdk` 36. Package `app.nodstop`.

### Components

| Component | Job |
|---|---|
| `TripService` | Foreground service, type `location`. Started from the UI (foreground), so no background-location permission is needed. Gets the last known position, then fused location at HIGH_ACCURACY every 2 s; fix age measured on the monotonic clock. Feeds the engine, applies `alarmAtMs`, buzzes once at the stop, stores learned rides, and runs the live trip notification (§5 Notifications). A `Tick` every 5 s. Writes the trace. Saves the active trip (legs, leg, alarm reason, time left) on every change and every 15 s. |
| `AlarmScheduler` | Wraps `AlarmManager.setAlarmClock(AlarmClockInfo(t, showIntent), receiverPI)` with one fixed request code, so rescheduling replaces it. Cancels on null. |
| `AlarmReceiver` | Always starts `RingService`, with no conditions. Sends `Rang` to the engine if the process is alive. |
| `RingService` | Foreground service (type `systemExempted` on Android 14+, allowed for apps holding `USE_EXACT_ALARM`; `mediaPlayback` before that or if refused). Posts the full-screen-intent notification on the `alarm` channel (the channel itself is silent; the service plays the audio and vibration), plays the ring ladder, holds a partial wake lock. |
| `AlarmActivity` | Shows over the lock screen and turns the screen on. **Ways to say "I'm awake"**: hold anywhere on the bottom half for 1 s (it fills up and buzzes), hold volume down for 1.5 s (works by feel, in a pocket), or the **I'm awake** button on the alarm notification (for when you're using the phone and it shows as a banner). If Nodstop itself is open, the alarm takes over its screen (0.6 s hold). A tap, a pocket brush, back, or a quick volume press does nothing. Shows the stop, why it rang (No GPS, GPS lost, passed your stop), and earbuds/vibration. **With no trip service alive** (a cold start after a kill), "I'm awake" resumes the same leg from the saved trip: awake for it if the ring was for this stop, still tracking it if it was an early safety ring. Started from a visible screen, so Android allows it. |
| `LocationSource` | Interface. `FusedLocationSource` (real; also last-known and one-shot "Use my location") and `SimulatedLocationSource` (a pretend bus in real time: halts 90 s at stop 1, drives 3 km to stop 2, halts 2 min, then keeps going, for couch tests). |
| `LearnedStore` | What's learned per pasted stop (`learned.json`, phone only). Forget per stop (route editor) or all (Debug). |
| `TraceLogger` | Writes JSONL per trip to app files, flushed per line. Share uses the system share sheet: **location hidden** (every position moved by one random offset, engine re-run, `Trace.anonymize`) for the public repo, or raw (private). |
| Quick Settings tiles, launcher shortcuts | One tile per first two routes ("To work", "From work"): one tap opens the app and starts the route. Tapping while a trip runs just shows it. Long-press the app icon for the same. |

### Ring ladder (earbuds, then vibration)

Headset = any output device of type BT A2DP, BLE headset, wired headset or headphones, or USB headset.

| Time | Earbuds connected | No earbuds |
|---|---|---|
| 0–15 s | Tone in the earbuds at 40% media volume (`USAGE_MEDIA`, exclusive transient audio focus so music or podcasts pause), gentle vibration pulses | Gentle vibration pulses |
| 15–30 s | Earbuds at 70%, stronger pulses | Stronger pulses |
| 30 s+ (or overshoot) | Earbuds at 100%, continuous strong vibration | Continuous strong vibration |
| 60 s+ (or overshoot), **only if "speaker last resort" is on** (off by default) | Phone speaker, `USAGE_ALARM` at 100% | Same |

- Vibration uses alarm usage, so it gets through DND when alarms are allowed.
- If the earbuds disconnect mid-ring, the tone stops at once (device callback, "becoming noisy",
  and an audio-routing listener), so it never leaks to the phone speaker. Vibration carries on.
- Restore the user's volumes after Ack. The ring never auto-stops; only Ack or Stop trip end it.
- Known limits: connected doesn't mean in your ears (an open case in a bag), and vibration alone
  may not wake you with the phone in a bag. These routing assumptions must be confirmed on the
  device in the couch test.

### Notifications

- **Trip progress** (silent): "On the bus → Smales Farm", "1.2 km · ~3 min · learned route", a progress
  bar. On Android 16 it's a Live Update: promoted to the lock screen and status bar chip with the
  distance (`POST_PROMOTED_NOTIFICATIONS`; Readiness shows whether it's allowed).
- **At your stop**: the one-buzz nudge, "You're at Smales Farm, get off now".
- **Wake-up alarm**: full-screen intent, colourised, with an **I'm awake** action.

### Permissions

`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
`USE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `WAKE_LOCK`, `VIBRATE`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `MODIFY_AUDIO_SETTINGS` (media volume during the ring),
`POST_PROMOTED_NOTIFICATIONS` (Android 16 Live Updates).

### Screens (v0)

- **Home**: list of Routes, Start per route (or the Quick Settings tile), the running trip (or an "interrupted" card if the app
  was killed mid-trip, with the alarm time still set), Stop trip (confirmed), a "How was the ring
  timing?" card after each trip (too early / good / too late per leg, written to the trip log).
  Shows the build number and short SHA (from BuildConfig) at the bottom.
- **Route editor**: name, plus 1–3 legs. Each leg has a name, where you get off (paste a point or a
  Maps link, or **Use my location** at the stop), and an alarm on/off toggle. **No times.** Shows what's
  been learned ("learned from 3 rides: real stop is 85 m from this point", Forget).
  The parser accepts `-36.78123, 174.75123`, `-36.78123 174.75123`, and Google Maps URLs containing
  `@lat,lng` or `q=lat,lng` / `query=lat,lng`. Warn if the point is outside New Zealand.
- **Trip**: Waiting for the bus / On the bus / Get off at X, a progress bar, distance (along the route or
  straight), a realistic ETA, speed, GPS age, alarm time and why, how much is learned, plus
  "I'm off this bus" (SkipLeg).
- **Readiness**: a green or red row per check, each with a button that opens the right settings page:
  notifications, fine location, `canUseFullScreenIntent()`, `canScheduleExactAlarms()`,
  ignoring battery optimisations, alarm-stream volume above 0 (only when the speaker is on). The
  speaker-last-resort toggle. Plus static Samsung instructions:
  - Settings → Apps → Nodstop → Battery → Unrestricted
  - Settings → Battery → Background usage limits → make sure it's not in Sleeping / Deep sleeping apps;
    add it to Never sleeping apps
  - Never Force stop the app; Android cancels its alarms when you do.
- **Debug**: Test ring in 30 s (the real AlarmManager path; disabled during a trip), Simulated trip
  (§5 LocationSource), **Kill app now** (`Process.killProcess`), Forget all learned stops, Share last
  trip log, a view of the last 200 trace events.

---

## 6. Build and delivery (phone-only)

- Gradle project: `:engine` (Kotlin JVM) and `:app`. Gradle wrapper committed. Versions in
  `gradle/libs.versions.toml`.
- Debug signing: `keystore/debug.keystore` (standard debug credentials) is committed and
  `signingConfigs.debug` points at it, so every CI build installs over the previous one. It is never used for release.
- `versionCode = GITHUB_RUN_NUMBER` (1 locally); `BuildConfig.GIT_SHA`.
- GitHub Actions `.github/workflows/android.yml`:
  - On push to any branch, plus `workflow_dispatch`.
  - `build` job: per-branch concurrency (a new push cancels only that branch's older run).
    `actions/setup-java` (Temurin 21) and `gradle/actions/setup-gradle`;
    `./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug`.
  - `publish` job: `concurrency: debug-release`, cancel in progress; `permissions: contents: write`;
    publishes with the `gh` CLI to the rolling prerelease `debug-latest`: create it if missing,
    `gh release upload ... app-debug.apk --clobber`, and set the notes to branch, SHA and build number.
- Phone install URL: `https://github.com/tyforlife/wake-up-call/releases/download/debug-latest/app-debug.apk`
  (signed in to GitHub in the phone browser; allow "Install unknown apps" for that browser once).
  The newest push on any branch wins: check the build number on Home.

Cloud sessions have no emulator and no device. Verify via JVM tests, lint and `assembleDebug`.
Anything device-only goes on the couch-test checklist in the PR description.

---

## 7. Test plan

### Couch test (about 20 min)

1. Readiness all green (the Live trip progress row is optional); Samsung steps done.
2. Test ring in 30 s → lock the phone → it rings. Try each way to stop it: hold the bottom half (1 s);
   hold volume down (1.5 s); with the phone unlocked in another app, the banner's **I'm awake** button;
   with Nodstop open, the in-app alarm. A tap or a quick volume press must not stop it.
   Repeat with: silent mode, DND on, earbuds in (rings in the earbuds only, escalating, stops instantly
   if you take them out), and with the app swiped away from recents.
3. Simulated trip (6 km at 50 km/h) → lock the phone → watch the lock-screen progress → it rings about
   45 s before stop 1 (about 6.5 min in). Hold "I'm awake" → one light buzz at the stop → the pretend
   bus waits 90 s (leg 1 ends) → stop 2 rings about 45 s before it.
4. Don't ack a sim ring: when the pretend bus leaves the stop, "You passed your stop" appears and the ring maxes out.
5. Kill test: simulated trip, **Kill app now** after 1 min → it must still ring (note when). Hold
   "I'm awake" → the Trip screen shows the trip again and it carries on to stop 2.
6. Route editor: stand somewhere and tap **Use my location**: a ✓ point appears.
7. Share the log and confirm the file opens.

### Rides

1. Set each get-off stop (paste, or Use my location while you're there). Close is fine.
2. First ride on each route: stay awake, with a normal phone alarm as a backup. Tap Start (or the
   tile) at the stop; the Trip screen should go from "Waiting for the bus" to "On the bus" once it moves.
3. Note when it rang and how far you were from the stop; answer the ring-timing card. From the second
   ride on, the Trip screen says "learned route" and the timing should tighten to 30–60 s.
4. Afterwards: Debug → **Share last trip log (location hidden)** → upload it under
   `engine/src/test/resources/traces/` (GitHub web, "Add file → Upload files"). The hidden export moves
   every position far away and re-runs the engine, so it replays exactly but doesn't show your streets.
   Never upload the raw log to this public repo.

---

## 8. Roadmap (after v0 is trusted)

**Rule for everything below:** live data from AT can only make an alarm earlier, never later. The
GPS dead-man's switch stays the guaranteed fallback; ghost buses or a dead feed degrade to GPS-only.

- **v1, AT live (needs an AT developer key, kept on the phone, never in the repo or APK):**
  - Stop search: bundle `stops.txt` from AT's static GTFS as a generated asset; search by name or
    stop number, so no more pasting coordinates.
  - Match you to your bus: pick the route; the app finds the AT vehicle that has moved with you for about a minute.
  - Strip view: your stops in a line with a bus marker ("3 stops · ~6 min"). No map library.
  - AT predicted arrival at your stop as one more estimate (the engine takes the earliest).
  - Departures board for any stop.
  - **Stop alarm**: sleep at the stop; ring when your bus is about 4 min away so you can stand up
    and hail it (Auckland buses only stop if hailed). Backup: timetable time minus the lead.
    "Timetable only" when AT shows no live vehicle yet.
- **v2:** recorded-route matching (ride once, keep the trace as the route's path, use along-route
  distance; removes most of the early bias), a map view, adaptive GPS rate far from the stop (battery).
- **Public release:** a caching proxy (for example a Cloudflare Worker) with one AT key, AT's
  permission (§9), Play declarations (exact alarm, full-screen intent, location foreground service
  with a demo video, and the battery-optimisation exemption request, which Play restricts), a
  privacy policy ("your location never leaves the phone"), a real release key in GitHub secrets,
  battery steps for other phone brands, onboarding with first-ride safety notes, and a
  transit-provider interface so other cities (Wellington Metlink, Christchurch) can follow.
- **Later:** the phone speaker as a default escalation, earbud tap gestures to stop the alarm, iOS.

## 9. Open questions

- AT API terms for a public app with optional donations. The "free for non-commercial" claim came
  from a third-party API directory, not from AT. Ask AT directly before any public release:

  > To: atapidev@at.govt.nz
  > Subject: Real-time API use in a free public app with optional donations
  >
  > Hi, I'm building a free Android app that wakes bus passengers before their stop, using AT's GTFS
  > and real-time feeds through a single server-side key with caching. The app would have no ads or
  > paid features, but might link to an optional "buy me a coffee" donation page. Would that count as
  > non-commercial use under the API terms? If not, what's the process for a commercial agreement?
  > Thanks, Tae

- Donations on the Play Store: put the link on the project page or README rather than in the app.
  Check Play's payments policy before adding any in-app tip button.
- Final name and application ID (Nodstop / `app.nodstop` for now): check the name is free on Play
  and as a domain before the first release.
