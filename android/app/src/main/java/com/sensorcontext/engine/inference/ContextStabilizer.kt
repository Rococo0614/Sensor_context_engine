package com.sensorcontext.engine.inference

import com.sensorcontext.engine.context.ContextSnapshot

data class StabilizationResult(
    val snapshot: ContextSnapshot,
    val stateChanged: Boolean,
    val candidatePending: Boolean,
)

class ContextStabilizer(
    private val confirmationsRequired: Int = 2,
    private val immediateConfidence: Float = 0.92f,
) {
    private var stable: ContextSnapshot? = null
    private var candidateKey: String? = null
    private var candidateCount = 0

    fun update(raw: ContextSnapshot): StabilizationResult {
        val current = stable
        if (current == null) {
            stable = raw
            return StabilizationResult(raw, stateChanged = true, candidatePending = false)
        }

        val rawKey = raw.stateKey()
        if (rawKey == current.stateKey()) {
            stable = raw
            candidateKey = null
            candidateCount = 0
            return StabilizationResult(raw, stateChanged = false, candidatePending = false)
        }

        if (candidateKey == rawKey) {
            candidateCount += 1
        } else {
            candidateKey = rawKey
            candidateCount = 1
        }

        val minimumConfidence = minOf(
            raw.activity.confidence,
            raw.carryPosture.confidence,
            raw.environment.lightLevel.confidence,
        )
        val confirmed = candidateCount >= confirmationsRequired ||
            minimumConfidence >= immediateConfidence

        return if (confirmed) {
            stable = raw
            candidateKey = null
            candidateCount = 0
            StabilizationResult(raw, stateChanged = true, candidatePending = false)
        } else {
            StabilizationResult(current, stateChanged = false, candidatePending = true)
        }
    }

    fun reset() {
        stable = null
        candidateKey = null
        candidateCount = 0
    }
}
