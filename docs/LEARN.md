# Track A: learn a trip by riding

Onboarding A (owner, 2026-11-22): "Do both A and B. Do B first." B (Home and work, by address) is Run 13 in
`docs/PLAN.md`. This file plans A for its own session: work it milestone by milestone, only when the owner says so
(e.g. `/goal Finish L1 in docs/LEARN.md and merge its PR`). Feasibility is deliberately left open: find it out in
L0 and in each milestone, and report anything that turns out impossible rather than working around it.

Read first: `CLAUDE.md` (the rules: one ring path, ring earlier never later, pure engine, no new dependencies
without a reason), `docs/OWNER.md` (who Tae is and what he dislikes), `docs/DESIGN.md`. The runbook in
`docs/PLAN.md` applies to every milestone: start from the latest `main`, run the tests, open a PR whose
description ends with "Test on phone", and merge only when the prompt says so with CI green.

## The idea

Tae taps one thing and rides as he always does. Nodstop notices when he's on a bus and when he's walking, works out
which buses he took and where he got off, and offers the result: "You rode the 866 from Smales Farm to Symonds St,
then the 70 to Hill Ave. Save as To work?" One tap, and the route exists with every stop. No addresses, no stop
numbers, no maps. Later, it can notice his regular trips by itself and offer them.

## What it can use

- GPS (already used for trips), and Android's Activity Recognition Transition API (in a vehicle, on foot; free, on
  the phone, needs the `ACTIVITY_RECOGNITION` permission).
- Auckland Transport's GTFS, already bundled (`TransitIndex`, `tools/gtfs_index.py`): stops and route patterns,
  timetables from Run 13, and route shapes (`shapes.txt`, not bundled yet).
- Optionally AT's Realtime API (bus positions; free key) to tell which bus he's on. Rule 6: live data never makes
  an alarm later.

## Milestones

### L0 Spike: what the phone can tell us
Done in two steps (owner, 2026-11-22): step 1 ships the spike screen; step 2 writes the report from one ride the
owner records and shares (`/goal Finish L0 in docs/LEARN.md: write the report from this ride file`).

- [x] A Debug screen "Learn spike" records one ride's raw signals: GPS fixes, activity transitions (with their
  confidence and delay), speed. It shows a timeline and shares it as a location-hidden file.
  Debug → Learn spike (`LearnSpikeService`, `SpikeLog`; format and report maths in `engine/LearnSpike.kt`). Tap
  **Got on** / **Got off** (screen or notification) as it happens: those marks are what the delays are measured from.
- [ ] A short report in the PR: on the owner's Galaxy A17, how quickly "in a vehicle" and "on foot" arrive after
  boarding and after getting off, how reliable they are on a bus (versus a car), and the battery cost per 30 min.
  Recommend L1–L3's thresholds from it.

**Acceptance:** one real ride recorded and shared; the report answers each question with numbers.

### L1 Record a learning ride
- [ ] "Learn this trip" (Routes, and the empty state): a foreground service records GPS and activity
  transitions until he taps Done, or he's been on foot for 5 minutes after the last ride.
- [ ] Before it starts, it says plainly that **there's no alarm on a learning ride** (stay awake, or set a stop the
  usual way). It never schedules or changes an alarm (rule 1).
- [ ] The recording is stored on the phone only, as a trip log (`traces`, location hidden when shared). It's never
  committed: synthetic tracks only in tests.

**Acceptance:** a ride recorded end to end on the phone; JVM tests for the start and stop rules; nothing touches
`AlarmScheduler`.

### L2 Split the ride into buses and walks
- [ ] Pure Kotlin (no Android): from fixes and activity transitions, segments of **on a bus**, **walking** and
  **waiting**, with each segment's start and end. Bus: in a vehicle, or bus-like speeds with stop-and-go. Waiting:
  still, at a stop.
- [ ] Tested on synthetic tracks from the trace generator: one bus; two buses with a walk between; a bus then a
  short wait at the same stop; GPS gaps; a car ride (not a bus: no stop-and-go at bus stops).

**Acceptance:** every synthetic case segments correctly; misses are reported as "unsure", never guessed.

### L3 Match each bus to an AT route and stops
- [ ] Pure Kotlin: each bus segment is matched to the AT route pattern it followed (track against route shapes or
  stop sequences, plus timetable times from Run 13), with the stop he got on at and the stop he got off at.
- [ ] Several candidates: the best, with a confidence, and the runners-up. Low confidence: say so and ask, never
  guess silently.
- [ ] Tested on synthetic rides along fixture routes, including two routes sharing a road, and a detour.

**Acceptance:** fixture rides match the right route and get-off stop; ambiguous cases return more than one candidate.

### L4 Offer the route
- [ ] After a learning ride: "You rode the 866 from Smales Farm to Symonds St, then the 70 to Hill Ave". Then **Save
  as To work** (or From work, or a name), **Change**, and **Not this**.
  - Change lets him fix a bus (from the runners-up) or a stop (from that pattern's stops), in a tap each.
- [ ] Saving creates a route whose stops are the get-off stops, with what the ride learned (its track) stored like
  any learned ride, so the first real trip already knows the way.
- [ ] Screenshot tests of the offer and the change sheet.

**Acceptance:** from a recorded ride to a saved route in one tap; the route starts and rings like any other.

### L5 Notice regular trips (passive)
- [ ] Optional, off by default: with the owner's permission, Nodstop notices weekday rides by itself (activity
  transitions in the background, GPS only while in a vehicle) and, after the same trip twice, offers it as a route.
- [ ] Battery under 2% a day; nothing leaves the phone; one switch turns it off and forgets everything it noticed.

**Acceptance:** two synthetic "same commute" days produce one offer; one-off trips don't.

## Not in this track

The alarm itself (Runs 10 and 13 own it), address search and the journey planner (Run 13), and anything that
sends his location anywhere.
