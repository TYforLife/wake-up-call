#!/usr/bin/env python3
"""Makes the Pūkeko alarm's recordings (app/src/main/res/raw/pukeko_*.pcm) from the owner's mp3s.

    python3 tools/pukeko_sounds.py BED.mp3 CHIRP1.mp3 CHIRP2.mp3 CHIRP3.mp3

Each becomes the format `Sounds.decode` reads ("NODSND1\\n", then little-endian 16-bit mono at 44.1 kHz), peak at
90%. The bed (a ~20 s morning recording) is crossfaded end into start so it loops without a seam. Each chirp (one
~1.5 s scream) is trimmed to just before its onset and faded at both ends, so it can land anywhere without a click.
The app mixes them at ring time (`Sounds.pukeko`, DESIGN §5). Needs ffmpeg and numpy.
"""
import subprocess
import sys
from pathlib import Path

import numpy as np

RATE = 44_100
OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/raw"


def load(path: str) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"],
        check=True, capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0


def peak90(x: np.ndarray) -> np.ndarray:
    return x * (0.9 / np.max(np.abs(x)))


def write(name: str, x: np.ndarray) -> None:
    pcm = np.clip(np.round(x * 32767), -32768, 32767).astype("<i2")
    (OUT / f"{name}.pcm").write_bytes(b"NODSND1\n" + pcm.tobytes())
    print(f"{name}: {len(pcm) / RATE:.2f} s")


def loop_ready(bed: np.ndarray, fade_s: float = 0.5) -> np.ndarray:
    """The last [fade_s] folded over the first with an equal-power crossfade: the end flows into the start."""
    n = int(RATE * fade_s)
    t = np.linspace(0.0, np.pi / 2, n)
    out = bed[: len(bed) - n].copy()
    out[:n] = bed[:n] * np.sin(t) + bed[len(bed) - n:] * np.cos(t)
    return out


def chirp(x: np.ndarray) -> np.ndarray:
    """From 40 ms before the scream starts to the end, with a 15 ms fade in and a 120 ms fade out."""
    env = np.convolve(np.abs(x), np.ones(441) / 441, mode="same")
    onset = int(np.argmax(env > 0.25 * env.max()))
    x = x[max(0, onset - int(RATE * 0.04)):].copy()
    fi, fo = int(RATE * 0.015), int(RATE * 0.12)
    x[:fi] *= np.linspace(0.0, 1.0, fi)
    x[-fo:] *= np.linspace(1.0, 0.0, fo)
    return x


def main(bed: str, *chirps: str) -> None:
    write("pukeko_bed", peak90(loop_ready(load(bed))))
    for i, c in enumerate(chirps, 1):
        write(f"pukeko_chirp{i}", peak90(chirp(load(c))))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
