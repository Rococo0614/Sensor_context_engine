package com.sensorcontext.engine.personalization

import android.content.Context

class SharedPreferencesPolicyStatsStore(context: Context) : PolicyStatsStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun getStats(policyId: String): PolicyStats = synchronized(this) {
        PolicyStats(
            shown = preferences.getInt(key(policyId, "shown"), 0),
            accepted = preferences.getInt(key(policyId, "accepted"), 0),
            dismissed = preferences.getInt(key(policyId, "dismissed"), 0),
            reverted = preferences.getInt(key(policyId, "reverted"), 0),
            consecutiveDismissals = preferences.getInt(
                key(policyId, "consecutive_dismissals"),
                0,
            ),
            lastShownAtMillis = preferences.getLong(key(policyId, "last_shown_at"), 0L),
        )
    }

    override fun markShown(policyId: String, shownAtMillis: Long): PolicyStats = synchronized(this) {
        val current = getStats(policyId)
        preferences.edit()
            .putInt(key(policyId, "shown"), current.shown + 1)
            .putLong(key(policyId, "last_shown_at"), shownAtMillis)
            .apply()
        getStats(policyId)
    }

    override fun recordFeedback(
        policyId: String,
        decisionId: String,
        feedbackType: FeedbackType,
    ): PolicyStats = synchronized(this) {
        val processed = preferences.getStringSet(KEY_PROCESSED_DECISIONS, emptySet())
            ?.toMutableSet()
            ?: mutableSetOf()
        if (decisionId in processed) return getStats(policyId)

        if (processed.size >= MAX_PROCESSED_DECISIONS) processed.clear()
        processed += decisionId

        val current = getStats(policyId)
        val editor = preferences.edit()
            .putStringSet(KEY_PROCESSED_DECISIONS, processed)

        when (feedbackType) {
            FeedbackType.ACCEPTED -> editor
                .putInt(key(policyId, "accepted"), current.accepted + 1)
                .putInt(key(policyId, "consecutive_dismissals"), 0)

            FeedbackType.DISMISSED -> editor
                .putInt(key(policyId, "dismissed"), current.dismissed + 1)
                .putInt(
                    key(policyId, "consecutive_dismissals"),
                    current.consecutiveDismissals + 1,
                )

            FeedbackType.REVERTED -> editor
                .putInt(key(policyId, "reverted"), current.reverted + 1)
                .putInt(
                    key(policyId, "consecutive_dismissals"),
                    current.consecutiveDismissals + 1,
                )
        }
        editor.apply()
        getStats(policyId)
    }

    override fun resetAll() {
        synchronized(this) {
            preferences.edit().clear().apply()
        }
    }

    private fun key(policyId: String, suffix: String): String = "$policyId.$suffix"

    private companion object {
        const val PREFERENCES_NAME = "personalization_stats"
        const val KEY_PROCESSED_DECISIONS = "processed_decisions"
        const val MAX_PROCESSED_DECISIONS = 64
    }
}
