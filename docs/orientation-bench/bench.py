#!/usr/bin/env python3
"""Desktop benchmark replicating PageOrientationDetector.kt preprocessing exactly.

Dumps raw model scores for every (pixel rotation x crop x class) so aggregation
strategies can be compared offline without re-running inference.
"""
import json
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent.parent  # repo root (docs/orientation-bench/)
MODEL = ROOT / "app/src/main/assets/models/page_orientation.onnx"
OUT = Path(__file__).resolve().parent / "results"
OUT.mkdir(exist_ok=True)

PREVIEW_MAX_DIMENSION = 1600
RESIZE_SHORT_SIDE = 256
INPUT_SIZE = 224
CROP_COUNT = 5
QUARTER_TURNS = [0, 90, 180, 270]

MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)


def android_in_sample_size(width: int, height: int) -> int:
    """BitmapFactory inSampleSize as computed by decodePreview()."""
    return max(1, max(width, height) // PREVIEW_MAX_DIMENSION)


def android_decode(path: Path) -> Image.Image:
    """Replicate BitmapFactory decodeStream with inSampleSize (power-of-2 subsample)."""
    with Image.open(path) as im:
        width, height = im.size
        sample = android_in_sample_size(width, height)
        if sample > 1:
            width, height = width // sample, height // sample
        im.load()
        return im.convert("RGB").resize((width, height), Image.BILINEAR)


def downscale_with_prefilter(img: Image.Image, target_w: int, target_h: int) -> Image.Image:
    """Repeated halving exactly like PageOrientationDetector.downscaleWithPrefilter."""
    current = img
    while current.width // 2 >= target_w and current.height // 2 >= target_h:
        current = current.resize((current.width // 2, current.height // 2), Image.BILINEAR)
    if current.width != target_w or current.height != target_h:
        current = current.resize((target_w, target_h), Image.BILINEAR)
    return current


def android_rotate(img: Image.Image, degrees: int) -> Image.Image:
    """Matrix.postRotate(degrees) rotates CLOCKWISE; PIL ROTATE_k is counter-clockwise."""
    method = [
        None,  # identity
        Image.Transpose.ROTATE_270,  # clockwise 90
        Image.Transpose.ROTATE_180,
        Image.Transpose.ROTATE_90,  # clockwise 270
    ][degrees // 90]
    return img if method is None else img.transpose(method)


def crop_box(scaled: Image.Image, index: int) -> tuple[int, int]:
    horizontal = scaled.width > scaled.height
    travel = (scaled.width if horizontal else scaled.height) - INPUT_SIZE
    offset = round(travel * index / (CROP_COUNT - 1))
    left = offset if horizontal else (scaled.width - INPUT_SIZE) // 2
    top = (scaled.height - INPUT_SIZE) // 2 if horizontal else offset
    return left, top


def to_nchw(crop: Image.Image) -> np.ndarray:
    arr = np.asarray(crop, dtype=np.float32) / 255.0  # HWC RGB
    arr = (arr - MEAN) / STD
    return arr.transpose(2, 0, 1)[None]  # 1CHW


def main() -> None:
    session = ort.InferenceSession(str(MODEL), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name

    targets = []
    for sub in ("landscape", "portrait"):
        for p in sorted((ROOT / "test_pic" / sub).iterdir()):
            if p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}:
                targets.append(p)
    for n in range(3, 10):
        targets.append(ROOT / "test_pic" / f"{n}.jpg")

    report = []
    for path in targets:
        t0 = time.perf_counter()
        bitmap = android_decode(path)
        scale = RESIZE_SHORT_SIDE / min(bitmap.width, bitmap.height)
        scaled = downscale_with_prefilter(
            bitmap, round(bitmap.width * scale), round(bitmap.height * scale)
        )

        crops_by_rotation = {}
        for rotation in QUARTER_TURNS:
            rotated = scaled if rotation == 0 else android_rotate(scaled, rotation)
            inputs = np.concatenate(
                [
                    to_nchw(rotated.crop((left, top, left + INPUT_SIZE, top + INPUT_SIZE)))
                    for i in range(CROP_COUNT)
                    for left, top in [crop_box(rotated, i)]
                ]
            )
            logits = session.run(None, {input_name: inputs})[0]  # [5,4]
            crops_by_rotation[str(rotation)] = logits.tolist()

        elapsed_ms = (time.perf_counter() - t0) * 1000
        report.append(
            {
                "path": str(path.relative_to(ROOT)),
                "size": [bitmap.width, bitmap.height],
                "elapsed_ms": round(elapsed_ms, 1),
                "scores": crops_by_rotation,
            }
        )
        print(f"{path.relative_to(ROOT)}: {elapsed_ms:.0f}ms", file=sys.stderr)

    (OUT / "raw_scores.json").write_text(json.dumps(report, ensure_ascii=False, indent=1))
    print(f"wrote {OUT / 'raw_scores.json'} ({len(report)} images)")


if __name__ == "__main__":
    main()
