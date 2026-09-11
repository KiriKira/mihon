# Advanced double-page spread detection plan v3

## Why the current approach is being replaced

The legacy detector asks a deliberately narrow question: is there a very uniform near-white or near-black column close to the image center? If yes, it assumes that column is a page gutter and treats the image as two independent pages.

That is useful as a cheap baseline, but it cannot distinguish these two cases:

1. two independent pages stitched side-by-side; and
2. an intentional double-page spread whose physical book fold / scan leaves a white or black center gutter.

The first enhanced implementation tried to recover that distinction from handcrafted seam statistics: gutter width, per-row activity, left/right correlation, near-seam luminance correlation, and local seam support. Real examples show that this remains fundamentally brittle. Speech balloons, large white areas, diagonal compositions, dense color artwork, and spreads whose two halves are compositionally related but not pixel-continuous can all defeat seam-based rules.

The enhanced heuristic implementation should therefore be removed rather than extended with more threshold paths.

## Compatibility contract

- Keep `pref_dual_page_split_skip_spread` and the current legacy detector unchanged.
- Keep a separate opt-in enhanced preference, default `false`.
- Enhanced OFF: behavior is exactly the current legacy behavior.
- Enhanced ON: the legacy detector remains a cheap first stage. When legacy already says "real spread", keep the image whole. When legacy says "split", run the new classifier before splitting.
- The enhanced classifier may only veto a legacy split. It must never convert a legacy real-spread result into a split.
- Pager and webtoon use the same preference.

This keeps rollback trivial and guarantees that the new work does not regress users who leave the switch disabled.

## Core design change

### Old question

`Does artwork look locally continuous across the detected center gutter?`

### New question

`Does the whole landscape image represent one intentional double-page composition, or two independent pages?`

This is an image-classification problem, not a gutter-threshold problem.

## Proposed classifier

Use a small ONNX binary classifier. Mihon already ships ONNX Runtime, so no new inference framework is required.

Output:

```text
pSpread = P(intentional double-page spread)
```

The classifier is invoked only when all are true:

- page is landscape;
- wide-page splitting is enabled;
- skip-spread is enabled;
- enhanced detection is enabled;
- legacy detector would otherwise split the page.

### Inputs

Use two visual views so the model receives both composition-level and fold-level information.

#### Global view

Letterboxed full page, target around `320 x 224`.

Purpose:

- overall panel layout;
- shared perspective/background;
- character and object composition across both halves;
- whether the image reads as one designed canvas or two unrelated pages.

#### Seam view

Centered crop covering roughly 25-30% of image width, resized to around `128 x 224`.

Purpose:

- physical fold / scan gutter appearance;
- page margins;
- structures that approach or cross the center;
- distinguishing an artificial stitched boundary from a scanned spread fold.

### Network shape

Initial target:

```text
Global MobileNetV3-Small tower ----\
                                   +--> feature concat --> small MLP --> pSpread
Tiny seam CNN tower ---------------/
```

Requirements:

- INT8 quantized ONNX target size: preferably <= 5 MB;
- CPU inference only;
- no dynamic input sizes;
- deterministic preprocessing;
- inference is asynchronous and result is cached per page.

If the two-tower model is not measurably better than a single full-page MobileNetV3-Small in validation, prefer the simpler single-input model.

## Training data strategy

The main difficulty is labels, so use high-confidence automatic data generation plus a relatively small hard-example set.

### Positive set: intentional spreads

High-confidence seeds:

- landscape pages where the existing legacy detector already sees obvious artwork through the center and therefore refuses to split;
- manually confirmed hard positives reported during testing.

Augment positives aggressively to simulate the exact failure mode that the legacy detector cannot handle:

- insert a centered white / black / gray gutter;
- gutter width from 1 px to realistic scan widths;
- remove several center columns to simulate content lost in the fold;
- add book-fold shadows / gradients;
- slight left/right vertical misalignment;
- JPEG/WebP degradation;
- grayscale/color conversion;
- brightness and contrast differences between halves.

The important training lesson is: `a center gutter does not imply two independent pages`.

### Negative set: independent pages

Generate negatives by pairing two portrait pages, preferably from the same title / volume / chapter so the model cannot cheat by detecting different art styles.

Augment with:

- white/black/gray gutters;
- variable gutter widths;
- crop and margin variation;
- slight scan skew;
- compression artifacts;
- left/right exposure differences.

### Hard negatives

Construct deliberately confusing negatives:

- pair halves with similar tone and panel density;
- select pairs whose handcrafted seam correlations are unusually high;
- pair pages from adjacent positions in the same chapter;
- pair visually similar full-bleed pages.

### Hard positives

Keep the user-reported false-split examples as local validation/training references, but do not commit copyrighted page images to the repository.

### Dataset split

Split by manga/title, not randomly by page, to avoid style leakage between train and validation sets.

## Decision policy

Because enhanced detection only vetoes splitting, false positives are costly: a true two-page scan would remain unsplit. Start conservatively.

Initial policy:

```text
if pSpread >= 0.80:
    keep whole page
else:
    follow legacy result and split
```

Tune the threshold from validation ROC/precision-recall data rather than from individual screenshots.

Primary target:

- very high precision for `intentional spread` vetoes;
- then maximize recall subject to that precision target.

Suggested first acceptance target:

- spread-veto precision >= 97%;
- maximize recall while staying above that precision.

## Optional chapter-context prior

A later phase can use chapter-level layout as an auxiliary prior, not as a hard rule.

Useful signal:

- one isolated landscape page among many portrait pages is more likely to be an intentional spread;
- a chapter where most files are landscape is more likely to consist of paired scans that should be split.

Do not block the first classifier implementation on this because neighboring page dimensions are not always available cheaply at decision time. Add only if offline evaluation shows a clear benefit.

## Runtime architecture

```text
wide page
   |
legacy detector
   |
   +-- legacy says real spread --> keep whole
   |
   +-- legacy says split
           |
       enhanced OFF --> split
           |
       enhanced ON
           |
      SpreadClassifier
           |
      pSpread >= T ?
        /       \
      yes       no
       |         |
   keep whole   split
```

### Caching

Cache classification by page/image identity for the reader session so rotations, redraws, pager recreation, or switching viewer modes do not rerun inference unnecessarily.

### Failure behavior

Any model-load, preprocessing, or inference failure must fail open to the legacy result: split exactly as legacy requested. Enhanced detection must never break page loading.

## Code replacement plan

Remove the current enhanced heuristic implementation rather than preserving it behind another path.

Delete or collapse:

- `GutterRun` enhanced-only analysis;
- `ContinuityStats`;
- activity-density correlation logic;
- near-seam correlation logic;
- local seam support logic;
- threshold-path tests specific to those metrics.

Retain only the legacy `DoublePageSpreadDetector` behavior used when enhanced mode is disabled / before model escalation.

Introduce:

- `SpreadClassifier` interface;
- ONNX-backed implementation;
- deterministic image preprocessing helpers;
- result cache;
- classifier-focused tests using synthetic/generated fixtures;
- model metadata/version constants.

## Validation plan

### Unit tests

1. Enhanced OFF calls only the legacy path.
2. Legacy real-spread result never invokes the model.
3. Legacy split + enhanced ON invokes classifier.
4. High classifier probability vetoes splitting.
5. Low classifier probability preserves legacy split.
6. Inference/model failure preserves legacy split.
7. Preprocessing output dimensions and normalization are deterministic.
8. Cache prevents repeated inference for the same page.

### Model evaluation

Maintain a separate local evaluation corpus containing:

- all reported false-split pages;
- ordinary stitched two-page scans;
- difficult speech-balloon-heavy spreads;
- white-background spreads;
- color spreads;
- independent pages with similar layout/tone.

Track confusion matrix, precision, recall, PR curve, and per-category failures. Do not tune a threshold from a single sample.

### Regression rule

All currently reported false-split examples must be correctly protected by the candidate model before replacing the heuristic implementation.

## Implementation phases

1. Revert/remove the current handcrafted enhanced continuity code, keeping preference wiring and legacy behavior.
2. Add `SpreadClassifier` abstraction and a fake implementation for reader integration tests.
3. Build the offline dataset-generation/training/evaluation tooling outside the Android runtime path.
4. Train baseline single-view and two-view MobileNetV3-Small models.
5. Compare models using title-separated validation data and hard examples.
6. Quantize selected model to INT8 ONNX and benchmark Android CPU inference.
7. Add model asset and ONNX inference implementation.
8. Tune the conservative veto threshold from validation data.
9. Run existing tests, new integration tests, and build an installable APK.
10. Only after model validation, consider chapter-context priors as a separate improvement.

## Rollback / safety

The enhanced switch remains default-off. Disabling it restores the unchanged legacy algorithm immediately. The new model has no authority to force a split; it can only protect a page that legacy would otherwise split.