package com.sensorcontext.engine.sensor

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.sensorcontext.engine.AppContract
import com.sensorcontext.engine.context.ActivityLabel
import com.sensorcontext.engine.context.CarryPosture
import com.sensorcontext.engine.context.ContextSnapshot
import com.sensorcontext.engine.context.ContextSource
import com.sensorcontext.engine.context.EnvironmentContext
import com.sensorcontext.engine.context.IndoorOutdoorLabel
import com.sensorcontext.engine.context.LightColor
import com.sensorcontext.engine.context.LightLevel
import com.sensorcontext.engine.context.ScoredLabel
import com.sensorcontext.engine.inference.ChangeGate
import com.sensorcontext.engine.inference.ContextInferenceEngine
import com.sensorcontext.engine.inference.ContextStabilizer
import com.sensorcontext.engine.inference.HeuristicContextInferenceEngine
import com.sensorcontext.engine.notification.NotificationController
import com.sensorcontext.engine.personalization.PersonalizationEngine
import com.sensorcontext.engine.personalization.SharedPreferencesPolicyStatsStore
import com.sensorcontext.engine.storage.RuntimeStateStore
import com.sensorcontext.engine.storage.TraceLogger

class SensorCollectorService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var pipelineThread: HandlerThread
    private lateinit var pipelineHandler: Handler
    private lateinit var notificationController: NotificationController
    private lateinit var traceLogger: TraceLogger
    private lateinit var runtimeState: RuntimeStateStore

    private val windowBuffer = SensorWindowBuffer()
    private val changeGate = ChangeGate()
    private val stabilizer = ContextStabilizer()
    private val inferenceEngine: ContextInferenceEngine = HeuristicContextInferenceEngine()
    private lateinit var personalizationEngine: PersonalizationEngine

    private var accelerationSensor: Sensor? = null
    private var lightSensor: Sensor? = null
    private var proximitySensor: Sensor? = null
    private var lastPipelineScheduleNanos = Long.MIN_VALUE
    private var evaluationScheduled = false
    private var pendingForcedReason: String? = null

    private val evaluationRunnable = Runnable {
        evaluationScheduled = false
        evaluateCurrentWindow()
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SensorManager::class.java)
        notificationController = NotificationController(this).also { it.createChannels() }
        traceLogger = TraceLogger(this)
        runtimeState = RuntimeStateStore(this)
        personalizationEngine = PersonalizationEngine(SharedPreferencesPolicyStatsStore(this))

        pipelineThread = HandlerThread("sensor-pipeline", Process.THREAD_PRIORITY_MORE_FAVORABLE)
            .also { it.start() }
        pipelineHandler = Handler(pipelineThread.looper)

        if (!enterForeground()) return
        isServiceRunning = true
        runtimeState.serviceRunning = true
        traceLogger.logServiceEvent("created", "foreground service started")
        pipelineHandler.post(::registerSensors)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AppContract.ACTION_SIMULATE_READING_CONTEXT -> {
                pipelineHandler.post(::injectReadingContext)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        pipelineHandler.removeCallbacksAndMessages(null)
        pipelineThread.quitSafely()
        windowBuffer.clear()
        isServiceRunning = false
        runtimeState.serviceRunning = false
        traceLogger.logServiceEvent("destroyed", "foreground service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                if (event.values.size < 3) return
                windowBuffer.addAcceleration(
                    Vector3Sample(
                        elapsedRealtimeNanos = event.timestamp,
                        x = event.values[0],
                        y = event.values[1],
                        z = event.values[2],
                    ),
                )
                scheduleEvaluation()
            }

            Sensor.TYPE_LIGHT -> {
                if (event.values.isEmpty()) return
                windowBuffer.updateLight(ScalarSample(event.timestamp, event.values[0]))
                // This device reports small lux fluctuations several times per second even though
                // the sensor is advertised as on-change. Let ChangeGate compare light buckets
                // after the normal pipeline interval instead of waking Stage 1 for every sample.
                scheduleEvaluation()
            }

            Sensor.TYPE_PROXIMITY -> {
                if (event.values.isEmpty()) return
                windowBuffer.updateProximity(
                    ProximitySample(
                        elapsedRealtimeNanos = event.timestamp,
                        distanceCentimeters = event.values[0],
                        maxRangeCentimeters = event.sensor.maximumRange,
                    ),
                )
                scheduleEvaluation("proximity_event")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun enterForeground(): Boolean {
        val notification = notificationController.buildServiceNotification("正在初始化传感器")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationController.SERVICE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
                )
            } else {
                startForeground(NotificationController.SERVICE_NOTIFICATION_ID, notification)
            }
            true
        } catch (securityException: SecurityException) {
            traceLogger.logServiceEvent(
                "foreground_failed",
                securityException.message ?: "missing foreground service prerequisite",
            )
            runtimeState.serviceRunning = false
            stopSelf()
            false
        }
    }

    private fun registerSensors() {
        accelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        val accelerationRegistered = accelerationSensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                ACCELERATION_PERIOD_US,
                ACCELERATION_BATCH_LATENCY_US,
                pipelineHandler,
            )
        } ?: false

        if (!accelerationRegistered && accelerationSensor != null) {
            sensorManager.registerListener(
                this,
                accelerationSensor,
                ACCELERATION_PERIOD_US,
                pipelineHandler,
            )
        }

        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, pipelineHandler)
        }
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, pipelineHandler)
        }

        val capability = buildString {
            append("accel=${accelerationSensor?.name ?: "missing"}; ")
            append("light=${lightSensor?.name ?: "missing"}; ")
            append("proximity=${proximitySensor?.name ?: "missing"}; ")
            append("batching=$accelerationRegistered")
        }
        traceLogger.logServiceEvent("sensors_registered", capability)

        val status = if (accelerationSensor == null) {
            "缺少加速度计，无法运行"
        } else {
            "25 Hz 加速度计 · 光照/接近按变化采集"
        }
        updateServiceNotification(status)
    }

    private fun scheduleEvaluation(forcedReason: String? = null) {
        if (forcedReason != null) pendingForcedReason = forcedReason

        val now = SystemClock.elapsedRealtimeNanos()
        val intervalPassed = lastPipelineScheduleNanos == Long.MIN_VALUE ||
            now - lastPipelineScheduleNanos >= PIPELINE_SCHEDULE_INTERVAL_NANOS
        if (!intervalPassed && forcedReason == null) return
        if (evaluationScheduled) return

        lastPipelineScheduleNanos = now
        evaluationScheduled = true
        pipelineHandler.postDelayed(evaluationRunnable, BATCH_DRAIN_DELAY_MILLIS)
    }

    private fun evaluateCurrentWindow() {
        val window = windowBuffer.snapshot() ?: return
        if (window.acceleration.size < MIN_WINDOW_SAMPLES) return

        val forcedReason = pendingForcedReason
        pendingForcedReason = null
        val gateDecision = changeGate.evaluate(
            window = window,
            nowElapsedNanos = SystemClock.elapsedRealtimeNanos(),
            forcedReason = forcedReason,
        )
        traceLogger.logGate(gateDecision.shouldInfer, gateDecision.reason)
        if (!gateDecision.shouldInfer) return

        val rawContext = inferenceEngine.infer(window)
        val stabilization = stabilizer.update(rawContext)
        traceLogger.logContext(
            stabilization.snapshot,
            stabilization.stateChanged,
            stabilization.candidatePending,
        )
        publishContext(stabilization.snapshot)
        evaluatePersonalization(stabilization.snapshot, bypassCooldown = false)
    }

    private fun injectReadingContext() {
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        val context = ContextSnapshot(
            elapsedRealtimeNanos = nowElapsed,
            wallTimeMillis = System.currentTimeMillis(),
            validUntilElapsedRealtimeNanos = nowElapsed + 30_000_000_000L,
            activity = ScoredLabel(ActivityLabel.STILL, 0.99f),
            carryPosture = ScoredLabel(CarryPosture.IN_HAND, 0.99f),
            environment = EnvironmentContext(
                indoorOutdoor = ScoredLabel(IndoorOutdoorLabel.INDOOR, 0.95f),
                lightLevel = ScoredLabel(LightLevel.DARK, 0.99f),
                lightColor = ScoredLabel(LightColor.UNKNOWN, 0f),
                lux = 3f,
                cctKelvin = null,
            ),
            interactionEvents = emptySet(),
            source = ContextSource.SIMULATED,
        )
        traceLogger.logContext(context, stateChanged = true, candidatePending = false)
        publishContext(context)
        evaluatePersonalization(context, bypassCooldown = true)
    }

    private fun evaluatePersonalization(context: ContextSnapshot, bypassCooldown: Boolean) {
        val result = personalizationEngine.evaluate(context, bypassCooldown)
        traceLogger.logPersonalization(result.reason, result.decision)

        val summary = result.decision?.let {
            "${it.title}\n${it.explanation}\n置信度 %.0f%%".format(it.confidence * 100f)
        } ?: "本次无建议：${result.reason}"
        runtimeState.latestDecisionSummary = summary
        sendBroadcast(
            Intent(AppContract.ACTION_DECISION_UPDATED)
                .setPackage(packageName)
                .putExtra(AppContract.EXTRA_SUMMARY, summary),
        )
        result.decision?.let(notificationController::showRecommendation)
    }

    private fun publishContext(context: ContextSnapshot) {
        val summary = context.displaySummary()
        runtimeState.latestContextSummary = summary
        sendBroadcast(
            Intent(AppContract.ACTION_CONTEXT_UPDATED)
                .setPackage(packageName)
                .putExtra(AppContract.EXTRA_SUMMARY, summary),
        )
        updateServiceNotification(
            "${context.activity.label.name} · ${context.carryPosture.label.name} · " +
                context.environment.lightLevel.label.name,
        )
    }

    private fun updateServiceNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(
            NotificationController.SERVICE_NOTIFICATION_ID,
            notificationController.buildServiceNotification(status),
        )
    }

    companion object {
        @Volatile
        var isServiceRunning: Boolean = false
            private set

        private const val ACCELERATION_PERIOD_US = 40_000
        private const val ACCELERATION_BATCH_LATENCY_US = 5_000_000
        private const val MIN_WINDOW_SAMPLES = 25
        private const val BATCH_DRAIN_DELAY_MILLIS = 150L
        private const val PIPELINE_SCHEDULE_INTERVAL_NANOS = 4_500_000_000L
    }
}
