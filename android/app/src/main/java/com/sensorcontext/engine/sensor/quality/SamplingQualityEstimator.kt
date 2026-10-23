package com.sensorcontext.engine.sensor.quality

import com.sensorcontext.engine.fusion.SignalQuality
import kotlin.math.ceil
import kotlin.math.floor

object SamplingQualityEstimator {
    fun estimate(
        timestampsNanos: LongArray,
        expectedRateHz: Float,
        windowStartNanos: Long,
        windowEndNanos: Long,
        values: Array<FloatArray>? = null,
        maxRange: Float? = null,
    ): SignalQuality {
        require(expectedRateHz > 0f)
        require(windowEndNanos > windowStartNanos)
        val selectedIndices = timestampsNanos.indices.filter { index ->
            timestampsNanos[index] in windowStartNanos until windowEndNanos
        }
        val durationSeconds = (windowEndNanos - windowStartNanos) / 1_000_000_000f
        val expectedCount = durationSeconds * expectedRateHz
        if (selectedIndices.isEmpty()) {
            return SignalQuality(
                available = false,
                sampleCount = 0,
                expectedSampleCount = expectedCount,
                sampleRatio = 0f,
                medianPeriodMillis = Float.POSITIVE_INFINITY,
                p95PeriodMillis = Float.POSITIVE_INFINITY,
                maxGapMillis = Float.POSITIVE_INFINITY,
                timestampDisorderCount = 0,
                saturationRatio = 0f,
            )
        }

        val originalTimestamps = selectedIndices.map(timestampsNanos::get)
        val disorderCount = originalTimestamps.zipWithNext().count { (left, right) -> right < left }
        val ordered = originalTimestamps.sorted()
        val periodsMillis = ordered.zipWithNext()
            .mapNotNull { (left, right) ->
                (right - left).takeIf { it > 0L }?.div(1_000_000f)
            }
        val saturationRatio = saturationRatio(selectedIndices, values, maxRange)
        return SignalQuality(
            available = true,
            sampleCount = ordered.size,
            expectedSampleCount = expectedCount,
            sampleRatio = if (expectedCount > 0f) ordered.size / expectedCount else 0f,
            medianPeriodMillis = percentile(periodsMillis, 0.5f),
            p95PeriodMillis = percentile(periodsMillis, 0.95f),
            maxGapMillis = periodsMillis.maxOrNull() ?: Float.POSITIVE_INFINITY,
            timestampDisorderCount = disorderCount,
            saturationRatio = saturationRatio,
        )
    }

    private fun saturationRatio(
        selectedIndices: List<Int>,
        values: Array<FloatArray>?,
        maxRange: Float?,
    ): Float {
        if (values == null || maxRange == null || maxRange <= 0f) return 0f
        val threshold = maxRange * 0.98f
        var total = 0
        var saturated = 0
        selectedIndices.forEach { index ->
            values.getOrNull(index)?.forEach { value ->
                total += 1
                if (kotlin.math.abs(value) >= threshold) saturated += 1
            }
        }
        return if (total == 0) 0f else saturated.toFloat() / total
    }

    private fun percentile(values: List<Float>, quantile: Float): Float {
        if (values.isEmpty()) return Float.POSITIVE_INFINITY
        val ordered = values.sorted()
        val position = (ordered.size - 1) * quantile
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        if (lower == upper) return ordered[lower]
        val fraction = position - lower
        return ordered[lower] * (1f - fraction) + ordered[upper] * fraction
    }
}
