# Nodstop designs (Run 7, for Gate D)

- `../UX.md`: the research, journey audit and information architecture these designs come from.
- `screens/`: every screen and state at phone size (PNG, 2×), plus the five key flows (`flow-*.png`).
  Rendered from `mockups.html` with the tokens in `system/tokens.json` (`tokens.gen.css` is generated from them).
- `system/`: the design system as the files of a Claude **Design System** artifact (`project/` layout:
  `design-system.json`, `tokens.json`, `README.md` brand book, `components/*/README.md` + `preview.html`,
  `components/bundle.css`). Published: https://claude.ai/artifact/CEjr1LwtbBKZzcXQeZEKdS

The screens are also on a Claude **Design** canvas, four rows by flow, where you can comment on any one:
https://claude.ai/artifact/VoVev4CB3YYocztGCXF8Cb

Made-up stops and messages only; no personal locations.

| Screen | File |
| --- | --- |
| Setup 1: it can wake you | `screens/setup-wake.png` |
| Setup 2: it can follow the bus | `screens/setup-follow.png` |
| Now: ready (morning) | `screens/now-ready.png` |
| Now: not ready | `screens/now-notready.png` |
| Now: waiting for the bus | `screens/now-waiting.png` |
| Now: riding (armed) | `screens/now-riding.png` |
| Now: riding at night | `screens/now-riding-night.png` |
| Now: GPS lost | `screens/now-gps-lost.png` |
| Now: transfer | `screens/now-transfer.png` |
| Ringing, locked | `screens/ring-locked.png` |
| Ringing, phone in use | `screens/ring-inuse.png` |
| Awake: what now | `screens/awake-next.png` |
| Awake: early safety ring | `screens/awake-stay.png` |
| Routes | `screens/routes.png` |
| Route | `screens/route-edit.png` |
| Set a stop and its circle on the map | `screens/stop-map.png` |
| Settings | `screens/settings.png` |
| Lock screen while dozing | `screens/lock.png` |
