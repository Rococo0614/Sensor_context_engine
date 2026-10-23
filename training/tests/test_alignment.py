from __future__ import annotations

import unittest
from pathlib import Path

from training.sce_data.alignment import DEFAULT_PERIOD_NS, align_session, iter_windows
from training.sce_data.binary_log import (
    SensorDescriptor,
    SensorEventRecord,
    SessionHeader,
    SessionLog,
)


def descriptor(sensor_id: int, android_type: int, string_type: str) -> SensorDescriptor:
    return SensorDescriptor(
        sensor_id=sensor_id,
        android_type=android_type,
        reporting_mode=0,
        min_delay_us=0,
        max_delay_us=0,
        fifo_max_event_count=0,
        resolution=1.0,
        max_range=100.0,
        is_wake_up=False,
        name=string_type,
        vendor="fixture",
        string_type=string_type,
    )


class AlignmentTest(unittest.TestCase):
    def test_grid_is_25hz_and_on_change_values_are_forward_filled(self) -> None:
        accel = descriptor(0, 1, "android.sensor.accelerometer")
        light = descriptor(1, 5, "android.sensor.light")
        events = []
        for index in range(5):
            events.append(
                SensorEventRecord(
                    elapsed_ns=index * DEFAULT_PERIOD_NS,
                    sensor_id=0,
                    accuracy=3,
                    values=(float(index), 2.0, 3.0),
                )
            )
        events.append(SensorEventRecord(0, 1, 3, (5.0,)))
        session = session_log((accel, light), tuple(events))

        rows = align_session(session)

        self.assertEqual(5, len(rows))
        self.assertTrue(all(
            right.elapsed_ns - left.elapsed_ns == DEFAULT_PERIOD_NS
            for left, right in zip(rows, rows[1:])
        ))
        self.assertEqual([5.0] * 5, [row.values["lux"] for row in rows])
        self.assertEqual(
            [index * DEFAULT_PERIOD_NS for index in range(5)],
            [row.ages_ns["lux"] for row in rows],
        )
        self.assertTrue(all(row.valid["accel"] for row in rows))

    def test_four_second_window_has_100_rows(self) -> None:
        accel = descriptor(0, 1, "android.sensor.accelerometer")
        events = tuple(
            SensorEventRecord(
                elapsed_ns=index * DEFAULT_PERIOD_NS,
                sensor_id=0,
                accuracy=3,
                values=(0.0, 0.0, 9.81),
            )
            for index in range(150)
        )

        windows = list(iter_windows(align_session(session_log((accel,), events))))

        self.assertEqual(2, len(windows))
        self.assertTrue(all(len(window) == 100 for window in windows))


def session_log(
    sensors: tuple[SensorDescriptor, ...],
    events: tuple[SensorEventRecord, ...],
) -> SessionLog:
    return SessionLog(
        path=Path("fixture.scelog"),
        header=SessionHeader(
            session_start_elapsed_ns=0,
            session_start_wall_ms=0,
            session_id="fixture",
            participant_id="p01",
            activity_label="still",
            posture_label="in_hand",
            environment_label="indoor",
            light_label="normal",
            note="",
            manufacturer="vivo",
            model="V2547A",
            device="PD2547",
            build_fingerprint="fixture",
            app_version="test",
            sensors=sensors,
        ),
        records=events,
        truncated=True,
        unknown_record_count=0,
    )


if __name__ == "__main__":
    unittest.main()
