from __future__ import annotations

import io
import struct
import tempfile
import unittest
from pathlib import Path

from training.sce_data.binary_log import (
    MAGIC,
    CleanEndRecord,
    MarkerRecord,
    SensorEventRecord,
    read_session,
)


def utf8(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def frame(record_type: int, payload: bytes) -> bytes:
    framed = bytes([record_type]) + payload
    return struct.pack(">I", len(framed)) + framed


def fixture_bytes(include_end: bool = True) -> bytes:
    header = io.BytesIO()
    header.write(struct.pack(">qq", 1_000, 2_000))
    for value in (
        "session-1",
        "p01",
        "still",
        "in_hand",
        "indoor",
        "dark",
        "fixture",
        "vivo",
        "V2547A",
        "PD2547",
        "fixture/fingerprint",
        "test",
    ):
        header.write(utf8(value))
    header.write(struct.pack(">H", 1))
    header.write(struct.pack(">Hiiiii", 0, 1, 0, 20_000, 1_000_000, 10_000))
    header.write(struct.pack(">ffB", 0.001, 78.4, 0))
    header.write(utf8("accelerometer"))
    header.write(utf8("ST"))
    header.write(utf8("android.sensor.accelerometer"))
    header_payload = header.getvalue()

    result = io.BytesIO()
    result.write(MAGIC)
    result.write(struct.pack(">HHI", 1, 0, len(header_payload)))
    result.write(header_payload)
    sensor_payload = struct.pack(">qHbBfff", 1_100, 0, 3, 3, 1.0, 2.0, 3.0)
    result.write(frame(1, sensor_payload))
    result.write(frame(2, struct.pack(">q", 1_200) + utf8("pickup") + utf8("test")))
    if include_end:
        result.write(frame(3, struct.pack(">qqq", 1_300, 2_300, 2)))
    return result.getvalue()


class BinaryLogTest(unittest.TestCase):
    def test_reads_header_records_and_clean_end(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.scelog"
            path.write_bytes(fixture_bytes())

            session = read_session(path)

            self.assertFalse(session.truncated)
            self.assertEqual("session-1", session.header.session_id)
            self.assertEqual(1, len(session.header.sensors))
            self.assertIsInstance(session.records[0], SensorEventRecord)
            self.assertEqual((1.0, 2.0, 3.0), session.records[0].values)
            self.assertIsInstance(session.records[1], MarkerRecord)
            self.assertIsInstance(session.records[2], CleanEndRecord)

    def test_missing_clean_end_is_recoverable_and_marked_truncated(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "truncated.scelog"
            path.write_bytes(fixture_bytes(include_end=False))

            session = read_session(path)

            self.assertTrue(session.truncated)
            self.assertEqual(1, len(session.sensor_events))
            self.assertEqual(1, len(session.markers))


if __name__ == "__main__":
    unittest.main()
