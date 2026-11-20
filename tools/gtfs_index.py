#!/usr/bin/env python3
"""
Builds Nodstop's offline stop and route index from a GTFS feed (PLAN Run 12, C2 and C3).

Auckland Transport publishes its timetable as GTFS (CC BY 4.0). CI downloads it at build time and this script
boils it down to two small files the app reads offline:

  stops.tsv   one stop per line: code, name, lat, lng (the line number is the stop's index)
  routes.tsv  one route pattern per line: short name, headsign, its stops in order (comma-separated indexes)
  meta.txt    where it came from, and the attribution

Only the standard library is used. Public data only: no personal stops or rides are involved.

  python3 tools/gtfs_index.py --out app/src/main/assets/gtfs                       # download Auckland Transport's feed
  python3 tools/gtfs_index.py --dir tools/testdata/gtfs --out build/gtfs-fixture   # a feed already unpacked
"""
import argparse
import csv
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

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "stops.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for name, code, _, lat, lng in stops:
            f.write(f"{code}\t{name}\t{lat:.5f}\t{lng:.5f}\n")
    with open(os.path.join(args.out, "routes.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for short, headsign, order in sorted(patterns):
            f.write(f"{short}\t{headsign}\t{order}\n")
    with open(os.path.join(args.out, "meta.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"{ATTRIBUTION}\nstops={len(stops)}\npatterns={len(patterns)}\n")
    print(f"gtfs index: {len(stops)} stops, {len(patterns)} route patterns -> {args.out}")


if __name__ == "__main__":
    sys.exit(main())
