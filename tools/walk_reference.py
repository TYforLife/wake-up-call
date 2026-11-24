#!/usr/bin/env python3
"""Reference walks for RealWalkTest (BACKLOG 27), from CI only.

From a public place (Auckland City Hospital), the stops near it from the bundled AT index, and a second router's
walking metres to them (FOSSGIS's OSRM, foot profile, one table request). Also prints the ways the app's Overpass
filter adds over a plain one (trunk roads with footpaths, pedestrian areas), so a reviewer can see why.

    python3 tools/walk_reference.py --stops app/src/main/assets/gtfs/stops.tsv --out app/build/footpaths
    python3 tools/walk_reference.py --explain app/build/footpaths/disagree.tsv

--explain: for each walk RealWalkTest found more than 15% from the router's, the map's tags (ways and barriers)
where our walk leaves the router's, so the cause can be read off CI's log.
"""
import argparse
import json
import math
import os
import sys
import time
import urllib.parse
import urllib.request

HOSPITAL = (-36.8601, 174.7697)
ROUTER = "https://routing.openstreetmap.de/routed-foot/table/v1/foot/"
ROUTE = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/"
OVERPASS = "https://overpass-api.de/api/interpreter"
UA = {"User-Agent": "Nodstop-CI"}
ACCESS_M = 800.0
COUNT = 15


def dist(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6_371_008.8 * math.asin(math.sqrt(h))


def get(url, data=None):
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, data=data, headers=UA)
            with urllib.request.urlopen(req, timeout=90) as r:
                return r.read().decode()
        except OSError as e:
            print(f"  {url[:60]}...: {e}", file=sys.stderr)
            time.sleep(20 * (attempt + 1))
    return None


def near_stops(path):
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            code, name, lat, lng = line.rstrip("\n").split("\t")[:4]
            p = (float(lat), float(lng))
            d = dist(HOSPITAL, p)
            if d <= ACCESS_M:
                out.append((d, code, name, p))
    return sorted(out)[:COUNT]


def report_added():
    """The trunk roads and areas around the hospital that the app's filter walks on, with their tags."""
    lat, lng = HOSPITAL
    dlat, dlng = 1000 / 111320.0, 1000 / (111320.0 * math.cos(math.radians(lat)))
    bbox = f"{lat - dlat:.5f},{lng - dlng:.5f},{lat + dlat:.5f},{lng + dlng:.5f}"
    q = (f'[out:json][timeout:25];(way["highway"~"^(trunk|trunk_link)$"]({bbox});'
         f'way["highway"]["area"="yes"]({bbox}););out tags qt;')
    reply = get(OVERPASS, urllib.parse.urlencode({"data": q}).encode())
    if reply is None:
        print("filter report: Overpass didn't answer")
        return
    for e in json.loads(reply).get("elements", []):
        t = e.get("tags", {})
        print(f"  way {e['id']}: highway={t.get('highway')} area={t.get('area', '-')} sidewalk={t.get('sidewalk', '-')} "
              f"foot={t.get('foot', '-')} motorroad={t.get('motorroad', '-')} name={t.get('name', '')}")


def explain(path):
    """Where our walk leaves the router's: the ways and barriers there, with their tags."""
    with open(path, encoding="utf-8") as f:
        rows = [l.rstrip("\n").split("\t") for l in f if l.strip()]
    for name, lat, lng, way in rows:
        ours = [tuple(map(float, p.split(","))) for p in way.split(";")]
        stop = (float(lat), float(lng))
        reply = get(f"{ROUTE}{HOSPITAL[1]:.6f},{HOSPITAL[0]:.6f};{stop[1]:.6f},{stop[0]:.6f}?overview=full&geometries=geojson")
        theirs = []
        if reply:
            r = json.loads(reply).get("routes") or [{}]
            theirs = [(c[1], c[0]) for c in r[0].get("geometry", {}).get("coordinates", [])]
        apart = [p for p in ours if not theirs or min(dist(p, t) for t in theirs) > 20]
        print(f"{name}: our walk {len(ours)} points, {len(apart)} more than 20 m from the router's")
        sample = apart[:: max(1, len(apart) // 12)][:12]
        if not sample:
            continue
        parts = "".join(f"way(around:6,{p[0]:.6f},{p[1]:.6f})[highway];node(around:6,{p[0]:.6f},{p[1]:.6f})[barrier];" for p in sample)
        tags = get(OVERPASS, urllib.parse.urlencode({"data": f"[out:json][timeout:25];({parts});out tags qt;"}).encode())
        if tags is None:
            print("  Overpass didn't answer")
            continue
        for e in json.loads(tags).get("elements", []):
            t = e.get("tags", {})
            print(f"  {e['type']} {e['id']}: " + " ".join(f"{k}={v}" for k, v in sorted(t.items()) if not k.startswith("name:")))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stops")
    ap.add_argument("--out")
    ap.add_argument("--explain")
    a = ap.parse_args()
    if a.explain:
        if os.path.exists(a.explain):
            explain(a.explain)
        return 0
    stops = near_stops(a.stops)
    coords = ";".join(f"{p[1]:.6f},{p[0]:.6f}" for p in [HOSPITAL] + [s[3] for s in stops])
    reply = get(f"{ROUTER}{coords}?sources=0&annotations=distance")
    if reply is None or json.loads(reply).get("code") != "Ok":
        print(f"no reference walks: {reply[:300] if reply else 'no answer'}")
        return 1
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "router.json"), "w", encoding="utf-8") as f:
        f.write(reply)
    with open(os.path.join(a.out, "router-stops.tsv"), "w", encoding="utf-8") as f:
        for _, code, name, p in stops:
            f.write(f"{code}\t{name}\t{p[0]}\t{p[1]}\n")
    print(f"reference walks to {len(stops)} stops near the hospital")
    print("ways the app adds over a plain filter (trunk roads with footpaths, areas):")
    report_added()
    return 0


if __name__ == "__main__":
    sys.exit(main())
