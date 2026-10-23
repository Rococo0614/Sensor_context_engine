package com.sensorcontext.engine.context

enum class ActivityLabel {
    UNKNOWN,
    STILL,
    WALKING,
    RUNNING,
    CYCLING,
    IN_VEHICLE,
    ELEVATOR,
    STAIRS,
}

enum class CarryPosture {
    UNKNOWN,
    DESK_FACE_UP,
    DESK_FACE_DOWN,
    IN_HAND,
    IN_POCKET,
    IN_BAG,
}

enum class IndoorOutdoorLabel {
    UNKNOWN,
    INDOOR,
    OUTDOOR,
    TRANSITION,
}

enum class LightLevel {
    UNKNOWN,
    BRIGHT,
    NORMAL,
    DIM,
    DARK,
}

enum class LightColor {
    UNKNOWN,
    WARM,
    NEUTRAL,
    COOL,
}

enum class InteractionEventLabel {
    PICKUP,
    PUTDOWN,
    SHAKE,
    FLIP,
}

enum class ContextSource {
    HEURISTIC,
    TFLITE,
    QNN,
    SIMULATED,
}

data class ScoredLabel<T>(
    val label: T,
    val confidence: Float,
) {
    init {
        require(confidence in 0f..1f) { "confidence must be in [0, 1]" }
    }
}

data class EnvironmentContext(
    val indoorOutdoor: ScoredLabel<IndoorOutdoorLabel>,
    val lightLevel: ScoredLabel<LightLevel>,
    val lightColor: ScoredLabel<LightColor>,
    val lux: Float?,
    val cctKelvin: Float?,
)

data class ContextSnapshot(
    val elapsedRealtimeNanos: Long,
    val wallTimeMillis: Long,
    val validUntilElapsedRealtimeNanos: Long,
    val activity: ScoredLabel<ActivityLabel>,
    val carryPosture: ScoredLabel<CarryPosture>,
    val environment: EnvironmentContext,
    val interactionEvents: Set<InteractionEventLabel>,
    val source: ContextSource,
) {
    fun stateKey(): String = buildString {
        append(activity.label.name)
        append('|')
        append(carryPosture.label.name)
        append('|')
        append(environment.indoorOutdoor.label.name)
        append('|')
        append(environment.lightLevel.label.name)
        append('|')
        append(environment.lightColor.label.name)
    }

    fun displaySummary(): String = buildString {
        append("活动: ${activity.label.name} (${activity.confidence.asPercent()})\n")
        append("携带: ${carryPosture.label.name} (${carryPosture.confidence.asPercent()})\n")
        append("明暗: ${environment.lightLevel.label.name} ")
        append("(${environment.lightLevel.confidence.asPercent()})")
        environment.lux?.let { append(" · %.1f lux".format(it)) }
        append("\n来源: ${source.name}")
    }
}

private fun Float.asPercent(): String = "%.0f%%".format(this * 100f)
