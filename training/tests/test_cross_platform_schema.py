from __future__ import annotations

import unittest
from pathlib import Path

from training.sce_data.fusion_dataset import (
    ACTIVITY_LABELS,
    CARRY_LABELS,
    CONTINUOUS_CHANNELS,
    ENVIRONMENT_FEATURES,
    FUSION_SCHEMA_HASH,
)


class CrossPlatformSchemaTest(unittest.TestCase):
    def test_kotlin_contract_declares_python_schema_hash_and_names(self) -> None:
        root = Path(__file__).resolve().parents[2]
        kotlin = (
            root
            / "android/app/src/main/java/com/sensorcontext/engine/fusion/FusionSchema.kt"
        ).read_text(encoding="utf-8")

        self.assertIn(f'const val HASH = "{FUSION_SCHEMA_HASH}"', kotlin)
        for name in (
            *ACTIVITY_LABELS,
            *CARRY_LABELS,
            *CONTINUOUS_CHANNELS,
            *ENVIRONMENT_FEATURES,
        ):
            self.assertIn(f'"{name}"', kotlin)
        for identity in (
            "step_detector",
            "significant_motion",
            "tilt",
            "vendor_raiseup",
            "vendor_putdown",
            "vendor_car",
            "vendor_activity",
        ):
            self.assertIn(f'"{identity}"', kotlin)
        for channel in ("accel", "gyro", "mag", "gravity"):
            self.assertIn(f'"{channel}"', kotlin)


if __name__ == "__main__":
    unittest.main()
