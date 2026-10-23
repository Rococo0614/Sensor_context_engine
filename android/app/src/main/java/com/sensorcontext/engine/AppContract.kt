package com.sensorcontext.engine

object AppContract {
    const val ACTION_CONTEXT_UPDATED = "com.sensorcontext.engine.action.CONTEXT_UPDATED"
    const val ACTION_DECISION_UPDATED = "com.sensorcontext.engine.action.DECISION_UPDATED"
    const val ACTION_FEEDBACK_RECORDED = "com.sensorcontext.engine.action.FEEDBACK_RECORDED"
    const val ACTION_SIMULATE_READING_CONTEXT =
        "com.sensorcontext.engine.action.SIMULATE_READING_CONTEXT"

    const val ACTION_FEEDBACK_ACCEPT = "com.sensorcontext.engine.action.FEEDBACK_ACCEPT"
    const val ACTION_FEEDBACK_DISMISS = "com.sensorcontext.engine.action.FEEDBACK_DISMISS"

    const val EXTRA_SUMMARY = "summary"
    const val EXTRA_DECISION_ID = "decision_id"
    const val EXTRA_POLICY_ID = "policy_id"
    const val EXTRA_POLICY_TITLE = "policy_title"
    const val EXTRA_FEEDBACK = "feedback"
}
