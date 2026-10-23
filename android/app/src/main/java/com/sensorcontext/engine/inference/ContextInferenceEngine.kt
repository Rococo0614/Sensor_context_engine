package com.sensorcontext.engine.inference

import com.sensorcontext.engine.context.ContextSnapshot
import com.sensorcontext.engine.sensor.SensorWindow

/**
 * Stable boundary between sensor processing and a concrete inference backend.
 * Implementations must not retain or mutate [SensorWindow].
 */
interface ContextInferenceEngine {
    fun infer(window: SensorWindow): ContextSnapshot
}
