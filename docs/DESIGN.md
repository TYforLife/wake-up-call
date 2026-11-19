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
you get off (paste a Google Maps point, or stand at the stop and tap "Use my location"), and GPS
does the rest. **Your pin is always the stop.** After a ride or two the app learns the way the bus
goes and *suggests* where the stop really is (§2.3); you choose whether to move the pin.
Personal coordinates stay on the phone, never in the repo.

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
    straight line, to **your pin**.
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

- **Quick get-off** (after "I'm awake"): in this stop's circle (150 m without one, 500 m at most) at
  walking pace for 10 s, the leg ends at once instead of after 45 s. Then the old stop is watched for 2 min:
  if you were really still on the bus (a red light in the circle) and it rides on past, it rings
  "You passed your stop"; acking that keeps you on the current leg.
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
  If the bus carries on past an alarm-off leg's stop with you on it, you're on the next leg (still RIDING).
- **Start anywhere.** Tapping Start lets the engine pick the leg from the first fix (`LegPicker`): a
  learned route you're on; else at a stop (within 300 m) → the leg after it; else beside the line between
  two consecutive stops → the leg ending at the second; else the first leg (an extra leg only rings
  early). So starting late, already on the second bus, or forgetting leg 1 all work. Boarding needs only
  8 s of movement, so starting while already on a moving bus shows "On the bus" within seconds.
  Home also has **Start from…** to choose the leg yourself.

### 2.2 What this buys

| Failure | What happens |
|---|---|
| App process killed (Samsung) | The last scheduled alarm still fires, no later than safe. After "I'm awake", the app resumes the same leg from the saved trip, awake for it or still tracking it depending on why it rang. |
| GPS lost, app alive | Updates stop, so the failsafe fires (early-biased); acking it keeps tracking. |
| GPS never gets a fix | Failsafe from the last known position; with none, a No GPS ring at 3 min. |
| Phone silent / DND | System alarm, alarm-usage vibration, earbuds (media stream), plus a full-screen intent. |
| Bus faster than expected | `V_MAX` in the failsafe, `avg15`, and past rides' fastest times in wakeAt. |
| Bus slower than expected | Rings a bit early. |
| Roughly pasted stop | A pin past the real stop costs ~d/v seconds (e.g. 150 m at 40 km/h ≈ 14 s). The app suggests a better spot (§2.3); you decide. |

There is a single ring path: `AlarmManager` → `AlarmReceiver` → `RingService` → `AlarmActivity`.
"Ring now" means scheduling the alarm at `now`. The at-stop nudge is a single short vibration and a
notification, not the alarm.

### 2.3 Route learning

When a leg ends with you getting off (and you'd boarded), the engine emits a ride: the trail (a point
every ~25 m from boarding to the point nearest **your pin**, each with the distance and time left on
that ride) and the get-off spot. The app stores it per pin (`learned.json`, on the phone only):

- **Suggested stop**: a running average of where you got off (ignored if > 300 m from your pin).
  It's only a suggestion: GPS-detected getting off varies with where you stood or walked, so the alarm
  never uses it. The route editor (and the map) show it faintly with **Use suggestion**, which moves
  your pin there and starts learning afresh.
- **Tracks**: the newest 5 rides.

On later rides each fix is matched onto the tracks (nearest segment within 60 m). The distance left is
the smallest over the matching tracks, × 0.95, so GPS noise or a cut corner never makes it too long.
`v_route` uses the fastest past time from that point. Off the learned route, the engine falls back to
the straight line. Changing a stop's coordinates starts learning afresh; "Forget" is in the route editor.

### 2.4 ETA (display)

Buses don't go at one speed (motorway, then residential streets), so a speed × distance ETA is far off.
On a learned route the ETA is **past rides' typical (median) time left from this exact point × today's
pace**, where pace = the time you took over the last ~4 min ÷ the time past rides took over the same
stretch (needs ≥ 45 s of it; clamped 0.5–2.5×). On a first ride it's the distance at your average speed
over the last minute; none while waiting or stopped (`Eta`). Shown on the Trip screen (with its source)
and the trip notification. This is display only here; §2's alarm uses its own early-biased estimate.

### 2.5 Smarter waking (learned time, per-stop lead)

- On a learned route, `v_route` uses the **learned time left**: the fastest past ride from this point, or
  past rides' typical time × today's pace × 0.85 if that's sooner (`Eta.learnedTimeLeft`). Taking the
  sooner can only ring earlier, so the invariant is untouched.
- **Per-stop lead**, tuned by your ring-timing ratings: "too early" → 10 s less lead (rings later),
  "too late" → 15 s more (rings earlier), kept within 30–90 s, default 45 s (`Learned.leadS`,
  `Eta.adjustLead`). The failsafe never uses less than the default 45 s.
- At the shortest lead (30 s) the guarantee is relative to that lead: never less than 30 − 20 = 10 s
  before the stop (the property test runs 500 trips at lead 30 to prove it). That's the cost of telling
  it "too early" repeatedly; a "too late" moves it back by 15 s.

### 2.6 Wake zones

Each alarm-on stop has a wake zone, drawn on the map as a circle: where it will ring.
- **Automatic** (dashed): how far out, in a straight line, the bus usually is when the stop's lead is
  left on past rides (median); before any ride, lead × 25 km/h (about 310 m at 45 s). Display only: the
  alarm itself follows §2's timing.
- **Yours** (solid): tap a stop pin on the map, then a preset (Auto, 150, 250, 400, 700 m) or the slider
  (50 m–2 km on a square-root scale, so most of it is under 1 km; `ZoneScale`); the circle follows live;
  Save stores it on that leg (`LegDraft.wakeRadiusM`). **A saved circle applies to a running trip at once**
  (`Input.UpdateLegs`: only circles, only for legs whose stop is unchanged; a circle you're already inside
  rings now; property-tested never to make an alarm later). A simulated trip's stops can be set too (for
  that trip only). Once you're on the bus and inside
  it, it rings, unless the timing rang first: **whichever comes first**, so it can only ring earlier.
  Waiting at a stop inside the zone doesn't ring until you're on the bus.

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
| Quick get-off | circle or 150 m (≤ 500 m), < 8 km/h, 10 s | After "I'm awake"; then the old stop is watched 120 s. |
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
d, eta, v, leg, ph`), plus app annotations ignored by replay: `rate`, `note`, `wys` (While You Slept
counts at each ring, §10) and `ringd` (how a ring reached you, §5). `wys` and `ringd` are kept in
location-hidden exports like `rate`, since they have no positions or text. Input kinds also include
`urgent` (§10) and `legs` (a wake circle changed mid-trip, §2.6).

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
- your pin stays the stop; the suggested spot, once used, gives 30–60 s
- an early failsafe ring that doesn't use up the leg
- riding past after an ack
- the at-stop nudge, boarding detection, resume awake
- learning: track building, matching, merging

---

## 5. Android shell (`:app`)

Kotlin, Jetpack Compose (Material 3), single activity plus `AlarmActivity`.
No DI framework, no Room, no Hilt: manual wiring (`App`), JSON in app storage.

Dependencies: Compose BOM, `activity-compose` (hosts Compose), `play-services-location`,
`kotlinx-serialization-json`, `maplibre` (the map: open source, no account or API key),
`litertlm-android` (on-device Gemma for While You Slept, §10; about +20 MB of native code); JUnit 5 for
tests. Nothing else without a reason written in the PR. Test builds include only `arm64-v8a` native
code (the owner's phone), which keeps MapLibre to about +14 MB.

`minSdk 31`, `targetSdk`/`compileSdk` 36. Package `app.nodstop`.

### Components

| Component | Job |
|---|---|
| `TripService` | Foreground service, type `location`. Started from the UI (foreground), so no background-location permission is needed. Gets the last known position, then fused location at HIGH_ACCURACY every 2 s; fix age measured on the monotonic clock. Feeds the engine, applies `alarmAtMs`, buzzes once at the stop, stores learned rides, and runs the live trip notification (§5 Notifications). A `Tick` every 5 s. Writes the trace. Saves the active trip (legs, leg, alarm reason, time left) on every change and every 15 s. |
| `AlarmScheduler` | Wraps `AlarmManager.setAlarmClock(AlarmClockInfo(t, showIntent), receiverPI)` with one fixed request code, so rescheduling replaces it. Cancels on null. |
| `AlarmReceiver` | Always starts `RingService`, with no conditions. Sends `Rang` to the engine if the process is alive. |
| `RingService` | Foreground service (type `systemExempted` on Android 14+, allowed for apps holding `USE_EXACT_ALARM`; `mediaPlayback` before that or if refused). Posts the full-screen-intent notification on the `alarm` channel (the channel itself is silent; the service plays the audio and vibration), plays the ring ladder, holds a partial wake lock. If the screen is off it also takes a 30 s screen-on wake lock, so the alarm screen is already up when you look; each ring is recorded (`RingDiag`: screen on/off, full-screen allowed, shown after how long, earbuds or vibration, how it was stopped) in the trip log (`ringd`) and as Readiness's "Last ring" line. |
| `AlarmActivity` | Shows over the lock screen and turns the screen on. **Ways to say "I'm awake"**: hold anywhere on the bottom half for 1 s (it fills up and buzzes), hold volume down for 1.5 s (works by feel, in a pocket), or the **I'm awake** button on the alarm notification (for when you're using the phone and it shows as a banner). If Nodstop itself is open, the alarm takes over its screen (0.6 s hold). A tap, a pocket brush, back, or a quick volume press does nothing. Shows the stop, why it rang (No GPS, GPS lost, passed your stop), and earbuds/vibration. **With no trip service alive** (a cold start after a kill), "I'm awake" resumes the same leg from the saved trip: awake for it if the ring was for this stop, still tracking it if it was an early safety ring. Started from a visible screen, so Android allows it. |
| `LocationSource` | Interface. `FusedLocationSource` (real; also last-known and one-shot "Use my location") and `SimulatedLocationSource` (a pretend bus in real time: halts 90 s at stop 1, drives 3 km to stop 2, halts 2 min, then keeps going, for couch tests). |
| `LearnedStore` | What's learned per pasted stop (`learned.json`, phone only). Forget per stop (route editor) or all (Debug). |
| `SafeStore` | How every store loads and saves: each save keeps the previous file as `.bak`; a file that can't be read is kept as `.bad-<time>` and the backup is used, so an update can never wipe routes or learned rides. Fixture tests decode every shipped format. Debug → **Your data** backs everything up to one JSON file (share sheet) and restores it (file picker). |
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
  bar, and a **Stop trip** action that needs the phone unlocked (so a pocket can't cancel your alarm). On Android 16 it's a Live Update: promoted to the lock screen and status bar chip with the
  distance (`POST_PROMOTED_NOTIFICATIONS`; Readiness shows whether it's allowed).
- **At your stop**: the one-buzz nudge, "You're at Smales Farm, get off now".
- **Wake-up alarm**: full-screen intent, colourised, with an **I'm awake** action.

### Permissions

`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
`USE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `WAKE_LOCK`, `VIBRATE`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `MODIFY_AUDIO_SETTINGS` (media volume during the ring),
`POST_PROMOTED_NOTIFICATIONS` (Android 16 Live Updates), `INTERNET` (map tiles only, from
OpenFreeMap; the tile server sees roughly which area you're looking at, nothing else is sent).

### Screens (v0)

- **Home**: if full-screen alarms are off, a red card at the top ("The alarm can't turn your screen on")
  with the button to allow them. Then the list of Routes, Start per route (or the Quick Settings tile), the running trip (or an "interrupted" card if the app
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
- **Map** (from Home, and "Show on map" on the Trip screen): MapLibre with OpenFreeMap's "liberty"
  style. Shows you as a blue dot (waiting, no trip, or off the bus) or a bus icon (on the bus: RIDING,
  or still at bus speed while ringing/awake), every pinned stop (the trip's current stop highlighted),
  faint suggested stops with a dashed line to their pin, and faint learned routes. Frames you and the
  current stop, then **follows you by default** every time it opens. Dragging the map pauses following;
  it resumes 10 s after your last drag, or at once when you tap **Follow** (`FollowPolicy`). With no trip running it gets your position only while open.
  It's for checking the pins sit on the right spots; it doesn't change any alarm.
- **Debug**: Test ring in 30 s (the real AlarmManager path; disabled during a trip), Simulated trip
  (§5 LocationSource), **Kill app now** (`Process.killProcess`), Forget all learned stops, Share last
  trip log, a view of the last 200 trace events.

---

## 6. Build and delivery (phone-only)

- Gradle project: `:engine` and `:brief` (Kotlin JVM) and `:app`. Gradle wrapper committed. Versions in
  `gradle/libs.versions.toml`.
- Debug signing: `keystore/debug.keystore` (standard debug credentials) is committed and
  `signingConfigs.debug` points at it, so every CI build installs over the previous one. It is never used for release.
- `versionCode = GITHUB_RUN_NUMBER` (1 locally); `BuildConfig.GIT_SHA`.
- GitHub Actions `.github/workflows/android.yml`:
  - On push to any branch, plus `workflow_dispatch`.
  - `build` job: per-branch concurrency (a new push cancels only that branch's older run).
    `actions/setup-java` (Temurin 21) and `gradle/actions/setup-gradle`;
    `./gradlew :engine:test :brief:test :app:testDebugUnitTest :app:assembleDebug`; then screenshots
    (`./gradlew :app:recordRoborazziDebug -Pscreenshots`, Robolectric; never blocks the APK), uploaded as an
    artifact and to the release.
  - `publish` job: `concurrency: debug-release`, cancel in progress; `permissions: contents: write`;
    publishes with the `gh` CLI to the rolling prerelease `debug-latest`: create it if missing,
    `gh release upload ... app-debug.apk --clobber` (plus the screenshot PNGs), and set the notes to
    branch, SHA and build number.
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
7. Map: your blue dot is where you are; your pins sit on the right stops; during a simulated trip the
   dot turns into a bus once it moves.
8. Share the log and confirm the file opens.

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

**While You Slept** (on-device notification briefing on the alarm screen): spec in §10, work order in `docs/PLAN.md`.

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

---

## 10. While You Slept (v1: on-device briefing)

While you sleep, the phone holds your notifications and reads your chats on the phone itself.
When the alarm rings, the alarm screen also shows what you missed: a few lines per conversation.
The work order and acceptance criteria are in `docs/PLAN.md` (Runs 1–3).

### Invariants (rank with §2; each one has a test)

- **W1. The AI can only ring earlier.** Its only way into the engine is `Input.Urgent`, which
  schedules the alarm at `now` (reason URGENT). Nothing on the briefing path can cancel, delay
  or silence a ring.
- **W2. The ring never waits for the briefing.** The alarm screen shows the best thing ready:
  the Gemma briefing, else the plain digest, else "Briefing unavailable".
- **W3. Message text lives in process memory only.** It never goes into traces, `note` or `rate` lines,
  logs, files or crash reports. It is cleared when the alarm screen closes or the trip stops; a process
  death loses it (W2 covers that).
- **W4. Chats only.** Text is read only from notifications with MessagingStyle or category `msg`, from
  other apps, while a trip runs. Everything else is counted, never read. News is never summarized.
- **W5. Facts are copied, not invented.** Every name, time, date, number and place in a briefing line
  must appear in that conversation's source text. A line that fails is replaced by its digest line.
- **W6. Message text never touches network code.** The app needs `INTERNET` for map tiles (§5),
  so this is enforced in the code: `:brief` is pure Kotlin, and a source-scan test fails if the
  `notify` or `brief` packages use networking (`java.net`, sockets, HTTP clients, MapLibre).
  Message types never cross into map, tile or trace code. The rider imports the model file through
  the system file picker; the app never downloads it.

### Engine (`:engine`)

- `Input.Urgent(tMs)` (trace kind `urgent`): in WAITING, RIDING or AWAKE, `alarmAt = tMs` with
  `AlarmReason.URGENT`. Ignored in other phases, when an alarm is already due, and after
  `MAX_URGENT_RINGS` urgent rings in one trip. Fixes arriving before it rings can't push it later.
- **Acking an URGENT ring never counts as "awake for this stop"**, however close the stop is: you
  may doze off again. The engine goes back to the phase it was in and restores the stop alarm it
  had, or the one the newest fix gives if that's sooner, with no REARM delay.
- Property (`UrgentTest`, 1,000 random trips): with random urgent rings acked after random delays,
  until either run first rings for the stop, the trip is at every step ringing or scheduled no later
  than the same trip without them; and its stop ring comes no later than without them (or than its
  last urgent ack, if that came after).

### Capture (`:app`, package `app.nodstop.notify`)

- `CaptureService` (NotificationListenerService) writes to an in-memory `MessageStore`, only while a
  trip runs. Conversation key = package + conversation title. Newest 200 messages per trip.
- **Do Not Disturb during trips**: optional, off by default, experimental until Gate B confirms the
  earbud tone and the at-stop buzz still come through. Sets `INTERRUPTION_FILTER_ALARMS` at Start,
  restores the previous filter on Stop, DONE or service destroy, and on next app start if a
  "set by us" flag survived a crash.

### Briefing (`:brief`, pure Kotlin/JVM: same rules as `:engine`)

- `Digest`: per conversation, the sender, message count and latest message (≤ 120 chars); important
  contacts (VIPs) first, then by count; plus "N other notifications".
- `PromptBuilder`, `BriefParser` (JSON lines `{"c":"<conv id>","line":"…","tags":["affects_you","needs_reply"]}`),
  `Guardrails` (W5), `ShareText`. The model sits behind
  `interface Llm { suspend fun generate(prompt: String, maxTokens: Int): String }`: LiteRT-LM in `:app`,
  a fake in tests.
- Timing: summarize in passes: every 180 s (every 360 s while the alarm is over 15 min away, to save
  battery), or at once for 30 new messages; final passes once the scheduled alarm is ≤ 5 min away, at
  most one a minute, so the briefing is fresh at the ring. Each pass re-briefs only chats with something
  new since their last line, so later passes are small and quick. Each model call times out at 60 s and
  falls back to the digest for its conversations. No AI below 20% battery. The model stays loaded from
  the first pass until the ring (then it is unloaded until the next pass needs it).
  The lead is short (§3), so the final pass may not finish before the ring: W2 shows the newest
  finished batch plus digest lines for anything newer.
- Urgent: only for important contacts the rider types in on Readiness (matched as whole words against
  sender or conversation title; no contacts permission). Each new VIP message, newest first and at most
  5 per check, goes to the model alone ("YES or NO"); anything but a clear YES is not urgent. Urgent
  checks run before briefing passes, one model job at a time. Urgent sends `Input.Urgent` (W1) and
  marks that chat, which the alarm screen shows first ("urgent: this woke you").
- Tones: plain (default) or "tea" (Readiness switch). Tea is a second model call over the checked lines;
  W5 is checked again on each rewrite, and a failing or missing rewrite keeps the plain line.
- Share-back: after "I'm awake" on the alarm screen, if there is anything to read, the screen stays
  open ("You're awake") with a **Share this line** button per line and **Done**. Share unlocks the
  phone and opens the share sheet with that line only, plus "recapped while I napped · Nodstop"
  (`ShareText`, `ShareLine`: the only place a line leaves the app). Nothing is ever sent
  automatically. The messages are cleared on Done (or when that screen goes away), still W3.

### Model

Default Gemma 4 E2B (`.litertlm`), fallback Gemma 3 1B. Runtime: LiteRT-LM
(`com.google.ai.edge.litertlm:litertlm-android` **0.16.1**, the newest built with this project's
Kotlin 2.2.21; 0.17+ needs Kotlin 2.4), CPU backend by default. Readiness → **Use the GPU (experimental)**
tries the GPU first and falls back to the CPU if the phone or model can't use it; the lab shows which
one ran, so both can be compared. A GPU driver can crash natively, past any `try`, so trips use the GPU
only after the lab has loaded the model on it successfully on this phone (`gpuVerified`). The rider downloads the file on the phone and
imports it in Debug → **Briefing lab** (`ModelManager`, app storage, select or delete); the lab also
times the model on made-up chats (`SampleChats`). With no model, the alarm screen shows the digest
only. **Decision C (owner, 2026-11-16): Gemma 4 E2B is the default**; Gemma 3 1B stays the fallback
for phones where it is too slow.

How a pass runs (`BriefingCoordinator`, asked on every trip tick): `BatchPlan` decides; loading the
model is not time-limited (the first load can take a minute), only the model call is (60 s); the
answer is parsed, checked (W5) and stored per conversation with the newest message time it covers.
The alarm screen (`PanelLines`) shows a model line only while it still covers that conversation's
newest message, else the digest line. Results are tagged with the store's generation, so a pass that
started before the alarm screen closed can't bring old text back (W3).

### Measuring it (Gate C)

At every ring the trip log gets a `wys` line with counts only, never text (`RingStats`):
`chats=4 model=3 urgent=0 others=2 modelOn=true ready=false`. `ready` means every chat on the alarm
screen had a model line covering its newest message. Gate C's "briefing ready before the ring" is the
share of rings with `ready=true`, read from shared (location-hidden) logs.

Couch test without real chats: Debug → **Add sample chats to this trip** puts `SampleChats` (and an
urgent message from "Daycare") into the running trip's store. Add "Daycare" to important contacts to
test the urgent wake.

### Robustness

- The runtime keeps working on a cancelled call until it notices, so a conversation is closed only after
  its callback says done, and closing the model waits off the main thread for running calls. If the
  runtime never stops, the engine is left open (a leak until the process ends) rather than closed under a
  running call.
- Each VIP message gets one urgency attempt, marked before the call (`Urgency.take`), so a timeout or
  error can't resend it every tick.
- `DeviceGate`: the model shares the trip's process, so it steps aside (and is unloaded) when the battery
  is under 20%, the thermal status is severe or worse, the system reports low memory, or (before
  loading) free memory is under min(model file, 2 GB) + 512 MB. The digest is shown instead (W2). The
  Briefing lab shows the phone's current memory, heat and battery and whether a briefing would run.

### Quality eval

`EvalSet`: 20 made-up conversations, each with the facts a good line must keep. `EvalRunner` sends them
through the real prompt, parser and guardrails in prompts of 10 and scores facts kept and lines that
invented something. Debug → Briefing lab → **Run eval** runs it with the real model on the phone; JVM
tests check the scoring with a fake model. (It already found one guardrail false positive: a possessive
such as "Lena's" in a message now vouches for "Lena".)

### Alarm screen

Below the existing stop name, reason and hold area, a scrollable "While you slept" panel shows the
briefing or digest, made to be read half-asleep: one card per chat (up to 8, then "+N more chats"), big
text, a coloured edge for what matters most (red urgent, amber needs a reply, blue affects your plans)
and labelled chips for each. For URGENT rings, the urgent conversation's card comes first.

### Parameters

`MAX_URGENT_RINGS` 2 · `BATCH_INTERVAL` 180 s (360 s while the alarm is > 15 min away) · `BATCH_SIZE` 30 ·
`FINAL_PASS_BEFORE_ALARM` 300 s (one pass a minute at most) ·
`LLM_TIMEOUT` 60 s · `MIN_BATTERY_FOR_AI` 20% · `MAX_MESSAGES_PER_TRIP` 200 (keep newest).
New dependency in this phase: `litertlm-android` (on-device Gemma runtime).
