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
1. [ ] **Your bus, live, from AT.** Show the coming bus for this trip on the map, with AT's predicted
   arrival at your stop. That makes the trip measurable.
   - **Waiting on Tae:** an AT API key, which is free from AT's developer portal. He enters it in Settings, and it's kept on
     the phone, never in the repo.
   - Rule 6: live data may only make an alarm earlier. GPS stays the fallback.

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
- [x] **Walking along real footpaths (2026-11-23).** OpenStreetMap's paths around each end, by Overpass, cached
  30 days. Real walks near Auckland City Hospital were 1.4–2× the straight line.
- [x] **The alarm and "min left" agree.** The sheet shows both, labelled: "Alarm about 7:58 at your 400 m circle ·
  stop ~8:02". Live AT arrival times come with item 1.
- [x] **Alarm sounds.** Beeps, Chime, Rising, Pūkeko, or your own mp3/wav/m4a (the first 20 s loop). The beeps
  if your file can't play. "Try it" is a real test ring.
- [x] **Say when a trip starts past a stop.** "Started past Smales Farm", with **Start at Smales Farm**.
