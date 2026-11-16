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
     passes on `main` before you change anything.
3. **Each milestone, in order:**
   1. Implement it.
   2. Write its tests.
   3. Run `./gradlew :engine:test` (and `:brief:test` once it exists), then `:app:testDebugUnitTest :app:assembleDebug`.
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
| `kotlinx-coroutines-core` (`:brief`) and `kotlinx-coroutines-test` (tests) | `Llm.generate` is a suspend call (CLAUDE.md: coroutines and Flow) |
| `com.google.ai.edge.litertlm:litertlm-android` (Run 2 only; pin the exact version, 0.19.1 as of 2026-11-16) | Runs Gemma on the phone (DESIGN §10) |

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
- [ ] New module `:brief` with the purity test; `CLAUDE.md` rule 3 mentions it
- [ ] `Digest` per DESIGN §10, tested with a 64-message group, three one-to-one chats, VIP ordering, and non-chat notifications (counted, not read)

### B3 Capture and the alarm-screen panel
- [ ] `CaptureService` stores messages only while a trip runs (W3, W4). Own app and non-chat notifications are counted only.
- [ ] `MessageStore` is plain Kotlin, tested to clear on close and on Stop, and to keep the newest 200
- [ ] W3/W6 source-scan test (see "Where things go")
- [ ] The alarm screen shows the "While you slept" panel with the digest, or "Briefing unavailable" (W2)
- [ ] Readiness row for notification access, with a button to its settings page

### B4 Do Not Disturb during trips (experimental, off by default)
- [ ] A toggle on Readiness, plus the Do Not Disturb access request
- [ ] `DndRestore` is a pure class. Tests: start then stop restores the old filter; a crash leaves a flag that restores it on next start.

**Test on phone (Run 1):**
- capture from your chat apps (WhatsApp, Messenger, Discord, SMS, whatever you use);
- message text appears only on the alarm screen;
- with DND on: a test ring in earbuds, plus a simulated trip's at-stop buzz.

- [ ] **GATE B (Tae):** 5 rides with the digest; the DND verdict (keep or leave off) is written into DESIGN §10

---

## Run 2: Gemma on the phone

Prerequisite: Run 1 merged, Gate B ticked.

### C1 Runtime, model import, Briefing lab
- [ ] `litertlm-android` added at an exact version. `LiteRtLlm` implements `Llm`.
- [ ] `ModelManager`: import a `.litertlm` file through the system file picker into app storage; show its name and size; delete it
- [ ] Debug → "Briefing lab" runs `SampleChats` through the real model. It shows load time, time to first token, total time and the output. With no model it says so, and nothing crashes.
- [ ] W6 source-scan test still passes; the runtime adds no network use from our code

### C2 Briefing pipeline
- [ ] `PromptBuilder`: golden tests; long chats trimmed to a budget, newest messages kept
- [ ] `BriefParser`: tested with valid lines, junk, partial output and empty output; failures fall back per conversation
- [ ] `Guardrails` (W5): tested with an invented time, an invented name, a changed number and a clean pass
- [ ] `BatchPlan` is a pure class tested with time passed in:
  - a batch every 180 s or every 30 messages;
  - a final pass once the alarm is ≤ 3 min away;
  - a 60 s timeout per call;
  - no AI below 20% battery;
  - the model unloaded after the final pass
- [ ] End-to-end test with the fake `Llm`: messages in, panel lines out. A model that never answers still gives the digest (W2).

**Test on phone (Run 2):**
- import Gemma 4 E2B (and Gemma 3 1B);
- run the Briefing lab;
- post the timings and any crash in a PR comment;
- do 3 rides with the briefing.

- [ ] **DECISION C (Tae):** default model. Gemma 4 E2B if its final pass finishes in under 60 s with no crashes; otherwise Gemma 3 1B. Recorded in DESIGN §10.

---

## Run 3: Urgent wake, tones, share-back

Prerequisite: Run 2 merged, Decision C recorded.

### C3 Urgent wake from important contacts
- [ ] A list of VIP names in settings, typed in (no contacts permission)
- [ ] Only VIP messages are classified. The prompt and parser are tested with the fake `Llm`. Non-VIP messages never cause `Urgent` (test).
- [ ] An urgent message sends `Input.Urgent`; the ring goes through the single ring path; the panel shows that conversation first

### C4 Tones and share-back
- [ ] Plain or tea tone. The tea rewrite is re-checked by `Guardrails`, and a failing line falls back to plain (tests).
- [ ] A Share button per line sends that line only, plus the tagline, through the share sheet (`ShareText` tests)
- [ ] Source-scan test: `ACTION_SEND` appears only in the share UI (nothing is sent automatically)

### C5 Quality eval
- [ ] 20 synthetic conversations with the facts that must survive, in `brief/src/test/resources/eval/`
- [ ] Briefing lab "Run eval" scores facts kept and facts invented on the real model. A JVM test checks the scoring with the fake `Llm`.

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

## Later (not for automated runs yet)
These come after Gate C:
- privacy explanation and onboarding before the permission screens;
- Play declarations (notification access as well as §8's list);
- model delivery as a Play asset pack;
- localisation;
- an accessibility pass.
