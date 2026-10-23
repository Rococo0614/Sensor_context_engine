#!/usr/bin/env python3
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

MAGIC = b"SCELOG01"


def adb_prefix(serial: str | None) -> list[str]:
    return ["adb", "-s", serial] if serial else ["adb"]


def main() -> None:
    parser = argparse.ArgumentParser(description="Pull SCELOG sessions from a debug Android app")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--package", default="com.sensorcontext.engine.debug")
    parser.add_argument("--serial")
    args = parser.parse_args()

    adb = adb_prefix(args.serial)
    subprocess.run(adb + ["get-state"], check=True, capture_output=True)
    listing = subprocess.run(
        adb + ["shell", "run-as", args.package, "ls", "-1", "files/recordings"],
        check=False,
        capture_output=True,
        text=True,
    )
    if listing.returncode != 0:
        raise SystemExit(f"cannot list recordings: {listing.stderr.strip()}")

    names = [
        line.strip()
        for line in listing.stdout.splitlines()
        if line.strip().endswith(".scelog") and "/" not in line and "\\" not in line
    ]
    args.output.mkdir(parents=True, exist_ok=True)
    if not names:
        print("No .scelog sessions found.")
        return

    for name in names:
        result = subprocess.run(
            adb + ["exec-out", "run-as", args.package, "cat", f"files/recordings/{name}"],
            check=True,
            capture_output=True,
        )
        if not result.stdout.startswith(MAGIC):
            raise SystemExit(f"{name}: pulled data does not start with SCELOG magic")
        destination = args.output / name
        destination.write_bytes(result.stdout)
        print(f"Pulled {name} ({len(result.stdout)} bytes)")


if __name__ == "__main__":
    main()
