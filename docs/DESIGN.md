# Nodstop: design (v0 → first ride test)

Owner: Tae. Platform: Android (Samsung), Kotlin. Built mostly by Claude Code cloud sessions
started from a phone. No laptop in the loop: CI builds the APK, the phone downloads it.

Name: **Nodstop** ("nod off, don't miss your stop"), package `app.nodstop`. Final name and
application ID must be confirmed before the first Play release; the ID can't change after that.

---

## 1. Problem

Sleep on the bus and get woken reliably before the stop. Being woken too late is far worse
than being woken a little early.

Owner's commute (the first test):

| Route | Leg | Get off at | Ride | Wait before |
|---|---|---|---|---|
| To work (board stop 3209, ~7:40) | 1 | Smales Farm Stop D (3353) | ~10 min | – |
| | 2 | 866 → Symonds Street Cemetery (1069), boarding at Smales Farm Stop A (3360) | ~25 min | ~8 min |
| From work (board Upper Symonds St 7151, ~17:05–17:10) | 1 | AUT City Campus (7069) | short | – |
| | 2 | NX2 → Smales Farm Stop B (3355) | ~18 min | ~5 min |
| | 3 | Bond Crescent (3230), boarding at Smales Farm Stop C (3362) | ~7 min | ~5 min |

Both routes are seeded in the app with stop names and estimated minutes. Coordinates are blank
and must be pasted from Google Maps (search the stop number, long-press the pin, copy).

Earbuds are usually in; otherwise the phone is in hand, pocket or bag.

**Success metric** (after a week of rides): wake lead time between 2 and 6 min on at least 95% of
legs, and zero missed stops.

Out of scope for v0: AT real-time API, AT stop search, maps, iOS, Play Store, accounts, backend,
the phone speaker as a default ring.

---

## 2. The core idea: a dead-man's switch

> **There is always exactly one system alarm scheduled (`AlarmManager.setAlarmClock`).
> The live GPS loop keeps pushing it forward. Nothing else ever rings.**

On every good GPS fix the engine computes two times and schedules the earlier one. Both are
measured from the fix's own time `ft` (never later than now), which only makes them earlier:

- **wakeAt**: the accurate estimate of when to ring, biased early.
  `wakeAt = ft + d / v_est − LEAD`
- **failsafeAt**: when the alarm must ring if no further fixes arrive, assuming the bus could
  go as fast as `V_MAX`.
  `failsafeAt = ft + max(DEADMAN_MIN, d / V_MAX − LEAD)`
- `scheduled = max(now, min(wakeAt, failsafeAt))`

`v_est = max(V_FLOOR, avg90, avg15, peak)`, where `avg90` and `avg15` are path length ÷ time over
the last 90 s and 15 s, and **`peak` is the fastest 30-second average seen in the last 5 minutes**
(`WIN_PEAK`). The peak assumes the bus can get back to its recent top speed at any moment. Without
it, a bus held at a red light 1.5 km out that then runs straight in at 50 km/h rings ~95 s
before arrival. With it, the ring is ~150 s or more ahead. The peak uses 30-s rather than 15-s
averages because the maximum of noisy short averages reads high: 30-s averages halve that noise
and still capture any sustained speed. Cost: stop-and-go rides ring earlier; real ride logs will
show whether that hurts the 2–6 min target.

While fixes keep arriving (every 1–3 s), `failsafeAt` keeps being pushed forward and never
fires. It only fires if updates stop. Near the stop it sits about 30 s ahead.

Extra rules, all of which can only make an alarm earlier or keep a leg protected:

- **No speed information yet** (first seconds of a leg, or back from a dropout, when neither speed
  window spans 5 s): a fix may pull the alarm earlier, never later.
- **Passed the stop while still riding** (once `minD < 300 m`, `d > minD + 300 m`, for example a
  mis-pasted destination): ring now.
- **Transfer hold.** After an Ack you're still on the previous bus for about its remaining ETA
  (early-biased). Until then the next leg keeps its backup alarm and ignores fixes, then starts
  with fresh speed windows. Otherwise a short next leg (From work leg 3) would ring, and be
  acked away, while you're still on the NX2 at motorway speed.
- **Alarm-off legs** schedule the next alarm-on leg's backup, so a kill on an awake leg still
  protects the sleep leg.

What this buys:

| Failure | What happens |
|---|---|
| App process killed (Samsung) | The last scheduled alarm still fires, no later than safe. After "I'm awake", the app resumes at the next leg from the saved trip (see §5). |
| GPS lost, app alive | Updates stop, so failsafe fires (early-biased). |
| GPS never gets a fix | The no-fix backup fires at `legStart + expectedWait + expectedRide − LEAD`. |
| Phone silent / DND | System alarm, alarm-usage vibration, earbuds (media stream), plus a full-screen intent. |
| Bus faster than expected | Covered by `V_MAX` in failsafe, the 15 s window and the recent peak in wakeAt. |
| Bus slower than expected | You get woken early (acceptable by design). |

There is a single ring path: `AlarmManager` → `AlarmReceiver` → `RingService` → `AlarmActivity`.
"Ring now" means scheduling the alarm at `now`. There is no second code path to keep in sync.

### Why both approximations err early

- `d` is straight-line distance, which is never more than the remaining route distance.
- `v_est` uses the fastest of the recent speeds, which is at least the closing speed along a
  winding route.

Both make the ETA estimate shorter than reality, so the alarm comes early. Recorded-route
matching (v2) removes most of that early bias later.

### The invariant (the spec the tests enforce)

> For any trip whose speed never exceeds `V_MAX`, and which, inside the last
> `(LEAD + DEADMAN_MIN) × V_MAX ≈ 4.5 km`, never goes faster than the fastest 30-s average
> the phone has observed in the last `WIN_PEAK` (or `V_FLOOR`), at every instant the scheduled
> alarm time is no later than `trueArrival − LEAD + TOLERANCE`, where `TOLERANCE` is 20 s, and
> that holds even if all updates stop at that instant.

Why the precondition: inside ~4.5 km the 30 s dead-man minimum dominates the `V_MAX` failsafe,
so safety rests on the speed estimate. No estimate that only looks backwards can promise anything
about a bus that suddenly goes faster than it has recently been seen to go. Outside that radius
the failsafe alone guarantees the bound for any speed up to `V_MAX`. The property test also checks
that it has teeth: the same trips break the bound without `WIN_PEAK` (≈ 1 min late) or without the
failsafe (≈ 33 min late).

Also: the no-fix backup is only as good as `expectedRideMin`. **Enter your fastest usual ride**;
the route editor says so.

---

## 3. Parameters (engine `Config`, all overridable)

| Name | Default | Notes |
|---|---|---|
| `LEAD` | 150 s | Ring this long before the estimated arrival. |
| `HEADS_UP` | 300 s | One gentle vibration and notification at this ETA. |
| `V_FLOOR` | 25 km/h | Stops ETA blowing up at red lights or stops. |
| `V_MAX` | 90 km/h | Worst-case bus speed, used by failsafe. |
| `WIN_LONG` / `WIN_SHORT` | 90 s / 15 s | Speed windows (path length ÷ time). |
| `WIN_PEAK` / `PEAK_AVG` | 300 s / 30 s | Recent peak: fastest 30-s average in the last 5 min. 0 disables. |
| `DEADMAN_MIN` | 30 s | Smallest push-forward. |
| `MAX_ACCURACY` | 50 m | Ignore fixes that are worse. |
| `MAX_FIX_AGE` | 10 s | Ignore stale or cached fixes. |
| `OVERSHOOT_NEAR` / `OVERSHOOT_GROW` | 300 m / 300 m | Overshot = once `minD < NEAR`, `d > minD + GROW`. |
| `MIN_SPAN` | 5 s | A speed window needs this much fix-time span to count. |
| Arrival (alarm-off legs) | 150 m, 5 km/h, 20 s | Advance when within 150 m and displacement speed < 5 km/h for 20 s. |
| `MAX_HOLD` | 20 min | Longest transfer hold. |

---

## 4. Engine (`:engine`, pure Kotlin/JVM, zero Android imports)

A deterministic state machine: `step(state, input) -> (state, output)`.
No clocks, no randomness, no I/O. Time arrives in inputs.

### Types

```kotlin
data class LatLng(val lat: Double, val lng: Double)
data class Leg(
    val name: String,
    val dest: LatLng,
    val expectedRideMin: Int,     // e.g. 10, 25 — your FASTEST usual ride
    val expectedWaitMin: Int = 0, // wait BEFORE this leg, e.g. 8 for leg 2
    val alarmEnabled: Boolean = true,
)
data class Fix(val tMs: Long, val fixTimeMs: Long, val pos: LatLng, val accuracyM: Float)

sealed interface Input {
    val tMs: Long
    data class Start(override val tMs: Long, val legs: List<Leg>, val holdUntilMs: Long? = null) : Input
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
    val status: Status,        // legIndex, phase, distanceM, etaS, vEstKmh, lastFixAgeS, gpsOk, alarmAtMs, holdUntilMs
    val events: List<TraceEvent>,
)
```

`RingPlan.step(elapsed, headset, speakerLastResort, overshoot)` (the ring ladder, §5) and
`CoordParser` (pasted coordinates) also live in the engine: pure, tested logic.

### Behaviour

- **Start**: leg 0 active, `alarmAt = start + expectedWait + expectedRide − LEAD` (no-fix backup;
  leg 0's wait is 0 for a fresh trip and non-zero only when resuming). Optional `holdUntilMs`.
- **FixIn** (accepted only if accuracy and age pass, and newer than the last fix) while RIDING:
  update the speed windows, peak, `d` and `minD`; `alarmAt = max(now, min(wakeAt, failsafeAt))`,
  plus the extra rules in §2. If `eta ≤ HEADS_UP` and not yet sent this leg, emit `headsUp`.
  While a transfer hold is active, fixes are ignored for scheduling.
- **Tick**: no scheduling changes (the dead-man's switch handles silence). Updates `lastFixAgeS`
  and `gpsOk` for the UI only.
- **Rang**: phase becomes RINGING, `alarmAt = null` (RingService is ringing now).
- **FixIn while RINGING**: overshoot check only.
- **Ack**: advance to the next leg, or DONE. For the next leg,
  `alarmAt = now + expectedWait + expectedRide − LEAD` (its no-fix backup), with a transfer hold
  until the previous bus's estimated arrival. Then normal fixes take over, with fresh speed windows.
  Waiting at the transfer stop needs no special case: `v_est` falls to `V_FLOOR`.
- **SkipLeg**: same as Ack but with no ring involved and no hold (you're already off).
- **Leg with `alarmEnabled = false`**: tracked; schedules the next alarm-on leg's backup; advances
  when `d < 150 m` and displacement speed is under 5 km/h for 20 s.
- **Stop**: `alarmAt = null`, DONE.

A cold start with no state cannot defer anything. This is deliberate: the receiver always rings.

### Trace format (shared by the app logger and the test replay)

JSON Lines, one object per line, `kotlinx.serialization`:

```
{"t":1731300000000,"k":"fix","lat":-36.78,"lng":174.75,"acc":8.0,"ft":1731299999500}
```

Kinds: `start` (with `legs`, optional `hold`), `fix`, `tick`, `rang`, `ack`, `skip`, `stop`, `out`
(engine output summary: `alarmAt, headsUp, overshoot, d, eta, v, leg, ph`), plus app annotations
ignored by replay: `rate` (post-ride timing answer: `leg`, `msg` = too_early | good | too_late) and
`note` (e.g. "resumed after a cold-start ring").

Replaying the inputs of a recorded trace must reproduce the recorded `out` lines exactly.

### Required tests (`:engine:test`, all must pass before any push)

Synthetic scenario tests, built with a small trace generator in `engine/src/test` (a polyline, a
speed profile with acceleration limits, a fix interval; GPS error is correlated in time, AR(1)
with τ = 20 s plus 1 m white noise, as on real phones):

1. Steady 50 km/h straight approach: rings 140–160 s before arrival (checked over 20 seeds).
2. Busway 85 km/h: rings at least 130 s before arrival.
3. Winding route (path 1.4× straight line): rings at least 150 s before arrival.
4. Stop-and-go traffic averaging 15 km/h: rings between 150 s and 12 min before arrival.
5. Crawl at 10 km/h, then accelerate to 85 km/h 4 km out: rings at least 130 s before arrival.
6. GPS dropout 4 km out while the bus continues at 90 km/h: the last scheduled alarm is at least 130 s before arrival.
7. No fix ever: alarm at `start + expectedRide − LEAD`.
8. Jitter: fixes with accuracy over 50 m, or stale fixes, are ignored (alarm and state unchanged).
9. Overshoot while ringing: `overshoot` emitted once.
10. Two legs (10 min, wait 8, 25 min): Ack on leg 1 arms leg 2's backup; no ring during the wait; leg 2 rings correctly.
11. Invariant property test: 2,000 seeded random trips (random polylines, piecewise speed
    profiles ≤ `V_MAX`, noise, bad and stale fixes, random dropouts and kill points). At every
    step, assert the invariant in §2. Plus a teeth check (violations without `WIN_PEAK` or the failsafe).
12. Replay test: every `*.jsonl` in `engine/src/test/resources/traces/` reproduces its `out` lines.
    The folder starts empty; real ride logs get added after each ride. A synthetic trip also
    round-trips through the format.

Extra tests: red light near the stop (passes with `WIN_PEAK`, fails without), resume and hold,
short next leg after a motorway leg, alarm-off legs, passing the stop while riding, the ring
ladder, and the coordinate parser.

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
| `TripService` | Foreground service, type `location`. Started from the UI (foreground), so no background-location permission is needed. Fused location at HIGH_ACCURACY, every 2 s; fix age measured on the monotonic clock. Feeds the engine, applies `alarmAtMs`, posts heads-up, and runs the ongoing notification ("Leg 2/3 · 3.4 km · ~6 min · GPS ok"). A `Tick` every 5 s. Writes the trace. Saves the active trip (legs, leg, ETA) on every leg change and every 15 s. |
| `AlarmScheduler` | Wraps `AlarmManager.setAlarmClock(AlarmClockInfo(t, showIntent), receiverPI)` with one fixed request code, so rescheduling replaces it. Cancels on null. |
| `AlarmReceiver` | Always starts `RingService`, with no conditions. Sends `Rang` to the engine if the process is alive. |
| `RingService` | Foreground service (type `systemExempted` on Android 14+, allowed for apps holding `USE_EXACT_ALARM`; `mediaPlayback` before that or if refused). Posts the full-screen-intent notification on the `alarm` channel (the channel itself is silent; the service plays the audio and vibration), plays the ring ladder, holds a partial wake lock. |
| `AlarmActivity` | Shows over the lock screen and turns the screen on. A big hold-for-1.5-s "I'm awake" button (a pocket brush can't dismiss it). Shows the leg name, "Get off at X", whether it's ringing in the earbuds or vibrating, and an overshoot banner. Back and volume keys do nothing. **On "I'm awake" with no trip service alive** (the ring came from a cold start after a kill), it starts `TripService` to resume at the next leg from the saved trip, with a transfer hold until the saved ETA. That start happens from a visible screen, so Android allows it. |
| `LocationSource` | Interface. `FusedLocationSource` (real) and `SimulatedLocationSource` (straight-line approach in real time, continuing past the stop, for couch tests). |
| `TraceLogger` | Writes JSONL per trip to app files, flushed per line. "Share last trip log" uses the system share sheet. |
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

### Permissions

`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
`USE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `WAKE_LOCK`, `VIBRATE`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `MODIFY_AUDIO_SETTINGS` (media volume during the ring).

### Screens (v0)

- **Home**: list of Routes, Start per route, the running trip (or an "interrupted" card if the app
  was killed mid-trip, with the alarm time still set), Stop trip (confirmed), a "How was the ring
  timing?" card after each trip (too early / good / too late per leg, written to the trip log).
  Shows the build number and short SHA (from BuildConfig) at the bottom.
- **Route editor**: name, plus 1–3 legs. Each leg has a name, a paste coordinates field,
  expected ride minutes ("use your fastest usual ride"), expected wait minutes, and an alarm on/off toggle.
  The parser accepts `-36.78123, 174.75123`, `-36.78123 174.75123`, and Google Maps URLs containing
  `@lat,lng` or `q=lat,lng` / `query=lat,lng`. Warn if the point is outside New Zealand.
- **Trip**: live status from the engine (leg, distance, ETA, v_est, GPS age, scheduled alarm time,
  transfer hold), plus "I'm off this bus" (SkipLeg).
- **Readiness**: a green or red row per check, each with a button that opens the right settings page:
  notifications, fine location, `canUseFullScreenIntent()`, `canScheduleExactAlarms()`,
  ignoring battery optimisations, alarm-stream volume above 0 (only when the speaker is on). The
  speaker-last-resort toggle. Plus static Samsung instructions:
  - Settings → Apps → Nodstop → Battery → Unrestricted
  - Settings → Battery → Background usage limits → make sure it's not in Sleeping / Deep sleeping apps;
    add it to Never sleeping apps
  - Never Force stop the app; Android cancels its alarms when you do.
- **Debug**: Test ring in 30 s (the real AlarmManager path; disabled during a trip), Simulated trip
  (start N km out at X km/h, in real time, through the full engine and alarm path), **Kill app now**
  (`Process.killProcess`, for the kill test), Share last trip log, a view of the last 200 trace events.

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

### Couch test (tonight, about 20 min)

1. Readiness screen all green; Samsung steps done.
2. Test ring in 30 s → lock the phone → it rings. Repeat with: silent mode, DND on, earbuds in
   (it should ring in the earbuds only, escalating, and stop instantly if you take them out or
   turn them off, never through the speaker), and with the app swiped away from recents.
3. Simulated trip (6 km out, 50 km/h; 3 km is too short for a 5-min heads-up) → lock the phone →
   heads-up about 2 min in (ETA ~5 min), ring about 4.5 min in (ETA ~2.5 min). Hold "I'm awake":
   the transfer hold runs until sim stop 1, then sim stop 2 (3 km on) rings about a minute later.
4. Kill test: simulated trip at 5 km and 60 km/h, then **Kill app now** after 1 min. The alarm
   must still ring early (dead-man's switch). Note how long it took. Then hold "I'm awake": the
   app must resume at sim stop 2 (Trip screen shows leg 2 with an alarm time) and ring again.
5. Hold-to-ack works; a short tap, back and volume keys do not dismiss.
6. Share the log and confirm the file opens.

### First ride (tomorrow)

1. Paste the stop coordinates into "To work" (and "From work").
2. Stay awake on the first ride, and set a normal phone alarm as a backup.
3. Tap Start (or the Quick Settings tile) when boarding bus 1. When it rings, note the time and
   how far you were from the stop. Answer the ring-timing card afterwards.
4. Afterwards: share the log → upload it to the repo under `engine/src/test/resources/traces/`
   (GitHub web, "Add file → Upload files") so it becomes a regression test.

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
- **Later:** the phone speaker as a default escalation, iOS.

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
