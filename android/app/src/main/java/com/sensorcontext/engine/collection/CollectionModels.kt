package com.sensorcontext.engine.collection

data class CollectionLabels(
    val participantId: String,
    val activity: String,
    val posture: String,
    val environment: String,
    val light: String,
    val note: String,
)

data class RecordedSensorDescriptor(
    val sensorId: Int,
    val androidType: Int,
    val reportingMode: Int,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val fifoMaxEventCount: Int,
    val resolution: Float,
    val maxRange: Float,
    val isWakeUp: Boolean,
    val name: String,
    val vendor: String,
    val stringType: String,
)

data class CollectionSessionMetadata(
    val sessionStartElapsedNanos: Long,
    val sessionStartWallMillis: Long,
    val sessionId: String,
    val labels: CollectionLabels,
    val manufacturer: String,
    val model: String,
    val device: String,
    val buildFingerprint: String,
    val appVersion: String,
    val sensors: List<RecordedSensorDescriptor>,
)

data class CollectionStatus(
    val sessionId: String,
    val fileName: String,
    val recordCount: Long,
    val startedAtElapsedNanos: Long,
    val activeSensorCount: Int,
)
