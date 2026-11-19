# Nodstop UX: research, audit and information architecture (Run 7)

Written from the code on `main` (after Run 6) and the owner's feedback in Runs 4–6. This doc is
the input to the designs. It is not a spec: once Gate D approves the designs, DESIGN.md §5 is updated
to match them in Run 8.

## Who and when

One rider, on a Samsung phone, on two commutes: **To work** (two buses) and **From work** (three buses,
one a short hop with no alarm). The rider uses the app in four states, and the design has to work in
each one:

| State | Hands, eyes, attention | What they need from the screen |
| --- | --- | --- |
| **At the stop**, about to board | One hand, standing, often in a hurry | One tap to start the right route. Confidence it's on. |
| **Dozing** on the bus | Phone in a pocket or a loose hand, screen off | Nothing to do. The trip shows on the lock screen and in the status bar. |
| **Woken** by the ring | Groggy, maybe on Mute, maybe without earbuds | Stop the ring without fumbling. Where am I, what do I do now? |
| **Planning**, at home | Two hands, time to spare | Set routes and circles, fix setup. Trust that it will work tomorrow. |

What the owner said, across the rounds (quoted or close to it):
- "It looks so bad." "All you did was change colours and move words." The product needs a real UX, not a reskin.
- "Never wants to type times"; "everything comes from GPS and learned rides" (CLAUDE.md).
- "Don't show bus stations unrelated to this trip" (fixed in Run 6, keep it).
- "I tend to just end the trip when I wake at a good time" (Run 5: ending doesn't teach).
- "The brief didn't show because I held I'm awake" (Run 5: You're awake always shows).
- "Alarm me even when I'm on my phone"; "4 failed alarms"; "the buzz isn't ringing on Mute" (Runs 4 and 6).
- Feedback buttons: "make them optional, and after each leg (that's when I click it)".
- "A small island at the top, like Samsung's timer" (Run 4).
- "Can I turn the map like Google Maps?" (Run 5).

## Journey audit (today)

Taps are counted from the app's launcher icon unless stated. "Pain" items are the failure points the
designs must remove.

### 1. First run and setup

Today: install → Home shows a "Check setup" chip and a red card if full-screen alarms are off →
Readiness lists 9 checks (notifications, precise location, full-screen alarm, buzz on Mute, exact alarms,
battery, live progress, While you slept, alarm volume), each with its own Fix button that leaves the app →
Samsung's three manual steps as text → back to Home → the seeded routes "To work" and "From work" need
every stop's coordinates.

- About **12–15 taps and 6 trips out to system settings** before the first ride, in no particular order.
- Pain: the checks are a flat list. Nothing says which ones decide whether it **wakes you** (notifications,
  full-screen, exact alarms, buzz on Mute) and which ones decide whether it **tracks the bus**
  (location, battery).
- Pain: a stop can only be set by standing at it ("Use my location") or pasting coordinates from Google
  Maps. **You can't drop a pin on the map**, though the app has a map.
- Pain: Samsung's sleeping-apps steps are plain text that must be followed by hand, from memory.

### 2. Start a trip at the stop

Today: launcher → Home → scroll to the route card → **Start** (2 taps), or the Quick Settings tile
(1 tap). The first time, "Quiet your trips?" adds a dialog. Then the Trip screen opens.

- Pain: Home always lists every route, though the time of day and where you stand almost always tell
  which one it is (morning at home: To work).
- Pain: "Start from a later leg…" is a menu under each route; auto-pick of the leg from GPS exists but is
  invisible.
- Pain: nothing confirms, at a glance, that **the alarm is armed** (the most important fact).

### 3. Ride and doze

Today: the Trip screen (stop name, distance, ETA, alarm time, details), the same facts again on Home's
running-trip card, and the trip notification (Live Update on Android 16, Stop trip action).

- Pain: **Home and Trip are two screens showing one thing.** Back from Trip lands on Home, which shows it
  again.
- Pain: the lock screen and status bar are where the rider actually looks while dozing; the app screens
  are rarely seen mid-ride. The notification is designed as an afterthought.
- Pain: GPS lost / waiting for GPS is a small chip; it should change the whole screen's tone, because the
  alarm then runs on the failsafe.

### 4. Wake

Today: the ring (earbuds or vibration, escalating; buzzes on Mute since Run 6) → full-screen alarm
(over the lock screen) or a heads-up if the phone is in use → hold **I'm awake** 1 s (or volume down
1.5 s, or the notification button) → "You're awake" (briefing or "Nothing new") → Done → the app
closes. Then a silent "How was that ring's timing?" notification.

- Pain: after "I'm awake", **nothing says what to do next**: get off here, or stay on (it was an early
  safety ring), or which bus is next.
- Pain: the briefing and the wake-up instructions compete for the same screen.
- Strength to keep: the hold target is huge and can't be hit by a pocket brush.

### 5. Get off

Today: automatic. Walking slowly inside the circle (or 150 m) for 10 s ends the leg; a 45-s slow
dwell near the stop also does. Manually: Trip → "I'm off this bus".

- Pain: when the leg ends by itself, the screen just switches; there's no "You're off at Harbour St"
  moment, so the rider can't tell whether the app noticed.

### 6. Transfer

Today: the next leg starts in WAITING; its alarm is a failsafe until boarding is detected; Trip
shows "Waiting for the bus".

- Pain: no transfer view. The rider needs: which stop to get off at next, that the alarm will ring
  again, and (later, with live data) when the next bus comes.

### 7. End and give feedback

Today: the trip ends by itself at the last stop, or with Stop trip (confirmed). Feedback: the per-leg
notification after each "I'm awake" (optional), and a Home card after the trip for legs not asked.

- Pain: the Home rating card comes back on every open until answered or skipped.
- Pain: when an alarm failed, the only way to report it is Debug → Share log, which most riders would
  never find.

### 8. Set a circle (wake zone)

Today: Home → Map → tap a stop pin → wake-zone sheet (presets, slider) → Save.

- Pain: the circle is a strong feature buried two levels down; it isn't visible from the route itself.
- Pain: "Auto" vs. your own size isn't explained where you'd decide.

## Information architecture (proposed)

**Three places, plus two overlays.** Every screen in one sentence:

| Place | Its one job |
| --- | --- |
| **Now** | Start the right trip in one tap, and while riding, show where you get off and that the alarm is set. |
| **Routes** | Your routes as buses in order, each stop on a map with its circle; add, edit, reorder. |
| **Settings** | How it wakes you (sound, buzz, speaker, quiet trip, timing questions) and While you slept. |
| *Setup* (overlay) | First run, and whenever something required breaks: get to "it will wake you" in as few steps as possible. |
| *Alarm* (overlay) | Stop the ring, then tell you what to do next. |

What goes, what merges:
- **Home + Trip → Now.** Idle: a suggested route (by time of day and where you are) as one big Start,
  others below. Riding: the live trip fills the screen (stop, distance, ETA, alarm armed), with the
  map one swipe up, not a separate screen.
- **Map → inside Now (riding) and Routes (planning).** No standalone map screen.
- **Readiness → Setup + a status line.** Setup groups checks into "It can wake you" and "It can track
  the bus" and walks through them; afterwards a single "Ready" or "1 to fix" line on Now.
  Preferences move to Settings.
- **Debug → hidden.** Long-press the build number in Settings. "Report a missed alarm" becomes a
  plain row in Settings that shares the location-hidden log.
- **Route editor → Routes.** Pins are placed **on the map** (drop or drag), with "Use my location"
  and paste as alternatives. The circle is set on the same map.
- **After "I'm awake" → a "Next" card first** (get off at X / stay on, next bus to Y), the briefing
  below it.

### Navigation map

```
            ┌──────────── first run / required check broken ────────────┐
            ▼                                                            │
         [Setup] ── done ──► [Now] ◄──── tab ────► [Routes] ◄── tab ──► [Settings]
                              │  ▲                    │                     │
                 Start ───────┘  │                    └─ route ─► [Route]   └─ long-press build ─► (Debug)
                              ▼  │                                  │
                         [Now: riding] ── swipe up ─► map           └─ stop ─► pin + circle on map
                              │
                  ring ──► [Alarm] ── hold ──► [Awake: Next + briefing] ── Done ──► [Now]
```

The bottom bar has three tabs: Now, Routes, Settings. While a trip runs, Now is pinned and the tab
shows the live distance.

## Design principles

1. **The alarm state is the headline.** Every riding screen says, first and largest: armed, ringing,
   or "running on backup" (GPS lost).
2. **One primary action per screen**, in the thumb zone at the bottom.
3. **Readable at arm's length on a moving bus**: stop names 28 sp or more, numbers 44 sp, contrast AAA
   for the riding and alarm screens.
4. **Dark by default at night**, with the alarm screen's night palette; light by day; both designed.
5. **Calm, then loud.** Riding screens are quiet and dim; the alarm is the only loud screen.
6. **Never ask for typing on the bus.** Typing is for planning (route names, contacts) only.
7. **Explain by state, not by paragraphs.** One line of plain text per state; details behind a tap.

## Open questions for Gate D

- Suggested route by time and place: is "morning → To work, evening → From work" enough, or should it
  learn from what you start?
- Bottom bar or a single screen with a menu? (Proposed: bottom bar, three tabs.)
- Should the briefing (While you slept) get its own tab in "Now", or stay on the awake screen only?
