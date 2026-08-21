#!/usr/bin/env python3
"""Plan-C style polarity labeling: for every detected text box, try all four
crop rotations through the recognizer; the page rotation whose boxes read best
(most valid CJK chars x confidence) is the upright one."""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from rapidocr_onnxruntime import RapidOCR

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
METHODS = {0: None, 90: Image.Transpose.ROTATE_270, 180: Image.Transpose.ROTATE_180, 270: Image.Transpose.ROTATE_90}

ocr = RapidOCR()

def cjk_count(s: str) -> int:
    n = 0
    for ch in s:
        o = ord(ch)
        if (0x4E00 <= o <= 0x9FFF or 0x3400 <= o <= 0x4DBF or
            0x3040 <= o <= 0x309F or 0x30A0 <= o <= 0x30FF or
            0xF900 <= o <= 0xFAFF):
            n += 1
    return n

def page_score(img: Image.Image):
    result, _ = ocr(np.asarray(img))
    if not result:
        return 0.0, 0
    boxes = sorted(result, key=lambda r: -abs(
        (r[0][2][0]-r[0][0][0]) * (r[0][2][1]-r[0][0][1])))[:6]
    total = 0.0
    used = 0
    for box, _text, _conf in boxes:
        xs = [pt[0] for pt in box]; ys = [pt[1] for pt in box]
        crop = img.crop((max(0,min(xs)-2), max(0,min(ys)-2),
                         min(img.width,max(xs)+2), min(img.height,max(ys)+2)))
        if crop.width < 12 or crop.height < 12:
            continue
        best = 0.0
        for method in METHODS.values():
            c = crop if method is None else crop.transpose(method)
            if min(c.size) < 12:
                continue
            r2 = ocr(np.asarray(c))
            elist = r2[0] if isinstance(r2, tuple) else r2
            if elist:
                for item in elist:
                    t, cf = item[1], item[2]
                    cand = cjk_count(t) * float(cf)
                    best = max(best, cand)
        total += best
        used += 1
    return round(total, 1), used

files = sorted((ROOT / "test_pic" / "landscape").iterdir())
files += [ROOT / "test_pic" / f"{n}.jpg" for n in range(3, 10)]
out = {}
for p in files:
    im = Image.open(p).convert("RGB")
    im.thumbnail((1000, 1400))
    scores = {}
    for deg, method in METHODS.items():
        r = im if method is None else im.transpose(method)
        s, nb = page_score(r)
        scores[str(deg)] = {"score": s, "boxes": nb}
    best_deg = max(scores, key=lambda d: scores[d]["score"])
    out[p.name] = scores
    (HERE / "results" / "ocr_polarity_scores.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
    ranked = sorted(scores.items(), key=lambda kv: -kv[1]["score"])
    print(f"{p.name}: " + " ".join(f"r{d}={v['score']}" for d, v in scores.items())
          + f" -> upright={best_deg} (2nd={ranked[1][0]}:{ranked[1][1]['score']})")
    sys.stdout.flush()

(HERE / "results" / "ocr_polarity_scores.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
