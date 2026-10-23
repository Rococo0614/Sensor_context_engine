package com.sensorcontext.engine.sensor.quality

import com.sensorcontext.engine.fusion.MagneticQuality
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object MagneticInterferenceDetector {
    fun estimate(samples: Array<FloatArray>): MagneticQuality {
        val magnitudes = samples.mapNotNull { values ->
            if (values.size < 3) null else sqrt(
                values[0] * values[0] + values[1] * values[1] + values[2] * values[2],
            )
        }
        if (magnitudes.isEmpty()) {
            return MagneticQuality(false, 0f, 0f, 1f, 0f)
        }
        val mean = magnitudes.average().toFloat()
        val variance = magnitudes.sumOf { value ->
            val delta = value - mean
            (delta * delta).toDouble()
        }.toFloat() / magnitudes.size
        val std = sqrt(variance)
        val outsideRatio = magnitudes.count { it < 20f || it > 80f }.toFloat() / magnitudes.size
        val spikes = magnitudes.zipWithNext().map { (left, right) -> abs(right - left) }
        val spikeRatio = if (spikes.isEmpty()) 0f else {
            spikes.count { it > 10f }.toFloat() / spikes.size
        }
        return MagneticQuality(
            available = true,
            fieldMeanMicroTesla = mean,
            fieldStdMicroTesla = std,
            interferenceScore = minOf(1f, max(outsideRatio, max(spikeRatio, std / 30f))),
            spikeRatio = spikeRatio,
        )
    }
}
