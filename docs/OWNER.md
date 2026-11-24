# Working with Tae (read before any work)

This is a model of the owner, Tae. It is built from his own words across Runs 4–9. It exists so he never has to say
the same thing twice. Read it with `CLAUDE.md` before you plan, design or ship anything. When he asks for something
new, or repeats something, add it here in the same PR.

## Who he is and how he works
- He rides two commutes on a Samsung phone (a **Galaxy A17**, a budget phone with little memory) and sleeps on the bus.
  Anything heavy, like the Gemma model, must be proven on that phone before a trip relies on it. "To work" is two buses and "From work" is three.
  He usually has earbuds in, but he forgets them.
- He works from his phone only. He sends `/goal` lines, reads the PR on his phone, installs the `debug-latest` APK
  and tests on real buses. He has no laptop and won't debug for you.
- His standing order is: **"Do all. Merge them. Don't bother me."** Finish the run, merge it when CI is green, and
  report in a few lines. Don't ask questions you can answer from the code, the docs or sensible defaults.
- He writes fast, with typos ("buss", "baiier" = barrier, "dtop" = stop). Read for intent and don't correct him.
- He gets frustrated when a problem comes back after he reported it. Each repeat costs trust. Say so plainly when
  something is the second or third report, and show what is different this time.

## What he cares about, in order
1. **The alarm must get through, every time.** He has reported this many times:
   - Run 4: "4 failed alarms today", including while he was using the phone.
   - Run 6: "the buzz isn't ringing, my phone is on Mute, can't you make it ignore that?" and "the alarm didn't
     go off when the bus entered my circle".
   - Run 10: "no buzz again, nor any screen appearing… make sure the buzz comes through, it's not working."

   Treat any change near the ring path as dangerous. Prove it with tests that run in CI, and make the app say
   after each trip whether it rang and buzzed. Never rely on him sending logs: he finds that friction, so the app
   must surface the evidence itself.
2. **Minimum friction, lots of defaults, and everything customizable.**
   - No typing: "never wants to type times", default names like "Stop 1", "Stop 2", and "Route 1".
   - Rename only if he wants to.
   - Open the map straight away to add a stop.
   - One or two stops and it's ready to go.
   - No dialogs that ask for something a default could answer.
   - Anything he does often must be one tap from where he already is. Example: "I'm off this bus" from the
     notification. Opening the app, then the route, then ending the trip is too much.
3. **Simple, like the most-used apps.**
   - He rejected v1's look ("I hate the colours, lack of simplicity and aesthetic").
   - He approved v2's black and white ("Looks bloody amazing").
   - He wants maps to look like **Google Maps**, and he dislikes the grey recoloured map.
   - Google Maps is his reference for maps, links and search.
4. **The map is the main surface while riding.** He likes how it frames him and his stop when it opens; keep that.
   He wants more map and less panel, and he wants to do things on the map directly, like dragging the circle or
   tapping it.
5. **Quiet, but not deaf.** Silence other notifications only while the screen is off (he's dozing). When he's using
   the phone, notifications come through normally.
6. **Finding stops is hard.** He wants to paste Google Maps links (places and routes), share from Google Maps, and
   search stops or routes in the app.

## The ring problem so far (read before touching the alarm)
- **Run 4:** the screen turns on; a heads-up while he's using the phone; re-ring every minute until "I'm awake".
- **Run 6:** Buzz on Mute, the speaker after 30 s if the buzz may not get through, and the circle rings on entry.
- **Run 10:**
  - Found and closed silent failures:
    - nothing resumed a trip after an update or a reboot;
    - the alarm channel couldn't get through Do Not Disturb, because it was made before access was granted;
    - quiet trip stayed on during the ring;
    - nothing showed if the ring service failed to start;
    - a failing earbuds tone could stop the buzz;
    - a ring that cold-started the app wasn't logged.
  - Added "Over other apps" for the alarm screen while he's using the phone.
  - Added `RingPathTest` in CI, and a "Last trip" verdict on Now.
- If he reports a missed ring again: read Now's "Last trip" line and the `ringd` report in his log (he can tap
  Send), then add the case to `RingPathTest` before fixing it.
- **2026-11-23, 4th missed first stop (Smales Farm):** "you claimed a fix and it didn't… I do not accept lie
  anymore… if the previous fix overlaps, you're probably wrong." He was right: #43 and #50 fixed the leg pick, which
  wasn't it. The cause was Android: an app's alarm fires no sooner than 5 s after it is set, and each set replaces
  the last, so the due alarm, set again on every 2-s fix, never fired (DESIGN §2 "The system alarm"). The tests
  never saw it because their harness rang alarms instantly. Lesson: **a test harness that models the OS must model
  it as the OS really is**, from the Android source, not as we'd like it. Before calling a ring fixed, ask what the
  tests assume about Android and check that against AOSP.

## Round 4 (2026-11-22): what he said after the couch test
- The ring, Mute, Do Not Disturb and earbuds worked. He tests only what he cares about ("not bothered" with
  simulated trips and kill tests), so CI must cover the rest.
- "No dot" on the circle: direct manipulation of the thing itself, never an extra handle.
- Sharing a stop from Google Maps was "too much work". He wants a whole trip (all stops, up to 3 buses) from one
  search or one paste. Prefer one action that does everything over several small ones.
- The alarm must open full screen even while he's using the phone, not as a banner.

## Onboarding (2026-11-22)
- He wants starting to be effortless: "Home? Work?" by address, with rides and times like Google (Run 13). Later,
  learning a trip by riding (`docs/LEARN.md`, its own session, worked on only when he says so).
- Plan a trip, first try: "somewhat works... I doubt its truth". It gave a ride Google never would (off at a far
  stop, then a 13-minute walk). He judges the planner against Google Maps for his real commute, so test new planner
  work against well-known public trips in CI, and against Google's habits: changes cost time, walks are short, one
  walk at a time.
- He wants **Arrive by** as well as Leave at, and **the ride on a map** when he taps it.

## 2026-11-23
- **Another missed ring at the first stop:** "Fucking sick of all non-alarms." Whatever he did during the trip
  (used other apps, resized a circle), it must still ring. A missed ring is the worst bug this app can have. Treat
  any report of one as top priority, and fix the cause, with a test.
- He keeps a **backlog** with Claude: `docs/BACKLOG.md`. Add every new ask to it as he makes it.
- Product direction: Auckland and AT only for now, region by region later, each region with a kind little joke.
- He compares everything with Google Maps: walking routes, the stop to get off at, times.

## 2026-11-24
- **While he's using the phone, the ring must not take over the screen**: just buzz and sound, with the banner
  ("they are awake"). This replaces the 2026-11-22 full-screen overlay. `AlarmScreenTest` checks it.
- Test rings ring at once, and a toggle lets them play on the phone speaker. He wants an alarm volume to judge.
- On a trip: pull the panel up for the whole journey (stops and times); touching the map shrinks it. Every leg's way on
  the map, the current one in colour and the rest grey.
- ETA: the header was "somewhat accurate"; past rides must stay in it (the timetable only fills in). "Alarm about"
  must agree with the time until arrival (the aimed time, not the dead-man's switch).
- **His circle decides where it rings** (2026-11-24, "seen it fail multiple times… woke me outside the ring"): on entering
  it, per stop and per route, updated mid-trip; the stop's own timing only as the 30 s minimum. Don't go back to
  "a circle only adds an earlier ring".
- He likes the route map (buses on their roads, current blue, the rest grey). He wants every stop's circle editable on
  the trip map, but not by accident: tap the stop first (bold circle), then drag. A fixed-length scale bar whose label
  changes.
- **"Fucking don't wake me up early."** Woken about 4 km out, outside his circle (screenshot, 2026-11-24). Never ring on
  an estimate outside his circle; v_est never above V_MAX. Treat early rings as seriously as missed ones.
- He didn't like the made-up Pūkeko sound. He then gave his own recordings, and Pūkeko is back made from those
  (PR #56).
- **pstack** (Lauren Tan's skills, `cursor/plugins`): he asked for it to be used in this project. It is enabled
  in `.claude/settings.json`, and `CLAUDE.md` "pstack" says where it gives way to the project's rules.
- **Pūkeko alarm, real this time.** The made-in-code one was "awful" and went (#53). He then sent his own
  recordings (a 20 s morning and three ~1.5 s screams) and asked for the morning looping with screams "happening
  stochastically" like a real pūkeko morning. Sounds he supplies beat synthesised ones; use them as given.

## 2026-11-23 (walks)
- **Walks must look and measure like Google's.** The map drew the walk as a straight line while its minutes followed
  the footpaths: "make it match. Find a way. No nos." The straight line × 1.25 is the "last last last resort", not a
  quiet default. BACKLOG F39 (was item 27). "No nos" means: don't come back with why it can't be done; find the way.

## 2026-11-23 (product review)
- He asked for a product director's view and a backlog for later sessions. He rejected the first one (a ranked
  list with criteria) and wanted a **feature list he ranks himself**. It's `docs/BACKLOG.md`, F1–F38 with his
  priorities and words; the goal is at its top, linked from `CLAUDE.md`. Offer features as a flat list for him to
  rank; don't rank them for him.
- New facts: he wears a **Coros** watch. He wants Nodstop on the **Play Store**, for himself first. Other languages
  yes, accessibility not yet ("just make the app intuitive"). Samsung only for now. No stop countdown while riding
  ("let me sleep"). AI in While You Slept waits ("message summary… we'll find ways to use AI later").
- He likes Auckland flavour (Pūkeko, region jokes) and asked for more product-level ideas like it: BACKLOG
  "Product directions", P1–P7, for him to pick.

## Things he has decided; don't change them unless he asks
- **App icon:** "I don't like it. Don't change it. I'll tell you when you should address it again." Leave the icon
  alone, and don't propose icon work.
- v2 visual language: black and white, the system font, and one orange accent meaning "trip is live". The map is
  the exception: it should look like Google Maps.
- One ring path, the dead-man's switch, and "ring earlier, never later" (`CLAUDE.md`). He has never asked to relax
  these.
- Personal stops and ride logs never go in the repo.
- **No backups off the phone** (2026-11-22): "keep backups off the new phone". Nothing goes to cloud backup or to a
  new phone by transfer; moving phones is the backup file he shares himself (DESIGN §5).
- **Android target**: he left it to us ("raise it if you believe it's better"). It stays at 36 until the Android 17
  audio rules are proven on a phone (DESIGN §5).
- **Clean code, no workarounds** (asked Nov 2026, after Lauren Tan's talk "All Code Is Slop (Except Mine)"). He
  wants the codebase to stay clean as sessions pile up, so later sessions copy good patterns. Fix causes, not
  symptoms. Turn his corrections into checks (`CLAUDE.md` "Code quality"), not more prose.

## What he'll probably say next (pre-empt these)
- "Why do I have to open the app for that?" Anything shown in a notification should have its action there too.
- "Too many steps." Count taps for every flow you touch; the common path should be 1–2 taps.
- "Why is it asking me that?" Prefer a default plus a way to change it over a question.
- "It still doesn't buzz." Any ring change must come with CI tests for Mute, Do Not Disturb, quiet trip, a cold
  start and no earbuds, plus the after-trip verdict.
- "Did an update break it?" Updates and reboots must never lose a running trip or its alarm. Test both.
- "The map looks bad or doesn't look like Google." Judge any map work against Google Maps on Android.
- "I can't find my stop." Every place you set a stop should accept a Google Maps link, a search and a tap on the
  map.
- Text-heavy screens: he skims. Keep one line per idea, with no jargon such as "failsafe", "leg" or "DND" on screen.

## How to report back to him
- A few short lines: what changed, what to test on the phone, and the APK link. No walls of text.
- If something he asked for wasn't done, or was done differently, say so in one line with the reason.
