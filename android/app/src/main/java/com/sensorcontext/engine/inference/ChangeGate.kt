package com.sensorcontext.engine.inference

import com.sensorcontext.engine.context.LightLevel
import com.sensorcontext.engine.sensor.SensorWindow
import kotlin.math.abs

data class GateDecision(
    val shouldInfer: Boolean,
    val reason: String,
)

private data class GateFeatures(
    val motionStdDev: Float,
    val lightBucket: LightLevel,
    val proximityNear: Boolean?,
)

class ChangeGate(
    private val minimumIntervalNanos: Long = 2_000_000_000L,
    private val heartbeatNanos: Long = 60_000_000_000L,
    private val motionChangeThreshold: Float = 0.18f,
) {
    private var lastEvaluationNanos = Long.MIN_VALUE
    private var lastInferenceNanos = Long.MIN_VALUE
    private var lastFeatures: GateFeatures? = null

    fun evaluate(
        window: SensorWindow,
        nowElapsedNanos: Long = window.windowEndElapsedNanos,
        forcedReason: String? = null,
    ): GateDecision {
        if (lastEvaluationNanos != Long.MIN_VALUE &&
            nowElapsedNanos - lastEvaluationNanos < minimumIntervalNanos &&
            forcedReason == null
        ) {
            return GateDecision(false, "minimum_interval")
        }
        lastEvaluationNanos = nowElapsedNanos

        val stats = window.accelerationStats()
            ?: return GateDecision(false, "no_acceleration")
        val features = GateFeatures(
            motionStdDev = stats.magnitudeStdDev,
            lightBucket = lightBucket(window.latestLight?.value),
            proximityNear = window.latestProximity?.isNear,
        )

        val previous = lastFeatures
        val reason = when {
            forcedReason != null -> forcedReason
            previous == null -> "initial_context"
            lastInferenceNanos != Long.MIN_VALUE &&
                nowElapsedNanos - lastInferenceNanos >= heartbeatNanos -> "heartbeat"

            abs(features.motionStdDev - previous.motionStdDev) >= motionChangeThreshold ->
                "motion_changed"

            features.lightBucket != previous.lightBucket -> "light_changed"
            features.proximityNear != previous.proximityNear -> "proximity_changed"
            else -> null
        }

        return if (reason != null) {
            lastFeatures = features
            lastInferenceNanos = nowElapsedNanos
            GateDecision(true, reason)
        } else {
            GateDecision(false, "stable")
        }
    }

    fun reset() {
        lastEvaluationNanos = Long.MIN_VALUE
        lastInferenceNanos = Long.MIN_VALUE
        lastFeatures = null
    }

    private fun lightBucket(lux: Float?): LightLevel = when {
        lux == null || lux.isNaN() -> LightLevel.UNKNOWN
        lux < 10f -> LightLevel.DARK
        lux < 100f -> LightLevel.DIM
        lux < 10_000f -> LightLevel.NORMAL
        else -> LightLevel.BRIGHT
    }
}
