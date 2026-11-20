# Nodstop designs (v2, for Gate D)

Redesigned from scratch after the owner's review of v1 ("hate the colours, the lack of simplicity and aesthetic").
Inspiration only, from what the most-used apps share (maps, ride-hailing, messaging, the phone's clock and settings):

- black and white, the phone's own typeface (Roboto Flex in the mockups, Roboto on the phone);
- one black button per screen, at the bottom;
- plain lists with hairlines, no cards;
- a full-screen map with one sheet while riding;
- an alarm that looks like the phone's clock: black, one big line, one round hold button;
- one accent colour (orange) that means only "a trip is live"; red only for a problem, always in words.

Links (private to the owner until shared):
- Screens, on a Claude Design canvas: https://claude.ai/artifact/VoVev4CB3YYocztGCXF8Cb
- Design system: https://claude.ai/artifact/CEjr1LwtbBKZzcXQeZEKdS (its files: `system/`)

Files:
- `gen_mockups.py`: the screens and tokens (`tokens.v2.json`), writes `mockups.html`, `screens.json`, `flows.json`.
- `shoot.js`: renders them to `screens/` (`node docs/design/shoot.js docs/design`).
- `screens/`: 13 screens and 2 flows. Made-up stops and messages only.
- `compare.py`: puts each mockup next to the app's screenshot (`app/src/test/snapshots/v2_<id>.png`) as
  `compare_<id>.png`, and counts the screens built. CI runs it on every push and attaches the images to
  `debug-latest` (PLAN Runs 8 and 9).

| Screen | File |
| --- | --- |
| Before your first ride | `screens/setup.png` |
| Now | `screens/now.png` |
| Riding | `screens/riding.png` |
| Riding at night | `screens/riding-night.png` |
| GPS lost | `screens/gps-lost.png` |
| Ringing | `screens/ring.png` |
| After I'm awake | `screens/awake.png` |
| Transfer | `screens/transfer.png` |
| Routes | `screens/routes.png` |
| Route | `screens/route.png` |
| Set a stop | `screens/stop.png` |
| Settings | `screens/settings.png` |
| Lock screen | `screens/lock.png` |

## Icon

A map pin, asleep: your stop, and you dozing until it. Black on white, like the screens, with no accent (orange means
a trip is live). `gen_icon.py` builds the shape once, with the eye cut out, and writes the launcher icon (adaptive:
white background, black pin at 80 % so it has room inside the launcher's circle), the themed icon (Android 13+) and
the small white icon for notifications and the Quick Settings tile. `icon/preview.png` shows them at real size.
To change the icon, edit the script (`pip install shapely`, then `python3 docs/design/gen_icon.py`), not the XML.
