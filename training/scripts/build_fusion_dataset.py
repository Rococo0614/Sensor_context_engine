#!/usr/bin/env python3
from __future__ import annotations

import argparse
import gzip
import json
import sys
from collections import Counter
from dataclasses import asdict
from pathlib import Path

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from training.sce_data.alignment import align_session
from training.sce_data.binary_log import read_session
from training.sce_data.fusion_dataset import (
    ACTIVITY_LABELS,
    CARRY_LABELS,
    CONTINUOUS_CHANNELS,
    ENVIRONMENT_FEATURES,
    EVENT_FEATURES,
    FUSION_SCHEMA_HASH,
    MOTION_QUALITY_FEATURES,
    build_fusion_examples,
)
from training.sce_data.quality import assess_session_quality


def discover(path: Path) -> list[Path]:
    return [path] if path.is_file() else sorted(path.rglob("*.scelog"))


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build quality-filtered, schema-versioned fusion examples"
    )
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--include-failed-quality",
        action="store_true",
        help="Export failed sessions for debugging; never use this for a training run.",
    )
    args = parser.parse_args()

    paths = discover(args.input)
    if not paths:
        raise SystemExit("No .scelog files found")
    args.output.parent.mkdir(parents=True, exist_ok=True)

    session_manifest = []
    label_pairs: Counter[str] = Counter()
    exported_windows = 0
    with gzip.open(args.output, "wt", encoding="utf-8") as stream:
        for path in paths:
            session = read_session(path)
            rows = align_session(session)
            quality = assess_session_quality(session, rows)
            examples = (
                build_fusion_examples(session)
                if quality.quality_ok or args.include_failed_quality
                else []
            )
            for example in examples:
                stream.write(json.dumps(asdict(example), separators=(",", ":")) + "\n")
            exported_windows += len(examples)
            pair = f"{session.header.activity_label}×{session.header.posture_label}"
            if quality.quality_ok:
                label_pairs[pair] += 1
            session_manifest.append(
                {
                    "path": str(path),
                    "session_id": session.header.session_id,
                    "participant_id": session.header.participant_id,
                    "activity": session.header.activity_label,
                    "carry": session.header.posture_label,
                    "quality_ok": quality.quality_ok,
                    "failure_reasons": quality.failure_reasons,
                    "window_count": len(examples),
                }
            )

    manifest_path = args.output.with_suffix(args.output.suffix + ".manifest.json")
    manifest = {
        "schema_hash": FUSION_SCHEMA_HASH,
        "continuous_channels": CONTINUOUS_CHANNELS,
        "environment_features": ENVIRONMENT_FEATURES,
        "event_features": EVENT_FEATURES,
        "motion_quality_features": MOTION_QUALITY_FEATURES,
        "activity_labels": ACTIVITY_LABELS,
        "carry_labels": CARRY_LABELS,
        "session_count": len(session_manifest),
        "exported_window_count": exported_windows,
        "quality_pass_session_count": sum(item["quality_ok"] for item in session_manifest),
        "quality_pass_label_pairs": dict(sorted(label_pairs.items())),
        "sessions": session_manifest,
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(
        f"schema={FUSION_SCHEMA_HASH} sessions={len(session_manifest)} "
        f"windows={exported_windows} output={args.output}"
    )
    print(f"manifest={manifest_path}")


if __name__ == "__main__":
    main()
