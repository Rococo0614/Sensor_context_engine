from __future__ import annotations

import unittest

from training.sce_data.alignment import align_session, select_channels
from training.sce_data.quality import (
    assess_session_quality,
    events_for_sensor,
    freshness_score,
    magnetic_quality,
    signal_quality,
)
from training.tests.fixtures import fusion_session


class QualityTest(unittest.TestCase):
    def test_expected_rate_and_magnetic_quality_are_finite(self) -> None:
        session = fusion_session()
        selected = select_channels(session.header.sensors)
        accel = signal_quality(
            events_for_sensor(session, selected["accel"]),
            3_000_000_000,
            7_000_000_000,
            expected_rate_hz=50.0,
            descriptor=session.header.sensors_by_id[selected["accel"]],
        )
        mag = magnetic_quality(events_for_sensor(session, selected["mag"]))

        self.assertTrue(accel.available)
        self.assertAlmostEqual(1.0, accel.sample_ratio, places=2)
        self.assertAlmostEqual(20.0, accel.median_period_ms, places=3)
        self.assertAlmostEqual(20.0, accel.max_gap_ms, places=3)
        self.assertTrue(mag.available)
        self.assertLess(mag.field_deviation_score, 0.2)

    def test_freshness_decays_and_missing_is_zero(self) -> None:
        self.assertEqual(1.0, freshness_score(1_000_000_000))
        self.assertGreater(freshness_score(10_000_000_000), 0.0)
        self.assertEqual(0.0, freshness_score(31_000_000_000))
        self.assertEqual(0.0, freshness_score(None))

    def test_short_session_fails_protocol_duration_only(self) -> None:
        session = fusion_session(duration_seconds=12)
        report = assess_session_quality(session, align_session(session))

        self.assertFalse(report.quality_ok)
        self.assertIn("duration_below_30s", report.failure_reasons)
        self.assertNotIn("accelerometer_gap_above_1s", report.failure_reasons)


if __name__ == "__main__":
    unittest.main()
