from __future__ import annotations

import hashlib
import json
import math
from dataclasses import dataclass
from typing import Iterator, Sequence

from .alignment import (
    DEFAULT_PERIOD_NS,
    AlignedRow,
    align_session,
    iter_windows,
    select_channels,
)
from .binary_log import SensorDescriptor, SensorEventRecord, SessionLog
from .quality import (
    EXPECTED_RATES_HZ,
    NANOS_PER_SECOND,
    events_for_sensor,
    freshness_score,
    magnetic_quality,
    signal_quality,
)

ACTIVITY_LABELS = ("still", "walking", "running", "in_vehicle")
CARRY_LABELS = ("desk_faceup", "desk_facedown", "in_hand", "in_pocket", "in_bag")

CONTINUOUS_CHANNELS = (
    "accel_x",
    "accel_y",
    "accel_z",
    "gyro_x",
    "gyro_y",
    "gyro_z",
    "mag_x",
    "mag_y",
    "mag_z",
    "gravity_x",
    "gravity_y",
    "gravity_z",
)

ENVIRONMENT_FEATURES = (
    "log_lux",
    "cct_norm",
    "proximity_distance",
    "proximity_near",
    "lux_age_s",
    "cct_age_s",
    "proximity_age_s",
    "lux_freshness",
    "cct_freshness",
    "proximity_freshness",
    "lux_valid",
    "cct_valid",
    "proximity_valid",
)

EVENT_IDENTITIES = (
    "step_detector",
    "significant_motion",
    "tilt",
    "vendor_raiseup",
    "vendor_putdown",
    "vendor_car",
    "vendor_activity",
)

EVENT_FEATURES = (
    "step_delta",
    "step_rate_hz",
    "time_since_step_s",
    *tuple(name for identity in EVENT_IDENTITIES for name in (
        f"{identity}_in_window",
        f"{identity}_age_s",
        f"{identity}_available",
    )),
)

MOTION_QUALITY_FEATURES = tuple(
    name
    for channel in ("accel", "gyro", "mag", "gravity")
    for name in (
        f"{channel}_available",
        f"{channel}_sample_ratio",
        f"{channel}_period_ratio",
        f"{channel}_max_gap_s",
        f"{channel}_saturation_ratio",
    )
) + (
    "mag_field_mean_norm",
    "mag_field_std_norm",
    "mag_interference_score",
    "mag_spike_ratio",
)


def _schema_hash() -> str:
    payload = json.dumps(
        {
            "continuous": CONTINUOUS_CHANNELS,
            "environment": ENVIRONMENT_FEATURES,
            "event": EVENT_FEATURES,
            "motion_quality": MOTION_QUALITY_FEATURES,
            "activity": ACTIVITY_LABELS,
            "carry": CARRY_LABELS,
        },
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()[:16]


FUSION_SCHEMA_HASH = _schema_hash()


@dataclass(frozen=True)
class FusionExample:
    session_id: str
    participant_id: str
    window_start_ns: int
    window_end_ns: int
    continuous_values: tuple[tuple[float, ...], ...]
    continuous_valid: tuple[tuple[float, ...], ...]
    environment_values: tuple[float, ...]
    event_values: tuple[float, ...]
    motion_quality: tuple[float, ...]
    activity_index: int
    carry_index: int
    schema_hash: str = FUSION_SCHEMA_HASH

    def validate(self) -> None:
        if len(self.continuous_values) != len(CONTINUOUS_CHANNELS):
            raise ValueError("continuous channel count mismatch")
        if len(self.continuous_valid) != len(CONTINUOUS_CHANNELS):
            raise ValueError("continuous mask channel count mismatch")
        lengths = {len(channel) for channel in self.continuous_values}
        mask_lengths = {len(channel) for channel in self.continuous_valid}
        if lengths != {100} or mask_lengths != {100}:
            raise ValueError("fusion windows must contain exactly 100 grid points")
        if len(self.environment_values) != len(ENVIRONMENT_FEATURES):
            raise ValueError("environment feature count mismatch")
        if len(self.event_values) != len(EVENT_FEATURES):
            raise ValueError("event feature count mismatch")
        if len(self.motion_quality) != len(MOTION_QUALITY_FEATURES):
            raise ValueError("motion quality feature count mismatch")
        if not 0 <= self.activity_index < len(ACTIVITY_LABELS):
            raise ValueError("invalid activity index")
        if not 0 <= self.carry_index < len(CARRY_LABELS):
            raise ValueError("invalid carry index")


def build_fusion_examples(
    session: SessionLog,
    edge_trim_ns: int = 3 * NANOS_PER_SECOND,
) -> list[FusionExample]:
    if session.header.activity_label not in ACTIVITY_LABELS:
        return []
    if session.header.posture_label not in CARRY_LABELS:
        return []

    rows = align_session(session)
    if not rows:
        return []
    usable = [
        row
        for row in rows
        if row.elapsed_ns >= rows[0].elapsed_ns + edge_trim_ns
        and row.elapsed_ns <= rows[-1].elapsed_ns - edge_trim_ns
    ]
    examples: list[FusionExample] = []
    for window in iter_windows(usable):
        example = _build_example(session, window)
        example.validate()
        examples.append(example)
    return examples


def _build_example(session: SessionLog, rows: Sequence[AlignedRow]) -> FusionExample:
    start_ns = rows[0].elapsed_ns
    end_ns = rows[-1].elapsed_ns + DEFAULT_PERIOD_NS
    continuous_values: list[tuple[float, ...]] = []
    continuous_valid: list[tuple[float, ...]] = []
    for channel in CONTINUOUS_CHANNELS:
        prefix = channel.split("_", 1)[0]
        valid = tuple(1.0 if row.valid.get(prefix, False) else 0.0 for row in rows)
        values = tuple(
            finite_or_zero(row.values.get(channel, math.nan)) if mask else 0.0
            for row, mask in zip(rows, valid)
        )
        continuous_values.append(values)
        continuous_valid.append(valid)

    selected = select_channels(session.header.sensors)
    environment = _environment_features(session, rows, selected)
    events = _event_features(session, start_ns, end_ns, selected)
    motion_quality = _motion_quality_features(session, start_ns, end_ns, selected)
    return FusionExample(
        session_id=session.header.session_id,
        participant_id=session.header.participant_id,
        window_start_ns=start_ns,
        window_end_ns=end_ns,
        continuous_values=tuple(continuous_values),
        continuous_valid=tuple(continuous_valid),
        environment_values=tuple(environment[name] for name in ENVIRONMENT_FEATURES),
        event_values=tuple(events[name] for name in EVENT_FEATURES),
        motion_quality=tuple(motion_quality[name] for name in MOTION_QUALITY_FEATURES),
        activity_index=ACTIVITY_LABELS.index(session.header.activity_label),
        carry_index=CARRY_LABELS.index(session.header.posture_label),
    )


def _environment_features(
    session: SessionLog,
    rows: Sequence[AlignedRow],
    selected: dict[str, int],
) -> dict[str, float]:
    last = rows[-1]
    lux = last.values.get("lux", math.nan)
    cct = last.values.get("cct", math.nan)
    proximity = last.values.get("proximity", math.nan)
    prox_descriptor = session.header.sensors_by_id.get(selected.get("proximity", -1))
    near_threshold = (
        min(3.0, prox_descriptor.max_range * 0.2)
        if prox_descriptor is not None and prox_descriptor.max_range > 0
        else 1.0
    )

    lux_age = last.ages_ns.get("lux")
    cct_age = last.ages_ns.get("cct")
    proximity_age = last.ages_ns.get("proximity")
    return {
        "log_lux": math.log1p(max(0.0, lux)) if math.isfinite(lux) else 0.0,
        "cct_norm": cct / 10_000.0 if math.isfinite(cct) else 0.0,
        "proximity_distance": proximity if math.isfinite(proximity) else 0.0,
        "proximity_near": (
            1.0 if math.isfinite(proximity) and proximity < near_threshold else 0.0
        ),
        "lux_age_s": capped_age_seconds(lux_age),
        "cct_age_s": capped_age_seconds(cct_age),
        "proximity_age_s": capped_age_seconds(proximity_age),
        "lux_freshness": freshness_score(lux_age),
        "cct_freshness": freshness_score(cct_age),
        "proximity_freshness": freshness_score(proximity_age),
        "lux_valid": float(last.valid.get("lux", False)),
        "cct_valid": float(last.valid.get("cct", False)),
        "proximity_valid": float(last.valid.get("proximity", False)),
    }


def _event_features(
    session: SessionLog,
    start_ns: int,
    end_ns: int,
    selected: dict[str, int],
) -> dict[str, float]:
    descriptors = {descriptor.sensor_id: descriptor for descriptor in session.header.sensors}
    step_events = sorted(
        events_for_sensor(session, selected.get("step_counter")),
        key=lambda event: event.elapsed_ns,
    )
    before_or_at_end = [event for event in step_events if event.elapsed_ns < end_ns]
    before_or_at_start = [event for event in step_events if event.elapsed_ns <= start_ns]
    end_count = _first_value(before_or_at_end[-1]) if before_or_at_end else None
    start_count = _first_value(before_or_at_start[-1]) if before_or_at_start else end_count
    step_delta = max(0.0, (end_count or 0.0) - (start_count or 0.0))
    duration_seconds = (end_ns - start_ns) / NANOS_PER_SECOND
    last_step_ns = before_or_at_end[-1].elapsed_ns if before_or_at_end else None

    result = {
        "step_delta": step_delta,
        "step_rate_hz": step_delta / duration_seconds if duration_seconds > 0 else 0.0,
        "time_since_step_s": capped_age_seconds(
            end_ns - last_step_ns if last_step_ns is not None else None
        ),
    }
    identity_sensor_ids = _event_sensor_ids(session.header.sensors)
    for identity in EVENT_IDENTITIES:
        sensor_ids = identity_sensor_ids.get(identity, set())
        identity_events = sorted(
            (
                event
                for event in session.sensor_events
                if event.sensor_id in sensor_ids and event.elapsed_ns < end_ns
            ),
            key=lambda event: event.elapsed_ns,
        )
        in_window = any(event.elapsed_ns >= start_ns for event in identity_events)
        last_event_ns = identity_events[-1].elapsed_ns if identity_events else None
        result[f"{identity}_in_window"] = float(in_window)
        result[f"{identity}_age_s"] = capped_age_seconds(
            end_ns - last_event_ns if last_event_ns is not None else None
        )
        result[f"{identity}_available"] = float(bool(sensor_ids))

    # Keep this lookup alive as an explicit schema sanity check.
    if any(sensor_id not in descriptors for ids in identity_sensor_ids.values() for sensor_id in ids):
        raise ValueError("event sensor id missing from descriptor inventory")
    return result


def _motion_quality_features(
    session: SessionLog,
    start_ns: int,
    end_ns: int,
    selected: dict[str, int],
) -> dict[str, float]:
    result: dict[str, float] = {}
    descriptors = session.header.sensors_by_id
    for channel in ("accel", "gyro", "mag", "gravity"):
        sensor_id = selected.get(channel)
        descriptor = descriptors.get(sensor_id) if sensor_id is not None else None
        quality = signal_quality(
            events_for_sensor(session, sensor_id),
            start_ns,
            end_ns,
            EXPECTED_RATES_HZ[channel],
            descriptor,
        )
        expected_period_ms = 1_000.0 / EXPECTED_RATES_HZ[channel]
        result[f"{channel}_available"] = float(quality.available)
        result[f"{channel}_sample_ratio"] = min(1.5, quality.sample_ratio)
        result[f"{channel}_period_ratio"] = (
            min(10.0, quality.median_period_ms / expected_period_ms)
            if math.isfinite(quality.median_period_ms)
            else 10.0
        )
        result[f"{channel}_max_gap_s"] = (
            min(4.0, quality.max_gap_ms / 1_000.0)
            if math.isfinite(quality.max_gap_ms)
            else 4.0
        )
        result[f"{channel}_saturation_ratio"] = quality.saturation_ratio

    mag_id = selected.get("mag")
    mag_events = [
        event
        for event in events_for_sensor(session, mag_id)
        if start_ns <= event.elapsed_ns < end_ns
    ]
    mag = magnetic_quality(mag_events)
    result["mag_field_mean_norm"] = min(2.0, mag.field_mean_ut / 100.0)
    result["mag_field_std_norm"] = min(2.0, mag.field_std_ut / 50.0)
    result["mag_interference_score"] = mag.field_deviation_score
    result["mag_spike_ratio"] = mag.spike_ratio
    return result


def _event_sensor_ids(
    descriptors: Sequence[SensorDescriptor],
) -> dict[str, set[int]]:
    result = {identity: set() for identity in EVENT_IDENTITIES}
    for descriptor in descriptors:
        identity = f"{descriptor.name} {descriptor.string_type}".lower()
        if descriptor.android_type == 18:
            result["step_detector"].add(descriptor.sensor_id)
        if descriptor.android_type == 17:
            result["significant_motion"].add(descriptor.sensor_id)
        if descriptor.android_type == 22 or "tilt" in identity:
            result["tilt"].add(descriptor.sensor_id)
        if "raiseup" in identity:
            result["vendor_raiseup"].add(descriptor.sensor_id)
        if "putdown" in identity:
            result["vendor_putdown"].add(descriptor.sensor_id)
        if "car_navi" in identity or "vibration_classification" in identity:
            result["vendor_car"].add(descriptor.sensor_id)
        if "activity_wk" in identity or "activity_nwk" in identity:
            result["vendor_activity"].add(descriptor.sensor_id)
    return result


def capped_age_seconds(age_ns: int | None, cap_seconds: float = 600.0) -> float:
    if age_ns is None or age_ns < 0:
        return cap_seconds
    return min(cap_seconds, age_ns / NANOS_PER_SECOND)


def finite_or_zero(value: float) -> float:
    return value if math.isfinite(value) else 0.0


def _first_value(event: SensorEventRecord) -> float | None:
    return event.values[0] if event.values else None


def iter_fusion_examples(sessions: Sequence[SessionLog]) -> Iterator[FusionExample]:
    for session in sessions:
        yield from build_fusion_examples(session)
