from __future__ import annotations

import math
import statistics
from typing import Sequence

from .alignment import AlignedRow


def extract_window_features(window: Sequence[AlignedRow]) -> dict[str, float]:
    features: dict[str, float] = {}
    for prefix in ("accel", "gyro", "mag", "gravity"):
        vectors = [
            (
                row.values[f"{prefix}_x"],
                row.values[f"{prefix}_y"],
                row.values[f"{prefix}_z"],
            )
            for row in window
            if row.valid.get(prefix, False)
        ]
        features[f"{prefix}_valid_ratio"] = len(vectors) / len(window)
        for axis_index, axis in enumerate(("x", "y", "z")):
            axis_values = [vector[axis_index] for vector in vectors]
            _add_stats(features, f"{prefix}_{axis}", axis_values)
        magnitudes = [math.sqrt(x * x + y * y + z * z) for x, y, z in vectors]
        _add_stats(features, f"{prefix}_magnitude", magnitudes)
        features[f"{prefix}_magnitude_mean_abs_diff"] = _mean_abs_diff(magnitudes)

    for channel in ("lux", "proximity", "cct", "step_counter"):
        values = [row.values[channel] for row in window if row.valid.get(channel, False)]
        features[f"{channel}_valid_ratio"] = len(values) / len(window)
        _add_stats(features, channel, values)

    return features


def _add_stats(target: dict[str, float], prefix: str, values: Sequence[float]) -> None:
    finite = [value for value in values if math.isfinite(value)]
    if not finite:
        target[f"{prefix}_mean"] = 0.0
        target[f"{prefix}_std"] = 0.0
        target[f"{prefix}_min"] = 0.0
        target[f"{prefix}_max"] = 0.0
        return
    target[f"{prefix}_mean"] = statistics.fmean(finite)
    target[f"{prefix}_std"] = statistics.pstdev(finite) if len(finite) > 1 else 0.0
    target[f"{prefix}_min"] = min(finite)
    target[f"{prefix}_max"] = max(finite)


def _mean_abs_diff(values: Sequence[float]) -> float:
    if len(values) < 2:
        return 0.0
    return statistics.fmean(abs(right - left) for left, right in zip(values, values[1:]))
