"""
Sleepers' icon: a pūkeko nodding off. Auckland's swamp hen, the bird of the pūkeko alarm, asleep on its feet with its
head drooping, the way you doze on the bus.

One geometry, every size: the launcher icon (adaptive: black background, the bird in colour), the themed icon
(Android 13+ monochrome) and the splash icon, and the small white icon for notifications, shortcuts and the Quick
Settings tile all come from the shapes built here, so they can't drift apart. The single-colour versions are one
silhouette with the closed eye cut out (exact outlines), because a notification icon is only its alpha.

    pip install shapely
    python3 docs/design/gen_icon.py          # writes app/src/main/res/drawable/ic_*.xml and docs/design/icon/*.svg

Canvas: the adaptive icon's 108 x 108 with the safe zone a circle of radius 33 at the centre.
"""
import math
import os

from shapely import affinity
from shapely.geometry import LineString, Point, Polygon
from shapely.geometry.polygon import orient
from shapely.ops import unary_union

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app/src/main/res/drawable")
OUT = os.path.join(ROOT, "docs/design/icon")

NIGHT = "#FF111111"  # the background: tokens.v2.json light fg, the alarm's black
BODY = "#FF4B47C8"  # pūkeko violet-blue, bright enough to read on black
RED = "#FFE5352B"  # the frontal shield, beak and legs
WHITE = "#FFFFFFFF"  # the tail flash and the closed eye
PAPER = "#FFFFFFFF"

NOD = -16.0  # degrees: the head droops toward the chest, about the neck
NECK = (49.0, 48.0)
# The bird's farthest point from the centre after placing: inside the safe circle (33) with room to breathe.
REACH = 30.5
# Outlines are simplified to this tolerance (canvas units) and written to one decimal: invisible at any size, and it
# keeps each vector path short enough to draw fast (lint's VectorPath).
TOLERANCE = 0.12


# ---- curves ----

def cubic(p0, p1, p2, p3, n=12):
    out = []
    for i in range(n + 1):
        t = i / n
        a, b, c, d = (1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t ** 3
        out.append((a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]))
    return out


def quad(p0, p1, p2, n=10):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]) for t in (i / n for i in range(n + 1))]


def path(start, *segments):
    """A closed outline from a start point and segments: ('C', c1, c2, end) or ('L', end)."""
    pts, cur = [start], start
    for s in segments:
        if s[0] == "C":
            pts += cubic(cur, s[1], s[2], s[3])[1:]
            cur = s[3]
        else:
            pts.append(s[1])
            cur = s[1]
    return Polygon(pts).buffer(0)


def stroke(points, width):
    return LineString(points).buffer(width / 2, quad_segs=5)


# ---- the bird (108 canvas, before placing) ----

def parts():
    body = path((42, 54), ("C", (42, 44), (53, 39.5), (63, 41)), ("C", (75, 43), (82, 52), (80.5, 61.5)),
                ("C", (79, 70), (67, 73), (57.5, 71.5)), ("C", (47.5, 70), (42, 64), (42, 54)))
    tail = Polygon([(76.5, 55), (84.5, 51), (81.5, 61.5)])
    legs = unary_union([stroke([(56.5, 70.5), (54.5, 84.5)], 3.4), stroke([(63.5, 71), (66.5, 84.5)], 3.4),
                        stroke([(50.5, 85), (57.5, 85)], 2.6), stroke([(63.5, 85), (70.5, 85)], 2.6)])
    neck = path((43.5, 51), ("C", (41.5, 46.5), (41.5, 42), (44.5, 38)), ("L", (53, 42)),
                ("C", (49.5, 45), (49, 49), (51, 53)), ("L", (43.5, 51)))
    head = Point(46, 37).buffer(10, quad_segs=10)
    shield = path((45.2, 27.1), ("C", (40.5, 26.6), (36.7, 29.6), (35.6, 34.4)), ("L", (26, 47.2)),
                  ("C", (33, 48), (38.3, 44.6), (40.8, 40.4)), ("C", (42.6, 37.3), (44, 32.3), (45.2, 27.1)))
    eye = stroke(quad((45.6, 37.4), (48.6, 40.6), (51.6, 37.4)), 2.4)

    def nod(g):
        return affinity.rotate(g, NOD, origin=NECK)

    blue = unary_union([body, nod(neck), nod(head)])
    return {"legs": legs, "tail": tail, "blue": blue, "shield": nod(shield), "eye": nod(eye)}


def place(shapes, cx, cy, reach):
    """Centres the whole bird on (cx, cy) and scales it so its farthest point is [reach] from there."""
    whole = unary_union(list(shapes.values()))
    minx, miny, maxx, maxy = whole.bounds
    mx, my = (minx + maxx) / 2, (miny + maxy) / 2
    far = max(math.hypot(x - mx, y - my) for g in getattr(whole, "geoms", [whole]) for x, y in g.exterior.coords)
    k = reach / far
    return {n: affinity.translate(affinity.scale(g, k, k, origin=(mx, my)), cx - mx, cy - my) for n, g in shapes.items()}


def fit(shapes, size, margin):
    """Centres the bird in a [size] box, its larger side filling all but [margin] each side."""
    whole = unary_union(list(shapes.values()))
    minx, miny, maxx, maxy = whole.bounds
    k = (size - 2 * margin) / max(maxx - minx, maxy - miny)
    mx, my = (minx + maxx) / 2, (miny + maxy) / 2
    return {n: affinity.translate(affinity.scale(g, k, k, origin=(mx, my)), size / 2 - mx, size / 2 - my) for n, g in shapes.items()}


def silhouette(s, colour):
    """One colour: every part but the eye, with the eye cut out. The parts stay separate paths (they overlap in one
    colour, so they read as one shape), which keeps each path short."""
    return [(colour, orient(s[n].difference(s["eye"]), sign=1.0)) for n in ("legs", "tail", "blue", "shield")]


# ---- output ----

def path_data(geom, scale=1.0):
    geom = geom.simplify(TOLERANCE * scale)

    def ring(coords):
        return "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in coords[:-1]) + " Z"
    polys = getattr(geom, "geoms", [geom])
    return " ".join(ring(list(p.exterior.coords)) + "".join(" " + ring(list(i.coords)) for i in p.interiors) for p in polys)


def colour_layers(s):
    return [(RED, s["legs"]), (WHITE, s["tail"]), (BODY, s["blue"]), (RED, s["shield"]), (WHITE, s["eye"])]


def vector(size_dp, viewport, layers, disc=None):
    body = ""
    if disc:
        c, r = disc
        h = viewport / 2
        body += (f'\n    <path android:fillColor="{c}"\n        android:pathData="M{h - r},{h} a{r},{r} 0 1,0 {2 * r},0 '
                 f'a{r},{r} 0 1,0 {-2 * r},0 Z" />')
    for c, g in layers:
        body += f'\n    <path android:fillColor="{c}" android:fillType="evenOdd"\n        android:pathData="{path_data(g, viewport / 108)}" />'
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by docs/design/gen_icon.py: edit that, not this. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{size_dp}dp" android:height="{size_dp}dp"\n'
        f'    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">{body}\n</vector>\n'
    )


def svg(viewport, bg, layers):
    rect = f'<rect width="{viewport}" height="{viewport}" fill="{bg}"/>' if bg else ""
    shapes = "".join(f'<path fill="#{c[3:]}" fill-rule="evenodd" d="{path_data(g, viewport / 108)}"/>' for c, g in layers)
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {viewport} {viewport}">{rect}{shapes}</svg>\n'


def main():
    bird = parts()
    launcher = place(bird, 54, 54, REACH)
    # The small icon: the silhouette in a 24 dp box (status bar, tile), 1 dp clear around it.
    small = fit(bird, 24, 1.0)
    files = {
        os.path.join(RES, "ic_launcher_background.xml"):
            '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by docs/design/gen_icon.py: edit that, not this. -->\n'
            '<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n'
            f'    <solid android:color="{NIGHT}" />\n</shape>\n',
        os.path.join(RES, "ic_launcher_foreground.xml"): vector(108, 108, colour_layers(launcher)),
        os.path.join(RES, "ic_launcher_monochrome.xml"): vector(108, 108, silhouette(launcher, PAPER)),
        # Android 12+ masks the splash icon to a circle of radius 36 on its 108 canvas: the black disc is drawn at
        # that radius, so on the white splash the bird sits on black as on the launcher; at night it's on black anyway.
        os.path.join(RES, "ic_splash.xml"): vector(108, 108, colour_layers(launcher), disc=(NIGHT, 36)),
        os.path.join(RES, "ic_tile.xml"): vector(24, 24, silhouette(small, PAPER)),
    }
    os.makedirs(OUT, exist_ok=True)
    files[os.path.join(OUT, "icon.svg")] = svg(108, "#111111", colour_layers(launcher))  # the full adaptive canvas
    files[os.path.join(OUT, "small.svg")] = svg(24, None, silhouette(small, "#FF111111"))
    for p, text in files.items():
        with open(p, "w") as fh:
            fh.write(text)
        print("wrote", os.path.relpath(p, ROOT))


if __name__ == "__main__":
    main()
