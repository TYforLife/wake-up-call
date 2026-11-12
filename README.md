# Nodstop (wake-up-call)

PLEASE LET ME SLEEP ON THE BUS (AT limited)

Android app that wakes you before your bus stop, so you can sleep on the bus. A real system alarm
is always scheduled and the live GPS keeps pushing it forward, so it rings even if the phone kills
the app. See [`docs/DESIGN.md`](docs/DESIGN.md).

**Install the latest debug build:**
https://github.com/tyforlife/wake-up-call/releases/download/debug-latest/app-debug.apk

```bash
./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug
```
