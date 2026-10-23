package com.sensorcontext.engine.inference

import com.sensorcontext.engine.context.ActivityLabel
import com.sensorcontext.engine.context.CarryPosture
import com.sensorcontext.engine.context.LightLevel
import com.sensorcontext.engine.sensor.ProximitySample
import com.sensorcontext.engine.sensor.ScalarSample
import com.sensorcontext.engine.sensor.SensorWindow
import com.sensorcontext.engine.sensor.Vector3Sample
import org.junit.Assert.assertEquals
import org.junit.Test

class HeuristicContextInferenceEngineTest {
    private val engine = HeuristicContextInferenceEngine { 123L }

    @Test
    fun `stationary angled phone in darkness is handheld reading evidence`() {
        val window = window(x = 0f, y = 6f, z = 8f, lux = 3f, proximity = 5f)

        val context = engine.infer(window)

        assertEquals(ActivityLabel.STILL, context.activity.label)
        assertEquals(CarryPosture.IN_HAND, context.carryPosture.label)
        assertEquals(LightLevel.DARK, context.environment.lightLevel.label)
    }

    @Test
    fun `stationary flat phone is classified as desk face up`() {
        val window = window(x = 0f, y = 0f, z = 9.81f, lux = 500f, proximity = 5f)

        val context = engine.infer(window)

        assertEquals(CarryPosture.DESK_FACE_UP, context.carryPosture.label)
    }

    private fun window(
        x: Float,
        y: Float,
        z: Float,
        lux: Float,
        proximity: Float,
    ): SensorWindow {
        val samples = (0 until 100).map { index ->
            Vector3Sample(
                elapsedRealtimeNanos = index * 40_000_000L,
                x = x,
                y = y,
                z = z,
            )
        }
        return SensorWindow(
            windowStartElapsedNanos = 0L,
            windowEndElapsedNanos = samples.last().elapsedRealtimeNanos,
            acceleration = samples,
            latestLight = ScalarSample(samples.last().elapsedRealtimeNanos, lux),
            latestProximity = ProximitySample(
                elapsedRealtimeNanos = samples.last().elapsedRealtimeNanos,
                distanceCentimeters = proximity,
                maxRangeCentimeters = 5f,
            ),
        )
    }
}
