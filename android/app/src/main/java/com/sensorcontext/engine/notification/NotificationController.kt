package com.sensorcontext.engine.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.sensorcontext.engine.AppContract
import com.sensorcontext.engine.MainActivity
import com.sensorcontext.engine.R
import com.sensorcontext.engine.personalization.ExperienceDecision

class NotificationController(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        notificationManager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    context.getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "显示端侧情境感知服务的运行状态"
                    setShowBadge(false)
                },
                NotificationChannel(
                    RECOMMENDATION_CHANNEL_ID,
                    context.getString(R.string.recommendation_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "根据当前情境和用户偏好生成的建议"
                },
            ),
        )
    }

    fun buildServiceNotification(status: String): Notification =
        Notification.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sensor)
            .setContentTitle("情境感知正在运行")
            .setContentText(status)
            .setContentIntent(mainActivityPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    fun showRecommendation(decision: ExperienceDecision) {
        val accept = feedbackPendingIntent(
            action = AppContract.ACTION_FEEDBACK_ACCEPT,
            decision = decision,
            requestCode = decision.decisionId.hashCode(),
        )
        val dismiss = feedbackPendingIntent(
            action = AppContract.ACTION_FEEDBACK_DISMISS,
            decision = decision,
            requestCode = decision.decisionId.hashCode() xor 0x5f3759df,
        )

        val notification = Notification.Builder(context, RECOMMENDATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sensor)
            .setContentTitle(decision.title)
            .setContentText(decision.explanation)
            .setStyle(Notification.BigTextStyle().bigText(decision.explanation))
            .setContentIntent(mainActivityPendingIntent())
            .addAction(Notification.Action.Builder(null, "接受", accept).build())
            .addAction(Notification.Action.Builder(null, "暂不需要", dismiss).build())
            .setDeleteIntent(dismiss)
            // Opening the app is not an explicit acceptance. Keep the suggestion until the user
            // accepts it, dismisses it, or swipes it away so the feedback signal stays meaningful.
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .build()

        notificationManager.notify(RECOMMENDATION_NOTIFICATION_ID, notification)
    }

    fun cancelRecommendation() {
        notificationManager.cancel(RECOMMENDATION_NOTIFICATION_ID)
    }

    private fun mainActivityPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun feedbackPendingIntent(
        action: String,
        decision: ExperienceDecision,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, FeedbackReceiver::class.java).apply {
            this.action = action
            putExtra(AppContract.EXTRA_DECISION_ID, decision.decisionId)
            putExtra(AppContract.EXTRA_POLICY_ID, decision.policyId)
            putExtra(AppContract.EXTRA_POLICY_TITLE, decision.title)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val SERVICE_NOTIFICATION_ID = 1001
        const val RECOMMENDATION_NOTIFICATION_ID = 2001
        private const val SERVICE_CHANNEL_ID = "context_service"
        private const val RECOMMENDATION_CHANNEL_ID = "personalized_recommendations"
    }
}
