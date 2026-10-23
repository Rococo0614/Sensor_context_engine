package com.sensorcontext.engine.personalization

import com.sensorcontext.engine.context.ActivityLabel
import com.sensorcontext.engine.context.CarryPosture
import com.sensorcontext.engine.context.ContextSnapshot
import com.sensorcontext.engine.context.LightLevel
import java.util.UUID

class PersonalizationEngine(
    private val statsStore: PolicyStatsStore,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val decisionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val cooldownMillis: Long = 30 * 60 * 1_000L,
) {
    fun evaluate(
        context: ContextSnapshot,
        bypassCooldown: Boolean = false,
    ): PersonalizationResult {
        val evidenceMatches =
            context.activity.label == ActivityLabel.STILL &&
                context.activity.confidence >= MIN_ACTIVITY_CONFIDENCE &&
                context.carryPosture.label == CarryPosture.IN_HAND &&
                context.carryPosture.confidence >= MIN_CARRY_CONFIDENCE &&
                context.environment.lightLevel.label in setOf(LightLevel.DIM, LightLevel.DARK) &&
                context.environment.lightLevel.confidence >= MIN_LIGHT_CONFIDENCE

        if (!evidenceMatches) {
            return PersonalizationResult(null, "reading_context_not_matched")
        }

        val stats = statsStore.getStats(READING_ASSIST_POLICY_ID)
        if (stats.consecutiveDismissals >= DISMISSAL_SUPPRESSION_THRESHOLD) {
            return PersonalizationResult(null, "suppressed_after_repeated_dismissals")
        }

        val now = wallClockMillis()
        if (!bypassCooldown &&
            stats.lastShownAtMillis > 0L &&
            now - stats.lastShownAtMillis < cooldownMillis
        ) {
            return PersonalizationResult(null, "cooldown_active")
        }

        val confidence = minOf(
            context.activity.confidence,
            context.carryPosture.confidence,
            context.environment.lightLevel.confidence,
        )
        val decision = ExperienceDecision(
            decisionId = decisionIdFactory(),
            policyId = READING_ASSIST_POLICY_ID,
            actionType = ExperienceActionType.SUGGEST_READING_ASSIST,
            title = "暗光阅读辅助",
            explanation = "检测到静止、手持和暗光环境，建议降低视觉刺激。",
            confidence = confidence,
            createdAtMillis = now,
            sourceContext = context,
        )
        statsStore.markShown(READING_ASSIST_POLICY_ID, now)
        return PersonalizationResult(decision, "reading_assist_suggested")
    }

    companion object {
        const val READING_ASSIST_POLICY_ID = "low_light_handheld_reading"
        const val DISMISSAL_SUPPRESSION_THRESHOLD = 3

        private const val MIN_ACTIVITY_CONFIDENCE = 0.75f
        private const val MIN_CARRY_CONFIDENCE = 0.55f
        private const val MIN_LIGHT_CONFIDENCE = 0.8f
    }
}
