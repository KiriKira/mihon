#!/usr/bin/env python3
"""Objective per-file upright verdict via ink-projection glyph analysis.

For each rotation r in {0, 90} of the stored image:
  - OCR the page; keep long reads (>=6 chars, conf >= 0.85)
  - For each kept box: WIDE or TALL; count glyph clusters along x and y via ink projection
  - Horizontal-upright text => WIDE box, clusters_x ~= len(text), clusters_y == 1
  - Vertical-upright text   => TALL box, clusters_y ~= len(text), clusters_x == 1
Score(r) = number of kept boxes whose geometry is self-consistent at rotation r.
The rotation with the higher score is the upright viewing orientation.
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from rapidocr_onnxruntime import RapidOCR

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
METHODS = {0: None, 90: Image.Transpose.ROTATE_270}
ocr = RapidOCR()


def glyph_clusters(ink_profile: np.ndarray) -> int:
    active = ink_profile > max(1.0, ink_profile.mean() * 0.25)
    n = 0
    prev = False
    for a in active:
        if a and not prev:
            n += 1
        prev = a
    return n


def analyze_rotation(img: Image.Image):
    result, _ = ocr(np.asarray(img))
    if not result:
        return 0, 0, []
    arr = np.asarray(img.convert("L"))
    consistent = 0
    total_long = 0
    details = []
    for box, text, conf in result:
        if len(text) < 6 or float(conf) < 0.85:
            continue
        total_long += 1
        xs = [p[0] for p in box]
        ys = [p[1] for p in box]
        x0, y0, x1, y1 = int(min(xs)), int(min(ys)), int(max(xs)), int(max(ys))
        w, h = x1 - x0, y1 - y0
        crop = 255.0 - arr[max(0, y0):y1, max(0, x0):x1]
        if crop.size == 0:
            continue
        ink = (crop > 100).astype(float)
        cx = glyph_clusters(ink.sum(axis=0))
        cy = glyph_clusters(ink.sum(axis=1))
        n_glyphs = len(text)
        # horizontal upright: glyphs side-by-side (cx close to n, cy==1..2)
        horiz_ok = w > h * 1.4 and cx >= max(3, n_glyphs * 0.55) and cy <= 2
        # vertical upright: glyphs stacked (cy close to n, cx==1..2)
        vert_ok = h > w * 1.4 and cy >= max(3, n_glyphs * 0.55) and cx <= 2
        if horiz_ok or vert_ok:
            consistent += 1
            details.append(f"'{text}'({'H' if horiz_ok else 'V'} cx={cx},cy={cy})")
    return consistent, total_long, details


files = sorted((ROOT / "test_pic" / "landscape").iterdir())
files += [ROOT / "test_pic" / f"{n}.jpg" for n in range(3, 10)]
out = {}
for p in files:
    im = Image.open(p).convert("RGB")
    im.thumbnail((1400, 2000))
    scores = {}
    detail_str = {}
    for deg, method in METHODS.items():
        r = im if method is None else im.transpose(method)
        c, t, det = analyze_rotation(r)
        scores[str(deg)] = {"consistent": c, "long_reads": t}
        detail_str[str(deg)] = det[:3]
    v0, v90 = scores["0"]["consistent"], scores["90"]["consistent"]
    verdict = "UPRIGHT_AS_IS" if v0 > v90 else ("NEEDS_90CW" if v90 > v0 else "TIE")
    out[p.name] = {"scores": scores, "details": detail_str, "verdict": verdict}
    print(f"{p.name}: rot0={v0}/{scores['0']['long_reads']} rot90={v90}/{scores['90']['long_reads']} -> {verdict}")
    sys.stdout.flush()

(HERE / "results" / "ink_polarity.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
