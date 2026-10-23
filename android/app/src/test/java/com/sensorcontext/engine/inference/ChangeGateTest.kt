package com.sensorcontext.engine.inference

import com.sensorcontext.engine.sensor.SensorWindow
import com.sensorcontext.engine.sensor.ScalarSample
import com.sensorcontext.engine.sensor.Vector3Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeGateTest {
    @Test
    fun `initial context wakes then stable context sleeps until heartbeat`() {
        val gate = ChangeGate(
            minimumIntervalNanos = 1_000_000_000L,
            heartbeatNanos = 60_000_000_000L,
        )
        val window = stationaryWindow()

        val initial = gate.evaluate(window, nowElapsedNanos = 10_000_000_000L)
        val stable = gate.evaluate(window, nowElapsedNanos = 13_000_000_000L)
        val heartbeat = gate.evaluate(window, nowElapsedNanos = 71_000_000_000L)

        assertTrue(initial.shouldInfer)
        assertEquals("initial_context", initial.reason)
        assertFalse(stable.shouldInfer)
        assertTrue(heartbeat.shouldInfer)
        assertEquals("heartbeat", heartbeat.reason)
    }

    @Test
    fun `small lux fluctuations stay gated but light bucket transition wakes`() {
        val gate = ChangeGate(minimumIntervalNanos = 1_000_000_000L)

        gate.evaluate(stationaryWindow(lux = 500f), nowElapsedNanos = 10_000_000_000L)
        val sameBucket = gate.evaluate(
            stationaryWindow(lux = 650f),
            nowElapsedNanos = 13_000_000_000L,
        )
        val dark = gate.evaluate(
            stationaryWindow(lux = 5f),
            nowElapsedNanos = 16_000_000_000L,
        )

        assertFalse(sameBucket.shouldInfer)
        assertTrue(dark.shouldInfer)
        assertEquals("light_changed", dark.reason)
    }

    private fun stationaryWindow(lux: Float? = null): SensorWindow {
        val samples = (0 until 100).map { index ->
            Vector3Sample(index * 40_000_000L, 0f, 6f, 8f)
        }
        return SensorWindow(
            windowStartElapsedNanos = 0L,
            windowEndElapsedNanos = samples.last().elapsedRealtimeNanos,
            acceleration = samples,
            latestLight = lux?.let { ScalarSample(samples.last().elapsedRealtimeNanos, it) },
            latestProximity = null,
        )
    }
}
