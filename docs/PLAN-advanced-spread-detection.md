# PLAN: Learned double-page spread classification

## Status

This supersedes the previous enhanced seam-continuity heuristic plan and implementation.

The existing `pref_dual_page_advanced_spread_detection` reader switch is retained as the opt-in gate, but the current enhanced heuristic implementation (`GutterRun`, `ContinuityStats`, activity/correlation/local-seam thresholds) should be removed rather than tuned further.

The legacy detector remains untouched for compatibility when the enhanced switch is off.

## Problem statement

The original "Skip splitting double-page spreads" detector is intentionally simple: it scans a narrow center band and treats a nearly uniform pure-white/pure-black center column as evidence that the image is two stitched pages.

That assumption is useful for many scans, but the reported false-split samples demonstrate a fundamental ambiguity:

- real double-page artwork can contain a physical scan gutter;
- the gutter can be pure white/black and several pixels wide;
- artwork may be locally discontinuous near the fold because of speech balloons, deliberate whitespace, scan loss, page curvature, or asymmetric composition;
- therefore gutter width, row-density correlation, near-seam correlation, and similar local metrics cannot reliably distinguish "one composition with a fold" from "two independent pages".

The enhanced feature should therefore solve the actual classification problem:

> Is this landscape image a single intentional double-page composition, or two independent pages stitched side-by-side?

This is a global visual/layout classification problem, not a center-line detection problem.

## Compatibility contract

### Enhanced switch OFF

Behavior must remain identical to current Mihon:

1. Wide-page splitting is enabled.
2. `pref_dual_page_split_skip_spread` controls the existing legacy detector.
3. The original `DoublePageSpreadDetector` result is used unchanged.
4. No model is loaded and no extra inference cost is paid.

### Enhanced switch ON

The learned classifier becomes the enhanced decision layer for landscape pages.

The classifier uses a confidence band rather than forcing every image into one class:

- high-confidence `SPREAD` -> keep the image whole;
- high-confidence `STITCHED` -> allow splitting;
- uncertain -> fall back to the unchanged legacy detector.

This is deliberately safer than making the model authoritative for uncertain samples and also allows the enhanced mode to improve both kinds of legacy error.

Initial decision policy:

```text
pSpread >= spread_keep_threshold
    => SPREAD

pSpread <= stitched_split_threshold
    => STITCHED

otherwise
    => LEGACY_FALLBACK
```

Initial thresholds for development:

- `spread_keep_threshold = 0.80`
- `stitched_split_threshold = 0.20`

These are placeholders. Final thresholds must be selected from validation data and calibration results, not tuned against individual reported pages.

## Remove the current enhanced heuristic implementation

Delete the current enhanced-only code paths instead of keeping two competing advanced systems:

- `GutterRun`
- `ContinuityStats`
- `analyzeCrossGutterContinuity`
- `findNearSeamLuminanceCorrelation`
- `findLocalSeamSupportRatio`
- enhanced activity/correlation threshold logic
- tests that exist only to lock those thresholds

Keep only the original legacy detector and its pre-existing tests.

The existing enhanced reader preference may be reused so users do not need a new setting or migration.

## Model baseline

Start with the simplest model likely to solve the actual problem before introducing a multi-branch architecture.

### Baseline A: single global view

- architecture: MobileNetV3-Small or an equivalently small mobile CNN;
- transfer learning from ImageNet weights;
- input: RGB landscape image resized to `384 x 256`;
- output: one calibrated scalar `pSpread` in `[0, 1]`;
- export: ONNX;
- deployment: `onnxruntime-android`, which the app already uses.

Do not crop the center out of the global view. The objective is to learn composition, page layout, subjects, perspective, panel structure, whitespace, and the relationship between both halves.

### Candidate B: global + seam view

Only build this if the single-view baseline fails the validation target.

Candidate B adds a second narrow center crop showing the suspected fold at higher effective resolution:

- global branch: `384 x 256`;
- seam branch: approximately `128 x 256`;
- concatenate embeddings before the final classifier.

Candidate B is accepted only if it gives a meaningful improvement on held-out series while staying within the size/latency budget.

The project should not begin with this added complexity.

## Dataset design

Model quality depends more on dataset construction than on architecture.

### Labels

Two classes:

- `SPREAD`: one intentional visual composition spanning both pages.
- `STITCHED`: two independent pages stored side-by-side.

Ambiguous samples should not be forced into the training set. They may be retained for later analysis.

### Positive samples

Collect genuine known double-page spreads.

High-value positives include:

- artwork that visibly crosses the center;
- spreads with speech balloons or large whitespace near the center;
- scanned folds that erase/occlude center content;
- color full-bleed spreads;
- asymmetric compositions where the two halves do not locally match;
- spreads where nothing literally crosses the gutter but the full layout is one composition.

The five user-reported false-split pages committed under `docs/spread-classifier/regression-samples/` are hard-positive regression samples.

### Positive gutter augmentation

For each genuine spread, generate variants that simulate physical book scanning:

- insert white, black, gray, or tinted center gaps;
- gap width sampled over a realistic range;
- remove a narrow vertical strip before inserting the gap to simulate content lost in the binding;
- add asymmetric fold shadows/highlights;
- apply small left/right vertical offsets;
- light perspective/skew;
- blur/downsampling;
- JPEG/WebP compression;
- grayscale conversion;
- brightness/contrast variation.

This explicitly teaches the model that a clean center gutter is not sufficient evidence of two independent pages.

### Negative samples

Construct stitched-page examples from ordinary portrait pages.

Prefer difficult negatives:

- choose both pages from the same series;
- preferably the same volume/chapter;
- include adjacent page pairs;
- include non-adjacent pairs with similar tone/layout;
- join them with realistic white/black/gray gutters and fold shadows;
- vary margin cropping and page-edge loss.

This prevents the classifier from cheating through differences in art style.

### Hard-negative mining

After each training round:

1. run the model over a large pool of synthetic/real stitched pages;
2. collect false `SPREAD` predictions;
3. add them to the next training round with increased sampling probability.

Important hard negatives include:

- two text-heavy pages with aligned balloons;
- two dense full-bleed pages;
- pages with similar horizontal backgrounds;
- unrelated halves whose luminance/edge statistics happen to correlate;
- two pages that visually form a plausible panorama by accident.

### Hard-positive mining

Likewise collect real spreads incorrectly classified as `STITCHED`.

Do not solve them by adding runtime special cases. Add them to the dataset and retrain.

## Dataset split rules

Do not randomly split individual pages from the same work across train/validation/test.

Split by series or at minimum by volume so near-duplicate art and layouts cannot leak between sets.

Recommended:

- train: ~80% of series;
- validation: ~10% of series;
- test: ~10% of series.

Maintain a small separate `reported-regressions` set containing the user-provided problem samples. This set is a release gate, not the primary metric used to choose thresholds.

## Evaluation

Track at least:

- ROC-AUC;
- PR-AUC;
- confusion matrix;
- `SPREAD` precision/recall;
- `STITCHED` precision/recall;
- fallback rate under the two-threshold policy;
- results grouped by monochrome/color and gutter/no-gutter.

Because a false `SPREAD` decision can prevent a genuinely stitched pair from being split, production thresholds should prioritize precision at both confident ends.

Threshold selection:

1. calibrate output probabilities on the validation set, initially with temperature scaling;
2. select `spread_keep_threshold` such that confident `SPREAD` precision reaches the chosen safety target (initial target: >= 98%);
3. select `stitched_split_threshold` such that confident `STITCHED` precision reaches the chosen safety target (initial target: >= 98%);
4. route the middle confidence region to the legacy detector.

Do not tune thresholds page-by-page.

## Regression samples in this repository

Directory:

`docs/spread-classifier/regression-samples/`

Expected label for all currently committed examples: `SPREAD`.

The repository versions are downsampled preview/regression copies to limit repository growth while preserving the layout and center-gutter failure mode. Their manifest records source hashes so the exact user-supplied originals can be distinguished from the committed derivatives.

The first five cases cover:

1. dynamic action / explosion with a clean center gap;
2. dialogue/action composition with a clean center gap;
3. dense color full-bleed composition;
4. large whitespace and asymmetric character composition;
5. strongly asymmetric panel/layout composition where local seam similarity is weak.

These files should be used as mandatory regression checks for every candidate model.

## Training tooling

Add an offline tooling directory, for example:

```text
tools/spread-classifier/
  README.md
  requirements.txt
  dataset.py
  augment.py
  train.py
  evaluate.py
  export_onnx.py
  calibrate.py
```

Training must not be part of the Android Gradle build.

`evaluate.py` should accept a manifest so local datasets can remain outside the repository.

The committed regression fixtures should be evaluable with a single command.

Example desired workflow:

```text
python tools/spread-classifier/evaluate.py \
    --model app/src/main/assets/models/spread_classifier_v1.onnx \
    --manifest docs/spread-classifier/regression-samples/manifest.csv
```

## Model artifact constraints

Initial targets:

- ONNX model <= 5 MB after quantization;
- INT8 preferred if accuracy remains within tolerance;
- no network access at runtime;
- deterministic preprocessing;
- inference only on eligible landscape pages;
- model session initialized lazily;
- session reused across pages;
- no full-resolution bitmap required.

Performance target should be measured on representative Android hardware before merge. Latency targets are goals, not acceptance claims until measured.

## Android runtime design

Proposed components:

```text
SpreadClassifier
  - lazy ONNX Runtime session
  - bitmap preprocessing
  - inference
  - calibrated pSpread

SpreadClassification
  - SPREAD
  - STITCHED
  - UNCERTAIN

ImageUtil / reader
  - legacy path when enhanced switch is OFF
  - model classification when enhanced switch is ON
  - legacy fallback for UNCERTAIN
```

### Preprocessing

- decode directly to a low-resolution bitmap;
- normalize deterministically to `384 x 256`;
- preserve the entire image; no center crop in baseline A;
- use the exact same normalization parameters as training/export;
- recycle temporary bitmaps promptly.

### Caching

Avoid repeated inference for the same page during layout/rebinding.

Start with per-reader-session in-memory caching keyed by chapter/page identity plus source dimensions. Persistent disk caching is unnecessary unless profiling later proves it valuable.

### Failure handling

Any model load/inference/preprocessing error must fail open to the legacy detector.

The model must never make the reader unable to display a page.

## Testing

### Legacy regression

All original `DoublePageSpreadDetector` tests must pass unchanged.

### Classifier logic tests

Test:

- threshold boundaries;
- uncertain -> legacy fallback;
- inference exception -> legacy fallback;
- enhanced switch off -> model never invoked;
- session reuse/cache behavior;
- preprocessing dimensions and channel normalization.

### Model regression

The five committed hard-positive samples must all classify as `SPREAD` or fall into a policy result that keeps them whole.

Add hard-negative regression samples before enabling the feature by default. A model that only passes the five positives is not sufficient.

### CI

Add a lightweight model-regression job after the first model exists.

It should:

1. decode committed regression fixtures;
2. run ONNX inference;
3. verify expected policy outcomes;
4. report probabilities for debugging.

Do not retrain the model in normal application CI.

## Implementation phases

### Phase 0 - reset the current enhanced experiment

- retain the reader setting;
- remove the enhanced seam heuristic implementation and threshold-specific tests;
- keep legacy detector unchanged.

### Phase 1 - dataset/evaluation harness

- commit the regression sample manifest;
- add training/evaluation scripts;
- create a labeled local dataset outside the main repo;
- establish train/validation/test splits by series.

Do not modify runtime classification yet.

### Phase 2 - baseline model

- train single-view MobileNetV3-Small;
- calibrate;
- evaluate on held-out series and regression samples;
- export ONNX;
- measure model size and Android inference latency.

### Phase 3 - decide whether a seam branch is necessary

Only if baseline A misses the agreed accuracy target:

- train candidate B with global + seam inputs;
- compare against baseline on the exact same held-out splits;
- keep B only for a meaningful improvement.

### Phase 4 - Android integration

- implement `SpreadClassifier`;
- reuse existing enhanced reader switch;
- use two-threshold tri-state policy with legacy fallback;
- add caching and graceful failure;
- remove obsolete enhanced heuristic code completely.

### Phase 5 - validation

Before considering merge:

- legacy-off behavior is byte-for-behavior compatible;
- all five current hard positives pass;
- hard negatives pass;
- held-out-series metrics meet the precision target;
- APK model-size increase is acceptable;
- inference latency/memory are measured;
- Pager and Webtoon paths both behave consistently.

## Rollback

The feature remains opt-in.

Disabling `pref_dual_page_advanced_spread_detection` bypasses the model completely and returns to the unchanged legacy detector.

If the model asset is absent or inference fails, runtime automatically falls back to legacy behavior.
