# Bus Alarm: agent instructions

Android app that wakes the owner (Tae) before his bus stop so he can sleep on the bus.
The full spec is in `docs/DESIGN.md`. Read it before any work; it wins over your instincts.

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
2. **Dead-man's switch.** Exactly one system alarm is scheduled at all times during a trip, at
   `min(wakeAt, failsafeAt)`. The receiver rings unconditionally. Never add logic that lets a
   cold-started receiver skip or defer the ring.
3. **`:engine` is pure Kotlin/JVM.** No Android imports, no clocks, no randomness, no I/O. Time comes in through inputs.
   All decision logic lives here, and `:app` only adapts OS APIs to engine inputs and outputs.
4. **When in doubt, ring earlier.** Every approximation must err early. If a change could make
   any alarm later, it needs a test proving the invariant in DESIGN §2 still holds.
5. No new dependencies beyond DESIGN §5 without a one-line reason in the PR.

## Commands

```bash
./gradlew :engine:test                 # must pass before every push
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug           # must succeed before every push
```

The Android SDK is at `$ANDROID_HOME` (`/opt/android-sdk`) in cloud sessions, installed by the
environment's setup script. If it's missing, say so in your reply. Don't try to work around the
network allowlist.

## Conventions

- Kotlin, Jetpack Compose (Material 3), coroutines and Flow. No Hilt, no Room.
- Engine tests use JUnit 5 and plain asserts. Synthetic trips come from the trace generator in `engine/src/test`.
- Real ride logs go in `engine/src/test/resources/traces/*.jsonl` and must keep replaying identically.
  If an intentional engine change alters their outputs, regenerate the expected `out` lines in the
  same PR and explain why in the description.
- Small PRs, conventional commit messages.
- Never commit secrets. The committed `keystore/debug.keystore` is debug-only and expected.
- Keep `docs/DESIGN.md` in sync when behaviour or parameters change.

## Device facts to respect

- Samsung phone; One UI kills background apps aggressively. Readiness screen and Samsung steps are part of v0.
- Owner usually wears earbuds: the ring starts in the earbuds, then goes to the speaker at 30 s (DESIGN §5).
- Commute is two legs: 10 min ride, about 8 min wait, 25 min ride.
