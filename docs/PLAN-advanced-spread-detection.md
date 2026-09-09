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
- English summary: `Also detect artwork continuity across a centered gutter. More accurate for scanned spreads, with a small extra processing cost.`
- Simplified Chinese: `增强大跨页识别`
- Simplified Chinese summary: `进一步识别跨越中央白缝/黑缝的连续画面。对扫描版跨页更准确，但会增加少量处理开销。`

The switch is enabled only when both wide-page splitting and "Skip splitting double-page spreads" are enabled.

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

1. Analyse a wider centered band only in enhanced mode so there is context on both sides of the gutter.
2. Expand the single candidate column into a contiguous gutter run using the same gutter-like criteria (uniform and near the detected edge color).
3. Take a context window immediately outside each side of the gutter run (target: ~3% of the downsampled image width per side).
4. For each row, compute an activity density on the left and right. A pixel is active when its luminance differs sufficiently from the gutter luminance; this works for both white and black gutters.
5. Compute:
   - `bothSidesActiveRatio`: fraction of rows where both context windows contain meaningful activity.
   - `rowProfileCorrelation`: Pearson correlation between left/right per-row activity densities.
6. Classify the page as an intentional spread (veto splitting) only when both metrics exceed conservative thresholds.

Initial conservative thresholds:

- context width: 3% of analysed image width per side
- pixel activity delta from gutter luminance: 35
- active-row density: 20%
- `bothSidesActiveRatio >= 0.45`
- `rowProfileCorrelation >= 0.35`

These values are intentionally conservative because the enhanced stage should only remove clear false-positive splits.

## Detector API shape

Keep the legacy API available. Add a dedicated analysis function rather than changing the legacy classification semantics.

Proposed structure:

```kotlin
data class GutterRun(
    val startX: Int,
    val endX: Int,
    val mean: Double,
)

data class ContinuityStats(
    val bothSidesActiveRatio: Double,
    val rowProfileCorrelation: Double,
)

fun isLikelyContinuousSpread(...): Boolean
```

`ImageUtil.isWideStitchedPage()` receives an optional `enhancedSpreadDetection: Boolean = false` argument. The default preserves all existing callers and behavior.

## Tests

### Existing tests

All current `DoublePageSpreadDetectorTest` cases must remain unchanged and continue to pass.

### New unit tests

Add synthetic fixtures for:

1. Centered white gutter + strongly correlated artwork/activity on both sides -> enhanced mode identifies a real spread.
2. Centered black gutter + strongly correlated bright artwork on both sides -> enhanced mode identifies a real spread.
3. Centered gutter + independent/unrelated side activity -> remains stitched.
4. Centered gutter + content on only one side for most rows -> remains stitched.
5. Low-variance/constant profiles -> correlation handling is stable (no NaN-driven false veto).
6. Gutter-run expansion stays bounded and does not consume the context windows.

### Integration behavior

Add/adjust `ImageUtil` tests if practical so the default `enhancedSpreadDetection = false` path is explicitly regression-tested.

## Implementation order

1. Add preference, viewer config property, strings, and settings UI. Default off.
2. Add gutter-run/context continuity analysis to `DoublePageSpreadDetector` without modifying legacy classification behavior.
3. Extend `ImageUtil.isWideStitchedPage()` with the optional enhanced flag and wider opt-in analysis band.
4. Pass the flag from pager/webtoon page holders.
5. Add unit tests for continuity metrics and legacy compatibility.
6. Run formatting/unit tests and review CI before merge.

## Rollback / safety

Because the feature is separately gated and defaults off, disabling the new switch immediately restores the previous algorithm. No preference migration is required.