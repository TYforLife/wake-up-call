Nodstop wakes one rider before their bus stop so they can sleep on the bus. Every screen is used in one of four states (at the stop, dozing, just woken, planning at home), and the design answers one question first in each: **will it wake me?**

## Content fundamentals

- Write to the rider as "you". Short, plain sentences. No exclamation marks except the ring itself ("Wake up").
- Name things the way a rider does: *stop*, *bus*, *circle*, *alarm*, *ring*. Never *leg*, *geofence*, *failsafe*, *trace* on screen. A route is "buses, in order"; a leg is "Bus 2".
- State first, detail after: "Alarm armed · rings about 45 s before Harbour St". One line per state; anything longer goes behind "Details".
- Buttons say exactly what happens: **Start To work**, **I'm off this bus**, **Stop trip**, **Allow**. Never "OK" or "Continue".
- Errors say what's wrong and the fix in one line: "GPS lost 40 s ago · the backup alarm is set for 07:49".
- No emoji in the interface. Times in 24-hour `time` style when exact (07:42), "about 6 min" when estimated.
- Real copy from the app: "Hold to stop the alarm", "Nothing new while you slept", "How was that ring's timing?", "Let the alarm buzz on Mute".

## Visual foundations

**Colour.** Two themes: Day (`light`) and Night (`dark`), following the phone; Night is the default after sunset. Everything sits on `ground`; cards are `surface`; wells, tracks and the tab bar are `surface-sunk`. Text is `ink`, secondary text `ink-muted`.
- One primary action per screen in `action` with `on-action` text. In Day that is night-bus navy; in Night it turns to the wake amber, so the one thing to tap glows.
- Alarm state has three colours, each always paired with its word: `armed` (teal) "Armed", `backup` (amber) "Backup alarm", `ring` (red) "Ringing" / "Fix". Their `-soft` tokens are the fills behind them. Never show a state by colour alone.
- The alarm and You're awake screens use the night palette in both themes: `alarm-ground`, `alarm-surface`, `alarm-raised`, `alarm-ink`, `alarm-muted`, `alarm-amber`; markers `alarm-urgent` (URGENT) and `alarm-plans` (AFFECTS YOUR PLANS).
- The map: pins `map-pin`, the current stop and its circle `map-current`.

**Type.** One family, Atkinson Hyperlegible, made for readers with low vision: its letters stay distinct when you're half asleep (`sans`); its mono cut for numbers that count down (`mono`).
- `stop-name` for where you get off. `number-xl` (mono) for distance and minutes. `alarm-title` only on the ring.
- `title` for screen titles, `heading` for card titles, `body`, `body-small` for secondary lines, `label` for status lines, chips and tabs, `time` for clock times.
- Riding and alarm screens never go below `body` (16px).

**Space and shape.** Gutters and card padding `space-4`; between cards `space-6`; above a primary action `space-8`. Cards `radius-lg`, buttons and inputs `radius-md`, chips `radius-sm`, status lines and tracks `radius-pill`, the hold target `radius-xl`. Every tap target is at least `tap-min`; primary actions are `action-height` tall and sit in the bottom third, in the thumb's reach.

**Depth.** Flat. Cards have no shadow and no border; they separate by `surface` on `ground`. Only bottom sheets over the map take `shadow-sheet`.

**Motion.** Calm, then loud. Riding screens don't animate except the progress fill and the distance counting down. The ring pulses the hold ring once per second; the hold fills in 1 s. Respect reduced motion: no pulse, the fill still shows.

**States.** Every riding surface shows one of: **Not ready** (`ring`, "1 to fix"), **Ready** (idle), **Armed** (`armed`), **Backup alarm** (`backup`, GPS lost or waiting for the bus), **Ringing** (`ring`, then the alarm screen), **Awake** (`action-soft`, "Get off at …").

## Iconography

Material Symbols Rounded (Google, Apache 2.0) at 24px, weight 500, filled for the active tab only. Used sparingly: tab icons, the back arrow, the compass, the follow button. The app has no logo yet: the name is set in `heading` type. Status is never an icon alone.
