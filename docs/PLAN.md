# Build plan: While You Slept on Nodstop

This file is the work queue for cloud sessions. One **run** = one cloud session = one PR.
The spec is `docs/DESIGN.md` (Nodstop v0.2: §1–§9; While You Slept: §10). Where they disagree, DESIGN wins.
Nodstop v0.2 is already on `main` (alarm engine, trips, learning, map, wake zones), so the runs below
only add While You Slept on top of it.

## How big

About 6k lines of Kotlin including tests (estimate), in 3 runs and 3 phone-test checkpoints.
Coding takes a few hours per run. The calendar is set by your bus rides: about 3–4 weeks if you ride
most weekdays.

| Run | Milestones | Size (estimate) | You do after |
| --- | --- | --- | --- |
| 1 | B1–B4: urgent rings in the engine, `:brief` digest, capture, alarm-screen panel, DND | ~2k lines | **Gate B**: 5 rides with the digest; DND verdict |
| 2 | C1–C2: Gemma runtime, model import, Briefing lab, briefing pipeline | ~2k lines | **Decision C**: model speed on your phone; 3 rides |
| 3 | C3–C5: urgent wake from VIPs, tones, share-back, quality eval | ~1.5k lines | **Gate C**: 10 rides with the briefing |

## How to start a run (from the phone)

Start a **new** cloud session on this repo (environment "Bus Alarm") and send one line:

```
/goal Finish Run 1 in docs/PLAN.md and open its PR
```

Change the number for each run. Add "and merge it" if you want the session to merge once CI is green.
If the run needs a checkpoint you passed but haven't ticked here, add it:
`/goal Finish Run 2 in docs/PLAN.md and open its PR. Gate B passed.`

Between runs: review the PR (one commit per milestone), merge it, install
<https://github.com/tyforlife/wake-up-call/releases/download/debug-latest/app-debug.apk>
(check the build number on Home: the newest push on any branch wins), and work through the PR's
"Test on phone" list.

Why `/goal`, not `/loop`: a run is one bounded job with a clear finish line (its acceptance criteria and
a PR), and `/goal` keeps the session working until that holds. `/loop` repeats a prompt on a timer; it
can't wait for you to merge and ride a bus. Checkpoints are deliberately human: no command crosses them.

## Runbook (for the session)

1. Read `CLAUDE.md`, `docs/DESIGN.md` and this file.
2. **Preflight.** Confirm all of these, or stop and report without coding:
   - `git fetch origin main`, and your branch starts from the latest `main`. Other sessions merge
     often; work from an old `main` duplicates or breaks theirs. Rebase onto `main` again before
     opening the PR.
   - The previous run's milestone boxes are ticked on `main`.
   - The checkpoint before this run is ticked, or the prompt says it passed. If the prompt says so,
     tick it in the run's first commit.
   - `$ANDROID_HOME` exists, and `./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug`
     passes on `main` before you change anything (plus `:brief:test` once it exists).
3. **Each milestone, in order:**
   1. Implement it.
   2. Write its tests.
   3. Run `./gradlew :engine:test :brief:test`, then `:app:testDebugUnitTest :app:assembleDebug`.
   4. Tick the milestone's boxes here.
   5. Make one conventional commit, then push.
4. Keep `docs/DESIGN.md` in sync whenever behaviour or parameters change.
5. **Open the PR.** Sections, in order:
   - Summary
   - Milestones (one line each)
   - New dependencies and the reason for each
   - Trace changes, if any (and why)
   - **Test on phone**: the run's phone checks plus the APK link
6. **Merge only if the prompt says so,** and only with CI green on the PR's head commit.
7. **Finish with a Done report:** every acceptance criterion of the run, each with its evidence (test
   name, command output or file path), plus the CI result.
8. **Never:**
   - work around the network allowlist;
   - skip, weaken or disable a test;
   - add a dependency outside the allowlist below;
   - add a second way to ring;
   - tick a checkpoint yourself;
   - put personal stop coordinates, ride logs or message text in the repo.
   If a criterion can't be met without breaking `CLAUDE.md` rules, stop and say which one, and why.

## Dependency allowlist (pre-approved, with the reason CLAUDE.md rule 5 asks for)

| Dependency | Reason |
| --- | --- |
| Everything already in DESIGN §5 | Already approved |
| `kotlinx-coroutines-core` (`:brief` tests) and `kotlinx-serialization-json` (`:brief`, already in DESIGN §5) | Testing the suspend `Llm` call; parsing the model's JSON lines |
| `com.google.ai.edge.litertlm:litertlm-android` 0.16.1 (Run 2; the newest built with Kotlin 2.2.21, 0.17+ needs Kotlin 2.4) | Runs Gemma on the phone (DESIGN §10) |
| Roborazzi 1.79.0 (plugin + `roborazzi`, `roborazzi-compose`), Robolectric 4.18.1, JUnit 4 + vintage engine, Compose `ui-test-junit4` / `ui-test-manifest` (Run 4, U0; test-only, only with `-Pscreenshots`) | Renders screens to PNG on the JVM, so UI changes can be reviewed from a phone. Paparazzi was tried first: 2.0 alpha breaks AGP 8.13's resource merge, 1.3.5 can't render API 36. Robolectric's renderer comes from a host cloud sessions can't reach, so screenshots are recorded in CI only. |

## Where things go

```
brief/   new pure Kotlin/JVM module, package app.nodstop.brief (Run 1)
  src/main/kotlin/app/nodstop/brief/   CapturedMessage.kt Digest.kt Llm.kt PromptBuilder.kt BriefParser.kt
                                       Guardrails.kt Briefing.kt BatchPlan.kt ShareText.kt SampleChats.kt
  src/test/resources/eval/             synthetic conversations + expected facts (Run 3)
engine/  app.nodstop.engine: Input.Urgent, AlarmReason.URGENT (Run 1)
app/src/main/kotlin/app/nodstop/
  notify/  CaptureService MessageStore NotificationMapper DndController DndRestore   (Run 1)
  brief/   LiteRtLlm ModelManager BriefingCoordinator BriefingLabScreen             (Run 2)
  ui/      AlarmUi (the "While you slept" panel), ReadinessScreen rows, DebugScreen entry
  trip/    TripService: starts/stops capture, sends Urgent, hands the briefing to RingService/AlarmUi
```

`:brief` gets the same source-scan purity test as `:engine` (no Android, clocks, randomness, I/O).
A second source-scan test covers W3 and W6: the `notify` and `brief` packages use no networking APIs, and
`MessageStore`/`CapturedMessage` are never referenced from `data/TraceLogger`, `map/` or `tile/`.

---

## Run 1: While You Slept without AI

Prerequisite: none beyond a green `main`.

### B1 Engine: urgent rings
- [x] `Input.Urgent` and `AlarmReason.URGENT` per DESIGN §10, with trace kind `urgent` (replay-safe)
- [x] Ignored outside WAITING, RIDING and AWAKE, and after `MAX_URGENT_RINGS` (2) per trip (tests)
- [x] Acking an URGENT ring never counts as "awake for this stop", far or near; the stop alarm still rings (tests)
- [x] Property test: random trips with random `Urgent`/`Ack` inserted. Until either run rings for the stop, the trip is at every step ringing or scheduled no later than without the inserts; the stop ring is no later either.
- [x] Every trace in `engine/src/test/resources/traces/` still replays identically

### B2 `:brief` module and plain digest
- [x] New module `:brief` with the purity test; `CLAUDE.md` rule 3 mentions it
- [x] `Digest` per DESIGN §10, tested with a 64-message group, three one-to-one chats, VIP ordering, and non-chat notifications (counted, not read)

### B3 Capture and the alarm-screen panel
- [x] `CaptureService` stores messages only while a trip runs (W3, W4). Own app and non-chat notifications are counted only.
- [x] `MessageStore` is plain Kotlin, tested to clear on close and on Stop, and to keep the newest 200
- [x] W3/W6 source-scan test (see "Where things go")
- [x] The alarm screen shows the "While you slept" panel with the digest, or "Briefing unavailable" (W2)
- [x] Readiness row for notification access, with a button to its settings page

### B4 Do Not Disturb during trips (experimental, off by default)
- [x] A toggle on Readiness, plus the Do Not Disturb access request
- [x] `DndRestore` is a pure class. Tests: start then stop restores the old filter; a crash leaves a flag that restores it on next start.

**Test on phone (Run 1):**
- capture from your chat apps (WhatsApp, Messenger, Discord, SMS, whatever you use);
- message text appears only on the alarm screen;
- with DND on: a test ring in earbuds, plus a simulated trip's at-stop buzz.

- [x] **GATE B (Tae):** passed (owner, 2026-11-16). The DND verdict is not recorded yet, so it stays experimental and off by default.

---

## Run 2: Gemma on the phone

Prerequisite: Run 1 merged, Gate B ticked.

### C1 Runtime, model import, Briefing lab
- [x] `litertlm-android` added at an exact version. `LiteRtLlm` implements `Llm`.
- [x] `ModelManager`: import a `.litertlm` file through the system file picker into app storage; show its name and size; delete it
- [x] Debug → "Briefing lab" runs `SampleChats` through the real model. It shows load time, time to first token, total time and the output. With no model it says so, and nothing crashes.
- [x] W6 source-scan test still passes; the runtime adds no network use from our code

### C2 Briefing pipeline
- [x] `PromptBuilder`: golden tests; long chats trimmed to a budget, newest messages kept
- [x] `BriefParser`: tested with valid lines, junk, partial output and empty output; failures fall back per conversation
- [x] `Guardrails` (W5): tested with an invented time, an invented name, a changed number and a clean pass
- [x] `BatchPlan` is a pure class tested with time passed in:
  - a batch every 180 s or every 30 messages;
  - a final pass once the alarm is ≤ 3 min away;
  - a 60 s timeout per call;
  - no AI below 20% battery;
  - the model unloaded after the final pass
- [x] End-to-end test with the fake `Llm`: messages in, panel lines out. A model that never answers still gives the digest (W2).

**Test on phone (Run 2):**
- import Gemma 4 E2B (and Gemma 3 1B);
- run the Briefing lab;
- post the timings and any crash in a PR comment;
- do 3 rides with the briefing.

- [x] **DECISION C (Tae):** Gemma 4 E2B is the default model (owner, 2026-11-16). Recorded in DESIGN §10.

---

## Run 3: Urgent wake, tones, share-back

Prerequisite: Run 2 merged, Decision C recorded.

### C3 Urgent wake from important contacts
- [x] A list of VIP names in settings, typed in (no contacts permission)
- [x] Only VIP messages are classified. The prompt and parser are tested with the fake `Llm`. Non-VIP messages never cause `Urgent` (test).
- [x] An urgent message sends `Input.Urgent`; the ring goes through the single ring path; the panel shows that conversation first

### C4 Tones and share-back
- [x] Plain or tea tone. The tea rewrite is re-checked by `Guardrails`, and a failing line falls back to plain (tests).
- [x] A Share button per line sends that line only, plus the tagline, through the share sheet (`ShareText` tests)
- [x] Source-scan test: `ACTION_SEND` appears only in the share UI (nothing is sent automatically)

### C5 Quality eval
- [x] 20 synthetic conversations with the facts that must survive, in `EvalSet` (`:brief` main code, not test resources, so the lab can run them on the phone)
- [x] Briefing lab "Run eval" scores facts kept and facts invented on the real model. A JVM test checks the scoring with the fake `Llm`.

Tip (added after Run 3): Debug → **Add sample chats to this trip** fills a simulated trip with made-up
chats and an urgent "Daycare" message, so the checks below and Gate C's couch runs don't need anyone to
text you. Each ring writes a `wys` line with counts to the trip log; Gate C's "ready before the ring"
is the share of rings with `ready=true`.

**Test on phone (Run 3):**
- a friend marked as VIP sends an urgent message (it rings) and a non-urgent one (it doesn't);
- tea tone;
- share a line to a group;
- run the eval and post the score.

- [ ] **GATE C (Tae):** 10 rides with the briefing:
  - 0 missed stops;
  - briefing ready before the ring on at least 90% of rides;
  - at least 9 of 10 briefings correct;
  - battery use per trip noted

---

## UX round: Runs 4 and 5 (owner feedback, 2026-11-19)

Take this as its own UX session: plan first, then build. **Every screen change ships with before/after
screenshots in the PR** (rendered in CI, see U0), because there is no device in the cloud session.

### Feedback, and what the code says

| # | Owner said | What's actually going on | Milestone |
| --- | --- | --- | --- |
| 1 | "Don't lose data between updates" | Stores treat a file they can't read as empty, and the next save **overwrites** it (`RouteStore.load` returns `emptyList()`, `LearnedStore` `emptyMap()`). One schema mistake in an update wipes routes and learned rides. | U1 |
| 2 | "The slider feels awful; I mostly use the lower km range" | The wake-zone slider is linear 0–2,000 m, so the low range that's actually used is a sliver of the track. | U3 |
| 3 | "Use speed for the whole trip (weighted to recent) + past rides" | `v_est` uses the last 15 s, the fastest 15 s of the last 30 s, and past rides; nothing remembers the whole trip. | U4 |
| 4 | "Don't use the end point to improve the real stop: I end the trip when I'm woken at a good time" | The suggested-stop / learned-track logic assumes the trip ends where you get off. Trips ended by hand (or early) must never move or suggest a stop. | U4 |
| 5 | "The brief didn't show because I held I'm awake. Was it supposed to?" | Partly by design: the panel shows *while ringing*; after the hold, "You're awake" only appears if something was captured (and capture needs notification access). Nothing tells you this. | U5 |
| 6 | "A small island at the top, like Samsung's timer, to cancel easily" | The trip notification is a Live Update only on Android 16 and has no Stop action; the ring notification has "I'm awake" but isn't promoted. | U2 |
| 7 | "Is it turning the screen on at the stop? I've never seen I'm awake already on screen" | The ring relies on a full-screen intent (needs the full-screen permission) plus `setTurnScreenOn`; `RingService` also tries a direct `startActivity`, which Android often blocks from the background. There's no record of which path worked. | U0 diagnostics, U2 fix |
| 8 | "Can I turn the map, like Google Maps?" | Rotation is switched off in code (`isRotateGesturesEnabled = false`). | U6 |
| 9 | "Alarm even when I'm on my phone? Why didn't it work? Maybe when I adjust the circle?" | A wake zone saved on the map goes into the route, but a **running trip keeps the legs it started with**, so changing the circle mid-trip does nothing until the next trip. Ringing while you use the phone shows as a banner, not full screen, and with no earbuds it's vibration only. Needs the ride's trace to confirm. | U0, U2, U3 |
| 10 | "Can it move to the next leg automatically if I'm in the circle?" | After "I'm awake" the leg ends only after 45 s slow within 300 m of the stop. | U3 |
| 11 | "Make the UI better, and more importantly the UX. It looks so bad." | No design system: ad-hoc colours, sizes and spacing per screen; key info isn't glanceable. | U6 (Run 5) |

Two items were guesses where the note was unclear; check them in the PR: #9's "when I know my phone" is read
as "when I'm on my phone", and #10 is read as "after I'm awake, entering the stop's circle ends the leg".

## Run 4: Reliability you can feel

Prerequisite: none beyond a green `main`.

### U0 See what happened, and see the screens
- [x] Ring-delivery diagnostics in the trip log (`note`-free, kept in hidden exports): receiver fired, ring service started, full-screen permission state, alarm screen shown (y/n, time), screen was on/off, earbuds or vibration, "I'm awake" source (hold, volume, notification).
- [x] Screenshot tests (Roborazzi on Robolectric, JVM, no emulator), starting with the alarm screens; the other screens get theirs in U6 when they're made stateless. CI records them (`-Pscreenshots`) and attaches the PNGs to the `debug-latest` release, viewable on the phone.
- [x] Readiness shows the result of the last ring ("Last ring: screen turned on ✓ / shown as a banner ✗, why").

### U1 Never lose data on an update
- [x] A store that can't read its file keeps it (`routes.json.bad-<time>`), loads the last good backup, and never overwrites it with empty data.
- [x] Every save keeps the previous file as `.bak`; a schema version is stored; migration tests decode fixtures of every schema shipped so far (routes, learned, settings, active trip).
- [x] Debug → Export / Import all data (routes, stops, learned rides, settings) as one JSON file through the share sheet and file picker. No positions leave the phone unless you share the file.

### U2 The ring reaches you, screen on, and canceling is one tap
- [x] The screen turns on at the ring when the phone is locked: full-screen intent first; if the permission is missing, Readiness says so at the top of Home, not only on Readiness; a wake lock with screen-on as fallback; the diagnostic from U0 records which path worked.
- [x] While you're using the phone: the ring shows as a heads-up with **I'm awake** and keeps escalating; it never depends on the full-screen activity.
- [x] "Island": the trip notification becomes a promoted ongoing notification (Live Update / status-bar chip where the phone supports it, Samsung's Now Bar on One UI 8) with **Stop trip** and "Get off at X · 1.2 km"; the ring notification is promoted too, with **I'm awake**.
- [x] Tests for the notification builders' actions and texts (JVM, pure parts).

### U3 Wake zone that works the way you use it
- [x] Saving a zone (or any route change) **applies to the running trip** at once, through a new engine input (`UpdateLegs`) that can only make the alarm earlier or leave it; tested with the invariant suite.
- [x] Slider is non-linear: 50 m–2 km, most of the track under 1 km (e.g. square-root scale), with quick presets (Auto, 150, 250, 400, 700 m) and the value in big type; the circle follows live.
- [x] After "I'm awake", entering this stop's circle (or 150 m if none) at walking speed for 10 s ends the leg and moves to the next one; "rode past" protection keeps watching the previous stop for 2 minutes. Engine tests: two-leg and three-leg routes, early ack, ack inside the circle, still on the bus inside the circle.

### U2b Missed rings (owner report: "4 failed alarms today, even when I'm on my phone")
- [x] Re-ring until acknowledged: while ringing, the one alarm stays scheduled 60 s ahead (engine `RERING`, and `RingService` after a cold start), so a ring that dies rings again. Engine tests.
- [x] No model in memory at the ring: every pass loads and frees the model, no pass starts within 90 s of the alarm, the ring cancels model work. `BatchPlan` test.
- [x] Phone in use at the ring: full-strength vibration and earbuds at 70% from the first second (`RingPlan`, tested never weaker than the normal ladder).
- [x] Optional per-leg timing rating: a silent notification after "I'm awake" (Too early / Good / Too late), the end-of-trip card only asks about legs not already asked, and Readiness can turn both off.

**Test on phone (Run 4):** couch test of the lock-screen ring (screen must turn on), a ring while using
another app (banner with I'm awake), the island with Stop trip, changing the circle during a simulated
trip (the next ring respects it), an update over an existing install (routes and learned rides kept),
export then import.

## Run 5: Smarter estimate and a real redesign

Prerequisite: Run 4 merged.

### U4 Estimate and learning
- [x] Whole-trip speed: an exponentially weighted average over the trip (recent minutes count most) joins `v_est` as one more term in the max (it can only ring earlier), plus today's pace vs past rides for the display ETA. Invariant and scenario tests stay green; real traces regenerated with the reason in the PR.
- [x] Learning ignores how a trip ended: a ride counts only if GPS passed within 60 m of the pin, and "suggested stop" is never derived from where you stopped the trip, acked or got off. Remove the suggestion UI if nothing trustworthy is left to suggest.

### U5 After "I'm awake"
- [x] "You're awake" always appears after a hold on the alarm screen (with "Nothing new" or "Briefing unavailable: turn on While you slept" and a one-tap fix), then Done.
- [x] If you acked from the notification or volume key, a silent "What you missed (N chats)" notification (counts only, W3) reopens it until the messages are cleared.

### U6 Visual and UX redesign
- [x] A small design system: colour tokens (light and dark), type scale, spacing, cards, primary/secondary buttons, status chips; applied everywhere.
- [x] Home: one big card per route with **Start**; a running-trip card on top (next stop, distance, ETA, Stop); the rating card as one tap.
- [x] Trip: glanceable at arm's length: stop name, distance and ETA large, progress bar, "I'm off this bus".
- [x] Map: rotate and tilt like Google Maps (two-finger), compass button to reset north, follow mode with heading-up option; stop sheet with the new zone slider.
- [x] Route editor and Readiness: grouped, plain-language, one primary action per section; Readiness collapses to "All set" when green.
- [x] Screenshot tests updated; PR shows before/after for every screen.

**Test on phone (Run 5):** a week of rides: ETA closer on the slow city end, no stop suggestions from
trips you ended early; every screen readable at arm's length; map rotation.

Before/after screenshots of Run 5: `docs/screens/before/` and `docs/screens/after/` (the map's shot is its
stop sheet: MapLibre can't render on the JVM).

## Feedback round 2 (owner, 2026-11-19, after Run 5)

| # | Owner said | What the code says | Milestone |
| --- | --- | --- | --- |
| 1 | "The buzz isn't ringing. My phone is on Mute. Can't you make it ignore that?" | Without earbuds the ring is vibration only (`VibrationAttributes.USAGE_ALARM`). Android normally lets alarm-usage vibration through Mute, but nothing records whether it did on this Samsung, and the app never touches the ringer mode. | R1 |
| 2 | "I thought you silenced other notifications and only raised the important ones?" | That exists but is **off by default** and buried: Readiness → While you slept → "Do Not Disturb during trips" (experimental), and urgent wake needs notification access, important contacts and the model. | R2 |
| 3 | "The alarm didn't go off. I thought it rings when the bus enters my circle? Check more often near it?" | GPS already runs at 1–2 s for the whole trip, so checking more often near the circle won't help. The circle only rings **after the engine thinks you've boarded** (15 km/h over 20 s), and only from fixes that actually arrive. The failsafe aims at the stop, not the circle's edge, so after a GPS gap it can ring after you're already inside. Without the ride's log we can't tell which of these happened, or whether it rang silently (#1). | R0, R3 |
| 4 | "Don't show bus stops unrelated to this trip" | The map shows every pin of every route. | R4 |
| 5 | "Revamp the UI and UX properly, like a professional. All you did was change colours and move words. Use Claude's design tools." | True: Run 5 restyled the same screens and flows. No user research, no flows, no design review. | Run 7 (Gate D) |

## Run 6: The ring always gets through

Prerequisite: Run 5 merged. **Before coding, ask for the failed ride's log** if it isn't attached to the
prompt: Debug → Share (location hidden), plus Readiness's "Last ring" line. Use it to confirm which of #1/#3
happened, and say so in the PR. Add it to `engine/src/test/resources/traces/` as a replay test.

### R0 Know what happened
- [x] The ring diagnostic (`ringd`) also records: the ringer mode (normal / vibrate / mute), the Do Not Disturb filter, whether the vibrator reported vibrating, and whether earbuds were connected. "Last ring" says it in words: "buzzed ✓ (phone on Mute)" or "couldn't buzz ✗: Mute blocked vibration".
- [x] The trip log notes GPS gaps over 10 s (count and longest, `note`-free so it survives hidden exports) and whether the engine thought you'd boarded when you entered the circle.

### R1 Buzz on Mute
- [x] If the phone is on Mute or Do Not Disturb when a ring starts and Nodstop has Do Not Disturb access, switch to vibrate (and let alarms through) for the ring only, and put the setting back after "I'm awake". Still the one ring path: `RingService` does it, and the restore also runs on a cold start, like `DndController`'s.
- [x] Readiness: "Let the alarm buzz on Mute" becomes a **required** check (Do Not Disturb access), with one Fix button. Home's setup chip counts it.
- [x] If vibration still can't be confirmed (no access, or the vibrator refused) and no earbuds are connected, the speaker last resort turns on for that ring, whatever the setting, after 30 s. The PR must say this, because it's a behaviour change the owner asked for ("ignore Mute").
- [x] JVM tests for the decision (mode in → actions out, restore always), and the cold-start restore.

### R2 Quiet trip, clearly
- [x] "Quiet other notifications during trips" moves to the top of Readiness and to the first trip's start: one switch, plain words ("Alarms and your important contacts still get through"), asking for access in the same tap. Still off until the owner turns it on.
- [x] The trip notification says when it's on ("Quiet trip: 6 held").

### R3 The circle rings when the bus enters it
- [x] Inside your circle at bus speed rings, even if boarding was never detected (e.g. trip started already on the bus at a crawl). Walking speed inside it still doesn't, before boarding.
- [x] With a circle set, `wakeAt` and the failsafe aim at the circle's edge (`straight − radius`), not the stop, so a GPS gap can't carry you deep inside before it rings. Earlier only: invariant suite plus a test with a 60-s gap just before the circle.
- [x] A ride where fixes stop near the circle (Samsung batching with the screen off) still rings by the failsafe before the edge: scenario test.

### R4 Map shows this trip
- [x] While a trip runs, the map shows only that trip's stops, circles and learned route; with no trip, only the stops of the route you last used (a chip switches routes). Test in `MapModelTest`.

**Test on phone (Run 6):** phone on Mute, no earbuds: couch test rings and buzzes, and the mode is back to
Mute after "I'm awake"; a ride with a circle: rings on entry; the map during a trip shows only its stops.

## Run 7: UX revamp, designed first (Gate D)

Prerequisite: Run 6 merged. This run produces **designs, not code**. Its PR is docs-only.

- [x] **Research and audit.** The rider's real journeys, half-asleep and one-handed: set up a route; start at the stop; ride and doze; wake; get off; transfer; end; give feedback. For each, today's steps, taps and failure points, from the code and the owner's feedback. Written up in `docs/UX.md`.
- [x] **Information architecture.** What the app is for on each screen, in one sentence. Which screens go: e.g. Trip and Home merge into one "now" screen while riding; Readiness becomes a first-run setup plus a status chip; Debug hides. Navigation map.
- [x] **Design system** in Claude's **Design System** artifact type: tokens (light and dark, including the alarm screen's night palette), type, spacing, components with states (start, riding, ringing, awake, GPS lost, not ready). The owner can browse it on the phone.
  - https://claude.ai/artifact/CEjr1LwtbBKZzcXQeZEKdS (its files are also in `docs/design/system/`).
- [x] **Screens and flows** in Claude's **Design** artifact type: hi-fi mockups of every screen and state at phone size, plus the key flows as sequences (first run, start a trip, the ring and after, a transfer, set a circle on the map). Real copy, not lorem.
  - https://claude.ai/artifact/VoVev4CB3YYocztGCXF8Cb: 18 screens in four rows by flow, the design system installed. Rendered from `docs/design/mockups.html`; PNGs and flow strips in `docs/design/screens/`.
- [x] Links to both artifacts in the PR. The owner reviews and comments **in the artifacts**; the session answers there and revises.
- [x] **v2, from scratch** after the owner rejected v1's look: black and white, the system font, one button per screen, lists, map with a sheet, a clock-like alarm, one accent for "live". Same two artifact links, updated; `docs/design/` regenerated (`gen_mockups.py`, 13 screens).
- [x] **Gate D** (owner): approves the designs. Passed 2026-11-20 ("Looks bloody amazing"), on v2. Then Run 8 builds them screen by screen, each PR with the mockup next to its screenshot.

**Test on phone (Run 7):** none: review the artifacts on the phone instead.

## UI migration: Runs 8 and 9 (build the v2 design)

The approved design is the source of truth: `docs/design/screens/*.png` (13 screens), `docs/design/tokens.v2.json`
(every colour), `docs/design/system/` (type, spacing, radii, components, rules in its README). Where a mockup and
this plan disagree, the mockup wins; where the mockup is silent, follow the design system README.

**How every UI milestone is checked (no device in the cloud):**
- Each screen gets a Roborazzi screenshot test that records `app/src/test/snapshots/v2_<id>.png`, `<id>` as in
  `docs/design/screens.json`, at 390×844 dp (`qualifiers = "w390dp-h844dp-xxhdpi"`), from made-up data, light and
  (where the mockup is dark) dark.
- CI runs `python3 docs/design/compare.py` after the screenshots (after `pip install pillow`) and uploads
  `build/compare/*.png` (mockup left, app right) with the screenshots to `debug-latest`. Every UI PR shows the
  comparison images of the screens it touches, and `build/compare/index.md`'s count.
- The bar: same layout, hierarchy, copy, colours and spacing as the mockup at a glance. Pixel-exact is not the
  goal (fonts render differently); "looks like the mockup" is. Anything deliberately different is listed in the PR.

**What does not change:** behaviour. The engine, the alarm path (rule 1), the re-ring, buzz on Mute, quiet trip,
While you slept, learning, data stores and every test stay as they are. These runs move pixels and navigation, not
logic. Every existing JVM test must pass untouched (screenshot tests are replaced).

### Screen map (mockup → Compose → what it replaces)

| Mockup `id` | New composable | Replaces |
| --- | --- | --- |
| `setup` | `ui/v2/SetupScreen` | `ReadinessScreen` (checks list), Home's red cards |
| `now` | `ui/v2/NowIdle` | `HomeScreen` |
| `riding`, `riding-night`, `gps-lost`, `transfer` | `ui/v2/NowRiding` (map + `TripSheet`) | `TripScreen`, Home's trip card, `MapScreen` during a trip |
| `ring` | `ui/v2/AlarmRing` | `AlarmUi.AlarmScreen` |
| `awake` | `ui/v2/AlarmAwake` | `AlarmUi.AfterWakeScreen` |
| `routes` | `ui/v2/RoutesList` | Home's route cards |
| `route` | `ui/v2/RouteDetail` | `RouteEditorScreen` |
| `stop` | `ui/v2/StopPicker` (map + sheet) | `MapScreen`'s zone sheet, coordinate pasting |
| `settings` | `ui/v2/SettingsScreen` | `ReadinessScreen`'s settings sections |
| `lock` | `Notifications.trip` text and colour | the current trip notification copy |

## Run 8: Foundation, Now, the ring

Prerequisite: Gate D passed (above). Branch from the latest `main`.

### M1 Tokens, type and components
- [x] `ui/v2/Theme.kt`: `NodstopTheme` from `tokens.v2.json` exactly (light and dark), the system font
  (`FontFamily.Default`, Roboto on the phone), the type scale and spacing/radius/size tokens from
  `docs/design/system/tokens.json`. A JVM test reads `docs/design/tokens.v2.json` and fails if any colour in
  `Theme.kt` differs (so design and code can't drift).
- [x] `ui/v2/Components.kt`: `PrimaryButton`, `GhostButton`, `Pill`, `TextLink`, `ListRow`, `RadioRow`, `SwitchRow`,
  `Segmented`, `TripSheet`, `HoldToStop` (1 s hold, accent ring fills, haptic tick at the end; no tap or brush
  stops it), `TabBar` (live dot), and the outline icons from `gen_mockups.py` as `ImageVector`s (no icon library).
- [x] One screenshot test per component, light and dark.

### M2 Navigation shell
- [x] Three tabs (Now, Routes, Settings) and two overlays (Setup, Alarm), replacing the `Screen` enum's Home/Trip/
  Readiness/Map. Back from a tab goes to Now; Now's tab shows the live dot while a trip runs.
- [x] Debug moves behind a long-press on Settings' build line; nothing else links to it.
- [x] Quick Settings tiles and launcher shortcuts still start a route (open Now riding).

### M3 Now
- [x] `NowIdle` (`now`): "Good morning/afternoon/evening", "Where are you riding?", a `RadioRow` per route with the
  likely one selected, one Start. `RouteSuggest` (pure, `:app` JVM test): the route last started at a similar time
  of day (±2 h) on a weekday/weekend match, else the last started, else the first. A required check that's off shows
  as one red row above the list that opens Setup.
- [x] `NowRiding` (`riding`, `riding-night`, `gps-lost`, `transfer`): a full-bleed MapLibre map under one `TripSheet`.
  Map: OpenFreeMap's plain style (`positron`; confirm it on the phone, as the cloud session can't reach the tile
  host), recoloured at runtime to the tokens (`map-*`), dark at night; the route line in `fg`, the stop pin, the circle as
  a faint `fg` ring, you as the blue dot; only this trip's stops (R4). Recentre button; rotation and the compass
  stay. Sheet states: riding (min · km, "Alarm about 7:45"), waiting/transfer ("Off at X · next stop",
  "Waiting for bus 2"), GPS lost (figures muted with their age, the red line "GPS lost. Alarm at 7:44 at the latest."
  from the engine's failsafe time). "I'm off this bus" is the ghost button; Stop trip moves to the sheet's overflow
  (confirmed).
- [x] Screenshot tests: `v2_now`, `v2_riding` (light), `v2_riding-night` (dark), `v2_gps-lost`, `v2_transfer`. The map
  can't render on the JVM: the test draws the sheet over a flat `map-bg` box; the PR says so.

### M4 The ring and after
- [x] `AlarmRing` (`ring`): black in both themes, "Wake up" (light weight, 64 sp), "<stop> in about 45 s",
  `HoldToStop` low on the screen, "Hold for 1 second to stop / or hold volume down". The briefing is not on this
  screen any more (it's on the next). Volume-down hold, the notification button, `RingDiag` and the
  over-the-lock-screen behaviour stay exactly as they are.
- [x] `AlarmAwake` (`awake`): "Now" / "Get off at <stop>" (or "Stay on the bus" for an early ring, with why) /
  one line, then "While you slept" as plain rows (Urgent tag, Share this line), then Done. "What you missed" and
  W3 (cleared on Done) unchanged.
- [x] Screenshot tests `v2_ring`, `v2_awake` (+ the early-ring and nothing-new variants).

Deliberate differences from the mockups (in the PR): a ⋯ button on the riding map for Stop trip (the mockup
has nowhere for it); the transfer sheet's button reads "Skip this bus" (what it does) instead of "I'm on a
different bus"; Routes and Settings are interim v2 lists until Run 9.

**Test on phone (Run 8):** the Now screen starts the right route in the morning and the evening; a real ride shows
the map and sheet, readable at arm's length; the couch test rings with the new alarm and the hold works with a
groggy thumb; night mode on a dark bus.

## Run 9: Routes, stops, setup, settings, cleanup

Prerequisite: Run 8 merged.

### M5 Routes and stops
- [ ] `RoutesList` (`routes`): one row per route ("2 buses · 12 rides"), a problem as a red second line, + to add.
- [ ] `RouteDetail` (`route`): the name (tap to rename), its stops on a line (filled = alarm, dashed = no alarm, red
  = stop not set), Add a bus, one primary button for the next thing it needs ("Set Hill Ave"), Delete in the
  overflow (confirmed). Tapping a stop opens `StopPicker`.
- [ ] `StopPicker` (`stop`): the map moves under a fixed pin ("Drag the map to place your stop"), a search field that
  also takes a pasted Maps link or coordinates (`CoordParser`), the compass, a `Segmented` circle size (Auto, 150 m,
  250 m, 400 m; the 50 m–2 km slider stays one tap away as "More"), Save stop. "Use my location" sits in the search
  field's menu. The stop name comes from the field (typed once, at home).
- [ ] Screenshot tests `v2_routes`, `v2_route`, `v2_stop` (sheet over a flat map box).

### M6 Setup and settings
- [ ] `SetupScreen` (`setup`): the five required checks as rows (Notifications, Over the lock screen, Exact alarms,
  Buzz on Mute, Location while riding) with Allow pills; Continue enabled when all are allowed. Shown on first run
  and from Now's red row. Battery (unrestricted) and Samsung's sleeping apps become one more row on Samsung only.
  Optional checks (While you slept, live progress) move to Settings.
- [ ] `SettingsScreen` (`settings`): the five rows of the mockup; While you slept's extras (tea tone, GPU, backup
  and restore, Briefing lab) move to Debug. "Report a missed alarm" shares the location-hidden log (as Debug →
  Share does now) with the last ring's report in the message.
- [ ] Screenshot tests `v2_setup`, `v2_settings`.

### M7 Notifications, cleanup, docs
- [ ] The trip notification reads like `lock`: "<stop> in 6 min", "Alarm about 7:45", accent progress, Stop trip
  action kept (authenticated). The ring and rate notifications' copy matches the alarm screens.
- [ ] Delete the replaced screens (`HomeScreen`, `TripScreen`, `ReadinessScreen`, `RouteEditorScreen`, the old
  `MapScreen` UI, `AlarmUi`'s old composables, v1 `Design.kt`) and their old screenshot tests; nothing references them.
- [ ] `docs/DESIGN.md` §5 Screens rewritten for v2; `compare.py`'s index shows **13 of 13 screens built**.

**Test on phone (Run 9):** set a new stop by dragging the map at home; a full week of rides on v2 only; first run
from a fresh install (Setup) and an update over the old app (data kept, Setup only if something is missing).

Start them like the earlier runs:

```
/goal Finish Run 9 in docs/PLAN.md and merge its PR
```

## Later (not for automated runs yet)
These come after Gate C:
- privacy explanation and onboarding before the permission screens;
- Play declarations (notification access as well as §8's list);
- model delivery as a Play asset pack;
- localisation;
- an accessibility pass.
