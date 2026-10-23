package com.sensorcontext.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sensorcontext.engine.collection.CollectionActivity
import com.sensorcontext.engine.notification.NotificationController
import com.sensorcontext.engine.personalization.PersonalizationEngine
import com.sensorcontext.engine.personalization.SharedPreferencesPolicyStatsStore
import com.sensorcontext.engine.sensor.SensorCollectorService
import com.sensorcontext.engine.storage.RuntimeStateStore
import com.sensorcontext.engine.storage.TraceLogger

@SuppressLint("SetTextI18n", "UnspecifiedRegisterReceiverFlag")
class MainActivity : Activity() {
    private lateinit var serviceStatusView: TextView
    private lateinit var contextView: TextView
    private lateinit var decisionView: TextView
    private lateinit var statsView: TextView
    private lateinit var runtimeState: RuntimeStateStore
    private lateinit var statsStore: SharedPreferencesPolicyStatsStore
    private var receiverRegistered = false
    private var startAfterPermissionResult = false

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AppContract.ACTION_CONTEXT_UPDATED -> {
                    contextView.text = intent.getStringExtra(AppContract.EXTRA_SUMMARY)
                        ?: runtimeState.latestContextSummary
                }

                AppContract.ACTION_DECISION_UPDATED -> {
                    decisionView.text = intent.getStringExtra(AppContract.EXTRA_SUMMARY)
                        ?: runtimeState.latestDecisionSummary
                    refreshStats()
                }

                AppContract.ACTION_FEEDBACK_RECORDED -> {
                    decisionView.text = "反馈已记录：" +
                        (intent.getStringExtra(AppContract.EXTRA_FEEDBACK) ?: "UNKNOWN")
                    refreshStats()
                }
            }
            refreshServiceStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtimeState = RuntimeStateStore(this)
        statsStore = SharedPreferencesPolicyStatsStore(this)
        setContentView(buildContentView())
        refreshAll()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(AppContract.ACTION_CONTEXT_UPDATED)
            addAction(AppContract.ACTION_DECISION_UPDATED)
            addAction(AppContract.ACTION_FEEDBACK_RECORDED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(updateReceiver, filter)
        }
        receiverRegistered = true
        refreshAll()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(updateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RUNTIME_PERMISSIONS) return

        if (startAfterPermissionResult && hasActivityRecognitionPermission()) {
            startContextService()
        } else if (!hasActivityRecognitionPermission()) {
            serviceStatusView.text = "未授予身体活动权限，Android 16 不允许启动 health 前台服务。"
        }
        startAfterPermissionResult = false
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
            setBackgroundColor(Color.rgb(244, 251, 247))
        }

        root.addView(TextView(this).apply {
            text = "端侧情境与个性化引擎"
            textSize = 25f
            setTextColor(Color.rgb(20, 50, 40))
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "首版使用启发式推理打通闭环；后续通过同一接口接入 TFLite/QNN。"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(20))
        })

        root.addView(sectionTitle("服务"))
        serviceStatusView = bodyText()
        root.addView(serviceStatusView)
        root.addView(horizontalButtons(
            button("启动感知") { ensurePermissionsAndStart() },
            button("停止感知") {
                stopService(Intent(this, SensorCollectorService::class.java))
                runtimeState.serviceRunning = false
                refreshServiceStatus()
            },
        ))
        root.addView(button("打开融合数据采集") {
            startActivity(Intent(this, CollectionActivity::class.java))
        })

        root.addView(sectionTitle("当前情境"))
        contextView = cardText()
        root.addView(contextView)

        root.addView(sectionTitle("个性化决策"))
        decisionView = cardText()
        root.addView(decisionView)

        root.addView(button("调试：注入暗光手持静止情境") {
            if (!SensorCollectorService.isServiceRunning) {
                serviceStatusView.text = "请先启动感知服务，再注入调试情境。"
                return@button
            }
            startService(
                Intent(this, SensorCollectorService::class.java)
                    .setAction(AppContract.ACTION_SIMULATE_READING_CONTEXT),
            )
        })

        root.addView(sectionTitle("用户偏好统计"))
        statsView = bodyText()
        root.addView(statsView)
        root.addView(horizontalButtons(
            button("刷新") { refreshAll() },
            button("重置偏好") {
                statsStore.resetAll()
                NotificationController(this).cancelRecommendation()
                runtimeState.latestDecisionSummary = "个性化偏好已重置"
                refreshAll()
            },
        ))

        root.addView(sectionTitle("本地审计日志"))
        root.addView(bodyText().apply {
            text = TraceLogger(this@MainActivity).absolutePath()
            setTextIsSelectable(true)
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun ensurePermissionsAndStart() {
        val missingPermissions = mutableListOf<String>()
        if (!hasActivityRecognitionPermission()) {
            missingPermissions += Manifest.permission.ACTIVITY_RECOGNITION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missingPermissions += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missingPermissions.isNotEmpty()) {
            startAfterPermissionResult = true
            requestPermissions(missingPermissions.toTypedArray(), REQUEST_RUNTIME_PERMISSIONS)
        } else {
            startContextService()
        }
    }

    private fun hasActivityRecognitionPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startContextService() {
        try {
            startForegroundService(Intent(this, SensorCollectorService::class.java))
            serviceStatusView.text = "正在启动……"
        } catch (exception: SecurityException) {
            serviceStatusView.text = "服务启动失败：${exception.message}"
        }
    }

    private fun refreshAll() {
        refreshServiceStatus()
        contextView.text = runtimeState.latestContextSummary
        decisionView.text = runtimeState.latestDecisionSummary
        refreshStats()
    }

    private fun refreshServiceStatus() {
        val running = SensorCollectorService.isServiceRunning
        runtimeState.serviceRunning = running
        serviceStatusView.text = if (running) {
            "运行中 · 原始传感器仅保存在内存"
        } else {
            "未运行"
        }
    }

    private fun refreshStats() {
        val stats = statsStore.getStats(PersonalizationEngine.READING_ASSIST_POLICY_ID)
        statsView.text = buildString {
            append("策略: ${PersonalizationEngine.READING_ASSIST_POLICY_ID}\n")
            append("展示 ${stats.shown} · 接受 ${stats.accepted} · 拒绝 ${stats.dismissed}")
            append(" · 撤销 ${stats.reverted}\n")
            append("连续拒绝 ${stats.consecutiveDismissals} · 接受率 ")
            append("%.0f%%".format(stats.acceptanceRate * 100f))
            if (stats.consecutiveDismissals >=
                PersonalizationEngine.DISMISSAL_SUPPRESSION_THRESHOLD
            ) {
                append("\n当前策略已因连续拒绝而暂停")
            }
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.rgb(0, 95, 67))
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun bodyText(): TextView = TextView(this).apply {
        textSize = 15f
        setTextColor(Color.rgb(40, 55, 50))
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun cardText(): TextView = bodyText().apply {
        setBackgroundColor(Color.WHITE)
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun horizontalButtons(vararg buttons: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { button ->
                addView(
                    button,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_RUNTIME_PERMISSIONS = 100
    }
}
