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
   3. Run `./gradlew :engine:test :brief:test`, then `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
   4. Tick the milestone's boxes here.
   5. Make one conventional commit, then push.
4. Keep `docs/DESIGN.md` in sync whenever behaviour or parameters change.
5. **Review your own diff** (`git diff origin/main`) as a reviewer would, against `CLAUDE.md` "Code quality":
   - Does every fix remove a cause, or does it hide a symptom?
   - Did you copy a pattern that breaks the rules?
   - Is anything left dead?
   - Did a correction from Tae get a check, not just a sentence?
   Fix what you find before opening the PR.
6. **Open the PR.** Sections, in order:
   - Summary
   - Milestones (one line each)
   - New dependencies and the reason for each
   - Trace changes, if any (and why)
   - Escape hatches: any line raised in `escape-hatches.txt`, and why (omit if none)
   - Not fixed: causes you found but left alone, and why (omit if none)
   - **Test on phone**: the run's phone checks plus the APK link
7. **Merge only if the prompt says so,** and only with CI green on the PR's head commit.
8. **Finish with a Done report:** every acceptance criterion of the run, each with its evidence (test
   name, command output or file path), plus the CI result.
9. **Never:**
   - work around the network allowlist;
   - skip, weaken or disable a test, or loosen a check in `ArchitectureTest` or a `PurityTest` to get green;
   - raise a line in `escape-hatches.txt` without a reason in the PR;
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
- [x] `RoutesList` (`routes`): one row per route ("2 buses · 12 rides"), a problem as a red second line, + to add.
- [x] `RouteDetail` (`route`): the name (tap to rename), its stops on a line (filled = alarm, dashed = no alarm, red
  = stop not set), Add a bus, one primary button for the next thing it needs ("Set Hill Ave"), Delete in the
  overflow (confirmed). Tapping a stop opens `StopPicker`.
- [x] `StopPicker` (`stop`): the map moves under a fixed pin ("Drag the map to place your stop"), a search field that
  also takes a pasted Maps link or coordinates (`CoordParser`), the compass, a `Segmented` circle size (Auto, 150 m,
  250 m, 400 m; the 50 m–2 km slider stays one tap away as "More"), Save stop. "Use my location" sits in the search
  field's menu. The stop name comes from the field (typed once, at home).
- [x] Screenshot tests `v2_routes`, `v2_route`, `v2_stop` (sheet over a flat map box).

### M6 Setup and settings
- [x] `SetupScreen` (`setup`): the five required checks as rows (Notifications, Over the lock screen, Exact alarms,
  Buzz on Mute, Location while riding) with Allow pills; Continue enabled when all are allowed. Shown on first run
  and from Now's red row. Battery (unrestricted) and Samsung's sleeping apps become one more row on Samsung only.
  Optional checks (While you slept, live progress) move to Settings.
- [x] `SettingsScreen` (`settings`): the five rows of the mockup; While you slept's extras (tea tone, GPU, backup
  and restore, Briefing lab) move to Debug. "Report a missed alarm" shares the location-hidden log (as Debug →
  Share does now) with the last ring's report in the message.
- [x] Screenshot tests `v2_setup`, `v2_settings`.

### M7 Notifications, cleanup, docs
- [x] The trip notification reads like `lock`: "<stop> in 6 min", "Alarm about 7:45", accent progress, Stop trip
  action kept (authenticated). The ring and rate notifications' copy matches the alarm screens.
- [x] Delete the replaced screens (`HomeScreen`, `TripScreen`, `ReadinessScreen`, `RouteEditorScreen`, the old
  `MapScreen` UI, `AlarmUi`'s old composables, v1 `Design.kt`) and their old screenshot tests; nothing references them.
- [x] `docs/DESIGN.md` §5 Screens rewritten for v2; `compare.py`'s index shows **13 of 13 screens built**.

Deliberate differences from the mockups (in the PR):
- The stop picker's field reads "Paste a Maps link or coordinates". There's no place search: it would need a
  geocoding service, which is a new network dependency.
- The field has Back at its start and ⋯ at its end (Rename stop, alarm on or off, Forget learned rides, Remove
  this bus). "Use my location" is a round button on the map, as on the riding map, not inside the menu.
- The slider is "Other size" under the four sizes.
- Setup's Continue stays grey until every row is allowed, with "Not now" under it, so you're never stuck.
- On Samsung, Setup adds a sixth row, Keep running, and the three steps no API can check.
- A route's ⋯ menu also has Start from <a later stop>. It is the old Home's "Start from a later leg", which Run 8's
  Now left out.
- Settings shows an Allow row for Android 16's lock-screen progress only while it's off. Important contacts opens
  its own screen, with the chat notification access While you slept needs.

**Test on phone (Run 9):** set a new stop by dragging the map at home; a full week of rides on v2 only; first run
from a fresh install (Setup) and an update over the old app (data kept, Setup only if something is missing).

Start them like the earlier runs:

```
/goal Finish Run 13 in docs/PLAN.md and merge its PR
```

## Feedback round 3 (owner, 2026-11-20, after Run 9)

Read `docs/OWNER.md` first: it holds the owner's standing preferences, so he doesn't have to repeat them.

| # | Owner said | What the code says | Milestone |
| --- | --- | --- | --- |
| 1 | "No buzz again, nor any screen appearing… make sure the buzz comes through, it's not working. Perhaps this is lost in updates?" | Run 6 added Buzz on Mute, but several ways to fail silently remain. (a) Nothing resumes a trip after an app update or a reboot: no `MY_PACKAGE_REPLACED` or `BOOT_COMPLETED` receiver. (b) The alarm channel's `setBypassDnd` is only honoured if Do Not Disturb access existed when the channel was first created, which on this phone was before he allowed it. (c) Quiet trip's own Do Not Disturb stays on during the ring. (d) If `RingService` can't start, nothing is shown or felt. (e) While the phone is in use, Android shows only a banner, never the alarm screen. The app also never says whether the last trip actually rang. | A0, A1, A2 |
| 2 | "Let me press get off bus through the notification panel. There's only Stop trip." | The trip notification has one action, Stop trip. | A3 |
| 3 | "Quiet my trips only when the phone is off. When on, notifications still come through. When off, just don't buzz?" | Quiet trip is Do Not Disturb (alarms only) for the whole trip. | A4 |
| 4 | "Show the map straight away to add bus stops, one or two, and they're set to go. Default names like Stop 1 and 2, changeable. Less barrier. Minimum friction, lots of defaults that are customizable." | + asks for a name, then a route opens empty, then "Add a bus" asks for a stop name, then the map opens. | B1 |
| 5 | "I don't like how the map looks. Make it look more like Google Maps." | `positron`, recoloured to grey map tokens. | B2 |
| 6 | "Move the descriptive panel down so I can see more of the map. I like how the map frames me and my stop when it opens." | The trip sheet is always fully open. Framing: `MapModel.frame` (keep it). | B3 |
| 7 | "Let me change the circle on the map: tap it and a slider pops up, or drag its edge in or out. Per bus stop." | The circle size can only be set in Set a stop (segmented buttons and a slider). | B4 |
| 8 | "Can people copy Google Maps links to the route? Copy-paste a Google Maps route search, or search routes in the app. It's hard to find stops." | Only coordinates and long links with `@lat,lng` or `q=` parse. Short links, directions links and sharing from Google Maps don't work, and there's no search. | C1, C2, C3 |
| 9 | "Make a note of the changes I request and the kind of character I am, so I don't repeat myself." | Done in this PR: `docs/OWNER.md`, linked from `CLAUDE.md`. | — |

## Run 10: The alarm gets through, every time

Prerequisite: Run 9 merged. Don't ask the owner for logs (OWNER.md). If the prompt includes a report, use it, add
it as a replay test and say what it showed. Otherwise fix every known failure, and make the app say after each
trip what happened.

### A0 The app says whether it rang
- [x] After a trip, Now shows one line per stop that had an alarm:
  - "Rang at Harbour St · buzzed · screen on".
  - Or, in red, "Didn't ring at Harbour St" or "Rang at Harbour St, but couldn't buzz: phone on Mute", with the
    reason in plain words.
  - A red line comes with a one-tap **Send report**: the hidden-location log plus the ring report, through the
    share sheet.
  - The verdict comes from a pure `TripVerdict` over the trip log and ring reports, with JVM tests for each outcome.
- [x] The ring report also records:
  - the alarm channel's importance and whether it can bypass Do Not Disturb;
  - whether notifications are on for that channel;
  - "Display over other apps";
  - whether quiet trip's Do Not Disturb was on at the ring;
  - whether the process was cold-started by the alarm;
  - whether the alarm screen actually opened, and how long it took.

### A1 Close every silent failure
- [x] **Alarm channel health.**
  - At each app start, if the alarm channel can't bypass Do Not Disturb while access is granted, or its importance
    is below high, replace it with a fresh channel.
  - The new channel gets its own vibration pattern, alongside the app's vibrator. Changing channel settings needs
    a new channel ID.
  - Setup gets an "Alarm notifications" row when the channel is blocked or turned down.
- [x] **The ring lifts quiet trip.** When a ring starts, quiet trip's Do Not Disturb is restored to your own
  setting before anything else. It comes back after "I'm awake" only if the trip goes on and the screen is off (A4).
- [x] **Shown even if the service fails.**
  - `AlarmReceiver` posts the full-screen ring notification itself, first, then starts `RingService`, which takes
    over the same notification ID.
  - A service that can't start still leaves an alarm on screen that buzzes through its channel.
  - It is still the one ring path (receiver → service, rule 1). Update DESIGN §5 Components.
- [x] **Resume after an update or a reboot.**
  - Add `MY_PACKAGE_REPLACED` and `BOOT_COMPLETED` receivers.
  - They re-schedule the saved trip's alarm at `min(wakeAt, failsafeAt)`, or ring now if that time has passed,
    then restart `TripService`.
  - Test with a saved trip fixture.
- [x] **The alarm screen while you're using the phone.**
  - Setup gets a "Show over other apps" row (`SYSTEM_ALERT_WINDOW`; one-line reason in the PR, rule 5).
  - With it, the ring opens the alarm screen even while another app is in front, instead of only a banner.
    Without it, the banner stays, as now.

### A2 Ring-path tests in CI
- [x] Robolectric tests drive `AlarmReceiver.onReceive`, then check three things: a full-screen notification is
  posted on the alarm channel, vibration is requested with `USAGE_ALARM` (or the channel vibrates), and the mode
  changes are undone after "I'm awake".
- [x] The phone states covered: normal; Mute; Do Not Disturb priority; quiet trip on; no Do Not Disturb access; a
  cold start with no `TripService`; `RingService` failing to start; no earbuds; earbuds in.
- [x] A test for "update during a trip": a saved trip plus a `MY_PACKAGE_REPLACED` broadcast leads to the alarm
  being scheduled and the trip resumed.
- [x] These run in `:app:testDebugUnitTest`, so no future change can silently break the ring.

### A3 Get off from the notification
- [x] The trip notification gets **I'm off this bus** next to Stop trip. It sends `SkipLeg`; on the last bus it
  ends the trip. While waiting for the next bus, it reads **Skip this bus**.
- [x] It works without unlocking once this stop has rung, or once you're inside its circle. Before that it asks you
  to unlock, so a pocket can't skip your alarm.
- [x] JVM tests for which actions show, and whether each needs unlocking, in every phase.

### A4 Quiet only while the screen is off
- [x] During a trip with quiet trip on, Do Not Disturb (alarms only) is on while the screen is off and off while
  it's on. Notifications come through while you use the phone and stay silent while you doze.
- [x] `TripService` listens for screen on and off. The decision is pure, in `DndRestore`, and takes the screen
  state as an input.
- [x] A Do Not Disturb you set yourself is never touched, and your setting comes back when the trip ends.
- [x] The Settings subtitle becomes "While the screen is off", and the first-trip offer text matches.
- [x] JVM tests: screen off, then on, then off, then the trip ends; DND you set mid-trip; a crash mid-trip.

**Test on phone (Run 10):**
1. Couch test with the phone on Mute, no earbuds, quiet trip on and the screen off: the screen turns on, it
   buzzes, and Mute is back after "I'm awake".
2. The same couch test while using another app: the alarm screen opens (with "Show over other apps").
3. Install an update during a simulated trip: the trip carries on and still rings.
4. A real ride: after it, Now says whether it rang and buzzed.
5. Tap "I'm off this bus" in the notification.
6. During a trip with the screen on, a chat notification comes through; with the screen off, it doesn't buzz.

## Run 11: Map first, no friction

Prerequisite: Run 10 merged.

### B1 A new route in two taps
- [x] The + button on Routes (and Now when there are no routes) opens the map straight away to set Stop 1.
  - Save goes to the route, which has "Add stop 2" and Start.
  - A route with one set stop is ready.
  - No name dialogs anywhere.
- [x] Default names:
  - "Route 1" and "Route 2"; "Stop 1" and "Stop 2".
  - A stop chosen by tapping a bus-stop marker (C2) takes that stop's name instead. Say so in the PR; it's one
    line to revert if the owner wants plain "Stop N".
  - Tap a name to change it. A new route's name can also come from the time of day ("Morning", "Evening") if
    that reads better; decide and say so in the PR.
- [x] Count taps in the PR, before and after, for: a new route with two stops, and changing a circle.

### B2 The map looks like Google Maps
- [x] Base map: OpenFreeMap's `liberty` style, tuned to Google Maps' palette, replacing the grey recoloured
  `positron`:
  - grey-beige land, white streets, and yellow-orange main roads;
  - green parks and blue water;
  - place, POI and transit labels, with bus stops visible;
  - a dark variant at night, like Google's night map.
- [x] Markers:
  - you are Google's blue dot with a heading beam;
  - your stop is a red Google-style pin;
  - the learned route is Google's route blue.
- [x] The style is built in code. A JVM test checks the layer colours and that bus stops show. The cloud session
  can't load the tiles (network allowlist), so the owner checks the real look on the phone. Screenshot tests keep
  the flat map box.

### B3 More map, less panel
- [x] The riding sheet collapses to one line ("Harbour St · 6 min · alarm 7:45", with I'm off this bus) and drags
  up for the rest. It collapses by itself when you touch the map.
- [x] Opening the map still frames you and your stop: the owner likes this, so keep `MapModel.frame`.
- [x] Screenshot tests for the collapsed and open sheets.

### B4 Change the circle on the map, per stop
- [x] Tap inside a stop's circle: a small sheet with that stop's size (Auto, 150, 250, 400 m, plus the slider).
- [x] Drag the handle on the circle's edge in or out: the circle follows your finger and saves when you let go.
- [x] Both are per stop (the leg's `wakeRadiusM`), work on the riding map and in Set a stop, and apply to a running
  trip at once (`UpdateLegs`).
- [x] The circle only ever makes a ring earlier (DESIGN §2.6). Add an invariant test that shrinking it mid-trip
  never moves the alarm later than without a circle.
- [x] The math is pure and tested: screen drag to metres, clamped to 50 m–2 km, rounded like `ZoneScale`.

**Test on phone (Run 11):**
1. Make a new two-stop route from scratch, counting taps.
2. Compare the riding map with Google Maps, day and night.
3. Collapse and expand the sheet.
4. Resize a circle by dragging and by tapping, during a simulated trip; the next ring respects it.

## Run 12: Finding stops

Prerequisite: Run 11 merged.

### C1 Google Maps links and sharing
- [x] Any Google Maps link works wherever a stop is set:
  - place and pin links;
  - `maps.app.goo.gl` short links, resolved by following redirects, to Google hosts only (the link is sent to
    Google, nothing else; DESIGN §5 Permissions);
  - directions links, whose stops become the route's stops, named from the link. (Built: the start is dropped,
    because it's where you get on, not a stop you get off at; the waypoints and the destination are the stops.)
- [x] Nodstop appears in Google Maps' Share sheet: share a place, then pick "Add to To work" or "New route".
- [x] Parser tests with the real link shapes, using public landmarks only, never personal places.

### C2 Search Auckland Transport stops
- [x] Auckland Transport's public GTFS stops (name, stop number, position) are bundled into the APK at build time;
  CI downloads them, and search works offline.
  - This is a public dataset, not personal data.
  - The cloud session can't download it (network allowlist), so tests use a small fixture.
- [x] The stop field searches by name or number ("3353", "Smales Farm").
- [x] Bus-stop markers on the Set a stop map: tap one to choose it, with its name.

### C3 Route search (direct buses)
- [x] "To <stop>" from where you are: the Auckland Transport routes that serve a stop near you and the destination
  directly. Pick one to create the route with its stops.
  - The routes come from a compact route-to-stops index built from GTFS at build time.
  - Two-bus journeys come later: the owner can paste a Google Maps directions link for those (C1).
- [x] Live data never changes the alarm (rule 6): this only fills in stops.

**Test on phone (Run 12):**
1. Share a stop from Google Maps to Nodstop.
2. Paste a directions link.
3. Search "Smales Farm" and a stop number.
4. Set up "From work" (three buses) with no typing beyond the search.

## Feedback round 4 (owner, 2026-11-22, couch test after Run 12)

Couch test: the ring, Mute, Do Not Disturb and earbuds all **worked**. Done directly in one PR:
- [x] "Using another app when it rings: no ring screen, the banner pops up." The trick of opening the alarm screen
  from a hidden window doesn't work on his phone. The ring is now drawn as a full-screen layer over the app
  (`AlarmOverlay`), with hold to stop and volume down.
- [x] "Don't make the circle edge with a dot. No dot. Touch and drag the edge to resize it." Done (`EdgeDrag`):
  a touch within 28 dp of the edge grabs it, and any other touch moves the map.
- [x] "Sharing a stop from Google Maps is too much work. Search a trip, or paste a trip from Google Maps, so that
  the stops are all present." A Google Maps link carries only the start and the end, never where you change
  buses, so pasting alone can't give the stops. Nodstop now works the trip out from AT's route patterns: up to
  3 buses, every get-off stop. Routes → Find a trip takes a search or pasted directions (`TransitIndex.trips`).

## Run 13: Home and work, by address, with times (onboarding B)

Prerequisite: feedback round 4 merged. The owner (2026-11-22): "Do B first. By address. If timetable is relevant,
use now as the default and give a list of rides with estimated times, like Google does. Make it intuitive and
correct." Learning a trip by riding (onboarding A) is planned separately in `docs/LEARN.md`.

### The walkpath (what the owner does)
1. **First run, or Routes with no routes:** "Where's home?" Type an address (suggestions as you type), or tap
   "I'm home now". Then "Where's work?", the same way. That's all the typing there is.
2. **Pick your rides:** "To work, leaving now" shows a list like Google's. Each ride has:
   - the times, "7:42 → 8:20 · 38 min";
   - the buses, "866 › 70";
   - where to start, "Walk 4 min to Smales Farm Stop D, leaves 7:42".
   Tap one to see its timeline, then **Use this**. The same follows for "From work", leaving at 5 pm.
3. **Done:** To work and From work exist, with every stop where you get off, ready to start. Home and work are
   saved (on the phone only) and can be changed in Settings.
4. **Any time later:** Routes → **Plan a trip**.
   - From (default: your location; or home, work, an address or a stop). To: an address, a stop, home or work.
     There's a swap button.
   - A time chip: **Leave now**, or tap it to pick a departure time.
   - Results, a timeline, then Use this (save as a route) or **Start** (save and start the trip now).
   - Pasting Google Maps directions fills From and To.

### T1 The timetable, offline
- [x] `tools/gtfs_index.py` also writes the timetable:
  - patterns as **exact stop sequences** (one per distinct sequence);
  - each pattern's trips, stored as a departure time plus a shared per-stop time profile;
  - each trip's service;
  - the calendar (which services run on which dates, including exceptions).

  All times are in seconds after midnight; GTFS times past 24:00 are kept.
- [x] The APK grows by **under 5 MB**. CI prints the index size, and the fixture feed gains times and a calendar.
  CI still checks the script against its fixture.

### T2 The journey planner (RAPTOR)
- [x] `Planner.plan(index, from, to, departAt, date)` is pure Kotlin and tested on the fixture. It uses round-based
  RAPTOR:
  - up to 3 buses;
  - walking up to 800 m to the first stop and from the last stop, at 4.5 km/h;
  - changes within 250 m, with 1 minute to change at the same stop;
  - only services that run on that date, including trips after midnight.
- [x] **A list like Google's:** the earliest-arriving ride, then fewer-change alternatives, then the next
  departures (search again just after each found departure), up to 5, sorted by departure time.
  - Each ride shows its legs (walk, or a bus with board and alight stops and times), and its departure, arrival
    and total time.
- [x] Tests:
  - exact times on the fixture;
  - one, two and three buses;
  - waiting for the next trip;
  - no service on a date;
  - an after-midnight trip;
  - nothing within walking distance;
  - the list order.
- [x] **A real-data check in CI:** when the AT index is present, a JVM test plans 3 public trips between well-known
  places (Britomart, Sky Tower and Smales Farm, at a weekday 8 am). Each must give at least one ride under 2 hours,
  with times that only go forward.

### T3 Addresses
- [x] Address search with suggestions as you type, using Android's built-in `Geocoder`: free, no key, limited to
  the Auckland region.
  - Results are mixed with AT stops ("Smales Farm Stop D") and with Home and Work.
  - "Your location" uses GPS.
  - The address text goes to the phone's geocoding provider, which is Google on his phone. DESIGN §5 says so.
- [x] The ranking and the labels are pure and tested.

### T4 Plan a trip (Google-like)
- [x] From and To fields with suggestions, a swap button, the Leave now / Depart at chip, and the results list:
  - each ride shows times, total time, bus chips "866 › 70", and the walk and first departure;
  - tap a ride for its timeline: walk, wait, bus (stops, times), change, the arrival, and **Use this** or Start.
  - Using a ride creates a route whose stops are the get-off stops of each bus, named "To <place>".
- [x] It replaces Find a trip. Pasted or shared Google Maps directions fill From and To.
- [x] Screenshot tests: the form, the results, the timeline.

### T5 Home and work, first run
- [x] First run, or Routes with no routes: the "Where's home?" and "Where's work?" steps from the walkpath, then
  picking rides for To work (leaving now) and From work (leaving at 5 pm), which creates both routes.
  - Every step has a Skip; skipping falls back to + and the map.
- [x] Home and work are saved in settings (never in the repo or a log), and changed in Settings → Home and work.
  Plan a trip offers them as From and To.
- [x] Count taps in the PR. The goal is home and work typed once, plus 4 taps.

**Test on phone (Run 13):**
1. A fresh start: type home and work, and pick rides. Both routes exist with the right stops.
2. Compare Plan a trip's list with Google Maps for the same trip now: the same buses and times within a few
   minutes.
3. Leave now, and a later departure time.
4. Paste Google directions into Plan a trip.

## Later (not for automated runs yet)
These come after Gate C:
- privacy explanation and onboarding before the permission screens;
- Play declarations (notification access as well as §8's list);
- model delivery as a Play asset pack;
- localisation;
- an accessibility pass.
