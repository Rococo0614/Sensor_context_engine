package com.sensorcontext.engine.fusion

import com.sensorcontext.engine.sensor.quality.FreshnessTracker
import com.sensorcontext.engine.sensor.quality.MagneticInterferenceDetector
import com.sensorcontext.engine.sensor.quality.SamplingQualityEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionContractTest {
    @Test
    fun `schema matches Python F0 contract`() {
        assertEquals("52a7299a08ae8313", FusionSchema.HASH)
        assertEquals(12, FusionSchema.continuousChannels.size)
        assertEquals(13, FusionSchema.environmentFeatures.size)
        assertEquals(24, FusionSchema.eventFeatures.size)
        assertEquals(24, FusionSchema.motionQualityFeatures.size)
        assertEquals(4, FusionSchema.activityLabels.size)
        assertEquals(5, FusionSchema.carryLabels.size)
    }

    @Test
    fun `fusion input requires fixed finite tensors and explicit masks`() {
        val input = validInput()
        input.requireValid()

        val invalid = input.copy(
            continuousValues = input.continuousValues.copyOf().also {
                it[0] = it[0].copyOf().also { channel -> channel[0] = Float.NaN }
            },
        )
        assertThrows(IllegalArgumentException::class.java, invalid::requireValid)
    }

    @Test
    fun `sampling quality recognizes fifty hertz window`() {
        val timestamps = LongArray(200) { index -> index * 20_000_000L }
        val quality = SamplingQualityEstimator.estimate(
            timestampsNanos = timestamps,
            expectedRateHz = 50f,
            windowStartNanos = 0L,
            windowEndNanos = 4_000_000_000L,
        )

        assertTrue(quality.available)
        assertEquals(1f, quality.sampleRatio, 0.001f)
        assertEquals(20f, quality.medianPeriodMillis, 0.001f)
        assertEquals(20f, quality.maxGapMillis, 0.001f)
    }

    @Test
    fun `freshness and magnetic interference move in expected direction`() {
        assertEquals(1f, FreshnessTracker.score(1_000_000_000L), 0f)
        assertTrue(FreshnessTracker.score(10_000_000_000L) in 0f..1f)
        assertEquals(0f, FreshnessTracker.score(31_000_000_000L), 0f)

        val normal = MagneticInterferenceDetector.estimate(
            Array(20) { floatArrayOf(30f, 5f, 35f) },
        )
        val interfered = MagneticInterferenceDetector.estimate(
            Array(20) { index ->
                if (index % 2 == 0) floatArrayOf(150f, 0f, 0f)
                else floatArrayOf(20f, 0f, 0f)
            },
        )
        assertFalse(normal.interferenceScore > 0.2f)
        assertTrue(interfered.interferenceScore > normal.interferenceScore)
    }

    private fun validInput(): FusionInput = FusionInput(
        windowStartElapsedNanos = 1L,
        windowEndElapsedNanos = 4_000_000_001L,
        continuousValues = Array(FusionSchema.continuousChannels.size) {
            FloatArray(FusionInput.WINDOW_POINTS)
        },
        continuousValid = Array(FusionSchema.continuousChannels.size) {
            FloatArray(FusionInput.WINDOW_POINTS) { 1f }
        },
        environmentValues = FloatArray(FusionSchema.environmentFeatures.size),
        eventValues = FloatArray(FusionSchema.eventFeatures.size),
        motionQuality = FloatArray(FusionSchema.motionQualityFeatures.size),
    )
}
