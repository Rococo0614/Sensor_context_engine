from __future__ import annotations

import unittest

from training.sce_data.fusion_dataset import (
    CONTINUOUS_CHANNELS,
    ENVIRONMENT_FEATURES,
    EVENT_FEATURES,
    FUSION_SCHEMA_HASH,
    MOTION_QUALITY_FEATURES,
    build_fusion_examples,
)
from training.tests.fixtures import fusion_session


class FusionDatasetTest(unittest.TestCase):
    def test_example_has_frozen_shapes_masks_and_freshness(self) -> None:
        examples = build_fusion_examples(fusion_session())

        self.assertEqual(2, len(examples))
        example = examples[0]
        example.validate()
        self.assertEqual(FUSION_SCHEMA_HASH, example.schema_hash)
        self.assertEqual(len(CONTINUOUS_CHANNELS), len(example.continuous_values))
        self.assertEqual(len(ENVIRONMENT_FEATURES), len(example.environment_values))
        self.assertEqual(len(EVENT_FEATURES), len(example.event_values))
        self.assertEqual(len(MOTION_QUALITY_FEATURES), len(example.motion_quality))
        self.assertTrue(all(len(channel) == 100 for channel in example.continuous_values))

        environment = dict(zip(ENVIRONMENT_FEATURES, example.environment_values))
        self.assertEqual(1.0, environment["lux_valid"])
        self.assertGreaterEqual(environment["lux_age_s"], 3.0)
        self.assertGreater(environment["lux_freshness"], 0.0)

    def test_missing_gyro_has_zero_mask_and_unavailable_quality(self) -> None:
        example = build_fusion_examples(fusion_session(include_gyro=False))[0]
        continuous = dict(zip(CONTINUOUS_CHANNELS, example.continuous_valid))
        quality = dict(zip(MOTION_QUALITY_FEATURES, example.motion_quality))

        self.assertEqual({0.0}, set(continuous["gyro_x"]))
        self.assertEqual(0.0, quality["gyro_available"])
        self.assertEqual(1.0, quality["accel_available"])


if __name__ == "__main__":
    unittest.main()
