package com.sensorcontext.engine.personalization

import com.sensorcontext.engine.context.ContextSnapshot

enum class ExperienceActionType {
    SUGGEST_READING_ASSIST,
}

enum class FeedbackType {
    ACCEPTED,
    DISMISSED,
    REVERTED,
}

data class PolicyStats(
    val shown: Int = 0,
    val accepted: Int = 0,
    val dismissed: Int = 0,
    val reverted: Int = 0,
    val consecutiveDismissals: Int = 0,
    val lastShownAtMillis: Long = 0L,
) {
    val acceptanceRate: Float
        get() = if (shown == 0) 0f else accepted.toFloat() / shown.toFloat()
}

data class ExperienceDecision(
    val decisionId: String,
    val policyId: String,
    val actionType: ExperienceActionType,
    val title: String,
    val explanation: String,
    val confidence: Float,
    val createdAtMillis: Long,
    val sourceContext: ContextSnapshot,
)

data class PersonalizationResult(
    val decision: ExperienceDecision?,
    val reason: String,
)

interface PolicyStatsStore {
    fun getStats(policyId: String): PolicyStats

    fun markShown(policyId: String, shownAtMillis: Long): PolicyStats

    fun recordFeedback(
        policyId: String,
        decisionId: String,
        feedbackType: FeedbackType,
    ): PolicyStats

    fun resetAll()
}
