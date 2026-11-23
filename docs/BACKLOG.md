# Backlog

Everything Tae has asked for that isn't built yet, newest asks added as he makes them. `docs/PLAN.md` holds
the runs that are planned in detail. This list is the queue those runs come from. Each item says what he asked
for, what done looks like, and anything that blocks it. Tick an item when its PR merges, and move it to Done
at the bottom.

## Product direction (2026-11-23)
- **Auckland and AT only, for now.** Tailor everything to Auckland Transport: AT's timetable, AT's live data,
  Auckland places. Then add the next region, and so on.
- **Post about it** once it's solid for Auckland.
- **A map of covered regions**, at the end of Settings (or an About page), that grows as regions are added.
- **Each region gets a small, kind welcome joke** when it's added. A little cheeky is fine, such as the
  NZ–Australia "brain drain" jokes. Kind, never mean.

## Next up
1. [x] **Walking along real footpaths, not straight lines.** (built: see Done once merged)
   - He asked "Does it know road walk paths? If not, add it. Make destination estimation accurate." It suggested
     Karangahape Rd (Rainbow Bridge) where he always gets off at Symonds Street Cemetery.
   - Plan: fetch OpenStreetMap's footpaths for a small area around where you start and where you're going.
     Overpass sends only that area, like the map tiles already do. Then find the shortest walk on the phone.
     Cache it, and fall back to the straight line × 1.25 offline.
   - Done when the walk to and from each stop is the walking-path distance, and the planner picks the stop
     with the shorter real walk.
2. [ ] **The alarm and "min left" disagree.** "It says 5 min left but the alarm is in 1 minute."
   - Cause: "min left" is the typical time to the stop. The alarm is deliberately earlier: the fastest past
     ride, the 45 s lead, and your circle's edge.
   - Done when the trip screen shows both, labelled, e.g. "Alarm 7:58 (your circle) · at the stop ~8:02".
     Then use AT's live arrival time for your stop when it's known (item 3).
3. [ ] **Your bus, live, from AT.** Show the coming bus for this trip on the map, with AT's predicted
   arrival at your stop. That makes the trip measurable.
   - Needs an AT API key, which is free from AT's developer portal. He enters it in Settings, and it's kept on
     the phone, never in the repo.
   - Rule 6: live data may only make an alarm earlier. GPS stays the fallback.
4. [ ] **Alarm sounds.**
   - A range of built-in sounds, plus your own mp3 or wav file. The file is picked with the system file
     picker and copied into the app.
   - Maybe region sounds, such as a crying pūkeko.
   - The ring still goes only through `RingService` (rule 1). The earbuds-and-vibration rules stay, and a file
     that can't play falls back to the default sound.
5. [ ] **Say when a trip starts past a stop.** For example: "Starting at Rainbow Bridge: you're past Smales Farm.
   Wrong? Start at Smales Farm." So a skipped stop is never silent (see Done: the missed ring).

## Later
- Learning a trip by riding it (Track A, `docs/LEARN.md`), worked on only when he says so.
- The time chip remembers Leave at / Arrive by per route ("To work" arrive by 8:30).

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
