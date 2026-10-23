from __future__ import annotations

import math
import statistics
from dataclasses import dataclass
from typing import Iterable, Sequence

from .alignment import AlignedRow, select_channels
from .binary_log import SensorDescriptor, SensorEventRecord, SessionLog

NANOS_PER_SECOND = 1_000_000_000

EXPECTED_RATES_HZ = {
    "accel": 50.0,
    "gyro": 50.0,
    "gravity": 50.0,
    "mag": 20.0,
}


@dataclass(frozen=True)
class SignalQuality:
    available: bool
    sample_count: int
    expected_sample_count: float
    sample_ratio: float
    median_period_ms: float
    p95_period_ms: float
    max_gap_ms: float
    timestamp_disorder_count: int
    saturation_ratio: float

    @classmethod
    def unavailable(cls, expected_sample_count: float = 0.0) -> "SignalQuality":
        return cls(
            available=False,
            sample_count=0,
            expected_sample_count=expected_sample_count,
            sample_ratio=0.0,
            median_period_ms=math.inf,
            p95_period_ms=math.inf,
            max_gap_ms=math.inf,
            timestamp_disorder_count=0,
            saturation_ratio=0.0,
        )


@dataclass(frozen=True)
class MagneticQuality:
    available: bool
    field_mean_ut: float
    field_std_ut: float
    field_deviation_score: float
    spike_ratio: float

    @classmethod
    def unavailable(cls) -> "MagneticQuality":
        return cls(False, 0.0, 0.0, 1.0, 0.0)


@dataclass(frozen=True)
class SessionQualityReport:
    duration_seconds: float
    accel: SignalQuality
    aligned_accel_valid_ratio: float
    clean_end: bool
    quality_ok: bool
    failure_reasons: tuple[str, ...]


def signal_quality(
    events: Sequence[SensorEventRecord],
    start_ns: int,
    end_ns: int,
    expected_rate_hz: float,
    descriptor: SensorDescriptor | None = None,
) -> SignalQuality:
    duration_seconds = max(0.0, (end_ns - start_ns) / NANOS_PER_SECOND)
    expected_count = duration_seconds * expected_rate_hz
    selected = [event for event in events if start_ns <= event.elapsed_ns < end_ns]
    if not selected:
        return SignalQuality.unavailable(expected_count)

    disorder_count = sum(
        right.elapsed_ns < left.elapsed_ns for left, right in zip(selected, selected[1:])
    )
    ordered = sorted(selected, key=lambda event: event.elapsed_ns)
    intervals_ms = [
        (right.elapsed_ns - left.elapsed_ns) / 1_000_000.0
        for left, right in zip(ordered, ordered[1:])
        if right.elapsed_ns > left.elapsed_ns
    ]
    median_period = statistics.median(intervals_ms) if intervals_ms else math.inf
    p95_period = percentile(intervals_ms, 0.95) if intervals_ms else math.inf
    max_gap = max(intervals_ms) if intervals_ms else math.inf

    saturation_ratio = 0.0
    if descriptor is not None and descriptor.max_range > 0.0:
        threshold = descriptor.max_range * 0.98
        total_values = sum(len(event.values) for event in ordered)
        saturated = sum(
            abs(value) >= threshold for event in ordered for value in event.values
        )
        saturation_ratio = saturated / total_values if total_values else 0.0

    return SignalQuality(
        available=True,
        sample_count=len(ordered),
        expected_sample_count=expected_count,
        sample_ratio=len(ordered) / expected_count if expected_count > 0 else 0.0,
        median_period_ms=median_period,
        p95_period_ms=p95_period,
        max_gap_ms=max_gap,
        timestamp_disorder_count=disorder_count,
        saturation_ratio=saturation_ratio,
    )


def magnetic_quality(events: Sequence[SensorEventRecord]) -> MagneticQuality:
    vectors = [event.values for event in events if len(event.values) >= 3]
    if not vectors:
        return MagneticQuality.unavailable()
    magnitudes = [math.sqrt(x * x + y * y + z * z) for x, y, z, *_ in vectors]
    mean = statistics.fmean(magnitudes)
    std = statistics.pstdev(magnitudes) if len(magnitudes) > 1 else 0.0

    # Broad physical plausibility prior. Device-specific calibration is learned from the
    # training distribution; this score is evidence, not a hard validity decision.
    outside_ratio = sum(value < 20.0 or value > 80.0 for value in magnitudes) / len(magnitudes)
    spikes = [abs(right - left) for left, right in zip(magnitudes, magnitudes[1:])]
    spike_ratio = sum(delta > 10.0 for delta in spikes) / len(spikes) if spikes else 0.0
    deviation_score = min(1.0, max(outside_ratio, spike_ratio, std / 30.0))
    return MagneticQuality(True, mean, std, deviation_score, spike_ratio)


def freshness_score(
    age_ns: int | None,
    fresh_ns: int = 5 * NANOS_PER_SECOND,
    stale_ns: int = 30 * NANOS_PER_SECOND,
) -> float:
    if age_ns is None or age_ns < 0:
        return 0.0
    if age_ns <= fresh_ns:
        return 1.0
    if age_ns >= stale_ns:
        return 0.0
    return 1.0 - (age_ns - fresh_ns) / (stale_ns - fresh_ns)


def assess_session_quality(
    session: SessionLog,
    aligned_rows: Sequence[AlignedRow],
    minimum_duration_seconds: float = 30.0,
) -> SessionQualityReport:
    clean_end = session.clean_end
    duration_seconds = (
        (clean_end.elapsed_ns - session.header.session_start_elapsed_ns) / NANOS_PER_SECOND
        if clean_end is not None
        else 0.0
    )
    selected = select_channels(session.header.sensors)
    accel_id = selected.get("accel")
    descriptors = session.header.sensors_by_id
    accel_events = events_for_sensor(session, accel_id)
    end_ns = clean_end.elapsed_ns if clean_end is not None else _last_event_timestamp(session)
    accel = signal_quality(
        accel_events,
        session.header.session_start_elapsed_ns,
        end_ns,
        EXPECTED_RATES_HZ["accel"],
        descriptors.get(accel_id) if accel_id is not None else None,
    )
    valid_ratio = (
        sum(row.valid.get("accel", False) for row in aligned_rows) / len(aligned_rows)
        if aligned_rows
        else 0.0
    )

    reasons: list[str] = []
    if clean_end is None or session.truncated:
        reasons.append("missing_clean_end")
    if duration_seconds < minimum_duration_seconds:
        reasons.append("duration_below_30s")
    if not accel.available:
        reasons.append("accelerometer_missing")
    elif accel.median_period_ms > 50.0:  # <20 Hz
        reasons.append("accelerometer_rate_below_20hz")
    if accel.max_gap_ms > 1_000.0:
        reasons.append("accelerometer_gap_above_1s")
    if valid_ratio < 0.90:
        reasons.append("aligned_accel_valid_below_90pct")
    if session.header.activity_label in {"", "unknown"}:
        reasons.append("activity_label_unknown")
    if session.header.posture_label in {"", "unknown"}:
        reasons.append("posture_label_unknown")

    return SessionQualityReport(
        duration_seconds=duration_seconds,
        accel=accel,
        aligned_accel_valid_ratio=valid_ratio,
        clean_end=clean_end is not None and not session.truncated,
        quality_ok=not reasons,
        failure_reasons=tuple(reasons),
    )


def events_for_sensor(
    session: SessionLog,
    sensor_id: int | None,
) -> list[SensorEventRecord]:
    if sensor_id is None:
        return []
    return [event for event in session.sensor_events if event.sensor_id == sensor_id]


def percentile(values: Sequence[float], quantile: float) -> float:
    if not values:
        return math.nan
    if not 0.0 <= quantile <= 1.0:
        raise ValueError("quantile must be in [0, 1]")
    ordered = sorted(values)
    position = (len(ordered) - 1) * quantile
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] * (1.0 - fraction) + ordered[upper] * fraction


def vector_magnitudes(events: Iterable[SensorEventRecord]) -> list[float]:
    return [
        math.sqrt(event.values[0] ** 2 + event.values[1] ** 2 + event.values[2] ** 2)
        for event in events
        if len(event.values) >= 3
    ]


def _last_event_timestamp(session: SessionLog) -> int:
    return max(
        (event.elapsed_ns for event in session.sensor_events),
        default=session.header.session_start_elapsed_ns,
    )
