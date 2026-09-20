# Reader page auto-rotation tuning

This document records the fork-specific decision rule used by the reader's
"Auto-rotate current page to fit screen" option so later tuning does not lose the
original intent.

## Current behavior

Auto-rotation is evaluated after any configured double-page splitting, so the
decision applies to the image that is actually displayed.

A displayed page is rotated clockwise by 90 degrees only when both conditions are
true:

1. The **logical reader surface is portrait**.
2. The displayed image satisfies `width / height >= 1.25`.

If the logical reader surface is already landscape, auto-rotation does nothing,
even for a very wide image.

"Logical reader surface" is intentional wording. On unfolded foldables, the fork
can keep the Android activity in its natural landscape orientation while rotating
the complete reader content by 90 degrees to present a portrait reader. The
auto-rotation decision must follow that transformed reader surface, not the raw
activity orientation and not an individual recycled page holder's dimensions.

## Tuning parameter

Current minimum landscape aspect ratio:

```text
AUTO_ROTATE_MIN_ASPECT_RATIO = 1.25
```

Equivalent ratio: **5:4**.

This value is a tuning parameter rather than a permanent semantic boundary.
Future optimization should change it only with regression testing against pages
that should rotate and pages that should remain unchanged.

## Regression constraints

Any future implementation should preserve these properties:

- flipping forward and backward must not change the rotation decision for the same
  page while the reader layout orientation is unchanged;
- recycled pager/webtoon holders must not influence the decision;
- a portrait reader plus a page exactly at 1.25:1 should rotate;
- a page below 1.25:1 should remain unchanged;
- a landscape reader should never auto-rotate a page solely because the page is
  wide.
