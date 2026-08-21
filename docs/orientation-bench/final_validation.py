#!/usr/bin/env python3
"""Final offline regression replicating the NEW Kotlin decision path exactly."""
import json
from pathlib import Path

import numpy as np
from PIL import Image
from rapidocr_onnxruntime import RapidOCR

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
ocr = RapidOCR()
MIN_MEAN_CONFIDENCE = 0.75
BOX_ASPECT_RATIO = 1.4
MIN_VERTICAL_MASS = 3.0


def select_orientation_class(crops):
    best = max(crops, key=max)
    return max(range(4), key=lambda c: best[c]), best[max(range(4), key=lambda c: best[c])]


def select_consistent_proposal(scores_by_rot):
    mean_conf = 0.0
    aligned = []
    for rot in (0, 90, 180, 270):
        cls, conf = select_orientation_class(scores_by_rot[str(rot)])
        mean_conf += conf / 4
        aligned.append((cls - rot // 90) % 4)
    if len(set(aligned)) != 1 or mean_conf < MIN_MEAN_CONFIDENCE:
        return None
    oc = aligned[0]
    return None if oc == 0 else (360 - oc * 90) % 360


def ocr_evidence(img):
    """Kotlin-exact: per-element stats + verticalMass from non-horizontal lines."""
    result, _ = ocr(np.asarray(img))
    characters = elements = 0
    score = 0.0
    vmass = 0.0
    if result:
        for box, text, conf in result:
            # rapidocr gives one item per detected line; treat it as an element
            cjk = sum(1 for ch in text if '\u3400' <= ch <= '\u9fff' or '\u3040' <= ch <= '\u30ff')
            if cjk == 0:
                continue
            xs = [pt[0] for pt in box]; ys = [pt[1] for pt in box]
            ww = abs(max(xs) - min(xs))
            hh = abs(max(ys) - min(ys))
            vertical_dominant = hh >= ww * BOX_ASPECT_RATIO
            horizontal_dominant = ww >= hh * BOX_ASPECT_RATIO
            characters += cjk
            elements += 1
            score += cjk * float(conf)
            if not horizontal_dominant or vertical_dominant:
                vmass += cjk * float(conf)
    return {"chars": characters, "elements": elements, "score": score, "vmass": vmass}


raw = json.loads((HERE / "results" / "raw_scores.json").read_text())
print(f"{'image':<46} {'proposal':>9} {'guard':>7} {'final':>6}")
stats = {"landscape_final": 0, "portrait_rotated": 0}
for item in raw:
    proposal = select_consistent_proposal(item["scores"])
    if proposal is None:
        print(f"{item['path']:<46} {'-':>9}")
        continue
    img = Image.open(ROOT / item["path"]).convert("RGB")
    img.thumbnail((1400, 2000))
    ev = ocr_evidence(img)
    sufficient = ev["chars"] >= 6 and ev["elements"] >= 2
    if not sufficient:
        print(f"{item['path']:<46} {proposal:>9} {'ocr_rej':>7} {'0':>6}")
        continue
    guarded = ev["vmass"] >= MIN_VERTICAL_MASS
    final = 0 if guarded else proposal
    if "/portrait/" in item["path"] and final != 0:
        stats["portrait_rotated"] += 1
    if "/landscape/" in item["path"] and final != 0:
        stats["landscape_final"] += 1
    print(f"{item['path']:<46} {proposal:>9} {'GUARD' if guarded else 'pass':>7} {final:>6}")

print(stats)
