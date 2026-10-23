from __future__ import annotations

import bisect
import math
from dataclasses import dataclass, field
from typing import Iterator, Sequence

from .binary_log import SensorDescriptor, SensorEventRecord, SessionLog

TYPE_ACCELEROMETER = 1
TYPE_MAGNETIC_FIELD = 2
TYPE_GYROSCOPE = 4
TYPE_LIGHT = 5
TYPE_PROXIMITY = 8
TYPE_GRAVITY = 9
TYPE_MAGNETIC_FIELD_UNCALIBRATED = 14
TYPE_STEP_COUNTER = 19

DEFAULT_PERIOD_NS = 40_000_000
LINEAR_MAX_GAP_NS = 200_000_000
MAG_NEAREST_MAX_DISTANCE_NS = 100_000_000
ON_CHANGE_MAX_AGE_NS = 10 * 60 * 1_000_000_000


@dataclass(frozen=True)
class AlignedRow:
    elapsed_ns: int
    values: dict[str, float]
    valid: dict[str, bool]
    ages_ns: dict[str, int | None] = field(default_factory=dict)


@dataclass(frozen=True)
class _EventSeries:
    events: Sequence[SensorEventRecord]
    timestamps: Sequence[int]


EMPTY_SERIES = _EventSeries((), ())


def align_session(session: SessionLog, period_ns: int = DEFAULT_PERIOD_NS) -> list[AlignedRow]:
    descriptors = session.header.sensors
    selected = select_channels(descriptors)
    events_by_sensor: dict[int, list[SensorEventRecord]] = {}
    for event in session.sensor_events:
        events_by_sensor.setdefault(event.sensor_id, []).append(event)
    for events in events_by_sensor.values():
        events.sort(key=lambda event: event.elapsed_ns)
    series_by_sensor = {
        sensor_id: _EventSeries(events, tuple(event.elapsed_ns for event in events))
        for sensor_id, events in events_by_sensor.items()
    }

    def series(channel: str) -> _EventSeries:
        sensor_id = selected.get(channel)
        return series_by_sensor.get(sensor_id, EMPTY_SERIES)

    accel_id = selected.get("accel")
    accel_events = events_by_sensor.get(accel_id, []) if accel_id is not None else []
    if len(accel_events) < 2:
        return []

    first = ((accel_events[0].elapsed_ns + period_ns - 1) // period_ns) * period_ns
    last = (accel_events[-1].elapsed_ns // period_ns) * period_ns
    if last < first:
        return []

    rows: list[AlignedRow] = []
    for timestamp in range(first, last + 1, period_ns):
        values: dict[str, float] = {}
        valid: dict[str, bool] = {}
        ages_ns: dict[str, int | None] = {}
        _put_vector(
            values,
            valid,
            "accel",
            _linear(series("accel"), timestamp),
        )
        _put_vector(
            values,
            valid,
            "gyro",
            _linear(series("gyro"), timestamp),
        )
        _put_vector(
            values,
            valid,
            "gravity",
            _linear(series("gravity"), timestamp),
        )
        _put_vector(
            values,
            valid,
            "mag",
            _nearest(series("mag"), timestamp),
        )
        _put_forward_filled_scalar(
            values, valid, ages_ns, "lux", series("light"), timestamp
        )
        _put_forward_filled_scalar(
            values, valid, ages_ns, "proximity", series("proximity"), timestamp
        )
        _put_forward_filled_scalar(
            values, valid, ages_ns, "cct", series("cct"), timestamp
        )
        _put_forward_filled_scalar(
            values,
            valid,
            ages_ns,
            "step_counter",
            series("step_counter"),
            timestamp,
            max_age_ns=None,
        )
        rows.append(AlignedRow(timestamp, values, valid, ages_ns))
    return rows


def iter_windows(
    rows: Sequence[AlignedRow],
    window_size: int = 100,
    step_size: int = 50,
    minimum_accel_valid_ratio: float = 0.9,
) -> Iterator[Sequence[AlignedRow]]:
    for start in range(0, len(rows) - window_size + 1, step_size):
        window = rows[start : start + window_size]
        valid_count = sum(row.valid.get("accel", False) for row in window)
        if valid_count / window_size >= minimum_accel_valid_ratio:
            yield window


def select_channels(descriptors: Sequence[SensorDescriptor]) -> dict[str, int]:
    by_type: dict[int, list[SensorDescriptor]] = {}
    for descriptor in descriptors:
        by_type.setdefault(descriptor.android_type, []).append(descriptor)

    selected: dict[str, int] = {}

    def first(sensor_type: int) -> int | None:
        candidates = by_type.get(sensor_type, [])
        non_wakeup = next((item for item in candidates if not item.is_wake_up), None)
        chosen = non_wakeup or (candidates[0] if candidates else None)
        return chosen.sensor_id if chosen else None

    for channel, sensor_type in (
        ("accel", TYPE_ACCELEROMETER),
        ("gyro", TYPE_GYROSCOPE),
        ("gravity", TYPE_GRAVITY),
        ("light", TYPE_LIGHT),
        ("proximity", TYPE_PROXIMITY),
        ("step_counter", TYPE_STEP_COUNTER),
    ):
        sensor_id = first(sensor_type)
        if sensor_id is not None:
            selected[channel] = sensor_id

    mag_id = first(TYPE_MAGNETIC_FIELD_UNCALIBRATED)
    if mag_id is None:
        mag_id = first(TYPE_MAGNETIC_FIELD)
    if mag_id is not None:
        selected["mag"] = mag_id

    cct = next(
        (
            descriptor
            for descriptor in descriptors
            if "cct" in f"{descriptor.name} {descriptor.string_type}".lower()
        ),
        None,
    )
    if cct is not None:
        selected["cct"] = cct.sensor_id
    return selected


# Compatibility alias for callers written against alignment v1.
_select_channels = select_channels


def _linear(series: _EventSeries, timestamp: int) -> tuple[float, ...] | None:
    events = series.events
    if not events:
        return None
    right = bisect.bisect_left(series.timestamps, timestamp)
    if right < len(events) and events[right].elapsed_ns == timestamp:
        return events[right].values
    if right == 0 or right >= len(events):
        return None
    before = events[right - 1]
    after = events[right]
    gap = after.elapsed_ns - before.elapsed_ns
    if gap <= 0 or gap > LINEAR_MAX_GAP_NS:
        return None
    count = min(len(before.values), len(after.values))
    if count == 0:
        return None
    ratio = (timestamp - before.elapsed_ns) / gap
    return tuple(
        before.values[index] + (after.values[index] - before.values[index]) * ratio
        for index in range(count)
    )


def _nearest(series: _EventSeries, timestamp: int) -> tuple[float, ...] | None:
    events = series.events
    if not events:
        return None
    index = bisect.bisect_left(series.timestamps, timestamp)
    candidates = []
    if index < len(events):
        candidates.append(events[index])
    if index > 0:
        candidates.append(events[index - 1])
    chosen = min(candidates, key=lambda event: abs(event.elapsed_ns - timestamp))
    if abs(chosen.elapsed_ns - timestamp) > MAG_NEAREST_MAX_DISTANCE_NS:
        return None
    return chosen.values


def _forward_fill_event(
    series: _EventSeries,
    timestamp: int,
    max_age_ns: int | None = ON_CHANGE_MAX_AGE_NS,
) -> SensorEventRecord | None:
    events = series.events
    if not events:
        return None
    index = bisect.bisect_right(series.timestamps, timestamp) - 1
    if index < 0:
        return None
    event = events[index]
    if max_age_ns is not None and timestamp - event.elapsed_ns > max_age_ns:
        return None
    return event


def _put_vector(
    values: dict[str, float],
    valid: dict[str, bool],
    prefix: str,
    sample: tuple[float, ...] | None,
) -> None:
    is_valid = sample is not None and len(sample) >= 3
    valid[prefix] = is_valid
    for index, axis in enumerate(("x", "y", "z")):
        values[f"{prefix}_{axis}"] = sample[index] if is_valid else math.nan


def _put_scalar(
    values: dict[str, float],
    valid: dict[str, bool],
    name: str,
    sample: tuple[float, ...] | None,
) -> None:
    is_valid = sample is not None and len(sample) >= 1
    valid[name] = is_valid
    values[name] = sample[0] if is_valid else math.nan


def _put_forward_filled_scalar(
    values: dict[str, float],
    valid: dict[str, bool],
    ages_ns: dict[str, int | None],
    name: str,
    series: _EventSeries,
    timestamp: int,
    max_age_ns: int | None = ON_CHANGE_MAX_AGE_NS,
) -> None:
    event = _forward_fill_event(series, timestamp, max_age_ns)
    _put_scalar(values, valid, name, event.values if event is not None else None)
    ages_ns[name] = timestamp - event.elapsed_ns if event is not None else None
