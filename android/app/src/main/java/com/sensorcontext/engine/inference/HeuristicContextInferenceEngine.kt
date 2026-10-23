package com.sensorcontext.engine.inference

import com.sensorcontext.engine.context.ActivityLabel
import com.sensorcontext.engine.context.CarryPosture
import com.sensorcontext.engine.context.ContextSnapshot
import com.sensorcontext.engine.context.ContextSource
import com.sensorcontext.engine.context.EnvironmentContext
import com.sensorcontext.engine.context.IndoorOutdoorLabel
import com.sensorcontext.engine.context.LightColor
import com.sensorcontext.engine.context.LightLevel
import com.sensorcontext.engine.context.ScoredLabel
import com.sensorcontext.engine.sensor.AccelerationStats
import com.sensorcontext.engine.sensor.SensorWindow
import kotlin.math.abs
import kotlin.math.max

/**
 * Engineering-only baseline used to validate the end-to-end Android pipeline.
 * It is intentionally small and will be replaced by TFLite/QNN implementations.
 */
class HeuristicContextInferenceEngine(
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) : ContextInferenceEngine {

    override fun infer(window: SensorWindow): ContextSnapshot {
        val stats = window.accelerationStats()
        val activity = inferActivity(stats)
        val lightLevel = inferLight(window.latestLight?.value)
        val carry = inferCarry(
            stats = stats,
            activity = activity.label,
            light = lightLevel.label,
            proximityNear = window.latestProximity?.isNear,
        )

        return ContextSnapshot(
            elapsedRealtimeNanos = window.windowEndElapsedNanos,
            wallTimeMillis = wallClockMillis(),
            validUntilElapsedRealtimeNanos = window.windowEndElapsedNanos + 10_000_000_000L,
            activity = activity,
            carryPosture = carry,
            environment = EnvironmentContext(
                indoorOutdoor = ScoredLabel(IndoorOutdoorLabel.UNKNOWN, 0f),
                lightLevel = lightLevel,
                lightColor = ScoredLabel(LightColor.UNKNOWN, 0f),
                lux = window.latestLight?.value,
                cctKelvin = null,
            ),
            interactionEvents = emptySet(),
            source = ContextSource.HEURISTIC,
        )
    }

    private fun inferActivity(stats: AccelerationStats?): ScoredLabel<ActivityLabel> {
        if (stats == null || stats.sampleCount < MIN_ACCEL_SAMPLES) {
            return ScoredLabel(ActivityLabel.UNKNOWN, 0f)
        }

        return when {
            stats.magnitudeStdDev < 0.22f -> ScoredLabel(ActivityLabel.STILL, 0.9f)
            stats.magnitudeStdDev < 2.2f -> ScoredLabel(ActivityLabel.WALKING, 0.65f)
            else -> ScoredLabel(ActivityLabel.RUNNING, 0.7f)
        }
    }

    private fun inferCarry(
        stats: AccelerationStats?,
        activity: ActivityLabel,
        light: LightLevel,
        proximityNear: Boolean?,
    ): ScoredLabel<CarryPosture> {
        if (stats == null || stats.sampleCount < MIN_ACCEL_SAMPLES) {
            return ScoredLabel(CarryPosture.UNKNOWN, 0f)
        }

        val normalizedZ = abs(stats.meanZ) / max(stats.meanMagnitude, 1f)
        val isFlat = normalizedZ > 0.85f
        val isStill = activity == ActivityLabel.STILL

        return when {
            isStill && isFlat && stats.meanZ >= 0f ->
                ScoredLabel(CarryPosture.DESK_FACE_UP, 0.8f)

            isStill && isFlat && stats.meanZ < 0f ->
                ScoredLabel(CarryPosture.DESK_FACE_DOWN, 0.8f)

            proximityNear == true && light == LightLevel.DARK ->
                ScoredLabel(CarryPosture.IN_POCKET, 0.72f)

            else -> ScoredLabel(CarryPosture.IN_HAND, 0.62f)
        }
    }

    private fun inferLight(lux: Float?): ScoredLabel<LightLevel> {
        if (lux == null || lux.isNaN()) return ScoredLabel(LightLevel.UNKNOWN, 0f)
        return when {
            lux < 10f -> ScoredLabel(LightLevel.DARK, 0.95f)
            lux < 100f -> ScoredLabel(LightLevel.DIM, 0.9f)
            lux < 10_000f -> ScoredLabel(LightLevel.NORMAL, 0.85f)
            else -> ScoredLabel(LightLevel.BRIGHT, 0.9f)
        }
    }

    private companion object {
        const val MIN_ACCEL_SAMPLES = 25
    }
}
