"""Prepare the exclusive artist icon set for the Android launcher.

Source art: 1024x1024 white marks on an opaque black background.

What this script does:
  * turns black into transparency (alpha from luminance, soft edge kept),
  * trims the empty margin and re-centres the mark,
  * fits it into the 66-72% safe zone of an adaptive icon,
  * writes 108dp@4x (432 px) PNGs to app/src/main/res/drawable-xxxhdpi
    (bitmaps are scaled by the system, unlike vectors they keep every spike),
  * keeps a copy of the originals in promo/artist-icons for future re-runs.

Usage:
    py -3 promo/artist-icons/build.py --src "D:\\ishodniki"
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
from pathlib import Path

from PIL import Image, ImageChops

FIRST_INDEX = 11          # Icon11 .. Icon21 in the manifest
CANVAS = 432              # 108 dp at xxxhdpi
SAFE = 0.70               # share of the canvas the mark may occupy
LOW_CUT = 10              # luminance below this is background
HIGH_CUT = 250            # luminance at/above this is fully opaque


def numeric_key(path: Path) -> int:
    match = re.search(r"(\d+)", path.stem)
    return int(match.group(1)) if match else 0


def to_alpha(img: Image.Image) -> Image.Image:
    """White mark + alpha taken from the (grey) source luminance."""
    grey = img.convert("RGB")
    # The art is monochrome; max() over the channels survives a stray
    # coloured pixel and gives the same result as plain luminance.
    r, g, b = grey.split()
    luminance = ImageChops.lighter(ImageChops.lighter(r, g), b)

    alpha = luminance.point(
        lambda v: 0 if v <= LOW_CUT else min(255, int((v - LOW_CUT) * 255 / (HIGH_CUT - LOW_CUT)))
    )
    out = Image.new("RGBA", grey.size, (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def fit_canvas(mark: Image.Image) -> Image.Image:
    bbox = mark.getchannel("A").getbbox()
    if bbox:
        mark = mark.crop(bbox)
    target = int(CANVAS * SAFE)
    scale = min(target / mark.width, target / mark.height)
    size = (max(1, round(mark.width * scale)), max(1, round(mark.height * scale)))
    mark = mark.resize(size, Image.LANCZOS)
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (255, 255, 255, 0))
    canvas.alpha_composite(mark, ((CANVAS - size[0]) // 2, (CANVAS - size[1]) // 2))
    return canvas


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--src", default=r"D:\ishodniki")
    parser.add_argument("--res", default="app/src/main/res")
    parser.add_argument("--promo", default="promo/artist-icons")
    args = parser.parse_args()

    src = Path(args.src)
    files = sorted([p for p in src.iterdir() if p.suffix.lower() == ".png"], key=numeric_key)
    if not files:
        raise SystemExit(f"no PNG files in {src}")

    res_dir = Path(args.res) / "drawable-xxxhdpi"
    res_dir.mkdir(parents=True, exist_ok=True)
    promo = Path(args.promo)
    promo.mkdir(parents=True, exist_ok=True)

    for offset, file in enumerate(files):
        index = FIRST_INDEX + offset
        with Image.open(file) as img:
            mark = fit_canvas(to_alpha(img.convert("RGB")))
        name = f"ic_onyx_{index:02d}.png"
        mark.save(res_dir / name, optimize=True)
        shutil.copyfile(file, promo / f"{offset + 1:02d}.png")
        print(f"{file.name} -> {res_dir / name}")

    print(f"\n{len(files)} icons written; first index {FIRST_INDEX}, last {FIRST_INDEX + len(files) - 1}")


if __name__ == "__main__":
    main()
