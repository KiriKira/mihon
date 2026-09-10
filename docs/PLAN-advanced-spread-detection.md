# Advanced double-page spread detection plan

## Goal

Reduce false splitting of intentional double-page spreads that contain a real, centered white/black gutter line (for example a scanned book fold), while preserving the current skip-spread behavior exactly unless the user explicitly enables the new detector.

## Compatibility contract

- Keep `pref_dual_page_split_skip_spread` and the existing detector as the baseline behavior.
- Add a separate opt-in preference for the enhanced detector.
- Default the new preference to `false`.
- When the new preference is disabled, `ImageUtil.isWideStitchedPage()` must follow the current legacy path and produce the same result as before this change.
- The enhanced detector only runs after the legacy detector has already classified the page as a stitched double page. It may veto splitting; it never turns a legacy "real spread" result into "stitched".
- Pager and webtoon viewers use the same preference and same detector path.

## New preference

Suggested key:

`pref_dual_page_advanced_spread_detection`

Suggested UI copy:

- English: `Enhanced double-page spread detection`
- Simplified Chinese: `增强大跨页识别`

The switch is exposed only in the in-reader settings and only when wide-page splitting plus "Skip splitting double-page spreads" are enabled.

## Algorithm

### Stage 1: legacy detector (unchanged)

1. Decode the wide page at the existing low-resolution target (~512 px width).
2. Search the existing centered 5% band.
3. Find the minimum-standard-deviation gutter candidate column.
4. Apply the existing uniformity, edge-color, and center-distance checks.
5. If the legacy result is `false`, keep the page whole immediately.
6. If the legacy result is `true` and enhanced detection is disabled, split exactly as today.

### Stage 2: enhanced continuity veto (opt-in only)

The failure case this addresses is a true spread whose scan/book fold produces a centered pure white/black gutter. A gutter alone is not sufficient evidence that the two sides are independent pages.

1. Expand the single candidate column into a contiguous gutter run using the same gutter-like criteria (uniform and near the detected edge color).
2. Take a context window immediately outside each side of the gutter run (target: ~3% of the downsampled image width per side).
3. For each row, compute an activity density on the left and right. A pixel is active when its luminance differs sufficiently from the gutter luminance; this works for both white and black gutters.
4. Compute:
   - `bothSidesActiveRatio`: fraction of rows where both context windows contain meaningful activity.
   - `rowProfileCorrelation`: Pearson correlation between left/right per-row activity densities.
   - `leftMeanActiveDensity` / `rightMeanActiveDensity`: average activity on each side.
   - `nearSeamLuminanceCorrelation`: best correlation of raw luminance profiles at the first few symmetric columns outside the gutter, accepted only when their mean absolute luminance difference is reasonably small.
5. Classify the page as an intentional spread (veto splitting) when either of two conservative continuity paths succeeds.

### Path A: correlated activity profile

Used for sparse or mixed manga artwork where matching shapes/effects occur on similar rows across the fold.

Initial thresholds:

- context width: 3% of analysed image width per side
- pixel activity delta from gutter luminance: 35
- active-row density: 20%
- `bothSidesActiveRatio >= 0.35`
- weaker side mean activity `>= 0.30`
- `rowProfileCorrelation >= 0.45`

### Path B: dense full-bleed continuity

Used for painted/color spreads where both sides are active on nearly every row. In this case the binary activity profiles have too little useful variance, so their Pearson correlation may be weak or negative even though the scene clearly continues across the physical fold.

Initial thresholds:

- `bothSidesActiveRatio >= 0.80`
- weaker side mean activity `>= 0.75`
- inspect the first 3 symmetric columns outside the gutter
- only consider a symmetric pair when mean absolute luminance difference `<= 45`
- require `nearSeamLuminanceCorrelation >= 0.55`

This second path is deliberately gated by very high two-sided activity so two ordinary independent pages are not protected merely because they both contain ink near the center.

## Detector API shape

Keep the legacy API available. Add dedicated enhanced analysis functions rather than changing the legacy classification semantics.

```kotlin
data class GutterRun(
    val startX: Int,
    val endX: Int,
    val mean: Double,
)

data class ContinuityStats(
    val bothSidesActiveRatio: Double,
    val rowProfileCorrelation: Double,
    val leftMeanActiveDensity: Double,
    val rightMeanActiveDensity: Double,
    val nearSeamLuminanceCorrelation: Double,
)

fun isLikelyContinuousSpread(...): Boolean
```

The enhanced image-analysis entry point remains separate from the legacy detector so disabling the new switch restores the old behavior exactly.

## Tests

### Existing tests

All current `DoublePageSpreadDetectorTest` cases must remain unchanged and continue to pass.

### New unit tests

Cover synthetic fixtures/stat sets for:

1. Centered white gutter + strongly correlated artwork/activity on both sides -> enhanced mode identifies a real spread.
2. Centered black gutter + strongly correlated bright artwork on both sides -> enhanced mode identifies a real spread.
3. Centered gutter + independent/unrelated side activity -> remains stitched.
4. Centered gutter + content on only one side for most rows -> remains stitched.
5. Low-variance/constant profiles -> correlation handling is stable (no NaN-driven false veto).
6. Gutter-run expansion stays bounded and does not consume the context windows.
7. Sparse-but-correlated real spread matching the second reported false split -> protected by Path A.
8. Dense full-bleed real spread with weak activity correlation but strong near-seam luminance continuity -> protected by Path B.
9. Dense independent pages without near-seam correlation -> remain stitched.

## Implementation order

1. Add preference and viewer config property. Default off.
2. Expose it in the in-reader settings only.
3. Add gutter-run/context continuity analysis to `DoublePageSpreadDetector` without modifying legacy classification behavior.
4. Add the dense full-bleed fallback based on near-seam luminance correlation.
5. Pass the flag from pager/webtoon page holders.
6. Add unit tests for continuity metrics and legacy compatibility.
7. Run formatting/unit tests and build an installable arm64 APK artifact from PR Actions.

## Rollback / safety

Because the feature is separately gated and defaults off, disabling the new switch immediately restores the previous algorithm. No preference migration is required.
