# Nodstop (wake-up-call)

PLEASE LET ME SLEEP ON THE BUS (AT limited)

**Nodstop** = nod off, don't miss your stop. It's an Android app that wakes you 30–60 s before your bus stop, so
you can sleep on the bus. It's built for Auckland Transport for now, with other regions to come.

**Install the latest debug build** (on the phone):
https://github.com/tyforlife/wake-up-call/releases/download/debug-latest/app-debug.apk

The newest push on any branch wins. The build number is at the bottom of Settings. It installs as **Nodstop dev**,
next to the Play build.

**From Google Play** (internal testing, every merge to `main`): set-up steps in [docs/play/README.md](docs/play/README.md).
Privacy policy: [docs/privacy.md](docs/privacy.md).

## Why

<!-- Tae: your story goes here, in your own words. -->

## What it does

- **Rings before your stop, every time.** A real system alarm is always set. Live GPS keeps pushing it later
  while the bus is still far away. If the phone kills the app, the alarm still goes off, early rather than late.
- **No times to type.** You pick where you get off and GPS does the rest. After a ride or two it learns how
  the bus really goes.
- **Gets through.** It plays in your earbuds with vibration, works on Mute and Do Not Disturb, opens full screen
  over other apps, and rings again every minute until you hold "I'm awake". The phone speaker is opt-in.
- **Finding stops.** Paste or share a Google Maps link, search AT stops, or tap the map. Plan a trip by
  address, with Leave at or Arrive by, from AT's timetable, offline.
- **The map looks like Google Maps.** It shows your stop, its circle and the stops still to come. Drag the circle
  to change where it wakes you.
- **One tap from where you are.** "I'm off this bus" and Stop trip are on the notification, and there are Quick
  Settings tiles for your two main routes.
- **While You Slept.** When it rings, the alarm screen shows what you missed. It's summarised on the phone, and
  your messages never leave it.
- **Says what happened.** After each trip, "Last trip" on Now says whether it rang and buzzed at each stop.
- **Alarm sounds:** Beeps, Chime, Rising, a pūkeko morning (real recordings), or your own file.

## Privacy

Your stops, rides and messages stay on the phone. Nothing goes to cloud backup or to a new phone by itself. To
move phones, use Debug → Your data, a file you share yourself. The only things sent out are map tiles
(OpenFreeMap), footpaths (OpenStreetMap), and Google short links you paste, which have to be opened to read them.

## Status

<!-- Tae: what works on your rides, and what doesn't yet. -->

- Next up: your bus, live from AT. It's waiting on an AT API key ([`docs/BACKLOG.md`](docs/BACKLOG.md)).
- Later: learn a trip just by riding it ([`docs/LEARN.md`](docs/LEARN.md)).

## How it's built

Built mostly by Claude Code cloud sessions, started and reviewed from a phone. There's no laptop. CI builds every
push and publishes the APK above, then it gets tested on real buses.

| Module | What's in it |
| --- | --- |
| `:engine` | When to ring. Pure Kotlin, no Android, with property and replay tests. |
| `:brief` | The While You Slept digest. Pure Kotlin. |
| `:app` | The Android side: Compose UI, alarm, location, map (MapLibre), on-device Gemma. |

The docs:

- [`docs/DESIGN.md`](docs/DESIGN.md): the spec. It's where the rules live.
- [`docs/OWNER.md`](docs/OWNER.md): who it's for, and what he has asked for.
- [`docs/BACKLOG.md`](docs/BACKLOG.md): what's asked for and not built yet.
- [`docs/PLAN.md`](docs/PLAN.md): the runs, for cloud sessions.
- [`CLAUDE.md`](CLAUDE.md): the rules every session follows.

Build and test (needs the Android SDK and JDK 21):

```bash
./gradlew :engine:test :brief:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

## License

MIT. See [`LICENSE`](LICENSE). Stop data comes from Auckland Transport's public GTFS feed (CC BY 4.0). Map data
is © OpenStreetMap contributors.
