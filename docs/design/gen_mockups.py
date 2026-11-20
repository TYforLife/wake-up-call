"""Nodstop mockups, v2: rendered to docs/design/screens/ by docs/design/shoot.js.

Direction: what the most-used apps share (maps, ride-hailing, messaging, the clock): black and white,
the system font, one black primary button, lists with hairlines instead of cards, a full-bleed map with a
sheet over it, and one accent colour used only for the live trip and the alarm.
"""
import json
import pathlib

HERE = pathlib.Path(__file__).parent

# ---- tokens (the whole palette) ----
LIGHT = {
    "bg": "#ffffff", "fg": "#111111", "muted": "#6b6b6b", "faint": "#a3a3a3",
    "line": "#ececec", "fill": "#f4f4f4", "primary": "#111111", "on-primary": "#ffffff",
    "accent": "#ff5a1f", "danger": "#d92d20",
    "map-bg": "#eef0f2", "map-road": "#ffffff", "map-park": "#e3eedf", "map-water": "#d6e6f5",
}
DARK = {
    "bg": "#000000", "fg": "#f5f5f5", "muted": "#9a9a9a", "faint": "#5c5c5c",
    "line": "#1f1f1f", "fill": "#161616", "primary": "#f5f5f5", "on-primary": "#000000",
    "accent": "#ff6a33", "danger": "#ff5c50",
    "map-bg": "#121314", "map-road": "#24262a", "map-park": "#16211a", "map-water": "#0f1a26",
}


def vars_css(t):
    return "".join(f"--{k}:{v};" for k, v in t.items())


ICON = {
    "now": '<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="2.5" fill="currentColor" stroke="none"/></svg>',
    "routes": '<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="6" cy="18" r="2"/><circle cx="18" cy="6" r="2"/><path d="M8 18h6a3 3 0 0 0 0-6h-4a3 3 0 0 1 0-6h6"/></svg>',
    "settings": '<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><path d="M4 7h10M18 7h2M4 17h4M12 17h8"/><circle cx="16" cy="7" r="2"/><circle cx="10" cy="17" r="2"/></svg>',
    "chev": '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 6l6 6-6 6"/></svg>',
    "back": '<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M15 6l-6 6 6 6"/></svg>',
    "bell": '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20a2 2 0 0 0 4 0"/></svg>',
    "check": '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
    "locate": '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="7"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/></svg>',
    "compass": '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path d="M12 3l3.5 9h-7z" fill="#d92d20"/><path d="M12 21l-3.5-9h7z" fill="currentColor" opacity=".35"/></svg>',
    "search": '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="6"/><path d="M16 16l4 4"/></svg>',
    "plus": '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>',
    "warn": '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 4l9 16H3z"/><path d="M12 10v4M12 17v.5"/></svg>',
}


def status(time="7:38", dark=False):
    return f'<div class="sb"><span>{time}</span><span class="sb-r"><i class="sig"></i><i class="bat"></i></span></div>'


def tabs(active, live=None):
    out = []
    for key, label in (("now", "Now"), ("routes", "Routes"), ("settings", "Settings")):
        on = " on" if key == active else ""
        dot = '<i class="live-dot"></i>' if (key == "now" and live) else ""
        out.append(f'<div class="tab{on}"><span class="ti">{ICON[key]}{dot}</span><span>{label}</span></div>')
    return '<nav class="tabs">' + "".join(out) + "</nav>"


def _road_pts():
    # Street geometry shared by the drawing and the route (all straight lines).
    return {"h1": ((-20, 60), (410, 110)), "h2": ((-20, 250), (410, 215)), "h3": ((-20, 430), (410, 400)),
            "v1": ((80, -200), (120, 900)), "v2": ((210, -200), (190, 900)), "v3": ((330, -200), (360, 900))}


def _cross(a, b):
    (x1, y1), (x2, y2) = a
    (x3, y3), (x4, y4) = b
    d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
    t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / d
    return (round(x1 + t * (x2 - x1), 1), round(y1 + t * (y2 - y1), 1))


def _on(line, y):
    (x1, y1), (x2, y2) = line
    return (round(x1 + (x2 - x1) * (y - y1) / (y2 - y1), 1), y)


def map_svg(h=520, route=True, zone=90, pin=None, me=None, voff=95):
    r = _road_pts()
    # Default ride: up street v1, along h2, up v2, along h1 to the stop.
    me = me or _on(r["v1"], 330)
    turn1 = _cross(r["v1"], r["h2"])
    turn2 = _cross(r["h2"], r["v2"])
    turn3 = _cross(r["v2"], r["h1"])
    pin = pin or (turn3[0] + 70, round(60 + 50 * (turn3[0] + 70 + 20) / 430, 1))
    pts = [me, turn1, turn2, turn3, pin]
    poly = " ".join(f"{x},{y}" for x, y in pts)
    px, py = pin
    mx, my = me
    roads = "".join(
        f'<path d="M{a[0]} {a[1]} L{b[0]} {b[1]}" stroke-width="{w}"/>'
        for (a, b), w in zip(r.values(), (10, 14, 10, 10, 14, 8)))
    return f'''<svg class="map" viewBox="0 {-voff} 390 {h}" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
<rect x="-50" y="-250" width="490" height="{h+500}" fill="var(--map-bg)"/>
<path d="M300 -250 C 330 0, 330 260, 420 330 L 420 -250 Z" fill="var(--map-water)"/>
<rect x="18" y="120" width="56" height="86" rx="6" fill="var(--map-park)"/>
<g stroke="var(--map-road)" stroke-linecap="round" fill="none">{roads}</g>
{f'<circle cx="{px}" cy="{py}" r="{zone}" fill="var(--fg)" fill-opacity=".045" stroke="var(--fg)" stroke-opacity=".28" stroke-width="1.5"/>' if zone else ''}
{f'<polyline points="{poly}" fill="none" stroke="var(--fg)" stroke-width="5" stroke-linejoin="round" stroke-linecap="round"/>' if route else ''}
<circle cx="{px}" cy="{py}" r="11" fill="var(--fg)"/><circle cx="{px}" cy="{py}" r="4" fill="var(--bg)"/>
{f'<circle cx="{mx}" cy="{my}" r="18" fill="#1a73e8" fill-opacity=".18"/><circle cx="{mx}" cy="{my}" r="8" fill="#1a73e8" stroke="#fff" stroke-width="3"/>' if me else ''}
</svg>'''


screens = []


def S(id, title, body, dark=False, note=""):
    screens.append({"id": id, "title": title, "body": body, "dark": dark, "note": note})


# 1. Setup: one screen, four switches-like rows
S("setup", "Before your first ride", f'''{status()}
<div class="page">
  <h1 class="h1">Before your first ride</h1>
  <p class="lead">Allow these so the alarm always reaches you.</p>
  <div class="list">
    <div class="row"><div class="rt"><b>Notifications</b><span>To show the alarm</span></div><span class="ok">{ICON['check']}</span></div>
    <div class="row"><div class="rt"><b>Over the lock screen</b><span>So you can stop it without unlocking</span></div><button class="pill">Allow</button></div>
    <div class="row"><div class="rt"><b>Exact alarms</b><span>So it rings on time</span></div><button class="pill">Allow</button></div>
    <div class="row"><div class="rt"><b>Buzz on Mute</b><span>So it vibrates even when muted</span></div><button class="pill">Allow</button></div>
    <div class="row"><div class="rt"><b>Location while riding</b><span>Only during a trip you start</span></div><button class="pill">Allow</button></div>
  </div>
  <div class="grow"></div>
  <button class="btn">Continue</button>
</div>''', note="One screen, one list, one button. Each row says why in five words.")

# 2. Now idle
S("now", "Now", f'''{status()}
<div class="page">
  <h1 class="h1">Good morning</h1>
  <p class="lead">Where are you riding?</p>
  <div class="list tight">
    <div class="row big pick on"><span class="radio"></span><div class="rt"><b>To work</b><span>Central Station, then Harbour St</span></div></div>
    <div class="row big pick"><span class="radio"></span><div class="rt"><b>From work</b><span>3 buses · Hill Ave</span></div></div>
  </div>
  <div class="grow"></div>
  <p class="hint center">Wakes you about 45 s before each stop</p>
  <button class="btn">Start</button>
</div>
{tabs("now")}''', note="Your likely route is already picked. Start is the only button.")

# 3. Riding: Uber/Maps style
S("riding", "Riding", f'''<div class="mapwrap">{map_svg(520)}{status()}
  <button class="fab" aria-label="Recentre">{ICON['locate']}</button>
</div>
<div class="sheet">
  <div class="grab"></div>
  <div class="eyebrow">Get off at</div>
  <div class="stop">Harbour St</div>
  <div class="meta"><span class="num">6 min</span><span class="sep"></span><span class="num">2.3 km</span></div>
  <div class="alarmline">{ICON['bell']}<span>Alarm about 7:45</span></div>
  <button class="btn ghost">I'm off this bus</button>
</div>
{tabs("now", live=True)}''', note="Full-bleed map, one sheet: where, how long, and that the alarm is set.")

# 4. Riding at night
S("riding-night", "Riding at night", f'''<div class="mapwrap">{map_svg(520)}{status()}
  <button class="fab" aria-label="Recentre">{ICON['locate']}</button>
</div>
<div class="sheet">
  <div class="grab"></div>
  <div class="eyebrow">Get off at</div>
  <div class="stop">Harbour St</div>
  <div class="meta"><span class="num">6 min</span><span class="sep"></span><span class="num">2.3 km</span></div>
  <div class="alarmline">{ICON['bell']}<span>Alarm about 22:45</span></div>
  <button class="btn ghost">I'm off this bus</button>
</div>
{tabs("now", live=True)}''', dark=True, note="True black at night: easy on the eyes on a dark bus.")

# 5. GPS lost
S("gps-lost", "GPS lost", f'''<div class="mapwrap dim">{map_svg(520)}{status()}</div>
<div class="sheet">
  <div class="grab"></div>
  <div class="eyebrow">Get off at</div>
  <div class="stop">Harbour St</div>
  <div class="meta"><span class="num muted">2.9 km · 40 s ago</span></div>
  <div class="alarmline danger">{ICON['warn']}<span>GPS lost. Alarm at 7:44 at the latest.</span></div>
  <button class="btn ghost">I'm off this bus</button>
</div>
{tabs("now", live=True)}''', note="The only change is one red line that says what still happens.")

# 6. Ringing
S("ring", "Ringing", f'''<div class="alarm">
  {status("7:44")}
  <div class="al-top">
    <div class="al-time">Wake up</div>
    <div class="al-stop">Harbour St in about 45 s</div>
  </div>
  <div class="grow"></div>
  <div class="holdwrap">
    <svg viewBox="0 0 200 200" width="200" height="200" aria-hidden="true">
      <circle cx="100" cy="100" r="88" fill="#161616"/>
      <circle cx="100" cy="100" r="88" fill="none" stroke="var(--accent)" stroke-width="8" stroke-linecap="round" stroke-dasharray="207 553" transform="rotate(-90 100 100)"/>
    </svg>
    <span class="holdlabel">Hold</span>
  </div>
  <p class="al-hint">Hold for 1 second to stop<br>or hold volume down</p>
</div>''', dark=True, note="Like the phone's own alarm: black, one big line, one control. Messages wait until you're awake.")

# 7. Awake
S("awake", "After I'm awake", f'''{status("7:44")}
<div class="page">
  <div class="eyebrow">Now</div>
  <h1 class="h1 xl">Get off at Harbour St</h1>
  <p class="lead">About 45 s away. It's your last bus.</p>
  <div class="section-t">While you slept</div>
  <div class="list">
    <div class="row"><div class="rt"><b>Daycare <em class="tag">Urgent</em></b><span>Please call us now, Mia has a fever</span></div></div>
    <div class="row"><div class="rt"><b>Flat group</b><span>Saturday 7pm is decided; you're on snacks.</span></div></div>
    <div class="row"><div class="rt"><span>3 other notifications</span></div></div>
  </div>
  <div class="grow"></div>
  <button class="btn">Done</button>
</div>''', dark=True, note="What to do first, then what you missed. Nothing else.")

# 8. Transfer
S("transfer", "Transfer", f'''<div class="mapwrap">{map_svg(520)}{status()}</div>
<div class="sheet">
  <div class="grab"></div>
  <div class="eyebrow">Off at Central Station · next stop</div>
  <div class="stop">Harbour St</div>
  <div class="meta"><span class="num muted">Waiting for bus 2</span></div>
  <div class="alarmline">{ICON['bell']}<span>Alarm set until you're on it</span></div>
  <button class="btn ghost">I'm on a different bus</button>
</div>
{tabs("now", live=True)}''', note="Same screen as riding: the stop changes, the line says you're waiting.")

# 9. Routes
S("routes", "Routes", f'''{status()}
<div class="page">
  <div class="titlebar"><h1 class="h1">Routes</h1><button class="iconbtn" aria-label="Add a route">{ICON['plus']}</button></div>
  <div class="list tight">
    <div class="row big"><div class="rt"><b>To work</b><span>2 buses · 12 rides</span></div><span class="chev">{ICON['chev']}</span></div>
    <div class="row big"><div class="rt"><b>From work</b><span class="danger-t">Hill Ave needs a stop</span></div><span class="chev">{ICON['chev']}</span></div>
  </div>
  <div class="grow"></div>
</div>
{tabs("routes")}''', note="Two rows. A problem is a red line on the row, nothing more.")

# 10. Route
S("route", "From work", f'''{status()}
<div class="page">
  <div class="titlebar"><button class="iconbtn" aria-label="Back">{ICON['back']}</button></div>
  <h1 class="h1">From work</h1>
  <div class="steps">
    <div class="st"><i></i><div><b>Central Station</b><span>Alarm 45 s before</span></div></div>
    <div class="st off"><i></i><div><b>Park Rd</b><span>No alarm</span></div></div>
    <div class="st warn"><i></i><div><b>Hill Ave</b><span class="danger-t">Set the stop</span></div></div>
  </div>
  <button class="link">Add a bus</button>
  <div class="grow"></div>
  <button class="btn">Set Hill Ave</button>
</div>''', note="The route as stops on a line. The button is the next thing it needs.")

# 11. Set a stop on the map
S("stop", "Set a stop", f'''<div class="mapwrap tall">{map_svg(600, pin=(200, 232), me=(300, 400), zone=80, route=False, voff=0)}{status()}
  <div class="search">{ICON['search']}<span>Search or paste a link</span></div>
  <button class="fab top" aria-label="North">{ICON['compass']}</button>
  <div class="maptip">Drag the map to place your stop</div>
</div>
<div class="sheet">
  <div class="grab"></div>
  <div class="eyebrow">Hill Ave · wake me within</div>
  <div class="seg"><span>Auto</span><span>150 m</span><span class="on">250 m</span><span>400 m</span></div>
  <button class="btn">Save stop</button>
</div>''', note="The map does the work. Pick a distance, save.")

# 12. Settings
S("settings", "Settings", f'''{status()}
<div class="page">
  <h1 class="h1">Settings</h1>
  <div class="list">
    <div class="row"><div class="rt"><b>Quiet other notifications</b><span>During trips</span></div><span class="sw on"></span></div>
    <div class="row"><div class="rt"><b>Ring out loud after 60 s</b><span>If vibration didn't wake you</span></div><span class="sw"></span></div>
    <div class="row"><div class="rt"><b>Ask how the timing was</b><span>After each ring</span></div><span class="sw on"></span></div>
    <div class="row"><div class="rt"><b>Important contacts</b><span>Mum, Daycare</span></div><span class="chev">{ICON['chev']}</span></div>
    <div class="row"><div class="rt"><b>Report a missed alarm</b><span>Shares the trip log, locations hidden</span></div><span class="chev">{ICON['chev']}</span></div>
  </div>
  <div class="grow"></div>
  <p class="hint center">Build 64</p>
</div>
{tabs("settings")}''', note="Five rows. Everything technical is gone.")

# 13. Lock screen
S("lock", "Lock screen", f'''<div class="lock">
  {status("7:41")}
  <div class="lk-time">7:41</div>
  <div class="lk-date">Friday 20 November</div>
  <div class="grow"></div>
  <div class="note"><div class="nt"><span class="app"><i class="live-dot inline"></i>Nodstop</span><span class="muted">now</span></div>
    <b>Harbour St in 6 min</b><span class="muted">Alarm about 7:45</span>
    <div class="bar"><i style="width:68%"></i></div></div>
</div>''', dark=True, note="What you see while dozing: one line and a bar.")

flows = [
    ("Start a trip", ["now", "riding", "ring", "awake"]),
    ("Set a stop", ["routes", "route", "stop"]),
]

CSS = """
@import url("https://fonts.googleapis.com/css2?family=Roboto+Flex:opsz,wght@8..144,400;8..144,500;8..144,600;8..144,700&display=swap");
*{box-sizing:border-box} html,body{margin:0}
body{background:#e9e9e9;font-family:"Roboto Flex",Roboto,system-ui,sans-serif;color:#111;padding:32px;-webkit-font-smoothing:antialiased}
.board{display:flex;flex-wrap:wrap;gap:40px}
.frame{width:390px;display:flex;flex-direction:column;gap:10px}
.cap{font-size:15px;font-weight:600} .why{font-size:13px;line-height:18px;color:#555}
.phone{width:390px;height:844px;border-radius:44px;overflow:hidden;position:relative;display:flex;flex-direction:column;background:var(--bg);color:var(--fg);box-shadow:0 0 0 1px rgba(0,0,0,.08),0 20px 50px rgba(0,0,0,.12)}
.sb{height:44px;display:flex;align-items:center;justify-content:space-between;padding:0 28px;font-size:15px;font-weight:600;position:relative;z-index:2;flex:none}
.sb-r{display:flex;gap:6px;align-items:center} .sig{width:17px;height:11px;border-radius:2px;background:currentColor;opacity:.9;clip-path:polygon(0 100%,100% 0,100% 100%)} .bat{width:24px;height:11px;border-radius:3px;border:1.5px solid currentColor;position:relative} .bat::after{content:"";position:absolute;inset:1.5px;right:6px;background:currentColor;border-radius:1px}
.page{flex:1;display:flex;flex-direction:column;padding:12px 24px 24px;min-height:0}
.grow{flex:1}
.h1{font-size:32px;line-height:38px;font-weight:700;letter-spacing:-.02em;margin:16px 0 4px}
.h1.xl{font-size:36px;line-height:42px;margin-top:4px}
.lead{font-size:17px;line-height:24px;color:var(--muted);margin:4px 0 20px}
.eyebrow{font-size:14px;font-weight:500;color:var(--muted);display:flex;align-items:center;gap:8px;margin-top:8px}
.hint{font-size:14px;color:var(--muted);margin:0 0 12px} .center{text-align:center} .muted{color:var(--muted)}
.list{display:flex;flex-direction:column;margin-top:12px}
.row{display:flex;align-items:center;gap:16px;padding:16px 0;border-bottom:1px solid var(--line);min-height:64px}
.row:last-child{border-bottom:0}
.row.big{min-height:76px}
.rt{flex:1;display:flex;flex-direction:column;gap:3px;min-width:0}
.rt b{font-size:17px;line-height:22px;font-weight:600;display:flex;gap:8px;align-items:center}
.row.big .rt b{font-size:20px;line-height:26px}
.rt span{font-size:15px;line-height:20px;color:var(--muted)}
.chev{color:var(--faint);display:flex}
.ok{color:var(--fg);display:flex}
.pill{font:inherit;font-size:15px;font-weight:600;border:0;border-radius:999px;padding:9px 16px;background:var(--fill);color:var(--fg)}
.btn{font:inherit;font-size:17px;font-weight:600;height:56px;border-radius:16px;border:0;background:var(--primary);color:var(--on-primary);width:100%}
.btn.ghost{background:var(--fill);color:var(--fg)}
.link{font:inherit;font-size:16px;font-weight:600;background:none;border:0;color:var(--fg);text-align:left;padding:14px 0;text-decoration:underline;text-underline-offset:3px}
.tabs{display:grid;grid-template-columns:repeat(3,1fr);border-top:1px solid var(--line);padding:8px 0 22px;background:var(--bg);flex:none;position:relative;z-index:3}
.tab{display:flex;flex-direction:column;align-items:center;gap:3px;font-size:12px;font-weight:500;color:var(--muted)}
.tab.on{color:var(--fg)}
.ti{position:relative;display:flex}
.live-dot{position:absolute;top:-1px;right:-3px;width:8px;height:8px;border-radius:50%;background:var(--accent);border:2px solid var(--bg)}
.live-dot.inline{position:static;display:inline-block;border:0;width:8px;height:8px}
.mapwrap{position:relative;flex:1;min-height:0} .mapwrap.tall{flex:1}
.mapwrap .map{position:absolute;inset:0;width:100%;height:100%}
.mapwrap .sb{position:absolute;top:0;left:0;right:0}
.mapwrap.dim .map{filter:grayscale(1);opacity:.55}
.fab{position:absolute;right:16px;bottom:44px;width:48px;height:48px;border-radius:50%;border:0;background:var(--bg);color:var(--fg);display:grid;place-items:center;box-shadow:0 2px 10px rgba(0,0,0,.15)}
.fab.top{top:116px;bottom:auto}
.sheet{background:var(--bg);border-radius:24px 24px 0 0;margin-top:-28px;position:relative;z-index:2;padding:8px 24px 20px;display:flex;flex-direction:column;gap:6px;box-shadow:0 -6px 20px rgba(0,0,0,.08)}
.grab{width:36px;height:5px;border-radius:3px;background:var(--line);align-self:center;margin-bottom:6px}
.stop{font-size:34px;line-height:40px;font-weight:700;letter-spacing:-.02em}
.meta{display:flex;align-items:center;gap:12px;font-size:20px;font-weight:600;font-feature-settings:"tnum"}
.sep{width:4px;height:4px;border-radius:50%;background:var(--faint)}
.num.muted{color:var(--muted);font-weight:500;font-size:17px}
.alarmline{display:flex;align-items:center;gap:8px;font-size:15px;color:var(--muted);margin:6px 0 12px}
.alarmline.danger{color:var(--danger);font-weight:600}
.alarm{flex:1;display:flex;flex-direction:column;padding:0 0 28px;background:var(--bg)}
.al-top{padding:56px 28px 0;text-align:center}
.al-time{font-size:64px;line-height:68px;font-weight:300;letter-spacing:-.03em}
.al-stop{font-size:20px;line-height:26px;color:var(--muted);margin-top:10px}
.msgs{margin:0 24px 20px;display:flex;flex-direction:column}
.msg{display:flex;flex-direction:column;gap:2px;padding:12px 0;border-top:1px solid var(--line);font-size:15px;line-height:20px} .msg b{font-weight:600} .msg span{color:var(--muted)}
.hold{margin:0 24px;height:76px;border-radius:38px;background:#1c1c1c;position:relative;overflow:hidden;display:grid;place-items:center;font-size:19px;font-weight:600}
.hold-fill{position:absolute;left:0;top:0;bottom:0;width:38%;background:var(--accent)}
.hold span{position:relative}
.holdwrap{position:relative;width:200px;height:200px;align-self:center;display:grid;place-items:center}
.holdwrap svg{position:absolute;inset:0}
.holdlabel{position:relative;font-size:24px;font-weight:600}
.al-hint{text-align:center;color:var(--muted);font-size:15px;line-height:22px;margin:18px 0 8px}
.pick{gap:14px}
.radio{width:24px;height:24px;border-radius:50%;border:2px solid var(--faint);flex:none}
.pick.on .radio{border:7px solid var(--fg)}
.section-t{font-size:14px;font-weight:600;color:var(--muted);margin-top:24px}
.tag{font-style:normal;font-size:12px;font-weight:600;color:var(--danger);border:1px solid currentColor;border-radius:6px;padding:0 6px;line-height:18px}
.steps{display:flex;flex-direction:column;margin-top:20px}
.st{display:grid;grid-template-columns:20px 1fr;gap:16px;padding-bottom:24px;position:relative}
.st::before{content:"";position:absolute;left:9px;top:20px;bottom:0;width:2px;background:var(--line)}
.st:last-child::before{display:none}
.st i{width:20px;height:20px;border-radius:50%;border:2px solid var(--fg);background:var(--bg);margin-top:2px}
.st.done i{background:var(--fg)} .st.now i{border-color:var(--accent);background:var(--accent)}
.st.off i{border-style:dashed;border-color:var(--faint)} .st.warn i{border-color:var(--danger)}
.st b{font-size:18px;line-height:24px;font-weight:600;display:block} .st span{font-size:15px;color:var(--muted)}
.danger-t{color:var(--danger)!important}
.titlebar{display:flex;align-items:center;justify-content:space-between;min-height:48px} .titlebar .h1{margin:8px 0}
.iconbtn{width:44px;height:44px;border-radius:50%;border:0;background:var(--fill);color:var(--fg);display:grid;place-items:center}
.search{position:absolute;left:16px;right:16px;top:52px;height:52px;border-radius:26px;background:var(--bg);display:flex;align-items:center;gap:10px;padding:0 18px;color:var(--muted);font-size:16px;box-shadow:0 2px 12px rgba(0,0,0,.12);z-index:2}
.maptip{position:absolute;left:50%;bottom:48px;transform:translateX(-50%);background:var(--fg);color:var(--bg);font-size:14px;font-weight:500;padding:8px 14px;border-radius:999px;white-space:nowrap}
.seg{display:grid;grid-template-columns:repeat(4,1fr);background:var(--fill);border-radius:14px;padding:4px;margin:6px 0 14px}
.seg span{text-align:center;padding:10px 0;font-size:15px;font-weight:600;color:var(--muted);border-radius:10px}
.seg span.on{background:var(--bg);color:var(--fg);box-shadow:0 1px 4px rgba(0,0,0,.12)}
.sw{width:51px;height:31px;border-radius:16px;background:var(--line);position:relative;flex:none}
.sw::after{content:"";position:absolute;top:2px;left:2px;width:27px;height:27px;border-radius:50%;background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.25)}
.sw.on{background:var(--fg)} .sw.on::after{left:22px;background:var(--bg)}
.lock{flex:1;display:flex;flex-direction:column;padding:0 0 32px;background:linear-gradient(180deg,#1b1d22,#000)}
.lk-time{font-size:84px;line-height:90px;font-weight:300;text-align:center;margin-top:48px;letter-spacing:-.03em}
.lk-date{text-align:center;font-size:17px;color:var(--muted)}
.note{margin:0 12px;background:rgba(255,255,255,.1);border-radius:24px;padding:14px 16px;display:flex;flex-direction:column;gap:3px;font-size:15px}
.note b{font-size:17px} .nt{display:flex;justify-content:space-between;font-size:13px;margin-bottom:4px} .app{display:flex;align-items:center;gap:6px;font-weight:600}
.bar{height:4px;border-radius:2px;background:rgba(255,255,255,.18);margin-top:10px;overflow:hidden} .bar i{display:block;height:100%;background:var(--accent)}
.flows{margin-top:56px;display:flex;flex-direction:column;gap:48px}
.flow h2{font-size:22px;margin:0 0 16px} .flowrow{display:flex;gap:20px;align-items:center;flex-wrap:wrap}
.arrow{font-size:28px;color:#888} .mini{width:234px;height:506px;overflow:hidden;border-radius:28px}
.mini .phone{transform:scale(.6);transform-origin:top left;box-shadow:none}
"""


def phone(s):
    t = DARK if s["dark"] else LIGHT
    return f'<div class="phone" id="{s["id"]}" style="{vars_css(t)}">{s["body"]}</div>'


def build():
    html = [f'<!doctype html><html><head><meta charset="utf-8"><title>Nodstop v2</title><style>{CSS}</style></head><body>',
            '<div class="board">']
    for s in screens:
        html.append(f'<div class="frame"><div class="cap">{s["title"]}</div>{phone(s)}<div class="why">{s["note"]}</div></div>')
    html.append('</div><div class="flows">')
    by = {s["id"]: s for s in screens}
    for name, ids in flows:
        html.append(f'<div class="flow" id="flow-{name.lower().replace(" ", "-")}"><h2>{name}</h2><div class="flowrow">')
        for i, sid in enumerate(ids):
            if i:
                html.append('<span class="arrow">→</span>')
            s = by[sid]
            t = DARK if s["dark"] else LIGHT
            html.append(f'<div class="mini"><div class="phone" style="{vars_css(t)}">{s["body"]}</div></div>')
        html.append('</div></div>')
    html.append('</div></body></html>')
    (HERE / "mockups.html").write_text("\n".join(html))
    (HERE / "screens.json").write_text(json.dumps([{k: s[k] for k in ("id", "title", "note", "dark")} for s in screens], indent=1))
    (HERE / "flows.json").write_text(json.dumps([{"name": n, "screens": i} for n, i in flows], indent=1))
    (HERE / "tokens.v2.json").write_text(json.dumps({"light": LIGHT, "dark": DARK}, indent=1))


if __name__ == "__main__":
    build()
    print(len(screens), "screens")
