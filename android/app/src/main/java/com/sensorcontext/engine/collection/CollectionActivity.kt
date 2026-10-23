package com.sensorcontext.engine.collection

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
import android.os.SystemClock
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.sensorcontext.engine.sensor.SensorCollectorService
import java.io.File

@SuppressLint("SetTextI18n", "UnspecifiedRegisterReceiverFlag")
class CollectionActivity : Activity() {
    private lateinit var participantInput: EditText
    private lateinit var activitySpinner: Spinner
    private lateinit var postureSpinner: Spinner
    private lateinit var environmentSpinner: Spinner
    private lateinit var lightSpinner: Spinner
    private lateinit var durationSpinner: Spinner
    private lateinit var noteInput: EditText
    private lateinit var statusView: TextView
    private lateinit var filesView: TextView
    private var receiverRegistered = false
    private var startAfterPermissionResult = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            statusView.text = intent.getStringExtra(CollectionContract.EXTRA_SUMMARY)
                ?: "状态已更新"
            refreshFiles()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        refreshStatus()
        refreshFiles()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CollectionContract.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        receiverRegistered = true
        refreshStatus()
        refreshFiles()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
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
        if (requestCode != REQUEST_PERMISSIONS) return
        if (startAfterPermissionResult && hasActivityRecognitionPermission()) {
            startCollection()
        } else {
            statusView.text = "缺少身体活动权限，无法启动采集前台服务。"
        }
        startAfterPermissionResult = false
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(36))
            setBackgroundColor(Color.rgb(247, 249, 252))
        }
        root.addView(TextView(this).apply {
            text = "融合算法数据采集"
            textSize = 25f
            setTextColor(Color.rgb(20, 45, 70))
        })
        root.addView(TextView(this).apply {
            text = "一个 session 只保持一组 Activity × Carry 标签；请按 v2 交叉采集协议执行。"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(14))
        })
        if (Build.MANUFACTURER.equals("vivo", ignoreCase = true)) {
            root.addView(button("打开 vivo 后台耗电设置") {
                try {
                    startActivity(Intent(VIVO_BACKGROUND_POWER_ACTION))
                } catch (exception: Exception) {
                    statusView.text = "无法打开后台耗电设置：${exception.message}"
                }
            })
            root.addView(TextView(this).apply {
                text = "息屏采集前，请将本 App 设为“允许后台耗电”，否则系统可能冻结传感器回调。"
                textSize = 13f
                setTextColor(Color.rgb(160, 70, 20))
            })
        }

        participantInput = editText("匿名 participant，例如 p01", "p01")
        root.addView(fieldLabel("Participant"))
        root.addView(participantInput)

        activitySpinner = spinner(ACTIVITY_OPTIONS)
        postureSpinner = spinner(POSTURE_OPTIONS)
        environmentSpinner = spinner(ENVIRONMENT_OPTIONS)
        lightSpinner = spinner(LIGHT_OPTIONS)
        durationSpinner = spinner(DURATION_OPTIONS)
        root.addView(fieldLabel("Activity"))
        root.addView(activitySpinner)
        root.addView(fieldLabel("Carry posture"))
        root.addView(postureSpinner)
        root.addView(fieldLabel("Environment"))
        root.addView(environmentSpinner)
        root.addView(fieldLabel("Light"))
        root.addView(lightSpinner)
        root.addView(fieldLabel("自动停止时长"))
        root.addView(durationSpinner)

        noteInput = editText("可选：地点、路线或异常说明", "")
        root.addView(fieldLabel("Session note"))
        root.addView(noteInput)

        statusView = cardText("未采集")
        root.addView(fieldLabel("状态"))
        root.addView(statusView)
        root.addView(horizontalButtons(
            button("开始 session") { ensurePermissionsAndStart() },
            button("干净停止") { stopCollection() },
        ))

        root.addView(fieldLabel("事件打点"))
        root.addView(horizontalButtons(
            button("pickup") { sendMarker("pickup") },
            button("putdown") { sendMarker("putdown") },
        ))
        root.addView(horizontalButtons(
            button("shake") { sendMarker("shake") },
            button("flip") { sendMarker("flip") },
        ))

        root.addView(fieldLabel("已保存 session"))
        filesView = cardText("暂无文件")
        filesView.setTextIsSelectable(true)
        root.addView(filesView)
        root.addView(button("刷新文件列表") { refreshFiles() })

        root.addView(TextView(this).apply {
            text = "建议开始后 3 秒内息屏；导出和交叉采集矩阵见 docs/rapid_validation_protocol.md。"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(16), 0, 0)
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun ensurePermissionsAndStart() {
        val missing = mutableListOf<String>()
        if (!hasActivityRecognitionPermission()) {
            missing += Manifest.permission.ACTIVITY_RECOGNITION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) {
            startAfterPermissionResult = true
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            startCollection()
        }
    }

    private fun startCollection() {
        if (DataCollectionService.isCollecting) {
            statusView.text = "已有 session 正在采集，请先停止。"
            return
        }
        if (SensorCollectorService.isServiceRunning) {
            stopService(Intent(this, SensorCollectorService::class.java))
        }

        val activity = activitySpinner.selectedItem.toString()
        val posture = postureSpinner.selectedItem.toString()
        val validationError = validateLabelCombination(activity, posture)
        if (validationError != null) {
            statusView.text = validationError
            return
        }

        val intent = Intent(this, DataCollectionService::class.java).apply {
            action = CollectionContract.ACTION_START
            putExtra(CollectionContract.EXTRA_PARTICIPANT, participantInput.text.toString())
            putExtra(CollectionContract.EXTRA_ACTIVITY, activity)
            putExtra(CollectionContract.EXTRA_POSTURE, posture)
            putExtra(
                CollectionContract.EXTRA_ENVIRONMENT,
                environmentSpinner.selectedItem.toString(),
            )
            putExtra(CollectionContract.EXTRA_LIGHT, lightSpinner.selectedItem.toString())
            putExtra(
                CollectionContract.EXTRA_DURATION_SECONDS,
                durationSpinner.selectedItem.toString().toInt(),
            )
            putExtra(CollectionContract.EXTRA_NOTE, noteInput.text.toString())
        }
        try {
            startForegroundService(intent)
            statusView.text = "正在启动采集……"
        } catch (exception: SecurityException) {
            statusView.text = "启动失败：${exception.message}"
        }
    }

    private fun validateLabelCombination(activity: String, posture: String): String? {
        if (activity == "unknown" || posture == "unknown") {
            return "训练采集不能使用 unknown 标签。"
        }
        if (posture in setOf("desk_faceup", "desk_facedown") && activity != "still") {
            return "桌面姿态只允许与 still 组合，避免无效标签。"
        }
        return null
    }

    private fun stopCollection() {
        if (!DataCollectionService.isCollecting) {
            statusView.text = "当前没有活动 session。"
            return
        }
        startService(
            Intent(this, DataCollectionService::class.java)
                .setAction(CollectionContract.ACTION_STOP),
        )
        statusView.text = "正在写入结束帧……"
    }

    private fun sendMarker(marker: String) {
        if (!DataCollectionService.isCollecting) {
            statusView.text = "请先开始 session。"
            return
        }
        startService(
            Intent(this, DataCollectionService::class.java).apply {
                action = CollectionContract.ACTION_MARKER
                putExtra(CollectionContract.EXTRA_MARKER, marker)
                putExtra(CollectionContract.EXTRA_NOTE, noteInput.text.toString())
                putExtra(CollectionContract.EXTRA_ELAPSED_NANOS, SystemClock.elapsedRealtimeNanos())
            },
        )
    }

    private fun refreshStatus() {
        statusView.text = if (DataCollectionService.isCollecting) {
            "采集中；详细计数将在下一次状态广播更新。"
        } else {
            "未采集"
        }
    }

    private fun refreshFiles() {
        val directory = File(filesDir, "recordings")
        val files = directory.listFiles { file -> file.extension == "scelog" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        filesView.text = if (files.isEmpty()) {
            "暂无文件"
        } else {
            files.take(20).joinToString("\n") { file ->
                "${file.name} · ${formatBytes(file.length())}"
            } + if (files.size > 20) "\n……共 ${files.size} 个" else ""
        }
    }

    private fun hasActivityRecognitionPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun spinner(options: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@CollectionActivity,
            android.R.layout.simple_spinner_dropdown_item,
            options,
        )
    }

    private fun editText(hint: String, initial: String): EditText = EditText(this).apply {
        this.hint = hint
        setText(initial)
        setSingleLine(true)
    }

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(Color.rgb(20, 75, 115))
        setPadding(0, dp(16), 0, dp(5))
    }

    private fun cardText(initial: String): TextView = TextView(this).apply {
        text = initial
        textSize = 14f
        setTextColor(Color.rgb(35, 45, 55))
        setBackgroundColor(Color.WHITE)
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
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

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
        else -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_PERMISSIONS = 201
        const val VIVO_BACKGROUND_POWER_ACTION =
            "com.iqoo.powersaving.battery.high.power.jump"
        val ACTIVITY_OPTIONS = listOf(
            "still",
            "walking",
            "running",
            "in_vehicle",
            "unknown",
        )
        val POSTURE_OPTIONS = listOf(
            "in_hand",
            "in_pocket",
            "in_bag",
            "desk_faceup",
            "desk_facedown",
            "unknown",
        )
        val ENVIRONMENT_OPTIONS = listOf("indoor", "outdoor", "transition", "unknown")
        val LIGHT_OPTIONS = listOf("normal", "bright", "dim", "dark", "unknown")
        val DURATION_OPTIONS = listOf("45", "30", "60", "90", "120")
    }
}
