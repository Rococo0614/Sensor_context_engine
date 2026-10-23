package com.sensorcontext.engine.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.sensorcontext.engine.AppContract
import com.sensorcontext.engine.personalization.FeedbackType
import com.sensorcontext.engine.personalization.SharedPreferencesPolicyStatsStore
import com.sensorcontext.engine.storage.TraceLogger

class FeedbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val feedback = when (intent.action) {
            AppContract.ACTION_FEEDBACK_ACCEPT -> FeedbackType.ACCEPTED
            AppContract.ACTION_FEEDBACK_DISMISS -> FeedbackType.DISMISSED
            else -> return
        }
        val decisionId = intent.getStringExtra(AppContract.EXTRA_DECISION_ID) ?: return
        val policyId = intent.getStringExtra(AppContract.EXTRA_POLICY_ID) ?: return

        val store = SharedPreferencesPolicyStatsStore(context)
        val stats = store.recordFeedback(policyId, decisionId, feedback)
        TraceLogger(context).logFeedback(decisionId, policyId, feedback, stats)
        NotificationController(context).cancelRecommendation()

        val message = when (feedback) {
            FeedbackType.ACCEPTED -> "已记录：你接受了这条建议"
            FeedbackType.DISMISSED -> "已记录：减少类似建议"
            FeedbackType.REVERTED -> "已记录撤销"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

        context.sendBroadcast(
            Intent(AppContract.ACTION_FEEDBACK_RECORDED)
                .setPackage(context.packageName)
                .putExtra(AppContract.EXTRA_FEEDBACK, feedback.name),
        )
    }
}
