package com.sensorcontext.engine.personalization

import com.sensorcontext.engine.context.ActivityLabel
import com.sensorcontext.engine.context.CarryPosture
import com.sensorcontext.engine.context.ContextSnapshot
import com.sensorcontext.engine.context.ContextSource
import com.sensorcontext.engine.context.EnvironmentContext
import com.sensorcontext.engine.context.IndoorOutdoorLabel
import com.sensorcontext.engine.context.LightColor
import com.sensorcontext.engine.context.LightLevel
import com.sensorcontext.engine.context.ScoredLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalizationEngineTest {
    @Test
    fun `matching context creates reading assistance decision`() {
        val store = InMemoryPolicyStatsStore()
        val engine = engine(store)

        val result = engine.evaluate(readingContext())

        assertNotNull(result.decision)
        assertEquals("reading_assist_suggested", result.reason)
        assertEquals(1, store.getStats(POLICY_ID).shown)
    }

    @Test
    fun `cooldown prevents repeated recommendation`() {
        val store = InMemoryPolicyStatsStore()
        val engine = engine(store)

        assertNotNull(engine.evaluate(readingContext()).decision)
        val second = engine.evaluate(readingContext())

        assertNull(second.decision)
        assertEquals("cooldown_active", second.reason)
    }

    @Test
    fun `three consecutive dismissals suppress policy`() {
        val store = InMemoryPolicyStatsStore()
        val engine = engine(store)

        repeat(3) {
            val decision = engine.evaluate(readingContext(), bypassCooldown = true).decision!!
            store.recordFeedback(POLICY_ID, decision.decisionId, FeedbackType.DISMISSED)
        }

        val suppressed = engine.evaluate(readingContext(), bypassCooldown = true)
        assertNull(suppressed.decision)
        assertEquals("suppressed_after_repeated_dismissals", suppressed.reason)
    }

    private fun engine(store: PolicyStatsStore): PersonalizationEngine {
        var id = 0
        return PersonalizationEngine(
            statsStore = store,
            wallClockMillis = { 1_000_000L },
            decisionIdFactory = { "decision-${++id}" },
        )
    }

    private fun readingContext(): ContextSnapshot = ContextSnapshot(
        elapsedRealtimeNanos = 10L,
        wallTimeMillis = 1_000_000L,
        validUntilElapsedRealtimeNanos = 20L,
        activity = ScoredLabel(ActivityLabel.STILL, 0.95f),
        carryPosture = ScoredLabel(CarryPosture.IN_HAND, 0.9f),
        environment = EnvironmentContext(
            indoorOutdoor = ScoredLabel(IndoorOutdoorLabel.INDOOR, 0.9f),
            lightLevel = ScoredLabel(LightLevel.DARK, 0.95f),
            lightColor = ScoredLabel(LightColor.UNKNOWN, 0f),
            lux = 3f,
            cctKelvin = null,
        ),
        interactionEvents = emptySet(),
        source = ContextSource.SIMULATED,
    )

    private class InMemoryPolicyStatsStore : PolicyStatsStore {
        private val stats = mutableMapOf<String, PolicyStats>()
        private val processed = mutableSetOf<String>()

        override fun getStats(policyId: String): PolicyStats = stats[policyId] ?: PolicyStats()

        override fun markShown(policyId: String, shownAtMillis: Long): PolicyStats {
            val current = getStats(policyId)
            return current.copy(
                shown = current.shown + 1,
                lastShownAtMillis = shownAtMillis,
            ).also { stats[policyId] = it }
        }

        override fun recordFeedback(
            policyId: String,
            decisionId: String,
            feedbackType: FeedbackType,
        ): PolicyStats {
            if (!processed.add(decisionId)) return getStats(policyId)
            val current = getStats(policyId)
            val updated = when (feedbackType) {
                FeedbackType.ACCEPTED -> current.copy(
                    accepted = current.accepted + 1,
                    consecutiveDismissals = 0,
                )

                FeedbackType.DISMISSED -> current.copy(
                    dismissed = current.dismissed + 1,
                    consecutiveDismissals = current.consecutiveDismissals + 1,
                )

                FeedbackType.REVERTED -> current.copy(
                    reverted = current.reverted + 1,
                    consecutiveDismissals = current.consecutiveDismissals + 1,
                )
            }
            stats[policyId] = updated
            return updated
        }

        override fun resetAll() {
            stats.clear()
            processed.clear()
        }
    }

    private companion object {
        const val POLICY_ID = PersonalizationEngine.READING_ASSIST_POLICY_ID
    }
}
