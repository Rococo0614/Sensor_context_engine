package com.sensorcontext.engine.storage

import android.content.Context

class RuntimeStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var serviceRunning: Boolean
        get() = preferences.getBoolean(KEY_SERVICE_RUNNING, false)
        set(value) {
            preferences.edit().putBoolean(KEY_SERVICE_RUNNING, value).apply()
        }

    var latestContextSummary: String
        get() = preferences.getString(KEY_CONTEXT_SUMMARY, "尚无情境结果") ?: "尚无情境结果"
        set(value) {
            preferences.edit().putString(KEY_CONTEXT_SUMMARY, value).apply()
        }

    var latestDecisionSummary: String
        get() = preferences.getString(KEY_DECISION_SUMMARY, "尚无个性化决策")
            ?: "尚无个性化决策"
        set(value) {
            preferences.edit().putString(KEY_DECISION_SUMMARY, value).apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "runtime_state"
        const val KEY_SERVICE_RUNNING = "service_running"
        const val KEY_CONTEXT_SUMMARY = "latest_context_summary"
        const val KEY_DECISION_SUMMARY = "latest_decision_summary"
    }
}
