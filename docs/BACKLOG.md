# Backlog

Everything Tae wants that isn't built yet, as a feature list with **his** priorities (2026-11-23). `docs/PLAN.md`
holds the runs planned in detail; this list is the queue they come from. Tick a feature when its PR merges and move
it to Done at the bottom. Add every new ask as he makes it, with his priority if he gave one.

**Running a feature from the phone:** `/goal Build F10 in docs/BACKLOG.md and merge it`. The session follows
`docs/PLAN.md`'s runbook (latest `main`, tests, PR ending with "Test on phone", merge when green), and `CLAUDE.md`'s
rules win over anything written here. One feature is one PR unless it lists steps. Feature numbers (F1–F38, then new
ones from F39) never change or get reused. The older numbered items (1–26) are retired: item 1 is now F13.

Each feature says what he sees, in his words where he gave them, and "Done when" for the ones ranked medium or
higher. A session turns "Done when" into tests; anything only a bus can prove goes in "Test on phone".

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


## Product direction (2026-11-23)
- **Auckland and AT only, for now.** Tailor everything to Auckland Transport: AT's timetable, AT's live data,
  Auckland places. Then add the next region, and so on.
- **Post about it** once it's solid for Auckland (items 21–24 are what "solid" means for a stranger).
- [x] **A map of covered regions**, at the end of Settings ("Where Nodstop works"), that grows as regions are added.
- [x] **Each region gets a small, kind welcome joke** when it's added (Auckland's: "…and long before you move to
  Australia"). A little cheeky is fine, such as the NZ–Australia "brain drain" jokes. Kind, never mean.


## High

### F35. [ ] On the Play Store, starting with Tae
"high high prio." Install and update from Play instead of the GitHub APK, for him first, the public later.
- **Done when:**
  - [ ] A release key in GitHub secrets (never in the repo) and a signed release build (`.aab`) from CI.
  - [ ] An **internal testing** track he installs from: no 14-day wait, no review queue to speak of, updates
        through Play. The debug APK keeps working alongside it.
  - [ ] The Play declarations DESIGN §8 lists are written and pass: exact alarm and full-screen intent (an alarm
        app), location foreground service (with a demo video), and the battery-optimisation request, which Play
        restricts (if it's refused, open the battery settings page instead and say so in the PR).
  - [ ] A privacy policy page: "your location never leaves the phone".
  - [ ] The steps only Tae can do are listed in the PR, one line each (developer account, identity check, and the
        12 testers for 14 days a new personal account needs before production).
- **Needs Tae:** the final name and application ID (DESIGN §9: the ID can't change after the first upload), and a
  Play developer account.

### F10. [ ] Home screen widget
"that'd be amazing. high prio." Start a trip and see it live without opening the app.
- **Done when:**
  - [ ] Idle: the likely route and its next bus, "To work · 866 7:43", with **Start** (one tap, no unlock needed
        beyond the home screen).
  - [ ] Riding: "Smales Farm · alarm 7:45" with **I'm off this bus**, updated with the trip notification.
  - [ ] Its actions are the notification's and Now's (same code paths, same unlock rules, DESIGN §5 Notifications).
        Words from the existing pure text functions, tested. Black and white, the v2 look.
  - [ ] No new dependency unless Glance is needed, with its reason (rule 5).

### F30. [ ] Sleep stats
"amazing. High prio (exclude phone on time)." How much he slept on the bus.
- **Done when:**
  - [ ] "You slept 14 h on the bus this month" (and this week, and per ride), counting only time on a trip with the
        **screen off**: time using the phone never counts.
  - [ ] Read from the trip logs on the phone; counts only, no positions; nothing leaves the phone.
  - [ ] A place to see it (Now's "Last trip" area or Settings) and a screenshot test. Pure and tested on synthetic
        traces with screen on and off.
- See P5 for sharing it.

### F29. [ ] Trains and ferries
"Yes. Add soon." AT's trains and ferries, not just buses.
- **Done when:**
  - [ ] Plan a trip, stop search and the map show stations and wharves with train and ferry icons, and rides
        that mix them ("Western Line › 866"). AT's feed has them; `tools/gtfs_index.py` doesn't filter by mode, so
        check what's already in the index first.
  - [ ] Words say train or ferry, not bus, wherever the ride knows its mode.
  - [ ] The engine's limits fit trains: `V_MAX` and boarding detection are checked against a train's speed.
        Underground stations lose GPS, so the failsafe rings early there (rule 4); engine tests for a fast train
        and for a tunnel just before the station, with the invariant holding.
  - [ ] A ferry ride (open water, no roads) rings in time: a synthetic test.

### F3. [ ] Buzz on the watch: Coros first, Galaxy Watch too
"and for Coros as well. I use coros. somewhat high prio." The ring buzzes the wrist.
- **Done when:**
  - [ ] Coros: Coros has no app platform for this; the COROS app mirrors phone notifications to the watch. So the
        ring's notification must reach the watch and **keep buzzing** it until "I'm awake" (one buzz isn't an
        alarm). The session finds what makes the Coros buzz again (re-posting from `RingService` every few seconds,
        or a separate alerting notification) and proves it on Tae's watch.
  - [ ] Galaxy Watch: the same mirroring first; a watch app with hold-to-stop only if mirroring can't buzz enough.
  - [ ] Everything comes from the ring itself (`RingService`): the watch never rings on its own or at another time
        (rule 1), and losing the watch never stops or delays the phone's ring. `RingPathTest` covers it.
  - [ ] Setup or Settings says "Turn on Nodstop in the COROS app's notifications" with the steps.
- **Test on phone:** a test ring buzzes the Coros until you hold "I'm awake".

### F33. [ ] While You Slept: the message summary only
"message summary. We'll find ways to use AI… later." The model can't load on the A17.
- **Done when:**
  - [ ] The alarm screen and "You're awake" show the plain digest. Settings shows no model. The model, its import,
        the Briefing lab and the tones stay reachable under Debug only, for the AI work later (so nothing is dead
        code, `CLAUDE.md` Code quality 5).
  - [ ] Nothing loads a model during a trip. Invariants W1–W6 and their tests unchanged.

## Medium-high

### F16. [ ] Share my arrival
"nice. medium-high prio." From the trip sheet's ⋯ (and the widget, F10): the share sheet with one line, "On the
866, at Symonds St about 8:22 · Nodstop".
- **Done when:** text only, never a position or a link; nothing is sent automatically; the line comes from a pure,
  tested function, and the time is the ETA the sheet shows.

### F22 + F23. [ ] Missed a connection or a stop: get me back on track
"Seems like a nice feature for sleepers like me." Bundled.
- **Missed a connection:** you're still at the change stop after your planned bus left (the timetable, or live when
  F13 is there), or you're moving with a different bus. Nodstop says "Missed the NX2. Next 8:12, or the 82 at 8:05"
  and offers **Use this**, which changes the rest of the trip.
- **Missed a stop:** after a "You passed your stop" ring: "Get off at Akoranga, walk back 9 min, or the 866 back
  at 8:04", tap for the map.
- **Done when:**
  - [ ] Detection is pure and tested: on time; the bus left without you; you're on a different bus; no timetable.
  - [ ] Re-planning uses the existing planner (`Planner`), one way to plan.
  - [ ] Changing the rest of the trip is an ordinary leg update: any alarm it changes may only get earlier
        (property test), and a stop still ahead is never dropped silently.

### F28. [ ] Learn a trip by riding it
"we have this no? But yes, allow this. Medium to high." Not yet: only the L0 recording spike exists. This is
`docs/LEARN.md` L0–L5, and Tae has now said go.
- **Done when:** LEARN.md's milestones, in order, each its own PR. Next: L0's report from one recorded ride.

### F6 (+F5). [ ] "Wake me 1 stop before", and the circle by default
"that'd be nice. If we can know. this beats 5." And for 5: "wake when inside the radius, not time."
- Today the alarm is timed (about 45 s before the stop), and your own circle rings on entry if you've set one.
  Tae wants place, not time, to be what he thinks in.
- **Done when:**
  - [ ] A route made by Plan a trip knows its bus's stops, so a stop can be set to **1 stop before** (or 2): it
        rings as the bus leaves the stop before yours. This becomes the default where the stops are known.
  - [ ] Otherwise the circle is the default way to say when (shown solid, set on the map), not the timing.
  - [ ] Whichever setting he picks, the timed estimate still applies as well: **whichever comes first** (rule 4).
        Property test: never later than the timed alarm alone.
  - [ ] The stop's line says it plainly: "Wakes you 1 stop before (Akoranga)".

### F9. [ ] Start mid-ride knowing exactly where you are; start by itself
"if possible. I thought we had this?"
- What exists: starting mid-ride picks the right stop by GPS ("Start anywhere", DESIGN §2.1). Nothing starts by
  itself yet.
- **Done when:**
  - [ ] **Mid-ride:** on a route that knows its buses, a start on a moving bus uses that bus's stop sequence to
        place you ("On the 866, 4 stops to Symonds St"), on top of the GPS pick. Engine tests: start between each
        pair of stops; on the wrong bus. Never skips a stop still ahead (`SkipPropertyTest`).
  - [ ] **By itself (opt-in):** once Nodstop knows you're on a bus on a known route at a usual time, it starts the
        trip and buzzes once: "To work is on". It uses activity recognition, not always-on GPS, so it waits on
        LEARN.md L0's numbers. It only ever starts a trip, never stops one or changes an alarm.

### F34. [ ] First run in under a minute
"less than 1 minute." From install to the first armed trip.
- **Done when:**
  - [ ] Timed in the PR, by counting screens and taps: home and work by address, then the permissions in one
        flow, each with one plain line why (no "DND", "failsafe"), Samsung's steps only on Samsung.
  - [ ] Anything that can wait until the first ride does.
  - [ ] Screenshot tests for each step.

### F37. [ ] Other regions (after a decent MVP, not yet)
"Up there in medium to high… after we have a decent MVP." Wellington (Metlink) and Christchurch (Metro), each with
its own kind welcome joke (Product direction). Don't start until Tae says the MVP is there.

## Medium

- [ ] **F13. Your bus, live, from AT.** "when AT server isn't down": live when it's up, the timetable and GPS when it
  isn't, and it never makes an alarm later (rule 6). **Waiting on Tae:** a free AT API key, entered in Settings,
  kept on the phone, never in the repo. Steps, each its own PR:
  1. Settings → Live buses: paste the key, and **Check** says whether AT accepts it. CI works without a key; a key
     never goes in the repo, CI or a log.
  2. While you wait: the coming bus on the map, and "871 · 3 min away (live)" instead of the timetable.
  3. On the bus: match you to the vehicle that moves with you for about a minute; AT's predicted arrival beside ours.
  4. The alarm may use AT's arrival only to ring earlier, with a test proving it never rings later.
- [ ] **F19. Doze at the stop.** Rings (the one ring path) when your bus is **about 2 min away**, so you can stand
  and hail it. Live with F13; without it, the timetable minus 2 min, labelled "timetable". With both, the earlier.
- [ ] **F20. Late or cancelled bus alerts**, with the next option, from AT's alerts and trip updates (needs F13's
  key). Notifications only; never changes an alarm.
- [ ] **F17. Battery saver.** Slower GPS far from your stop, back to 1–2 s near it. A slower rate may only make the
  failsafe fire earlier; the property tests run with it. Battery per trip measured on the A17.
- [ ] **F15. Arrival time at the destination.** "should be simple." "Work about 8:22" on the sheet, from the
  timetable for the buses still to come.
- [ ] **F27. Departures board** for any stop, like AT's: the next buses, timetable times (live with F13).
- [ ] **F26. More saved places** (gym, a friend's), "if clean": only if it reuses Home and work's code as one list
  of places, not a second mechanism.
- [ ] **F8. Suggest the right ride from use.** Not a morning nudge: as he uses it more, Now preselects the route
  *and* the bus he usually takes at this time ("To work · 866 7:43").
- [ ] **F4. Stop the alarm from the earbuds.** "would like to have. actually pretty nice." A press or squeeze on
  the earbuds (media button) counts as "I'm awake", held like the volume key so a brush can't stop it.
- [ ] **F38. Other languages** (yes; accessibility not yet: keep the app intuitive). Which languages first: ask Tae.

## Low

- [ ] **F1. Ride report**: how many seconds before each stop it rang.
- [ ] **F2. Ready check at Start** (later): earbuds battery, volume, Mute access.
- [ ] **F11. Voice** ("Hey Google, wake me at…"), not at the bus stop.
- [ ] **F12. Samsung Modes and Routines** start the trip, if Samsung allows it.
- [ ] **F18. Offline map** (low-medium).
- [ ] **F21. Transfer guide** as text in the trip notification ("Walk to Stop C, 2 min · NX2 in 6 min"), not a
  notification of its own. Maybe.
- [ ] **F31. Weather at your stop** on "You're awake", if a free source fits: Open-Meteo or MET Norway need no key.
  Only the stop's rough area (rounded to about 1 km) may be sent, like map tiles (DESIGN §5 privacy).
- [ ] **F25. "Where to?" on Now** for a one-off trip from one search. Tae hasn't ranked it yet.

## Not doing (Tae, 2026-11-23)
- F7 re-doze check ("nah"), F14 stop countdown ("let me sleep"), F24 "Got everything?", F32 calendar on wake.
- F36 other phone brands: Samsung only for now.
- An accessibility pass: not yet; make the app intuitive instead.

## Product directions (proposed 2026-11-23, Tae to pick)
Ideas in the spirit of the Pūkeko alarm: what makes Nodstop feel made in Auckland, for sleepers.
- **P1. Native birds as alarms:** tūī for morning rides, ruru (morepork) for the evening, from real recordings (his,
  or DOC's if the licence allows). Picked by time of day by default.
- **P2. "Tag off your HOP card"** at the get-off nudge and on "You're awake" when it's your last stop, so a sleepy
  rider doesn't pay the default fare. And "Cheers, driver".
- **P3. "Wave it down":** Doze at the stop (F19) wakes you with "Your 866 is 2 min away: wave it down".
- **P4. A little te reo Māori:** "Mōrena" in the morning, "Kia ora" otherwise, "Ka kite" when you get off. Te reo as
  the first extra language (F38).
- **P5. The sleep stats card:** "I slept 14 h on AT buses in November" as a shareable image (F30). No map, no
  stops. The thing people post, and the way he posts about Nodstop.
- **P6. The privacy promise as the brand:** "Your location never leaves your phone" on the first screen, the Play
  listing and the post.
- **P7. Kind Auckland humour, rarely:** a cheeky line on "You're awake" now and then ("Four seasons in one day:
  jacket?"), the way each region gets its welcome joke. Never on a ring that went wrong.

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
