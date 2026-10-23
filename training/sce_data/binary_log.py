from __future__ import annotations

import argparse
import io
import json
import struct
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import BinaryIO, Union

MAGIC = b"SCELOG01"
FORMAT_VERSION = 1
RECORD_SENSOR_EVENT = 1
RECORD_MARKER = 2
RECORD_CLEAN_END = 3
MAX_FRAME_BYTES = 1024 * 1024


class BinaryFormatError(ValueError):
    pass


@dataclass(frozen=True)
class SensorDescriptor:
    sensor_id: int
    android_type: int
    reporting_mode: int
    min_delay_us: int
    max_delay_us: int
    fifo_max_event_count: int
    resolution: float
    max_range: float
    is_wake_up: bool
    name: str
    vendor: str
    string_type: str


@dataclass(frozen=True)
class SessionHeader:
    session_start_elapsed_ns: int
    session_start_wall_ms: int
    session_id: str
    participant_id: str
    activity_label: str
    posture_label: str
    environment_label: str
    light_label: str
    note: str
    manufacturer: str
    model: str
    device: str
    build_fingerprint: str
    app_version: str
    sensors: tuple[SensorDescriptor, ...]

    @property
    def sensors_by_id(self) -> dict[int, SensorDescriptor]:
        return {sensor.sensor_id: sensor for sensor in self.sensors}


@dataclass(frozen=True)
class SensorEventRecord:
    elapsed_ns: int
    sensor_id: int
    accuracy: int
    values: tuple[float, ...]


@dataclass(frozen=True)
class MarkerRecord:
    elapsed_ns: int
    marker: str
    note: str


@dataclass(frozen=True)
class CleanEndRecord:
    elapsed_ns: int
    wall_ms: int
    data_record_count: int


Record = Union[SensorEventRecord, MarkerRecord, CleanEndRecord]


@dataclass(frozen=True)
class SessionLog:
    path: Path
    header: SessionHeader
    records: tuple[Record, ...]
    truncated: bool
    unknown_record_count: int

    @property
    def sensor_events(self) -> tuple[SensorEventRecord, ...]:
        return tuple(record for record in self.records if isinstance(record, SensorEventRecord))

    @property
    def markers(self) -> tuple[MarkerRecord, ...]:
        return tuple(record for record in self.records if isinstance(record, MarkerRecord))

    @property
    def clean_end(self) -> CleanEndRecord | None:
        return next(
            (record for record in reversed(self.records) if isinstance(record, CleanEndRecord)),
            None,
        )

    def summary(self) -> dict[str, object]:
        counts: dict[str, int] = {}
        sensors = self.header.sensors_by_id
        for event in self.sensor_events:
            descriptor = sensors.get(event.sensor_id)
            key = descriptor.string_type or descriptor.name if descriptor else f"sensor:{event.sensor_id}"
            counts[key] = counts.get(key, 0) + 1
        end = self.clean_end
        duration_seconds = None
        if end is not None:
            duration_seconds = (end.elapsed_ns - self.header.session_start_elapsed_ns) / 1e9
        return {
            "path": str(self.path),
            "session_id": self.header.session_id,
            "participant_id": self.header.participant_id,
            "labels": {
                "activity": self.header.activity_label,
                "posture": self.header.posture_label,
                "environment": self.header.environment_label,
                "light": self.header.light_label,
            },
            "inventory_sensor_count": len(self.header.sensors),
            "sensor_event_count": len(self.sensor_events),
            "marker_count": len(self.markers),
            "duration_seconds": duration_seconds,
            "truncated": self.truncated,
            "unknown_record_count": self.unknown_record_count,
            "events_by_sensor": counts,
        }


class _Reader:
    def __init__(self, stream: BinaryIO):
        self.stream = stream

    def exact(self, size: int) -> bytes:
        data = self.stream.read(size)
        if len(data) != size:
            raise EOFError(f"expected {size} bytes, received {len(data)}")
        return data

    def unpack(self, format_string: str):
        size = struct.calcsize(format_string)
        return struct.unpack(format_string, self.exact(size))

    def u8(self) -> int:
        return self.unpack(">B")[0]

    def i8(self) -> int:
        return self.unpack(">b")[0]

    def u16(self) -> int:
        return self.unpack(">H")[0]

    def i32(self) -> int:
        return self.unpack(">i")[0]

    def u32(self) -> int:
        return self.unpack(">I")[0]

    def i64(self) -> int:
        return self.unpack(">q")[0]

    def f32(self) -> float:
        return self.unpack(">f")[0]

    def utf8(self) -> str:
        length = self.u16()
        try:
            return self.exact(length).decode("utf-8")
        except UnicodeDecodeError as error:
            raise BinaryFormatError("invalid UTF-8 string") from error


def read_session(path: str | Path) -> SessionLog:
    path = Path(path)
    with path.open("rb") as stream:
        reader = _Reader(stream)
        if reader.exact(len(MAGIC)) != MAGIC:
            raise BinaryFormatError(f"{path}: invalid magic")
        version = reader.u16()
        if version != FORMAT_VERSION:
            raise BinaryFormatError(f"{path}: unsupported format version {version}")
        _flags = reader.u16()
        header_length = reader.u32()
        if header_length > MAX_FRAME_BYTES:
            raise BinaryFormatError(f"{path}: unreasonable header size {header_length}")
        header = _read_header(_Reader(io.BytesIO(reader.exact(header_length))))

        records: list[Record] = []
        unknown_count = 0
        truncated = False
        saw_clean_end = False
        while True:
            length_bytes = stream.read(4)
            if not length_bytes:
                break
            if len(length_bytes) != 4:
                truncated = True
                break
            frame_length = struct.unpack(">I", length_bytes)[0]
            if frame_length < 1 or frame_length > MAX_FRAME_BYTES:
                raise BinaryFormatError(f"{path}: invalid frame length {frame_length}")
            payload = stream.read(frame_length)
            if len(payload) != frame_length:
                truncated = True
                break

            frame_reader = _Reader(io.BytesIO(payload))
            record_type = frame_reader.u8()
            try:
                if record_type == RECORD_SENSOR_EVENT:
                    records.append(_read_sensor_event(frame_reader))
                elif record_type == RECORD_MARKER:
                    records.append(_read_marker(frame_reader))
                elif record_type == RECORD_CLEAN_END:
                    records.append(_read_clean_end(frame_reader))
                    saw_clean_end = True
                else:
                    unknown_count += 1
            except EOFError as error:
                raise BinaryFormatError(f"{path}: malformed record type {record_type}") from error

        if not saw_clean_end:
            truncated = True
        return SessionLog(
            path=path,
            header=header,
            records=tuple(records),
            truncated=truncated,
            unknown_record_count=unknown_count,
        )


def _read_header(reader: _Reader) -> SessionHeader:
    start_elapsed = reader.i64()
    start_wall = reader.i64()
    strings = [reader.utf8() for _ in range(12)]
    sensor_count = reader.u16()
    sensors = tuple(_read_sensor_descriptor(reader) for _ in range(sensor_count))
    return SessionHeader(
        session_start_elapsed_ns=start_elapsed,
        session_start_wall_ms=start_wall,
        session_id=strings[0],
        participant_id=strings[1],
        activity_label=strings[2],
        posture_label=strings[3],
        environment_label=strings[4],
        light_label=strings[5],
        note=strings[6],
        manufacturer=strings[7],
        model=strings[8],
        device=strings[9],
        build_fingerprint=strings[10],
        app_version=strings[11],
        sensors=sensors,
    )


def _read_sensor_descriptor(reader: _Reader) -> SensorDescriptor:
    sensor_id = reader.u16()
    android_type = reader.i32()
    reporting_mode = reader.i32()
    min_delay_us = reader.i32()
    max_delay_us = reader.i32()
    fifo_max_event_count = reader.i32()
    resolution = reader.f32()
    max_range = reader.f32()
    flags = reader.u8()
    return SensorDescriptor(
        sensor_id=sensor_id,
        android_type=android_type,
        reporting_mode=reporting_mode,
        min_delay_us=min_delay_us,
        max_delay_us=max_delay_us,
        fifo_max_event_count=fifo_max_event_count,
        resolution=resolution,
        max_range=max_range,
        is_wake_up=bool(flags & 1),
        name=reader.utf8(),
        vendor=reader.utf8(),
        string_type=reader.utf8(),
    )


def _read_sensor_event(reader: _Reader) -> SensorEventRecord:
    elapsed_ns = reader.i64()
    sensor_id = reader.u16()
    accuracy = reader.i8()
    value_count = reader.u8()
    values = tuple(reader.f32() for _ in range(value_count))
    return SensorEventRecord(elapsed_ns, sensor_id, accuracy, values)


def _read_marker(reader: _Reader) -> MarkerRecord:
    return MarkerRecord(reader.i64(), reader.utf8(), reader.utf8())


def _read_clean_end(reader: _Reader) -> CleanEndRecord:
    return CleanEndRecord(reader.i64(), reader.i64(), reader.i64())


def _main() -> None:
    parser = argparse.ArgumentParser(description="Inspect an SCELOG v1 file")
    parser.add_argument("path", type=Path)
    parser.add_argument("--pretty", action="store_true")
    args = parser.parse_args()
    session = read_session(args.path)
    print(json.dumps(session.summary(), ensure_ascii=False, indent=2 if args.pretty else None))


if __name__ == "__main__":
    _main()
