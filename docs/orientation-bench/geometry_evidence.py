#!/usr/bin/env python3
"""Collect text-line geometry evidence (vertical vs horizontal mass) at 0 deg."""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from rapidocr_onnxruntime import RapidOCR

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
ocr = RapidOCR()

files = []
for sub in ("landscape", "portrait"):
    files += sorted((ROOT / "test_pic" / sub).iterdir())
files += [ROOT / "test_pic" / f"{n}.jpg" for n in range(3, 10)]

out = {}
for p in files:
    im = Image.open(p).convert("RGB")
    im.thumbnail((1400, 2000))
    result, _ = ocr(np.asarray(im))
    h_conf = v_conf = 0.0
    n_h = n_v = 0
    chars = 0
    elements = 0
    if result:
        for box, text, conf in result:
            cjk = sum(1 for ch in text if '\u3400' <= ch <= '\u9fff' or '\u3040' <= ch <= '\u30ff')
            if cjk == 0:
                continue
            chars += cjk
            elements += 1
            (x0, y0), (x1, y1) = box[0], box[2]
            w, h = abs(x1 - x0), abs(y1 - y0)
            if w >= h * 1.4:
                h_conf += float(conf); n_h += 1
            elif h >= w * 1.4:
                v_conf += float(conf); n_v += 1
    out[str(p.relative_to(ROOT))] = {
        "h_conf": round(h_conf, 2), "v_conf": round(v_conf, 2),
        "n_h": n_h, "n_v": n_v, "cjk_chars": chars, "elements": elements,
    }
    print(f"{p.relative_to(ROOT)}: H={h_conf:.1f}({n_h}) V={v_conf:.1f}({n_v}) chars={chars} el={elements}")
    sys.stdout.flush()

(HERE / "results" / "geometry_evidence.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
