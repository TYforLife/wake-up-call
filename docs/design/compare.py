"""Mockup next to the app's screenshot, for every screen of the v2 design (PLAN Runs 8 and 9).

Usage:
    python3 docs/design/compare.py [SNAPSHOTS_DIR] [OUT_DIR]

SNAPSHOTS_DIR defaults to app/src/test/snapshots (what `recordRoborazziDebug -Pscreenshots` writes);
OUT_DIR defaults to build/compare. For each screen id in docs/design/screens.json it pairs
docs/design/screens/<id>.png with <SNAPSHOTS_DIR>/v2_<id>.png and writes <OUT_DIR>/compare_<id>.png: the mockup
on the left, the app on the right, at the same height. Also writes <OUT_DIR>/index.md with a table of
which screens are built, and exits 0 either way (it reports; CI decides). Needs Pillow.
"""
import json
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = pathlib.Path(__file__).resolve().parents[2]
DESIGN = ROOT / "docs" / "design"
GAP = 48
LABEL_H = 72


def side_by_side(mock: Image.Image, shot: Image.Image | None) -> Image.Image:
    h = mock.height
    shot_img = shot.resize((round(shot.width * h / shot.height), h)) if shot else None
    w2 = shot_img.width if shot_img else mock.width
    out = Image.new("RGB", (mock.width + GAP + w2, h + LABEL_H), (233, 233, 233))
    draw = ImageDraw.Draw(out)
    try:
        font = ImageFont.load_default(size=36)
    except TypeError:  # Pillow < 10.1
        font = ImageFont.load_default()
    draw.text((8, 16), "Design", fill=(17, 17, 17), font=font)
    draw.text((mock.width + GAP + 8, 16), "App" if shot_img else "App: not built yet", fill=(17, 17, 17), font=font)
    out.paste(mock.convert("RGB"), (0, LABEL_H))
    if shot_img:
        out.paste(shot_img.convert("RGB"), (mock.width + GAP, LABEL_H))
    else:
        draw.rectangle([mock.width + GAP, LABEL_H, mock.width + GAP + w2 - 1, LABEL_H + h - 1], outline=(180, 180, 180), width=4)
    return out


def main() -> int:
    snaps = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "app" / "src" / "test" / "snapshots"
    out_dir = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "build" / "compare"
    out_dir.mkdir(parents=True, exist_ok=True)
    screens = json.loads((DESIGN / "screens.json").read_text())
    rows = ["| Screen | Built |", "| --- | --- |"]
    built = 0
    for s in screens:
        mock_path = DESIGN / "screens" / f"{s['id']}.png"
        shot_path = snaps / f"v2_{s['id']}.png"
        shot = Image.open(shot_path) if shot_path.exists() else None
        built += shot is not None
        side_by_side(Image.open(mock_path), shot).save(out_dir / f"compare_{s['id']}.png")
        rows.append(f"| {s['title']} | {'yes' if shot else 'not yet'} |")
    (out_dir / "index.md").write_text("\n".join(rows) + f"\n\n{built} of {len(screens)} screens built.\n")
    print(f"{built} of {len(screens)} screens built; comparisons in {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
