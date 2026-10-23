#!/usr/bin/env python3
from __future__ import annotations

import argparse
import math
import statistics
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from training.sce_data.alignment import align_session, iter_windows
from training.sce_data.binary_log import SessionLog, read_session
from training.sce_data.features import extract_window_features
from training.sce_data.quality import assess_session_quality

EDGE_TRIM_NS = 3_000_000_000


@dataclass(frozen=True)
class SessionFeatures:
    log: SessionLog
    windows: tuple[dict[str, float], ...]
    duration_seconds: float
    accel_rate_hz: float
    max_accel_gap_ms: float
    accel_valid_ratio: float
    quality_ok: bool
    failure_reasons: tuple[str, ...]


def discover(path: Path) -> list[Path]:
    if path.is_file():
        return [path]
    return sorted(path.rglob("*.scelog"))


def load_features(paths: Sequence[Path]) -> list[SessionFeatures]:
    result = []
    for path in paths:
        log = read_session(path)
        rows = align_session(log)
        analysis_rows = (
            [
                row
                for row in rows
                if row.elapsed_ns >= rows[0].elapsed_ns + EDGE_TRIM_NS
                and row.elapsed_ns <= rows[-1].elapsed_ns - EDGE_TRIM_NS
            ]
            if rows and rows[-1].elapsed_ns - rows[0].elapsed_ns > 2 * EDGE_TRIM_NS
            else []
        )
        windows = tuple(
            extract_window_features(window) for window in iter_windows(analysis_rows)
        )
        quality = assess_session_quality(log, rows)
        accel_rate_hz = (
            1_000.0 / quality.accel.median_period_ms
            if math.isfinite(quality.accel.median_period_ms)
            and quality.accel.median_period_ms > 0.0
            else 0.0
        )
        result.append(
            SessionFeatures(
                log=log,
                windows=windows,
                duration_seconds=quality.duration_seconds,
                accel_rate_hz=accel_rate_hz,
                max_accel_gap_ms=quality.accel.max_gap_ms,
                accel_valid_ratio=quality.aligned_accel_valid_ratio,
                quality_ok=quality.quality_ok,
                failure_reasons=quality.failure_reasons,
            )
        )
    return result


def evaluate_target(sessions: Sequence[SessionFeatures], target: str) -> dict[str, object]:
    eligible = [
        session
        for session in sessions
        if session.quality_ok
        and label_for(session, target) not in {"", "unknown"}
        and session.windows
    ]
    session_counts = Counter(label_for(session, target) for session in eligible)
    represented = {label for label, count in session_counts.items() if count >= 2}
    eligible = [session for session in eligible if label_for(session, target) in represented]
    if len(represented) < 2:
        return {
            "available": False,
            "reason": "need at least two labels with two independent sessions each",
            "session_counts": dict(session_counts),
        }

    predictions: list[str] = []
    truths: list[str] = []
    baseline_predictions: list[str] = []
    for held_out in eligible:
        training = [session for session in eligible if session is not held_out]
        train_features = [feature for session in training for feature in session.windows]
        train_labels = [
            label_for(session, target) for session in training for _ in session.windows
        ]
        truth = label_for(held_out, target)
        if truth not in train_labels or len(set(train_labels)) < 2:
            continue
        window_predictions = nearest_centroid_predict(
            train_features,
            train_labels,
            list(held_out.windows),
        )
        predictions.append(majority(window_predictions))
        truths.append(truth)
        baseline_predictions.append(majority(train_labels))

    if not truths:
        return {
            "available": False,
            "reason": "no valid leave-one-session-out folds",
            "session_counts": dict(session_counts),
        }

    metrics = classification_metrics(truths, predictions)
    baseline = classification_metrics(truths, baseline_predictions)
    all_features = [feature for session in eligible for feature in session.windows]
    all_labels = [label_for(session, target) for session in eligible for _ in session.windows]
    return {
        "available": True,
        "session_counts": dict(session_counts),
        "evaluated_sessions": len(truths),
        "metrics": metrics,
        "baseline": baseline,
        "macro_f1_gain": metrics["macro_f1"] - baseline["macro_f1"],
        "confusion": confusion_table(truths, predictions),
        "top_separating_features": separation_scores(all_features, all_labels)[:8],
    }


def nearest_centroid_predict(
    train: Sequence[dict[str, float]],
    labels: Sequence[str],
    test: Sequence[dict[str, float]],
) -> list[str]:
    names = sorted(set.intersection(*(set(item) for item in train)))
    means: dict[str, float] = {}
    scales: dict[str, float] = {}
    for name in names:
        values = [item[name] for item in train if math.isfinite(item[name])]
        means[name] = statistics.fmean(values) if values else 0.0
        scale = statistics.pstdev(values) if len(values) > 1 else 0.0
        scales[name] = scale if scale > 1e-9 else 1.0

    grouped: dict[str, list[dict[str, float]]] = defaultdict(list)
    for feature, label in zip(train, labels):
        grouped[label].append(feature)
    centroids = {
        label: {
            name: statistics.fmean(
                (item[name] if math.isfinite(item[name]) else means[name]) - means[name]
                for item in items
            )
            / scales[name]
            for name in names
        }
        for label, items in grouped.items()
    }

    predictions = []
    for feature in test:
        normalized = {
            name: ((feature[name] if math.isfinite(feature[name]) else means[name]) - means[name])
            / scales[name]
            for name in names
        }
        prediction = min(
            centroids,
            key=lambda label: sum(
                (normalized[name] - centroids[label][name]) ** 2 for name in names
            ),
        )
        predictions.append(prediction)
    return predictions


def classification_metrics(truths: Sequence[str], predictions: Sequence[str]) -> dict[str, float]:
    labels = sorted(set(truths) | set(predictions))
    correct = sum(truth == prediction for truth, prediction in zip(truths, predictions))
    f1_values = []
    for label in labels:
        tp = sum(t == label and p == label for t, p in zip(truths, predictions))
        fp = sum(t != label and p == label for t, p in zip(truths, predictions))
        fn = sum(t == label and p != label for t, p in zip(truths, predictions))
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1_values.append(
            2 * precision * recall / (precision + recall) if precision + recall else 0.0
        )
    return {
        "accuracy": correct / len(truths),
        "macro_f1": statistics.fmean(f1_values) if f1_values else 0.0,
    }


def confusion_table(truths: Sequence[str], predictions: Sequence[str]) -> dict[str, dict[str, int]]:
    labels = sorted(set(truths) | set(predictions))
    table = {truth: {prediction: 0 for prediction in labels} for truth in labels}
    for truth, prediction in zip(truths, predictions):
        table[truth][prediction] += 1
    return table


def separation_scores(
    features: Sequence[dict[str, float]],
    labels: Sequence[str],
) -> list[tuple[str, float]]:
    grouped_indices: dict[str, list[int]] = defaultdict(list)
    for index, label in enumerate(labels):
        grouped_indices[label].append(index)
    scores = []
    for name in sorted(set.intersection(*(set(item) for item in features))):
        group_values = {
            label: [features[index][name] for index in indices if math.isfinite(features[index][name])]
            for label, indices in grouped_indices.items()
        }
        group_values = {label: values for label, values in group_values.items() if values}
        if len(group_values) < 2:
            continue
        means = [statistics.fmean(values) for values in group_values.values()]
        between = statistics.pvariance(means) if len(means) > 1 else 0.0
        within_values = [
            statistics.pvariance(values) if len(values) > 1 else 0.0
            for values in group_values.values()
        ]
        within = statistics.fmean(within_values)
        scores.append((name, between / (within + 1e-9)))
    return sorted(scores, key=lambda item: item[1], reverse=True)


def label_for(session: SessionFeatures, target: str) -> str:
    return {
        "activity": session.log.header.activity_label,
        "posture": session.log.header.posture_label,
        "light": session.log.header.light_label,
    }[target]


def majority(values: Sequence[str]) -> str:
    counts = Counter(values)
    return sorted(counts, key=lambda value: (-counts[value], value))[0]


def render_report(sessions: Sequence[SessionFeatures], results: dict[str, dict[str, object]]) -> str:
    lines = [
        "# Phase B 快速价值验证报告",
        "",
        f"- Session 数：{len(sessions)}",
        f"- 有效 4 s 窗口：{sum(len(session.windows) for session in sessions)}",
        f"- 非干净结束：{sum(session.log.truncated for session in sessions)}",
        "- 每个 session 自动裁掉首尾各 3 s 后切窗",
        "",
        "## 数据质量",
        "",
        "| session | activity | posture | duration | accel Hz | max gap | valid | windows | quality |",
        "|---|---|---|---:|---:|---:|---:|---:|---|",
    ]
    for session in sessions:
        lines.append(
            f"| {session.log.header.session_id} | {session.log.header.activity_label} | "
            f"{session.log.header.posture_label} | {session.duration_seconds:.1f}s | "
            f"{session.accel_rate_hz:.1f} | {session.max_accel_gap_ms:.1f}ms | "
            f"{session.accel_valid_ratio:.1%} | {len(session.windows)} | "
            f"{'PASS' if session.quality_ok else 'FAIL'} |"
        )
        if session.failure_reasons:
            lines.append(f"  - FAIL reasons: `{', '.join(session.failure_reasons)}`")

    go_dimensions = []
    for target, result in results.items():
        lines.extend(["", f"## {target}", ""])
        lines.append(f"Session 分布：`{result.get('session_counts', {})}`")
        lines.append("")
        if not result.get("available"):
            lines.append(f"暂不评分：{result.get('reason')}")
            continue
        metrics = result["metrics"]
        baseline = result["baseline"]
        gain = result["macro_f1_gain"]
        lines.extend(
            [
                f"- Leave-one-session-out accuracy：**{metrics['accuracy']:.3f}**",
                f"- Leave-one-session-out macro-F1：**{metrics['macro_f1']:.3f}**",
                f"- 多数类 baseline macro-F1：{baseline['macro_f1']:.3f}",
                f"- macro-F1 增益：**{gain:+.3f}**",
                "",
                "Top separating features：",
                "",
            ]
        )
        for name, score in result["top_separating_features"]:
            lines.append(f"- `{name}`：{score:.3f}")
        if target in {"activity", "posture"} and gain >= 0.20:
            go_dimensions.append(target)

    lines.extend(["", "## 结论", ""])
    if go_dimensions:
        lines.append(
            "**GO**：" + ", ".join(go_dimensions) +
            " 相对多数类 baseline 的 macro-F1 增益达到 0.20。"
        )
    elif any(result.get("available") for result in results.values()):
        lines.append("**CONDITIONAL GO / REVIEW**：已有可评分数据，但尚未达到预设增益，请检查混淆和采集协议。")
    else:
        lines.append("**INSUFFICIENT DATA**：按协议补足每类至少两个独立 session 后再判断。")
    lines.append("")
    lines.append("该报告是小样本 go/no-go，不代表正式泛化精度。")
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description="Run leakage-safe pilot value validation")
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    paths = discover(args.input)
    if not paths:
        raise SystemExit("No .scelog files found")
    sessions = load_features(paths)
    results = {
        target: evaluate_target(sessions, target)
        for target in ("activity", "posture", "light")
    }
    report = render_report(sessions, results)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(report, encoding="utf-8")
    print(report)
    print(f"Report written to {args.output}")


if __name__ == "__main__":
    main()
