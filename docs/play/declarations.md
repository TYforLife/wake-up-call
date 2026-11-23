# Play Console: App content and declarations (copy from here)

What to answer in Play Console → **App content** (and the release page's permission prompts) for Nodstop. Each answer
matches the code as it is in `AndroidManifest.xml` and DESIGN §5. If the app changes what it uses, change this page in
the same PR.

## Privacy policy

URL: <https://github.com/tyforlife/wake-up-call/blob/main/docs/privacy.md>

## Ads, app access, target audience, content rating

- **Ads:** No.
- **App access:** "All functionality is available without any access restrictions" (no account, no login).
- **Target audience:** 18 and over. This keeps it out of the Families policy. It's an adult commuter's tool.
- **Content rating:** category **Utility, productivity, communication or other**. Every content question is No (no
  violence, no user-generated content, no sharing of location with other users, no purchases).
- **News app / government app / financial features / health:** No.

## Data safety

Google's form counts data as "collected" when it is sent off the phone. Nodstop keeps trips, GPS tracks, stops,
learned rides and captured chat notifications on the phone. A few requests do go out (`docs/privacy.md`):

- map tiles from OpenFreeMap for the area on the screen;
- **Plan a trip**: its start and end points, which is the current position when you start from "here", go to
  OpenStreetMap's Overpass (footpaths in about 2 km) and FOSSGIS's foot router (exact points);
- a pasted `maps.app.goo.gl` link sent to Google, to open it.

Over-declaring costs nothing, so declare location fully:

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | Yes |
| Location → **Precise location** and **Approximate location** | Collected: **Yes**. Shared: **Yes** (public OpenStreetMap services, to plan walks). Processed ephemerally: **Yes**. Required: **No** (only Plan a trip and the map). Purpose: **App functionality**. |
| Messages, contacts, personal info, files, app activity, device IDs | Not collected (While You Slept reads notifications in memory on the phone only, DESIGN §10 W3) |
| Is all user data encrypted in transit? | Yes (https only) |
| Do you provide a way for users to request that their data be deleted? | Nothing is kept off the phone. Uninstalling deletes everything; Settings → Debug → **Forget all learned stops** deletes learned rides. |

## Exact alarms (`USE_EXACT_ALARM`)

If Play asks for this declaration, pick **Alarm clock** as the core function.

> Nodstop is an alarm clock for bus passengers. Its core function is to wake the user before their stop. The
> wake-up time is scheduled with `AlarmManager.setAlarmClock` and moves as the bus moves, so it must fire exactly
> on time, including in Doze. A late alarm means a missed stop.

## Full-screen intent (`USE_FULL_SCREEN_INTENT`)

Answer: **Yes, the app's core function is an alarm.**

> The wake-up alarm shows full screen over the lock screen, like a clock app's alarm, so a sleeping passenger sees
> and stops it. It is only used for that alarm. While the phone is in use, it shows as a banner instead.

## Foreground services (Android 14+ types)

One declaration per type, each with a short video link. A YouTube "unlisted" video is fine. Record on the phone with
the screen recorder: a **simulated trip** (Settings → Debug) shows everything without riding a bus.

### Location (`location`: `TripService`, `LearnSpikeService`)

- **Feature:** Trip tracking: "Wake me before my stop".
- **Description:**
  > When the user taps Start on a route, Nodstop follows the bus with GPS until the user is off at the last stop.
  > It must keep running with the screen off, because the user is asleep. Its notification shows the next stop,
  > the distance, and buttons to get off or stop the trip. It starts only from a tap in the app, a Quick Settings
  > tile or a launcher shortcut (all in the foreground). The app doesn't ask for background location.
- **Impact if deferred or interrupted:** "The alarm would come late or not at all, and the passenger misses their stop."
- **Video must show:**
  1. Opening Nodstop and tapping **Start** on a route.
  2. The trip notification appearing.
  3. Locking the phone, then the lock-screen progress.
  4. The alarm ringing near the stop.
  5. **I'm off this bus** from the notification, and the notification going away.

### System exempted (`systemExempted`: `RingService`)

- **Feature:** The wake-up alarm itself (sound in the earbuds, vibration).
- **Description:**
  > Started only by the exact alarm (`setAlarmClock`) the app schedules for the user's stop. It plays the alarm in
  > the earbuds and vibrates until the user holds "I'm awake". Android documents this type for apps holding
  > `USE_EXACT_ALARM`.
- **Video:** the same recording, from the alarm screen until "I'm awake" is held.

### Media playback (`mediaPlayback`: `RingService`)

- **Feature:** The same alarm, on phones where `systemExempted` isn't available or is refused (the app falls back).
- **Description:**
  > Plays the alarm tone in the user's earbuds while the alarm rings, and pauses their music while it does. It
  > stops when the user says they're awake.
- **Video:** the same recording, with earbuds connected and music playing before the ring.

## Battery optimisation (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)

**Nothing to declare: the app doesn't ask for it.** Google's list of acceptable uses covers VoIP or chat apps that
can't use FCM, safety apps, task automation and some companion devices. Alarm apps aren't on it. Google also says
alarms set with `setAlarmClock` "continue to fire normally" in Doze, which is how Nodstop rings. So the exemption
isn't part of the core function, and a request would likely be rejected. The Setup row **Keep running** opens
Nodstop's **App info** instead, where the user taps **Battery → Unrestricted**. On Samsung that is the same switch,
and `isIgnoringBatteryOptimizations` still reports it, with no permission needed.

## Notification access (While You Slept, optional)

No Play Console form exists for it. The policy that applies is Play's User Data policy: say what is read and why
before the user turns it on. The Setup row already does this before it opens the system page: "Lets Nodstop read your
chat notifications during a trip and show what you missed on the alarm screen. Memory only: never saved, logged or
sent anywhere." DESIGN §10 W1–W6, enforced by `NotifyIsolationTest`.

## Other permissions (no declaration)

- Do Not Disturb access (`ACCESS_NOTIFICATION_POLICY`): "Let the alarm buzz on Mute".
- `ACTIVITY_RECOGNITION`: Debug → Learn spike only.
- `RECEIVE_BOOT_COMPLETED`: a trip's alarm is set again after a restart.
- `POST_PROMOTED_NOTIFICATIONS`: the Android 16 Live Update.
- `MODIFY_AUDIO_SETTINGS`: the earbuds' volume during the ring.
- `WAKE_LOCK`, `VIBRATE`, `POST_NOTIFICATIONS`, `INTERNET`.
