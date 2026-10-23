package com.sensorcontext.engine.fusion

object FusionSchema {
    const val VERSION = 1

    // Generated from training.sce_data.fusion_dataset. A model must reject a different hash.
    const val HASH = "52a7299a08ae8313"

    val activityLabels = listOf("still", "walking", "running", "in_vehicle")
    val carryLabels = listOf(
        "desk_faceup",
        "desk_facedown",
        "in_hand",
        "in_pocket",
        "in_bag",
    )
    val continuousChannels = listOf(
        "accel_x",
        "accel_y",
        "accel_z",
        "gyro_x",
        "gyro_y",
        "gyro_z",
        "mag_x",
        "mag_y",
        "mag_z",
        "gravity_x",
        "gravity_y",
        "gravity_z",
    )
    val environmentFeatures = listOf(
        "log_lux",
        "cct_norm",
        "proximity_distance",
        "proximity_near",
        "lux_age_s",
        "cct_age_s",
        "proximity_age_s",
        "lux_freshness",
        "cct_freshness",
        "proximity_freshness",
        "lux_valid",
        "cct_valid",
        "proximity_valid",
    )
    val eventFeatures = buildList {
        add("step_delta")
        add("step_rate_hz")
        add("time_since_step_s")
        listOf(
            "step_detector",
            "significant_motion",
            "tilt",
            "vendor_raiseup",
            "vendor_putdown",
            "vendor_car",
            "vendor_activity",
        ).forEach { identity ->
            add("${identity}_in_window")
            add("${identity}_age_s")
            add("${identity}_available")
        }
    }
    val motionQualityFeatures = buildList {
        listOf("accel", "gyro", "mag", "gravity").forEach { channel ->
            add("${channel}_available")
            add("${channel}_sample_ratio")
            add("${channel}_period_ratio")
            add("${channel}_max_gap_s")
            add("${channel}_saturation_ratio")
        }
        add("mag_field_mean_norm")
        add("mag_field_std_norm")
        add("mag_interference_score")
        add("mag_spike_ratio")
    }
}
