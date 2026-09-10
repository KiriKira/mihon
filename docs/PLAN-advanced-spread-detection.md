# Advanced double-page spread detection plan

## Goal

Reduce false splitting of intentional double-page spreads that contain a real, centered white/black gutter line (for example a scanned book fold), while preserving the current skip-spread behavior exactly unless the user explicitly enables the new detector.

## Compatibility contract

- Keep `pref_dual_page_split_skip_spread` and the existing detector as the baseline behavior.
- Add a separate opt-in preference for the enhanced detector.
- Default the new preference to `false`.
- When the new preference is disabled, `ImageUtil.isWideStitchedPage()` follows the current legacy path unchanged.
- The enhanced detector only runs after the legacy detector has classified the page as a stitched double page. It may veto splitting; it never turns a legacy real-spread result into stitched.
- Pager and webtoon viewers use the same preference and detector path.

## New preference

Key:

`pref_dual_page_advanced_spread_detection`

UI copy:

- English: `Enhanced double-page spread detection`
- Simplified Chinese: `增强大跨页识别`

The switch is exposed only in the in-reader settings and only when wide-page splitting plus `Skip splitting double-page spreads` are enabled.

## Stage 1: legacy detector (unchanged)

1. Decode the page at the existing low-resolution target.
2. Search the centered band for the minimum-standard-deviation gutter candidate.
3. Apply the existing uniformity, edge-color, and center-distance checks.
4. If legacy detection says real spread, keep the page whole immediately.
5. If legacy detection says stitched and enhanced detection is disabled, split exactly as before.

## Stage 2: enhanced detector

### Fixed analysis scale

The enhanced path must not depend on `BitmapFactory.inSampleSize` producing an exact dimension. Decode with a power-of-two sample size that leaves at least the target width, then explicitly scale the analysis bitmap to 512 px wide. All enhanced thresholds therefore operate at a deterministic spatial scale.

### Gutter model

Expand the single legacy candidate column into a contiguous gutter run with the same bright/dark polarity. Enhanced analysis starts immediately outside this run.

## Algorithm v2: corroborated global + local seam evidence

The first implementation used several hard-AND paths based mainly on whole-image row-density correlations. Real examples showed that this is brittle: speech balloons, white backgrounds, diagonal composition, and dense color art all change those aggregate metrics substantially. More importantly, two unrelated pages can accidentally have strong whole-height correlation.

The enhanced detector therefore uses two levels of evidence rather than accumulating special-case threshold paths.

### Global evidence

From a ~3% context strip on each side of the gutter, compute:

- `bothSidesActiveRatio`: fraction of rows with meaningful activity on both sides.
- `leftMeanActiveDensity` / `rightMeanActiveDensity`: average activity density on each side.
- `rowProfileCorrelation`: Pearson correlation between the left/right per-row activity profiles.
- `nearSeamLuminanceCorrelation`: best whole-height luminance correlation among the first few symmetric columns outside the gutter, accepted only when their mean absolute luminance difference is small enough.

These metrics provide broad evidence but are not sufficient on their own.

### Local seam corroboration

Split the vertical seam into overlapping short windows (about 9% of image height, 50% overlap). For each window, inspect the first four symmetric column pairs outside the gutter.

A window is informative when both profiles have enough variance. It is locally supported when at least one symmetric pair has:

- Pearson correlation >= 0.65, and
- mean absolute luminance difference <= 50.

`localSeamSupportRatio` is the fraction of informative windows that are locally supported.

This is the key algorithmic change: true spreads should show repeated local continuation near the fold, while accidental whole-height similarity between independent pages should not.

### Final decision

First require a broad sanity gate so nearly blank/one-sided pages cannot be protected:

- `bothSidesActiveRatio >= 0.25`
- weaker side mean activity >= 0.25

Then veto splitting when either:

1. a global signal exists (`rowProfileCorrelation >= 0.45` OR `nearSeamLuminanceCorrelation >= 0.55`) AND `localSeamSupportRatio >= 0.05`; or
2. `localSeamSupportRatio >= 0.25`, meaning repeated strong local seam continuity is sufficient by itself.

This replaces the previous sparse-vs-dense special-case paths.

## Validation strategy

Keep all legacy detector tests unchanged. Enhanced tests cover:

1. sparse correlated artwork across a white/black gutter;
2. dense color artwork where row-activity correlation is weak;
3. mixed artwork where whole-image correlation is only moderate but local seam support is strong;
4. one-sided/mostly blank pages;
5. unrelated pages with similar overall activity;
6. deliberately adversarial unrelated halves with high whole-height correlation but no local seam support;
7. deterministic 512 px analysis scaling.

For tuning, use the reported real false-split examples only as local/offline measurements; do not add copyrighted page images to the repository. Also construct adversarial negative samples by pairing left/right halves from different examples. The target is that all reported real spreads are vetoed while mismatched halves remain split.

## Implementation order

1. Add preference and viewer config property, default off.
2. Expose it in the in-reader settings only.
3. Keep legacy detector semantics unchanged.
4. Normalize enhanced analysis to 512 px wide.
5. Add gutter-run extraction and global metrics.
6. Add local seam support and replace special-case decision paths with corroborated evidence.
7. Extend regression tests and run formatting/unit tests.
8. Build an installable arm64 APK artifact from PR Actions.

## Rollback / safety

Because the feature is separately gated and defaults off, disabling the new switch immediately restores the previous algorithm. No preference migration is required.
