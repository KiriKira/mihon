# Orientation offline benchmark

Desktop replication of `PageOrientationDetector` preprocessing for offline
evaluation. Requires no Android SDK; runs against `test_pic/`.

```bash
uv venv .bench-venv --python 3.11
uv pip install --python .bench-venv/bin/python onnxruntime pillow numpy rapidocr-onnxruntime

.bench-venv/bin/python bench.py              # dump raw scores -> results/raw_scores.json
.bench-venv/bin/python evaluate.py           # compare aggregation strategies
.bench-venv/bin/python final_validation.py   # replicate the shipped decision path end-to-end
```

Scripts and their verdicts (2026-08-21 round):

- `bench.py` – exact Android preprocessing (inSampleSize decode, halving
  prefilter, clockwise rotations, 5 long-axis crops), dumps every
  rotation x crop x class score.
- `evaluate.py` – aggregation ablations (best-crop / median / top-k / majority).
  Result: aggregators cannot fix unanimous-wrong pages nor recover
  threshold-killed landscape pages.
- `geometry_evidence.py` – per-page vertical/horizontal OCR mass at 0 deg;
  basis of the geometry guard.
- `final_validation.py` – RapidOCR stand-in for ML Kit; reproduces the
  shipped classifier gate + OCR gate + geometry guard decisions.
- `ocr_label.py`, `ocr_polarity.py`, `ink_polarity.py` – dead ends kept as
  record: recognition quality and ink projections are rotation-symmetric for
  CJK, so neither can label upright polarity objectively.
- `label_with_gemini.py`, `label_with_mimo.py` – VLM labeling attempts;
  vision models were inconsistent across presentations (see
  docs/page-orientation.md).

`results/` holds dumps and contact sheets; it is local-only and not committed.
