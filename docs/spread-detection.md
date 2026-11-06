# Double-page spread detection

> Proposed redesign (design only, no classifier): [Mobile spread detection algorithm and integration specification](PLAN-mobile-spread-detection.md). The behavior documented below is the current implementation, not the proposal. A bounded, model-free evidence probe exists on a separate experiment branch ([#18](https://github.com/KiriKira/mihon/pull/18)); it is not called by the reader.

With "Split double pages" and "Skip splitting double-page spreads" on, a wide page is split only when it looks like two pages scanned side by side.

## Legacy check (always on)

`DoublePageSpreadDetector` looks for a uniform pure white or pure black column near the center. If there is one, the page is treated as two stitched pages and split. True spreads whose scan has a thin white gutter at the fold are split by mistake.

## Enhanced spread detection (opt-in)

`SpreadPageStructure` only runs when the legacy check would split. It needs no trained model. It vetoes the split unless the page layout shows two separate pages:

- **Wide blank seam.** Each scanned page keeps its own inner margin, so two pages leave a blank strip in the middle that is at least 1.5% of the image width. A spread scan usually has a thin gutter of 0.5% or less.
- **Page margins next to the seam.** Within 8% of the width on each side of the seam, both halves have blank rows at the top or bottom (at least 2% of the height), and at least one half has both. Spreads are drawn up to the edges.

Margins tolerate scan defects: a dark scan edge of up to 1% of the height at the outer edge is skipped, and single noisy rows inside a margin (up to 0.5% of the height in total) do not end it.

Both signals are needed. So a spread with a wider gutter, blank sky at the top, or halves scanned slightly out of line is still kept whole.

Comparing pixels on both sides of the seam was tried before and dropped. The panel borders and white margins of two unrelated pages line up across the seam just as well as continuous artwork does, so every framed page pair looked like a spread.

Known limitations:

- Pages whose inner margins were cropped away look like a spread and are kept whole.
- Spreads printed with page margins and a wide gutter are split.
- A black gutter is left to the legacy check.

Measured on user samples (analysis width 512 px):

| Sample | Seam | Margins (left / right, top-bottom) | Result |
|---|---|---|---|
| Spread, castle | 0.4% | none | kept |
| Spread, pipes | 0.4% | none | kept |
| Pages 118–119 | 3.1% | 6.1–4.8% / 6.6–4.3% | split |
| Pages 130–131 | 2.5% | 5.9–5.3% / 6.1–5.3% | split |
| Pages 10–11 | 2.9% | 5.3–5.9% / 0–5.3% | split |
