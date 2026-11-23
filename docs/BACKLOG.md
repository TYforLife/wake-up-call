# Backlog

Everything Tae has asked for that isn't built yet, plus the product direction it comes from. `docs/PLAN.md` holds
the runs planned in detail; this list is the queue those runs come from. Each item says why it matters to the rider,
what done looks like (acceptance criteria a session can prove), and what blocks it. Tick an item when its PR merges,
and move it to Done at the bottom. Add every new ask as he makes it.

**Running an item from the phone:** `/goal Build backlog item 12 in docs/BACKLOG.md and merge it`. The session
follows `docs/PLAN.md`'s runbook (latest `main`, tests, PR ending with "Test on phone", merge when green), and
`CLAUDE.md`'s rules win over anything written here. One item is one PR unless it lists steps; then one step is one
PR. Item numbers never change or get reused (1–9 were used by older items, so new ones start at 10).

## The goal (read this first)

> **Fall asleep on the bus, and trust that you'll wake up 30–60 s before your stop. Every time.**

Two things have to be true, and the second one is the product:
1. **It works**: zero missed stops; 30–60 s before the stop on 90% of legs once learned, never under 25 s
   (DESIGN §1).
2. **He believes it works**, enough to actually close his eyes without a backup alarm. Belief comes from evidence
   the app shows him, not from his memory of the last miss.

And it should cost nothing to use: **no typing, no remembering, one tap or none** (OWNER "What he cares about").

**How we know we're winning** (all measured on the phone, from the trip log, never sent anywhere):
- missed stops: 0;
- legs rung 30–60 s before the stop: 90% or more of learned legs;
- commute days he forgot to start a trip: 0;
- taps from pocket to "alarm armed": 1, then 0;
- battery per 40-minute trip: under 5% on the Galaxy A17.

## What's holding the app back (product review, 2026-11-23)

From the rider's side, in order of how much each one costs:
1. **He still can't see that it works.** "Last trip" says whether it rang, not how well. Nothing shows the success
   metric, so one miss outweighs twenty good rings. Ratings are asked for, and asking is friction. → items 10, 11
2. **It only works if he remembers to tap Start, at the stop, half awake.** A forgotten Start is a missed stop that
   no ring path test can catch. → items 12, 13
3. **Nothing checks the ring will get through before he dozes off.** Earbuds flat, volume 0, a permission Android
   took back after an update: he finds out at the stop. → item 11
4. **GPS at full rate for the whole ride** on a budget phone. A dead phone wakes nobody. → item 14
5. **The app knows only GPS, not the bus.** No live bus, no "your bus is cancelled", no dozing at the stop while
   waiting, no help after a missed stop. → items 1, 15, 16, 17
6. **Without earbuds, a buzz in a bag may not wake him.** → item 18
7. **Anything that isn't his commute takes several screens.** A one-off trip should be one search. → items 19, 20
8. **A stranger couldn't start it.** Six permissions, Samsung steps, an APK from GitHub. Fine for Tae, a wall for
   anyone he posts it to. → items 21, 22, 23, 24
9. **While You Slept can't run its model on his phone** (Gemma closed the app on the A17). It is weight without
   payoff today. → decision D1

## Product direction (2026-11-23)
- **Auckland and AT only, for now.** Tailor everything to Auckland Transport: AT's timetable, AT's live data,
  Auckland places. Then add the next region, and so on.
- **Post about it** once it's solid for Auckland (items 21–24 are what "solid" means for a stranger).
- [x] **A map of covered regions**, at the end of Settings ("Where Nodstop works"), that grows as regions are added.
- [x] **Each region gets a small, kind welcome joke** when it's added (Auckland's: "…and long before you move to
  Australia"). A little cheeky is fine, such as the NZ–Australia "brain drain" jokes. Kind, never mean.

## Decisions for Tae
- **D1. While You Slept on the A17.** The model can't load on his phone, so the alarm screen shows the plain digest.
  Options: (a) keep the digest, hide the Briefing lab and the model from Settings until a phone can run it;
  (b) keep everything as is; (c) try Gemma 3 1B as the default on phones under 6 GB. Recommended: (a), then (c) as
  its own item if he still wants summaries. Nothing is built until he picks.

## Now: trust it enough to sleep

### 10. [ ] Ride report: see how well it woke you
**Why:** the success metric exists only in DESIGN. He should see it, per stop, without rating anything.
- **He sees:** Now's "Last trip" line gains the timing: "Rang 48 s before Smales Farm · Buzzed". A **Rides** row
  (Settings, and a tap on Last trip) lists the last 20 rides: date, route, per stop "48 s early" (good), "2 min early"
  (early), "12 s early" or "after the stop" (red). On top: "Last 20 legs: 18 on time, 2 early, 0 late".
- **Acceptance:**
  - [ ] How early it rang is measured, not asked: from the trace, the time between the first ring and the moment the
        bus reached the stop (nearest fix to the pin, or the start of the get-off spell). Pure Kotlin in `:engine`,
        tested on synthetic trips and on every trace in `engine/src/test/resources/traces/`.
  - [ ] A leg where you stopped the trip or got off first says so ("You were off first"), never "late".
  - [ ] The measured timing feeds the per-stop lead (DESIGN §2.5) the way a rating does: "late" counts as "too late"
        automatically. It may only make a lead **longer** on its own (rule 4); "too early" still needs his rating.
        Property-tested: an auto-adjusted lead never rings later than the default.
  - [ ] The "How was the ring timing?" notification is off by default once this ships (Settings keeps the switch).
  - [ ] Numbers only, no positions, in what's stored and shown (DESIGN §5 privacy).
  - [ ] Screenshot tests: Now with the line, the Rides list (good, early, late, off first).
- **Test on phone:** after a real ride, the line shows a number that matches what you felt.

### 11. [ ] Ready check at Start: know it will get through before you doze
**Why:** every past miss was a setting or a state he couldn't see at the stop.
- **He sees:** tapping Start (anywhere: Now, tile, shortcut, notification) shows one line on the trip sheet for 10 s:
  "Ready · earbuds 60% · buzz on" in calm text, or one red line with one tap to fix: "Earbuds at 8%: you'll get
  buzz only", "Alarm volume is 0", "Nodstop can't buzz on Mute: Allow", "Phone at 9%: plug in or it may die before
  your stop".
- **Acceptance:**
  - [ ] One pure function decides the line from a snapshot: required permissions, alarm channel state, Do Not Disturb
        access, ring mode, alarm volume, earbuds connected and their battery when the phone reports it, phone
        battery, battery optimisation. Unit-tested for every red case and for the calm case.
  - [ ] It never blocks or delays Start, and never changes the alarm (rules 1, 2). `RingPathTest` unchanged.
  - [ ] The same check runs again when the phone is unplugged or earbuds disconnect mid-trip, and the trip
        notification shows the red line until it's fixed or the trip ends.
  - [ ] No new permission is asked for just for this.
- **Test on phone:** Start with earbuds out, with alarm volume at 0, with Mute on: each shows its line.

### 12. [ ] Never forget to start (commute nudge)
**Why:** a trip he never started can't ring. He's half awake at the stop.
- **He sees:** on a commute day, near his usual boarding stop at his usual time, a silent notification: "To work?
  · 866 due 7:43" with **Start**. One tap from the lock screen arms the trip. If he's already on a bus (moving at bus
  speed on the route's line) it says "On the 866? Start To work".
- **Acceptance:**
  - [ ] When and where come from his own past starts (`RouteSuggest` already knows the time; the place is where past
        trips' first fixes were), never from a typed time. Pure and tested: weekdays, a holiday gap, two routes at
        similar times.
  - [ ] Location use stays inside what's already allowed: no background location permission. If that makes "near the
        stop" impossible, it falls back to time only ("To work? You usually leave about now") and says so in the PR.
  - [ ] One nudge per route per day, gone after 20 min or once a trip starts. A switch in Settings turns it off.
  - [ ] **Start** from the notification needs no unlock and starts exactly as Now's Start does (same code path).
  - [ ] Nothing here schedules, delays or cancels an alarm; a nudge is not a ring (rule 1).
- **Test on phone:** on two commute mornings the nudge appears at the stop, and Start from the lock screen works.

### 13. [ ] Start without touching the phone (auto-arm), after 12
**Why:** the end state of "no remembering": he gets on, it's armed.
- **He sees:** opt-in, off by default. On a commute day, once Nodstop knows he's on a bus on a known route, the trip
  starts by itself and buzzes once: "To work is on". Stop trip undoes it.
- **Acceptance:**
  - [ ] Uses activity recognition ("in a vehicle") as LEARN.md L0 measures it; no always-on GPS. Battery under 2% a
        day for the watching (measured on the A17, numbers in the PR).
  - [ ] It only ever starts a trip; it never stops one or changes an alarm. A started trip is an ordinary trip.
  - [ ] It needs his go: it shares LEARN.md's L0 findings, so it waits for L0's report.

### 14. [ ] A trip costs under 5% battery
**Why:** GPS runs at 2 s for the whole ride on a budget phone; a flat phone is a missed stop.
- **He sees:** "This trip used 3% battery" on the Rides list (item 10). Nothing else changes.
- **Acceptance:**
  - [ ] Far from the stop (more than the failsafe radius plus a margin), the fix interval may grow; near it, it goes
        back to 1–2 s. The engine decides the wanted interval (pure, an output like `alarmAtMs`), the app applies it.
  - [ ] Rule 2 and 4: `InvariantPropertyTest` and `SkipPropertyTest` run with the slower fix rates and still hold.
        A slower rate may only make the failsafe fire **earlier**, never later. Teeth check included.
  - [ ] Battery per trip measured and logged (counts only) and shown in item 10.
  - [ ] On the A17: a 40-minute ride uses under 5% (PR reports before and after).
- **Test on phone:** two real rides, read the battery line.

## Now: walks like Google's

### 27. [ ] Every walk follows the footpaths, on the map too; the straight line is the last resort
**Why:** he judges the planner against Google Maps (OWNER), and Google draws the walk along the streets. Today the
minutes to the first stop and from the last follow OpenStreetMap's footpaths, but the map draws a straight dotted
line, walks between buses are straight × 1.25, and several cases quietly fall back to the straight line. His words
(2026-11-23): "make it match. Find a way. No nos. The fallback is last last last resort."

**What's there now** (read `WalkGraph.kt`, `Footpaths.kt`, `Planner.kt`, `JourneyShape.kt`, `PlanHost.kt`):
- `WalkGraph.from(p)` runs Dijkstra and keeps only distances; `Planner.Walks.to` returns metres; `Leg.Walk` has no
  path; `JourneyShape` draws `listOf(from, to)`.
- CI (run 182, Auckland City Hospital, 1 km box: 1.9 MB, 18 269 points, 267 ms) walked the 8 nearest stops at
  1.4–2× the straight line: Park Road 115 m straight → 237 m, Whitaker Place 529 → 969 m, Symonds St/K Road
  551 → 887 m. Whether those match Google is not yet checked.
- Ways it silently falls back to the straight line × 1.25 (each one errs short, so it's wrong in the bad direction):
  1. paths not loaded yet, offline, both Overpass servers refuse, or the cache is over 30 days old (an expired file
     is ignored even when a refresh then fails);
  2. the place is more than 80 m (`SNAP_M`) from a path (a big park, a campus, a large building);
  3. the stop snaps to a path that isn't joined to yours (`nearest` picks the nearest node, reached or not), or the
     walk needs a way outside the 1 km box: `to` returns null and `walksNear` uses the straight line, so a stop
     1.5 km away on foot across a motorway shows as ~400 m;
  4. every walk between buses (`Transfer`, up to `CHANGE_M` 250 m).
- The filter leaves out `trunk`/`trunk_link`; NZ urban state highways are often trunk with their footpath tagged on
  the road (`sidewalk=*`) rather than drawn separately, so walks beside them may be missing or long.
- Plazas and concourses (`area=yes`) are walked round their edge, not across.
- `RealWalkTest` skips when CI's single Overpass fetch fails (`continue-on-error` + `assumeTrue`), so green CI
  doesn't prove it ran.
- This sandbox can't reach Overpass or any router (network allowlist; don't work around it). Real-data checks run
  in CI: push, then read the job log with the GitHub MCP tools (`actions_list`, `get_job_logs`).

**Done when** (one PR; one commit per part):
- **The map draws the walk.** `WalkGraph` keeps each point's previous point and returns the path, not only metres
  (`Walks.to` → a walk with metres and points). `Leg.Walk` carries its points (place → first path point → … → last
  path point → stop, so it joins the pin and the stop). `JourneyShape` draws them, dotted as now. The metres shown
  are the path's length, and a test checks path length = metres for every walk leg.
- **Unreachable means unreachable.** Once the paths for an area are loaded, a stop they can't reach within
  `MAX_WALK_M` is not walkable (dropped, like a stop too far away), never straight × 1.25. The stop snaps to the
  nearest point the walk actually reached within the snap distance, so a disconnected lane next to it doesn't hide
  the real way. The box is big enough for every walk the planner allows (`ACCESS_M` stops, walks up to
  `MAX_WALK_M`), and that's tested from `query`'s numbers. `WalkGraphTest`'s "not joined up" case changes to match
  (this is the intended change, say so in the PR).
- **Places off the paths still walk.** A place up to about 250 m from any path snaps to it (counted in the metres
  and drawn), so parks, campuses and big buildings get a real walk.
- **Walks between buses follow footpaths.** For the rides shown, the footpaths around each change stop are loaded
  (cached like the ends); the change walk is drawn and timed along them. A ride whose change no longer fits with
  the real walk (and `CHANGE_S`) is not shown, or is re-planned with the real time. Test with a fixture where the
  straight line fits and the real walk doesn't.
- **The straight line really is the last resort.** In order, each tested: fresh cache → Overpass → the mirror (add
  another live public mirror if one exists) → the expired cache (kept when a refresh fails, refreshed in the
  background) → a public foot router (FOSSGIS `routing.openstreetmap.de`, OSRM foot profile: route geometry and
  metres, generic client name, only the two points, never a name; one-line reason in the PR, DESIGN §5 updated) →
  straight × 1.25. Footpaths for Home, Work and every saved route's stops are fetched when they're set (and on the
  next start if that failed), so his commute never plans on straight lines. A fallback walk says so on screen
  ("about 4 min"), and the map line stays dotted but lighter.
- **The ways match what you can walk.** `trunk`/`trunk_link` included unless `foot=no`, `motorroad=yes` or
  `sidewalk=no|none`; motorways stay out. Pedestrian areas are crossable. Check the filter on the hospital data:
  print which ways it adds or removes and why.
- **Checked against another router and Google's habits, in CI.** CI fetches the hospital paths with retries and the
  mirror, and the same 8 walks from the FOSSGIS foot router; `RealWalkTest` fails (not skips) when `CI=true` and the
  fixture is missing, and each walk's metres are within 15% of the router's (print both). Plus a well-known public
  trip (Britomart → Auckland City Hospital) whose walks are printed with their paths for the PR.
- **Fast enough on the A17.** The bigger box is measured in CI (size, points, build + walk time) and printed; if
  building the graph is over ~300 ms on CI, cache the parsed graph compactly rather than re-parsing JSON.
- DESIGN's Plan a trip section and BACKLOG "Done" updated; `escape-hatches.txt` never goes up.
- **Test on phone:** plan Home → Work and Work → Home and compare each walk's line and minutes with Google Maps;
  plan with flight mode on after a first plan (cached, still along the paths); a trip from somewhere new with flight
  mode on (the only time it may say "about").

## Next: know the bus, not just the GPS

### 1. [ ] Your bus, live, from AT
Show the coming bus for this trip on the map, with AT's predicted arrival at your stop. That makes the trip
measurable.
- Started offline: while you wait, the sheet says "871 due 7:43, then 7:51" from the timetable (routes made
  by Plan a trip remember each bus). Live times replace those once there's a key.
- **Waiting on Tae:** an AT API key, which is free from AT's developer portal. He enters it in Settings, and it's
  kept on the phone, never in the repo.
- Rule 6: live data may only make an alarm earlier. GPS stays the fallback.
- Plan, once there's a key (each step its own PR):
  1. Settings → Live buses: paste the key (kept on the phone), and **Check** says whether AT accepts it.
  2. CI keeps working without the key. A key never goes in the repo, CI or a log.
  3. While you wait: the coming bus of your route on the map, from AT's vehicle positions. The sheet says
     "871 · 3 min away (live)" instead of the timetable.
  4. On the bus: match you to the vehicle that moves with you for about a minute. Show AT's predicted arrival
     at your stop next to ours.
  5. The alarm may use AT's arrival only to ring earlier (rule 6), with a test proving it never rings later.

### 15. [ ] Doze at the stop: wake me when my bus is near
**Why:** the wait at Smales Farm is sleep time too, and Auckland buses only stop if you hail them (DESIGN §8).
- **He sees:** while waiting, the sheet offers "Wake me when the 866 is 4 min away". Then the phone can go in the
  pocket. It rings (the one ring path) when the bus is about 4 minutes out, so he can stand up and hail it.
- **Acceptance:**
  - [ ] With live data (item 1): rings when the matched vehicle's predicted arrival is ≤ 4 min. Without it: the
        timetable departure minus 4 min, labelled "timetable only".
  - [ ] It is an ordinary scheduled alarm on the one ring path, with its own reason ("Your bus is coming"); the
        stop alarm for the ride itself is untouched. Engine tests: a bus early, late, cancelled, and no data.
  - [ ] With both, it rings at the earlier of the two (rule 6: live data only makes it earlier). A bus that
        turns out late means a longer wait awake, never a missed bus.
- **Needs:** item 1 step 3 for live; the timetable path can ship first.

### 16. [ ] Your bus is late or cancelled: tell me while I can still act
**Why:** a cancelled 866 means a missed connection and a late arrival he finds out about at the stop.
- **He sees:** a heads-up while waiting or at a transfer: "866 at 7:43 is cancelled. Next 7:51" or "Your NX2 is 9
  min late: the 7:58 70 still works". Before leaving home on a commute day (with item 12): "Leave 5 min later".
- **Acceptance:**
  - [ ] Reads AT's service alerts and trip updates for this trip's buses only.
  - [ ] Notifications only; never changes, delays or cancels an alarm (rule 6). Tested with recorded AT feed
        fixtures, no key in CI.
- **Needs:** item 1 step 1.

### 17. [ ] Missed it anyway: get me back
**Why:** "You passed your stop" today says what happened, not what to do.
- **He sees:** after a "You passed your stop" ring is acked: "Get off at Akoranga (next stop) · walk back 9 min, or
  the 866 back at 8:04". Tapping it opens the walk or the ride on the map.
- **Acceptance:**
  - [ ] Uses the bundled timetable and planner (`Planner`) from the next stop on this bus's pattern back to the
        missed stop and to the trip's end. Pure and tested on the fixture.
  - [ ] Shown on the "Now" card after the ack; never changes the ring.

## Next: wake reliably without earbuds

### 18. [ ] Buzz on the wrist (Galaxy Watch)
**Why:** a watch on the wrist wakes far better than a phone in a bag, and many Samsung users wear one.
- **He sees:** if a Galaxy Watch is paired, the ring also buzzes the watch, strongly and repeating, with "I'm
  awake" on the watch face (hold to stop, like the phone).
- **Acceptance:**
  - [ ] The watch is driven by the ring itself (`RingService`), so it can never ring on its own or at another time
        (rule 1). Losing the watch connection never stops or delays the phone's ring. `RingPathTest` covers a ring
        with the watch gone mid-ring.
  - [ ] "Ready" (item 11) says "watch connected" when it is.
  - [ ] A new dependency (Wear OS data layer) needs its one-line reason in the PR (rule 5).
- **Open:** whether it ships as notification mirroring only (no watch app) or a small watch app. The session finds
  out and reports; mirroring first if it gets through.

## Next: anything that isn't the commute

### 19. [ ] "Where to?" on Now: a one-off trip in one search
**Why:** Google Maps' habit: open, type where, go. Today a one-off trip is Routes → Plan a trip → pick → Start.
- **He sees:** a "Where to?" bar at the top of Now (idle). Type or paste a place or stop, pick a ride, **Start now**.
  The trip runs like any route; afterwards it's offered once: "Keep as a route?"
- **Acceptance:**
  - [ ] Reuses Plan a trip's search, planner and ride list (no second planner, CLAUDE.md "One way").
  - [ ] Pasting or sharing a Google Maps place or directions while on Now does the same.
  - [ ] From opening the app to "alarm armed": type the place, plus 2 taps. Counted in the PR.
  - [ ] Not keeping it leaves no route behind.

### 20. [ ] Home screen widget
**Why:** one tap from the home screen, and the trip's state at a glance without opening anything.
- **He sees:** a widget, black and white: idle, "To work · 866 due 7:43 · Start"; riding, "Smales Farm · 6 min ·
  alarm 7:45" with "I'm off this bus".
- **Acceptance:**
  - [ ] Its actions are the notification's and Now's (same code paths, same unlock rules as DESIGN §5
        Notifications).
  - [ ] Words come from the existing pure text functions (`TripNoteText`, `RouteText`), tested.
  - [ ] No new dependency unless Glance is needed, with its reason (rule 5).

## Later: ready for strangers (before posting about it)

### 21. [ ] First run for someone who isn't Tae
**Why:** six permissions and Samsung steps before the first ride; a stranger quits at the second one.
- **He sees (as a new user):** three screens at most: what Nodstop does and that location never leaves the phone;
  "Where's home? Where's work?" (exists); then one "Let it wake you" step that asks the permissions in order, each
  with one plain line why. Under 2 minutes to the first armed trip, counted in the PR.
- **Acceptance:**
  - [ ] Every permission is asked in context with one line, never as a list of jargon (OWNER: no "DND", "failsafe").
  - [ ] Non-Samsung phones get their own battery steps (Pixel, Xiaomi, Oppo), or none where none are needed.
  - [ ] The first ride tells the rider to stay awake with a backup alarm until the route is learned (DESIGN §8).
  - [ ] Screenshot tests for each step.

### 22. [ ] Install without GitHub (friends beta)
**Why:** "download an APK from a GitHub release" is fine for Tae, not for the people he posts to.
- **Acceptance:**
  - [ ] A release key in GitHub secrets, a signed release build, and Play internal testing (or closed testing) set
        up, with the Play declarations DESIGN §8 lists (exact alarm, full-screen intent, location foreground
        service, battery exemption).
  - [ ] Final name and application ID confirmed by Tae first (DESIGN §9); the ID can't change after.
  - [ ] A privacy policy page: "your location never leaves the phone".
- **Needs:** Tae's decisions on the name and the Play account.

### 23. [ ] The timetable says when it's old
**Why:** the timetable is bundled in the APK. An old install quietly plans with dead buses.
- **Acceptance:**
  - [ ] When the bundled calendar ends within 7 days, or has ended, Plan a trip and the waiting sheet say "Timetable
        out of date: update Nodstop", and timetable times are labelled as such. Alarms never depend on it (rule 6).
  - [ ] Tested with a fixture calendar that has run out.

### 24. [ ] Learn a trip by riding it (`docs/LEARN.md`, milestones L0–L5)
Worked on only when he says so (`/goal Finish L1 in docs/LEARN.md and merge its PR`). It is the "no typing at all"
path for new users, so it belongs before posting about the app. Recommended next step: L0's report, from one
recorded ride.

## Small delights (any time)
- [ ] **25. "Grab your bag"**: the at-stop nudge (DESIGN §2.1) adds "Got everything?" on the last stop of a trip.
  Text only, tested in `Texts.kt`.
- [ ] **26. Share my arrival**: on the trip sheet's ⋯, "Share arrival" opens the share sheet with one line, "On the
  866, at Symonds St about 8:22 · Nodstop". Text only, never a position or a link; nothing is sent automatically.

## Later
- Learning a trip by riding it: item 24.
- The phone speaker as a default escalation, earbud tap gestures to stop the alarm, iOS (DESIGN §8).
- Localisation, an accessibility pass (PLAN "Later").

## Done
- [x] **The missed ring at the first stop (2026-11-23).** "It didn't ring again… It rang fine on 2nd stop."
  - The start-of-trip leg pick could skip stop 1 because of a learned ride for stop 2 that began at home. Every
    such trip taught it the same again.
  - Fixed three ways:
    - the pick never skips a stop that's still ahead on that ride;
    - a stop's learned ride starts after the previous stop;
    - a skipped stop the bus comes up to rings at once.
  - Tests in `StartAnywhereTest`.
- [x] **The trip map shows the stops to come.** The current stop's circle is strong, later stops are faint, and
  stops behind you aren't circled.
- [x] **A scale bar on the trip map.**
- [x] **After changing buses, the map frames you and the next stop again.**
- [x] **Walking along real footpaths (2026-11-23).** OpenStreetMap's paths around each end, by Overpass, cached
  30 days. Real walks near Auckland City Hospital were 1.4–2× the straight line.
- [x] **The alarm and "min left" agree.** The sheet shows both, labelled: "Alarm about 7:58 at your 400 m circle ·
  stop ~8:02". Live AT arrival times come with item 1.
- [x] **Arrive by is remembered** for work and home ("To work" arrive by 8:30, set once).
- [x] **Alarm sounds.** Beeps, Chime, Rising, or your own mp3/wav/m4a (the first 20 s loop). The beeps
  if your file can't play. "Try it" is a real test ring.
- [x] **Pūkeko is back, from real recordings (2026-11-23).** A morning chorus that loops, with screams at
  random on top, different every ring.
- [x] **Say when a trip starts past a stop.** "Started past Smales Farm", with **Start at Smales Farm**.
