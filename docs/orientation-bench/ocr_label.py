#!/usr/bin/env python3
"""Determine true upright rotation per image via OCR score across 4 rotations.

RapidOCR (PaddleOCR models) detects text boxes with quad angles; we score each
rotation by summing confidence of boxes whose long axis is vertical (manga is
vertical-writing dominant). The upright page should have the most/strongest
vertical text; a rotated-away page has its text axis flipped to horizontal.
"""
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

def ocr_score(img: Image.Image):
    arr = np.asarray(img)
    result, _ = ocr(arr)
    if not result:
        return {"v_conf": 0.0, "h_conf": 0.0, "n": 0, "chars": 0}
    v_conf = h_conf = 0.0
    nv = nh = 0
    chars = 0
    for box, text, conf in result:
        (x0, y0), (x1, y1) = box[0], box[2]
        w, h = abs(x1 - x0), abs(y1 - y0)
        if h >= w * 1.4:      # vertical text line
            v_conf += float(conf)
            nv += 1
        elif w >= h * 1.4:    # horizontal text line
            h_conf += float(conf)
            nh += 1
        chars += len(text)
    return {"v_conf": round(v_conf, 2), "h_conf": round(h_conf, 2),
            "n_v": nv, "n_h": nh, "chars": chars}

files = sorted((ROOT / "test_pic" / "landscape").iterdir())
files += [ROOT / "test_pic" / f"{n}.jpg" for n in range(3, 10)]
out = {}
for p in files:
    im = Image.open(p).convert("RGB")
    im.thumbnail((1400, 2000))
    scores = {}
    for deg, method in METHODS.items():
        r = im if method is None else im.transpose(method)
        scores[deg] = ocr_score(r)
    out[p.name] = scores
    best = max(scores, key=lambda d: scores[d]["v_conf"] * 2 + scores[d]["h_conf"])
    print(p.name, {d: (s["v_conf"], s["h_conf"]) for d, s in scores.items()}, "-> upright:", best)
    sys.stdout.flush()

(HERE / "results" / "ocr_rotation_scores.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
