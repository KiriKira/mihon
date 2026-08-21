#!/usr/bin/env python3
"""Offline evaluation of aggregation strategies over dumped raw scores."""
import json
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
RAW = json.loads((HERE / "results" / "raw_scores.json").read_text())

MIN_CONFIDENCE = 0.80
MIN_MARGIN = 0.40


def best_crop_class(scores, min_conf=MIN_CONFIDENCE, min_margin=MIN_MARGIN):
    """Current Android logic: single highest-confidence crop decides."""
    crop = max(scores, key=lambda s: max(s))
    ranked = sorted(range(4), key=lambda i: -crop[i])
    if crop[ranked[0]] < min_conf or crop[ranked[0]] - crop[ranked[1]] < min_margin:
        return None
    return ranked[0]


def median_crop_class(scores, min_conf=MIN_CONFIDENCE, min_margin=MIN_MARGIN):
    """Per-class median across crops, then argmax with margin check."""
    meds = [sorted(s[c] for s in scores)[len(scores) // 2] for c in range(4)]
    ranked = sorted(range(4), key=lambda i: -meds[i])
    if meds[ranked[0]] < min_conf or meds[ranked[0]] - meds[ranked[1]] < min_margin:
        return None
    return ranked[0]


def mean_topk_class(scores, k=3, min_conf=MIN_CONFIDENCE, min_margin=MIN_MARGIN):
    """Mean of top-k crops per class (trimmed mean variant)."""
    agg = []
    for c in range(4):
        vals = sorted((s[c] for s in scores), reverse=True)[:k]
        agg.append(sum(vals) / len(vals))
    ranked = sorted(range(4), key=lambda i: -agg[i])
    if agg[ranked[0]] < min_conf or agg[ranked[0]] - agg[ranked[1]] < min_margin:
        return None
    return ranked[0]


def majority_vote_class(scores, min_votes=3, min_conf=MIN_CONFIDENCE):
    """Crops vote; require quorum. Ties broken by mean score."""
    votes = [max(range(4), key=lambda c: s[c]) for s in scores]
    counts = Counter(votes)
    top_count = max(counts.values())
    if top_count < min_votes:
        return None
    candidates = [c for c, n in counts.items() if n == top_count]
    if len(candidates) > 1:
        means = [sum(s[c] for s in scores) / len(scores) for c in candidates]
        return candidates[means.index(max(means))]
    cls = candidates[0]
    confs = sorted((s[cls] for s in scores), reverse=True)
    if sum(confs[:3]) / 3 < min_conf:
        return None
    return cls


STRATEGIES = {
    "current_best_crop": best_crop_class,
    "median": median_crop_class,
    "mean_top3": mean_topk_class,
    "majority3": majority_vote_class,
}


def proposal_from_classes(classes_by_rotation):
    """classes_by_rotation: {pixel_rotation: class or None} -> correction or None."""
    aligned = set()
    for rot, cls in classes_by_rotation.items():
        if cls is None:
            return None
        aligned.add((cls - rot // 90) % 4)
    if len(aligned) != 1:
        return None
    oc = aligned.pop()
    return None if oc == 0 else (360 - oc * 90) % 360


def main():
    labels = {}
    lab_path = HERE / "labels.csv"
    if lab_path.exists():
        for line in lab_path.read_text().splitlines()[1:]:
            if line.strip():
                path, expected = line.split(",")[:2]
                labels[path] = int(expected)

    have_labels = bool(labels)
    print(f"labels loaded: {len(labels)}" if have_labels else "NO LABELS - proposal dump only")

    for name, fn in STRATEGIES.items():
        stats = Counter()
        wrong_rows = []
        detail = []
        for item in RAW:
            classes = {int(rot): fn(scores) for rot, scores in item["scores"].items()}
            proposal = proposal_from_classes(classes)
            expected = labels.get(item["path"])
            if proposal is None:
                stats["reject"] += 1
                detail.append(f"  reject {item['path']}")
            elif expected is None:
                stats["proposal"] += 1
                detail.append(f"  prop={proposal} {item['path']}")
            elif proposal == expected:
                stats["correct"] += 1
            else:
                stats["WRONG"] += 1
                wrong_rows.append(f"  WRONG {item['path']}: got {proposal} want {expected}")
        false_rot = sum(
            1 for item in RAW
            if "/portrait/" in item["path"]
            and proposal_from_classes({int(r): fn(s) for r, s in item["scores"].items()}) not in (None,)
        )
        print(f"\n== {name} == reject={stats['reject']} proposal={stats['proposal']} "
              f"correct={stats['correct']} wrong={stats['WRONG']}")
        for r in wrong_rows + detail:
            print(r)


if __name__ == "__main__":
    main()
