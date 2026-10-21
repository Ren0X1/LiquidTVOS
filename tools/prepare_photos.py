#!/usr/bin/env python3
"""Turn the downloaded photos into launcher assets.

  tools/prepare_photos.py

Reads photos/raw/chosen.json and photos/raw/NN.jpg, writes to
launcher/assets/photos/: NN.jpg (1920x1080) and NN_blur.jpg (480x270,
pre-blurred for the glass surfaces), plus index.json with place and credit.

In chosen.json, "focus_y" (0 top .. 1 bottom, default 0.5) picks which band
of a taller photo survives the 16:9 crop.
"""
import json
from pathlib import Path

from PIL import Image, ImageEnhance, ImageFilter, ImageOps

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "photos" / "raw"
OUT = ROOT / "launcher" / "assets" / "photos"
W, H = 1920, 1080


def crop_16_9(im, focus_y):
    w, h = im.size
    if w / h > W / H:  # too wide: trim the sides
        nw = round(h * W / H)
        x = (w - nw) // 2
        return im.crop((x, 0, x + nw, h))
    nh = round(w * H / W)
    y = round((h - nh) * focus_y)
    return im.crop((0, y, w, y + nh))


def short_author(s):
    s = " ".join(s.split())
    for cut in (" (", ", ", " / "):
        if cut in s and len(s) > 28:
            s = s.split(cut)[0]
    return s[:40]


def main():
    chosen = json.loads((RAW / "chosen.json").read_text(encoding="utf-8"))
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*"):
        old.unlink()
    index = []
    for i, c in enumerate(chosen):
        im = ImageOps.exif_transpose(Image.open(RAW / f"{i:02d}.jpg")).convert("RGB")
        im = crop_16_9(im, c.get("focus_y", 0.5)).resize((W, H), Image.LANCZOS)
        name = f"{i:02d}.jpg"
        im.save(OUT / name, quality=80, optimize=True, progressive=False)
        small = im.resize((W // 4, H // 4), Image.LANCZOS).filter(ImageFilter.GaussianBlur(9))
        small = ImageEnhance.Color(small).enhance(1.25)
        blur = f"{i:02d}_blur.jpg"
        small.save(OUT / blur, quality=88)
        lic = c.get("license", "")
        index.append({
            "file": name, "blur": blur, "place": c["place"], "city": c["city"],
            "author": short_author(c.get("author", "")),
            "license": lic,
        })
        print(name, c["place"], (OUT / name).stat().st_size // 1024, "KB")
    (OUT / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=1), encoding="utf-8")
    total = sum(p.stat().st_size for p in OUT.glob("*")) // 1024
    print(f"{len(index)} photos, {total} KB")


if __name__ == "__main__":
    main()
