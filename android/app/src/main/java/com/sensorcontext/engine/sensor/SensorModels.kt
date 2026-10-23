package com.sensorcontext.engine.sensor

import kotlin.math.sqrt

data class Vector3Sample(
    val elapsedRealtimeNanos: Long,
    val x: Float,
    val y: Float,
    val z: Float,
) {
    val magnitude: Float
        get() = sqrt(x * x + y * y + z * z)
}

data class ScalarSample(
    val elapsedRealtimeNanos: Long,
    val value: Float,
)

data class ProximitySample(
    val elapsedRealtimeNanos: Long,
    val distanceCentimeters: Float,
    val maxRangeCentimeters: Float,
) {
    val isNear: Boolean
        get() {
            val threshold = minOf(3f, maxRangeCentimeters * 0.2f)
            return distanceCentimeters < threshold
        }
}

data class SensorWindow(
    val windowStartElapsedNanos: Long,
    val windowEndElapsedNanos: Long,
    val acceleration: List<Vector3Sample>,
    val latestLight: ScalarSample?,
    val latestProximity: ProximitySample?,
) {
    fun accelerationStats(): AccelerationStats? {
        if (acceleration.isEmpty()) return null

        val count = acceleration.size.toFloat()
        val meanX = acceleration.sumOf { it.x.toDouble() }.toFloat() / count
        val meanY = acceleration.sumOf { it.y.toDouble() }.toFloat() / count
        val meanZ = acceleration.sumOf { it.z.toDouble() }.toFloat() / count
        val meanMagnitude = acceleration.sumOf { it.magnitude.toDouble() }.toFloat() / count
        val variance = acceleration.sumOf {
            val delta = it.magnitude - meanMagnitude
            (delta * delta).toDouble()
        }.toFloat() / count

        return AccelerationStats(
            meanX = meanX,
            meanY = meanY,
            meanZ = meanZ,
            meanMagnitude = meanMagnitude,
            magnitudeStdDev = sqrt(variance),
            sampleCount = acceleration.size,
        )
    }
}

data class AccelerationStats(
    val meanX: Float,
    val meanY: Float,
    val meanZ: Float,
    val meanMagnitude: Float,
    val magnitudeStdDev: Float,
    val sampleCount: Int,
)
