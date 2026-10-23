#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
import sys
from dataclasses import asdict
from pathlib import Path

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from training.sce_data.alignment import align_session
from training.sce_data.binary_log import read_session
from training.sce_data.fusion_dataset import FUSION_SCHEMA_HASH
from training.sce_data.quality import freshness_score


def discover(path: Path) -> list[Path]:
    if path.is_file():
        return [path]
    return sorted(path.rglob("*.scelog"))


def convert(path: Path, output: Path) -> None:
    session = read_session(path)
    rows = align_session(session)
    session_dir = output / session.header.session_id
    session_dir.mkdir(parents=True, exist_ok=True)

    metadata = session.summary()
    metadata["header"] = asdict(session.header)
    metadata["fusion_schema_hash"] = FUSION_SCHEMA_HASH
    (session_dir / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    value_names = sorted({name for row in rows for name in row.values})
    valid_names = sorted({name for row in rows for name in row.valid})
    age_names = sorted({name for row in rows for name in row.ages_ns})
    fieldnames = (
        ["elapsed_ns"]
        + value_names
        + [f"{name}_valid" for name in valid_names]
        + [f"{name}_age_ms" for name in age_names]
        + [f"{name}_freshness" for name in age_names]
        + ["activity", "posture", "environment", "light", "session_id", "participant_id"]
    )
    with (session_dir / "aligned_25hz.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            payload = {"elapsed_ns": row.elapsed_ns, **row.values}
            payload.update({f"{name}_valid": int(value) for name, value in row.valid.items()})
            payload.update(
                {
                    f"{name}_age_ms": (
                        age_ns / 1_000_000.0 if age_ns is not None else ""
                    )
                    for name, age_ns in row.ages_ns.items()
                }
            )
            payload.update(
                {
                    f"{name}_freshness": (
                        1.0
                        if name == "step_counter" and age_ns is not None
                        else freshness_score(age_ns)
                    )
                    for name, age_ns in row.ages_ns.items()
                }
            )
            payload.update(
                {
                    "activity": session.header.activity_label,
                    "posture": session.header.posture_label,
                    "environment": session.header.environment_label,
                    "light": session.header.light_label,
                    "session_id": session.header.session_id,
                    "participant_id": session.header.participant_id,
                }
            )
            writer.writerow(payload)

    with (session_dir / "markers.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=["elapsed_ns", "marker", "note"])
        writer.writeheader()
        for marker in session.markers:
            writer.writerow(asdict(marker))

    print(
        f"Converted {path.name}: {len(session.sensor_events)} events -> "
        f"{len(rows)} aligned rows ({'truncated' if session.truncated else 'clean'})"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Convert SCELOG sessions to aligned CSV")
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    files = discover(args.input)
    if not files:
        raise SystemExit("No .scelog files found")
    for path in files:
        convert(path, args.output)


if __name__ == "__main__":
    main()
