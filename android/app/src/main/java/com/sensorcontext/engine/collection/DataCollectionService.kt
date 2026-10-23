package com.sensorcontext.engine.collection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.sensorcontext.engine.R
import com.sensorcontext.engine.storage.TraceLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DataCollectionService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var pipelineThread: HandlerThread
    private lateinit var pipelineHandler: Handler
    private lateinit var notificationManager: NotificationManager
    private lateinit var traceLogger: TraceLogger

    private val sensorIds = IdentityHashMap<Sensor, Int>()
    private val activeNormalSensors = mutableListOf<Sensor>()
    private val activeTriggerSensors = mutableListOf<Sensor>()
    private var writer: BinarySessionWriter? = null
    private var outputFile: File? = null
    private var currentStatus: CollectionStatus? = null
    private var serviceInForeground = false

    private val autoStopRunnable = Runnable {
        if (writer != null) {
            publishStatus("达到预设时长，正在停止")
            stopCollection(clean = true)
            stopSelf()
        }
    }

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            val sensorId = sensorIds[event.sensor] ?: return
            val timestamp = event.timestamp
            val values = event.values.copyOf()
            val sensor = event.sensor
            pipelineHandler.post {
                writeSensorRecord(
                    elapsedRealtimeNanos = timestamp,
                    sensorId = sensorId,
                    accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                    values = values,
                )
                if (writer != null) {
                    sensorManager.requestTriggerSensor(this, sensor)
                }
            }
        }
    }

    private val flushRunnable = object : Runnable {
        override fun run() {
            val activeWriter = writer ?: return
            try {
                activeWriter.flush(syncToDisk = false)
                publishStatus("正在采集")
            } catch (exception: Exception) {
                failCollection("flush failed: ${exception.message}")
                return
            }
            pipelineHandler.postDelayed(this, FLUSH_INTERVAL_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SensorManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        traceLogger = TraceLogger(this)
        pipelineThread = HandlerThread("collection-pipeline", Process.THREAD_PRIORITY_MORE_FAVORABLE)
            .also { it.start() }
        pipelineHandler = Handler(pipelineThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CollectionContract.ACTION_START -> {
                if (!enterForeground("正在准备采集")) return START_NOT_STICKY
                val labels = labelsFromIntent(intent)
                val durationSeconds = intent.getIntExtra(
                    CollectionContract.EXTRA_DURATION_SECONDS,
                    DEFAULT_DURATION_SECONDS,
                ).coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
                pipelineHandler.post { startCollection(labels, durationSeconds) }
            }

            CollectionContract.ACTION_MARKER -> {
                val marker = intent.getStringExtra(CollectionContract.EXTRA_MARKER)
                    ?.take(MAX_LABEL_LENGTH)
                    ?: return START_NOT_STICKY
                val note = intent.getStringExtra(CollectionContract.EXTRA_NOTE)
                    ?.take(MAX_NOTE_LENGTH)
                    .orEmpty()
                val elapsedNanos = intent.getLongExtra(
                    CollectionContract.EXTRA_ELAPSED_NANOS,
                    SystemClock.elapsedRealtimeNanos(),
                )
                pipelineHandler.post {
                    if (writer == null) {
                        stopSelf(startId)
                    } else {
                        addMarker(elapsedNanos, marker, note)
                    }
                }
            }

            CollectionContract.ACTION_STOP -> {
                pipelineHandler.post {
                    stopCollection(clean = true)
                    stopSelf(startId)
                }
            }

            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (::pipelineHandler.isInitialized && pipelineThread.isAlive) {
            val stopped = CountDownLatch(1)
            pipelineHandler.post {
                stopCollection(clean = true)
                stopped.countDown()
            }
            stopped.await(2, TimeUnit.SECONDS)
            pipelineHandler.removeCallbacksAndMessages(null)
            pipelineThread.quitSafely()
        }
        isCollecting = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        val sensorId = sensorIds[event.sensor] ?: return
        writeSensorRecord(
            elapsedRealtimeNanos = event.timestamp,
            sensorId = sensorId,
            accuracy = event.accuracy,
            values = event.values,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun startCollection(labels: CollectionLabels, durationSeconds: Int) {
        if (writer != null) {
            publishStatus("已有 session 正在采集")
            return
        }

        try {
            val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            val descriptors = allSensors.mapIndexed { index, sensor ->
                sensorIds[sensor] = index
                sensor.toDescriptor(index)
            }
            val nowElapsed = SystemClock.elapsedRealtimeNanos()
            val nowWall = System.currentTimeMillis()
            val sessionId = newSessionId(labels)
            val file = File(recordingsDirectory(), "$sessionId.scelog")
            val metadata = CollectionSessionMetadata(
                sessionStartElapsedNanos = nowElapsed,
                sessionStartWallMillis = nowWall,
                sessionId = sessionId,
                labels = labels,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                device = Build.DEVICE,
                buildFingerprint = Build.FINGERPRINT,
                appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                sensors = descriptors,
            )

            writer = BinarySessionWriter(file, metadata)
            outputFile = file
            registerSelectedSensors(allSensors)
            currentStatus = CollectionStatus(
                sessionId = sessionId,
                fileName = file.name,
                recordCount = 0L,
                startedAtElapsedNanos = nowElapsed,
                activeSensorCount = activeNormalSensors.size + activeTriggerSensors.size,
            )
            isCollecting = true
            traceLogger.logServiceEvent(
                "collection_started",
                "session=$sessionId inventory=${allSensors.size} active=" +
                    (activeNormalSensors.size + activeTriggerSensors.size),
            )
            pipelineHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MILLIS)
            pipelineHandler.postDelayed(autoStopRunnable, durationSeconds * 1_000L)
            publishStatus("采集已开始 · 将在 ${durationSeconds}s 自动停止")
        } catch (exception: Exception) {
            failCollection("start failed: ${exception.message}")
        }
    }

    private fun registerSelectedSensors(allSensors: List<Sensor>) {
        val hasUncalibratedMag = allSensors.any {
            it.type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED
        }
        allSensors.filter { shouldRecord(it, hasUncalibratedMag) }.forEach { sensor ->
            if (sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
                if (sensorManager.requestTriggerSensor(triggerListener, sensor)) {
                    activeTriggerSensors += sensor
                }
                return@forEach
            }

            val samplingPeriodUs = samplingPeriodUs(sensor)
            val maxLatencyUs = if (sensor.reportingMode == Sensor.REPORTING_MODE_CONTINUOUS) {
                COLLECTION_BATCH_LATENCY_US
            } else {
                0
            }
            val registered = sensorManager.registerListener(
                this,
                sensor,
                samplingPeriodUs,
                maxLatencyUs,
                pipelineHandler,
            )
            if (registered) activeNormalSensors += sensor
        }
    }

    private fun shouldRecord(sensor: Sensor, hasUncalibratedMag: Boolean): Boolean {
        val standard = when (sensor.type) {
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_STEP_DETECTOR,
            Sensor.TYPE_STEP_COUNTER,
            Sensor.TYPE_SIGNIFICANT_MOTION,
            SENSOR_TYPE_TILT_DETECTOR,
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
            -> true

            Sensor.TYPE_MAGNETIC_FIELD -> !hasUncalibratedMag
            else -> false
        }
        if (standard) return true

        val identity = "${sensor.name} ${sensor.stringType}".lowercase(Locale.US)
        return VENDOR_SENSOR_TOKENS.any(identity::contains)
    }

    private fun samplingPeriodUs(sensor: Sensor): Int = when (sensor.type) {
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_GRAVITY,
        -> 20_000 // 50 Hz collection source; training can downsample to 25 Hz.

        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
        -> 50_000 // 20 Hz

        else -> SensorManager.SENSOR_DELAY_NORMAL
    }

    private fun addMarker(elapsedNanos: Long, marker: String, note: String) {
        val activeWriter = writer ?: return
        try {
            activeWriter.writeMarker(elapsedNanos, marker, note)
            activeWriter.flush()
            publishStatus("已标记 $marker")
        } catch (exception: Exception) {
            failCollection("marker failed: ${exception.message}")
        }
    }

    private fun writeSensorRecord(
        elapsedRealtimeNanos: Long,
        sensorId: Int,
        accuracy: Int,
        values: FloatArray,
    ) {
        val activeWriter = writer ?: return
        try {
            activeWriter.writeSensorEvent(
                elapsedRealtimeNanos,
                sensorId,
                accuracy,
                values.copyOf(),
            )
        } catch (exception: Exception) {
            failCollection("sensor write failed: ${exception.message}")
        }
    }

    private fun stopCollection(clean: Boolean) {
        pipelineHandler.removeCallbacks(flushRunnable)
        pipelineHandler.removeCallbacks(autoStopRunnable)
        sensorManager.unregisterListener(this)
        activeTriggerSensors.forEach { sensorManager.cancelTriggerSensor(triggerListener, it) }
        activeNormalSensors.clear()
        activeTriggerSensors.clear()

        val activeWriter = writer
        if (activeWriter != null) {
            try {
                if (clean) {
                    activeWriter.closeCleanly(
                        SystemClock.elapsedRealtimeNanos(),
                        System.currentTimeMillis(),
                    )
                } else {
                    activeWriter.close()
                }
                traceLogger.logServiceEvent(
                    "collection_stopped",
                    "file=${outputFile?.name} records=${activeWriter.recordCount}",
                )
            } catch (exception: Exception) {
                traceLogger.logServiceEvent(
                    "collection_close_failed",
                    exception.message ?: "unknown close error",
                )
            }
        }

        writer = null
        outputFile = null
        currentStatus = null
        sensorIds.clear()
        isCollecting = false
        publishStatus("采集已停止")
        if (serviceInForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            serviceInForeground = false
        }
    }

    private fun failCollection(message: String) {
        traceLogger.logServiceEvent("collection_error", message)
        publishStatus("采集失败：$message")
        stopCollection(clean = false)
        stopSelf()
    }

    private fun publishStatus(prefix: String) {
        val status = currentStatus
        val records = writer?.recordCount ?: status?.recordCount ?: 0L
        val durationSeconds = status?.let {
            (SystemClock.elapsedRealtimeNanos() - it.startedAtElapsedNanos) / 1_000_000_000L
        } ?: 0L
        val summary = if (status == null) {
            prefix
        } else {
            "$prefix · ${durationSeconds}s · $records 条 · ${status.activeSensorCount} 个流"
        }

        sendBroadcast(
            Intent(CollectionContract.ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(CollectionContract.EXTRA_SUMMARY, summary)
                .putExtra(CollectionContract.EXTRA_FILE_NAME, status?.fileName.orEmpty())
                .putExtra(CollectionContract.EXTRA_RECORD_COUNT, records),
        )
        if (serviceInForeground) {
            notificationManager.notify(
                COLLECTION_NOTIFICATION_ID,
                buildNotification(summary),
            )
        }
    }

    private fun enterForeground(status: String): Boolean {
        if (serviceInForeground) return true
        return try {
            val notification = buildNotification(status)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    COLLECTION_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
                )
            } else {
                startForeground(COLLECTION_NOTIFICATION_ID, notification)
            }
            serviceInForeground = true
            true
        } catch (exception: SecurityException) {
            traceLogger.logServiceEvent(
                "collection_foreground_failed",
                exception.message ?: "missing permission",
            )
            stopSelf()
            false
        }
    }

    private fun buildNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, CollectionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, COLLECTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sensor)
            .setContentTitle("传感器数据采集中")
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                COLLECTION_CHANNEL_ID,
                "传感器数据采集",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示已明确启动的数据采集 session 状态"
                setShowBadge(false)
            },
        )
    }

    private fun labelsFromIntent(intent: Intent): CollectionLabels = CollectionLabels(
        participantId = intent.getStringExtra(CollectionContract.EXTRA_PARTICIPANT)
            ?.take(MAX_LABEL_LENGTH)
            .orEmpty()
            .ifBlank { "p01" },
        activity = intent.getStringExtra(CollectionContract.EXTRA_ACTIVITY)
            ?.take(MAX_LABEL_LENGTH)
            .orEmpty()
            .ifBlank { "unknown" },
        posture = intent.getStringExtra(CollectionContract.EXTRA_POSTURE)
            ?.take(MAX_LABEL_LENGTH)
            .orEmpty()
            .ifBlank { "unknown" },
        environment = intent.getStringExtra(CollectionContract.EXTRA_ENVIRONMENT)
            ?.take(MAX_LABEL_LENGTH)
            .orEmpty()
            .ifBlank { "unknown" },
        light = intent.getStringExtra(CollectionContract.EXTRA_LIGHT)
            ?.take(MAX_LABEL_LENGTH)
            .orEmpty()
            .ifBlank { "unknown" },
        note = intent.getStringExtra(CollectionContract.EXTRA_NOTE)
            ?.take(MAX_NOTE_LENGTH)
            .orEmpty(),
    )

    private fun Sensor.toDescriptor(sensorId: Int): RecordedSensorDescriptor =
        RecordedSensorDescriptor(
            sensorId = sensorId,
            androidType = type,
            reportingMode = reportingMode,
            minDelayUs = minDelay,
            maxDelayUs = maxDelay,
            fifoMaxEventCount = fifoMaxEventCount,
            resolution = resolution,
            maxRange = maximumRange,
            isWakeUp = isWakeUpSensor,
            name = name.orEmpty(),
            vendor = vendor.orEmpty(),
            stringType = stringType.orEmpty(),
        )

    private fun recordingsDirectory(): File = File(filesDir, "recordings").apply { mkdirs() }

    private fun newSessionId(labels: CollectionLabels): String {
        val timestamp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val participant = labels.participantId
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(24)
            .ifBlank { "p00" }
        val suffix = UUID.randomUUID().toString().take(8)
        return "${timestamp}_${participant}_$suffix"
    }

    companion object {
        @Volatile
        var isCollecting: Boolean = false
            private set

        private const val COLLECTION_CHANNEL_ID = "sensor_data_collection"
        private const val COLLECTION_NOTIFICATION_ID = 3001
        private const val COLLECTION_BATCH_LATENCY_US = 1_000_000
        private const val FLUSH_INTERVAL_MILLIS = 5_000L
        private const val DEFAULT_DURATION_SECONDS = 45
        private const val MIN_DURATION_SECONDS = 10
        private const val MAX_DURATION_SECONDS = 300
        private const val MAX_LABEL_LENGTH = 64
        private const val MAX_NOTE_LENGTH = 512
        private const val SENSOR_TYPE_TILT_DETECTOR = 22

        private val VENDOR_SENSOR_TOKENS = listOf(
            "light_cct",
            "raiseup",
            "putdown",
            "vivomotion",
            "elevator",
            "car_navi",
            "vibration_classification",
            "activity_wk",
            "activity_nwk",
            "lowlight",
            "lit_up",
        )
    }
}
