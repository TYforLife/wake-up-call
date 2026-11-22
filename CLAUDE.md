# Nodstop (bus alarm): agent instructions

Android app that wakes the owner (Tae) before his bus stop so he can sleep on the bus.
The full spec is in `docs/DESIGN.md`. Read it before any work; it wins over your instincts.
Read `docs/OWNER.md` too: who Tae is, what he has already asked for, and what not to touch. Add to it
whenever he asks for something new or repeats himself.
The work queue is `docs/PLAN.md`. A prompt like `/goal Finish Run N in docs/PLAN.md and open its PR` means:
follow that file's runbook for Run N, starting from the latest `main`.

## How this project is worked on

- Tae prompts cloud sessions **from his phone** and reviews PRs on his phone. He has no laptop
  during the day, and there is no emulator or device in the cloud session.
- CI builds every push and publishes `app-debug.apk` to the rolling GitHub prerelease `debug-latest`.
  Tae installs from that URL and tests on real buses.
- So: every PR description must end with a **"Test on phone"** checklist covering only what
  can't be verified in CI, plus the APK link.

## Architecture rules (non-negotiable)

1. **One ring path.** Only `AlarmManager.setAlarmClock` → `AlarmReceiver` → `RingService` rings.
   "Ring now" means scheduling at `now`. Never add a second way to make sound for the main alarm.
2. **Dead-man's switch.** Whenever a stop is left to protect, exactly one system alarm is scheduled,
   at `min(wakeAt, failsafeAt)` (DESIGN §2). The receiver rings unconditionally. Never add logic that
   lets a cold-started receiver skip or defer the ring.
3. **`:engine` and `:brief` are pure Kotlin/JVM.** No Android imports, no clocks, no randomness, no I/O. Time comes in through inputs.
   All decision logic lives here, and `:app` only adapts OS APIs to engine inputs and outputs.
4. **When in doubt, ring earlier.** Every approximation must err early. If a change could make
   any alarm later, it needs a test proving the invariant in DESIGN §2 still holds.
5. No new dependencies beyond DESIGN §5 without a one-line reason in the PR.
6. Live data (AT, roadmap) may only make an alarm earlier, never later. GPS stays the guaranteed fallback.
7. **The AI can only ring earlier.** The While You Slept briefing (DESIGN §10) never delays, cancels or
   silences a ring, and message text never reaches traces, logs, files or network code (invariants W1–W6).

## Code quality

The codebase is the biggest prompt every session reads, and whatever is in it gets copied as "how it's done
here". A workaround merged today becomes the pattern three sessions from now. So:

1. **Fix the cause, not the symptom.** No retry, delay, timer, flag or catch-all that makes a symptom go away
   while its cause stays. If the clean fix is outside this PR, leave the code as it was and list it under
   "Not fixed" in the PR, with the cause you found.
2. **Escape hatches only go down.** Each file's count of `!!`, `@Suppress`, `runCatching` and `catch (` is pinned in
   `app/src/test/resources/escape-hatches.txt`. Don't add one; when you remove one, lower its line. Raising a line
   needs a reason under "Escape hatches" in the PR. Most of the legitimate ones are on the ring path, where a
   failure must never stop a ring.
3. **Comments say what the spec requires, never why a shortcut is fine.** Cite `DESIGN §n` when it helps. Comments
   containing "workaround", "for now", "hack", "TODO" or "FIXME" fail CI.
4. **Copy a nearby pattern only if it follows these rules.** If it doesn't, don't spread it; fix it if it's
   small, otherwise note it under "Not fixed".
5. **One way to do each thing.** Search for an existing helper before writing one, and extend it rather than
   adding a second. Code that a change makes dead is deleted in the same PR, not kept "just in case".

**Corrections become checks.** When Tae or a review corrects something, first ask whether a test could catch it
next time. If it could, add that test in the same PR. Source-scanning tests like `ArchitectureTest` count, and
need no new dependency. Prose here is the last resort, kept for things that need judgement. One correction fixes
one case: record it in `docs/OWNER.md`, and don't turn it into a new hard rule unless he says it is one. Never
loosen a check to get green. If a check is wrong, change it in its own commit and say why in the PR.

| Rule | Checked by |
| --- | --- |
| 1. One ring path | `ArchitectureTest` (one `setAlarmClock`, audio only in the ring service), `RingPathTest` |
| 2. Dead-man's switch, 4. ring earlier | `InvariantPropertyTest`, engine scenario tests, `ReplayTest` |
| 3. Pure `:engine` and `:brief` | `PurityTest` in each module |
| 7. The AI only rings earlier (W1–W6) | `UrgentTest`, `GuardrailsTest`, `NotifyIsolationTest`, `:brief` `PurityTest` |
| Code quality 2 and 3 | `ArchitectureTest` |

A rule without a check in this table is one a session can break unnoticed. When you add a rule, add its check.

## Commands

```bash
./gradlew :engine:test                 # must pass before every push
./gradlew :brief:test                  # must pass before every push (While You Slept, pure Kotlin)
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug           # must succeed before every push
```

The Android SDK is at `$ANDROID_HOME` (`/opt/android-sdk`) in cloud sessions, installed by the
environment's setup script. If it's missing, say so in your reply. Don't try to work around the
network allowlist.

## Conventions

- Kotlin, Jetpack Compose (Material 3), coroutines and Flow. No Hilt, no Room.
- Engine tests use JUnit 5 and plain asserts. Synthetic trips come from the trace generator in `engine/src/test`.
- Real ride logs go in `engine/src/test/resources/traces/*.jsonl`, **location-hidden exports only**
  (Debug → Share, location hidden), and must keep replaying identically.
  If an intentional engine change alters their outputs, regenerate the expected `out` lines in the
  same PR and explain why in the description.
- Small PRs, conventional commit messages.
- Never commit secrets. The committed `keystore/debug.keystore` is debug-only and expected.
- Keep `docs/DESIGN.md` in sync when behaviour or parameters change.

## Device facts to respect

- Samsung phone; One UI kills background apps aggressively. Readiness screen and Samsung steps are part of v0.
- Owner usually wears earbuds: the ring escalates in the earbuds with vibration; without earbuds it is vibration only.
  The phone speaker is an opt-in last resort, off by default (DESIGN §5).
- Commute: "To work" is two legs, "From work" is three (DESIGN §1). The owner wants the alarm 30–60 s
  before the stop and never wants to type times: everything comes from GPS and learned rides.
- Personal stop coordinates and ride logs never go in this public repo.
