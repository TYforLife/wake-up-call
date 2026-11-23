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
goes (§2.3). Where a trip ends is never taken as the stop: you often end it as soon as you're awake.
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
      a light near the stop can pull away fast. On a first ride, the larger of the fastest 15-s average of
      the last 30 s and the **whole-ride speed**: `avg15` since boarding, exponentially weighted with
      `TRIP_TAU` = 60 s (recent minutes count most), so a bus held at lights or stuck behind a turning
      truck for a minute still counts as the speed it's been doing. As a term in the max it can only
      ring earlier (property test: 300 random trips).
    - `V_FLOOR` = 25 km/h (halved on a learned route). It also means "ring by ~310 m" when stopped.
- **failsafeAt**: when the alarm must ring if no further fixes arrive, assuming the bus could go as
  fast as `V_MAX`: `ft + max(DEADMAN_MIN, straight / V_MAX − LEAD)`. Measured on the straight line.
- `scheduled = max(now, min(wakeAt, failsafeAt))`.

While fixes keep arriving (every 1–3 s), `failsafeAt` keeps being pushed forward and never fires.
Before the first fix of a trip, the phone's **last known position** (up to 3 min old) arms the
failsafe from its own time, so a trip that never gets a fix is still covered. With no position at
all, a **No GPS** alarm rings 3 min in: you're unprotected, stay awake.

**While it rings, the alarm stays scheduled.** Each ring re-arms the one system alarm `RERING` (60 s)
ahead, with the same reason, and each re-ring pushes it on again; "I'm awake" replaces it and Stop
trip cancels it. So if anything kills the ring before the ack (the process is killed for memory, the
service is stopped), the system rings again within a minute. After a cold start (no trip service),
`RingService` keeps that re-ring itself; if the trip turns out to be over when "I'm awake" is held,
the cold path cancels it.

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
  A route's ⋯ menu also has **Start from <stop>** to choose the leg yourself.
  The pick never skips a stop that's still ahead (owner's missed ring, 2026-11-23):
  - a learned route that still passes an earlier stop picks that earlier stop;
  - a stop's learned ride starts after the previous stop, so a ride from home never teaches stop 2 the way
    through stop 1;
  - a stop the pick skipped is watched for the rest of that leg. Coming within 400 m of it (or the lead + 15 s at
    the bus's speed, if that's further), at least 150 m closer than at the pick, switches back to it and rings at
    once. `SkipPropertyTest`: over 300 random trips up to V_MAX, it rings at least 53 s before the stop;
  - a skip is never silent: the trip sheet says "Started past Smales Farm" and offers **Start at Smales Farm**,
    which starts the same trip again from that stop, with no pick (`Status.pickedPast`, BACKLOG 5). The sheet stays
    open while it shows.

### 2.2 What this buys

| Failure | What happens |
|---|---|
| App process killed (Samsung) | The last scheduled alarm still fires, no later than safe. After "I'm awake", the app resumes the same leg from the saved trip, awake for it or still tracking it depending on why it rang. |
| GPS lost, app alive | Updates stop, so the failsafe fires (early-biased); acking it keeps tracking. |
| GPS never gets a fix | Failsafe from the last known position; with none, a No GPS ring at 3 min. |
| Phone silent / DND | System alarm, alarm-usage vibration, earbuds (media stream), plus a full-screen intent. |
| Bus faster than expected | `V_MAX` in the failsafe, `avg15`, and past rides' fastest times in wakeAt. |
| Bus slower than expected | Rings a bit early. |
| Roughly pasted stop | A pin past the real stop costs ~d/v seconds (e.g. 150 m at 40 km/h ≈ 14 s), and rides that never pass within 60 m of it aren't learned. Use "Use my location" at the stop. |

There is a single ring path: `AlarmManager` → `AlarmReceiver` → `RingService` → `AlarmActivity`.
"Ring now" means scheduling the alarm at `now`. The at-stop nudge is a single short vibration and a
notification, not the alarm.

### 2.3 Route learning

When a leg ends with you getting off (and you'd boarded), the engine emits a ride, **only if GPS
passed within 60 m of your pin** (`LEARN_PASS`): the trail (a point every ~25 m from boarding to the
point nearest your pin, each with the distance and time left on that ride). A trip you stop by hand,
or a leg that ends 300 m short (a long red light after "I'm awake"), teaches nothing. Where you got
off, acked or stopped the trip is never learned or suggested: you often end the trip as soon as you're
awake, so it says nothing about where the stop is (PLAN U4; the old "suggested stop" is gone). The app
stores the newest 5 tracks per pin (`learned.json`, on the phone only), with your timing ratings.

On later rides each fix is matched onto the tracks (nearest segment within 60 m). The distance left is
the smallest over the matching tracks, × 0.95, so GPS noise or a cut corner never makes it too long.
`v_route` uses the fastest past time from that point. Off the learned route, the engine falls back to
the straight line. Changing a stop's coordinates starts learning afresh; "Forget learned rides" is in Set a stop's ⋯ menu.

### 2.4 ETA (display)

Buses don't go at one speed (motorway, then residential streets), so a speed × distance ETA is far off.
On a learned route the ETA is **past rides' typical (median) time left from this exact point × today's
pace**, where pace = the time you took over the last ~4 min ÷ the time past rides took over the same
stretch (needs ≥ 45 s of it; clamped 0.5–2.5×). On a first ride it's the distance at the whole-ride
speed (§2, falling back to the average over the last minute); none while waiting or stopped (`Eta`). Shown on the Trip screen (with its source)
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
- **Yours** (solid), set right on the map (PLAN Run 11, B4):
  - Tap inside your stop's circle for its sizes: Auto, 150, 250, 400 m, or the slider (50 m–2 km on a
    square-root scale, so most of it is under 1 km; `ZoneScale`).
  - Or touch its edge and drag in or out (no handle: a touch within a finger's width of the edge grabs it,
    `EdgeDrag`): the circle follows your finger, and the size is rounded like the
    slider's (`CircleDrag`).
  - It's stored on that stop (`LegDraft.wakeRadiusM`), from the riding map or Set a stop. **A saved circle applies to a running trip at once**
  (`Input.UpdateLegs`: only circles, only for legs whose stop is unchanged; a circle you're already inside
  rings now; property-tested never to make an alarm later). A simulated trip's stops can be set too (for
  that trip only). Once you're on the bus and inside
  it, it rings, unless the timing rang first: **whichever comes first**, so it can only ring earlier.
  "On the bus" is boarding detected **or bus speed right now** (≥ 15 km/h over the last 15 s), so a
  trip started on a crawling bus, where boarding was never detected, still rings on entry (PLAN R3).
  From outside the circle, the estimate and the failsafe also aim at **its edge**, with no extra lead
  (the circle is your margin): `ft + (d − r) / v_est` and `ft + max(DEADMAN_MIN, (straight − r) / V_MAX)`,
  each taken only if earlier. GPS already runs at 1–2 s for the whole trip, so no faster checking is
  needed near the circle; a GPS gap is covered by the failsafe (tested: a gap from 60 s before the edge
  still rings before it). Waiting at a stop inside the zone doesn't ring until you're on the bus.
  Property-tested: with a circle, the first ring is never later than without one (300 random trips). Shrinking a
  circle mid-trip, even to 50 m, never rings later than having no circle (200 random trips, PLAN B4).

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
| `RERING` | 60 s | While ringing, the alarm stays scheduled this far ahead (§2). |
| Learning | 60 m match, × 0.95, 25 m trail, 5 tracks, ride counts if it passed within 60 m of the pin | §2.3 |
| `TRIP_TAU` | 60 s | Whole-ride speed weighting (first rides, §2). |

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
counts at each ring, §10), `ringd` (how a ring reached you, §5), `gps` (GPS gaps over 10 s and the
longest, at each ring and at the trip's end) and `zone` (the first time you're inside a leg's circle: was
boarding detected, and when the alarm was set). `wys`, `ringd`, `gps` and `zone` are kept in
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
13. Purity: `:engine` main code has no Android, clocks, randomness, I/O or networking (`PurityTest`).

Extra tests:
- motorway then slow city (the 866 complaint): 64 s first ride, 44 s learned
- a red light near the stop, with and without a learned route
- **the evening commute regression**: an alarm-off hop, two transfers, rings for both sleep legs, none while waiting
- a ride is learned only if it reached your pin; stopping a trip early teaches nothing
- a slow city end on a first ride: 45–49 s without the whole-ride speed, 54–55 s with it
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

- **Target stays at 36.** Targeting 37 (Android 17) lets an app play audio in the background only from a
  foreground service with while-in-use capabilities. It waives that only for `USAGE_ALARM` audio with exact-alarm
  access, and an alarm-started service isn't documented to have them, so the earbuds tone (`USAGE_MEDIA`) could be
  muted without an error. It also requires read-only native libraries (MapLibre, LiteRT). Raising it needs an
  Android 17 phone, `adb shell cmd audio set-enable-hardening enable`, and every ring case in `RingPathTest`.
  `ArchitectureTest` pins it.
- **Nothing is copied off the phone**: `allowBackup="false"` plus `data_extraction_rules.xml`, which excludes
  everything from cloud backup and from phone-to-phone transfer. Moving phones is Debug → **Your data** (a file
  you choose to share). `ArchitectureTest` checks both.

### Components

| Component | Job |
|---|---|
| `TripService` | Foreground service, type `location`. Started from the UI (foreground), so no background-location permission is needed. Gets the last known position, then fused location at HIGH_ACCURACY every 2 s; fix age measured on the monotonic clock. Feeds the engine, applies `alarmAtMs`, buzzes once at the stop, stores learned rides, and runs the live trip notification (§5 Notifications). A `Tick` every 5 s. Writes the trace. Saves the active trip (legs, leg, alarm reason, time left) on every change and every 15 s. |
| `AlarmScheduler` | Wraps `AlarmManager.setAlarmClock(AlarmClockInfo(t, showIntent), receiverPI)` with one fixed request code, so rescheduling replaces it. Cancels on null. |
| `AlarmReceiver` | Rings with no conditions. First it posts the ring notification itself: full screen, insistent, on the alarm channel, which vibrates by itself. Then it starts `RingService`, which takes over the same notification ID. If the service can't start, the alarm is still on screen and buzzing, and the receiver re-arms the alarm a minute ahead (PLAN Run 10, A1). Sends `Rang` to the engine if the process is alive. |
| `RestartReceiver` | After an app update (`MY_PACKAGE_REPLACED`) or a phone restart (`BOOT_COMPLETED`) during a trip (PLAN Run 10, A1): sets the saved alarm again at once (`Restart.plan`), or now if it passed while the phone was off. It then resumes tracking the same stop (`ACTION_RESUME`, not "awake for it"). It also posts "Your trip is still on, tap to keep tracking" in case Android refuses to start location tracking from the background. A stale or finished trip is dropped. |
| `RingService` | Foreground service (type `systemExempted` on Android 14+, allowed for apps holding `USE_EXACT_ALARM`; `mediaPlayback` before that or if refused). Posts the full-screen-intent notification on the alarm channel. The channel has no sound, but it vibrates by itself, and the notification is insistent (PLAN Run 10). The service plays the audio and its own vibration, plays the ring ladder, holds a partial wake lock. If the screen is off it also takes a 30 s screen-on wake lock, so the alarm screen is already up when you look; each ring is recorded (`RingDiag`: screen on/off, full-screen allowed, shown after how long, earbuds or vibration, the sound mode and Do Not Disturb at the start, whether it could buzz, when the speaker joined, how it was stopped, plus the alarm channel's state, "Display over other apps", quiet trip at the ring and a cold start) in the trip log (`ringd`; a ring that cold-started the app writes into the saved trip's log) and as the last-ring report that Settings → Report a missed alarm shares. |
| `AlarmActivity` | Shows over the lock screen and turns the screen on. It opens only when you're not using the phone (screen off or locked) or Nodstop is in front (`AlarmScreen.opens`, tested). While you're using another app you're awake (owner, 2026-11-24): the ring doesn't take over your screen; the alarm notification shows as a banner with "I'm awake", and it buzzes at full strength and sounds in your earbuds. (The earlier overlay over other apps, and its "Display over other apps" permission, are gone.) |
| Alarm channel | `Notifications.ensureAlarmChannel`, at every app start and every ring (never while a ring shows). The channel must be high importance and vibrating, and must get through Do Not Disturb whenever Nodstop has that access. Android only honours "bypass Do Not Disturb" if the access existed when the channel was made, and keeps a channel's settings, so a channel that falls short is replaced with a new ID (`AlarmChannel`, tested). |
| `AlarmActivity` (ack) | **Ways to say "I'm awake"**: hold anywhere on the bottom half for 1 s (it fills up and buzzes), hold volume down for 1.5 s (works by feel, in a pocket), or the **I'm awake** button on the alarm notification (for when you're using the phone and it shows as a banner). If Nodstop itself is open, the alarm takes over its screen (0.6 s hold). A tap, a pocket brush, back, or a quick volume press does nothing. Shows the stop, why it rang (No GPS, GPS lost, passed your stop), and earbuds/vibration. **With no trip service alive** (a cold start after a kill), "I'm awake" resumes the same leg from the saved trip: awake for it if the ring was for this stop, still tracking it if it was an early safety ring. Started from a visible screen, so Android allows it. |
| `LocationSource` | Interface. `FusedLocationSource` (real; also last-known and one-shot "Use my location") and `SimulatedLocationSource` (a pretend bus in real time: halts 90 s at stop 1, drives 3 km to stop 2, halts 2 min, then keeps going, for couch tests). |
| `LearnedStore` | What's learned per pasted stop (`learned.json`, phone only). Forget per stop (Set a stop ⋯) or all (Debug). |
| `TransitIndex` | Auckland Transport's stops and route patterns, offline (PLAN Run 12). CI downloads the public GTFS feed (CC BY 4.0) and `tools/gtfs_index.py` boils it down to `assets/gtfs/stops.tsv` (number, name, position) and `routes.tsv` (each route and direction's stops in order). It powers stop search and Plan a trip; `patterns`, `profiles`, `trips` and `services.tsv` hold the timetable (PLAN Run 13, about 2.4 MB). It only ever fills in stops: no alarm depends on it (rule 6). A build without it (a local build) has no search; links and the map still work. CI also checks the script against its fixture. |
| `SafeStore` | How every store loads and saves: each save keeps the previous file as `.bak`; a file that can't be read is kept as `.bad-<time>` and the backup is used, so an update can never wipe routes or learned rides. Fixture tests decode every shipped format. Debug → **Your data** backs everything up to one JSON file (share sheet) and restores it (file picker). |
| `LearnSpikeService` | Debug → Learn spike (`docs/LEARN.md` L0): a location foreground service that records one ride's GPS (a trip's rate), activity transitions and activity guesses (Play services Activity Recognition, already in `play-services-location`), the battery every 5 min, and the rider's Got on / Got off marks. JSONL in `files/learn` (`SpikeLog`), never in `files/traces`; shared location hidden only (`LearnSpike.anonymize`). Never touches an alarm (`LearnIsolationTest`). |
| `TraceLogger` | Writes JSONL per trip to app files, flushed per line. Share uses the system share sheet: **location hidden** (every position moved by one random offset, engine re-run, `Trace.anonymize`) for the public repo, or raw (private). |
| Quick Settings tiles, launcher shortcuts | One tile per first two routes ("To work", "From work"): one tap opens the app and starts the route. Tapping while a trip runs just shows it. Long-press the app icon for the same. |

### Ring ladder (earbuds, then vibration)

Headset = any output device of type BT A2DP, BLE headset, wired headset or headphones, or USB headset.

**The tone** is the sound chosen in Settings → Alarm sound (BACKLOG 3, `Sounds`):
- Built in: Beeps (the default), Chime and Rising. They're made in code, so there's no file to go missing.
- Pūkeko: the owner's own recordings, in the app (`res/raw/pukeko_*.pcm`, made by `tools/pukeko_sounds.py`): a
  ~22 s morning recording, crossfaded to loop, and three single screams. Each ring mixes them afresh
  (`Sounds.pukeko`): the morning twice (~44 s, then it loops), with a scream at random every 1.5–5 s, and
  sometimes a reply 0.25–0.7 s after; each a random one of the three, a little higher or lower and nearer or
  further. Made loud, overlaps rounded off. If the recordings can't be read, the beeps.
- Or your own file (mp3, wav, m4a, anything the phone can play), via the system file picker. It's decoded once
  when you pick it (`AlarmSound.import`): mixed to mono, resampled to 44.1 kHz, the first 20 s kept, leading
  silence trimmed and made as loud as it can be. It's stored in the app's files.
- A ring reads it once, from memory. If it's missing or unreadable, or the phone won't take it, the ring plays
  the beeps.
- It plays through the same looping track as always (`Tone`, in `RingService` only), so the ladder below, the
  earbuds rule and the vibration don't change.
- "Try it" is a real test ring through `AlarmScheduler`, now, off during a trip: in the earbuds (or vibration
  only), as a trip rings. With **Test on the phone speaker** on, test rings play on the speaker from the start,
  at your volume (`Settings.testOnSpeaker`; a trip's ring never uses it).
- **Alarm volume** (10–100 %, default 100) scales the earbud ladder below: at 50 % it rises 20/35/50 %, never
  below 10 %. Vibration and the last-resort speaker (full) don't change.

| Time | Earbuds connected | No earbuds |
|---|---|---|
| 0–15 s | Tone in the earbuds at 40% media volume (`USAGE_MEDIA`, exclusive transient audio focus so music or podcasts pause), gentle vibration pulses | Gentle vibration pulses |
| 15–30 s | Earbuds at 70%, stronger pulses | Stronger pulses |
| 30 s+ (or overshoot) | Earbuds at 100%, continuous strong vibration | Continuous strong vibration |
| 60 s+ (or overshoot), **only if "speaker last resort" is on** (off by default) | Phone speaker, `USAGE_ALARM` at 100% | Same |

- **Phone in use** (screen on when the ring starts): a gentle start is easy to miss while scrolling, so
  vibration is at full strength from the first second and the earbuds start at 70%, then 100% at 30 s.
- **Buzz on Mute** (PLAN R1, `RingMode`, `RingModeController`): if the phone is on Mute, or in a Do Not
  Disturb mode that may block alarms (total silence or priority), when a ring starts and Nodstop has Do
  Not Disturb access (a required Setup row, "Buzz on Mute"), it switches to vibrate
  (and alarms only) for the ring and puts your setting back after "I'm awake" or Stop; only if it's still
  what we set. The record is saved before anything changes, so the next app start undoes a ring a crash
  left behind. Quiet trip's own Do Not Disturb is lifted first, as the ring starts (PLAN Run 10).
- **No earbuds and the buzz may not get through** (on Mute without that access, or no vibrator): the
  phone speaker joins at **30 s**, whatever the "speaker last resort" setting.

- Vibration uses alarm usage, so it gets through DND when alarms are allowed.
- If the earbuds disconnect mid-ring, the tone stops at once (device callback, "becoming noisy",
  and an audio-routing listener), so it never leaks to the phone speaker. Vibration carries on.
- Restore the user's volumes after Ack. The ring never auto-stops; only Ack or Stop trip end it.
- Known limits: connected doesn't mean in your ears (an open case in a bag), and vibration alone
  may not wake you with the phone in a bag. These routing assumptions must be confirmed on the
  device in the couch test.

### Notifications

The words come from pure, tested functions in `ui/v2/Texts.kt`, so the notifications say what the screens say.

- **Trip progress** (silent, tinted with the accent; `TripNoteText`, mockup `lock`): "Harbour St in 6 min",
  "Alarm about 7:45", an accent progress bar, and two buttons (`TripNoteText.actions`, PLAN Run 10 A3):
  - **I'm off this bus**, or **Skip this bus** while waiting for the next one. It ends the leg, or the trip on the
    last bus. It needs no unlock once this stop has rung or you're inside its circle (300 m without one); before
    that it asks you to unlock, so a pocket can't skip your alarm.
  - **Stop trip**, which always needs the phone unlocked. Waiting: "Harbour St · waiting for the bus" / "Alarm set until you're on it";
  GPS lost: "Harbour St · GPS lost" / "Alarm at 7:44 at the latest". A quiet trip adds "Quiet trip: 6 held". On
  Android 16 it's a Live Update: promoted to the lock screen and status bar chip with the distance
  (`POST_PROMOTED_NOTIFICATIONS`; Settings shows an Allow row only while it's off).
- **At your stop**: the one-buzz nudge, "You're at Harbour St", "Get off now".
- **Wake-up alarm**: full-screen intent, colourised (accent; red when you passed your stop), insistent, on a
  channel that vibrates by itself, with an **I'm awake** action. Posted by `AlarmReceiver` first. Its two lines are the ring screen's (`RingText`): "Wake up" / "Harbour St is close".
- **Your trip is still on** (after an update or a restart): one tap resumes tracking; the alarm is already set.
- **Ring timing** (optional, silent, gone after 15 min): "How was the ring timing?", Too early / Good / Too late.

### Permissions

`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
`USE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `WAKE_LOCK`, `VIBRATE`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `MODIFY_AUDIO_SETTINGS` (media volume during the ring),
`POST_PROMOTED_NOTIFICATIONS` (Android 16 Live Updates), `ACTIVITY_RECOGNITION` ("in a vehicle" / "on foot", asked
only by Debug → Learn spike, `docs/LEARN.md`), `RECEIVE_BOOT_COMPLETED` (a trip's alarm survives a
restart), `SYSTEM_ALERT_WINDOW` ("Display over other apps": the alarm screen opens over the app you're using),
`INTERNET` (map tiles; and, only when you paste or share a `maps.app.goo.gl` short link, that link is sent to
Google to open it: `ShortLinks` follows its redirects to Google hosts over https only, PLAN Run 12; otherwise map tiles only, from
OpenFreeMap; the tile server sees roughly which area you're looking at, nothing else is sent).

### Screens

v2 (PLAN Runs 8 and 9): three tabs, **Now**, **Routes**, **Settings** (`ui/v2/`). The design is in `docs/design/`;
the colours are checked against `docs/design/tokens.v2.json` by `TokensTest`. It is black and white in the system
font, with one black primary button per screen and plain lists with hairlines. The only colour is the orange accent
(a trip is live) and red (a problem). Every screen is a stateless composable fed by plain data, and its words come
from pure functions with unit tests (`TripSheetText`, `AwakeText`, `RingText`, `TripNoteText`, `RouteText`,
`StopText`, `SetupText`). Each screen has a screenshot test, `v2_<id>.png` (`ScreensV2ScreenshotTest`). CI pairs
each one with its mockup (`docs/design/compare.py`) and attaches the pairs to `debug-latest`.

The app icon is a map pin, asleep: black on white, also as the themed icon and the small notification and tile icon.
One script builds them all (`docs/design/gen_icon.py`). The launch window is white, or black at night, so the app
doesn't flash another colour while it opens.

- **Setup** (`setup`): "Before your first ride", one list:
  - Notifications; Over the lock screen (full-screen intent); Exact alarms; Buzz on Mute (Do Not Disturb access);
    Location while riding; Over other apps ("Display over other apps", Run 10).
  - An Alarm notifications row only when the alarm channel is turned off or down.
  - Each row has an Allow that asks for the permission or opens its settings page.
  - On Samsung, a Keep running (battery Unrestricted) row and the steps no API can check:
    - Settings → Apps → Nodstop → Battery → Unrestricted
    - Settings → Battery → Background usage limits → Never sleeping apps → add Nodstop
    - Never Force stop Nodstop: Android cancels its alarms when you do.
  - An Alarm volume row only while the speaker is on and the alarm volume is 0.
  - Continue is enabled once every row is allowed; Not now otherwise. It is shown once on first run, if anything
    is off. After that it opens from Now's red row, or when a trip can't start.
  - The optional checks are not here: Live trip progress is in Settings, and chat notification access is in
    Important contacts.
- **Now, idle** (`now`):
  - A greeting, then "Where are you riding?".
  - Your routes as a radio list, with the likely one selected (`RouteSuggest`: the route you start most at about
    this time, ±2 h, on this kind of day; else the last started; else the first).
  - One Start.
  - A red row ("1 thing to set up") when a required check is off.
  - Rows for an interrupted trip, with its alarm time and Stop, and for timing ratings not yet given (one tap per
    leg).
  - Starting the first trip offers to quiet other notifications.
  - **Last trip** (PLAN Run 10, A0, `TripVerdict`): one line per stop that had an alarm, read from the trip log,
    including rings that cold-started the app.
    - Calm: "Rang at Harbour St · Buzzed · screen on".
    - Red, with a one-tap Send (the hidden log and the ring report): "Didn't ring at Harbour St", "May not have
      buzzed: phone on Mute without access", or "The alarm screen didn't open".
    - Neutral when you got off or stopped first.
    - OK hides it.
- **Now, riding** (`riding`, `riding-night`, `gps-lost`, `transfer`):
  - A full-bleed map that looks like Google Maps (PLAN Run 11, B2; `MapPalette`, `MapStyle`):
    - The base is OpenFreeMap `liberty` in Google's colours: grey-beige land, white streets, yellow-orange main
      roads, green parks, blue water, place, POI and transit labels, and bus stops as small blue discs, then
      their names. At night it uses Google's navy night map.
    - It shows only this trip's stops: your stop as Google's red pin with its circle shaded blue, the others as
      small white discs, the learned route in route blue, and you as the blue dot with a heading beam.
    - The stops still to come have their circles too, fainter (`MapModel.tripZones`). Stops behind you have none.
    - A scale bar, top left, like Google's (`Scale`: the longest round distance under 96 dp).
    - Every leg's way (owner, 2026-11-24): the one you're on in route blue, the others grey (`MapModel.legPaths`):
      - the bus's stops from the timetable, when Plan a trip knows the bus (`Departures.path`);
      - else the newest learned ride to that stop;
      - else a straight line from the stop before.

      Learned rides aren't drawn separately on a trip.
    - It has recentre, rotation and the compass, and opens framing you and your stop (`MapModel.frame`; the owner
      likes this). After you change buses it frames you and the next stop again, even if you'd moved the map.
    - Tap your stop's circle for its sizes, or touch its edge and drag (§2.6).
  - The sheet has three heights (owner, 2026-11-24). Drag it up or down; touching the map folds it:
    - folded: one line, for the map;
    - the sheet below;
    - pulled all the way up: the sheet and **Your journey**, every stop in order (`JourneyText`):
      - done;
      - the one you're heading for, with "~7:46" and its bus;
      - the ones after it, with the timetable's arrival and departure when Plan a trip knows their bus
        (`Departures.ride`, after a 2-minute change).
  - **The time left on the bus** (owner, 2026-11-24: "the ETAs are very off… I know my bus times") is worked out
    from the timetable when Plan a trip knows the bus and where you got on (`Departures.remainingS`, in
    `TripService.busEta`):
    - you're placed on the line of its stops (within 400 m);
    - the run due at that point about now (within 30 min) gives the time left from there to your stop.

    Your real position holds the delay so far, and the timetable gives the rest. On a learned route, past rides come
    first (the engine's ETA: past rides × today's pace); the timetable is for buses not learned yet. It's shown in
    "6 min", "stop ~7:46", the journey list and the notification. The alarm keeps the engine's own, deliberately
    earlier estimate.
  - **"Alarm about 7:45"** (owner, 2026-11-24: "not consistent with the time until arrival") is when the alarm aims to
    ring: the time left shown, minus the stop's lead, or, with your own circle, when the bus reaches it
    (`TripSheetText.aimMs`).
    - It's not the scheduled system alarm. While GPS is fine that's the dead-man's switch (§2), a few minutes ahead
      and pushed on with every fix.
    - With no time left known it says "Alarm set". With GPS lost it says the real "at the latest" time.
  - One sheet over the map: "Get off at", the stop, "6 min · 2.3 km", "Alarm about 7:45 · stop ~7:46", and I'm off
    this bus. The alarm is meant to come first. With your own circle it says so: "Alarm about 7:42 at your 400 m
    circle · stop ~7:46".
    - Waiting: "4.1 km · waiting for the bus". For a route made by Plan a trip, each stop also knows its bus and
      where you get on (`LegDraft.bus`, display only, never the alarm), so waiting says "871 due 7:43, then 7:51"
      from the timetable (`Departures`), refreshed each minute.
    - After changing buses: "Off at X · next stop", "Waiting for bus 2", and Skip this bus.
    - GPS lost: the map greys out, the figures show their age, and a red line says when it rings at the latest
      (the failsafe).
    - **It folds to one line** for more map (PLAN B3, `CompactSheetText`): "Harbour St · 6 min · alarm 7:45",
      with I'm off this bus. Drag it down or touch the map to fold it; drag it up or tap it to open it.
  - Stop trip is in the ⋯ menu, and is confirmed.
- **The ring** (`ring`):
  - Black in both themes. "Wake up" (or "Urgent message"), then one line ("Harbour St in about 45 s", is close,
    passed, GPS, test).
  - A round button you hold for 1 s; the ring fills in orange. Or hold volume down.
  - Messages wait for the next screen.
- **After "I'm awake"** (`awake`):
  - "Now", then what to do: get off and what comes next, or stay on after an early ring, and why.
  - Then While you slept as plain rows (tap one to share it), then Done.
- **Routes** (`routes`): **Plan a trip** (PLAN Run 13, T4), like Google's:
  - From (Your location by default) and To, each with suggestions as you type: Home and Work, addresses, then AT
    stops. A swap button, and a **Leave now** chip: tap it for **Leave at** or **Arrive by** and a time.
  - Rides from the bundled timetable (`Timetable`, `Planner`: RAPTOR, up to 3 buses): "7:38 → 8:20 · 42 min", bus
    chips "866 › 70", "Walk 4 min · Central Station at 7:42". Times are the timetable's, not live.
    - Walks to the first stop and from the last follow **real footpaths** (`WalkGraph`, BACKLOG 1). Those are
      OpenStreetMap's walkable ways within about 1 km of each end, from the Overpass API, kept in the cache for 30
      days (`Footpaths`; a public mirror when the main server refuses, and a refusal or timed-out reply is never
      cached), and walked by the shortest path (up to 1.3 km). Until they arrive, or offline, walks are
      straight lines × 1.25. Walking is 4.5 km/h. Between buses: straight × 1.25, up to about 300 m. Only **one walk
      at a time** (never a walk to another stop and then on).
    - A change takes at least 2 minutes.
    - Each search keeps every way to arrive within 20 minutes of the fastest (another bus, another last stop). The
      list then drops a ride when another leaves about as late (within 3 minutes) and costs less, where a change
      costs 5 minutes and each walking minute counts half again (Google's habits). Sorted by when you leave.
    - Arrive by: the rides that get there in time, leaving latest, none more than 45 minutes before the last.
      Going to work or home, the Arrive by you set is remembered for next time (Settings, on the phone); Leave now
      or Leave at forgets it. The owner never wants to type times.
  - Tap a ride for its **map** (the buses in route blue through every stop, walks dotted, where you get on and off,
    the end as the red pin) and its timeline (leave, walk, bus with stops and times, change with the wait, arrive),
    then **Use this** or **Start now**. Using it makes a route whose stops are where you get off each bus, named "To <place>"
    ("To work" updates that route rather than adding another).
  - Pasting or sharing Google Maps directions fills From and To.
  - With no routes yet, **Home and work** comes first (PLAN Run 13, T5): "Where's home?", "Where's work?" (an
    address, or "I'm home now"), then the ride to work (leaving now) and home (leaving work at 5 pm). Each step can
    be skipped, the ride steps too. Now's empty state opens it too, and Settings → Home and work changes them.
  - **Privacy:** home and work are kept in the phone's settings only, never in a log, the repo or a share. The
    address text you type goes only to the phone's own geocoder (`Geocoder`, which is Google's on his phone), limited
    to Auckland. The timetable is offline. For footpaths, only a square about 2 km across around each end goes
    to OpenStreetMap's Overpass API (like the map tiles the map already loads), with a generic client name: never
    an address or a name.
  - One row per route: "2 buses · 12 rides", or its problem as a red line ("Hill Ave needs a stop").
  - + makes "Route N" with Stop 1 and opens the map at once (PLAN Run 11, B1): save it and the route is ready. Back
    out and it's gone. No names to type.
- **A route** (`route`):
  - The name (tap to rename), then the stops on a line: a ring for an alarm, dashed for no alarm, red for not set,
    each with "Alarm 45 s before · within 250 m".
  - "Add stop 2" (up to 3) opens the map straight away; backing out without saving drops it. Stops are "Stop 1",
    "Stop 2" until you tap a name to change it. A route with one stop set is ready.
  - One primary button for the next thing it needs ("Set Hill Ave"), or Start when it's ready.
  - Rename and Delete route (confirmed) are in the ⋯ menu. **No times.**
- **Set a stop** (`stop`):
  - The map (the same Google look) moves under a fixed red pin, and the wake circle follows it. Touch and drag
    the circle's edge to size it, or tap the circle for the slider. Tap the stop's name to rename it.
  - The field at the top (PLAN Run 12) searches stops, or takes a link:
    - **Search:** a stop number ("3353") or name ("Smales Farm"), nearest first, from the bundled Auckland
      Transport index (`TransitIndex.search`). Pick one and the map goes there; the stop takes its name.
    - **Links** (`MapsLink`): place and pin links (the place's own position, and its name), `q=`/`query=`, plain
      coordinates, directions (the destination), and `maps.app.goo.gl` short links, opened first (`ShortLinks`).
    - It warns outside New Zealand, or for a link that isn't a Google Maps place.
  - Tap a bus stop on the map (the blue dots): it becomes this stop, with its name.
  - Use my location; the compass once turned.
  - The sheet: "Hill Ave · wake me within", then Auto / 150 / 250 / 400 m, with Other size for the 50 m–2 km
    slider (`ZoneScale`), then Save stop.
  - The ⋯ menu: Rename stop, Turn alarm off, Forget learned rides, Remove this bus.
  - It opens on the stop, else the bus before it, else where you are.
  - Saving when only the circle changed keeps the stop exactly where it was. A running trip takes the new circle
    straight away (`TripService.onRoutesChanged`).
- **Shared from Google Maps** (PLAN Run 12, C1): Nodstop is in the Share sheet as "Add to Nodstop".
  - A shared place: "Add to To work" (as its next stop) or "New route"; Set a stop then opens on it.
  - Shared directions: a new route whose stops are the directions' waypoints and destination. The start is dropped:
    it's where you get on.
  - Receiving only (`ShareIn`).
- **Settings** (`settings`):
  - Home and work: the two places, and the steps to change them (PLAN Run 13).
  - Alarm sound: the chosen sound, and the list (§5 Ring ladder).
  - Where Nodstop works (last): a map of the covered area (the hull of the bundled AT stops, `Regions.hull`), and
    each region with its operator, stop count and a kind, slightly cheeky welcome (`Regions.all`). Auckland only,
    for now; regions are added one at a time.
  - Quiet other notifications (during trips); Ring out loud after 60 s; Ask how the timing was.
  - Important contacts: the names, plus chat notification access for While you slept.
  - Report a missed alarm: shares the last trip log with locations hidden, and the last ring's report as the
    message.
  - The credit for the bundled stop data ("Stop and route data: Auckland Transport, CC BY 4.0").
  - The build line. A long press opens Debug.
- **Debug** (a tool, plain):
  - Test ring in 30 s: the real AlarmManager path, disabled during a trip.
  - Simulated trip (§5 LocationSource), and sample chats for While you slept.
  - Tea tone and GPU (While you slept), and the Briefing lab.
  - Back up and restore.
  - **Kill app now** (`Process.killProcess`).
  - Forget all learned stops; share the last trip log, hidden or raw.
  - The last 200 trace events.

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
  The newest push on any branch wins: check the build number at the bottom of Settings.

Cloud sessions have no emulator and no device. Verify via JVM tests, lint and `assembleDebug`.
Anything device-only goes on the couch-test checklist in the PR description.

---

## 7. Test plan

### Couch test (about 20 min)

1. Setup: every row allowed (Continue lights up); Samsung steps done.
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
6. Set a stop: stand somewhere and tap **Use my location**: the map moves to you; Save stop.
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
  earbud tone and the at-stop buzz still come through.
  - **Only while the screen is off** (PLAN Run 10, A4; the owner: "only when the phone is off; when it's on,
    notifications still come through"). `INTERRUPTION_FILTER_ALARMS` is on while a trip runs with the screen off,
    and off while you use the phone or while it rings (`DndRestore.wanted`, `DndController.update` on screen
    changes, every tick and at the ring).
  - A Do Not Disturb you set yourself is never touched, and the previous filter comes back on Stop, DONE or
    service destroy, and on the next app start if a "set by us" flag survived a crash.
  - **Quiet trip** (PLAN R2): the switch ("Quiet other notifications · While the screen is off, on a trip") is
    the first row of Settings, and the first trip's Start asks once ("Quiet your trips?",
  Quiet my trips / Not now), asking for Do Not Disturb access in the same tap. Turned on (or allowed)
  mid-trip, it applies once at the next tick. The trip notification says "Quiet trip" ("Quiet trip: 6
  held" when notification access can count them). Still off until the owner turns it on.

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
  falls back to the digest for its conversations. No AI below 20% battery. **No model in memory at the
  ring:** each pass (and each urgent check) loads the model and frees it when done, no pass starts
  within 90 s of the scheduled alarm, and the ring cancels any model work and frees it. The ring shares
  this process, and under memory pressure (you're using the phone) Android kills the biggest process.
  The lead is short (§3), so the final pass may not finish before the ring: W2 shows the newest
  finished batch plus digest lines for anything newer.
- Urgent: only for important contacts the rider adds in Settings → Important contacts (matched as whole words against
  sender or conversation title; no contacts permission). Each new VIP message, newest first and at most
  5 per check, goes to the model alone ("YES or NO"); anything but a clear YES is not urgent. Urgent
  checks run before briefing passes, one model job at a time. Urgent sends `Input.Urgent` (W1) and
  marks that chat, which the alarm screen shows first ("urgent: this woke you").
- Tones: plain (default) or "tea" (Debug switch). Tea is a second model call over the checked lines;
  W5 is checked again on each rewrite, and a failing or missing rewrite keeps the plain line.
- After "I'm awake" (PLAN U5): a hold on the alarm screen **always** opens "You're awake", then
  **Done**. With nothing to read it says "Nothing new while you slept"; with notification access off,
  "Briefing unavailable: turn on While you slept…" and a one-tap **Turn on While you slept** button.
  Acked from the notification button or the volume key, the messages are kept and a silent
  **"What you missed (N chats)"** notification is posted: counts only (W3), private on the lock
  screen. Tapping it opens "You're awake" after unlocking; swiping it away, Done, or the trip ending
  forgets the messages.
- Share-back: "You're awake" has a **Share this line** button per line. Share unlocks the
  phone and opens the share sheet with that line only, plus "recapped while I napped · Nodstop"
  (`ShareText`, `ShareLine`: the only place a line leaves the app). Nothing is ever sent
  automatically. The messages are cleared on Done (or when that screen goes away), still W3.

### Model

Default Gemma 4 E2B (`.litertlm`), fallback Gemma 3 1B. Runtime: LiteRT-LM
(`com.google.ai.edge.litertlm:litertlm-android` **0.16.1**, the newest built with this project's
Kotlin 2.2.21; 0.17+ needs Kotlin 2.4), CPU backend by default. Debug → **Use the GPU (experimental)**
tries the GPU first and falls back to the CPU if the phone or model can't use it; the lab shows which
one ran, so both can be compared. A GPU driver can crash natively, past any `try`, so trips use the GPU
only after the lab has loaded the model on it successfully on this phone (`gpuVerified`). **Trips use a model
only after it has finished a lab run on this phone** (`modelVerified`, 2026-11-21). On the owner's Galaxy A17,
loading Gemma 4 E2B in the lab closed the app. A model too big for the phone's memory would otherwise be loaded
mid-trip, in the trip's own process, and take tracking down with it. Until a run finishes, the alarm screen
shows the digest. If a lab run closes the app, the next lab visit says why (`LabExit`, from Android's
`ApplicationExitInfo`: memory, or a native crash, and on the GPU or not). Before a run, the lab warns when free
memory looks too small and the button reads "Run anyway". The rider downloads the file on the phone and
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
