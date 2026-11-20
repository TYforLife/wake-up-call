Nodstop is an alarm for sleeping on the bus. Its design follows what the most-used apps share: black and white, the phone's own typeface, one button per screen, plain lists, a full-screen map with a sheet over it, and an alarm that looks like the phone's clock. When in doubt, take something away.

## Rules

- **One button per screen.** `primary` on `bg`, full width, at the bottom, in the thumb's reach. Everything else is a list row, a pill or a link.
- **Lists, not cards.** Rows sit on `bg`, divided by `line` hairlines, at least `row` tall: a `row` title and a `secondary` second line. A chevron (`faint`) means it opens; a pill (`fill`) means it acts.
- **Colour is information, so there's almost none.** `fg` and `muted` on `bg` do all the work. `accent` (orange) means one thing: a trip is live (the Now tab's dot, the alarm's hold ring, the lock-screen progress bar). `danger` means something needs you, always in words ("GPS lost. Alarm at 7:44 at the latest.").
- **Dark is true black** (`bg` #000 in Dark), for a dark bus at night. The alarm and the screen after it are always dark.
- **The map is the screen while riding.** The route in `fg`, your stop as an `fg` pin with your circle as a faint `fg` ring, you as the standard blue dot. One sheet (`radius-sheet`, `shadow-sheet`) holds the words: "Get off at", the `stop`, the `figure` line, the alarm line, one button.
- **Type:** Roboto Flex (the system's Roboto on the phone). `title` for screens, `stop` for where you get off, `figure` for time and distance, `alarm` only on the ring. Nothing on a riding or alarm screen is smaller than 15px.

## Words

- Talk to the rider as "you", in short plain sentences. "Get off at Harbour St". "Alarm about 7:45". "Hold to stop".
- Say the outcome, not the mechanism: "Buzz on Mute", not "Do Not Disturb access". "Quiet other notifications", not "Interruption filter".
- Problems say what still happens: "GPS lost. Alarm at 7:44 at the latest."
- No emoji, no exclamation marks. Times as on a clock (7:45); estimates with "about".

## Icons

Thin outline icons (1.8px stroke, round caps) at 24px, drawn in `fg` or `muted`: Now (a target), Routes (a route line), Settings (sliders), back, chevron, bell, locate, search, plus. No filled icons except the active state of nothing: the active tab is just `fg` instead of `muted`.
