from __future__ import annotations

from pathlib import Path

from training.sce_data.binary_log import (
    CleanEndRecord,
    SensorDescriptor,
    SensorEventRecord,
    SessionHeader,
    SessionLog,
)


def descriptor(
    sensor_id: int,
    android_type: int,
    string_type: str,
    max_range: float = 100.0,
    is_wake_up: bool = False,
) -> SensorDescriptor:
    return SensorDescriptor(
        sensor_id=sensor_id,
        android_type=android_type,
        reporting_mode=0,
        min_delay_us=0,
        max_delay_us=0,
        fifo_max_event_count=10_000,
        resolution=0.001,
        max_range=max_range,
        is_wake_up=is_wake_up,
        name=string_type,
        vendor="fixture",
        string_type=string_type,
    )


def fusion_session(duration_seconds: int = 12, include_gyro: bool = True) -> SessionLog:
    sensors = [
        descriptor(0, 1, "android.sensor.accelerometer", max_range=80.0),
        descriptor(2, 14, "android.sensor.magnetic_field_uncalibrated", max_range=2_000.0),
        descriptor(3, 9, "android.sensor.gravity", max_range=20.0),
        descriptor(4, 5, "android.sensor.light", max_range=100_000.0),
        descriptor(5, 8, "android.sensor.proximity", max_range=5.0, is_wake_up=True),
        descriptor(6, 19, "android.sensor.step_counter", max_range=100_000.0),
        descriptor(7, 18, "android.sensor.step_detector", max_range=1.0),
    ]
    if include_gyro:
        sensors.insert(1, descriptor(1, 4, "android.sensor.gyroscope", max_range=40.0))

    events: list[SensorEventRecord | CleanEndRecord] = []
    for index in range(duration_seconds * 50):
        timestamp = index * 20_000_000
        events.append(SensorEventRecord(timestamp, 0, 3, (0.0, 6.0, 8.0)))
        events.append(SensorEventRecord(timestamp, 3, 3, (0.0, 6.0, 8.0)))
        if include_gyro:
            events.append(SensorEventRecord(timestamp, 1, 3, (0.01, 0.02, 0.01)))
    for index in range(duration_seconds * 20):
        timestamp = index * 50_000_000
        events.append(SensorEventRecord(timestamp, 2, 3, (30.0, 5.0, 35.0)))
    events.extend(
        [
            SensorEventRecord(0, 4, 3, (100.0,)),
            SensorEventRecord(0, 5, 3, (5.0,)),
            SensorEventRecord(0, 6, 3, (10.0,)),
            SensorEventRecord(8_000_000_000, 6, 3, (12.0,)),
            SensorEventRecord(8_000_000_000, 7, 3, (1.0,)),
        ]
    )
    events.sort(key=lambda event: event.elapsed_ns)
    sensor_event_count = len(events)
    events.append(
        CleanEndRecord(
            elapsed_ns=duration_seconds * 1_000_000_000,
            wall_ms=1_000 + duration_seconds * 1_000,
            data_record_count=sensor_event_count,
        )
    )
    return SessionLog(
        path=Path("fusion-fixture.scelog"),
        header=SessionHeader(
            session_start_elapsed_ns=0,
            session_start_wall_ms=1_000,
            session_id="fusion-fixture",
            participant_id="p01",
            activity_label="still",
            posture_label="in_hand",
            environment_label="indoor",
            light_label="normal",
            note="fixture",
            manufacturer="vivo",
            model="V2547A",
            device="PD2547",
            build_fingerprint="fixture",
            app_version="test",
            sensors=tuple(sensors),
        ),
        records=tuple(events),
        truncated=False,
        unknown_record_count=0,
    )
