package com.sensorcontext.engine.storage

import android.content.Context
import com.sensorcontext.engine.context.ContextSnapshot
import com.sensorcontext.engine.personalization.ExperienceDecision
import com.sensorcontext.engine.personalization.FeedbackType
import com.sensorcontext.engine.personalization.PolicyStats
import org.json.JSONObject
import java.io.File

class TraceLogger(context: Context) {
    private val traceDirectory = File(context.applicationContext.filesDir, "traces")
    private val traceFile = File(traceDirectory, "decision_trace.jsonl")

    fun logServiceEvent(event: String, details: String) {
        append(
            JSONObject()
                .put("type", "service")
                .put("event", event)
                .put("details", details),
        )
    }

    fun logGate(shouldInfer: Boolean, reason: String) {
        append(
            JSONObject()
                .put("type", "gate")
                .put("should_infer", shouldInfer)
                .put("reason", reason),
        )
    }

    fun logContext(context: ContextSnapshot, stateChanged: Boolean, candidatePending: Boolean) {
        append(
            JSONObject()
                .put("type", "context")
                .put("source", context.source.name)
                .put("elapsed_ns", context.elapsedRealtimeNanos)
                .put("state_changed", stateChanged)
                .put("candidate_pending", candidatePending)
                .put("activity", scored(context.activity.label.name, context.activity.confidence))
                .put(
                    "carry_posture",
                    scored(context.carryPosture.label.name, context.carryPosture.confidence),
                )
                .put(
                    "light_level",
                    scored(
                        context.environment.lightLevel.label.name,
                        context.environment.lightLevel.confidence,
                    ),
                )
                .put("lux", context.environment.lux ?: JSONObject.NULL),
        )
    }

    fun logPersonalization(reason: String, decision: ExperienceDecision?) {
        val payload = JSONObject()
            .put("type", "personalization")
            .put("reason", reason)
        if (decision != null) {
            payload
                .put("decision_id", decision.decisionId)
                .put("policy_id", decision.policyId)
                .put("action", decision.actionType.name)
                .put("confidence", decision.confidence.toDouble())
                .put("context_source", decision.sourceContext.source.name)
        }
        append(payload)
    }

    fun logFeedback(
        decisionId: String,
        policyId: String,
        feedback: FeedbackType,
        stats: PolicyStats,
    ) {
        append(
            JSONObject()
                .put("type", "feedback")
                .put("decision_id", decisionId)
                .put("policy_id", policyId)
                .put("feedback", feedback.name)
                .put("shown", stats.shown)
                .put("accepted", stats.accepted)
                .put("dismissed", stats.dismissed)
                .put("reverted", stats.reverted)
                .put("consecutive_dismissals", stats.consecutiveDismissals),
        )
    }

    fun absolutePath(): String = traceFile.absolutePath

    private fun scored(label: String, confidence: Float): JSONObject = JSONObject()
        .put("label", label)
        .put("confidence", confidence.toDouble())

    private fun append(payload: JSONObject) {
        payload.put("wall_time_ms", System.currentTimeMillis())
        synchronized(FILE_LOCK) {
            traceDirectory.mkdirs()
            rotateIfNeeded()
            traceFile.appendText(payload.toString() + "\n", Charsets.UTF_8)
        }
    }

    private fun rotateIfNeeded() {
        if (!traceFile.exists() || traceFile.length() < MAX_TRACE_BYTES) return
        val rotated = File(traceDirectory, "decision_trace.1.jsonl")
        if (rotated.exists()) rotated.delete()
        traceFile.renameTo(rotated)
    }

    private companion object {
        val FILE_LOCK = Any()
        const val MAX_TRACE_BYTES = 5L * 1024L * 1024L
    }
}
