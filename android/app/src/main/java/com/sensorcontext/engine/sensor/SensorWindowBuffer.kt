package com.sensorcontext.engine.sensor

import java.util.ArrayDeque

class SensorWindowBuffer(
    private val retentionNanos: Long = 10_000_000_000L,
) {
    private val acceleration = ArrayDeque<Vector3Sample>()
    private var latestLight: ScalarSample? = null
    private var latestProximity: ProximitySample? = null

    fun addAcceleration(sample: Vector3Sample) {
        acceleration.addLast(sample)
        trim(sample.elapsedRealtimeNanos)
    }

    fun updateLight(sample: ScalarSample) {
        latestLight = sample
    }

    fun updateProximity(sample: ProximitySample) {
        latestProximity = sample
    }

    fun snapshot(windowDurationNanos: Long = 4_000_000_000L): SensorWindow? {
        val endNanos = acceleration.peekLast()?.elapsedRealtimeNanos ?: return null
        val startNanos = endNanos - windowDurationNanos
        val samples = acceleration.filter { it.elapsedRealtimeNanos >= startNanos }
        if (samples.isEmpty()) return null

        return SensorWindow(
            windowStartElapsedNanos = startNanos,
            windowEndElapsedNanos = endNanos,
            acceleration = samples,
            latestLight = latestLight,
            latestProximity = latestProximity,
        )
    }

    fun clear() {
        acceleration.clear()
        latestLight = null
        latestProximity = null
    }

    private fun trim(nowNanos: Long) {
        val cutoff = nowNanos - retentionNanos
        while (true) {
            val first = acceleration.peekFirst() ?: break
            if (first.elapsedRealtimeNanos >= cutoff) break
            acceleration.removeFirst()
        }
    }
}
