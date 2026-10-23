package com.sensorcontext.engine.sensor.quality

object FreshnessTracker {
    fun score(
        ageNanos: Long?,
        freshNanos: Long = 5_000_000_000L,
        staleNanos: Long = 30_000_000_000L,
    ): Float {
        if (ageNanos == null || ageNanos < 0L) return 0f
        if (ageNanos <= freshNanos) return 1f
        if (ageNanos >= staleNanos) return 0f
        return 1f - (ageNanos - freshNanos).toFloat() / (staleNanos - freshNanos)
    }
}
