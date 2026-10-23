package com.sensorcontext.engine.fusion

data class SignalQuality(
    val available: Boolean,
    val sampleCount: Int,
    val expectedSampleCount: Float,
    val sampleRatio: Float,
    val medianPeriodMillis: Float,
    val p95PeriodMillis: Float,
    val maxGapMillis: Float,
    val timestampDisorderCount: Int,
    val saturationRatio: Float,
)

data class MagneticQuality(
    val available: Boolean,
    val fieldMeanMicroTesla: Float,
    val fieldStdMicroTesla: Float,
    val interferenceScore: Float,
    val spikeRatio: Float,
)

data class FusionInput(
    val windowStartElapsedNanos: Long,
    val windowEndElapsedNanos: Long,
    val continuousValues: Array<FloatArray>,
    val continuousValid: Array<FloatArray>,
    val environmentValues: FloatArray,
    val eventValues: FloatArray,
    val motionQuality: FloatArray,
    val schemaHash: String = FusionSchema.HASH,
) {
    fun requireValid() {
        require(windowEndElapsedNanos > windowStartElapsedNanos) { "invalid window interval" }
        require(schemaHash == FusionSchema.HASH) { "fusion schema hash mismatch" }
        require(continuousValues.size == FusionSchema.continuousChannels.size) {
            "continuous channel count mismatch"
        }
        require(continuousValid.size == FusionSchema.continuousChannels.size) {
            "continuous mask channel count mismatch"
        }
        require(continuousValues.all { it.size == WINDOW_POINTS }) {
            "continuous channels must contain $WINDOW_POINTS points"
        }
        require(continuousValid.all { it.size == WINDOW_POINTS }) {
            "continuous masks must contain $WINDOW_POINTS points"
        }
        require(environmentValues.size == FusionSchema.environmentFeatures.size) {
            "environment feature count mismatch"
        }
        require(eventValues.size == FusionSchema.eventFeatures.size) {
            "event feature count mismatch"
        }
        require(motionQuality.size == FusionSchema.motionQualityFeatures.size) {
            "motion quality feature count mismatch"
        }
        require(continuousValues.all { channel -> channel.all(Float::isFinite) }) {
            "model tensor values must be finite; missing values require a zero plus explicit mask"
        }
        require(continuousValid.all { channel -> channel.all { it == 0f || it == 1f } }) {
            "continuous mask values must be binary"
        }
        require(environmentValues.all(Float::isFinite)) {
            "environment values must be finite and carry explicit valid features"
        }
        require(eventValues.all(Float::isFinite)) { "event values must be finite" }
        require(motionQuality.all(Float::isFinite)) { "quality values must be finite" }
    }

    companion object {
        const val WINDOW_POINTS = 100
    }
}

data class FusionOutput(
    val activityProbabilities: FloatArray,
    val carryProbabilities: FloatArray,
    val lightProbabilities: FloatArray,
    val gateWeights: Map<String, Float>,
    val overallConfidence: Float,
    val isRejected: Boolean,
    val modelVersion: String,
    val validUntilElapsedNanos: Long,
) {
    fun requireValid() {
        require(activityProbabilities.size == FusionSchema.activityLabels.size)
        require(carryProbabilities.size == FusionSchema.carryLabels.size)
        require(activityProbabilities.all { it in 0f..1f })
        require(carryProbabilities.all { it in 0f..1f })
        require(gateWeights.values.all { it in 0f..1f })
        require(overallConfidence in 0f..1f)
    }
}

interface FusionInferenceEngine {
    fun infer(input: FusionInput): FusionOutput
}
