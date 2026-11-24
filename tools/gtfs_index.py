#!/usr/bin/env python3
"""
Builds Nodstop's offline stop and route index from a GTFS feed (PLAN Run 12, C2 and C3).

Auckland Transport publishes its timetable as GTFS (CC BY 4.0). CI downloads it at build time and this script
boils it down to two small files the app reads offline:

  stops.tsv     one stop per line: code, name, lat, lng (the line number is the stop's index)
  routes.tsv    one route pattern per line: short name, headsign, its stops in order (comma-separated indexes)
  meta.txt      where it came from, and the attribution

and the timetable, for the journey planner (PLAN Run 13, T1):

  patterns.tsv  one exact stop sequence per line: short name, headsign, stop indexes (line number = pattern id)
  profiles.tsv  one per-stop time profile per line: seconds after the first stop's departure, comma-separated
  trips.tsv     pattern id, profile id, service id, first departure (seconds after midnight; may pass 86400)
  services.tsv  one service per line: days Mon..Sun as 7 digits, start and end dates (yyyymmdd), dates added,
                dates removed (comma-separated); the line number is the service id
  shapes.tsv    one line per timetable pattern (same line numbers): its road shape from shapes.txt as an encoded
                polyline (Google's format, 1e-5 degrees), simplified to about 8 m, then for each of its stops the
                index of the shape point at it (comma-separated); an empty line when the feed has no shape for it

Only the standard library is used. Public data only: no personal stops or rides are involved.

  python3 tools/gtfs_index.py --out app/src/main/assets/gtfs                       # download Auckland Transport's feed
  python3 tools/gtfs_index.py --dir tools/testdata/gtfs --out build/gtfs-fixture   # a feed already unpacked
"""
import argparse
import collections
import csv
import math
import io
import os
import sys
import tempfile
import urllib.request
import zipfile

AT_URL = "https://gtfs.at.govt.nz/gtfs.zip"
ATTRIBUTION = "Stop and route data: Auckland Transport, CC BY 4.0"


def opener(args):
    if args.dir:
        return lambda name: open(os.path.join(args.dir, name), encoding="utf-8-sig", newline="")
    path = args.zip
    if not path:
        tmp = tempfile.NamedTemporaryFile(suffix=".zip", delete=False)
        with urllib.request.urlopen(args.url, timeout=120) as r:
            tmp.write(r.read())
        tmp.close()
        path = tmp.name
    z = zipfile.ZipFile(path)
    return lambda name: io.TextIOWrapper(z.open(name), encoding="utf-8-sig", newline="")


def clean(s):
    return " ".join((s or "").replace("\t", " ").split())


def metres(a, b):
    """Equirectangular distance in metres between two (lat, lng): plenty for a few km."""
    k = math.cos(math.radians((a[0] + b[0]) / 2))
    return math.hypot((a[0] - b[0]) * 111_320.0, (a[1] - b[1]) * 111_320.0 * k)


def seg_dist(p, a, b):
    """Distance in metres from p to the segment a-b."""
    k = math.cos(math.radians(p[0])) * 111_320.0
    ax, ay, bx, by = (a[1] - p[1]) * k, (a[0] - p[0]) * 111_320.0, (b[1] - p[1]) * k, (b[0] - p[0]) * 111_320.0
    dx, dy = bx - ax, by - ay
    l2 = dx * dx + dy * dy
    t = 0.0 if l2 == 0 else max(0.0, min(1.0, (-ax * dx - ay * dy) / l2))
    return math.hypot(ax + dx * t, ay + dy * t)


def simplify(pts, tol=8.0):
    """Douglas-Peucker (iterative): the points that keep the line within [tol] metres."""
    if len(pts) <= 2:
        return list(pts)
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        i, j = stack.pop()
        far, at = -1.0, -1
        for k in range(i + 1, j):
            d = seg_dist(pts[k], pts[i], pts[j])
            if d > far:
                far, at = d, k
        if far > tol:
            keep[at] = True
            stack += [(i, at), (at, j)]
    return [p for p, k in zip(pts, keep) if k]


def encode(pts):
    """Google's encoded polyline (precision 1e-5)."""
    out, last = [], (0, 0)
    for lat, lng in pts:
        cur = (int(round(lat * 1e5)), int(round(lng * 1e5)))
        for v in (cur[0] - last[0], cur[1] - last[1]):
            v = ~(v << 1) if v < 0 else v << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        last = cur
    return "".join(out)


def stops_on(shape, stop_pts):
    """For each stop in order, the index of the (full-detail) shape point at it: after the previous stop's, the first
    point about as close as the route ever comes (within 30 m of that), so a loop or a route that comes back past the
    same place isn't read out of order."""
    out, start = [], 0
    for sp in stop_pts:
        ds = [metres(shape[k], sp) for k in range(start, len(shape))]
        if not ds:
            out.append(len(shape) - 1)
            continue
        near = min(ds) + 30.0
        k = next(i for i, d in enumerate(ds) if d <= near)
        # Down to the bottom of that approach.
        while k + 1 < len(ds) and ds[k + 1] < ds[k]:
            k += 1
        out.append(start + k)
        start = start + k
    return out


def simplify_keeping(pts, keep):
    """[simplify] between the points that must stay (each stop's), and those points' new indexes."""
    keep = sorted(set(keep) | {0, len(pts) - 1})
    out, at = [], {}
    for a, b in zip(keep, keep[1:]):
        part = simplify(pts[a:b + 1])
        if not out:
            at[a] = 0
            out += part
        else:
            out += part[1:]
        at[b] = len(out) - 1
    return out, at


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--dir")
    ap.add_argument("--zip")
    ap.add_argument("--url", default=AT_URL)
    args = ap.parse_args()
    open_txt = opener(args)

    # Stops you can stand at (not parent stations), in a stable order.
    stops = []
    with open_txt("stops.txt") as f:
        for r in csv.DictReader(f):
            if (r.get("location_type") or "0") not in ("", "0"):
                continue
            stops.append((clean(r.get("stop_name")), clean(r.get("stop_code")), r["stop_id"], float(r["stop_lat"]), float(r["stop_lon"])))
    stops.sort(key=lambda s: (s[0].lower(), s[1], s[2]))
    index = {s[2]: i for i, s in enumerate(stops)}

    routes = {}
    with open_txt("routes.txt") as f:
        for r in csv.DictReader(f):
            routes[r["route_id"]] = clean(r.get("route_short_name")) or clean(r.get("route_long_name"))

    trip_key = {}
    with open_txt("trips.txt") as f:
        for r in csv.DictReader(f):
            if r["route_id"] in routes:
                trip_key[r["trip_id"]] = (routes[r["route_id"]], clean(r.get("trip_headsign")), r.get("direction_id") or "")

    # One trip per route, headsign and direction: the one that calls at the most stops.
    counts = {}
    with open_txt("stop_times.txt") as f:
        for r in csv.DictReader(f):
            t = r["trip_id"]
            if t in trip_key:
                counts[t] = counts.get(t, 0) + 1
    best = {}
    for t, n in counts.items():
        k = trip_key[t]
        if k not in best or n > best[k][1] or (n == best[k][1] and t < best[k][0]):
            best[k] = (t, n)
    chosen = {t for t, _ in best.values()}
    seqs = {t: [] for t in chosen}
    with open_txt("stop_times.txt") as f:
        for r in csv.DictReader(f):
            t = r["trip_id"]
            if t in chosen and r["stop_id"] in index:
                seqs[t].append((int(r["stop_sequence"]), index[r["stop_id"]]))

    patterns = set()
    for (short, headsign, _), (t, _) in best.items():
        order = [i for _, i in sorted(seqs[t])]
        if len(order) >= 2:
            patterns.add((short, headsign, ",".join(map(str, order))))

    # ---- the timetable (PLAN Run 13, T1) ----
    def secs(t):
        h, m, x = (int(v) for v in t.strip().split(":"))
        return h * 3600 + m * 60 + x

    trip_service = {}
    trip_route = {}
    trip_shape = {}
    with open_txt("trips.txt") as f:
        for r in csv.DictReader(f):
            if r["route_id"] in routes:
                trip_service[r["trip_id"]] = r["service_id"]
                trip_route[r["trip_id"]] = (routes[r["route_id"]], clean(r.get("trip_headsign")))
                if r.get("shape_id"):
                    trip_shape[r["trip_id"]] = r["shape_id"]
    calls = {}
    with open_txt("stop_times.txt") as f:
        for r in csv.DictReader(f):
            t = r["trip_id"]
            if t in trip_service and r["stop_id"] in index:
                when = r.get("departure_time") or r.get("arrival_time")
                if when:
                    calls.setdefault(t, []).append((int(r["stop_sequence"]), index[r["stop_id"]], secs(when)))

    services = {}
    try:
        with open_txt("calendar.txt") as f:
            for r in csv.DictReader(f):
                days = "".join(r[d] for d in ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"))
                services[r["service_id"]] = [days, r["start_date"], r["end_date"], [], []]
    except KeyError:
        pass
    try:
        with open_txt("calendar_dates.txt") as f:
            for r in csv.DictReader(f):
                sv = services.setdefault(r["service_id"], ["0000000", "99999999", "00000000", [], []])
                (sv[3] if r["exception_type"] == "1" else sv[4]).append(r["date"])
    except KeyError:
        pass
    service_ids = sorted(set(trip_service.values()) & set(services))
    service_index = {sid: i for i, sid in enumerate(service_ids)}

    pattern_index, profile_index, timed = {}, {}, []
    pattern_shapes = collections.defaultdict(collections.Counter)
    for t, cs in calls.items():
        if len(cs) < 2 or trip_service[t] not in service_index:
            continue
        cs.sort()
        seq = tuple(c[1] for c in cs)
        first = cs[0][2]
        prof = tuple(c[2] - first for c in cs)
        pk = (trip_route[t][0], trip_route[t][1], seq)
        pid = pattern_index.setdefault(pk, len(pattern_index))
        fid = profile_index.setdefault(prof, len(profile_index))
        timed.append((pid, first, fid, service_index[trip_service[t]]))
        if t in trip_shape:
            pattern_shapes[pid][trip_shape[t]] += 1
    timed.sort()

    # Each pattern's road shape: its trips' most common one (PLAN: the map draws the bus's road, not stop to stop).
    wanted = {c.most_common(1)[0][0] for c in pattern_shapes.values()}
    raw = collections.defaultdict(list)
    try:
        with open_txt("shapes.txt") as f:
            for r in csv.DictReader(f):
                if r["shape_id"] in wanted:
                    raw[r["shape_id"]].append((int(r["shape_pt_sequence"]), float(r["shape_pt_lat"]), float(r["shape_pt_lon"])))
    except KeyError:
        pass
    shapes = {sid: [(la, ln) for _, la, ln in sorted(pts)] for sid, pts in raw.items()}

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "stops.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for name, code, _, lat, lng in stops:
            f.write(f"{code}\t{name}\t{lat:.5f}\t{lng:.5f}\n")
    with open(os.path.join(args.out, "routes.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for short, headsign, order in sorted(patterns):
            f.write(f"{short}\t{headsign}\t{order}\n")
    with open(os.path.join(args.out, "patterns.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for (short, headsign, seq), _ in sorted(pattern_index.items(), key=lambda kv: kv[1]):
            f.write(f"{short}\t{headsign}\t{','.join(map(str, seq))}\n")
    with open(os.path.join(args.out, "profiles.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for prof, _ in sorted(profile_index.items(), key=lambda kv: kv[1]):
            f.write(",".join(map(str, prof)) + "\n")
    with open(os.path.join(args.out, "trips.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for pid, first, fid, sid in timed:
            f.write(f"{pid}\t{fid}\t{sid}\t{first}\n")
    with open(os.path.join(args.out, "services.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for sid in service_ids:
            days, start, end, added, removed = services[sid]
            f.write(f"{days}\t{start}\t{end}\t{','.join(sorted(added))}\t{','.join(sorted(removed))}\n")
    shaped = 0
    with open(os.path.join(args.out, "shapes.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for (short, headsign, seq), pid in sorted(pattern_index.items(), key=lambda kv: kv[1]):
            c = pattern_shapes.get(pid)
            shape = shapes.get(c.most_common(1)[0][0]) if c else None
            if not shape or len(shape) < 2:
                f.write("\n")
                continue
            # Stops are placed on the full-detail shape, then kept through the simplifying, so each stop has a road
            # point at it (a long straight stretch would otherwise leave its nearest point far away).
            on = stops_on(shape, [(stops[i][3], stops[i][4]) for i in seq])
            simple, moved = simplify_keeping(shape, on)
            f.write(f"{encode(simple)}\t{','.join(str(moved[i]) for i in on)}\n")
            shaped += 1
    with open(os.path.join(args.out, "meta.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"{ATTRIBUTION}\nstops={len(stops)}\npatterns={len(patterns)}\n")
        f.write(f"timetable_patterns={len(pattern_index)}\nprofiles={len(profile_index)}\ntrips={len(timed)}\nservices={len(service_ids)}\n")
        f.write(f"shapes={shaped}\n")
    size = sum(os.path.getsize(os.path.join(args.out, n)) for n in os.listdir(args.out))
    print(f"gtfs index: {len(stops)} stops, {len(patterns)} route patterns, {len(timed)} trips, "
          f"{len(pattern_index)} timetable patterns, {len(profile_index)} profiles, {shaped} shapes, {size // 1024} KB -> {args.out}")


if __name__ == "__main__":
    sys.exit(main())
